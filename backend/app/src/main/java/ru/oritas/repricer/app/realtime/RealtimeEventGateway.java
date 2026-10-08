package ru.oritas.repricer.app.realtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.BusinessException;

/** Durable invalidations contain identities and revisions, never financial values or secrets. */
@Service
public final class RealtimeEventGateway implements OutboxRecipient {
  private static final Map<String, String> PERMISSIONS = Map.ofEntries(
      Map.entry("offers", "catalog.read"), Map.entry("policies", "policy.read"),
      Map.entry("policy-assignments", "policy.read"), Map.entry("decisions", "command.read"),
      Map.entry("commands", "command.read"), Map.entry("temporary-runs", "command.read"),
      Map.entry("automation-sweeps", "command.read"),
      Map.entry("marketplace-accounts", "account.read"), Map.entry("sources", "account.read"),
      Map.entry("memberships", "membership.read"), Map.entry("account-access", "membership.read"),
      Map.entry("organizations", "organization.read"), Map.entry("invitations", "membership.read"),
      Map.entry("economics", "finance.read"), Map.entry("cost-revisions", "finance.read"),
      Map.entry("tax-profile", "finance.read"), Map.entry("safety-envelopes", "finance.read"),
      Map.entry("operations", "operation.read"), Map.entry("imports", "file.read"),
      Map.entry("reports", "export"), Map.entry("stock-pools", "catalog.read"),
      Map.entry("audit", "audit.read"), Map.entry("notifications", "notification.read"),
      Map.entry("competitor-observations", "competitor.read"));
  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final AuthorizationService authorization;

  public RealtimeEventGateway(JdbcClient jdbc, JsonCodec json, AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.json = json;
    this.authorization = authorization;
  }

  @Override
  public String name() {
    return "UI_INVALIDATION";
  }

  @Override
  public boolean accepts(String eventType) {
    return true;
  }

  @Override
  public Set<String> servicePermissions() {
    return Set.of("app.events.manage");
  }

  @Override
  public void receive(Scope scope, UUID eventId, String type, String payload) {
    OutboxService.EntityChange change = json.decode(payload, OutboxService.EntityChange.class);
    if (change.resource() == null || change.entityId() == null) {
      return;
    }
    String resource = normalizeResource(change.resource());
    if (!PERMISSIONS.containsKey(resource)) {
      return;
    }
    // Allocate sequence numbers in commit order within a scope, including concurrent workers.
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope,0))")
        .param("scope", "ui-events:" + scope.organizationId() + ":" + scope.accountId())
        .query(Object.class).single();
    jdbc.sql("""
        INSERT INTO app_change_event(id,organization_id,account_id,event_type,resource,entity_id,revision)
        VALUES (:id,:org,:account,:type,:resource,:entity,:revision) ON CONFLICT(id) DO NOTHING
        """).param("id", eventId).param("org", scope.organizationId()).param("account", scope.accountId())
        .param("type", type).param("resource", resource).param("entity", change.entityId())
        .param("revision", Math.max(0, change.revision())).update();
  }

  public Set<String> resources(Scope scope, Set<String> requested) {
    Set<String> permissions = authorization.permissions(scope);
    return permittedResources(scope, requested, permissions);
  }

  private Set<String> permittedResources(Scope scope, Set<String> requested,
      Set<String> permissions) {
    return requested.stream().filter(resource -> PERMISSIONS.containsKey(resource)
            && permissions.contains(resource.equals("policies") && scope.accountId() == null
                ? "organization.policy.read" : PERMISSIONS.get(resource)))
        .collect(Collectors.toUnmodifiableSet());
  }

  public SubscriptionStart subscribe(Scope scope, Set<String> requested) {
    Set<String> permissions = authorization.permissions(scope);
    requireResources(scope, requested, permissions);
    return new SubscriptionStart(cursor(scope), permissions);
  }

  public List<Change> deliver(Scope scope, Set<String> requested, long cursor,
      Set<String> expectedPermissions) {
    Set<String> permissions = authorization.permissions(scope);
    if (!permissions.equals(expectedPermissions)) {
      throw new BusinessException("SESSION_SCOPE_CHANGED", 403, "Права доступа изменились");
    }
    requireResources(scope, requested, permissions);
    return query(scope, requested, cursor, permissions);
  }

  private void requireResources(Scope scope, Set<String> requested, Set<String> permissions) {
    if (!permittedResources(scope, requested, permissions).equals(requested)) {
      throw new BusinessException("SUBSCRIPTION_DENIED", 403, "Нет доступа к подписке");
    }
  }

  public long cursor(Scope scope) {
    authorization.require(scope, "notification.read");
    return jdbc.sql("SELECT COALESCE(max(sequence),0) FROM app_change_event").query(Long.class).single();
  }

  public List<Change> after(Scope scope, Set<String> requested, long cursor) {
    return query(scope, requested, cursor, authorization.permissions(scope));
  }

  private List<Change> query(Scope scope, Set<String> requested, long cursor,
      Set<String> permissions) {
    Set<String> allowed = permittedResources(scope,
        requested.contains("notifications") ? PERMISSIONS.keySet() : requested, permissions);
    if (allowed.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("""
        SELECT sequence,id,resource,entity_id,revision,created_at FROM app_change_event
        WHERE sequence>:cursor AND resource IN (:resources) ORDER BY sequence LIMIT 33
        """).param("cursor", cursor).param("resources", allowed).query((row, index) -> new Change(
            row.getLong(1), row.getObject(2, UUID.class), row.getString(3), row.getObject(4, UUID.class),
            row.getLong(5), row.getTimestamp(6).toInstant())).list();
  }

  private static String normalizeResource(String resource) {
    return switch (resource) {
      case "costs" -> "cost-revisions";
      case "tax" -> "tax-profile";
      case "safety-envelope" -> "safety-envelopes";
      case "temporary-economic-permissions" -> "temporary-runs";
      case "runtime" -> "policy-assignments";
      case "competitors" -> "competitor-observations";
      case "accounts" -> "marketplace-accounts";
      case "sales-pace", "payments", "returns", "services" -> "economics";
      case "promotions" -> "sources";
      default -> resource;
    };
  }

  public record Change(long sequence, UUID id, String resource, UUID entityId,
      long entityRevision, Instant createdAt) {}

  public record SubscriptionStart(long cursor, Set<String> permissions) {}
}
