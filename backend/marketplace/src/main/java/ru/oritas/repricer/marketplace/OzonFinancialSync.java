package ru.oritas.repricer.marketplace;

import java.io.IOException;
import java.io.InputStream;
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

/**
 * Read-only accrual and return traversal; partial days never become complete accounting periods.
 */
@Service
public final class OzonFinancialSync implements JobHandler {
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ConnectionService connections;
  private final CatalogSyncService sync;
  private final OutboundGateway gateway;
  private final CapabilityService capabilities;
  private final StoredFileService files;
  private final FinancialSourceService financial;
  private final JobRuntime jobs;
  private final IdempotencyService idempotency;
  private final JsonCodec json;
  private final OutboxService outbox;
  private final AuditService audit;
  private final Clock clock;

  public OzonFinancialSync(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      CatalogSyncService sync,
      OutboundGateway gateway,
      CapabilityService capabilities,
      StoredFileService files,
      FinancialSourceService financial,
      JobRuntime jobs,
      IdempotencyService idempotency,
      JsonCodec json,
      OutboxService outbox,
      AuditService audit,
      Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.connections = connections;
    this.sync = sync;
    this.gateway = gateway;
    this.capabilities = capabilities;
    this.files = files;
    this.financial = financial;
    this.jobs = jobs;
    this.idempotency = idempotency;
    this.json = json;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
  }

