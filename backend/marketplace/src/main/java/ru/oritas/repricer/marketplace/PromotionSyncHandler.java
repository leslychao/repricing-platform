package ru.oritas.repricer.marketplace;

import com.google.gson.JsonObject;
import java.io.InputStream;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.StoredFileService;

/** Reads every participation kind; a page or a processing response never replaces live state. */
@Component
public final class PromotionSyncHandler implements JobHandler {
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ConnectionService connections;
  private final OutboundGateway gateway;
  private final CapabilityService capabilities;
  private final CatalogSyncService catalog;
  private final JobRuntime jobs;
  private final StoredFileService files;
  private final JsonCodec json;
  private final OutboxService outbox;
  private final AuditService audit;
  private final Clock clock;
  private final SourceSnapshotPublisher snapshots;

  public PromotionSyncHandler(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      OutboundGateway gateway,
      CapabilityService capabilities,
      CatalogSyncService catalog,
      JobRuntime jobs,
      StoredFileService files,
      JsonCodec json,
      OutboxService outbox,
      AuditService audit,
      Clock clock,
      SourceSnapshotPublisher snapshots) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.connections = connections;
    this.gateway = gateway;
    this.capabilities = capabilities;
    this.catalog = catalog;
    this.jobs = jobs;
    this.files = files;
    this.json = json;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
    this.snapshots = snapshots;
  }

  @Override
  public String type() {
    return "PROMOTION_SYNC";
  }

  @Override
  public String lane() {
    return "fetch";
  }

  @Override
  public JobOutcome execute(JobContext context) throws Exception {
    UUID runId = json.decode(context.payload(), CatalogSyncService.SyncJob.class).runId();
    var run =
        transactions.run(
            context.scope(),
            () -> {
              jobs.requireOwnership(context);
              authorization.require(context.scope(), "sync.request");
              return catalog.run(runId);
            });
    if (run.state().equals("PUBLISHED")) {
      return JobOutcome.succeeded("{}");
    }
    if (Set.of("FAILED", "INCOMPLETE").contains(run.state())) {
      return JobOutcome.blocked("SYNC_INCOMPLETE");
    }
    if (run.page() >= 10000 || run.startedAt().plusSeconds(1800).isBefore(clock.instant())) {
      catalog.fail(context.scope(), runId, "TRAVERSAL_LIMIT");
      return JobOutcome.blocked("TRAVERSAL_LIMIT");
    }
    var connection = transactions.run(context.scope(), () -> connections.current(context.scope()));
    boolean ozon = connection.marketplace().equals("OZON");
    try {
      if (run.phase().equals("PUBLISH")) {
        if (!context.lane().equals("canonicalization")) {
          return JobOutcome.handoff("canonicalization", clock.instant());
        }
        return transactions.run(context.scope(), () -> publish(context, run, ozon));
      }
      boolean promotionList = run.phase().equals("PROMOS");
      String promotion =
          promotionList
              ? null
              : transactions.run(
                  context.scope(),
                  () ->
                      jdbc.sql("SELECT batch_after FROM marketplace_sync_run WHERE id=:id")
                          .param("id", runId)
                          .query(String.class)
                          .single());
      VendorMethod method = method(run.phase(), ozon);
      var cached =
          transactions.run(
              context.scope(),
              () ->
                  jdbc.sql(
                          """
                          SELECT file_id,content_encoding FROM marketplace_raw_page WHERE run_id=:run AND page_number=:page
                          """)
                      .param("run", runId)
                      .param("page", run.page())
                      .query(
                          (row, index) ->
                              new OutboundGateway.Response(
                                  null,
                                  200,
                                  files.get(row.getObject(1, UUID.class)),
                                  row.getString(2),
                                  null,
                                  connection.revision()))
                      .optional());
      String requiredLane = cached.isPresent() ? "canonicalization" : "fetch";
      if (!context.lane().equals(requiredLane)) {
        return JobOutcome.handoff(requiredLane, clock.instant());
      }
      OutboundGateway.Response response;
      if (cached.isPresent()) {
        response = cached.orElseThrow();
      } else {
        response =
            gateway.executePage(
                context.scope(),
                runId,
                run.page(),
                method,
                body(run, promotion, ozon),
                null,
                run.cursor(),
                100);
        if (response.retryAt() != null) {
          return JobOutcome.waiting("QUOTA_WAIT", response.retryAt());
        }
        if (!response.successful()) {
          if (response.status() >= 500 || Set.of(420, 429).contains(response.status())) {
            return JobOutcome.waiting("SUPPLIER_TEMPORARY", clock.instant().plusSeconds(60));
          }
          catalog.fail(context.scope(), runId, "SUPPLIER_REJECTED");
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
                  .param("run", runId)
                  .param("page", run.page())
                  .param("file", saved.raw().id())
                  .param("cursor", run.cursor())
                  .param("method", method.name())
                  .param("encoding", saved.encoding())
                  .update();
              files.retain(saved.raw().id(), "sync-run", runId, context.scope());
            });
        return JobOutcome.handoff("canonicalization", clock.instant());
      }
      List<Row> batch = new ArrayList<>(100);
      int[] batchBytes = {0};
      VendorJsonReader.PageInfo info;
      try (InputStream input = gateway.open(response)) {
        info =
            VendorJsonReader.parseSized(
                input,
                ozon
                    ? (promotionList ? "result" : "products")
                    : promotionList ? "result.promos" : "result.offers",
                (item, bytes) -> {
                  if (batchBytes[0] + bytes > 4 * 1024 * 1024) {
                    stage(context, runId, batch);
                    batch.clear();
                    batchBytes[0] = 0;
                  }
                  batch.add(ozon ? ozonRow(item, promotion, run.phase()) : row(item, promotion));
                  batchBytes[0] += bytes;
                  if (batch.size() == 100) {
                    stage(context, runId, batch);
                    batch.clear();
                    batchBytes[0] = 0;
                  }
                });
      }
      if (!batch.isEmpty()) {
        stage(context, runId, batch);
      }
      UUID raw = response.raw().id();
      transactions.run(
          context.scope(),
          () -> {
            jobs.requireOwnership(context);
            if (ozon) {
              advanceOzon(run, promotion, info, method);
              capabilities.confirmRead(context.scope(), method, raw);
              return;
            }
            if (info.cursor() != null && info.cursor().equals(run.cursor())) {
              throw invalid("CURSOR_STALLED");
            }
            String nextPromotion = promotion;
            String phase = run.phase();
            String cursor = info.cursor();
            if (promotionList || cursor == null || cursor.isBlank()) {
              nextPromotion =
                  jdbc.sql(
                          """
                          SELECT external_id FROM marketplace_promotion_stage
                          WHERE run_id=:run AND sku='' AND (:previous IS NULL OR external_id>:previous)
                          ORDER BY external_id LIMIT 1
                          """)
                      .param("run", runId)
                      .param("previous", promotion, java.sql.Types.VARCHAR)
                      .query(String.class)
                      .optional()
                      .orElse(null);
              phase = nextPromotion == null ? "PUBLISH" : "PROMO_OFFERS";
              cursor = null;
            }
            jdbc.sql(
                    """
                    UPDATE marketplace_sync_run SET page_number=page_number+1,next_cursor=:cursor,
                      batch_after=:promotion,phase=:phase,parsed_count=parsed_count+:count WHERE id=:id
                    """)
                .param("cursor", cursor)
                .param("promotion", nextPromotion)
                .param("phase", phase)
                .param("count", info.records())
                .param("id", runId)
                .update();
            jdbc.sql(
                    """
                    UPDATE marketplace_raw_page SET parsed=true,item_count=:count,next_cursor=:cursor
                    WHERE run_id=:run AND page_number=:page
                    """)
                .param("count", info.records())
                .param("cursor", info.cursor())
                .param("run", runId)
                .param("page", run.page())
                .update();
            capabilities.confirmRead(context.scope(), method, raw);
          });
      return JobOutcome.handoff("fetch", clock.instant().plusMillis(100));
    } catch (BusinessException | java.io.IOException exception) {
      catalog.fail(context.scope(), runId, "PROMOTION_SCOPE_INCOMPLETE");
      return JobOutcome.blocked("PROMOTION_SCOPE_INCOMPLETE");
    }
  }

  private static VendorMethod method(String phase, boolean ozon) {
    if (!ozon) {
      return phase.equals("PROMOS") ? VendorMethod.YANDEX_PROMOS : VendorMethod.YANDEX_PROMO_OFFERS;
    }
    return switch (phase) {
      case "PROMOS" -> VendorMethod.OZON_ACTIONS;
      case "PROMO_CANDIDATES" -> VendorMethod.OZON_ACTION_CANDIDATES;
      case "PROMO_OFFERS" -> VendorMethod.OZON_ACTION_PRODUCTS;
      default -> throw invalid("INVALID_PROMOTION_PHASE");
    };
  }

  private String body(CatalogSyncService.Run run, String promotion, boolean ozon) {
    if (promotion == null) {
      return "{}";
    }
    if (!ozon) {
      return json.encode(Map.of("promoId", promotion));
    }
    return json.encode(
        Map.of(
            "action_id",
            new BigInteger(promotion),
            "limit",
            100,
            "last_id",
            run.cursor() == null ? "" : run.cursor()));
  }

  private static Row ozonRow(JsonObject item, String promotion, String phase) {
    String id = CatalogSyncService.text(item, "id", true);
    if (!id.matches("[1-9][0-9]{0,19}") || new BigInteger(id).bitLength() > 64) {
      throw invalid("PROMOTION_ID_INVALID");
    }
    if (promotion == null) {
      CatalogSyncService.text(item, "title", true);
      CatalogSyncService.text(item, "action_type", true);
      Instant.parse(CatalogSyncService.text(item, "date_start", true));
      Instant.parse(CatalogSyncService.text(item, "date_end", true));
      return new Row(id, "", item.toString());
    }
    for (String field :
        List.of(
            "price",
            "action_price",
            "max_action_price",
            "min_seller_price",
            "marketplace_seller_price")) {
      if (item.has(field) && !item.get(field).isJsonNull()) {
        JsonObject money = item.getAsJsonObject(field);
        if (!"RUB".equals(CatalogSyncService.text(money, "currency", true))) {
          throw invalid("PROMOTION_CURRENCY_UNSUPPORTED");
        }
        CatalogSyncService.decimal(money, "amount");
      }
    }
    boolean participant = phase.equals("PROMO_OFFERS");
    if (participant
        && !Set.of("AUTO", "SELLER").contains(CatalogSyncService.text(item, "add_mode", true))) {
      throw invalid("PROMOTION_STATUS_UNKNOWN");
    }
    return new Row(promotion, (participant ? "participant:" : "candidate:") + id, item.toString());
  }

  private void advanceOzon(
      CatalogSyncService.Run run,
      String promotion,
      VendorJsonReader.PageInfo info,
      VendorMethod method) {
    boolean list = run.phase().equals("PROMOS");
    String cursor = info.cursor();
    boolean finished = list || info.records() == 0 || cursor == null || cursor.isBlank();
    if (!finished && cursor.equals(run.cursor())) {
      throw invalid("CURSOR_STALLED");
    }
    if (!list && finished && info.total() != null) {
      String prefix = run.phase().equals("PROMO_OFFERS") ? "participant:%" : "candidate:%";
      long staged =
          jdbc.sql(
                  "SELECT count(*) FROM marketplace_promotion_stage WHERE run_id=:run AND"
                      + " external_id=:promotion AND sku LIKE :prefix")
              .param("run", run.id())
              .param("promotion", promotion)
              .param("prefix", prefix)
              .query(Long.class)
              .single();
      if (staged != info.total()) {
        throw invalid("PROMOTION_TOTAL_MISMATCH");
      }
    }
    String phase = run.phase();
    String nextPromotion = promotion;
    if (finished) {
      cursor = null;
      if (phase.equals("PROMO_CANDIDATES")) {
        phase = "PROMO_OFFERS";
      } else {
        nextPromotion =
            jdbc.sql(
                    """
                    SELECT external_id FROM marketplace_promotion_stage WHERE run_id=:run AND sku=''
                      AND (:previous IS NULL OR external_id>:previous) ORDER BY external_id LIMIT 1
                    """)
                .param("run", run.id())
                .param("previous", promotion, java.sql.Types.VARCHAR)
                .query(String.class)
                .optional()
                .orElse(null);
        phase = nextPromotion == null ? "PUBLISH" : "PROMO_CANDIDATES";
      }
    }
    jdbc.sql(
            """
            UPDATE marketplace_sync_run SET page_number=page_number+1,next_cursor=:cursor,
              batch_after=:promotion,phase=:phase,parsed_count=parsed_count+:count WHERE id=:id
            """)
        .param("cursor", cursor)
        .param("promotion", nextPromotion)
        .param("phase", phase)
        .param("count", info.records())
        .param("id", run.id())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_raw_page SET parsed=true,item_count=:count,next_cursor=:cursor
              WHERE run_id=:run AND page_number=:page AND method=:method
            """)
        .param("count", info.records())
        .param("cursor", info.cursor())
        .param("run", run.id())
        .param("page", run.page())
        .param("method", method.name())
        .update();
  }

  private Row row(JsonObject item, String promotion) {
    if (promotion == null) {
      String id = CatalogSyncService.text(item, "id", true);
      CatalogSyncService.text(item, "name", true);
      CatalogSyncService.text(item.getAsJsonObject("mechanicsInfo"), "type", true);
      JsonObject period = item.getAsJsonObject("period");
      Instant.parse(CatalogSyncService.text(period, "dateTimeFrom", true));
      Instant.parse(CatalogSyncService.text(period, "dateTimeTo", true));
      return new Row(id, "", item.toString());
    }
    String sku = CatalogSyncService.text(item, "offerId", true);
    if (!Set.of(
            "AUTO",
            "PARTIALLY_AUTO",
            "MANUAL",
            "NOT_PARTICIPATING",
            "RENEWED",
            "RENEW_FAILED",
            "MINIMUM_FOR_PROMOS")
        .contains(CatalogSyncService.text(item, "status", true))) {
      throw invalid("PROMOTION_STATUS_UNKNOWN");
    }
    JsonObject params = item.getAsJsonObject("params");
    JsonObject discount = params == null ? null : params.getAsJsonObject("discountParams");
    if (discount != null) {
      CatalogSyncService.decimal(discount, "price");
      CatalogSyncService.decimal(discount, "promoPrice");
      CatalogSyncService.decimal(discount, "maxPromoPrice");
    }
    return new Row(promotion, sku, item.toString());
  }

  private void stage(JobContext context, UUID run, List<Row> batch) {
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          for (Row row : batch) {
            int changed =
                jdbc.sql(
                        """
                        INSERT INTO marketplace_promotion_stage(organization_id,account_id,run_id,external_id,sku,data)
                        VALUES (:org,:account,:run,:external,:sku,CAST(:data AS jsonb))
                        ON CONFLICT(run_id,external_id,sku) DO UPDATE SET data=EXCLUDED.data
                        WHERE marketplace_promotion_stage.data=EXCLUDED.data
                        """)
                    .param("org", context.scope().organizationId())
                    .param("account", context.scope().accountId())
                    .param("run", run)
                    .param("external", row.external())
                    .param("sku", row.sku())
                    .param("data", row.data())
                    .update();
            if (changed != 1) {
              throw invalid("PROMOTION_PAGE_CONFLICT");
            }
          }
        });
  }

  private JobOutcome publish(JobContext context, CatalogSyncService.Run run, boolean ozon) {
    jobs.requireOwnership(context);
    jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
        .param("id", context.scope().accountId())
        .query(UUID.class)
        .single();
    boolean late =
        jdbc.sql("SELECT EXISTS(SELECT 1 FROM marketplace_promotion WHERE observed_at>:observed)")
            .param("observed", Timestamp.from(run.startedAt()))
            .query(Boolean.class)
            .single();
    if (late) {
      throw invalid("SOURCE_OBSERVATION_SUPERSEDED");
    }
    if (ozon) {
      publishOzon(run);
    } else {
      publishYandex(run);
    }
    return finishPublication(context, run);
  }

  private void publishYandex(CatalogSyncService.Run run) {
    boolean invalid =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_promotion_stage s WHERE s.run_id=:run
                  AND ((s.sku='' AND (s.data#>>'{assortmentInfo,processing}') IS DISTINCT FROM 'false')
                  OR (s.sku<>'' AND NOT EXISTS(SELECT 1 FROM marketplace_offer o WHERE o.sku=s.sku))))
                """)
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    if (invalid) {
      throw invalid("PROMOTION_PROCESSING_OR_SCOPE_UNKNOWN");
    }
    jdbc.sql(
            """
            INSERT INTO marketplace_promotion(id,organization_id,account_id,external_id,name,promotion_type,
              starts_at,ends_at,processing,constraints,publication_id,observed_at,valid_until)
            SELECT md5(account_id::text||':promo:'||external_id)::uuid,organization_id,account_id,external_id,
              data->>'name',data#>>'{mechanicsInfo,type}',(data#>>'{period,dateTimeFrom}')::timestamptz,
              (data#>>'{period,dateTimeTo}')::timestamptz,false,COALESCE(data->'constraints','{}'::jsonb),
              :run,clock_timestamp(),clock_timestamp()+interval '15 minutes'
            FROM marketplace_promotion_stage WHERE run_id=:run AND sku=''
            ON CONFLICT(organization_id,account_id,external_id) DO UPDATE SET name=EXCLUDED.name,
              promotion_type=EXCLUDED.promotion_type,starts_at=EXCLUDED.starts_at,ends_at=EXCLUDED.ends_at,
              processing=EXCLUDED.processing,constraints=EXCLUDED.constraints,publication_id=EXCLUDED.publication_id,
              observed_at=EXCLUDED.observed_at,valid_until=EXCLUDED.valid_until,revision=marketplace_promotion.revision+1
            """)
        .param("run", run.id())
        .update();
    jdbc.sql(
            """
            INSERT INTO marketplace_promotion_offer(organization_id,account_id,promotion_id,offer_id,
              external_id,promotion_type,status,base_price,promotion_price,minimum_price,maximum_price,
              participation_scope,complete,publication_id,valid_until)
            SELECT s.organization_id,s.account_id,p.id,o.id,p.external_id,p.promotion_type,s.data->>'status',
              (s.data#>>'{params,discountParams,price}')::numeric,
              (s.data#>>'{params,discountParams,promoPrice}')::numeric,
              greatest(1,ceil((s.data#>>'{params,discountParams,price}')::numeric*0.01)),
              least(floor((s.data#>>'{params,discountParams,price}')::numeric*0.95),
                (s.data#>>'{params,discountParams,maxPromoPrice}')::numeric),
              COALESCE(s.data->'autoParticipatingDetails','{}'::jsonb),
              p.promotion_type IN ('DIRECT_DISCOUNT','BLUE_FLASH')
                AND s.data#>>'{params,discountParams,price}' IS NOT NULL,
              :run,clock_timestamp()+interval '15 minutes'
            FROM marketplace_promotion_stage s JOIN marketplace_promotion p
              ON p.account_id=s.account_id AND p.external_id=s.external_id
            JOIN marketplace_offer o ON o.account_id=s.account_id AND o.sku=s.sku
            WHERE s.run_id=:run AND s.sku<>''
            ON CONFLICT(organization_id,account_id,promotion_id,offer_id) DO UPDATE SET status=EXCLUDED.status,
              base_price=EXCLUDED.base_price,promotion_price=EXCLUDED.promotion_price,
              minimum_price=EXCLUDED.minimum_price,maximum_price=EXCLUDED.maximum_price,
              participation_scope=EXCLUDED.participation_scope,complete=EXCLUDED.complete,
              publication_id=EXCLUDED.publication_id,valid_until=EXCLUDED.valid_until,
              revision=marketplace_promotion_offer.revision+1
            """)
        .param("run", run.id())
        .update();
  }

  private void publishOzon(CatalogSyncService.Run run) {
    boolean unknown =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_promotion_stage s WHERE s.run_id=:run AND s.sku<>''
                  AND NOT EXISTS(SELECT 1 FROM marketplace_offer o WHERE o.external_id=s.data->>'id'))
                """)
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    if (unknown) {
      throw invalid("PROMOTION_OFFER_SCOPE_UNKNOWN");
    }
    jdbc.sql(
            """
            INSERT INTO marketplace_promotion(id,organization_id,account_id,external_id,name,promotion_type,
              starts_at,ends_at,processing,constraints,publication_id,observed_at,valid_until)
            SELECT md5(account_id::text||':promo:'||external_id)::uuid,organization_id,account_id,external_id,
              data->>'title',data->>'action_type',(data->>'date_start')::timestamptz,
              (data->>'date_end')::timestamptz,NULL,data,:run,:observed,
              CAST(:observed AS timestamptz)+interval '15 minutes'
            FROM marketplace_promotion_stage WHERE run_id=:run AND sku=''
            ON CONFLICT(organization_id,account_id,external_id) DO UPDATE SET name=EXCLUDED.name,
              promotion_type=EXCLUDED.promotion_type,starts_at=EXCLUDED.starts_at,ends_at=EXCLUDED.ends_at,
              processing=EXCLUDED.processing,constraints=EXCLUDED.constraints,publication_id=EXCLUDED.publication_id,
              observed_at=EXCLUDED.observed_at,valid_until=EXCLUDED.valid_until,revision=marketplace_promotion.revision+1
            """)
        .param("run", run.id())
        .param("observed", Timestamp.from(run.startedAt()))
        .update();
    jdbc.sql(
            """
            WITH products AS (
              SELECT external_id,data->>'id' AS product_id,
                max(data::text) FILTER(WHERE sku LIKE 'candidate:%')::jsonb AS candidate,
                max(data::text) FILTER(WHERE sku LIKE 'participant:%')::jsonb AS participant
              FROM marketplace_promotion_stage WHERE run_id=:run AND sku<>''
              GROUP BY external_id,data->>'id'
            )
            INSERT INTO marketplace_promotion_offer(organization_id,account_id,promotion_id,offer_id,
              external_id,promotion_type,status,base_price,promotion_price,minimum_price,maximum_price,
              participation_scope,complete,publication_id,valid_until)
            SELECT p.organization_id,p.account_id,p.id,o.id,p.external_id,p.promotion_type,
              CASE WHEN s.participant IS NULL THEN 'NOT_PARTICIPATING'
                WHEN s.participant->>'add_mode'='SELLER' THEN 'MANUAL' ELSE 'AUTO' END,
              (COALESCE(s.participant,s.candidate)#>>'{price,amount}')::numeric,
              (s.participant#>>'{action_price,amount}')::numeric,NULL,
              (COALESCE(s.participant,s.candidate)#>>'{max_action_price,amount}')::numeric,
              jsonb_build_object('candidate',s.candidate,'participant',s.participant,
                'profile','OZON_ACTIONS_V2','termsComplete',false),false,:run,p.valid_until
            FROM products s JOIN marketplace_promotion p ON p.external_id=s.external_id
            JOIN marketplace_offer o ON o.account_id=p.account_id AND o.external_id=s.product_id
            ON CONFLICT(organization_id,account_id,promotion_id,offer_id) DO UPDATE SET status=EXCLUDED.status,
              base_price=EXCLUDED.base_price,promotion_price=EXCLUDED.promotion_price,
              minimum_price=EXCLUDED.minimum_price,maximum_price=EXCLUDED.maximum_price,
              participation_scope=EXCLUDED.participation_scope,complete=EXCLUDED.complete,
              publication_id=EXCLUDED.publication_id,valid_until=EXCLUDED.valid_until,
              revision=marketplace_promotion_offer.revision+1
            """)
        .param("run", run.id())
        .update();
  }

  private JobOutcome finishPublication(JobContext context, CatalogSyncService.Run run) {
    jdbc.sql(
            """
            UPDATE marketplace_promotion_offer SET complete=false,valid_until=clock_timestamp()
            WHERE publication_id<>:run
            """)
        .param("run", run.id())
        .update();
    jdbc.sql(
            "UPDATE marketplace_promotion SET valid_until=clock_timestamp() WHERE"
                + " publication_id<>:run")
        .param("run", run.id())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_sync_run SET state='PUBLISHED',completed_at=clock_timestamp(),revision=revision+1
            WHERE id=:run
            """)
        .param("run", run.id())
        .update();
    boolean ozon = connections.current(context.scope()).marketplace().equals("OZON");
    jdbc.sql(
            """
            UPDATE marketplace_source SET status=:status,last_success_at=CASE WHEN :complete
                THEN :observed ELSE last_success_at END,publication_id=:run,
              revision=revision+1,reason=:reason WHERE source_type='PROMOTIONS'
            """)
        .param("run", run.id())
        .param("status", ozon ? "INCOMPLETE" : "READY")
        .param("complete", !ozon)
        .param("observed", Timestamp.from(run.startedAt()))
        .param("reason", ozon ? "PROMOTION_SCOPE_UNCONFIRMED" : null, java.sql.Types.VARCHAR)
        .update();
    audit.record(context.scope(), "source.published", run.id(), "source=PROMOTIONS");
    snapshots.refresh(context.scope(), run.id());
    outbox.emit(
        context.scope(),
        "promotions:" + run.id(),
        "source.changed",
        new OutboxService.EntityChange("promotions", context.scope().accountId(), run.page()));
    return JobOutcome.succeeded("{}");
  }

  private static BusinessException invalid(String code) {
    return new BusinessException(code, 422, "Полнота условий акции не подтверждена");
  }

  private record Row(String external, String sku, String data) {}
}
