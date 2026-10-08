package ru.oritas.repricer.app.realtime;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;

@Service
public final class NotificationService {
  private static final Set<String> RESOURCES = Set.of("offers", "policies", "policy-assignments",
      "decisions", "commands", "temporary-runs", "marketplace-accounts", "sources", "memberships",
      "account-access", "organizations", "invitations", "economics", "cost-revisions", "tax-profile",
      "safety-envelopes", "operations", "imports", "reports", "stock-pools", "competitor-observations");
  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final RealtimeEventGateway events;
  private final IdempotencyService requests;

  public NotificationService(JdbcClient jdbc, AuthorizationService authorization,
      RealtimeEventGateway events, IdempotencyService requests) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.events = events;
    this.requests = requests;
  }

  public Page<Notification> list(Scope scope, int page, int size) {
    authorization.require(scope, "notification.read");
    int offset = Page.offset(page, size);
    Set<String> allowed = events.resources(scope, RESOURCES);
    if (allowed.isEmpty()) {
      return new Page<>(List.of(), 0, page, size);
    }
    long count = jdbc.sql("SELECT count(*) FROM app_change_event WHERE resource IN (:allowed)")
        .param("allowed", allowed).query(Long.class).single();
    List<Notification> rows = jdbc.sql("""
        SELECT e.id,e.event_type,e.resource,e.entity_id,e.created_at,e.sequence,
          (EXISTS(SELECT 1 FROM app_notification_read r WHERE r.event_id=e.id)
          OR EXISTS(SELECT 1 FROM app_notification_cursor c
            WHERE c.resource=e.resource AND c.read_through>=e.sequence)) AS is_read
        FROM app_change_event e WHERE resource IN (:allowed)
        ORDER BY sequence DESC LIMIT :size OFFSET :offset
        """).param("allowed", allowed).param("size", size).param("offset", offset)
        .query((row, index) -> new Notification(row.getObject(1, UUID.class), "Данные обновлены",
            row.getString(2), row.getString(3), row.getTimestamp(5).toInstant(), row.getBoolean(7),
            false, scope.accountId(), row.getString(3), row.getObject(4, UUID.class), row.getLong(6))).list();
    return new Page<>(rows, count, page, size);
  }

  public Receipt read(Scope scope, UUID id, UUID requestId) {
    authorization.require(scope, "notification.write");
    Set<String> allowed = events.resources(scope, RESOURCES);
    boolean visible = !allowed.isEmpty() && jdbc.sql("""
        SELECT EXISTS(SELECT 1 FROM app_change_event WHERE id=:id AND resource IN (:allowed))
        """).param("id", id).param("allowed", allowed).query(Boolean.class).single();
    if (!visible) {
      throw new BusinessException("NOTIFICATION_NOT_FOUND", 404, "Уведомление недоступно");
    }
    return requests.execute(scope, "notification.read", requestId, id, Receipt.class, () -> {
      jdbc.sql("""
          INSERT INTO app_notification_read(event_id,subject_id) VALUES (:id,:subject)
          ON CONFLICT DO NOTHING
          """).param("id", id).param("subject", scope.subjectId()).update();
      return new Receipt(id, true);
    });
  }

  public Receipt readAll(Scope scope, UUID requestId) {
    authorization.require(scope, "notification.write");
    Set<String> allowed = events.resources(scope, RESOURCES);
    return requests.execute(scope, "notifications.read-all", requestId, "read-all", Receipt.class, () -> {
      if (!allowed.isEmpty()) {
        jdbc.sql("""
            INSERT INTO app_notification_cursor
              (organization_id,account_id,subject_id,resource,read_through)
            SELECT :org,:account,:subject,resource,max(sequence)
              FROM app_change_event WHERE resource IN (:allowed) GROUP BY resource
            ON CONFLICT(subject_id,scope_key,resource) DO UPDATE
              SET read_through=GREATEST(app_notification_cursor.read_through,EXCLUDED.read_through)
            """).param("subject", scope.subjectId()).param("org", scope.organizationId())
            .param("account", scope.accountId()).param("allowed", allowed).update();
      }
      return new Receipt(requestId, true);
    });
  }

  public record Receipt(UUID id, boolean read) {}
  public record Notification(UUID id, String title, String message, String category,
      Instant createdAt, boolean read, boolean active, UUID accountId, String resourceType,
      UUID resourceId, long revision) {}
}
