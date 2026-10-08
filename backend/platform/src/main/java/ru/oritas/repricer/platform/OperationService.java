package ru.oritas.repricer.platform;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Public operation metadata deliberately excludes payload, results and internal exception text. */
@Service
public final class OperationService {
  private final JdbcClient jdbc;

  public OperationService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Page<Operation> list(HistoryQuery query, ZoneId zone) {
    Parts parts = filter(query, zone, List.of());
    int offset = Page.offset(query.page(), query.size());
    long count =
        jdbc.sql("SELECT count(*) FROM platform_job" + parts.where())
            .params(parts.parameters())
            .query(Long.class)
            .single();
    List<Operation> items =
        jdbc.sql(
                """
                SELECT id,job_type,state,created_at,reason,fence FROM platform_job
                """
                    + parts.where()
                    + parts.order()
                    + " LIMIT :size OFFSET :offset")
            .params(parts.parameters())
            .param("size", query.size())
            .param("offset", offset)
            .query(
                (row, index) ->
                    new Operation(
                        row.getObject(1, UUID.class),
                        row.getString(2),
                        row.getString(3),
                        row.getTimestamp(4).toInstant(),
                        row.getString(5),
                        row.getLong(6),
                        null))
            .list();
    return new Page<>(items, count, query.page(), query.size());
  }

  public TableExportSource.Projection projection(HistoryQuery query, ZoneId zone, List<UUID> ids) {
    Parts parts = filter(query, zone, ids);
    return new TableExportSource.Projection(
        """
        SELECT id AS canonical_id,jsonb_build_array(id::text,job_type,state,created_at::text,reason,NULL) cells
        FROM platform_job
        """
            + parts.where()
            + parts.order(),
        parts.parameters(),
        List.of(
            new TableExportSource.Column("id", "Операция", false),
            new TableExportSource.Column("name", "Тип", false),
            new TableExportSource.Column("status", "Состояние", false),
            new TableExportSource.Column("createdAt", "Создана", false),
            new TableExportSource.Column("message", "Причина", false),
            new TableExportSource.Column("progress", "Процент выполнения", true)));
  }

  private static Parts filter(HistoryQuery query, ZoneId zone, List<UUID> ids) {
    if (!Set.of("", "READY", "RUNNING", "WAITING", "SUCCEEDED", "BLOCKED", "DEAD", "CANCELLED")
        .contains(query.status())) {
      throw new BusinessException("INVALID_OPERATION_STATE", 422, "Неизвестное состояние операции");
    }
    String column =
        switch (query.sort()) {
          case "id" -> "id";
          case "name" -> "job_type";
          case "status" -> "state";
          case "date", "createdAt" -> "created_at";
          case "revision" -> "fence";
          case "message" -> "reason";
          // A checkpoint or active lease does not prove a percentage of the whole operation.
          case "progress" -> "CAST(NULL AS integer)";
          default ->
              throw new BusinessException("INVALID_SORT", 422, "Неизвестная колонка сортировки");
        };
    String where =
        " WHERE job_type<>'OUTBOX_DELIVERY' AND deletion_after IS NULL"
            + " AND (:status='' OR state=:status)"
            + " AND position(lower(:search) in lower(concat_ws(' ',id::text,job_type,reason)))>0"
            + query.datePredicate("created_at");
    Map<String, Object> parameters = query.parameters(zone);
    if (!ids.isEmpty()) {
      where += " AND id IN (:ids)";
      parameters.put("ids", ids);
    }
    return new Parts(where, " ORDER BY " + column + " " + query.direction() + ",id", parameters);
  }

  public Operation get(UUID id) {
    return jdbc.sql(
            "SELECT id,job_type,state,created_at,reason,fence,deletion_after FROM platform_job"
                + " WHERE id=:id AND job_type<>'OUTBOX_DELIVERY'")
        .param("id", id)
        .query(
            (row, index) -> {
              if (row.getTimestamp(7) != null) {
                throw new BusinessException(
                    "OPERATION_EXPIRED", 410, "Срок хранения задания истёк");
              }
              return new Operation(
                  row.getObject(1, UUID.class),
                  row.getString(2),
                  row.getString(3),
                  row.getTimestamp(4).toInstant(),
                  row.getString(5),
                  row.getLong(6),
                  null);
            })
        .optional()
        .orElseThrow(
            () -> new BusinessException("OPERATION_NOT_FOUND", 404, "Операция не найдена"));
  }

  public record Operation(
      UUID id,
      String name,
      String status,
      Instant createdAt,
      String message,
      long revision,
      Integer progress) {}

  private record Parts(String where, String order, Map<String, Object> parameters) {}
}
