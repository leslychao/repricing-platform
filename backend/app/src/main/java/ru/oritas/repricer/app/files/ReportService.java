package ru.oritas.repricer.app.files;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;
import ru.oritas.repricer.platform.TableExportSource;

@Service
public final class ReportService implements TableExportSource {
  private static final String SUMMARY =
      """
      SELECT r.id,r.kind,r.format,r.state,r.created_at,r.reason,r.revision,
        s.total_rows,s.data_status,r.expires_at,r.file_id,r.operation_id
      FROM app_report r JOIN app_selection s ON s.id=r.selection_id
      """;
  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final SelectionService selections;
  private final IdempotencyService requests;
  private final JobRuntime jobs;
  private final StoredFileService files;
  private final JsonCodec json;
  private final OutboxService outbox;
  private final AuditService audit;
  private final Clock clock;
  private final MarketplaceReadService marketplace;

  public ReportService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      SelectionService selections,
      IdempotencyService requests,
      JobRuntime jobs,
      StoredFileService files,
      JsonCodec json,
      OutboxService outbox,
      AuditService audit,
      Clock clock,
      MarketplaceReadService marketplace) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.selections = selections;
    this.requests = requests;
    this.jobs = jobs;
    this.files = files;
    this.json = json;
    this.outbox = outbox;
    this.audit = audit;
    this.clock = clock;
    this.marketplace = marketplace;
  }

  public Accepted create(Scope scope, Request request) {
    authorization.requireLocked(scope, Set.of("export"));
    var snapshot = selections.snapshot(scope, request.selectionId(), true);
    String kind = request.kind() == null ? "EXPORT" : request.kind();
    String requiredResource =
        switch (kind) {
          case "MONTHLY_PNL" -> "monthly-pnl";
          case "MONTHLY_UNIT_ECONOMICS" -> "monthly-unit-economics";
          case "RETURNS" -> "returns";
          case "INVENTORY" -> "stock-pools";
          case "EXPORT" -> snapshot.resource();
          default -> "";
        };
    if (request.expectedRevision() != 0
        || !Set.of("CSV", "XLSX").contains(request.format())
        || !requiredResource.equals(snapshot.resource())) {
      throw new BusinessException("INVALID_REPORT", 422, "Некорректные параметры экспорта");
    }
    return requests.execute(
        scope,
        "report.create",
        request.clientRequestId(),
        request,
        Accepted.class,
        () -> {
          outbox.requireHeavyAdmission();
          UUID id = UUID.randomUUID();
          UUID operation =
              jobs.submit(scope, "REPORT_EXPORT", id.toString(), json.encode(new Work(id)));
          jdbc.sql(
                  """
                  INSERT INTO app_report(id,organization_id,account_id,subject_id,selection_id,
                    kind,format,state,operation_id)
                  VALUES (:id,:org,:account,:subject,:selection,:kind,:format,'PENDING',:job)
                  """)
              .param("id", id)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("subject", scope.subjectId())
              .param("selection", request.selectionId())
              .param("format", request.format())
              .param("job", operation)
              .param("kind", kind)
              .update();
          audit.record(scope, "REPORT_REQUESTED", id, "");
          selections.retain(scope, request.selectionId(), "REPORT", id);
          changed(scope, id, 1);
          return new Accepted(operation, "ACCEPTED");
        });
  }

  public Page<Summary> list(
      Scope scope,
      int page,
      int size,
      String status,
      String search,
      String sort,
      String direction,
      LocalDate from,
      LocalDate to) {
    authorization.require(scope, "export");
    long offset = Page.offset(page, size);
    Parts filter = filter(scope, status, search, sort, direction, from, to, List.of());
    long total =
        jdbc.sql(
                "SELECT count(*) FROM app_report r JOIN app_selection s ON s.id=r.selection_id"
                    + filter.where())
            .params(filter.parameters())
            .query(Long.class)
            .single();
    List<Summary> rows =
        jdbc.sql(SUMMARY + filter.where() + filter.order() + " LIMIT :size OFFSET :offset")
            .params(filter.parameters())
            .param("size", size)
            .param("offset", offset)
            .query(mapper())
            .list();
    return new Page<>(rows, total, page, size);
  }

  @Override
  public String resource() {
    return "reports";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("export");
  }

  @Override
  public Projection projection(Scope scope, Query query, List<UUID> ids) {
    authorization.require(scope, "export");
    if (!Set.of("status", "from", "to").containsAll(query.filters().keySet())) {
      throw new BusinessException("INVALID_REPORT_FILTER", 422, "Неизвестный фильтр отчётов");
    }
    String[] sort = query.sort().split(",", -1);
    if (sort.length != 2) {
      throw new BusinessException("INVALID_SORT", 422, "Некорректная сортировка");
    }
    Parts filter =
        filter(
            scope,
            query.filters().getOrDefault("status", ""),
            query.search(),
            sort[0],
            sort[1],
            date(query.filters().get("from")),
            date(query.filters().get("to")),
            ids);
    return new Projection(
        """
        SELECT r.id AS canonical_id,jsonb_build_array(r.kind,r.format,
          CASE WHEN r.state='READY' AND r.expires_at<=clock_timestamp() THEN 'EXPIRED' ELSE r.state END,
          r.created_at::text,s.total_rows::text,s.data_status) AS cells,s.permissions
        FROM app_report r JOIN app_selection s ON s.id=r.selection_id
        """
            + filter.where()
            + filter.order(),
        filter.parameters(),
        List.of(
            new Column("name", "Отчёт", false), new Column("format", "Формат", false),
            new Column("status", "Состояние файла", false),
                new Column("createdAt", "Создан", false),
            new Column("rowCount", "Строк", true),
                new Column("dataStatus", "Полнота данных", false)),
        "AS_PUBLISHED",
        true);
  }

  private Parts filter(
      Scope scope,
      String status,
      String search,
      String sort,
      String direction,
      LocalDate from,
      LocalDate to,
      List<UUID> ids) {
    if (search.length() > 200
        || !Set.of("", "PENDING", "PREPARING", "READY", "FAILED", "EXPIRED").contains(status)
        || from != null && to != null && from.isAfter(to)) {
      throw new BusinessException("INVALID_REPORT_FILTER", 422, "Некорректный фильтр отчётов");
    }
    String order =
        switch (sort) {
          case "id" -> "r.id";
          case "name" -> "r.kind";
          case "status" ->
              "CASE WHEN r.state='READY' AND r.expires_at<=statement_timestamp()"
                  + " THEN 'EXPIRED' ELSE r.state END";
          case "date", "createdAt" -> "r.created_at";
          case "format" -> "r.format";
          case "revision" -> "r.revision";
          case "rowCount" -> "s.total_rows";
          default ->
              throw new BusinessException("INVALID_SORT", 422, "Неизвестная колонка сортировки");
        };
    if (!Set.of("asc", "desc").contains(direction)) {
      throw new BusinessException("INVALID_SORT", 422, "Некорректный порядок сортировки");
    }
    StringBuilder where =
        new StringBuilder(
            """
             WHERE s.permissions <@ CAST(:allowed AS jsonb)
               AND (:status='' OR CASE WHEN r.state='READY' AND r.expires_at<=clock_timestamp()
                 THEN 'EXPIRED' ELSE r.state END=:status)
               AND position(lower(:search) in lower(r.kind))>0
            """);
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("allowed", json.encode(authorization.permissions(scope)));
    parameters.put("status", status);
    parameters.put("search", search);
    ZoneId zone = scope.accountId() == null ? ZoneOffset.UTC : marketplace.zoneId(scope);
    if (from != null) {
      where.append(" AND r.created_at>=:fromDate");
      parameters.put("fromDate", Timestamp.from(from.atStartOfDay(zone).toInstant()));
    }
    if (to != null) {
      where.append(" AND r.created_at<:untilDate");
      parameters.put("untilDate", Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
    }
    if (!ids.isEmpty()) {
      where.append(" AND r.id IN (:ids)");
      parameters.put("ids", ids);
    }
    return new Parts(
        where.toString(), " ORDER BY " + order + " " + direction + ",r.id", parameters);
  }

  private static LocalDate date(String value) {
    return value == null || value.isBlank() ? null : LocalDate.parse(value);
  }

  public Summary get(Scope scope, UUID id) {
    requireRead(scope, id);
    return jdbc.sql(SUMMARY + " WHERE r.id=:id").param("id", id).query(mapper()).single();
  }

  public StoredFileService.FileRecord download(Scope scope, UUID id) {
    Header header = requireRead(scope, id);
    if (header.expiresAt() != null && !header.expiresAt().isAfter(clock.instant())) {
      throw new BusinessException("REPORT_EXPIRED", 410, "Срок хранения файла истёк");
    }
    if (!header.state().equals("READY")) {
      throw new BusinessException("REPORT_NOT_READY", 409, "Файл ещё не готов");
    }
    return files.get(header.fileId());
  }

  Header requireRead(Scope scope, UUID id) {
    authorization.requireLocked(scope, Set.of("export"));
    Header header = load(id, false);
    selections.snapshot(scope, header.selectionId(), false);
    return header;
  }

  Header load(UUID id, boolean lock) {
    return jdbc.sql(
            "SELECT id,selection_id,format,state,revision,file_id,expires_at,prepared_file_id FROM"
                + " app_report WHERE id=:id"
                + (lock ? " FOR UPDATE" : ""))
        .param("id", id)
        .query(
            (row, index) -> {
              var expires = row.getTimestamp(7);
              return new Header(
                  row.getObject(1, UUID.class),
                  row.getObject(2, UUID.class),
                  row.getString(3),
                  row.getString(4),
                  row.getLong(5),
                  row.getObject(6, UUID.class),
                  expires == null ? null : expires.toInstant(),
                  row.getObject(8, UUID.class));
            })
        .optional()
        .orElseThrow(() -> new BusinessException("REPORT_NOT_FOUND", 404, "Отчёт недоступен"));
  }

  void publish(Scope scope, UUID id, StoredFileService.FileRecord file) {
    Header header = load(id, true);
    if (header.state().equals("READY")) {
      return;
    }
    files.retain(file.id(), "REPORT", id, scope);
    jdbc.sql(
            """
            UPDATE app_report SET state='READY',file_id=:file,expires_at=:expires,
              reason=NULL,revision=revision+1 WHERE id=:id
            """)
        .param("file", file.id())
        .param("expires", Timestamp.from(clock.instant().plusSeconds(30 * 86400L)))
        .param("id", id)
        .update();
    audit.record(scope, "REPORT_READY", id, "");
    selections.release(scope, header.selectionId(), "REPORT", id);
    changed(scope, id, header.revision() + 1);
  }

  void changed(Scope scope, UUID id, long revision) {
    outbox.emit(
        scope,
        id + ":" + revision,
        "report.changed",
        new OutboxService.EntityChange("reports", id, revision));
  }

  public void onTerminalFailure(Scope scope, UUID operationId, String reason) {
    authorization.require(scope, "app.files.terminal");
    var pending =
        jdbc.sql(
                """
                SELECT id,revision,selection_id FROM app_report WHERE operation_id=:operation
                  AND state IN ('PENDING','PREPARING') FOR UPDATE
                """)
            .param("operation", operationId)
            .query(
                (row, index) ->
                    new FailedReport(
                        row.getObject(1, UUID.class), row.getLong(2), row.getObject(3, UUID.class)))
            .optional();
    if (pending.isEmpty()) {
      return;
    }
    String code =
        reason != null && reason.matches("[A-Z0-9_]{1,100}") ? reason : "REPORT_PROCESSING_FAILED";
    FailedReport report = pending.orElseThrow();
    jdbc.sql("UPDATE app_report SET state='FAILED',reason=:reason,revision=revision+1 WHERE id=:id")
        .param("id", report.id())
        .param("reason", code)
        .update();
    audit.record(scope, "REPORT_FAILED", report.id(), code);
    selections.release(scope, report.selectionId(), "REPORT", report.id());
    changed(scope, report.id(), report.revision() + 1);
  }

  private RowMapper<Summary> mapper() {
    return (row, index) -> {
      var timestamp = row.getTimestamp("expires_at");
      Instant expiry = timestamp == null ? null : timestamp.toInstant();
      String status = row.getString("state");
      if (status.equals("READY") && expiry != null && !expiry.isAfter(clock.instant())) {
        status = "EXPIRED";
      }
      return new Summary(
          row.getObject("id", UUID.class),
          row.getString("kind"),
          status,
          row.getTimestamp("created_at").toInstant(),
          row.getString("reason"),
          row.getLong("revision"),
          row.getString("format"),
          row.getLong("total_rows"),
          expiry,
          row.getObject("file_id", UUID.class),
          row.getObject("operation_id", UUID.class),
          row.getString("data_status"));
    };
  }

  public record Request(
      UUID clientRequestId, long expectedRevision, UUID selectionId, String format, String kind) {}

  public record Accepted(UUID operationId, String status) {}

  public record Summary(
      UUID id,
      String name,
      String status,
      Instant createdAt,
      String message,
      long revision,
      String format,
      long rowCount,
      Instant expiresAt,
      UUID fileId,
      UUID operationId,
      String dataStatus) {}

  record Header(
      UUID id,
      UUID selectionId,
      String format,
      String state,
      long revision,
      UUID fileId,
      Instant expiresAt,
      UUID preparedFileId) {}

  record Work(UUID reportId) {}

  private record Parts(String where, String order, Map<String, Object> parameters) {}

  private record FailedReport(UUID id, long revision, UUID selectionId) {}
}
