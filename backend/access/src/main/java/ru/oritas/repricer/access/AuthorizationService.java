package ru.oritas.repricer.access;

import java.util.HashSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Scope;

/** Current database membership, field permissions and role ceilings are checked together. */
@Service
public final class AuthorizationService {
  private static final Set<String> RECOGNIZED = Set.of(
      "organization.read", "organization.manage", "organization.policy.read",
      "organization.policy.manage", "membership.read", "membership.manage", "ownership.transfer",
      "account.read", "account.manage", "connection.manage", "catalog.read", "sync.request",
      "policy.read", "policy.assign", "decision.preview", "decision.approve", "command.read",
      "command.reconcile", "automation.manage", "automation.pause", "automation.resume",
      "finance.read", "finance.write", "resource.manage", "export", "raw.read",
      "competitor.read", "competitor.write", "audit.read", "file.read", "file.write",
      "notification.read", "notification.write", "view.read", "view.write", "operation.read");
  private static final Set<String> READ = Set.of(
      "organization.read", "account.read", "catalog.read", "policy.read", "command.read",
      "competitor.read", "notification.read", "notification.write", "view.read", "view.write",
      "operation.read", "membership.read", "file.read");
  private static final Set<String> OPERATOR = Set.of("decision.preview", "decision.approve", "automation.pause");
  private static final Set<String> MANAGER = Set.of(
      "policy.assign", "automation.manage", "automation.resume", "membership.manage",
      "competitor.write", "sync.request");
  private static final Set<String> EXTRAS = Set.of(
      "finance.read", "finance.write", "resource.manage", "connection.manage",
      "export", "raw.read", "audit.read", "file.write");
  private final JdbcClient jdbc;

  public AuthorizationService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void requireMembership(Scope scope) {
    boolean member = jdbc.sql("""
        SELECT EXISTS(SELECT 1 FROM access_membership
          WHERE organization_id=:org AND subject_id=:subject AND active)
        """).param("org", scope.organizationId()).param("subject", scope.subjectId())
        .query(Boolean.class).single();
    if (!member) {
      throw denied();
    }
    if (scope.accountId() != null && !isOwner(scope)) {
      boolean assigned = jdbc.sql("""
          SELECT EXISTS(SELECT 1 FROM access_account_permission
            WHERE organization_id=:org AND account_id=:account AND subject_id=:subject AND active
              AND membership_generation=(SELECT access_generation FROM access_membership
                WHERE organization_id=:org AND subject_id=:subject AND active))
          """).param("org", scope.organizationId()).param("account", scope.accountId())
          .param("subject", scope.subjectId()).query(Boolean.class).single();
      if (!assigned) {
        throw denied();
      }
    }
  }

  public void require(Scope scope, String permission) {
    ScopeTransactionRunner.requireCurrent(scope);
    if (ScopeTransactionRunner.serviceAllows(scope, permission)) {
      return;
    }
    if (!hasPermission(scope, permission)) {
      throw denied();
    }
  }

  /** Holds the authorization boundary until the protected state transition commits. */
  public void requireLocked(Scope scope, Set<String> permissions) {
    ScopeTransactionRunner.requireCurrent(scope);
    scope.requireOrganization();
    lockOrganizationRights(scope);
    jdbc.sql("""
        SELECT id FROM access_membership WHERE organization_id=:org
          AND subject_id=:subject AND active FOR SHARE
        """).param("org", scope.organizationId()).param("subject", scope.subjectId())
        .query(UUID.class).optional().orElseThrow(AuthorizationService::denied);
    if (scope.accountId() != null) {
      jdbc.sql("""
          SELECT id FROM access_account_permission WHERE organization_id=:org
            AND account_id=:account AND subject_id=:subject AND active FOR SHARE
          """).param("org", scope.organizationId()).param("account", scope.accountId())
          .param("subject", scope.subjectId()).query(UUID.class).optional();
    }
    for (String permission : permissions) {
      require(scope, permission);
    }
  }

  private void lockOrganizationRights(Scope scope) {
    jdbc.sql("SELECT id FROM access_organization WHERE id=:id FOR SHARE")
        .param("id", scope.organizationId()).query(UUID.class).optional()
        .orElseThrow(AuthorizationService::denied);
  }

