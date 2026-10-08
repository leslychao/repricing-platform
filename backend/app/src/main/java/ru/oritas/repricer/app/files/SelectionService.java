package ru.oritas.repricer.app.files;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;

/** Captures identity and published values together in one PostgreSQL statement snapshot. */
@Service
public final class SelectionService {
  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final IdempotencyService requests;
  private final ObjectProvider<TableExportSource> sources;
  private final JsonCodec json;
  private final Clock clock;
  private final OutboxService outbox;

  public SelectionService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      IdempotencyService requests,
      ObjectProvider<TableExportSource> sources,
      JsonCodec json,
      Clock clock,
      OutboxService outbox) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.requests = requests;
    this.sources = sources;
    this.json = json;
    this.clock = clock;
    this.outbox = outbox;
  }

  public Selection create(Scope scope, Request request) {
    scope.requireOrganization();
    var source = source(request.resource());
    authorization.requireLocked(scope, source.permissions());
    if (request.expectedRevision() != 0
        || request.query() == null
        || request.ids() != null
            && (request.ids().size() > 1000
                || request.ids().stream().distinct().count() != request.ids().size())) {
      throw new BusinessException("INVALID_SELECTION", 422, "Некорректная выборка");
    }
    List<UUID> ids = request.ids() == null ? List.of() : List.copyOf(request.ids());
    var projection = source.projection(scope, request.query(), ids);
    return requests.execute(
        scope,
        "selection.create",
        request.clientRequestId(),
        request,
        Selection.class,
        () -> {
          outbox.requireHeavyAdmission();
          UUID id = UUID.randomUUID();
          Instant expiresAt = clock.instant().plusSeconds(7 * 86400L);
          jdbc.sql(
                  """
                  INSERT INTO app_selection(id,organization_id,account_id,subject_id,resource,
                    query,columns,permissions,expires_at,data_status)
                  VALUES (:id,:org,:account,:subject,:resource,CAST(:query AS jsonb),
                    CAST(:columns AS jsonb),CAST(:permissions AS jsonb),:expires,:dataStatus)
                  """)
              .param("id", id)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("subject", scope.subjectId())
              .param("resource", source.resource())
              .param("query", json.encode(request.query()))
              .param("columns", json.encode(projection.columns()))
              .param("permissions", json.encode(source.permissions()))
              .param("expires", Timestamp.from(expiresAt))
              .param("dataStatus", projection.dataStatus())
              .update();
          // A single INSERT SELECT sees one committed source snapshot; no result set enters Java.
          String capturePermissions =
              projection.includesRowPermissions()
                  ? """
                  , permissions=CAST(:selectionPermissions AS jsonb) || COALESCE((
                    SELECT jsonb_agg(DISTINCT permission) FROM chosen,
                      LATERAL jsonb_array_elements_text(chosen.permissions) permission),'[]'::jsonb)
                  """
                  : "";
          int count =
              jdbc.sql(
                      "WITH chosen AS MATERIALIZED ("
                          + projection.sql()
                          + " LIMIT 1000001), captured AS ("
                          + """
                          INSERT INTO app_selection_row(organization_id,account_id,selection_id,
                            ordinal,canonical_id,cells)
                          SELECT :selectionOrganization,:selectionAccount,:selectionId,
                            row_number() OVER (),source.canonical_id,source.cells
                          FROM chosen source RETURNING 1)
                          UPDATE app_selection SET total_rows=(SELECT count(*) FROM captured)
                          """
                          + capturePermissions
                          + " WHERE id=:selectionId RETURNING total_rows")
                  .params(projection.parameters())
                  .param("selectionOrganization", scope.organizationId())
                  .param("selectionAccount", scope.accountId())
                  .param("selectionId", id)
                  .param("selectionPermissions", json.encode(source.permissions()))
                  .query(Integer.class)
                  .single();
          if (count > 1_000_000) {
            throw new BusinessException(
                "EXPORT_ROW_LIMIT", 422, "Выборка превышает миллион строк. Уточните фильтры");
          }
          if (!ids.isEmpty() && count != ids.size()) {
            throw new BusinessException(
                "SELECTION_CHANGED",
                409,
                "Часть выбранных объектов недоступна или не соответствует фильтрам");
          }
          // Row-level sensitivity is frozen from the same SQL snapshot as the exported values.
          snapshot(scope, id, false);
          return new Selection(id, count, expiresAt);
        });
  }

  public Snapshot snapshot(Scope scope, UUID id, boolean requireUnexpired) {
    Snapshot result =
        jdbc.sql(
                """
                SELECT id,resource,columns::text,permissions::text,total_rows,expires_at,data_status
                FROM app_selection WHERE id=:id FOR KEY SHARE
                """)
            .param("id", id)
            .query(
                (row, index) ->
                    new Snapshot(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        List.of(json.decode(row.getString(3), TableExportSource.Column[].class)),
                        Set.copyOf(Arrays.asList(json.decode(row.getString(4), String[].class))),
                        row.getLong(5),
                        row.getTimestamp(6).toInstant(),
                        row.getString(7)))
            .optional()
            .orElseThrow(
                () -> new BusinessException("SELECTION_NOT_FOUND", 404, "Выборка недоступна"));
    authorization.requireLocked(scope, result.permissions());
    if (requireUnexpired && !result.expiresAt().isAfter(clock.instant())) {
      throw new BusinessException("SELECTION_EXPIRED", 410, "Срок действия выборки истёк");
    }
    return result;
  }

  public Selection get(Scope scope, UUID id) {
    Snapshot value = snapshot(scope, id, true);
    return new Selection(value.id(), value.total(), value.expiresAt());
  }

  public List<UUID> ids(Scope scope, UUID id, UUID after, int limit) {
    snapshot(scope, id, true);
    if (limit < 1 || limit > 1000) {
      throw new IllegalArgumentException("Selection ID batch must be 1..1000");
    }
    return jdbc.sql(
            "SELECT canonical_id FROM app_selection_row WHERE selection_id=:id"
                + (after == null ? "" : " AND canonical_id>:after")
                + " ORDER BY canonical_id LIMIT :limit")
        .param("id", id)
        .param("after", after)
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  /** Keeps the frozen rows available while an accepted report or batch is unfinished. */
  public void retain(Scope scope, UUID id, String ownerType, UUID ownerId) {
    snapshot(scope, id, true);
    if (!Set.of("REPORT", "ASSIGNMENT_BATCH").contains(ownerType) || ownerId == null) {
      throw new IllegalArgumentException("Invalid selection owner");
    }
    jdbc.sql(
            """
            INSERT INTO app_selection_reference(organization_id,account_id,selection_id,owner_type,owner_id)
            VALUES (:org,:account,:selection,:type,:owner) ON CONFLICT DO NOTHING
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("selection", id)
        .param("type", ownerType)
        .param("owner", ownerId)
        .update();
  }

  /** Metadata cleanup is allowed after author revocation; it grants no right to read the rows. */
  public void release(Scope scope, UUID id, String ownerType, UUID ownerId) {
    ru.oritas.repricer.access.ScopeTransactionRunner.requireCurrent(scope);
    jdbc.sql("SELECT id FROM app_selection WHERE id=:id FOR NO KEY UPDATE")
        .param("id", id)
        .query(UUID.class)
        .optional();
    jdbc.sql(
            """
            DELETE FROM app_selection_reference
            WHERE selection_id=:id AND owner_type=:type AND owner_id=:owner
            """)
        .param("id", id)
        .param("type", ownerType)
        .param("owner", ownerId)
        .update();
  }

  public List<UUID> capturedIds(
      Scope scope, UUID id, String ownerType, UUID ownerId, UUID after, int limit) {
    snapshot(scope, id, false);
    if (limit < 1 || limit > 200) {
      throw new IllegalArgumentException("Captured selection batch must be 1..200");
    }
    boolean retained =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM app_selection_reference
                  WHERE selection_id=:id AND owner_type=:type AND owner_id=:owner)
                """)
            .param("id", id)
            .param("type", ownerType)
            .param("owner", ownerId)
            .query(Boolean.class)
            .single();
    if (!retained) {
      throw new BusinessException(
          "SELECTION_NOT_RETAINED", 409, "Выборка больше не удерживается операцией");
    }
    return jdbc.sql(
            "SELECT canonical_id FROM app_selection_row WHERE selection_id=:id"
                + (after == null ? "" : " AND canonical_id>:after")
                + " ORDER BY canonical_id LIMIT :limit")
        .param("id", id)
        .param("after", after)
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  public List<Row> rows(Scope scope, UUID id, long after) {
    Snapshot snapshot = snapshot(scope, id, false);
    if (!snapshot.expiresAt().isAfter(clock.instant())
        && !jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM app_selection_reference WHERE selection_id=:id)
                """)
            .param("id", id)
            .query(Boolean.class)
            .single()) {
      throw new BusinessException("SELECTION_EXPIRED", 410, "Срок хранения строк выборки истёк");
    }
    // Bound both row count and encoded bytes; allow one individually bounded oversized row.
    return jdbc.sql(
            """
            WITH batch AS MATERIALIZED (
              SELECT ordinal,cells FROM app_selection_row
              WHERE selection_id=:id AND ordinal>:after ORDER BY ordinal LIMIT 200
            ), bounded AS (
              SELECT ordinal,cells,sum(octet_length(cells::text)) OVER (ORDER BY ordinal) bytes,
                row_number() OVER (ORDER BY ordinal) position FROM batch
            )
            SELECT ordinal,cells::text FROM bounded WHERE bytes<=4194304 OR position=1 ORDER BY ordinal
            """)
        .param("id", id)
        .param("after", after)
        .query((row, index) -> new Row(row.getLong(1), decodeCells(row.getString(2))))
        .list();
  }

  private List<String> decodeCells(String value) {
    // Each stored row is bounded by a database constraint; JsonCodec's message cap is unrelated.
    String[] cells = json.gson().fromJson(value, String[].class);
    return java.util.Collections.unmodifiableList(Arrays.asList(cells));
  }

  TableExportSource source(String resource) {
    return sources
        .orderedStream()
        .filter(item -> item.resource().equals(resource))
        .findFirst()
        .orElseThrow(
            () ->
                new BusinessException(
                    "INVALID_SELECTION_RESOURCE", 422, "Для этой таблицы выборка недоступна"));
  }

  public record Request(
      UUID clientRequestId,
      long expectedRevision,
      String resource,
      TableExportSource.Query query,
      List<UUID> ids) {}

  public record Selection(UUID id, long total, Instant expiresAt) {}

  public record Snapshot(
      UUID id,
      String resource,
      List<TableExportSource.Column> columns,
      Set<String> permissions,
      long total,
      Instant expiresAt,
      String dataStatus) {}

  public record Row(long ordinal, List<String> cells) {}
}
