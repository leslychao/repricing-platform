package ru.oritas.repricer.access;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** Explicit AUTO authority bound to an immutable publication and its complete commercial scope. */
@Service
public final class AutomationGrantService {
  private static final Set<String> REQUIRED =
      Set.of("automation.manage", "finance.read", "decision.approve");
  private static final Set<String> OPERATIONS =
      Set.of("SET_BASE_PRICE", "SET_PRICE", "SET_PROMO_PRICE", "LEAVE_PROMO");
  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final JsonCodec json;
  private final OutboxService outbox;

  public AutomationGrantService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      AuditService audit,
      JsonCodec json,
      OutboxService outbox) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.audit = audit;
    this.json = json;
    this.outbox = outbox;
  }

  /** The automation owner verifies the assignment and scope before entering this transaction. */
  public Grant grant(
      Scope scope,
      UUID assignmentId,
      long assignmentRevision,
      long policyVersion,
      String scopeDigest,
      Set<String> operations,
      Set<UUID> targetIds,
      long expectedRevision) {
    authorization.requireLocked(scope, REQUIRED);
    scope.requireAccount();
    Binding binding =
        new Binding(
            assignmentId, assignmentRevision, policyVersion, scopeDigest, operations, targetIds);
    var authority = authorization.requireActorLocked(scope, scope.subjectId(), REQUIRED);
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key", "auto-grant:" + scope.accountId() + ":" + assignmentId)
        .query((row, index) -> true)
        .single();
    long current =
        jdbc.sql(
                "SELECT COALESCE(max(revision),0) FROM access_automation_grant WHERE"
                    + " assignment_id=:assignment")
            .param("assignment", assignmentId)
            .query(Long.class)
            .single();
    if (current != expectedRevision) {
      throw new BusinessException("GRANT_REVISION_CONFLICT", 409, "Разрешение AUTO изменилось");
    }
    jdbc.sql(
            "UPDATE access_automation_grant SET active=false WHERE assignment_id=:assignment AND"
                + " active")
        .param("assignment", assignmentId)
        .update();
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO access_automation_grant(id,organization_id,account_id,assignment_id,issuer_id,
              revision,binding,active,issuer_membership_revision,issuer_account_revision)
            VALUES (:id,:org,:account,:assignment,:issuer,:revision,CAST(:binding AS jsonb),true,:member,:grant)
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("assignment", assignmentId)
        .param("issuer", scope.subjectId())
        .param("revision", current + 1)
        .param("binding", json.encode(binding))
        .param("member", authority.membershipRevision())
        .param("grant", authority.accountRevision())
        .update();
    audit.record(scope, "AUTO_AUTHORITY_GRANTED", id, "assignment=" + assignmentId);
    changed(scope, id, assignmentId, current + 1, "granted");
    return get(id, false);
  }

  public Grant requireCurrent(
      Scope scope,
      UUID id,
      UUID assignmentId,
      long assignmentRevision,
      long policyVersion,
      String scopeDigest,
      Set<String> operations,
      Set<UUID> targetIds) {
    return requireCurrent(
        scope,
        id,
        new Binding(
            assignmentId, assignmentRevision, policyVersion, scopeDigest, operations, targetIds));
  }

  public Grant requireCurrent(Scope scope, UUID id, Binding expected) {
    ScopeTransactionRunner.requireCurrent(scope);
    Grant current = get(id, false);
    var authority = authorization.requireActorLocked(scope, current.issuerId(), REQUIRED);
    current = get(id, true);
    var issued =
        jdbc.sql(
                "SELECT issuer_membership_revision,issuer_account_revision FROM"
                    + " access_automation_grant WHERE id=:id")
            .param("id", id)
            .query(
                (row, index) ->
                    new AuthorizationService.AuthorityStamp(row.getLong(1), row.getLong(2)))
            .single();
    if (!current.active() || !current.binding().equals(expected) || !authority.equals(issued)) {
      throw new BusinessException("AUTO_AUTHORITY_STALE", 409, "Требуется новое разрешение AUTO");
    }
    return current;
  }

  public Grant current(Scope scope, UUID assignmentId) {
    authorization.require(scope, "automation.manage");
    UUID id =
        jdbc.sql(
                "SELECT id FROM access_automation_grant WHERE assignment_id=:assignment AND active")
            .param("assignment", assignmentId)
            .query(UUID.class)
            .optional()
            .orElseThrow(
                () ->
                    new BusinessException(
                        "AUTO_AUTHORITY_REQUIRED", 409, "Разрешение AUTO не выдано"));
    return get(id, false);
  }

  public java.util.Optional<Grant> latest(Scope scope, UUID assignmentId) {
    authorization.require(scope, "automation.manage");
    return jdbc.sql(
            "SELECT id FROM access_automation_grant WHERE assignment_id=:assignment ORDER BY"
                + " revision DESC LIMIT 1")
        .param("assignment", assignmentId)
        .query(UUID.class)
        .optional()
        .map(id -> get(id, false));
  }

  public void revoke(Scope scope, UUID id, long expectedRevision) {
    authorization.requireLocked(scope, Set.of("automation.manage"));
    jdbc.sql("SELECT id FROM access_automation_grant WHERE id=:id FOR UPDATE")
        .param("id", id)
        .query(UUID.class)
        .optional();
    Grant grant = get(id, false);
    if (grant.revision() != expectedRevision) {
      throw new BusinessException("GRANT_REVISION_CONFLICT", 409, "Разрешение AUTO изменилось");
    }
    if (grant.active()) {
      jdbc.sql("UPDATE access_automation_grant SET active=false WHERE id=:id")
          .param("id", id)
          .update();
      audit.record(scope, "AUTO_AUTHORITY_REVOKED", id, "");
      changed(scope, id, grant.binding().assignmentId(), grant.revision(), "revoked");
    }
  }

  private void changed(Scope scope, UUID grantId, UUID assignmentId, long revision, String action) {
    outbox.emit(
        scope,
        "auto-grant:" + grantId + ":" + action,
        "automation.authority.changed",
        new OutboxService.EntityChange("policy-assignments", assignmentId, revision));
  }

  private Grant get(UUID id, boolean lock) {
    return jdbc.sql(
            "SELECT id,issuer_id,revision,binding::text,active,created_at FROM"
                + " access_automation_grant WHERE id=:id"
                + (lock ? " FOR SHARE" : ""))
        .param("id", id)
        .query(
            (row, index) ->
                new Grant(
                    row.getObject(1, UUID.class),
                    row.getObject(2, UUID.class),
                    row.getLong(3),
                    json.decode(row.getString(4), Binding.class),
                    row.getBoolean(5),
                    row.getTimestamp(6).toInstant()))
        .optional()
        .orElseThrow(
            () ->
                new BusinessException(
                    "AUTO_AUTHORITY_REQUIRED", 409, "Разрешение AUTO недоступно"));
  }

  public record Binding(
      UUID assignmentId,
      long assignmentRevision,
      long policyVersion,
      String scopeDigest,
      Set<String> operations,
      Set<UUID> targetIds) {
    public Binding {
      operations = Set.copyOf(operations);
      targetIds = Set.copyOf(targetIds);
      if (assignmentId == null
          || assignmentRevision < 1
          || policyVersion < 1
          || scopeDigest == null
          || !scopeDigest.matches("[0-9a-f]{64}")
          || operations.isEmpty()
          || !OPERATIONS.containsAll(operations)
          || targetIds.isEmpty()
          || targetIds.size() > 1000) {
        throw new BusinessException(
            "INVALID_AUTO_AUTHORITY", 422, "Некорректная область разрешения AUTO");
      }
    }
  }

  public record Grant(
      UUID id, UUID issuerId, long revision, Binding binding, boolean active, Instant createdAt) {}
}