  UUID request(Scope scope, String kind, LocalDate from, LocalDate until, UUID requestId) {
    ScopeTransactionRunner.requireCurrent(scope);
    authorization.require(scope, "sync.request");
    authorization.require(scope, "finance.read");
    if (!Set.of("SERVICES", "RETURNS").contains(kind)
        || from == null
        || until == null
        || until.isBefore(from)
        || from.plusDays(30).isBefore(until)
        || from.isBefore(LocalDate.of(2022, 1, 1))) {
      throw invalid("OZON_FINANCIAL_PERIOD_UNSUPPORTED");
    }
    if (!connections.current(scope).marketplace().equals("OZON")) {
      throw invalid("OZON_FINANCIAL_ACCOUNT_REQUIRED");
    }
    return idempotency.execute(
        scope,
        "ozon-financial.request",
        requestId,
        new Request(kind, from, until),
        UUID.class,
        () -> {
          jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
              .param("id", scope.requireAccount())
              .query(UUID.class)
              .single();
          var active =
              jdbc.sql(
                      """
                      SELECT job_id FROM marketplace_sync_run WHERE source_type=:kind
                        AND state IN ('FETCHING','PARSING','VALIDATING') ORDER BY started_at LIMIT 1
                      """)
                  .param("kind", kind)
                  .query(UUID.class)
                  .optional();
          if (active.isPresent()) {
            return active.orElseThrow();
          }
          outbox.requireHeavyAdmission();
          UUID run = UUID.randomUUID();
          UUID job = jobs.submit(scope, type(), run.toString(), json.encode(new Work(run)));
          jdbc.sql(
                  """
                  INSERT INTO marketplace_sync_run(id,organization_id,account_id,job_id,source_type,
                    state,phase,range_from,range_until,range_cursor)
                  VALUES(:id,:org,:account,:job,:kind,'FETCHING',:phase,:from,:until,:from)
                  """)
              .param("id", run)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("job", job)
              .param("kind", kind)
              .param("phase", kind.equals("SERVICES") ? "TYPES" : "RETURN")
              .param("from", from)
              .param("until", until)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_source(id,organization_id,account_id,source_type,status)
                  VALUES(:id,:org,:account,:kind,'SYNCING')
                  ON CONFLICT(organization_id,account_id,source_type) DO UPDATE SET status='SYNCING',reason=NULL
                  """)
              .param("id", UUID.randomUUID())
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("kind", kind)
              .update();
          audit.record(scope, "ozon-financial.requested", run, "kind=" + kind);
          return job;
        });
  }

  @Override
  public String type() {
    return "OZON_FINANCIAL_SYNC";
  }

  @Override
  public String lane() {
    return "fetch";
  }

  @Override
  public JobOutcome execute(JobContext context) throws Exception {
    UUID id = json.decode(context.payload(), Work.class).runId();
    Run run =
        transactions.run(
            context.scope(),
            () -> {
              jobs.requireOwnership(context);
              authorization.require(context.scope(), "sync.request");
              authorization.require(context.scope(), "finance.read");
              return run(id);
            });
    if (run.state().equals("PUBLISHED")) {
      return JobOutcome.succeeded(json.encode(Map.of("complete", false)));
    }
    if (Set.of("INCOMPLETE", "FAILED").contains(run.state())) {
      return JobOutcome.blocked("FINANCIAL_TRAVERSAL_INCOMPLETE");
    }
    // Ozon's daily cursor lives for fifteen minutes. Never resume it after its proven lifetime.
    if (run.page() >= 2000 || !run.startedAt().plusSeconds(840).isAfter(clock.instant())) {
      sync.fail(context.scope(), id, "FINANCIAL_TRAVERSAL_LIMIT");
      return JobOutcome.blocked("FINANCIAL_TRAVERSAL_LIMIT");
    }
    try {
      if (run.phase().startsWith("PUBLISH_")) {
        if (!context.lane().equals("canonicalization")) {
          return JobOutcome.handoff("canonicalization", clock.instant());
        }
        return transactions.run(context.scope(), () -> publish(context, run));
      }
      return fetch(context, run);
    } catch (BusinessException exception) {
      sync.fail(context.scope(), id, exception.code());
      throw exception;
    } catch (IOException | com.google.gson.JsonParseException exception) {
      sync.fail(context.scope(), id, "FINANCIAL_SOURCE_INVALID");
      return JobOutcome.blocked("FINANCIAL_SOURCE_INVALID");
    }
  }

  private JobOutcome fetch(JobContext context, Run run) throws IOException, InterruptedException {
    var connection = transactions.run(context.scope(), () -> connections.current(context.scope()));
    Call call = request(run);
    var saved =
        transactions.run(
            context.scope(),
            () ->
                jdbc.sql(
                        """
                        SELECT file_id,content_encoding FROM marketplace_raw_page WHERE run_id=:run AND page_number=:page
                        """)
                    .param("run", run.id())
                    .param("page", run.page())
                    .query((row, index) -> new Raw(row.getObject(1, UUID.class), row.getString(2)))
                    .optional());
    String requiredLane = saved.isPresent() ? "canonicalization" : "fetch";
    if (!context.lane().equals(requiredLane)) {
      return JobOutcome.handoff(requiredLane, clock.instant());
    }
    OutboundGateway.Response response;
    if (saved.isPresent()) {
      Raw raw = saved.orElseThrow();
      response =
          new OutboundGateway.Response(
              null,
              200,
              transactions.run(context.scope(), () -> files.get(raw.id())),
              raw.encoding(),
              null,
              connection.revision());
    } else {
      response =
          gateway.executePage(
              context.scope(),
              run.id(),
              run.page(),
              call.method(),
              call.body(),
              null,
              run.cursor(),
              call.elements());
    }
    if (response.retryAt() != null) {
      return JobOutcome.waiting("QUOTA_WAIT", response.retryAt());
    }
    if (!response.successful()) {
      if (Set.of(420, 429, 500, 502, 503, 504).contains(response.status())) {
        return JobOutcome.waiting("SUPPLIER_TEMPORARY", clock.instant().plusSeconds(30));
      }
      throw invalid("FINANCIAL_SOURCE_REJECTED");
    }
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          jdbc.sql(
                  """
                  INSERT INTO marketplace_raw_page(organization_id,account_id,run_id,page_number,
                    file_id,cursor,method,content_encoding)
                  VALUES(:org,:account,:run,:page,:raw,:cursor,:method,:encoding)
                  ON CONFLICT(run_id,page_number) DO NOTHING
                  """)
              .param("org", context.scope().organizationId())
              .param("account", context.scope().accountId())
              .param("run", run.id())
              .param("page", run.page())
              .param("raw", response.raw().id())
              .param("cursor", run.cursor())
              .param("method", call.method().name())
              .param("encoding", response.encoding())
              .update();
          files.retain(response.raw().id(), "sync-run", run.id(), context.scope());
          return true;
        });
    if (saved.isEmpty()) {
      return JobOutcome.handoff("canonicalization", clock.instant());
    }
    List<OzonFinancialCanonicalizer.Observation> batch = new ArrayList<>();
    String[] lastId = new String[1];
    VendorJsonReader.PageInfo info;
    try (InputStream input = gateway.open(response)) {
      info =
          VendorJsonReader.parse(
              input,
              call.recordsPath(),
              source -> {
                var value =
                    switch (run.phase()) {
                      case "TYPES" -> OzonFinancialCanonicalizer.accrualType(source);
                      case "ACCRUAL" -> OzonFinancialCanonicalizer.accrual(source, run.date());
                      case "RETURN" ->
                          OzonFinancialCanonicalizer.returned(source, connection.externalId());
                      default -> throw invalid("FINANCIAL_PHASE_INVALID");
                    };
                lastId[0] = value.externalId();
                batch.add(value);
                if (batch.size() == 10) {
                  stage(context, run, response.raw().id(), batch);
                  batch.clear();
                }
              });
    }
    if (!batch.isEmpty()) {
      stage(context, run, response.raw().id(), batch);
    }
    String next = continuation(run, info, lastId[0]);
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          capabilities.confirmRead(context.scope(), call.method(), response.raw().id());
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
                  UPDATE marketplace_sync_run SET phase=:phase,batch_after=NULL,
                    parsed_count=parsed_count+:count WHERE id=:run AND phase=:previous AND page_number=:page
                  """)
              .param("phase", "PUBLISH_" + run.phase())
              .param("previous", run.phase())
              .param("page", run.page())
              .param("count", info.records())
              .param("run", run.id())
              .update();
          return true;
        });
    return waiting();
  }

  private Call request(Run run) {
    if (run.phase().equals("TYPES")) {
      return new Call(VendorMethod.OZON_ACCRUAL_TYPES, "{}", "accrual_types", 1);
    }
    if (run.phase().equals("ACCRUAL")) {
      return new Call(
          VendorMethod.OZON_ACCRUAL_DAY,
          json.encode(
              Map.of("date", run.date(), "last_id", run.cursor() == null ? "" : run.cursor())),
          "accruals",
          1);
    }
    long cursor;
    try {
      cursor = run.cursor() == null ? 0 : Long.parseLong(run.cursor());
    } catch (NumberFormatException exception) {
      throw invalid("FINANCIAL_CURSOR_INVALID");
    }
    return new Call(
        VendorMethod.OZON_RETURNS,
        json.encode(
            Map.of(
                "limit",
                500,
                "last_id",
                cursor,
                "filter",
                Map.of(
                    "visual_status_change_moment",
                    Map.of(
                        "time_from", run.from().atStartOfDay(run.zone()).toInstant(),
                        "time_to", run.until().plusDays(1).atStartOfDay(run.zone()).toInstant())))),
        "returns",
        500);
  }

  private static String continuation(Run run, VendorJsonReader.PageInfo info, String lastId) {
    if (run.phase().equals("TYPES")) {
      return null;
    }
    String next = info.cursor();
    if (run.phase().equals("RETURN")) {
      if (info.hasNext() == null || (info.hasNext() && lastId == null)) {
        throw invalid("FINANCIAL_CONTINUATION_UNKNOWN");
      }
      next = info.hasNext() ? lastId : null;
    }
    if (next != null && next.isEmpty()) {
      next = null;
    }
    if (next != null && (next.equals(run.cursor()) || info.records() == 0)) {
      throw invalid("FINANCIAL_CURSOR_STALLED");
    }
    return next;
  }

  private void stage(
      JobContext context, Run run, UUID raw, List<OzonFinancialCanonicalizer.Observation> batch) {
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          for (var value : batch) {
            String encoded = OzonFinancialCanonicalizer.encode(value, json);
            int added =
                jdbc.sql(
                        """
                        INSERT INTO marketplace_history_stage(organization_id,account_id,run_id,source_kind,
                          external_id,data,raw_file_id) VALUES(:org,:account,:run,:kind,:external,CAST(:data AS jsonb),:raw)
                        ON CONFLICT(run_id,source_kind,external_id) DO NOTHING
                        """)
                    .param("org", context.scope().organizationId())
                    .param("account", context.scope().accountId())
                    .param("run", run.id())
                    .param("kind", stageKind(run))
                    .param("external", value.externalId())
                    .param("data", encoded)
                    .param("raw", raw)
                    .update();
            if (added == 0
                && !jdbc.sql(
                        """
                        SELECT data=CAST(:data AS jsonb) FROM marketplace_history_stage
                        WHERE run_id=:run AND source_kind=:kind AND external_id=:external
                        """)
                    .param("data", encoded)
                    .param("run", run.id())
                    .param("kind", stageKind(run))
                    .param("external", value.externalId())
                    .query(Boolean.class)
                    .single()) {
              throw invalid("FINANCIAL_DUPLICATE_CONFLICT");
            }
          }
          return true;
        });
  }

  private JobOutcome publish(JobContext context, Run run) {
    jobs.requireOwnership(context);
    var rows =
        jdbc.sql(
                """
                SELECT external_id,data::text,raw_file_id FROM marketplace_history_stage
                WHERE run_id=:run AND source_kind=:kind AND external_id>COALESCE(:after,'')
                ORDER BY external_id LIMIT 10
                """)
            .param("run", run.id())
            .param("kind", stageKind(run))
            .param("after", run.after())
            .query(
                (row, index) ->
                    new Staged(
                        row.getString(1),
                        OzonFinancialCanonicalizer.decode(row.getString(2), json),
                        row.getObject(3, UUID.class)))
            .list();
    if (!rows.isEmpty()) {
      for (Staged row : rows) {
        financial.publishOzon(
            context.scope(), run.id(), run.startedAt(), row.raw(), row.value(), outbox);
      }
      jdbc.sql("UPDATE marketplace_sync_run SET batch_after=:after WHERE id=:run")
          .param("after", rows.getLast().id())
          .param("run", run.id())
          .update();
      jobs.requireOwnership(context);
      return waiting();
    }
    String next =
        jdbc.sql(
                "SELECT next_cursor FROM marketplace_raw_page WHERE run_id=:run AND"
                    + " page_number=:page AND parsed")
            .param("run", run.id())
            .param("page", run.page())
            .query((row, index) -> new Cursor(row.getString(1)))
            .single()
            .value();
    String phase = run.phase().substring("PUBLISH_".length());
    LocalDate date = run.date();
    if (phase.equals("TYPES")) {
      phase = "ACCRUAL";
    } else if (next == null) {
      if (phase.equals("ACCRUAL") && date.isBefore(run.until())) {
        date = date.plusDays(1);
      } else {
        return finish(context, run);
      }
    }
    jdbc.sql(
            """
            UPDATE marketplace_sync_run SET phase=:phase,page_number=page_number+1,next_cursor=:next,
              range_cursor=:date,batch_after=NULL WHERE id=:run
            """)
        .param("phase", phase)
        .param("next", next)
        .param("date", date)
        .param("run", run.id())
        .update();
    return waiting();
  }

  private JobOutcome finish(JobContext context, Run run) {
    String reason =
        run.kind().equals("SERVICES")
            ? "ACCRUAL_PERIOD_COVERAGE_AND_FEE_POLARITY_UNCONFIRMED"
            : "RETURN_MONEY_AND_STOCK_PROOF_INCOMPLETE";
    jdbc.sql(
            """
            UPDATE marketplace_sync_run SET state='PUBLISHED',completed_at=clock_timestamp(),
              revision=revision+1,reason=:reason WHERE id=:run
            """)
        .param("run", run.id())
        .param("reason", reason)
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_source SET status='INCOMPLETE',publication_id=:run,
              last_success_at=:observed,revision=revision+1,reason=:reason WHERE source_type=:kind
            """)
        .param("run", run.id())
        .param("observed", Timestamp.from(run.startedAt()))
        .param("reason", reason)
        .param("kind", run.kind())
        .update();
    outbox.emit(
        context.scope(),
        "ozon-financial-finished:" + run.id(),
        "source.changed",
        new OutboxService.EntityChange("sync-runs", run.id(), run.page() + 1L));
    audit.record(context.scope(), "ozon-financial.observed", run.id(), "kind=" + run.kind());
    return JobOutcome.succeeded(json.encode(Map.of("complete", false, "reason", reason)));
  }

  private Run run(UUID id) {
    return jdbc.sql(
            """
            SELECT r.*,a.timezone FROM marketplace_sync_run r JOIN marketplace_account a ON a.id=r.account_id
            WHERE r.id=:id FOR SHARE OF r
            """)
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
                  row.getString("batch_after"),
                  row.getObject("range_from", LocalDate.class),
                  row.getObject("range_until", LocalDate.class),
                  row.getObject("range_cursor", LocalDate.class),
                  row.getTimestamp("started_at").toInstant(),
                  ZoneId.of(row.getString("timezone")));
            })
        .single();
  }

  private static String stageKind(Run run) {
    return "OZON_FINANCIAL:" + run.page();
  }

  private JobOutcome waiting() {
    return JobOutcome.waiting("FINANCIAL_SOURCE_CONTINUES", clock.instant().plusSeconds(1));
  }

  private static BusinessException invalid(String code) {
    return new BusinessException(code, 422, "Финансовый источник Ozon не подтверждён полностью");
  }

  private record Request(String kind, LocalDate from, LocalDate until) {}

  record Work(UUID runId) {}

  private record Run(
      UUID id,
      String kind,
      String state,
      String phase,
      int page,
      String cursor,
      String after,
      LocalDate from,
      LocalDate until,
      LocalDate date,
      Instant startedAt,
      ZoneId zone) {}

  private record Call(VendorMethod method, String body, String recordsPath, int elements) {}

  private record Raw(UUID id, String encoding) {}

  private record Staged(String id, OzonFinancialCanonicalizer.Observation value, UUID raw) {}

  private record Cursor(String value) {}
}