  /** Durable delegations remain effective only while their original issuer has current authority. */
  public AuthorityStamp requireActorLocked(Scope current, UUID actorId, Set<String> required) {
    ScopeTransactionRunner.requireCurrent(current);
    lockOrganizationRights(current);
    jdbc.sql("""
        SELECT id FROM access_membership WHERE organization_id=:org AND subject_id=:actor AND active FOR SHARE
        """).param("org", current.organizationId()).param("actor", actorId)
        .query(UUID.class).optional().orElseThrow(AuthorizationService::denied);
    if (current.accountId() != null) {
      jdbc.sql("""
          SELECT id FROM access_account_permission WHERE organization_id=:org AND account_id=:account
            AND subject_id=:actor AND active FOR SHARE
          """).param("org", current.organizationId()).param("account", current.accountId())
          .param("actor", actorId).query(UUID.class).optional();
    }
    if (!resolvePermissions(new Scope(current.organizationId(), current.accountId(), actorId)).containsAll(required)) {
      throw denied();
    }
    long membership = jdbc.sql("SELECT revision FROM access_membership WHERE organization_id=:org AND subject_id=:actor")
        .param("org", current.organizationId()).param("actor", actorId).query(Long.class).single();
    long account = current.accountId() == null ? 0 : jdbc.sql("""
        SELECT revision FROM access_account_permission WHERE organization_id=:org
          AND account_id=:account AND subject_id=:actor
        """).param("org", current.organizationId()).param("account", current.accountId())
        .param("actor", actorId).query(Long.class).optional().orElse(0L);
    return new AuthorityStamp(membership, account);
  }

  public record AuthorityStamp(long membershipRevision, long accountRevision) {}

  public boolean hasPermission(Scope scope, String permission) {
    ScopeTransactionRunner.requireCurrent(scope);
    if (ScopeTransactionRunner.serviceAllows(scope, permission)) {
      return true;
    }
    return RECOGNIZED.contains(permission) && permissions(scope).contains(permission);
  }

  public Set<String> permissions(Scope scope) {
    ScopeTransactionRunner.requireCurrent(scope);
    return resolvePermissions(scope);
  }

  /** Locks the current grants so revocation and company publication have one ordering. */
  public void requireAcrossAccounts(Scope scope, Collection<UUID> accountIds,
      Set<String> required) {
    ScopeTransactionRunner.requireCurrent(scope);
    scope.requireOrganization();
    if (!RECOGNIZED.containsAll(required)) {
      throw denied();
    }
    lockOrganizationRights(scope);
    jdbc.sql("""
        SELECT id FROM access_membership WHERE organization_id=:org
          AND subject_id=:subject AND active FOR SHARE
        """).param("org", scope.organizationId()).param("subject", scope.subjectId())
        .query(UUID.class).optional().orElseThrow(AuthorizationService::denied);
    for (UUID accountId : accountIds.stream().distinct().sorted().toList()) {
      jdbc.sql("""
          SELECT id FROM access_account_permission WHERE organization_id=:org
            AND account_id=:account AND subject_id=:subject AND active FOR SHARE
          """).param("org", scope.organizationId()).param("account", accountId)
          .param("subject", scope.subjectId()).query(UUID.class).optional();
      Scope target = new Scope(scope.organizationId(), accountId, scope.subjectId());
      if (!resolvePermissions(target).containsAll(required)) {
        throw denied();
      }
    }
  }

