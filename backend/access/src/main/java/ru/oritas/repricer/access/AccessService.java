package ru.oritas.repricer.access;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;

/** Owns membership transitions; the organization row orders every grant and revocation. */
@Service
public final class AccessService {
  private static final Set<String> ORGANIZATION_PERMISSIONS =
      Set.of("organization.policy.read", "organization.policy.manage");
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final IdempotencyService idempotency;
  private final AuditService audit;
  private final OutboxService outbox;
  private final IdentityService identities;

  public AccessService(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      IdempotencyService idempotency,
      AuditService audit,
      OutboxService outbox,
      IdentityService identities) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.idempotency = idempotency;
    this.audit = audit;
    this.outbox = outbox;
    this.identities = identities;
  }

  public Organization createOrganization(UUID actor, UUID requestId, String name) {
    String normalized = organizationName(name);
    UUID id = stableId("organization:" + actor + ":" + requestId);
    Scope scope = new Scope(id, null, actor);
    return transactions.runService(
        scope,
        Set.of("access.organization.create"),
        () -> {
          requireIdentity(actor);
          boolean exists =
              jdbc.sql("SELECT EXISTS(SELECT 1 FROM access_organization WHERE id=:id)")
                  .param("id", id)
                  .query(Boolean.class)
                  .single();
          if (exists) {
            authorization.require(scope, "organization.read");
          }
          return idempotency.execute(
              scope,
              "organization.create",
              requestId,
              normalized,
              Organization.class,
              () -> {
                jdbc.sql("INSERT INTO access_organization(id,name) VALUES (:id,:name)")
                    .param("id", id)
                    .param("name", normalized)
                    .update();
                jdbc.sql(
                        """
                        INSERT INTO access_membership(id,organization_id,subject_id,role)
                        VALUES (:id,:org,:subject,'OWNER')
                        """)
                    .param("id", UUID.randomUUID())
                    .param("org", id)
                    .param("subject", actor)
                    .update();
                changed(scope, "organization.created", id, 1);
                return new Organization(id, normalized, 1);
              });
        });
  }

  public Page<Member> members(Scope scope, AccessQuery query) {
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "membership.read");
          AccessSelection selection = memberSelection(scope, query);
          var items =
              jdbc.sql(
                      "SELECT"
                          + " m.id,m.subject_id,u.display_name,u.email,m.role,m.permissions,m.active,m.revision,m.access_generation,m.revoked_revision"
                          + " "
                          + selection.source()
                          + " ORDER BY "
                          + selection.order()
                          + " LIMIT :size OFFSET :offset")
                  .params(selection.parameters())
                  .param("size", query.size())
                  .param("offset", Page.offset(query.page(), query.size()))
                  .query((row, index) -> member(row))
                  .list();
          long total =
              jdbc.sql("SELECT count(*) " + selection.source())
                  .params(selection.parameters())
                  .query(Long.class)
                  .single();
          return new Page<>(items, total, query.page(), query.size());
        });
  }

  public TableExportSource.Projection memberProjection(
      Scope scope, AccessQuery query, List<UUID> ids) {
    authorization.require(scope, "membership.read");
    return accessProjection(memberSelection(scope, query), "m", ids);
  }

  private AccessSelection memberSelection(Scope scope, AccessQuery query) {
    query.requireStatuses(Set.of("ACTIVE", "REVOKED"));
    String source =
        """
        FROM access_membership m JOIN access_user u ON u.id=m.subject_id
        WHERE m.organization_id=:org
          AND (:search='' OR position(lower(:search) in lower(concat_ws(' ',
            u.display_name,u.email,m.role)))>0)
          AND (:status='' OR m.active=(:status='ACTIVE'))
        """;
    Map<String, Object> parameters = new LinkedHashMap<>(query.parameters());
    parameters.put("org", scope.requireOrganization());
    if (scope.accountId() != null) {
      source +=
          """
          AND EXISTS(SELECT 1 FROM access_account_permission p
            WHERE p.organization_id=m.organization_id AND p.subject_id=m.subject_id
              AND p.account_id=:account)
          """;
      parameters.put("account", scope.accountId());
    }
    return new AccessSelection(
        source,
        parameters,
        query.order(
            Map.of(
                "",
                "m.created_at",
                "id",
                "m.id",
                "name",
                "u.display_name",
                "email",
                "u.email",
                "role",
                "m.role",
                "status",
                "m.active"),
            "m.id"),
        "m.active");
  }

  public Organization rename(Scope scope, String name, long expectedRevision, UUID requestId) {
    String normalized = organizationName(name);
    return transactions.run(
        scope,
        () -> {
          requireOrganizationOwner(scope);
          long revision = lockOrganization(scope);
          return idempotency.execute(
              scope,
              "organization.rename",
              requestId,
              new RenameIntent(normalized, expectedRevision),
              Organization.class,
              () -> {
                requireRevision(revision, expectedRevision);
                jdbc.sql(
                        "UPDATE access_organization SET name=:name,revision=revision+1 WHERE"
                            + " id=:id")
                    .param("id", scope.organizationId())
                    .param("name", normalized)
                    .update();
                changed(scope, "organization.renamed", scope.organizationId(), revision + 1);
                return new Organization(scope.organizationId(), normalized, revision + 1);
              });
        });
  }

  public Member updateMembership(
      Scope scope, UUID target, long expectedRevision, Set<String> permissions, UUID requestId) {
    Set<String> grants = Set.copyOf(permissions);
    if (!ORGANIZATION_PERMISSIONS.containsAll(grants)) {
      throw invalid("Недопустимые права компании");
    }
    return transactions.run(
        scope,
        () -> {
          requireOrganizationOwner(scope);
          lockOrganization(scope);
          var intent = new MembershipIntent(target, expectedRevision, grants);
          return idempotency.execute(
              scope,
              "membership.update",
              requestId,
              intent,
              Member.class,
              () -> {
                Member member = member(scope, target);
                requireRevision(member.revision(), expectedRevision);
                if (!member.active()
                    || member.role().equals("OWNER")
                    || target.equals(scope.subjectId())) {
                  throw conflict("Изменение этих прав недопустимо");
                }
                long revision = nextRevision(scope);
                jdbc.sql(
                        """
                        UPDATE access_membership SET permissions=CAST(:permissions AS text[]),
                          revision=revision+1 WHERE organization_id=:org AND subject_id=:target
                        """)
                    .param("permissions", sqlPermissions(grants))
                    .param("org", scope.organizationId())
                    .param("target", target)
                    .update();
                changed(scope, "membership.updated", target, revision);
                return member(scope, target);
              });
        });
  }

  public Member revokeMembership(Scope scope, UUID target, long expectedRevision, UUID requestId) {
    return transactions.run(
        scope,
        () -> {
          requireOrganizationOwner(scope);
          lockOrganization(scope);
          return idempotency.execute(
              scope,
              "membership.revoke",
              requestId,
              new RevisionIntent(target, expectedRevision),
              Member.class,
              () -> {
                Member member = member(scope, target);
                requireRevision(member.revision(), expectedRevision);
                if (member.role().equals("OWNER") || target.equals(scope.subjectId())) {
                  throw conflict("Сначала передайте владение другому участнику");
                }
                if (!member.active()) {
                  return member;
                }
                long revision = nextRevision(scope);
                jdbc.sql(
                        """
                        UPDATE access_membership SET active=false,permissions='{}',revision=revision+1,
                          access_generation=access_generation+1,revoked_revision=:rev,
                          revoked_at=clock_timestamp() WHERE organization_id=:org AND subject_id=:target
                        """)
                    .param("rev", revision)
                    .param("org", scope.organizationId())
                    .param("target", target)
                    .update();
                changed(scope, "membership.revoked", target, revision);
                return member(scope, target);
              });
        });
  }

  public Member reactivateMembership(
      Scope scope, UUID target, long expectedRevision, UUID requestId) {
    return transactions.run(
        scope,
        () -> {
          requireOrganizationOwner(scope);
          lockOrganization(scope);
          return idempotency.execute(
              scope,
              "membership.reactivate",
              requestId,
              new RevisionIntent(target, expectedRevision),
              Member.class,
              () -> {
                Member member = member(scope, target);
                requireRevision(member.revision(), expectedRevision);
                if (member.active()) {
                  throw conflict("Участник уже активен");
                }
                long revision = nextRevision(scope);
                jdbc.sql(
                        """
                        UPDATE access_membership SET active=true,role='MEMBER',permissions='{}',
                          revision=revision+1,access_generation=access_generation+1
                        WHERE organization_id=:org AND subject_id=:target
                        """)
                    .param("org", scope.organizationId())
                    .param("target", target)
                    .update();
                changed(scope, "membership.reactivated", target, revision);
                return member(scope, target);
              });
        });
  }

  public Organization transferOwnership(
      Scope scope, UUID nextOwner, long expectedOrgRevision, UUID requestId) {
    return transactions.run(
        scope,
        () -> {
          requireOrganizationScope(scope);
          long current = lockOrganization(scope);
          // A retry by the former owner can return its committed result, but cannot mutate again.
          return idempotency.execute(
              scope,
              "ownership.transfer",
              requestId,
              new RevisionIntent(nextOwner, expectedOrgRevision),
              Organization.class,
              () -> {
                authorization.require(scope, "ownership.transfer");
                requireRevision(current, expectedOrgRevision);
                Member recipient = member(scope, nextOwner);
                if (!recipient.active() || recipient.role().equals("OWNER")) {
                  throw conflict("Нужен другой действующий участник компании");
                }
                long revision = nextRevision(scope);
                jdbc.sql(
                        """
                        UPDATE access_membership SET role='MEMBER',permissions='{}',revision=revision+1
                        WHERE organization_id=:org AND subject_id=:actor AND role='OWNER' AND active
                        """)
                    .param("org", scope.organizationId())
                    .param("actor", scope.subjectId())
                    .update();
                jdbc.sql(
                        """
                        UPDATE access_membership SET role='OWNER',permissions='{}',revision=revision+1
                        WHERE organization_id=:org AND subject_id=:target AND active
                        """)
                    .param("org", scope.organizationId())
                    .param("target", nextOwner)
                    .update();
                changed(scope, "ownership.transferred", nextOwner, revision);
                return organization(scope);
              });
        });
  }

  public Page<AccountAccess> accountAccess(Scope scope, AccessQuery query) {
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "membership.read");
          AccessSelection selection = accountSelection(scope, query);
          var items =
              jdbc.sql(
                      "SELECT p.id,p.subject_id,u.display_name,u.email,p.role,p.permissions,"
                          + selection.active()
                          + " AS active,p.revision,p.membership_generation,p.revoked_revision "
                          + selection.source()
                          + " ORDER BY "
                          + selection.order()
                          + " LIMIT :size OFFSET :offset")
                  .params(selection.parameters())
                  .param("size", query.size())
                  .param("offset", Page.offset(query.page(), query.size()))
                  .query((row, index) -> assignment(row))
                  .list();
          long total =
              jdbc.sql("SELECT count(*) " + selection.source())
                  .params(selection.parameters())
                  .query(Long.class)
                  .single();
          return new Page<>(items, total, query.page(), query.size());
        });
  }

  public TableExportSource.Projection accountProjection(
      Scope scope, AccessQuery query, List<UUID> ids) {
    authorization.require(scope, "membership.read");
    return accessProjection(accountSelection(scope, query), "p", ids);
  }

  private AccessSelection accountSelection(Scope scope, AccessQuery query) {
    query.requireStatuses(Set.of("ACTIVE", "REVOKED"));
    String active = "(p.active AND m.active AND p.membership_generation=m.access_generation)";
    String source =
        """
        FROM access_account_permission p JOIN access_membership m
          ON m.organization_id=p.organization_id AND m.subject_id=p.subject_id
        JOIN access_user u ON u.id=p.subject_id
        WHERE p.organization_id=:org AND p.account_id=:account
          AND (:search='' OR position(lower(:search) in lower(concat_ws(' ',
            u.display_name,u.email,p.role)))>0)
        """
            + " AND (:status='' OR "
            + active
            + "=(:status='ACTIVE'))";
    Map<String, Object> parameters = new LinkedHashMap<>(query.parameters());
    parameters.put("org", scope.requireOrganization());
    parameters.put("account", scope.requireAccount());
    return new AccessSelection(
        source,
        parameters,
        query.order(
            Map.of(
                "",
                "p.id",
                "id",
                "p.id",
                "name",
                "u.display_name",
                "email",
                "u.email",
                "role",
                "p.role",
                "status",
                active),
            "p.id"),
        active);
  }

  private TableExportSource.Projection accessProjection(
      AccessSelection selection, String alias, List<UUID> ids) {
    Map<String, Object> parameters = new LinkedHashMap<>(selection.parameters());
    String source = selection.source();
    if (!ids.isEmpty()) {
      source += " AND " + alias + ".id IN (:ids)";
      parameters.put("ids", ids);
    }
    return new TableExportSource.Projection(
        "SELECT "
            + alias
            + ".id AS canonical_id,"
            + "jsonb_build_array(u.display_name,u.email,"
            + alias
            + ".role,CASE WHEN "
            + selection.active()
            + " THEN 'ACTIVE' ELSE 'REVOKED' END) AS cells "
            + source
            + " ORDER BY "
            + selection.order(),
        parameters,
        List.of(
            new TableExportSource.Column("name", "Участник", false),
            new TableExportSource.Column("email", "Email", false),
            new TableExportSource.Column("role", "Роль", false),
            new TableExportSource.Column("status", "Состояние", false)));
  }

  private record AccessSelection(
      String source, Map<String, Object> parameters, String order, String active) {}

  public AccountAccess grantAccountAccess(
      Scope scope,
      UUID target,
      Role role,
      Set<String> extras,
      Long expectedRevision,
      UUID requestId) {
    scope.requireAccount();
    if (role == null || role == Role.MEMBER) {
      throw invalid("Нужна кабинетная роль");
    }
    Set<String> grants = Set.copyOf(extras);
    return transactions.run(
        scope,
        () -> {
          lockOrganization(scope);
          authorization.requireDelegation(scope, scope.subjectId(), role.name(), grants);
          return idempotency.execute(
              scope,
              "account-access.grant",
              requestId,
              new GrantIntent(target, role, grants, expectedRevision),
              AccountAccess.class,
              () -> {
                if (target.equals(scope.subjectId())) {
                  throw conflict("Нельзя изменять собственную запись доступа");
                }
                Member member = member(scope, target);
                if (!member.active() || member.role().equals("OWNER")) {
                  throw conflict("Нужен действующий участник без владения компанией");
                }
                var existing = findAssignment(scope, target);
                if (existing.isPresent()) {
                  AccountAccess prior = existing.orElseThrow();
                  requireRevision(prior.revision(), expectedRevision);
                  authorization.requireDelegation(
                      scope, scope.subjectId(), prior.role().name(), prior.permissions());
                  if (!prior.active() && !authorization.isOwner(scope)) {
                    throw denied();
                  }
                } else if (expectedRevision != null) {
                  throw conflict("Назначение уже изменено");
                }
                long revision = nextRevision(scope);
                jdbc.sql(
                        """
                        INSERT INTO access_account_permission(id,organization_id,account_id,subject_id,
                          role,permissions,membership_generation)
                        VALUES (:id,:org,:account,:target,:role,CAST(:permissions AS text[]),:generation)
                        ON CONFLICT(organization_id,account_id,subject_id) DO UPDATE SET
                          role=EXCLUDED.role,permissions=EXCLUDED.permissions,active=true,
                          membership_generation=EXCLUDED.membership_generation,
                          revision=access_account_permission.revision+1
                        """)
                    .param("id", UUID.randomUUID())
                    .param("org", scope.organizationId())
                    .param("account", scope.accountId())
                    .param("target", target)
                    .param("role", role.name())
                    .param("permissions", sqlPermissions(grants))
                    .param("generation", member.accessGeneration())
                    .update();
                changed(scope, "account-access.granted", target, revision);
                return findAssignment(scope, target).orElseThrow();
              });
        });
  }

  public AccountAccess revokeAccountAccess(
      Scope scope, UUID target, long expectedRevision, UUID requestId) {
    scope.requireAccount();
    return transactions.run(
        scope,
        () -> {
          lockOrganization(scope);
          authorization.require(scope, "membership.manage");
          return idempotency.execute(
              scope,
              "account-access.revoke",
              requestId,
              new RevisionIntent(target, expectedRevision),
              AccountAccess.class,
              () -> {
                AccountAccess prior =
                    findAssignment(scope, target).orElseThrow(AccessService::notFound);
                requireRevision(prior.revision(), expectedRevision);
                if (target.equals(scope.subjectId())) {
                  throw conflict("Нельзя изменять собственную запись доступа");
                }
                authorization.requireDelegation(
                    scope, scope.subjectId(), prior.role().name(), prior.permissions());
                if (!prior.active()) {
                  return prior;
                }
                long revision = nextRevision(scope);
                jdbc.sql(
                        """
                        UPDATE access_account_permission SET active=false,permissions='{}',
                          revision=revision+1,revoked_revision=:rev,revoked_at=clock_timestamp()
                        WHERE organization_id=:org AND account_id=:account AND subject_id=:target
                        """)
                    .param("rev", revision)
                    .param("org", scope.organizationId())
                    .param("account", scope.accountId())
                    .param("target", target)
                    .update();
                changed(scope, "account-access.revoked", target, revision);
                return findAssignment(scope, target).orElseThrow();
              });
        });
  }

  public UUID initManagedUsers(String issuer, String adminSub, String testSub) {
    UUID admin = IdentityService.userId(issuer, adminSub);
    UUID test = IdentityService.userId(issuer, testSub);
    confirmManagedUser(issuer, adminSub, "Администратор Repricer", "admin@repricer.local");
    confirmManagedUser(issuer, testSub, "Тестовый пользователь", "test@repricer.local");
    UUID company = stableId("managed-company:" + issuer);
    Scope scope = new Scope(company, null, admin);
    transactions.runService(
        scope,
        Set.of("access.bootstrap"),
        () -> {
          int created =
              jdbc.sql(
                      """
                      INSERT INTO access_organization(id,name) VALUES (:id,'Тестовая компания')
                      ON CONFLICT(id) DO NOTHING
                      """)
                  .param("id", company)
                  .update();
          if (created == 1) {
            for (UUID subject : List.of(admin, test)) {
              jdbc.sql(
                      """
                      INSERT INTO access_membership(id,organization_id,subject_id,role)
                      VALUES (:id,:org,:subject,:role)
                      """)
                  .param("id", UUID.randomUUID())
                  .param("org", company)
                  .param("subject", subject)
                  .param("role", subject.equals(admin) ? "OWNER" : "MEMBER")
                  .update();
            }
            changed(scope, "organization.initialized", company, 1);
          }
          return company;
        });
    return company;
  }

  long lockOrganization(Scope scope) {
    return jdbc.sql("SELECT revision FROM access_organization WHERE id=:org FOR UPDATE")
        .param("org", scope.requireOrganization())
        .query(Long.class)
        .optional()
        .orElseThrow(AccessService::notFound);
  }

  long nextRevision(Scope scope) {
    return jdbc.sql(
            """
            UPDATE access_organization SET revision=revision+1 WHERE id=:org RETURNING revision
            """)
        .param("org", scope.organizationId())
        .query(Long.class)
        .single();
  }

  Member member(Scope scope, UUID subject) {
    return findMember(scope, subject).orElseThrow(AccessService::notFound);
  }

  Optional<Member> findMember(Scope scope, UUID subject) {
    return jdbc.sql(
            """
            SELECT m.id,m.subject_id,u.display_name,u.email,m.role,m.permissions,m.active,
              m.revision,m.access_generation,m.revoked_revision
            FROM access_membership m JOIN access_user u ON u.id=m.subject_id
            WHERE m.organization_id=:org AND m.subject_id=:subject
            """)
        .param("org", scope.organizationId())
        .param("subject", subject)
        .query((row, index) -> member(row))
        .optional();
  }

  Optional<AccountAccess> findAssignment(Scope scope, UUID subject) {
    return jdbc.sql(
            """
            SELECT p.id,p.subject_id,u.display_name,u.email,p.role,p.permissions,
              (p.active AND m.active AND p.membership_generation=m.access_generation) AS active,
              p.revision,p.membership_generation,p.revoked_revision
            FROM access_account_permission p JOIN access_membership m
              ON m.organization_id=p.organization_id AND m.subject_id=p.subject_id
            JOIN access_user u ON u.id=p.subject_id
            WHERE p.organization_id=:org AND p.account_id=:account AND p.subject_id=:subject
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", subject)
        .query((row, index) -> assignment(row))
        .optional();
  }

  void changed(Scope scope, String action, UUID target, long revision) {
    audit.record(scope, action, target, "revision=" + revision);
    outbox.emit(
        scope,
        action + ":" + target + ":" + revision,
        "access.changed",
        new OutboxService.EntityChange("memberships", target, revision));
  }

  private void confirmManagedUser(String issuer, String subject, String name, String email) {
    Scope self = new Scope(null, null, IdentityService.userId(issuer, subject));
    transactions.run(
        self, () -> identities.confirm(self, issuer, subject, name, email, true, Set.of()));
  }

  private void requireIdentity(UUID subject) {
    if (!jdbc.sql("SELECT EXISTS(SELECT 1 FROM access_user WHERE id=:id)")
        .param("id", subject)
        .query(Boolean.class)
        .single()) {
      throw denied();
    }
  }

  private Organization organization(Scope scope) {
    return jdbc.sql("SELECT id,name,revision FROM access_organization WHERE id=:org")
        .param("org", scope.organizationId())
        .query(
            (row, index) ->
                new Organization(
                    row.getObject("id", UUID.class),
                    row.getString("name"),
                    row.getLong("revision")))
        .single();
  }

  private void requireOrganizationOwner(Scope scope) {
    requireOrganizationScope(scope);
    authorization.require(scope, "organization.manage");
  }

  private static void requireOrganizationScope(Scope scope) {
    scope.requireOrganization();
    if (scope.accountId() != null) {
      throw invalid("Для действия нужна область компании");
    }
  }

  private static Member member(ResultSet row) throws SQLException {
    return new Member(
        row.getObject("id", UUID.class),
        row.getObject("subject_id", UUID.class),
        row.getString("display_name"),
        row.getString("email"),
        row.getString("role"),
        Set.of((String[]) row.getArray("permissions").getArray()),
        row.getBoolean("active"),
        row.getLong("revision"),
        row.getLong("access_generation"),
        row.getObject("revoked_revision", Long.class));
  }

  private static AccountAccess assignment(ResultSet row) throws SQLException {
    return new AccountAccess(
        row.getObject("id", UUID.class),
        row.getObject("subject_id", UUID.class),
        row.getString("display_name"),
        row.getString("email"),
        Role.valueOf(row.getString("role")),
        Set.of((String[]) row.getArray("permissions").getArray()),
        row.getBoolean("active"),
        row.getLong("revision"),
        row.getLong("membership_generation"),
        row.getObject("revoked_revision", Long.class));
  }

  static String sqlPermissions(Set<String> permissions) {
    if (permissions.stream().anyMatch(item -> !item.matches("[a-z]+\\.[a-z]+(?:\\.[a-z]+)?"))) {
      throw invalid("Недопустимое разрешение");
    }
    return "{" + String.join(",", permissions.stream().sorted().toList()) + "}";
  }

  static void requireRevision(long actual, Long expected) {
    if (expected == null || actual != expected) {
      throw conflict("Данные изменены другим пользователем. Обновите запись");
    }
  }

  static UUID stableId(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  static BusinessException invalid(String message) {
    return new BusinessException("INVALID_ACCESS_CHANGE", 422, message);
  }

  static BusinessException conflict(String message) {
    return new BusinessException("ACCESS_CONFLICT", 409, message);
  }

  static BusinessException denied() {
    return new BusinessException("ACCESS_DENIED", 403, "Недостаточно прав для этой операции");
  }

  static BusinessException notFound() {
    return new BusinessException("ACCESS_NOT_FOUND", 404, "Запись доступа не найдена");
  }

  private static String organizationName(String name) {
    if (name == null || name.isBlank() || name.strip().length() > 160) {
      throw invalid("Название компании должно содержать от 1 до 160 символов");
    }
    return name.strip();
  }

  public enum Role {
    MEMBER,
    ADMIN,
    MANAGER,
    OPERATOR,
    VIEWER
  }

  public record Organization(UUID id, String name, long revision) {}

  public record Member(
      UUID id,
      UUID userId,
      String displayName,
      String email,
      String role,
      Set<String> permissions,
      boolean active,
      long revision,
      long accessGeneration,
      Long revokedRevision) {
    public Member {
      permissions = Set.copyOf(permissions);
    }
  }

  public record AccountAccess(
      UUID id,
      UUID userId,
      String displayName,
      String email,
      Role role,
      Set<String> permissions,
      boolean active,
      long revision,
      long membershipGeneration,
      Long revokedRevision) {
    public AccountAccess {
      permissions = Set.copyOf(permissions);
    }
  }

  private record RevisionIntent(UUID target, long revision) {}

  private record RenameIntent(String name, long revision) {}

  private record MembershipIntent(UUID target, long revision, Set<String> permissions) {}

  private record GrantIntent(UUID target, Role role, Set<String> permissions, Long revision) {}
}
