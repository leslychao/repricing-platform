package ru.oritas.repricer.marketplace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** Reads business orders and their original composition before publishing demand. */
@Component
public final class HistorySyncHandler implements JobHandler {
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ConnectionService connections;
  private final CatalogSyncService catalog;
  private final OutboundGateway gateway;
  private final CapabilityService capabilities;
  private final StoredFileService files;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final OutboxService outbox;
  private final AuditService audit;
  private final Clock clock;

  public HistorySyncHandler(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      CatalogSyncService catalog,
      OutboundGateway gateway,
      CapabilityService capabilities,
      StoredFileService files,
      JobRuntime jobs,
      JsonCodec json,
      OutboxService outbox,
      AuditService audit,
      Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.connections = connections;
    this.catalog = catalog;
    this.gateway = gateway;
    this.capabilities = capabilities;
    this.files = files;
    this.jobs = jobs;
    this.json = json;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
  }

  @Override
  public String type() {
    return "HISTORY_SYNC";
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
              authorization.require(context.scope(), "finance.read");
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
      if (run.phase().equals("CANONICALIZE")) {
        if (!context.lane().equals("canonicalization")) {
          return JobOutcome.handoff("canonicalization", clock.instant());
        }
        return transactions.run(context.scope(), () -> canonicalize(context, run));
      }
      if (run.phase().equals("PUBLISH")) {
        if (!context.lane().equals("canonicalization")) {
          return JobOutcome.handoff("canonicalization", clock.instant());
        }
        return transactions.run(context.scope(), () -> publish(context, run));
      }
      Period period = transactions.run(context.scope(), () -> period(runId));
      boolean business = run.phase().equals("BUSINESS");
      if (!ozon && !business) {
        transactions.run(context.scope(), () -> requireCampaign(period.campaign()));
      }
      ParentPosting parent =
          ozon && run.phase().equals("OZON_PARENTS")
              ? transactions.run(context.scope(), () -> parentCheckpoint(context, runId))
              : null;
      if (ozon && run.phase().equals("OZON_PARENTS") && parent == null) {
        if (!context.lane().equals("canonicalization")) {
          return JobOutcome.handoff("canonicalization", clock.instant());
        }
        return transactions.run(context.scope(), () -> publishOzonPostings(context, run));
      }
      VendorMethod method;
      if (ozon) {
        method =
            parent == null
                ? (business ? VendorMethod.OZON_FBS_ORDERS : VendorMethod.OZON_FBO_ORDERS)
                : (parent.kind().equals("OZON_FBS")
                    ? VendorMethod.OZON_FBS_ORDER
                    : VendorMethod.OZON_FBO_ORDER);
      } else {
        method = business ? VendorMethod.YANDEX_ORDERS : VendorMethod.YANDEX_ORDER_STATS;
      }
      Map<String, Object> body =
          ozon
              ? ozonBody(context, run, period, parent)
              : business
                  ? Map.of(
                      "fake",
                      false,
                      "dates",
                      Map.of(
                          "creationDateFrom",
                          period.windowFrom().toString(),
                          "creationDateTo",
                          period.windowUntil().toString()))
                  : Map.of(
                      "dateFrom",
                      period.from().minusDays(1).toString(),
                      "dateTo",
                      period.until().plusDays(1).toString());
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
                json.encode(body),
                ozon ? null : period.campaign(),
                run.cursor(),
                business ? 50 : 100);
        if (response.retryAt() != null) {
          return JobOutcome.waiting("QUOTA_WAIT", response.retryAt());
        }
        if (!response.successful()) {
          if (response.status() >= 500 || Set.of(420, 429).contains(response.status())) {
            return JobOutcome.waiting("SUPPLIER_TEMPORARY", clock.instant().plusSeconds(60));
          }
          throw invalid("HISTORY_SOURCE_REJECTED");
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
      UUID raw = response.raw().id();
      VendorJsonReader.PageInfo info;
      try (InputStream input = gateway.open(response)) {
        if (parent != null) {
          JsonObject posting =
              OrderCanonicalizer.ozonPosting(VendorJsonReader.single(input, "result"));
          if (!parent.id().equals(posting.get("posting_number").getAsString())) {
            throw invalid("SHIPMENT_PARENT_IDENTITY_MISMATCH");
          }
          stage(context, run, parent.kind(), parent.id(), null, posting, raw);
          info = new VendorJsonReader.PageInfo(1, null, null, false);
        } else {
          info =
              VendorJsonReader.parse(
                  input,
                  ozon ? "postings" : business ? "orders" : "result.orders",
                  order -> {
                    if (ozon) {
                      JsonObject posting = OrderCanonicalizer.ozonPosting(order);
                      stage(
                          context,
                          run,
                          business ? "OZON_FBS" : "OZON_FBO",
                          posting.get("posting_number").getAsString(),
                          null,
                          posting,
                          raw);
                      return;
                    }
                    if (!order.has("fake")
                        || !order.get("fake").isJsonPrimitive()
                        || !order.get("fake").getAsJsonPrimitive().isBoolean()) {
                      throw invalid("REAL_ORDER_NOT_PROVEN");
                    }
                    if (order.get("fake").getAsBoolean()) {
                      if (business) {
                        throw invalid("REAL_ORDER_FILTER_REJECTED");
                      }
                      return;
                    }
                    stage(
                        context,
                        run,
                        business ? "BUSINESS" : "STATS",
                        CatalogSyncService.text(order, business ? "orderId" : "id", true),
                        business
                            ? CatalogSyncService.text(order, "campaignId", true)
                            : period.campaign(),
                        order,
                        raw);
                  });
        }
      }
      transactions.run(
          context.scope(),
          () -> {
            if (ozon) {
              advanceOzon(context, run, info, method, raw);
            } else {
              advance(context, run, period, info, method, raw);
            }
          });
      return JobOutcome.handoff("fetch", clock.instant().plusMillis(100));
    } catch (BusinessException exception) {
      catalog.fail(context.scope(), runId, exception.code());
      return JobOutcome.blocked(exception.code());
    } catch (java.io.IOException | com.google.gson.JsonParseException exception) {
      catalog.fail(context.scope(), runId, "HISTORY_SOURCE_INVALID");
      return JobOutcome.blocked("HISTORY_SOURCE_INVALID");
    }
  }

  private Map<String, Object> ozonBody(
      JobContext context, CatalogSyncService.Run run, Period period, ParentPosting parent) {
    if (parent != null) {
      return Map.of(
          "posting_number",
          parent.id(),
          "with",
          Map.of("analytics_data", false, "financial_data", false));
    }
    ZoneId zone =
        transactions.run(
            context.scope(),
            () ->
                ZoneId.of(
                    jdbc.sql("SELECT timezone FROM marketplace_account WHERE id=:id")
                        .param("id", context.scope().accountId())
                        .query(String.class)
                        .single()));
    return Map.of(
        "cursor",
        run.cursor() == null ? "" : run.cursor(),
        "limit",
        100,
        "sort_dir",
        "ASC",
        "filter",
        Map.of(
            "since", period.from().atStartOfDay(zone).toInstant().toString(),
            "to",
                period.until().plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1).toString()),
        "with",
        Map.of("analytics_data", false, "financial_data", false));
  }

  private ParentPosting parentCheckpoint(JobContext context, UUID run) {
    jobs.requireOwnership(context);
    String checkpoint =
        jdbc.sql("SELECT batch_after FROM marketplace_sync_run WHERE id=:run FOR UPDATE")
            .param("run", run)
            .query(String.class)
            .optional()
            .orElse(null);
    if (checkpoint != null) {
      return json.decode(checkpoint, ParentPosting.class);
    }
    ParentPosting parent =
        jdbc.sql(
                """
                SELECT s.source_kind,s.data->>'parent_posting_number' AS parent
                FROM marketplace_history_stage s WHERE s.run_id=:run AND s.source_kind IN ('OZON_FBS','OZON_FBO')
                  AND COALESCE(s.data->>'parent_posting_number','')<>'' AND NOT EXISTS(
                    SELECT 1 FROM marketplace_history_stage parent WHERE parent.run_id=s.run_id
                      AND parent.source_kind=s.source_kind AND parent.external_id=s.data->>'parent_posting_number')
                ORDER BY s.external_id LIMIT 1
                """)
            .param("run", run)
            .query((row, index) -> new ParentPosting(row.getString(1), row.getString(2)))
            .optional()
            .orElse(null);
    if (parent != null) {
      jdbc.sql("UPDATE marketplace_sync_run SET batch_after=:parent WHERE id=:run")
          .param("parent", json.encode(parent))
          .param("run", run)
          .update();
    }
    return parent;
  }

  private void advanceOzon(
      JobContext context,
      CatalogSyncService.Run run,
      VendorJsonReader.PageInfo info,
      VendorMethod method,
      UUID raw) {
    jobs.requireOwnership(context);
    String phase = run.phase();
    String cursor = info.cursor();
    if (!phase.equals("OZON_PARENTS")) {
      if (info.hasNext() == null) {
        throw invalid("HISTORY_CONTINUATION_UNKNOWN");
      }
      if (info.hasNext()) {
        if (info.records() == 0
            || cursor == null
            || cursor.isBlank()
            || cursor.equals(run.cursor())) {
          throw invalid("CURSOR_STALLED");
        }
      } else {
        phase = phase.equals("BUSINESS") ? "OZON_FBO" : "OZON_PARENTS";
        cursor = null;
      }
    }
    jdbc.sql(
            "UPDATE marketplace_raw_page SET parsed=true,item_count=:count,next_cursor=:cursor"
                + " WHERE run_id=:run AND page_number=:page")
        .param("count", info.records())
        .param("cursor", cursor)
        .param("run", run.id())
        .param("page", run.page())
        .update();
    jdbc.sql(
            "UPDATE marketplace_sync_run SET"
                + " phase=:phase,next_cursor=:cursor,batch_after=NULL,page_number=page_number+1,parsed_count=parsed_count+:count"
                + " WHERE id=:run")
        .param("phase", phase)
        .param("cursor", cursor)
        .param("count", info.records())
        .param("run", run.id())
        .update();
    capabilities.confirmRead(context.scope(), method, raw);
  }

  private JobOutcome publishOzonPostings(JobContext context, CatalogSyncService.Run run) {
    jobs.requireOwnership(context);
    boolean invalidParent =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_history_stage child
                  JOIN marketplace_history_stage parent ON parent.run_id=child.run_id
                    AND parent.source_kind=child.source_kind AND parent.external_id=child.data->>'parent_posting_number'
                  WHERE child.run_id=:run AND child.data->>'order_id' IS DISTINCT FROM parent.data->>'order_id')
                """)
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    if (invalidParent) {
      throw invalid("SHIPMENT_PARENT_ORDER_MISMATCH");
    }
    boolean cyclic =
        jdbc.sql(
                """
                WITH RECURSIVE ancestry AS (
                  SELECT external_id,source_kind,data->>'parent_posting_number' AS parent,
                    ARRAY[external_id] AS path,false AS cycle,1 AS depth
                  FROM marketplace_history_stage WHERE run_id=:run AND source_kind IN ('OZON_FBS','OZON_FBO')
                  UNION ALL
                  SELECT p.external_id,p.source_kind,p.data->>'parent_posting_number',
                    a.path||p.external_id,p.external_id=ANY(a.path),a.depth+1
                  FROM ancestry a JOIN marketplace_history_stage p ON p.run_id=:run
                    AND p.source_kind=a.source_kind AND p.external_id=a.parent
                  WHERE NOT a.cycle AND a.depth<=64
                ) SELECT EXISTS(SELECT 1 FROM ancestry WHERE cycle OR depth>64)
                """)
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    if (cyclic) {
      throw invalid("SHIPMENT_PARENT_CYCLE_OR_LIMIT");
    }
    jdbc.sql(
            """
            INSERT INTO marketplace_shipment_observation(organization_id,account_id,source_run_id,
              model,shipment_id,order_id,parent_shipment_id,observed_at,composition,raw_file_id)
            SELECT organization_id,account_id,run_id,source_kind,external_id,data->>'order_id',
              NULLIF(data->>'parent_posting_number',''),:observed,data,raw_file_id
            FROM marketplace_history_stage WHERE run_id=:run AND source_kind IN ('OZON_FBS','OZON_FBO')
            ON CONFLICT(source_run_id,model,shipment_id) DO NOTHING
            """)
        .param("run", run.id())
        .param("observed", Timestamp.from(run.startedAt()))
        .update();
    // The documented list interval is processing time, not complete original order creation time.
    catalog.recordIncompletePublication(
        context.scope(), run.id(), "OZON_ORIGINAL_ORDER_SCOPE_UNCONFIRMED");
    audit.record(context.scope(), "history.shipments.observed", run.id(), "demandComplete=false");
    return JobOutcome.blocked("OZON_ORIGINAL_ORDER_SCOPE_UNCONFIRMED");
  }

  private void advance(
      JobContext context,
      CatalogSyncService.Run run,
      Period period,
      VendorJsonReader.PageInfo info,
      VendorMethod method,
      UUID raw) {
    jobs.requireOwnership(context);
    if (info.cursor() != null && info.cursor().equals(run.cursor())) {
      throw invalid("CURSOR_STALLED");
    }
    String next = info.cursor();
    String phase = run.phase();
    String campaign = period.campaign();
    LocalDate windowFrom = period.windowFrom();
    if (next == null || next.isBlank()) {
      next = null;
      if (phase.equals("BUSINESS") && period.windowUntil().isBefore(period.until().plusDays(1))) {
        windowFrom = period.windowUntil().plusDays(1);
      } else {
        campaign =
            jdbc.sql(
                    """
                    SELECT DISTINCT campaign_id FROM marketplace_history_stage WHERE run_id=:run
                      AND source_kind='BUSINESS' AND (:previous IS NULL OR campaign_id>:previous)
                    ORDER BY campaign_id LIMIT 1
                    """)
                .param("run", run.id())
                .param("previous", campaign, java.sql.Types.VARCHAR)
                .query(String.class)
                .optional()
                .orElse(null);
        phase = campaign == null ? "CANONICALIZE" : "STATS";
      }
    }
    jdbc.sql(
            """
            UPDATE marketplace_raw_page SET parsed=true,item_count=:count,next_cursor=:next
            WHERE run_id=:run AND page_number=:page
            """)
        .param("count", info.records())
        .param("next", next)
        .param("run", run.id())
        .param("page", run.page())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_sync_run SET phase=:phase,next_cursor=:next,batch_after=:campaign,
              range_cursor=:window,page_number=page_number+1,parsed_count=parsed_count+:count WHERE id=:run
            """)
        .param("phase", phase)
        .param("next", next)
        .param("campaign", campaign)
        .param("window", windowFrom)
        .param("count", info.records())
        .param("run", run.id())
        .update();
    capabilities.confirmRead(context.scope(), method, raw);
  }

  private JobOutcome canonicalize(JobContext context, CatalogSyncService.Run run) {
    jobs.requireOwnership(context);
    List<Original> originals =
        jdbc.sql(
                """
                SELECT * FROM (SELECT page.*,sum(octet_length(business)+COALESCE(octet_length(stats),0))
                  OVER (ORDER BY external_id) AS batch_bytes FROM (
                SELECT b.external_id,b.campaign_id,b.data::text AS business,s.data::text AS stats,s.raw_file_id
                FROM marketplace_history_stage b LEFT JOIN marketplace_history_stage s
                  ON s.run_id=b.run_id AND s.source_kind='STATS' AND s.external_id=b.external_id
                WHERE b.run_id=:run AND b.source_kind='BUSINESS' AND NOT EXISTS(
                  SELECT 1 FROM marketplace_history_stage done WHERE done.run_id=b.run_id
                    AND done.source_kind='CANONICAL' AND done.external_id=b.external_id)
                ORDER BY b.external_id LIMIT 20
                ) page) bounded WHERE batch_bytes<=4194304 ORDER BY external_id
                """)
            .param("run", run.id())
            .query(
                (row, index) ->
                    new Original(
                        row.getString("external_id"),
                        row.getString("campaign_id"),
                        row.getString("business"),
                        row.getString("stats"),
                        row.getObject("raw_file_id", UUID.class)))
            .list();
    if (originals.isEmpty()) {
      jdbc.sql("UPDATE marketplace_sync_run SET phase='PUBLISH' WHERE id=:id")
          .param("id", run.id())
          .update();
      return waiting();
    }
    for (Original original : originals) {
      if (original.stats() == null) {
        throw invalid("ORIGINAL_COMPOSITION_MISSING");
      }
      JsonObject canonical =
          OrderCanonicalizer.yandex(
              JsonParser.parseString(original.business()).getAsJsonObject(),
              JsonParser.parseString(original.stats()).getAsJsonObject());
      stage(
          context, run, "CANONICAL", original.id(), original.campaign(), canonical, original.raw());
    }
    return waiting();
  }

  private void stage(
      JobContext context,
      CatalogSyncService.Run run,
      String kind,
      String id,
      String campaign,
      JsonObject data,
      UUID raw) {
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          if (kind.equals("BUSINESS")) {
            requireCampaign(campaign);
          }
          int inserted =
              jdbc.sql(
                      """
                      INSERT INTO marketplace_history_stage(organization_id,account_id,run_id,source_kind,
                        external_id,campaign_id,data,raw_file_id)
                      VALUES (:org,:account,:run,:kind,:id,:campaign,CAST(:data AS jsonb),:raw)
                      ON CONFLICT(run_id,source_kind,external_id) DO UPDATE SET data=EXCLUDED.data
                      WHERE marketplace_history_stage.data=EXCLUDED.data
                      """)
                  .param("org", context.scope().organizationId())
                  .param("account", context.scope().accountId())
                  .param("run", run.id())
                  .param("kind", kind)
                  .param("id", id)
                  .param("campaign", campaign)
                  .param("data", data.toString())
                  .param("raw", raw)
                  .update();
          if (inserted != 1) {
            throw invalid("HISTORY_REVISION_CONFLICT");
          }
        });
  }

  private void requireCampaign(String campaign) {
    boolean verified =
        jdbc.sql("SELECT EXISTS(SELECT 1 FROM marketplace_campaign WHERE external_id=:id)")
            .param("id", campaign)
            .query(Boolean.class)
            .single();
    if (!verified) {
      throw invalid("ORDER_CAMPAIGN_SCOPE_UNCONFIRMED");
    }
  }

  private JobOutcome publish(JobContext context, CatalogSyncService.Run run) {
    jobs.requireOwnership(context);
    jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
        .param("id", context.scope().accountId())
        .query(UUID.class)
        .single();
    boolean unknown =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_history_stage s,
                  LATERAL jsonb_array_elements(s.data->'items') item WHERE s.run_id=:run
                  AND s.source_kind='CANONICAL' AND NOT EXISTS(
                    SELECT 1 FROM marketplace_offer o WHERE o.sku=item->>'sku'))
                """)
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    if (unknown) {
      throw invalid("ORDER_OFFER_UNKNOWN");
    }
    boolean conflict =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_history_stage s,
                  LATERAL jsonb_array_elements(s.data->'items') item,marketplace_order_item old
                  WHERE s.run_id=:run AND s.source_kind='CANONICAL' AND old.order_id=s.external_id
                  AND old.line_id='sku:'||(item->>'sku') AND (
                    old.campaign_id IS DISTINCT FROM s.campaign_id OR
                    old.created_at<>(s.data->>'createdAt')::timestamptz OR (
                      old.updated_at=(s.data->>'updatedAt')::timestamptz AND
                      old.content_hash<>encode(sha256(convert_to(item::text,'UTF8')),'hex'))))
                """)
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    if (conflict) {
      throw invalid("ORDER_SOURCE_REVISION_CONFLICT");
    }
    jdbc.sql(
            """
            INSERT INTO marketplace_order_item(id,organization_id,account_id,order_id,line_id,
              campaign_id,offer_id,created_at,updated_at,current_quantity,original_quantity,
              original_composition_confirmed,source_revision,content_hash,raw_file_id)
            SELECT md5(s.account_id::text||':order:'||s.external_id||':'||(item->>'sku'))::uuid,
              s.organization_id,s.account_id,s.external_id,'sku:'||(item->>'sku'),s.campaign_id,o.id,
              (s.data->>'createdAt')::timestamptz,(s.data->>'updatedAt')::timestamptz,
              (item->>'current')::numeric,(item->>'original')::numeric,true,s.data->>'updatedAt',
              encode(sha256(convert_to(item::text,'UTF8')),'hex'),s.raw_file_id
            FROM marketplace_history_stage s,LATERAL jsonb_array_elements(s.data->'items') item,
              marketplace_offer o WHERE s.run_id=:run AND s.source_kind='CANONICAL' AND o.sku=item->>'sku'
            ON CONFLICT(organization_id,account_id,order_id,line_id) DO UPDATE SET
              updated_at=EXCLUDED.updated_at,current_quantity=EXCLUDED.current_quantity,
              original_quantity=EXCLUDED.original_quantity,original_composition_confirmed=true,
              source_revision=EXCLUDED.source_revision,content_hash=EXCLUDED.content_hash,
              raw_file_id=EXCLUDED.raw_file_id,revision=marketplace_order_item.revision+
                CASE WHEN marketplace_order_item.content_hash<>EXCLUDED.content_hash THEN 1 ELSE 0 END
            WHERE EXCLUDED.updated_at>marketplace_order_item.updated_at
            """)
        .param("run", run.id())
        .update();
    jdbc.sql(
            """
            INSERT INTO marketplace_history_day(organization_id,account_id,placement_id,day,
              ordered_units,orders_complete,stock_continuous,regime,sources_valid_until,raw_file_id)
            SELECT p.organization_id,p.account_id,p.id,day::date,COALESCE(sum(i.original_quantity),0),
              true,false,p.model,clock_timestamp()+interval '15 minutes',
              (SELECT file_id FROM marketplace_raw_page WHERE run_id=:run ORDER BY page_number DESC LIMIT 1)
            FROM marketplace_placement p JOIN marketplace_account a ON a.id=p.account_id
            CROSS JOIN marketplace_sync_run r
            CROSS JOIN LATERAL generate_series(r.range_from,r.range_until,interval '1 day') day
            LEFT JOIN marketplace_order_item i ON i.offer_id=p.offer_id AND i.campaign_id=p.external_id
              AND (i.created_at AT TIME ZONE a.timezone)::date=day::date AND i.original_composition_confirmed
            WHERE r.id=:run GROUP BY p.organization_id,p.account_id,p.id,day,p.model
            ON CONFLICT(organization_id,account_id,placement_id,day) DO UPDATE SET
              ordered_units=EXCLUDED.ordered_units,orders_complete=EXCLUDED.orders_complete,
              regime=EXCLUDED.regime,sources_valid_until=EXCLUDED.sources_valid_until,
              raw_file_id=EXCLUDED.raw_file_id
            """)
        .param("run", run.id())
        .update();
    jdbc.sql(
            """
            INSERT INTO marketplace_demand_observation(organization_id,account_id,publication_id,demand_id,
              offer_id,accepted_at,original_quantity,raw_file_id)
            SELECT s.organization_id,s.account_id,s.run_id,
              md5(s.account_id::text||':order:'||s.external_id||':'||(item->>'sku'))::uuid,
              o.id,(s.data->>'createdAt')::timestamptz,(item->>'original')::numeric,s.raw_file_id
            FROM marketplace_history_stage s,LATERAL jsonb_array_elements(s.data->'items') item,
              marketplace_offer o WHERE s.run_id=:run AND s.source_kind='CANONICAL' AND o.sku=item->>'sku'
            ON CONFLICT(publication_id,demand_id) DO NOTHING
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
            UPDATE marketplace_source SET status='READY',last_success_at=clock_timestamp(),publication_id=:run,
              revision=revision+1,reason=NULL WHERE source_type='HISTORY'
            """)
        .param("run", run.id())
        .update();
    audit.record(context.scope(), "history.published", run.id(), "originalComposition=confirmed");
    outbox.emit(
        context.scope(),
        "history:" + run.id(),
        "source.changed",
        new OutboxService.EntityChange("sales-pace", context.scope().accountId(), run.page()));
    outbox.emit(
        context.scope(),
        "demand:" + run.id(),
        "history.published",
        new MarketplaceHistoryService.HistoryPublication(run.id(), 1));
    return JobOutcome.succeeded("{}");
  }

  private Period period(UUID run) {
    return jdbc.sql(
            """
            SELECT range_from,range_until,COALESCE(range_cursor,range_from-1) AS range_cursor,batch_after
            FROM marketplace_sync_run WHERE id=:run
            """)
        .param("run", run)
        .query(
            (row, index) -> {
              LocalDate from = row.getObject("range_from", LocalDate.class);
              LocalDate until = row.getObject("range_until", LocalDate.class);
              LocalDate start = row.getObject("range_cursor", LocalDate.class);
              LocalDate end =
                  start.plusDays(29).isBefore(until.plusDays(1))
                      ? start.plusDays(29)
                      : until.plusDays(1);
              return new Period(from, until, start, end, row.getString("batch_after"));
            })
        .single();
  }

  private JobOutcome waiting() {
    return JobOutcome.waiting("NEXT_PAGE", clock.instant().plusMillis(100));
  }

  private static BusinessException invalid(String code) {
    return new BusinessException(code, 422, "История не подтверждена полностью");
  }

  private record Period(
      LocalDate from,
      LocalDate until,
      LocalDate windowFrom,
      LocalDate windowUntil,
      String campaign) {}

  private record Original(String id, String campaign, String business, String stats, UUID raw) {}

  private record ParentPosting(String kind, String id) {}
}