  private Set<String> resolvePermissions(Scope scope) {
    if (scope.organizationId() == null) {
      return Set.of();
    }
    requireMembership(scope);
    if (isOwner(scope)) {
      return RECOGNIZED;
    }
    Set<String> result = new HashSet<>();
    result.addAll(Set.of("organization.read", "view.read", "view.write",
        "notification.read", "notification.write", "operation.read"));
    List<String> organizationPermissions = jdbc.sql("""
        SELECT unnest(permissions) FROM access_membership
        WHERE organization_id=:org AND subject_id=:subject AND active
        """).param("org", scope.organizationId()).param("subject", scope.subjectId())
        .query(String.class).list();
    for (String permission : organizationPermissions) {
      if (Set.of("organization.policy.read", "organization.policy.manage").contains(permission)) {
        result.add(permission);
      }
    }
    if (result.contains("organization.policy.manage")) {
      result.add("organization.policy.read");
    }
    if (scope.accountId() == null) {
      return Set.copyOf(result);
    }
    var access = jdbc.sql("""
        SELECT role,permissions FROM access_account_permission
        WHERE organization_id=:org AND account_id=:account AND subject_id=:subject AND active
          AND membership_generation=(SELECT access_generation FROM access_membership
            WHERE organization_id=:org AND subject_id=:subject AND active)
        """).param("org", scope.organizationId()).param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .query((row, index) -> new Assignment(row.getString("role"),
            Set.of((String[]) row.getArray("permissions").getArray())))
        .optional();
    if (access.isEmpty()) {
      return Set.copyOf(result);
    }
    Assignment assignment = access.orElseThrow();
    result.addAll(READ);
    if (!assignment.role().equals("VIEWER")) {
      result.addAll(OPERATOR);
    }
    boolean manager = Set.of("ADMIN", "MANAGER").contains(assignment.role());
    if (manager) {
      result.addAll(MANAGER);
    }
    if (assignment.role().equals("ADMIN")) {
      result.add("command.reconcile");
    }
    for (String extra : assignment.permissions()) {
      if (EXTRAS.contains(extra)) {
        if (manager || !Set.of("finance.write", "resource.manage", "connection.manage", "file.write").contains(extra)) {
          result.add(extra);
        }
      }
    }
    if (!result.contains("finance.read")) {
      result.removeAll(Set.of("finance.write", "policy.assign", "resource.manage"));
    }
    return Set.copyOf(result);
  }

  public boolean isOwner(Scope scope) {
    return isOwner(scope, scope.subjectId());
  }

  public boolean isOwner(Scope scope, UUID subjectId) {
    return jdbc.sql("""
        SELECT EXISTS(SELECT 1 FROM access_membership
          WHERE organization_id=:org AND subject_id=:subject AND active AND role='OWNER')
        """).param("org", scope.organizationId()).param("subject", subjectId)
        .query(Boolean.class).single();
  }

  public void requireDelegation(Scope scope, UUID grantorId, String requestedRole,
      Set<String> extras) {
    ScopeTransactionRunner.requireCurrent(scope);
    Scope grantor = new Scope(scope.organizationId(), scope.accountId(), grantorId);
    requireMembership(grantor);
    if (requestedRole.equals("MEMBER")) {
      if (!isOwner(scope, grantorId)
          || !Set.of("organization.policy.read", "organization.policy.manage").containsAll(extras)) {
        throw denied();
      }
      return;
    }
    scope.requireAccount();
    if (!Set.of("ADMIN", "MANAGER", "OPERATOR", "VIEWER").contains(requestedRole)
        || !EXTRAS.containsAll(extras)) {
      throw denied();
    }
    if (!Set.of("ADMIN", "MANAGER").contains(requestedRole)
        && extras.stream().anyMatch(Set.of("finance.write", "resource.manage",
            "connection.manage", "file.write")::contains)) {
      throw denied();
    }
    if (!extras.contains("finance.read")
        && extras.stream().anyMatch(Set.of("finance.write", "resource.manage")::contains)) {
      throw denied();
    }
    if (isOwner(scope, grantorId)) {
      return;
    }
    String role = jdbc.sql("""
        SELECT role FROM access_account_permission WHERE organization_id=:org
          AND account_id=:account AND subject_id=:subject AND active FOR SHARE
        """).param("org", scope.organizationId()).param("account", scope.accountId())
        .param("subject", grantorId).query(String.class).optional()
        .orElseThrow(AuthorizationService::denied);
    boolean allowedRole = role.equals("ADMIN") || (role.equals("MANAGER")
        && Set.of("OPERATOR", "VIEWER").contains(requestedRole));
    if (!allowedRole || !resolvePermissions(grantor).containsAll(extras)) {
      throw denied();
    }
  }

  public long revision(Scope scope) {
    return jdbc.sql("""
        SELECT revision FROM access_membership
        WHERE organization_id=:org AND subject_id=:subject AND active
        """).param("org", scope.organizationId()).param("subject", scope.subjectId())
        .query(Long.class).optional().orElseThrow(AuthorizationService::denied);
  }

  private static BusinessException denied() {
    return new BusinessException("ACCESS_DENIED", 403, "Недостаточно прав для этой операции");
  }

  private record Assignment(String role, Set<String> permissions) {}
}
