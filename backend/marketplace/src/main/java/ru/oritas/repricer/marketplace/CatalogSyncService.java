package ru.oritas.repricer.marketplace;

import com.google.gson.JsonObject;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

/** Durable page traversal and staging; only a complete validated traversal replaces the catalog. */
@Service
public final class CatalogSyncService implements JobHandler {
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ConnectionService connections;
  private final OutboundGateway gateway;
  private final CapabilityService capabilities;
  private final StoredFileService files;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final IdempotencyService idempotency;
  private final AuditService audit;
  private final OutboxService outbox;
  private final Clock clock;
  private final SourceSnapshotPublisher snapshots;
  private final CategoryService categories;

  public CatalogSyncService(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      OutboundGateway gateway,
      CapabilityService capabilities,
      StoredFileService files,
      JobRuntime jobs,
      JsonCodec json,
      IdempotencyService idempotency,
      AuditService audit,
      OutboxService outbox,
      Clock clock,
      SourceSnapshotPublisher snapshots,
      CategoryService categories) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.connections = connections;
    this.gateway = gateway;
    this.capabilities = capabilities;
    this.files = files;
    this.jobs = jobs;
    this.json = json;
    this.idempotency = idempotency;
    this.audit = audit;
    this.outbox = outbox;
    this.clock = clock;
    this.snapshots = snapshots;
    this.categories = categories;
  }

  public UUID request(Scope scope, String source, UUID requestId) {
    if (!Set.of("CATALOG", "PRICES", "STOCKS", "PROMOTIONS", "CATEGORIES", "ALL")
        .contains(source)) {
      throw new BusinessException("UNSUPPORTED_SOURCE", 422, "Неизвестный источник синхронизации");
    }
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "sync.request");
          connections.current(scope);
          return idempotency.execute(
              scope,
              "sync.request",
              requestId,
              source,
              UUID.class,
              () -> {
                jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
                    .param("id", scope.requireAccount())
                    .query(UUID.class)
                    .single();
                List<String> family =
                    Set.of("CATALOG", "PRICES", "ALL").contains(source)
                        ? List.of("CATALOG", "PRICES", "ALL")
                        : List.of(source);
                var active =
                    jdbc.sql(
                            """
                            SELECT job_id FROM marketplace_sync_run WHERE source_type IN (:family)
                              AND state IN ('FETCHING','PARSING','VALIDATING') ORDER BY started_at LIMIT 1
                            """)
                        .param("family", family)
                        .query(UUID.class)
                        .optional();
                if (active.isPresent()) {
                  return active.orElseThrow();
                }
                outbox.requireHeavyAdmission();
                UUID run = UUID.randomUUID();
                String phase =
                    switch (source) {
                      case "PRICES" -> "PRICES";
                      case "STOCKS" -> "DISCOVERY";
                      case "PROMOTIONS" -> "PROMOS";
                      case "CATEGORIES" -> "TREE";
                      default -> "CATALOG";
                    };
                jdbc.sql(
                        """
                        INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state,phase)
                        VALUES (:id,:org,:account,:source,'FETCHING',:phase)
                        """)
                    .param("id", run)
                    .param("org", scope.organizationId())
                    .param("account", scope.accountId())
                    .param("source", source)
                    .param("phase", phase)
                    .update();
                if (source.equals("PRICES")) {
                  jdbc.sql(
                          """
                          INSERT INTO marketplace_offer_stage(organization_id,account_id,run_id,external_id,
                            sku,vendor_sku,name,image_url,seller_price,buyer_price,archived,info_complete,commercial_fields,
                            catalog_fields,supplier_price_data,attributes_complete)
                          SELECT organization_id,account_id,:run,external_id,sku,vendor_sku,name,image_url,
                            seller_price,buyer_price,archived,true,commercial_fields,
                            catalog_fields,supplier_price_data,true
                          FROM marketplace_offer
                          """)
                      .param("run", run)
                      .update();
                }
                String handler =
                    switch (source) {
                      case "STOCKS" -> "STOCK_SYNC";
                      case "PROMOTIONS" -> "PROMOTION_SYNC";
                      case "CATEGORIES" -> "CATEGORY_SYNC";
                      default -> type();
                    };
                UUID job =
                    jobs.submit(scope, handler, run.toString(), json.encode(new SyncJob(run)));
                jdbc.sql("UPDATE marketplace_sync_run SET job_id=:job WHERE id=:run")
                    .param("job", job)
                    .param("run", run)
                    .update();
                jdbc.sql(
                        """
                        INSERT INTO marketplace_source(id,organization_id,account_id,source_type,status)
                        VALUES (:id,:org,:account,:source,'SYNCING')
                        ON CONFLICT(organization_id,account_id,source_type) DO UPDATE SET status='SYNCING',reason=NULL
                        """)
                    .param("id", UUID.randomUUID())
                    .param("org", scope.organizationId())
                    .param("account", scope.accountId())
                    .param("source", source)
                    .update();
                audit.record(scope, "sync.requested", run, "source=" + source);
                return job;
              });
        });
  }

  /**
   * An older active traversal cannot satisfy evidence required after a confirmed external effect.
   */
  boolean requestAfterReadback(JobContext context, String source, Instant confirmedAt) {
    if (!Set.of("PRICES", "PROMOTIONS").contains(source) || confirmedAt == null) {
      throw new IllegalArgumentException("Invalid confirmed source refresh");
    }
    return transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          authorization.requireLocked(context.scope(), Set.of("sync.request"));
          jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
              .param("id", context.scope().requireAccount())
              .query(UUID.class)
              .single();
          List<String> family =
              source.equals("PRICES") ? List.of("CATALOG", "PRICES", "ALL") : List.of("PROMOTIONS");
          boolean earlierTraversal =
              jdbc.sql(
                      """
                      SELECT EXISTS(SELECT 1 FROM marketplace_sync_run WHERE source_type IN (:family)
                        AND state IN ('FETCHING','PARSING','VALIDATING') AND started_at<:confirmed)
                      """)
                  .param("family", family)
                  .param("confirmed", Timestamp.from(confirmedAt))
                  .query(Boolean.class)
                  .single();
          if (earlierTraversal) {
            return false;
          }
          UUID requestId =
              UUID.nameUUIDFromBytes(
                  (context.id() + ":" + source).getBytes(StandardCharsets.UTF_8));
          request(context.scope(), source, requestId);
          jobs.requireOwnership(context);
          return true;
        });
  }

  public UUID requestHistory(Scope scope, LocalDate from, LocalDate until, UUID requestId) {
    if (from == null
        || until == null
        || until.isBefore(from)
        || from.plusDays(29).isBefore(until)) {
      throw new BusinessException(
          "INVALID_HISTORY_PERIOD", 422, "Выберите период не более 30 дней");
    }
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "sync.request");
          authorization.require(scope, "finance.read");
          connections.current(scope);
          return idempotency.execute(
              scope,
              "history.request",
              requestId,
              new HistoryPeriod(from, until),
              UUID.class,
              () -> {
                outbox.requireHeavyAdmission();
                UUID run = UUID.randomUUID();
                jdbc.sql(
                        """
                        INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state,
                          phase,range_from,range_until)
                        VALUES (:id,:org,:account,'HISTORY','FETCHING','BUSINESS',:from,:until)
                        """)
                    .param("id", run)
                    .param("org", scope.organizationId())
                    .param("account", scope.accountId())
                    .param("from", from)
                    .param("until", until)
                    .update();
                UUID job =
                    jobs.submit(
                        scope, "HISTORY_SYNC", run.toString(), json.encode(new SyncJob(run)));
                jdbc.sql("UPDATE marketplace_sync_run SET job_id=:job WHERE id=:run")
                    .param("job", job)
                    .param("run", run)
                    .update();
                jdbc.sql(
                        """
                        INSERT INTO marketplace_source(id,organization_id,account_id,source_type,status)
                        VALUES (:id,:org,:account,'HISTORY','SYNCING')
                        ON CONFLICT(organization_id,account_id,source_type) DO UPDATE SET status='SYNCING',reason=NULL
                        """)
                    .param("id", UUID.randomUUID())
                    .param("org", scope.organizationId())
                    .param("account", scope.accountId())
                    .update();
                audit.record(scope, "history.requested", run, "from=" + from + ";until=" + until);
                return job;
              });
        });
  }

  @Override
  public String type() {
    return "CATALOG_SYNC";
  }

  @Override
  public String lane() {
    return "fetch";
  }

  @Override
  public JobOutcome execute(JobContext context) throws Exception {
    UUID id = json.decode(context.payload(), SyncJob.class).runId();
    Run run =
        transactions.run(
            context.scope(),
            () -> {
              jobs.requireOwnership(context);
              authorization.require(context.scope(), "sync.request");
              return run(id);
            });
    if (run.state().equals("PUBLISHED")) {
      return JobOutcome.succeeded(json.encode(new Published(id)));
    }
    if (Set.of("FAILED", "INCOMPLETE").contains(run.state())) {
      return JobOutcome.blocked("SYNC_INCOMPLETE");
    }
    if (run.page() >= 10000 || run.startedAt().plusSeconds(1800).isBefore(clock.instant())) {
      fail(context.scope(), id, "TRAVERSAL_LIMIT");
      return JobOutcome.blocked("TRAVERSAL_LIMIT");
    }
    try {
      ConnectionService.Credentials credentials =
          transactions.run(context.scope(), () -> connections.current(context.scope()));
      if (run.phase().equals("PUBLISH")) {
        if (!context.lane().equals("canonicalization")) {
          return JobOutcome.handoff("canonicalization", clock.instant());
        }
        return transactions.run(context.scope(), () -> publish(context, run));
      }
      var previous =
          transactions.run(
              context.scope(),
              () ->
                  jdbc.sql(
                          """
                          SELECT file_id,content_encoding FROM marketplace_raw_page WHERE run_id=:run AND page_number=:page
                          """)
                      .param("run", id)
                      .param("page", run.page())
                      .query(
                          (row, index) ->
                              new RawPage(
                                  row.getObject("file_id", UUID.class),
                                  row.getString("content_encoding")))
                      .optional());
      String requiredLane = previous.isPresent() ? "canonicalization" : "fetch";
      if (!context.lane().equals(requiredLane)) {
        return JobOutcome.handoff(requiredLane, clock.instant());
      }
      Request request =
          requestFor(context.scope(), run, credentials.marketplace(), previous.isEmpty());
      OutboundGateway.Response response;
      if (previous.isPresent()) {
        RawPage raw = previous.orElseThrow();
        response =
            new OutboundGateway.Response(
                null,
                200,
                transactions.run(context.scope(), () -> files.get(raw.id())),
                raw.encoding(),
                null,
                credentials.revision());
      } else {
        response =
            gateway.executePage(
                context.scope(),
                id,
                run.page(),
                request.method(),
                request.body(),
                null,
                run.cursor(),
                request.elements());
        if (response.retryAt() != null) {
          return JobOutcome.waiting("QUOTA_WAIT", response.retryAt());
        }
        if (!response.successful()) {
          if (Set.of(420, 429, 500, 502, 503, 504).contains(response.status())) {
            return JobOutcome.waiting("SUPPLIER_TEMPORARY", clock.instant().plusSeconds(60));
          }
          fail(context.scope(), id, "SUPPLIER_REJECTED_" + response.status());
          return JobOutcome.blocked("SUPPLIER_REJECTED");
        }
        OutboundGateway.Response saved = response;
        transactions.run(
            context.scope(),
            () -> {
              jobs.requireOwnership(context);
              jdbc.sql(
                      """
                      INSERT INTO marketplace_raw_page(organization_id,account_id,run_id,page_number,
                        file_id,cursor,method,content_encoding)
                      VALUES (:org,:account,:run,:page,:file,:cursor,:method,:encoding)
                      ON CONFLICT(run_id,page_number) DO NOTHING
                      """)
                  .param("org", context.scope().organizationId())
                  .param("account", context.scope().accountId())
                  .param("run", id)
                  .param("page", run.page())
                  .param("file", saved.raw().id())
                  .param("cursor", run.cursor())
                  .param("method", request.method().name())
                  .param("encoding", saved.encoding())
                  .update();
              files.retain(saved.raw().id(), "sync-run", id, context.scope());
            });
        return JobOutcome.handoff("canonicalization", clock.instant());
      }
      var batch = new ArrayList<OfferInput>(100);
      int[] batchBytes = {0};
      VendorJsonReader.PageInfo info;
      try (InputStream input = gateway.open(response)) {
        info =
            VendorJsonReader.parseSized(
                input,
                request.recordsPath(),
                (record, bytes) -> {
                  if (batchBytes[0] + bytes > 4 * 1024 * 1024) {
                    stage(context, run, batch);
                    batch.clear();
                    batchBytes[0] = 0;
                  }
                  batch.add(offerInput(record, run.phase(), credentials.marketplace()));
                  batchBytes[0] += bytes;
                  if (batch.size() == 100) {
                    stage(context, run, batch);
                    batch.clear();
                    batchBytes[0] = 0;
                  }
                });
      }
      if (!batch.isEmpty()) {
        stage(context, run, batch);
      }
      UUID rawId = response.raw().id();
      transactions.run(
          context.scope(),
          () -> {
            jobs.requireOwnership(context);
            capabilities.confirmRead(context.scope(), request.method(), rawId);
            advance(run, info, credentials.marketplace());
          });
      return JobOutcome.handoff("fetch", clock.instant().plusMillis(100));
    } catch (BusinessException exception) {
      fail(context.scope(), id, exception.code());
      throw exception;
    } catch (java.io.IOException | com.google.gson.JsonParseException exception) {
      fail(context.scope(), id, "INVALID_OR_INCOMPLETE_SOURCE");
      return JobOutcome.blocked("INVALID_OR_INCOMPLETE_SOURCE");
    }
  }

  private Request requestFor(Scope scope, Run run, String marketplace, boolean fetching) {
    boolean ozon = marketplace.equals("OZON");
    if (run.phase().equals("INFO") || run.phase().equals("ATTRIBUTES")) {
      boolean attributes = run.phase().equals("ATTRIBUTES");
      // A replay parses the immutable raw batch; already staged rows must not change its identity.
      if (!fetching) {
        return new Request(
            attributes ? VendorMethod.OZON_ATTRIBUTES : VendorMethod.OZON_INFO,
            "{}",
            attributes ? "result" : "items",
            0);
      }
      List<String> ids =
          transactions.run(
              scope,
              () ->
                  jdbc.sql(
                          """
                          SELECT external_id FROM marketplace_offer_stage
                          WHERE run_id=:run AND NOT CASE WHEN :attributes THEN attributes_complete
                            ELSE info_complete END ORDER BY external_id LIMIT 100
                          """)
                      .param("run", run.id())
                      .param("attributes", attributes)
                      .query(String.class)
                      .list());
      if (ids.isEmpty()) {
        throw new BusinessException(
            "INVALID_SYNC_CHECKPOINT",
            409,
            "Состояние обхода не соответствует подготовленным карточкам");
      }
      return attributes
          ? new Request(
              VendorMethod.OZON_ATTRIBUTES,
              json.encode(
                  Map.of(
                      "filter",
                      Map.of("product_id", ids, "visibility", "ALL"),
                      "limit",
                      100,
                      "sort_dir",
                      "ASC")),
              "result",
              ids.size())
          : new Request(
              VendorMethod.OZON_INFO, json.encode(Map.of("product_id", ids)), "items", ids.size());
    }
    boolean archived = run.phase().endsWith("ARCHIVE");
    if (run.phase().startsWith("CATALOG")) {
      return ozon
          ? new Request(
              VendorMethod.OZON_CATALOG,
              json.encode(
                  Map.of(
                      "filter",
                      Map.of("visibility", archived ? "ARCHIVED" : "ALL"),
                      "last_id",
                      run.cursor() == null ? "" : run.cursor(),
                      "limit",
                      1000)),
              "result.items",
              1000)
          : new Request(
              VendorMethod.YANDEX_CATALOG,
              json.encode(Map.of("archived", archived)),
              "result.offerMappings",
              100);
    }
    return ozon
        ? new Request(
            VendorMethod.OZON_PRICES,
            json.encode(
                Map.of(
                    "filter",
                    Map.of("visibility", archived ? "ARCHIVED" : "ALL"),
                    "cursor",
                    run.cursor() == null ? "" : run.cursor(),
                    "limit",
                    100)),
            "items",
            100)
        : new Request(
            VendorMethod.YANDEX_PRICES,
            json.encode(Map.of("archived", archived)),
            "result.offers",
            100);
  }

  private void stage(JobContext context, Run run, List<OfferInput> batch) {
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          for (OfferInput item : batch) {
            if (run.phase().startsWith("CATALOG")) {
              jdbc.sql(
                      """
                      INSERT INTO marketplace_offer_stage(organization_id,account_id,run_id,external_id,
                        sku,name,image_url,archived,info_complete,catalog_fields,catalog_phase)
                      VALUES (:org,:account,:run,:external,:sku,:name,:image,:archived,:complete,CAST(:catalog AS jsonb),:phase)
                      ON CONFLICT(run_id,external_id) DO UPDATE SET sku=EXCLUDED.sku,name=EXCLUDED.name,
                        image_url=EXCLUDED.image_url,archived=EXCLUDED.archived,
                        info_complete=EXCLUDED.info_complete,catalog_fields=EXCLUDED.catalog_fields,
                        catalog_phase=EXCLUDED.catalog_phase
                      """)
                  .param("org", context.scope().organizationId())
                  .param("account", context.scope().accountId())
                  .param("run", run.id())
                  .param("external", item.externalId())
                  .param("sku", item.sku())
                  .param("name", item.name())
                  .param("image", item.image())
                  .param("archived", item.archived())
                  .param("complete", item.complete())
                  .param("catalog", item.catalogFields())
                  .param("phase", run.phase())
                  .update();
            } else {
              int changed =
                  jdbc.sql(
                          """
                          UPDATE marketplace_offer_stage SET
                            name=COALESCE(:name,name),image_url=COALESCE(:image,image_url),
                            vendor_sku=COALESCE(:vendorSku,vendor_sku),
                            seller_price=CASE WHEN :pricePhase THEN :price ELSE seller_price END,
                            commercial_fields=CASE WHEN :pricePhase THEN CAST(:fields AS jsonb) ELSE commercial_fields END,
                            supplier_price_data=CASE WHEN :pricePhase THEN CAST(:priceData AS jsonb) ELSE supplier_price_data END,
                            price_phase=CASE WHEN :pricePhase THEN :phase ELSE price_phase END,
                            catalog_fields=COALESCE(CAST(:catalog AS jsonb),catalog_fields),
                            attributes_complete=attributes_complete OR :attributes,
                            info_complete=CASE WHEN :complete THEN true ELSE info_complete END
                          WHERE run_id=:run AND external_id=:external
                          """)
                      .param("name", item.name())
                      .param("image", item.image())
                      .param("vendorSku", item.vendorSku())
                      .param("pricePhase", run.phase().startsWith("PRICE"))
                      .param("phase", run.phase())
                      .param("price", item.price())
                      .param("fields", item.fields())
                      .param("priceData", item.priceData())
                      .param("catalog", item.catalogFields())
                      .param("attributes", run.phase().equals("ATTRIBUTES"))
                      .param("complete", item.complete())
                      .param("run", run.id())
                      .param("external", item.externalId())
                      .update();
              if (changed != 1) {
                throw new BusinessException(
                    "CATALOG_CHANGED_DURING_SYNC",
                    409,
                    "Площадка вернула товар вне подготовленного снимка. Требуется новый полный"
                        + " обход");
              }
            }
          }
        });
  }

  private void advance(Run run, VendorJsonReader.PageInfo info, String marketplace) {
    String next = info.cursor();
    String phase = run.phase();
    Long phaseTotal = null;
    if (phase.equals("INFO") || phase.equals("ATTRIBUTES")) {
      boolean attributes = phase.equals("ATTRIBUTES");
      // Individual info batches have no cursor and finish by the remaining staged IDs.
      long missing =
          jdbc.sql(
                  """
                  SELECT count(*) FROM marketplace_offer_stage WHERE run_id=:run
                    AND NOT CASE WHEN :attributes THEN attributes_complete ELSE info_complete END
                  """)
              .param("run", run.id())
              .param("attributes", attributes)
              .query(Long.class)
              .single();
      if (missing == 0) {
        phase = attributes ? "PRICES" : "ATTRIBUTES";
      } else if (info.records() == 0) {
        throw new BusinessException(
            "INCOMPLETE_INFO", 422, "Площадка не вернула запрошенные карточки");
      }
      next = null;
    } else {
      Long previousTotal =
          jdbc.sql("SELECT phase_total FROM marketplace_sync_run WHERE id=:run")
              .param("run", run.id())
              .query(Long.class)
              .optional()
              .orElse(null);
      if (previousTotal != null && info.total() != null && !previousTotal.equals(info.total())) {
        throw new BusinessException(
            "SOURCE_TOTAL_CHANGED", 422, "Количество товаров изменилось во время обхода");
      }
      phaseTotal = info.total() == null ? previousTotal : info.total();
      boolean complete = next == null || next.isBlank() || info.records() == 0;
      if (phaseTotal != null && complete) {
        long distinctItems =
            jdbc.sql(
                    """
                    SELECT count(*) FROM marketplace_offer_stage WHERE run_id=:run
                      AND CASE WHEN :catalog THEN catalog_phase ELSE price_phase END=:phase
                    """)
                .param("run", run.id())
                .param("catalog", phase.startsWith("CATALOG"))
                .param("phase", phase)
                .query(Long.class)
                .single();
        if (distinctItems != phaseTotal) {
          throw new BusinessException(
              "SOURCE_TOTAL_MISMATCH",
              422,
              "Обход не содержит заявленное источником количество товаров");
        }
      }
      if (!complete && next.equals(run.cursor())) {
        throw new BusinessException("CURSOR_STALLED", 422, "Курсор площадки не продвигается");
      }
      if (complete) {
        phase =
            switch (phase) {
              case "CATALOG" -> "CATALOG_ARCHIVE";
              case "CATALOG_ARCHIVE" ->
                  marketplace.equals("OZON")
                          && jdbc.sql(
                                  "SELECT EXISTS(SELECT 1 FROM marketplace_offer_stage WHERE"
                                      + " run_id=:run)")
                              .param("run", run.id())
                              .query(Boolean.class)
                              .single()
                      ? "INFO"
                      : "PRICES";
              case "PRICES" -> "PRICE_ARCHIVE";
              case "PRICE_ARCHIVE" -> "PUBLISH";
              default -> throw new IllegalStateException("Unknown traversal phase");
            };
        next = null;
        phaseTotal = null;
      }
    }
    jdbc.sql(
            """
            UPDATE marketplace_raw_page SET parsed=true,next_cursor=:next,item_count=:count
            WHERE run_id=:run AND page_number=:page
            """)
        .param("next", next)
        .param("count", info.records())
        .param("run", run.id())
        .param("page", run.page())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_sync_run SET phase=:phase,next_cursor=:next,page_number=page_number+1,
              parsed_count=parsed_count+:count,phase_total=:total,revision=revision+1 WHERE id=:run
            """)
        .param("phase", phase)
        .param("next", next)
        .param("count", info.records())
        .param("total", phaseTotal)
        .param("run", run.id())
        .update();
  }

  private JobOutcome publish(JobContext context, Run run) {
    jobs.requireOwnership(context);
    authorization.require(context.scope(), "sync.request");
    jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
        .param("id", context.scope().accountId())
        .query(UUID.class)
        .single();
    boolean incomplete =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_offer_stage WHERE run_id=:run
                  AND (NOT info_complete OR price_phase IS NULL))
                """)
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    if (incomplete) {
      throw new BusinessException(
          "INCOMPLETE_CATALOG", 422, "Не все карточки подтверждены источником");
    }
    boolean late =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_offer WHERE observed_at>:observed)
                """)
            .param("observed", Timestamp.from(run.startedAt()))
            .query(Boolean.class)
            .single();
    if (late) {
      throw new BusinessException(
          "SOURCE_OBSERVATION_SUPERSEDED",
          409,
          "Поздний обход не может заменить более новое наблюдение");
    }
    jdbc.sql(
            """
            INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,vendor_sku,name,
              image_url,seller_price,buyer_price,archived,observed_at,publication_id,
              info_complete,commercial_fields,catalog_fields,supplier_price_data)
            SELECT md5(account_id::text||':'||external_id)::uuid,organization_id,account_id,
              external_id,sku,vendor_sku,name,image_url,seller_price,buyer_price,archived,:observed,:run,
              info_complete,commercial_fields,catalog_fields,supplier_price_data
            FROM marketplace_offer_stage WHERE run_id=:run
            ON CONFLICT(organization_id,account_id,external_id) DO UPDATE SET sku=EXCLUDED.sku,
              vendor_sku=EXCLUDED.vendor_sku,
              name=EXCLUDED.name,image_url=EXCLUDED.image_url,seller_price=EXCLUDED.seller_price,
              buyer_price=EXCLUDED.buyer_price,archived=EXCLUDED.archived,observed_at=EXCLUDED.observed_at,
              publication_id=EXCLUDED.publication_id,commercial_fields=EXCLUDED.commercial_fields,
              catalog_fields=EXCLUDED.catalog_fields,supplier_price_data=EXCLUDED.supplier_price_data,
              revision=marketplace_offer.revision+CASE WHEN
                (marketplace_offer.sku,marketplace_offer.name,marketplace_offer.image_url,
                  marketplace_offer.seller_price,marketplace_offer.buyer_price,marketplace_offer.archived,
                  marketplace_offer.commercial_fields,marketplace_offer.catalog_fields,
                  marketplace_offer.supplier_price_data)
                IS DISTINCT FROM (EXCLUDED.sku,EXCLUDED.name,EXCLUDED.image_url,
                  EXCLUDED.seller_price,EXCLUDED.buyer_price,EXCLUDED.archived,EXCLUDED.commercial_fields,
                  EXCLUDED.catalog_fields,EXCLUDED.supplier_price_data)
                THEN 1 ELSE 0 END
            """)
        .param("observed", Timestamp.from(run.startedAt()))
        .param("run", run.id())
        .update();
    if (!run.source().equals("PRICES")) {
      jdbc.sql(
              """
              UPDATE marketplace_offer SET archived=true,revision=revision+1
              WHERE publication_id<>:run AND NOT archived
              """)
          .param("run", run.id())
          .update();
    }
    jdbc.sql(
            """
            INSERT INTO marketplace_offer_observation(organization_id,account_id,offer_id,source_run_id,
              observed_at,seller_price,buyer_price,archived,commercial_fields,semantic_revision,
              catalog_fields,supplier_price_data)
            SELECT organization_id,account_id,id,:run,observed_at,seller_price,buyer_price,archived,
              commercial_fields,revision,catalog_fields,supplier_price_data
            FROM marketplace_offer WHERE publication_id=:run
            ON CONFLICT(offer_id,source_run_id) DO NOTHING
            """)
        .param("run", run.id())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_sync_run SET state='PUBLISHED',completed_at=clock_timestamp(),revision=revision+1
            WHERE id=:run
            """)
        .param("run", run.id())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_source SET status='READY',last_success_at=:observed,
              publication_id=:run,revision=revision+1,reason=NULL WHERE source_type=:source
            """)
        .param("observed", Timestamp.from(run.startedAt()))
        .param("run", run.id())
        .param("source", run.source())
        .update();
    categories.refreshOfferPaths(context.scope());
    audit.record(context.scope(), "sync.published", run.id(), "source=" + run.source());
    snapshots.refresh(context.scope(), run.id());
    outbox.emit(
        context.scope(),
        "publication:" + run.id(),
        "catalog.changed",
        new OutboxService.EntityChange("offers", run.id(), run.page() + 1L));
    if (!run.source().equals("PRICES")) {
      request(
          context.scope(),
          "CATEGORIES",
          UUID.nameUUIDFromBytes((run.id() + ":categories").getBytes(StandardCharsets.UTF_8)));
    }
    if (run.source().equals("ALL")) {
      // The follow-up is persisted in this same transaction and reports its own completeness.
      UUID requestId =
          UUID.nameUUIDFromBytes((run.id() + ":stock").getBytes(StandardCharsets.UTF_8));
      UUID stockJob = request(context.scope(), "STOCKS", requestId);
      UUID promotionsJob =
          request(
              context.scope(),
              "PROMOTIONS",
              UUID.nameUUIDFromBytes((run.id() + ":promotions").getBytes(StandardCharsets.UTF_8)));
      ZoneId zone =
          ZoneId.of(
              jdbc.sql("SELECT timezone FROM marketplace_account WHERE id=:id")
                  .param("id", context.scope().accountId())
                  .query(String.class)
                  .single());
      LocalDate yesterday = LocalDate.now(clock.withZone(zone)).minusDays(1);
      if (authorization.hasPermission(context.scope(), "finance.read")) {
        requestHistory(
            context.scope(),
            yesterday.minusDays(13),
            yesterday,
            UUID.nameUUIDFromBytes((run.id() + ":history").getBytes(StandardCharsets.UTF_8)));
      }
      return JobOutcome.succeeded(
          json.encode(
              Map.of(
                  "publicationId",
                  run.id(),
                  "stockJobId",
                  stockJob,
                  "promotionsJobId",
                  promotionsJob)));
    }
    return JobOutcome.succeeded(json.encode(new Published(run.id())));
  }

  void recordIncompletePublication(Scope scope, UUID run, String reason) {
    authorization.require(scope, "sync.request");
    failCurrent(scope, run, reason);
  }

  void fail(Scope scope, UUID run, String reason) {
    transactions.runService(
        scope,
        Set.of("marketplace.sync.record"),
        () -> {
          failCurrent(scope, run, reason);
          return true;
        });
  }

  void onTerminalFailure(Scope scope, UUID operation, String reason) {
    authorization.require(scope, "marketplace.sync.record");
    jdbc.sql(
            """
            SELECT id FROM marketplace_sync_run WHERE job_id=:job
              AND state IN ('FETCHING','PARSING','VALIDATING') FOR UPDATE
            """)
        .param("job", operation)
        .query(UUID.class)
        .optional()
        .ifPresent(id -> failCurrent(scope, id, reason));
  }

  private void failCurrent(Scope scope, UUID run, String reason) {
    var revision =
        jdbc.sql(
                """
                UPDATE marketplace_sync_run SET state='INCOMPLETE',reason=:reason,
                  completed_at=clock_timestamp(),revision=revision+1
                WHERE id=:id AND state IN ('FETCHING','PARSING','VALIDATING') RETURNING revision
                """)
            .param("reason", reason)
            .param("id", run)
            .query(Long.class)
            .optional();
    if (revision.isEmpty()) {
      return;
    }
    jdbc.sql(
            """
            UPDATE marketplace_source SET status='INCOMPLETE',reason=:reason
            WHERE source_type=(SELECT source_type FROM marketplace_sync_run WHERE id=:run)
            """)
        .param("reason", reason)
        .param("run", run)
        .update();
    outbox.emit(
        scope,
        "sync-failed:" + run,
        "source.changed",
        new OutboxService.EntityChange("sync-runs", run, revision.orElseThrow()));
  }

  Run run(UUID id) {
    return jdbc.sql("SELECT * FROM marketplace_sync_run WHERE id=:id FOR SHARE")
        .param("id", id)
        .query(
            (row, index) -> {
              SourceStageRetention.requireAvailable(
                  row.getTimestamp("stage_deletion_after") != null);
              return new Run(
                  id,
                  row.getString("source_type"),
                  row.getString("state"),
                  row.getString("phase"),
                  row.getInt("page_number"),
                  row.getString("next_cursor"),
                  row.getTimestamp("started_at").toInstant());
            })
        .single();
  }

  private static OfferInput offerInput(JsonObject record, String phase, String marketplace) {
    boolean ozon = marketplace.equals("OZON");
    boolean catalog = phase.startsWith("CATALOG");
    boolean info = phase.equals("INFO");
    boolean attributes = phase.equals("ATTRIBUTES");
    if (!ozon && catalog) {
      JsonObject offer = record.getAsJsonObject("offer");
      String sku = text(offer, "offerId", true);
      JsonObject catalogFields = offer.deepCopy();
      if (record.has("mapping")) {
        catalogFields.add("mapping", record.get("mapping"));
      }
      return new OfferInput(
          sku,
          sku,
          null,
          text(offer, "name", true),
          firstImage(offer, "pictures"),
          null,
          phase.endsWith("ARCHIVE"),
          true,
          null,
          catalogFields.toString(),
          null);
    }
    String external =
        text(record, ozon ? (info || attributes ? "id" : "product_id") : "offerId", true);
    String sku = ozon ? text(record, "offer_id", catalog) : external;
    if (catalog || info || attributes) {
      String image = null;
      if (attributes) {
        String sourceImage =
            record.has("primary_image") && !record.get("primary_image").isJsonNull()
                ? record.get("primary_image").getAsString()
                : null;
        if (sourceImage != null
            && sourceImage.startsWith("https://")
            && sourceImage.length() <= 4096) {
          image = sourceImage;
        }
      } else if (info) {
        image = firstImage(record, "primary_image");
      }
      return new OfferInput(
          external,
          sku,
          info || attributes ? text(record, "sku", false) : null,
          info || attributes ? text(record, "name", true) : sku,
          image,
          null,
          phase.endsWith("ARCHIVE"),
          info,
          null,
          attributes ? record.toString() : null,
          null);
    }
    JsonObject price = record.getAsJsonObject("price");
    BigDecimal value = decimal(price, ozon ? "price" : "value");
    return new OfferInput(
        external,
        sku,
        null,
        null,
        null,
        value,
        false,
        false,
        price == null ? null : price.toString(),
        null,
        record.toString());
  }

  static String text(JsonObject record, String name, boolean required) {
    if (record == null || !record.has(name) || record.get(name).isJsonNull()) {
      if (required) {
        throw new BusinessException(
            "SOURCE_FIELD_MISSING", 422, "В ответе площадки отсутствует обязательное поле");
      }
      return null;
    }
    String value = record.get(name).getAsString();
    if (value.isBlank() || value.length() > 4096) {
      throw new BusinessException("SOURCE_FIELD_INVALID", 422, "Некорректное поле ответа площадки");
    }
    return value;
  }

  static BigDecimal decimal(JsonObject record, String name) {
    String value = text(record, name, false);
    if (value == null) {
      return null;
    }
    BigDecimal number = new BigDecimal(value);
    if (number.signum() < 0 || number.precision() > 38 || number.scale() > 12) {
      throw new BusinessException(
          "SOURCE_NUMBER_INVALID", 422, "Некорректное число в ответе площадки");
    }
    return number;
  }

  private static String firstImage(JsonObject object, String key) {
    if (!object.has(key)
        || !object.get(key).isJsonArray()
        || object.getAsJsonArray(key).isEmpty()) {
      return null;
    }
    var first = object.getAsJsonArray(key).get(0);
    if (!first.isJsonPrimitive()) {
      return null;
    }
    String url = first.getAsString();
    return url.startsWith("https://") && url.length() <= 4096 ? url : null;
  }

  public record SyncJob(UUID runId) {}

  record Run(
      UUID id,
      String source,
      String state,
      String phase,
      int page,
      String cursor,
      Instant startedAt) {}

  private record Request(VendorMethod method, String body, String recordsPath, int elements) {}

  private record RawPage(UUID id, String encoding) {}

  private record Published(UUID publicationId) {}

  private record HistoryPeriod(LocalDate from, LocalDate until) {}

  private record OfferInput(
      String externalId,
      String sku,
      String vendorSku,
      String name,
      String image,
      BigDecimal price,
      boolean archived,
      boolean complete,
      String fields,
      String catalogFields,
      String priceData) {}
}
