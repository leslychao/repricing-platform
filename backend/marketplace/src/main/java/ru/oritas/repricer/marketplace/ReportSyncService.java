package ru.oritas.repricer.marketplace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.FileWorkGate;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

/** One durable report identity from generation through bounded archive publication. */
@Service
public final class ReportSyncService implements JobHandler {
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ConnectionService connections;
  private final OutboundGateway gateway;
  private final JobRuntime jobs;
  private final IdempotencyService idempotency;
  private final JsonCodec json;
  private final StoredFileService files;
  private final FileWorkGate fileGate;
  private final AuditService audit;
  private final OutboxService outbox;
  private final Clock clock;
  private final ReportPublication publication;
  private final QuotaManager quotas;
  private final CapabilityService capabilities;
  private final OzonFinancialSync ozonFinancial;

  public ReportSyncService(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      OutboundGateway gateway,
      JobRuntime jobs,
      IdempotencyService idempotency,
      JsonCodec json,
      StoredFileService files,
      FileWorkGate fileGate,
      AuditService audit,
      OutboxService outbox,
      Clock clock,
      ReportPublication publication,
      QuotaManager quotas,
      CapabilityService capabilities,
      OzonFinancialSync ozonFinancial) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.connections = connections;
    this.gateway = gateway;
    this.jobs = jobs;
    this.idempotency = idempotency;
    this.json = json;
    this.files = files;
    this.fileGate = fileGate;
    this.audit = audit;
    this.outbox = outbox;
    this.clock = clock;
    this.publication = publication;
    this.quotas = quotas;
    this.capabilities = capabilities;
    this.ozonFinancial = ozonFinancial;
  }

  public UUID request(Scope scope, String kind, LocalDate from, LocalDate until, UUID requestId) {
    return request(scope, kind, from, until, null, requestId);
  }

  public UUID request(
      Scope scope,
      String kind,
      LocalDate from,
      LocalDate until,
      String campaignId,
      UUID requestId) {
    method(kind);
    if (from == null
        || until == null
        || until.isBefore(from)
        || from.plusDays(30).isBefore(until)) {
      throw invalid("INVALID_REPORT_PERIOD");
    }
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "sync.request");
          authorization.require(scope, "finance.read");
          if (connections.current(scope).marketplace().equals("OZON")) {
            if (campaignId != null) {
              throw invalid("REPORT_CAMPAIGN_NOT_APPLICABLE");
            }
            return ozonFinancial.request(scope, kind, from, until, requestId);
          }
          if (Set.of("SERVICES", "REALIZATION").contains(kind)
              && (from.getDayOfMonth() != 1 || !until.equals(from.plusMonths(1).minusDays(1)))) {
            throw invalid("REPORT_REQUIRES_CALENDAR_MONTH");
          }
          return idempotency.execute(
              scope,
              "supplier-report.request",
              requestId,
              new Request(kind, from, until, campaignId),
              UUID.class,
              () -> {
                outbox.requireHeavyAdmission();
                String campaign = realizationCampaign(kind, campaignId);
                UUID id = UUID.randomUUID();
                UUID job =
                    jobs.submit(scope, type(), id.toString(), json.encode(new ReportJob(id)));
                jdbc.sql(
                        """
                        INSERT INTO marketplace_report(id,organization_id,account_id,job_id,kind,
                          date_from,date_until,state,generation_call_id,campaign_id)
                        VALUES (:id,:org,:account,:job,:kind,:from,:until,'QUEUED',:call,:campaign)
                        """)
                    .param("id", id)
                    .param("org", scope.organizationId())
                    .param("account", scope.accountId())
                    .param("job", job)
                    .param("kind", kind)
                    .param("from", from)
                    .param("until", until)
                    .param("call", UUID.randomUUID())
                    .param("campaign", campaign)
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
                    .param("source", kind)
                    .update();
                audit.record(scope, "supplier-report.requested", id, "kind=" + kind);
                return job;
              });
        });
  }

  private String realizationCampaign(String kind, String campaign) {
    if (!kind.equals("REALIZATION")) {
      if (campaign != null) {
        throw invalid("REPORT_CAMPAIGN_NOT_APPLICABLE");
      }
      return null;
    }
    List<String> campaigns =
        jdbc.sql(
                """
                SELECT external_id FROM marketplace_campaign
                WHERE (CAST(:campaign AS text) IS NULL OR external_id=:campaign)
                  AND availability='AVAILABLE' ORDER BY external_id LIMIT 2
                """)
            .param("campaign", campaign)
            .query(String.class)
            .list();
    if (campaigns.size() != 1) {
      throw invalid("REPORT_CAMPAIGN_REQUIRED");
    }
    return campaigns.getFirst();
  }

  public Page<Campaign> campaigns(Scope scope, int page, int size) {
    authorization.require(scope, "catalog.read");
    int offset = Page.offset(page, size);
    long total = jdbc.sql("SELECT count(*) FROM marketplace_campaign").query(Long.class).single();
    var items =
        jdbc.sql(
                """
                SELECT external_id,placement_type,availability FROM marketplace_campaign
                ORDER BY external_id LIMIT :limit OFFSET :offset
                """)
            .param("limit", size)
            .param("offset", offset)
            .query(
                (row, index) -> new Campaign(row.getString(1), row.getString(2), row.getString(3)))
            .list();
    return new Page<>(items, total, page, size);
  }

  @Override
  public String type() {
    return "SUPPLIER_REPORT";
  }

  @Override
  public String lane() {
    return "fetch";
  }

  @Override
  public Duration maximumAttemptDuration() {
    return Duration.ofMinutes(30);
  }

  @Override
  public JobOutcome execute(JobContext context) throws Exception {
    UUID id = json.decode(context.payload(), ReportJob.class).id();
    Report report =
        transactions.run(
            context.scope(),
            () -> {
              jobs.requireOwnership(context);
              authorization.require(context.scope(), "sync.request");
              authorization.require(context.scope(), "finance.read");
              return report(id);
            });
    if (report.state().equals("PUBLISHED")) {
      return JobOutcome.succeeded("{}");
    }
    if (Set.of("BLOCKED", "UNKNOWN").contains(report.state())) {
      return JobOutcome.blocked("REPORT_REQUIRES_REVIEW");
    }
    try {
      String requiredLane = report.state().equals("STORED") ? "canonicalization" : "fetch";
      if (!context.lane().equals(requiredLane)) {
        return JobOutcome.handoff(requiredLane, clock.instant());
      }
      return switch (report.state()) {
        case "QUEUED" -> generate(context, report);
        case "GENERATING" -> recoverGeneration(context, report);
        case "WAITING" -> poll(context, report);
        case "STORED" -> parse(context, report);
        default -> throw new IllegalStateException("Unexpected report state");
      };
    } catch (BusinessException exception) {
      boolean uncertainGeneration =
          transactions.run(context.scope(), () -> report(report.id()).state().equals("GENERATING"));
      fail(context, report.id(), exception.code(), uncertainGeneration);
      return JobOutcome.blocked(exception.code());
    }
  }

  private JobOutcome generate(JobContext context, Report report)
      throws IOException, InterruptedException {
    if (!outbox.heavyAllowed()) {
      return waitFor("OUTBOX_BACKPRESSURE", 30);
    }
    boolean admitted =
        transactions.run(
            context.scope(),
            () -> {
              jobs.requireOwnership(context);
              jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
                  .param("id", context.scope().accountId())
                  .query(UUID.class)
                  .single();
              if (jdbc.sql(
                      """
                      SELECT EXISTS(SELECT 1 FROM marketplace_report WHERE id<>:id
                        AND state IN ('GENERATING','WAITING','UNKNOWN'))
                      """)
                  .param("id", report.id())
                  .query(Boolean.class)
                  .single()) {
                return false;
              }
              return jdbc.sql(
                          "UPDATE marketplace_report SET state='GENERATING' WHERE id=:id AND"
                              + " state='QUEUED'")
                      .param("id", report.id())
                      .update()
                  == 1;
            });
    if (!admitted) {
      return waitFor("REPORT_SLOT_WAIT", 30);
    }
    var connection = transactions.run(context.scope(), () -> connections.current(context.scope()));
    String body =
        json.encode(
            report.kind().equals("REALIZATION")
                ? Map.of(
                    "campaignId",
                    Long.parseLong(report.campaign()),
                    "year",
                    report.from().getYear(),
                    "month",
                    report.from().getMonthValue())
                : Map.of(
                    "businessId",
                    Long.parseLong(connection.externalId()),
                    "dateFrom",
                    report.from().toString(),
                    "dateTo",
                    report.until().toString()));
    try {
      var response =
          gateway.execute(
              context.scope(), method(report.kind()), body, null, null, 1, report.generationCall());
      if (response.retryAt() != null) {
        transactions.run(
            context.scope(),
            () ->
                jdbc.sql(
                        "UPDATE marketplace_report SET state='QUEUED' WHERE id=:id AND"
                            + " state='GENERATING'")
                    .param("id", report.id())
                    .update());
        return JobOutcome.waiting("QUOTA_WAIT", response.retryAt());
      }
      return recordGeneration(context, report, response);
    } catch (IOException | InterruptedException exception) {
      fail(context, report.id(), "REPORT_GENERATION_UNKNOWN", true);
      if (exception instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return JobOutcome.blocked("REPORT_GENERATION_UNKNOWN");
    }
  }

  private JobOutcome recoverGeneration(JobContext context, Report report) throws IOException {
    var response =
        transactions.run(
            context.scope(),
            () ->
                jdbc.sql(
                        """
                        SELECT status_code,raw_file_id,content_encoding,connection_revision
                        FROM marketplace_external_call WHERE id=:id AND outcome='RESPONSE'
                        """)
                    .param("id", report.generationCall())
                    .query(
                        (row, index) ->
                            new OutboundGateway.Response(
                                report.generationCall(),
                                row.getInt("status_code"),
                                files.get(row.getObject("raw_file_id", UUID.class)),
                                row.getString("content_encoding"),
                                null,
                                row.getLong("connection_revision")))
                    .optional());
    if (response.isEmpty()) {
      fail(context, report.id(), "REPORT_GENERATION_UNKNOWN", true);
      return JobOutcome.blocked("REPORT_GENERATION_UNKNOWN");
    }
    return recordGeneration(context, report, response.orElseThrow());
  }

  private JobOutcome recordGeneration(
      JobContext context, Report report, OutboundGateway.Response response) throws IOException {
    if (!response.successful()) {
      boolean rejected = Set.of(400, 401, 403, 404, 420, 422, 429).contains(response.status());
      if (rejected) {
        quotas.finishReport(report.generationCall());
      }
      fail(
          context,
          report.id(),
          rejected ? "REPORT_GENERATION_REJECTED" : "REPORT_GENERATION_UNKNOWN",
          !rejected);
      return JobOutcome.blocked(
          rejected ? "REPORT_GENERATION_REJECTED" : "REPORT_GENERATION_UNKNOWN");
    }
    String supplierId = CatalogSyncService.text(result(response), "reportId", true);
    if (!VendorMethod.validReportId(supplierId)) {
      fail(context, report.id(), "REPORT_ID_INVALID", true);
      return JobOutcome.blocked("REPORT_ID_INVALID");
    }
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          jdbc.sql(
                  """
                  UPDATE marketplace_report SET state='WAITING',supplier_report_id=:supplier,revision=revision+1
                  WHERE id=:id AND state='GENERATING'
                  """)
              .param("supplier", supplierId)
              .param("id", report.id())
              .update();
          files.retain(response.raw().id(), "supplier-report", report.id(), context.scope());
          capabilities.confirmRead(context.scope(), method(report.kind()), response.raw().id());
        });
    return waitFor("REPORT_PROCESSING", 10);
  }

  private JobOutcome poll(JobContext context, Report report)
      throws IOException, InterruptedException {
    var response =
        gateway.execute(
            context.scope(),
            VendorMethod.YANDEX_REPORT_INFO,
            "{}",
            report.supplierId(),
            null,
            1,
            null);
    if (response.retryAt() != null) {
      return JobOutcome.waiting("QUOTA_WAIT", response.retryAt());
    }
    if (!response.successful()) {
      return waitFor("REPORT_STATUS_UNAVAILABLE", 60);
    }
    JsonObject info = result(response);
    String status = CatalogSyncService.text(info, "status", true);
    if (!Set.of("PENDING", "PROCESSING", "FAILED", "DONE").contains(status)) {
      throw invalid("REPORT_STATUS_UNKNOWN");
    }
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          capabilities.confirmRead(
              context.scope(), VendorMethod.YANDEX_REPORT_INFO, response.raw().id());
        });
    if (Set.of("PENDING", "PROCESSING").contains(status)) {
      return waitFor("REPORT_PROCESSING", 30);
    }
    if (status.equals("FAILED")) {
      quotas.finishReport(report.generationCall());
      throw invalid("REPORT_GENERATION_FAILED");
    }
    if (!status.equals("DONE")) {
      throw invalid("REPORT_GENERATION_FAILED");
    }
    quotas.finishReport(report.generationCall());
    String subStatus = CatalogSyncService.text(info, "subStatus", false);
    if (subStatus != null && !subStatus.equals("NO_DATA")) {
      throw invalid("REPORT_INCOMPLETE");
    }
    if ("NO_DATA".equals(subStatus)) {
      Instant finished = Instant.parse(CatalogSyncService.text(info, "generationFinishedAt", true));
      transactions.run(
          context.scope(),
          () -> {
            jobs.requireOwnership(context);
            jdbc.sql(
                    """
                    UPDATE marketplace_report SET raw_file_id=:raw,generation_finished_at=:finished,
                      row_count=0,financial_complete=:complete AND date_until<
                        (CAST(:finished AS timestamptz) AT TIME ZONE
                          (SELECT timezone FROM marketplace_account WHERE id=account_id))::date
                    WHERE id=:id
                    """)
                .param("raw", response.raw().id())
                .param("finished", Timestamp.from(finished))
                .param("complete", Set.of("SERVICES", "REALIZATION").contains(report.kind()))
                .param("id", report.id())
                .update();
            files.retain(response.raw().id(), "supplier-report", report.id(), context.scope());
            finish(context, report, null);
          });
      return JobOutcome.succeeded("{}");
    }
    var permit = fileGate.tryAcquire(context.deadline());
    if (permit.isEmpty()) {
      return waitFor("FILE_SLOT_WAIT", 5);
    }
    try (var lease = permit.orElseThrow()) {
      String url = CatalogSyncService.text(info, "file", true);
      StoredFileService.FileRecord raw =
          gateway.downloadReport(context.scope(), report.id(), url, context.deadline());
      Instant finished = Instant.parse(CatalogSyncService.text(info, "generationFinishedAt", true));
      transactions.run(
          context.scope(),
          () -> {
            jobs.requireOwnership(context);
            lease.confirm();
            jdbc.sql(
                    """
                    UPDATE marketplace_report SET state='STORED',raw_file_id=:raw,
                      generation_finished_at=:finished,revision=revision+1 WHERE id=:id AND state='WAITING'
                    """)
                .param("raw", raw.id())
                .param("finished", Timestamp.from(finished))
                .param("id", report.id())
                .update();
          });
    } catch (BusinessException exception) {
      if (exception.code().equals("REPORT_DOWNLOAD_REJECTED")) {
        return waitFor("REPORT_LINK_REFRESH", 10);
      }
      throw exception;
    }
    return JobOutcome.handoff("canonicalization", clock.instant());
  }

  private JobOutcome parse(JobContext context, Report report) throws IOException {
    var permit = fileGate.tryAcquire(context.deadline());
    if (permit.isEmpty()) {
      return waitFor("FILE_SLOT_WAIT", 5);
    }
    Path temporary = null;
    try (var lease = permit.orElseThrow()) {
      var file = transactions.run(context.scope(), () -> files.get(report.raw()));
      Set<String> sheets = new HashSet<>();
      temporary = Files.createTempFile("repricer-report-", ".zip");
      try (InputStream raw = files.open(file);
          var output = Files.newOutputStream(temporary)) {
        byte[] buffer = new byte[65536];
        long copied = 0;
        int count;
        while ((count = raw.read(buffer)) != -1) {
          lease.requireValid();
          copied += count;
          if (copied > 2L * 1024 * 1024 * 1024) {
            throw invalid("REPORT_ARCHIVE_SIZE_LIMIT");
          }
          output.write(buffer, 0, count);
        }
      }
      try (ZipFile zip = new ZipFile(temporary.toFile(), StandardCharsets.UTF_8)) {
        long[] total = {0};
        long[] ordinal = {0};
        long[] factNumber = {0};
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          String name = entry.getName();
          if (entry.isDirectory()
              || !name.matches("[a-z][a-z0-9_-]{0,150}\\.json")
              || !sheets.add(name)
              || sheets.size() > 100) {
            throw invalid("REPORT_ARCHIVE_INVALID");
          }
          String sheet = name.substring(0, name.length() - 5);
          if (report.kind().equals("SERVICES") && !ServiceReportCanonicalizer.supports(sheet)) {
            throw invalid("SERVICE_REPORT_SHEET_UNSUPPORTED");
          }
          if (report.kind().equals("REALIZATION")
              && !RealizationReportCanonicalizer.supports(sheet)) {
            throw invalid("REALIZATION_SHEET_UNSUPPORTED");
          }
          List<Row> batch = new ArrayList<>(50);
          int[] batchBytes = {0};
          long[] rowNumber = {0};
          CRC32 checksum = new CRC32();
          InputStream boundedEntry =
              new FilterInputStream(new CheckedInputStream(zip.getInputStream(entry), checksum)) {
                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                  int count = in.read(buffer, offset, length);
                  if (count > 0) {
                    total[0] += count;
                    checkArchive(total[0], file.byteCount());
                  }
                  return count;
                }

                @Override
                public int read() throws IOException {
                  int value = in.read();
                  if (value != -1) {
                    checkArchive(++total[0], file.byteCount());
                  }
                  return value;
                }
              };
          VendorJsonReader.reportRowsSized(
              boundedEntry,
              (data, bytes) -> {
                lease.requireValid();
                if (batchBytes[0] + bytes > 4 * 1024 * 1024) {
                  factNumber[0] = stage(context, report, sheet, batch, factNumber[0]);
                  batch.clear();
                  batchBytes[0] = 0;
                }
                batch.add(new Row(++rowNumber[0], ++ordinal[0], data));
                batchBytes[0] += bytes;
                if (batch.size() == 50) {
                  factNumber[0] = stage(context, report, sheet, batch, factNumber[0]);
                  batch.clear();
                  batchBytes[0] = 0;
                }
              });
          if (!batch.isEmpty()) {
            factNumber[0] = stage(context, report, sheet, batch, factNumber[0]);
          }
          if (checksum.getValue() != entry.getCrc()) {
            throw invalid("REPORT_ARCHIVE_CHECKSUM");
          }
        }
        if (sheets.isEmpty()) {
          throw invalid("REPORT_ARCHIVE_EMPTY");
        }
        transactions.run(
            context.scope(),
            () -> {
              jobs.requireOwnership(context);
              lease.confirm();
              String reason =
                  publication.publish(
                      context.scope(), report.id(), report.kind(), report.finishedAt(), sheets);
              jdbc.sql(
                      """
                      UPDATE marketplace_report SET row_count=:rows,financial_complete=:complete
                      WHERE id=:id
                      """)
                  .param("rows", factNumber[0])
                  .param(
                      "complete",
                      Set.of("SERVICES", "REALIZATION").contains(report.kind()) && reason == null)
                  .param("id", report.id())
                  .update();
              finish(context, report, reason);
            });
      }
    } finally {
      if (temporary != null) {
        Files.deleteIfExists(temporary);
      }
    }
    return JobOutcome.succeeded("{}");
  }

  private long stage(
      JobContext context, Report report, String sheet, List<Row> rows, long afterFact) {
    return transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          record Account(String externalId, ZoneId zone) {}
          Account account =
              jdbc.sql("SELECT external_id,timezone FROM marketplace_account WHERE id=:id")
                  .param("id", context.scope().accountId())
                  .query((row, index) -> new Account(row.getString(1), ZoneId.of(row.getString(2))))
                  .single();
          Set<String> skus = new HashSet<>();
          if (Set.of("SERVICES", "REALIZATION").contains(report.kind())) {
            for (Row row : rows) {
              String sku =
                  report.kind().equals("SERVICES")
                      ? ServiceReportCanonicalizer.sku(row.data(), sheet)
                      : RealizationReportCanonicalizer.sku(row.data());
              if (sku != null && !sku.isBlank()) {
                skus.add(sku);
              }
            }
          }
          Map<String, UUID> offers = new java.util.HashMap<>();
          if (!skus.isEmpty()) {
            jdbc.sql("SELECT sku,id FROM marketplace_offer WHERE sku IN (:skus)")
                .param("skus", skus)
                .query((row, index) -> Map.entry(row.getString(1), row.getObject(2, UUID.class)))
                .list()
                .forEach(entry -> offers.put(entry.getKey(), entry.getValue()));
          }
          Map<String, UUID> originalLines = new java.util.HashMap<>();
          if (report.kind().equals("REALIZATION")) {
            Set<String> lineKeys = new HashSet<>();
            for (Row row : rows) {
              lineKeys.add(
                  CatalogSyncService.text(row.data(), "orderId", true)
                      + ":sku:"
                      + RealizationReportCanonicalizer.sku(row.data()));
            }
            var lines =
                jdbc.sql(
                        """
                        SELECT i.order_id,o.sku,i.id FROM marketplace_order_item i
                        JOIN marketplace_offer o ON o.id=i.offer_id
                        WHERE i.campaign_id=:campaign AND i.order_id||':sku:'||o.sku IN (:keys)
                          AND i.original_composition_confirmed
                        ORDER BY i.id LIMIT 51
                        """)
                    .param("campaign", report.campaign())
                    .param("keys", lineKeys)
                    .query(
                        (row, index) ->
                            Map.entry(
                                row.getString(1) + ":sku:" + row.getString(2),
                                row.getObject(3, UUID.class)))
                    .list();
            for (var line : lines) {
              if (originalLines.putIfAbsent(line.getKey(), line.getValue()) != null
                  || originalLines.size() > rows.size()) {
                throw invalid("REALIZATION_ORIGINAL_LINE_AMBIGUOUS");
              }
            }
          }
          long lastFact = afterFact;
          for (Row row : rows) {
            String source = row.data().toString();
            String digest = IdempotencyService.sha256(source.getBytes(StandardCharsets.UTF_8));
            List<FinancialSourceService.FinancialFact> facts;
            if (report.kind().equals("SERVICES")) {
              facts =
                  List.of(
                      ServiceReportCanonicalizer.canonicalize(
                          report.id(),
                          lastFact + 1,
                          sheet,
                          row.data(),
                          account.externalId(),
                          account.zone(),
                          report.from(),
                          report.until(),
                          offers.get(ServiceReportCanonicalizer.sku(row.data(), sheet)),
                          report.raw()));
            } else if (report.kind().equals("REALIZATION")) {
              String sku = RealizationReportCanonicalizer.sku(row.data());
              String key = CatalogSyncService.text(row.data(), "orderId", true) + ":sku:" + sku;
              facts =
                  RealizationReportCanonicalizer.canonicalize(
                      report.id(),
                      lastFact + 1,
                      sheet,
                      row.data(),
                      report.from(),
                      report.until(),
                      offers.get(sku),
                      originalLines.get(key),
                      report.raw());
            } else {
              facts = List.of();
            }
            Long firstFact = facts.isEmpty() ? null : lastFact + 1;
            lastFact += facts.size();
            String canonical = facts.isEmpty() ? null : json.encode(facts);
            int inserted =
                jdbc.sql(
                        """
                        INSERT INTO marketplace_report_row(organization_id,account_id,report_id,sheet,row_number,
                          data,content_hash,ordinal,canonical_fact,fact_start,fact_end)
                        VALUES (:org,:account,:report,:sheet,:row,CAST(:data AS jsonb),:hash,:ordinal,
                          CAST(:canonical AS jsonb),:firstFact,:lastFact) ON CONFLICT DO NOTHING
                        """)
                    .param("org", context.scope().organizationId())
                    .param("account", context.scope().accountId())
                    .param("report", report.id())
                    .param("sheet", sheet)
                    .param("row", row.number())
                    .param("data", source)
                    .param("hash", digest)
                    .param("ordinal", row.ordinal())
                    .param("canonical", canonical)
                    .param("firstFact", firstFact)
                    .param("lastFact", facts.isEmpty() ? null : lastFact)
                    .update();
            if (inserted == 0
                && !jdbc.sql(
                        """
                        SELECT content_hash=:hash FROM marketplace_report_row
                        WHERE report_id=:report AND sheet=:sheet AND row_number=:row
                        """)
                    .param("hash", digest)
                    .param("report", report.id())
                    .param("sheet", sheet)
                    .param("row", row.number())
                    .query(Boolean.class)
                    .single()) {
              throw invalid("REPORT_REPLAY_CONFLICT");
            }
          }
          return lastFact;
        });
  }

  private void finish(JobContext context, Report report, String reason) {
    jobs.requireOwnership(context);
    jdbc.sql(
            "UPDATE marketplace_report SET state='PUBLISHED',reason=:reason,revision=revision+1"
                + " WHERE id=:id")
        .param("id", report.id())
        .param("reason", reason)
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_source SET status=:status,last_success_at=clock_timestamp(),
              revision=revision+1,reason=:reason WHERE source_type=:source
            """)
        .param("status", reason == null ? "READY" : "INCOMPLETE")
        .param("reason", reason)
        .param("source", report.kind())
        .update();
    audit.record(
        context.scope(), "supplier-report.published", report.id(), "kind=" + report.kind());
    outbox.emit(
        context.scope(),
        "supplier-report:" + report.id(),
        "source.changed",
        new OutboxService.EntityChange(report.kind().toLowerCase(Locale.ROOT), report.id(), 1));
  }

  private void fail(JobContext context, UUID id, String reason, boolean unknown) {
    transactions.run(
        context.scope(),
        () -> {
          jdbc.sql(
                  "UPDATE marketplace_report SET state=:state,reason=:reason,revision=revision+1"
                      + " WHERE id=:id")
              .param("id", id)
              .param("state", unknown ? "UNKNOWN" : "BLOCKED")
              .param("reason", reason)
              .update();
          jdbc.sql(
                  """
                  UPDATE marketplace_source SET status='INCOMPLETE',reason=:reason,revision=revision+1
                  WHERE source_type=(SELECT kind FROM marketplace_report WHERE id=:id)
                  """)
              .param("id", id)
              .param("reason", reason)
              .update();
        });
  }

  private Report report(UUID id) {
    return jdbc.sql("SELECT * FROM marketplace_report WHERE id=:id")
        .param("id", id)
        .query(
            (row, index) ->
                new Report(
                    row.getObject("id", UUID.class),
                    row.getString("kind"),
                    row.getObject("date_from", LocalDate.class),
                    row.getObject("date_until", LocalDate.class),
                    row.getString("state"),
                    row.getObject("generation_call_id", UUID.class),
                    row.getString("supplier_report_id"),
                    row.getObject("raw_file_id", UUID.class),
                    row.getTimestamp("generation_finished_at") == null
                        ? null
                        : row.getTimestamp("generation_finished_at").toInstant(),
                    row.getString("campaign_id")))
        .single();
  }

  void onTerminalFailure(Scope scope, UUID operation, String reason) {
    authorization.require(scope, "marketplace.sync.record");
    var id =
        jdbc.sql(
                """
                UPDATE marketplace_report SET state=CASE WHEN state='GENERATING' THEN 'UNKNOWN' ELSE 'BLOCKED' END,
                  reason=:reason,revision=revision+1
                WHERE job_id=:job AND state IN ('QUEUED','GENERATING','WAITING','STORED') RETURNING id
                """)
            .param("job", operation)
            .param("reason", reason)
            .query(UUID.class)
            .optional();
    if (id.isPresent()) {
      jdbc.sql(
              """
              UPDATE marketplace_source SET status='INCOMPLETE',reason=:reason,revision=revision+1
              WHERE source_type=(SELECT kind FROM marketplace_report WHERE id=:id)
              """)
          .param("id", id.orElseThrow())
          .param("reason", reason)
          .update();
      audit.record(scope, "supplier-report.blocked", id.orElseThrow(), "reason=" + reason);
    }
  }

  private JsonObject result(OutboundGateway.Response response) throws IOException {
    try (InputStream input = gateway.open(response)) {
      byte[] body = input.readNBytes(65537);
      if (body.length > 65536 || input.read() != -1) {
        throw invalid("REPORT_CONTROL_ENVELOPE_LIMIT");
      }
      JsonObject envelope =
          JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
      if (!"OK".equals(CatalogSyncService.text(envelope, "status", true))
          || !envelope.has("result")
          || !envelope.get("result").isJsonObject()) {
        throw invalid("REPORT_CONTROL_INVALID");
      }
      return envelope.getAsJsonObject("result");
    }
  }

  private static void checkArchive(long expanded, long compressed) throws IOException {
    if (expanded > 8L * 1024 * 1024 * 1024 || expanded > Math.max(compressed, 1) * 100) {
      throw new IOException("Report exceeds the expanded size or compression ratio limit");
    }
  }

  private JobOutcome waitFor(String reason, int seconds) {
    return JobOutcome.waiting(reason, clock.instant().plusSeconds(seconds));
  }

  private static VendorMethod method(String kind) {
    return switch (kind) {
      case "PAYMENTS" -> VendorMethod.YANDEX_REPORT_PAYMENTS;
      case "RETURNS" -> VendorMethod.YANDEX_REPORT_RETURNS;
      case "SERVICES" -> VendorMethod.YANDEX_REPORT_SERVICES;
      case "REALIZATION" -> VendorMethod.YANDEX_REPORT_REALIZATION;
      default -> throw invalid("REPORT_KIND_UNKNOWN");
    };
  }

  private static BusinessException invalid(String code) {
    return new BusinessException(code, 422, "Отчёт площадки не подтверждён полностью");
  }

  public record ReportJob(UUID id) {}

  public record Campaign(String id, String placementType, String status) {}

  private record Request(String kind, LocalDate from, LocalDate until, String campaignId) {}

  private record Row(long number, long ordinal, JsonObject data) {}

  private record Report(
      UUID id,
      String kind,
      LocalDate from,
      LocalDate until,
      String state,
      UUID generationCall,
      String supplierId,
      UUID raw,
      Instant finishedAt,
      String campaign) {}
}
