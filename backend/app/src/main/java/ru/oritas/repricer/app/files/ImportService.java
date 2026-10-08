package ru.oritas.repricer.app.files;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.FileWorkGate;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;
import ru.oritas.repricer.platform.TableExportSource;
import ru.oritas.repricer.platform.TabularImportTarget;

@Service
public final class ImportService implements TableExportSource {
  private static final Set<String> STATES =
      Set.of(
          "",
          "DRAFT",
          "VALIDATED",
          "PREPARING",
          "COMMITTED",
          "INVALID",
          "FAILED",
          "STALE",
          "CANCELLED");
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final IdempotencyService requests;
  private final JobRuntime jobs;
  private final StoredFileService files;
  private final JsonCodec json;
  private final OutboxService outbox;
  private final AuditService audit;
  private final ObjectProvider<TabularImportTarget> targets;
  private final Clock clock;
  private final MarketplaceReadService marketplace;

  public ImportService(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      IdempotencyService requests,
      JobRuntime jobs,
      StoredFileService files,
      JsonCodec json,
      OutboxService outbox,
      AuditService audit,
      ObjectProvider<TabularImportTarget> targets,
      Clock clock,
      MarketplaceReadService marketplace) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.requests = requests;
    this.jobs = jobs;
    this.files = files;
    this.json = json;
    this.outbox = outbox;
    this.audit = audit;
    this.targets = targets;
    this.clock = clock;
    this.marketplace = marketplace;
  }

  public Accepted upload(
      Scope scope,
      String kind,
      String name,
      Options options,
      UUID requestId,
      InputStream input,
      FileWorkGate.Permit permit)
      throws IOException {
    scope.requireAccount();
    TabularImportTarget target = target(kind);
    transactions.run(
        scope,
        () -> {
          requireWrite(scope, target);
          return true;
        });
    if (name == null
        || name.isBlank()
        || name.length() > 160
        || name.contains("/")
        || name.contains("\\")
        || name.chars().anyMatch(Character::isISOControl)) {
      throw new BusinessException("INVALID_FILENAME", 422, "Некорректное имя файла");
    }
    permit.requireValid();
    String media =
        options.format().equals("CSV")
            ? "text/csv"
            : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    StoredFileService.FileRecord file =
        files.store(
            scope,
            "IMPORT",
            media,
            name,
            input,
            100L * 1024 * 1024,
            clock.instant().plusSeconds(120));
    String optionJson = json.encode(options);
    String optionsHash =
        IdempotencyService.sha256(optionJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return transactions.run(
        scope,
        () -> {
          requireWrite(scope, target);
          permit.confirm();
          var intent = new UploadIntent(kind, file.sha256(), options);
          return requests.execute(
              scope,
              "import.upload",
              requestId,
              intent,
              Accepted.class,
              () -> {
                jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
                    .param(
                        "key",
                        "import:"
                            + scope.accountId()
                            + ":"
                            + kind
                            + ":"
                            + file.sha256()
                            + optionsHash)
                    .query((row, index) -> true)
                    .single();
                var existing =
                    jdbc.sql(
                            """
                            SELECT validation_job_id FROM app_import
                            WHERE kind=:kind AND content_hash=:hash AND options_hash=:options
                            """)
                        .param("kind", kind)
                        .param("hash", file.sha256())
                        .param("options", optionsHash)
                        .query(UUID.class)
                        .optional();
                if (existing.isPresent()) {
                  return new Accepted(existing.orElseThrow(), "ACCEPTED");
                }
                outbox.requireHeavyAdmission();
                UUID id = UUID.randomUUID();
                jdbc.sql(
                        """
                        INSERT INTO app_import(id,organization_id,account_id,subject_id,kind,name,file_id,
                          content_hash,options,options_hash,state,validation_id)
                        VALUES (:id,:org,:account,:subject,:kind,:name,:file,:hash,CAST(:options AS jsonb),:optionsHash,'DRAFT',:id)
                        """)
                    .param("id", id)
                    .param("org", scope.organizationId())
                    .param("account", scope.accountId())
                    .param("subject", scope.subjectId())
                    .param("kind", kind)
                    .param("name", name)
                    .param("file", file.id())
                    .param("hash", file.sha256())
                    .param("options", optionJson)
                    .param("optionsHash", optionsHash)
                    .update();
                createEdition(scope, id, id, 1);
                UUID operation =
                    jobs.submit(scope, "IMPORT_VALIDATE", id.toString(), json.encode(new Work(id)));
                jdbc.sql("UPDATE app_import SET validation_job_id=:job WHERE id=:id")
                    .param("job", operation)
                    .param("id", id)
                    .update();
                files.retain(file.id(), "IMPORT", id, scope);
                audit.record(scope, "IMPORT_RECEIVED", id, "kind=" + kind);
                changed(scope, id, 1);
                return new Accepted(operation, "ACCEPTED");
              });
        });
  }

  public Accepted apply(Scope scope, UUID id, Mutation input) {
    return transactions.run(
        scope,
        () -> {
          Header header = load(id, false);
          TabularImportTarget target = target(header.kind());
          requireWrite(scope, target);
          requireTemporary(header);
          target.lockForPublication(scope, header.validationId());
          return requests.execute(
              scope,
              "import.apply:" + id,
              input.clientRequestId(),
              input,
              Accepted.class,
              () -> {
                Header current = load(id, true);
                requireTemporary(current);
                if (!current.status().equals("VALIDATED")
                    || current.revision() != input.expectedRevision()
                    || input.previewRevision() != current.revision()) {
                  throw new BusinessException(
                      "IMPORT_PREVIEW_CONFLICT",
                      409,
                      "Проверка устарела или набор уже обрабатывается");
                }
                UUID operation =
                    jobs.submit(
                        scope,
                        "IMPORT_APPLY",
                        current.validationId().toString(),
                        json.encode(new Work(id)));
                jdbc.sql("UPDATE app_import SET apply_job_id=:job WHERE id=:id")
                    .param("id", id)
                    .param("job", operation)
                    .update();
                transition(scope, current, "PREPARING", null);
                return new Accepted(operation, "ACCEPTED");
              });
        });
  }

  public Accepted revalidate(Scope scope, UUID id, Revalidation input) {
    return transactions.run(
        scope,
        () -> {
          Header header = load(id, false);
          TabularImportTarget target = target(header.kind());
          requireWrite(scope, target);
          requireTemporary(header);
          target.lockForPublication(scope, header.validationId());
          return requests.execute(
              scope,
              "import.revalidate:" + id,
              input.clientRequestId(),
              input,
              Accepted.class,
              () -> {
                Header current = load(id, true);
                requireTemporary(current);
                if (current.revision() != input.expectedRevision()
                    || !current.status().equals("STALE")
                    || !current.parsed()) {
                  throw new BusinessException(
                      "IMPORT_REVALIDATION_CONFLICT",
                      409,
                      "Повторная проверка доступна для устаревшего набора без изменения исходных"
                          + " строк");
                }
                UUID edition = UUID.randomUUID();
                createEdition(scope, id, edition, current.validationEdition() + 1);
                UUID job =
                    jobs.submit(
                        scope, "IMPORT_VALIDATE", edition.toString(), json.encode(new Work(id)));
                jdbc.sql(
                        """
                        UPDATE app_import SET state='DRAFT',reason=NULL,revision=revision+1,
                          validation_id=:edition,validation_edition=validation_edition+1,
                          validation_job_id=:job,apply_job_id=NULL,staged_through=0,
                          valid_rows=(SELECT count(*) FROM app_import_row WHERE import_id=:id AND valid),
                          error_rows=(SELECT count(*) FROM app_import_row WHERE import_id=:id AND NOT valid)
                        WHERE id=:id
                        """)
                    .param("id", id)
                    .param("edition", edition)
                    .param("job", job)
                    .update();
                audit.record(
                    scope,
                    "IMPORT_REVALIDATION_REQUESTED",
                    id,
                    "edition=" + (current.validationEdition() + 1));
                changed(scope, id, current.revision() + 1);
                return new Accepted(job, "ACCEPTED");
              });
        });
  }

  public void cancel(Scope scope, UUID id, UUID requestId, long expectedRevision) {
    transactions.run(
        scope,
        () -> {
          Header header = load(id, false);
          TabularImportTarget target = target(header.kind());
          requireWrite(scope, target);
          target.lockForPublication(scope, header.validationId());
          return requests.execute(
              scope,
              "import.cancel:" + id,
              requestId,
              expectedRevision,
              Boolean.class,
              () -> {
                Header current = load(id, true);
                if (current.revision() != expectedRevision
                    || !Set.of("DRAFT", "VALIDATED", "PREPARING").contains(current.status())) {
                  throw new BusinessException(
                      "REVISION_CONFLICT", 409, "Набор изменился или уже закрыт");
                }
                transition(scope, current, "CANCELLED", null);
                audit.record(scope, "IMPORT_CANCELLED", id, "");
                return true;
              });
        });
  }

  public Detail detail(Scope scope, UUID id, int page, int size) {
    Header header = load(id, false);
    requireRead(scope, header.kind());
    int offset = Page.offset(page, size);
    List<RowResult> rows =
        jdbc.sql(
                """
                SELECT row_number,valid,errors::text FROM app_import_row WHERE import_id=:id
                ORDER BY valid,row_number LIMIT :size OFFSET :offset
                """)
            .param("id", id)
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) ->
                    new RowResult(
                        row.getInt(1),
                        row.getBoolean(2) ? "VALID" : "INVALID",
                        String.join(", ", json.decode(row.getString(3), String[].class))))
            .list();
    return new Detail(
        header.id(),
        header.name(),
        header.status(),
        header.revision(),
        header.totalRows(),
        header.validRows(),
        header.errorRows(),
        rows,
        header.reason(),
        header.validationEdition(),
        header.temporaryExpired());
  }

  public Page<Summary> list(
      Scope scope,
      int page,
      int size,
      String status,
      String kind,
      String search,
      String sort,
      String direction,
      LocalDate from,
      LocalDate to) {
    int offset = Page.offset(page, size);
    Parts parts = filter(scope, status, kind, search, sort, direction, from, to, List.of());
    long total =
        jdbc.sql("SELECT count(*) FROM app_import" + parts.where())
            .params(parts.parameters())
            .query(Long.class)
            .single();
    List<Summary> rows =
        jdbc.sql(
                """
                SELECT id,name,state,kind,created_at,revision,total_rows,error_rows,reason,validation_edition,
                  temporary_expired_at IS NOT NULL
                FROM app_import
                """
                    + parts.where()
                    + parts.order()
                    + " LIMIT :size OFFSET :offset")
            .params(parts.parameters())
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) ->
                    new Summary(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        row.getString(3),
                        row.getString(4),
                        row.getTimestamp(5).toInstant(),
                        row.getLong(6),
                        row.getInt(7),
                        row.getInt(8),
                        row.getString(9),
                        row.getInt(10),
                        row.getBoolean(11)))
            .list();
    return new Page<>(rows, total, page, size);
  }

  @Override
  public String resource() {
    return "imports";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("file.read");
  }

  @Override
  public Projection projection(Scope scope, Query query, List<UUID> ids) {
    if (!Set.of("status", "kind", "from", "to").containsAll(query.filters().keySet())) {
      throw new BusinessException("INVALID_IMPORT_FILTER", 422, "Неизвестный фильтр импорта");
    }
    String[] order = query.sort().split(",", -1);
    if (order.length != 2) {
      throw new BusinessException("INVALID_SORT", 422, "Некорректная сортировка");
    }
    Parts parts =
        filter(
            scope,
            query.filters().getOrDefault("status", ""),
            query.filters().getOrDefault("kind", ""),
            query.search(),
            order[0],
            order[1],
            date(query.filters().get("from")),
            date(query.filters().get("to")),
            ids);
    Map<String, Object> parameters = new LinkedHashMap<>(parts.parameters());
    parameters.put(
        "readPermissions",
        json.encode(
            targets.stream()
                .map(TabularImportTarget::kind)
                .collect(Collectors.toMap(value -> value, ImportService::readPermission))));
    return new Projection(
        """
        SELECT id AS canonical_id,jsonb_build_array(name,created_at::text,state,kind,
          total_rows::text,error_rows::text,reason,validation_edition::text) AS cells,
          jsonb_build_array('file.read',CAST(:readPermissions AS jsonb)->>kind)
            AS permissions FROM app_import
        """
            + parts.where()
            + parts.order(),
        parameters,
        List.of(
            new Column("name", "Файл", false),
            new Column("date", "Создан", false),
            new Column("status", "Состояние", false),
            new Column("kind", "Тип импорта", false),
            new Column("rows", "Строк", true),
            new Column("errors", "Ошибок", true),
            new Column("reason", "Причина", false),
            new Column("validationEdition", "Проверка", true)),
        "AS_PUBLISHED",
        true);
  }

  private Parts filter(
      Scope scope,
      String status,
      String kind,
      String search,
      String sort,
      String direction,
      LocalDate from,
      LocalDate to,
      List<UUID> ids) {
    authorization.require(scope, "file.read");
    Set<String> kinds =
        targets.stream().map(TabularImportTarget::kind).collect(Collectors.toUnmodifiableSet());
    if (!STATES.contains(status)
        || !kind.isEmpty() && !kinds.contains(kind)
        || search.length() > 200
        || from != null && to != null && from.isAfter(to)
        || ids.size() > 1000) {
      throw new BusinessException("INVALID_IMPORT_FILTER", 422, "Некорректный фильтр импорта");
    }
    String column =
        switch (sort) {
          case "id", "name", "kind", "revision" -> sort;
          case "status" -> "state";
          case "date", "createdAt" -> "created_at";
          case "rows", "totalRows" -> "total_rows";
          case "errors", "errorRows" -> "error_rows";
          default ->
              throw new BusinessException("INVALID_SORT", 422, "Неизвестная колонка сортировки");
        };
    if (!Set.of("asc", "desc").contains(direction)) {
      throw new BusinessException("INVALID_SORT", 422, "Некорректный порядок сортировки");
    }
    Set<String> permissions = authorization.permissions(scope);
    Set<String> allowed =
        kinds.stream()
            .filter(value -> permissions.contains(readPermission(value)))
            .collect(Collectors.toUnmodifiableSet());
    StringBuilder where =
        new StringBuilder(allowed.isEmpty() ? " WHERE false" : " WHERE kind IN (:allowed)");
    Map<String, Object> parameters = new LinkedHashMap<>();
    if (!allowed.isEmpty()) {
      parameters.put("allowed", allowed);
    }
    where
        .append(" AND (:status='' OR state=:status) AND (:kind='' OR kind=:kind)")
        .append(" AND position(lower(:search) in lower(name))>0");
    parameters.put("status", status);
    parameters.put("kind", kind);
    parameters.put("search", search);
    var zone = marketplace.zoneId(scope);
    if (from != null) {
      where.append(" AND created_at>=:fromDate");
      parameters.put("fromDate", Timestamp.from(from.atStartOfDay(zone).toInstant()));
    }
    if (to != null) {
      where.append(" AND created_at<:untilDate");
      parameters.put("untilDate", Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
    }
    if (!ids.isEmpty()) {
      where.append(" AND id IN (:ids)");
      parameters.put("ids", ids);
    }
    return new Parts(where.toString(), " ORDER BY " + column + " " + direction + ",id", parameters);
  }

  private static LocalDate date(String value) {
    return value == null || value.isBlank() ? null : LocalDate.parse(value);
  }

  public List<String> template(Scope scope, String kind) {
    TabularImportTarget target = target(kind);
    requireWrite(scope, target);
    return target.columns().stream().map(TabularImportTarget.Column::name).toList();
  }

  public List<RowResult> errors(Scope scope, UUID id, int after) {
    var header = load(id, false);
    requireRead(scope, header.kind());
    requireTemporary(header);
    if (Set.of("DRAFT", "PREPARING").contains(header.status())) {
      throw new BusinessException("IMPORT_BUSY", 409, "Дождитесь завершения проверки");
    }
    return jdbc.sql(
            """
            SELECT row_number,errors::text FROM (
              SELECT row_number,errors FROM app_import_row WHERE import_id=:id AND NOT valid
              UNION ALL SELECT 0,jsonb_build_array(reason) FROM app_import WHERE id=:id AND reason IS NOT NULL
            ) diagnostics WHERE row_number>:after ORDER BY row_number LIMIT 200
            """)
        .param("id", id)
        .param("after", after)
        .query(
            (row, index) ->
                new RowResult(
                    row.getInt(1),
                    "INVALID",
                    String.join(", ", json.decode(row.getString(2), String[].class))))
        .list();
  }

  Header load(UUID id, boolean lock) {
    return jdbc.sql("SELECT * FROM app_import WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
        .param("id", id)
        .query(
            (row, index) ->
                new Header(
                    row.getObject("id", UUID.class),
                    row.getString("name"),
                    row.getString("kind"),
                    row.getString("state"),
                    row.getLong("revision"),
                    row.getObject("file_id", UUID.class),
                    json.decode(row.getString("options"), Options.class),
                    row.getBoolean("parsed"),
                    row.getInt("staged_through"),
                    row.getInt("total_rows"),
                    row.getInt("valid_rows"),
                    row.getInt("error_rows"),
                    row.getString("reason"),
                    row.getObject("validation_id", UUID.class),
                    row.getInt("validation_edition"),
                    row.getObject("validation_job_id", UUID.class),
                    row.getObject("apply_job_id", UUID.class),
                    row.getTimestamp("temporary_expired_at") != null))
        .optional()
        .orElseThrow(() -> new BusinessException("IMPORT_NOT_FOUND", 404, "Импорт недоступен"));
  }

  TabularImportTarget target(String kind) {
    return targets.stream()
        .filter(target -> target.kind().equals(kind))
        .findFirst()
        .orElseThrow(
            () -> new BusinessException("IMPORT_KIND_UNKNOWN", 422, "Неизвестный шаблон импорта"));
  }

  void requireWrite(Scope scope, TabularImportTarget target) {
    var required = new HashSet<>(target.permissions());
    required.add("file.write");
    authorization.requireLocked(scope, Set.copyOf(required));
  }

  void requireRead(Scope scope, String kind) {
    authorization.require(scope, "file.read");
    authorization.require(scope, readPermission(kind));
  }

  void changed(Scope scope, UUID id, long revision) {
    outbox.emit(
        scope,
        id + ":" + revision,
        "import.changed",
        new OutboxService.EntityChange("imports", id, revision));
  }

  void transition(Scope scope, Header current, String state, String reason) {
    jdbc.sql(
            """
            UPDATE app_import SET state=:state,reason=:reason,revision=revision+1,
              committed_at=CASE WHEN :state='COMMITTED' THEN clock_timestamp() ELSE committed_at END
            WHERE id=:id
            """)
        .param("id", current.id())
        .param("state", state)
        .param("reason", reason)
        .update();
    jdbc.sql(
            """
            UPDATE app_import_validation SET state=:state,reason=:reason,total_rows=:total,
              valid_rows=:valid,error_rows=:errors,
              completed_at=CASE WHEN :state NOT IN ('DRAFT','VALIDATED','PREPARING')
                THEN clock_timestamp() END WHERE id=:edition
            """)
        .param("edition", current.validationId())
        .param("state", state)
        .param("reason", reason)
        .param("total", current.totalRows())
        .param("valid", current.validRows())
        .param("errors", current.errorRows())
        .update();
    changed(scope, current.id(), current.revision() + 1);
  }

  String failWork(JobContext context, UUID id, String phase, String state, String reason) {
    // This authority only records a failed owned job; it never stages or publishes business input.
    return transactions.runService(
        context.scope(),
        Set.of("file.import.manage"),
        () -> {
          jobs.requireOwnership(context);
          Header current = load(id, true);
          UUID job = phase.equals("DRAFT") ? current.validationJobId() : current.applyJobId();
          if (!current.status().equals(phase) || !context.id().equals(job)) {
            return current.status();
          }
          transition(context.scope(), current, state, reason);
          audit.record(context.scope(), "IMPORT_" + state, id, "reason=" + reason);
          return state;
        });
  }

  /** Records exhaustion of an active job without granting authority to publish imported data. */
  public void onTerminalFailure(Scope scope, UUID operationId, String safeReason) {
    authorization.require(scope, "app.files.terminal");
    var id =
        jdbc.sql(
                """
                SELECT id FROM app_import WHERE (state='DRAFT' AND validation_job_id=:job)
                  OR (state='PREPARING' AND apply_job_id=:job) FOR UPDATE
                """)
            .param("job", operationId)
            .query(UUID.class)
            .optional();
    if (id.isEmpty()) {
      return;
    }
    Header current = load(id.orElseThrow(), false);
    String reason =
        safeReason != null && safeReason.matches("[A-Z][A-Z0-9_]{0,127}")
            ? safeReason
            : "IMPORT_PROCESSING_FAILED";
    transition(scope, current, "FAILED", reason);
    audit.record(scope, "IMPORT_FAILED", current.id(), "reason=" + reason);
  }

  /** Expires temporary input without removing committed facts or their source evidence. */
  public int cleanupTemporary(Scope scope) {
    authorization.require(scope, "app.files.retention");
    var id =
        jdbc.sql(
                """
                      SELECT i.id FROM app_import i
                      WHERE i.created_at<clock_timestamp()-interval '7 days'
                        AND i.state NOT IN ('DRAFT','PREPARING')
                AND (i.temporary_expired_at IS NULL
                  OR (i.temporary_expired_at<clock_timestamp()-interval '24 hours'
                    AND EXISTS(SELECT 1 FROM app_import_row r WHERE r.import_id=i.id)))
                        AND NOT EXISTS(SELECT 1 FROM platform_job j
                          WHERE j.id IN (i.validation_job_id,i.apply_job_id)
                            AND j.state IN ('READY','WAITING','RUNNING'))
                      ORDER BY i.created_at,i.id LIMIT 1 FOR UPDATE OF i SKIP LOCKED
                """)
            .query(UUID.class)
            .optional();
    if (id.isEmpty()) {
      return 0;
    }
    Header current = load(id.orElseThrow(), false);
    // Acquiring the header lock can wait; recheck work committed after the selection snapshot.
    boolean active =
        jdbc.sql(
                """
                SELECT state IN ('DRAFT','PREPARING') OR EXISTS(
                  SELECT 1 FROM platform_job j WHERE j.id IN (i.validation_job_id,i.apply_job_id)
                    AND j.state IN ('READY','WAITING','RUNNING'))
                FROM app_import i WHERE i.id=:id
                """)
            .param("id", current.id())
            .query(Boolean.class)
            .single();
    if (active) {
      return 0;
    }
    if (!current.temporaryExpired()) {
      files.release(scope, current.fileId(), "IMPORT", current.id());
      jdbc.sql(
              """
              UPDATE app_import SET temporary_expired_at=clock_timestamp(),revision=revision+1 WHERE id=:id
              """)
          .param("id", current.id())
          .update();
      changed(scope, current.id(), current.revision() + 1);
    }
    jdbc.sql(
            """
            DELETE FROM app_import_row WHERE import_id=:id
              AND EXISTS(SELECT 1 FROM app_import WHERE id=:id
                AND temporary_expired_at<clock_timestamp()-interval '24 hours') AND row_number IN (
                SELECT row_number FROM app_import_row WHERE import_id=:id ORDER BY row_number LIMIT 500)
            """)
        .param("id", current.id())
        .update();
    return 1;
  }

  void retainAppliedEvidence(Scope scope, Header current) {
    files.retain(current.fileId(), "IMPORT_APPLIED", current.id(), scope);
  }

  private static void requireTemporary(Header header) {
    if (header.temporaryExpired()) {
      throw new BusinessException(
          "IMPORT_EXPIRED", 410, "Срок хранения временных строк импорта истёк");
    }
  }

  private void createEdition(Scope scope, UUID id, UUID edition, int number) {
    jdbc.sql(
            """
            INSERT INTO app_import_validation(id,organization_id,account_id,import_id,edition,state)
            VALUES (:edition,:org,:account,:id,:number,'DRAFT')
            """)
        .param("edition", edition)
        .param("id", id)
        .param("number", number)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .update();
  }

  private static String readPermission(String kind) {
    return switch (kind) {
      case "COSTS", "SELLER_COSTS", "TAX", "PRICE_PARAMETERS" -> "finance.read";
      case "COMPETITORS" -> "competitor.read";
      default -> "policy.read";
    };
  }

  public record Options(
      String format, String delimiter, String decimalSeparator, String sheetName) {
    public Options {
      if (!Set.of("CSV", "XLSX").contains(format)
          || !Set.of("SEMICOLON", "COMMA").contains(delimiter)
          || !Set.of("DOT", "COMMA").contains(decimalSeparator)
          || (sheetName != null && sheetName.length() > 128)) {
        throw new IllegalArgumentException("Invalid import options");
      }
    }
  }

  public record Accepted(UUID operationId, String status) {}

  public record Mutation(UUID clientRequestId, long expectedRevision, long previewRevision) {}

  public record Revalidation(UUID clientRequestId, long expectedRevision) {}

  public record Summary(
      UUID id,
      String name,
      String status,
      String kind,
      Instant createdAt,
      long revision,
      int totalRows,
      int errorRows,
      String reason,
      int validationEdition,
      boolean temporaryExpired) {}

  public record RowResult(int row, String status, String message) {}

  public record Detail(
      UUID id,
      String name,
      String status,
      long revision,
      int totalRows,
      int validRows,
      int errorRows,
      List<RowResult> rows,
      String reason,
      int validationEdition,
      boolean temporaryExpired) {}

  record Header(
      UUID id,
      String name,
      String kind,
      String status,
      long revision,
      UUID fileId,
      Options options,
      boolean parsed,
      int stagedThrough,
      int totalRows,
      int validRows,
      int errorRows,
      String reason,
      UUID validationId,
      int validationEdition,
      UUID validationJobId,
      UUID applyJobId,
      boolean temporaryExpired) {}

  record Work(UUID importId) {}

  private record UploadIntent(String kind, String fileHash, Options options) {}

  private record Parts(String where, String order, Map<String, Object> parameters) {}
}
