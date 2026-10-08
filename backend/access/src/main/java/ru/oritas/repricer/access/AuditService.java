package ru.oritas.repricer.access;

import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.HistoryQuery;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;

@Service
public final class AuditService {
  private final JdbcClient jdbc;

  public AuditService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void record(Scope scope, String action, UUID target, String safeDetails) {
    recordForOffer(scope, action, target, null, safeDetails);
  }

  public void recordForOffer(
      Scope scope, String action, UUID target, UUID offerId, String safeDetails) {
    ScopeTransactionRunner.requireCurrent(scope);
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Audit belongs to its business transaction");
    }
    if (safeDetails.length() > 4096) {
      throw new IllegalArgumentException("Audit detail exceeds limit");
    }
    jdbc.sql(
            """
            INSERT INTO access_audit(id,organization_id,account_id,subject_id,action,target_id,
              offer_id,details)
            VALUES (:id,:org,:account,:subject,:action,:target,:offer,:details)
            """)
        .param("id", UUID.randomUUID())
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("action", action)
        .param("target", target)
        .param("offer", offerId)
        .param("details", safeDetails)
        .update();
  }

  public Page<Entry> list(Scope scope, Query query, ZoneId zone) {
    ScopeTransactionRunner.requireCurrent(scope);
    Parts parts = filter(query, zone, List.of());
    int offset = Page.offset(query.history().page(), query.history().size());
    long count =
        jdbc.sql("SELECT count(*) FROM access_audit" + parts.where())
            .params(parts.parameters())
            .query(Long.class)
            .single();
    List<Entry> items =
        jdbc.sql(
                """
                SELECT id,subject_id,action,target_id,details,occurred_at FROM access_audit
                """
                    + parts.where()
                    + parts.order()
                    + " LIMIT :size OFFSET :offset")
            .params(parts.parameters())
            .param("size", query.history().size())
            .param("offset", offset)
            .query(
                (row, index) ->
                    new Entry(
                        row.getObject(1, UUID.class),
                        row.getObject(2, UUID.class),
                        row.getString(3),
                        row.getObject(4, UUID.class),
                        row.getString(5),
                        row.getTimestamp(6).toInstant()))
            .list();
    return new Page<>(items, count, query.history().page(), query.history().size());
  }

  public TableExportSource.Projection projection(
      Scope scope, Query query, ZoneId zone, List<UUID> ids) {
    ScopeTransactionRunner.requireCurrent(scope);
    Parts parts = filter(query, zone, ids);
    return new TableExportSource.Projection(
        """
        SELECT id AS canonical_id,jsonb_build_array(id::text,subject_id::text,action,
          target_id::text,details,occurred_at::text) cells FROM access_audit
        """
            + parts.where()
            + parts.order(),
        parts.parameters(),
        List.of(
            new TableExportSource.Column("id", "Запись", false),
            new TableExportSource.Column("actorId", "Пользователь", false),
            new TableExportSource.Column("action", "Действие", false),
            new TableExportSource.Column("targetId", "Объект", false),
            new TableExportSource.Column("details", "Подробности", false),
            new TableExportSource.Column("createdAt", "Время", false)));
  }

  private static Parts filter(Query selection, ZoneId zone, List<UUID> ids) {
    HistoryQuery query = selection.history();
    String column =
        switch (query.sort()) {
          case "id" -> "id";
          case "actorId" -> "subject_id";
          case "targetId" -> "target_id";
          case "name", "action", "status" -> "action";
          case "date", "createdAt" -> "occurred_at";
          case "details" -> "details";
          default ->
              throw new BusinessException("INVALID_SORT", 422, "Неизвестная колонка сортировки");
        };
    String where =
        " WHERE (:status='' OR action=:status) AND position(lower(:search) in lower(concat_ws('"
            + " ',subject_id::text,action,target_id::text,details)))>0"
            + query.datePredicate("occurred_at");
    Map<String, Object> parameters = query.parameters(zone);
    if (selection.offerId() != null) {
      where += " AND offer_id=:offerId";
      parameters.put("offerId", selection.offerId());
    }
    if (!ids.isEmpty()) {
      where += " AND id IN (:ids)";
      parameters.put("ids", ids);
    }
    return new Parts(where, " ORDER BY " + column + " " + query.direction() + ",id", parameters);
  }

  public record Entry(
      UUID id, UUID actorId, String action, UUID targetId, String details, Instant createdAt) {}

  public record Query(HistoryQuery history, UUID offerId) {
    public static Query from(TableExportSource.Query query) {
      var filters = new LinkedHashMap<>(query.filters());
      String value = filters.remove("offerId");
      UUID offerId;
      try {
        offerId = value == null ? null : UUID.fromString(value);
      } catch (IllegalArgumentException exception) {
        throw new BusinessException("INVALID_AUDIT_FILTER", 422, "Некорректный товар в журнале");
      }
      return new Query(
          HistoryQuery.from(
              new TableExportSource.Query(
                  query.search(), filters, query.sort(), query.page(), query.size())),
          offerId);
    }
  }

  private record Parts(String where, String order, Map<String, Object> parameters) {}
}
