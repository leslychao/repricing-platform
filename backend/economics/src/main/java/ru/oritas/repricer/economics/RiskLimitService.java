package ru.oritas.repricer.economics;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.marketplace.CommercialGateway.Operation;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** Reserves the full potential loss; timeouts and pauses never free liability. */
@Service
public class RiskLimitService {
  public record Permission(
      UUID id,
      UUID runId,
      long revision,
      Instant startsAt,
      Instant endsAt,
      BigDecimal keptProfitFloor,
      BigDecimal lossBudget,
      BigDecimal boundedQuantity,
      String externalBoundaryEvidence,
      String scopeDigest,
      String status,
      Set<Operation> allowedOperations) {
    public Permission {
      allowedOperations = Set.copyOf(allowedOperations);
    }
  }

  public record Usage(
      BigDecimal recognizedLoss,
      BigDecimal potentialLoss,
      BigDecimal occupied,
      BigDecimal remaining) {}

  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final Clock clock;
  private final JsonCodec json;
  private final AuditService audit;
  private final OutboxService outbox;
  private final EconomicCalculator calculator = new EconomicCalculator();
  private static final Set<String> GRANT_PERMISSIONS =
      Set.of("automation.manage", "finance.write", "resource.manage");

  public RiskLimitService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      Clock clock,
      JsonCodec json,
      AuditService audit,
      OutboxService outbox) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.clock = clock;
    this.json = json;
    this.audit = audit;
    this.outbox = outbox;
  }

  @Transactional
  public Permission grant(Scope scope, Permission requested) {
    var authority = authorization.requireActorLocked(scope, scope.subjectId(), GRANT_PERMISSIONS);
    if (requested.id() == null
        || requested.runId() == null
        || requested.revision() != 1
        || requested.startsAt() == null
        || requested.endsAt() == null
        || !requested.endsAt().isAfter(requested.startsAt())
        || requested.keptProfitFloor() == null
        || requested.scopeDigest() == null
        || requested.scopeDigest().isBlank()
        || !"ACTIVE".equals(requested.status())
        || requested.allowedOperations().isEmpty()) {
      throw new IllegalArgumentException("A permission needs a precise episode, scope and period");
    }
    if (requested.keptProfitFloor().signum() < 0
        && (requested.lossBudget() == null
            || requested.lossBudget().signum() <= 0
            || requested.boundedQuantity() == null
            || requested.boundedQuantity().signum() <= 0
            || requested.externalBoundaryEvidence() == null
            || requested.externalBoundaryEvidence().isBlank())) {
      throw new BusinessException(
          "EXTERNAL_LOSS_BOUND_REQUIRED",
          422,
          "Площадка должна подтверждать конечную ответственность по всем исходам");
    }
    exactMoney(requested.keptProfitFloor());
    if (requested.lossBudget() != null) {
      exactMoney(requested.lossBudget());
      if (requested.lossBudget().signum() <= 0) {
        throw new IllegalArgumentException("A loss budget must be positive");
      }
    }
    jdbc.sql(
            """
            INSERT INTO economics_risk_permission(organization_id,account_id,id,run_id,revision,
              starts_at,ends_at,kept_profit_floor,loss_budget,bounded_quantity,
              external_boundary_evidence,scope_digest,status,author_id,allowed_operations,
              issuer_membership_revision,issuer_account_revision)
            VALUES (:org,:account,:id,:run,:revision,:start,:end,:floor,:budget,:quantity,
              :evidence,:digest,'ACTIVE',:author,CAST(:operations AS jsonb),:membership,:accountGrant)
            """)
        .param("org", scope.requireOrganization())
        .param("account", scope.requireAccount())
        .param("id", requested.id())
        .param("run", requested.runId())
        .param("revision", requested.revision())
        .param("start", Timestamp.from(requested.startsAt()))
        .param("end", Timestamp.from(requested.endsAt()))
        .param("floor", requested.keptProfitFloor())
        .param("budget", requested.lossBudget())
        .param("quantity", requested.boundedQuantity())
        .param("evidence", requested.externalBoundaryEvidence())
        .param("digest", requested.scopeDigest())
        .param("author", scope.subjectId())
        .param("operations", json.encode(new Operations(requested.allowedOperations())))
        .param("membership", authority.membershipRevision())
        .param("accountGrant", authority.accountRevision())
        .update();
    changed(scope, requested.id(), 1, "ECONOMIC_PERMISSION_GRANTED");
    return requested;
  }

  @Transactional
  public void reserve(
      Scope scope,
      UUID permissionId,
      UUID obligationId,
      BigDecimal maximumLoss,
      String scopeDigest,
      boolean externalBoundVerified,
      UUID runId,
      Operation operation) {
    requireIssuer(scope, permissionId);
    Permission permission = lock(scope, permissionId);
    Instant now = clock.instant();
    if (!permission.status().equals("ACTIVE")
        || now.isBefore(permission.startsAt())
        || !now.isBefore(permission.endsAt())
        || !permission.scopeDigest().equals(scopeDigest)
        || !permission.runId().equals(runId)
        || !permission.allowedOperations().contains(operation)
        || !externalBoundVerified
        || maximumLoss == null
        || maximumLoss.signum() < 0) {
      throw new BusinessException(
          "RISK_PERMISSION_INVALID",
          409,
          "Разрешение не покрывает область, срок или внешнюю ответственность");
    }
    exactMoney(maximumLoss);
    var existing =
        jdbc.sql(
                """
                SELECT admitted_maximum FROM economics_risk_usage
                WHERE organization_id=:org AND account_id=:account AND permission_id=:permission
                  AND obligation_id=:obligation
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("permission", permissionId)
            .param("obligation", obligationId)
            .query(BigDecimal.class)
            .optional();
    BigDecimal admitted = existing.orElse(BigDecimal.ZERO);
    if (admitted.compareTo(maximumLoss) >= 0) {
      return;
    }
    BigDecimal additional = maximumLoss.subtract(admitted);
    Usage usage = usage(scope, permissionId, permission.lossBudget());
    if (permission.lossBudget() == null
        || usage.occupied().add(additional).compareTo(permission.lossBudget()) > 0) {
      throw new BusinessException("LOSS_BUDGET_EXCEEDED", 409, "Лимит прямых потерь занят");
    }
    long revision =
        jdbc.sql(
                """
                INSERT INTO economics_risk_usage(organization_id,account_id,permission_id,obligation_id,
                  recognized_loss,potential_loss,revision,admitted_maximum)
                VALUES (:org,:account,:permission,:obligation,0,:loss,1,:maximum)
                ON CONFLICT(organization_id,account_id,permission_id,obligation_id)
                  DO UPDATE SET potential_loss=economics_risk_usage.potential_loss+:additional,
                    admitted_maximum=:maximum,revision=economics_risk_usage.revision+1
                RETURNING revision
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("permission", permissionId)
            .param("obligation", obligationId)
            .param("loss", additional)
            .param("maximum", maximumLoss)
            .param("additional", additional)
            .query(Long.class)
            .single();
    riskChanged(scope, permissionId, obligationId, revision, "ECONOMIC_RISK_RESERVED");
  }

  @Transactional
  public void reconcile(
      Scope scope,
      UUID permissionId,
      UUID obligationId,
      BigDecimal recognizedLoss,
      BigDecimal remainingMaximum,
      long expectedRevision) {
    lock(scope, permissionId);
    calculator.liability(recognizedLoss, remainingMaximum);
    int changed =
        jdbc.sql(
                """
                UPDATE economics_risk_usage SET recognized_loss=:fact,potential_loss=:remaining,
                  revision=revision+1 WHERE organization_id=:org AND account_id=:account
                  AND permission_id=:permission AND obligation_id=:obligation AND revision=:revision
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("permission", permissionId)
            .param("obligation", obligationId)
            .param("fact", recognizedLoss)
            .param("remaining", remainingMaximum)
            .param("revision", expectedRevision)
            .update();
    if (changed != 1) {
      throw new BusinessException("STALE_REVISION", 409, "Ответственность уже изменена");
    }
    riskChanged(
        scope, permissionId, obligationId, expectedRevision + 1, "ECONOMIC_RISK_RECONCILED");
  }

  /**
   * A command reuses an existing portion; its proven non-send may release only its own exposure.
   */
  @Transactional
  public void reserveForCommand(
      Scope scope,
      UUID permissionId,
      UUID obligationId,
      UUID commandId,
      BigDecimal maximumLoss,
      String scopeDigest,
      UUID runId,
      Operation operation) {
    reserve(scope, permissionId, obligationId, maximumLoss, scopeDigest, true, runId, operation);
    record Claim(UUID permissionId, UUID obligationId, BigDecimal maximum, String state) {}
    var previous =
        jdbc.sql(
                """
                SELECT permission_id,obligation_id,maximum_loss,state FROM economics_risk_command WHERE organization_id=:org
                  AND account_id=:account AND command_id=:command
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("command", commandId)
            .query(
                (rs, row) ->
                    new Claim(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getBigDecimal(3),
                        rs.getString(4)))
            .optional();
    if (previous.isPresent()) {
      var claim = previous.orElseThrow();
      if (!claim.permissionId().equals(permissionId)
          || !claim.obligationId().equals(obligationId)
          || claim.maximum().compareTo(maximumLoss) != 0) {
        throw new BusinessException(
            "RISK_COMMAND_CONFLICT", 409, "Основание ответственности команды изменилось");
      }
      if (claim.state().equals("ABSENT")) {
        throw new BusinessException(
            "RISK_COMMAND_CLOSED", 409, "Команда с доказанным отсутствием эффекта уже закрыта");
      }
      return;
    }
    jdbc.sql(
            """
            INSERT INTO economics_risk_command(organization_id,account_id,command_id,permission_id,
              obligation_id,maximum_loss,state) VALUES(:org,:account,:command,:permission,:obligation,:maximum,'POSSIBLE')
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("command", commandId)
        .param("permission", permissionId)
        .param("obligation", obligationId)
        .param("maximum", maximumLoss)
        .update();
  }

  /** Only transport non-start or full definitive rejection proves this command has no exposure. */
  @Transactional
  public void releaseAbsentEffect(Scope scope, UUID commandId) {
    record Claim(UUID permissionId, UUID obligationId) {}
    var optional =
        jdbc.sql(
                """
                SELECT permission_id,obligation_id FROM economics_risk_command WHERE organization_id=:org
                  AND account_id=:account AND command_id=:command
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("command", commandId)
            .query((rs, row) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
            .optional();
    if (optional.isEmpty()) {
      return;
    }
    var claim = optional.orElseThrow();
    lock(scope, claim.permissionId());
    jdbc.sql(
            """
            UPDATE economics_risk_command SET state='ABSENT' WHERE organization_id=:org
              AND account_id=:account AND command_id=:command AND state='POSSIBLE'
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("command", commandId)
        .update();
    var revision =
        jdbc.sql(
                """
                UPDATE economics_risk_usage u SET potential_loss=0,admitted_maximum=0,revision=revision+1
                WHERE u.organization_id=:org AND u.account_id=:account AND u.permission_id=:permission
                  AND u.obligation_id=:obligation AND u.recognized_loss=0 AND u.potential_loss>0
                  AND NOT EXISTS(SELECT 1 FROM economics_risk_command c WHERE c.organization_id=u.organization_id
                    AND c.account_id=u.account_id AND c.permission_id=u.permission_id
                    AND c.obligation_id=u.obligation_id AND c.state='POSSIBLE') RETURNING revision
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("permission", claim.permissionId())
            .param("obligation", claim.obligationId())
            .query(Long.class)
            .optional();
    revision.ifPresent(
        value ->
            riskChanged(
                scope,
                claim.permissionId(),
                claim.obligationId(),
                value,
                "ECONOMIC_UNSENT_RISK_RELEASED"));
  }

  @Transactional
  public void revoke(Scope scope, UUID permissionId, long expectedRevision) {
    authorization.require(scope, "automation.manage");
    authorization.require(scope, "finance.write");
    authorization.require(scope, "resource.manage");
    Permission permission = lock(scope, permissionId);
    if (permission.revision() != expectedRevision) {
      throw new BusinessException("STALE_REVISION", 409, "Экономическое разрешение изменено");
    }
    if (permission.status().equals("REVOKED")) {
      return;
    }
    jdbc.sql(
            """
            UPDATE economics_risk_permission SET status='REVOKED',revision=revision+1
            WHERE organization_id=:org AND account_id=:account AND id=:id AND status='ACTIVE'
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", permissionId)
        .update();
    changed(scope, permissionId, permission.revision() + 1, "ECONOMIC_PERMISSION_REVOKED");
  }

  public Optional<Permission> forRun(Scope scope, UUID runId) {
    return jdbc.sql(
            """
            SELECT * FROM economics_risk_permission WHERE organization_id=:org AND account_id=:account
              AND run_id=:run ORDER BY created_at DESC,id DESC LIMIT 1
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("run", runId)
        .query(this::map)
        .optional();
  }

  public Permission requireCurrent(
      Scope scope, UUID permissionId, UUID runId, String scopeDigest, Operation operation) {
    requireIssuer(scope, permissionId);
    Permission permission = lock(scope, permissionId);
    Instant now = clock.instant();
    if (!permission.status().equals("ACTIVE")
        || !permission.runId().equals(runId)
        || !permission.scopeDigest().equals(scopeDigest)
        || now.isBefore(permission.startsAt())
        || !now.isBefore(permission.endsAt())
        || !permission.allowedOperations().contains(operation)) {
      throw new BusinessException(
          "RISK_PERMISSION_INVALID", 409, "Экономическое разрешение не действует");
    }
    return permission;
  }

  public Usage getUsage(Scope scope, UUID permissionId) {
    authorization.require(scope, "finance.read");
    BigDecimal budget =
        jdbc.sql(
                """
                SELECT loss_budget FROM economics_risk_permission
                WHERE organization_id=:org AND account_id=:account AND id=:id
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", permissionId)
            .query(BigDecimal.class)
            .single();
    return usage(scope, permissionId, budget);
  }

  public boolean recognizedLimitReached(Scope scope, Permission permission) {
    return permission.lossBudget() != null
        && usage(scope, permission.id(), permission.lossBudget())
                .recognizedLoss()
                .compareTo(permission.lossBudget())
            >= 0;
  }

  private Usage usage(Scope scope, UUID permissionId, BigDecimal budget) {
    return jdbc.sql(
            """
            SELECT coalesce(sum(recognized_loss),0),coalesce(sum(potential_loss),0)
            FROM economics_risk_usage WHERE organization_id=:org AND account_id=:account
              AND permission_id=:permission
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("permission", permissionId)
        .query(
            (rs, row) -> {
              BigDecimal occupied = calculator.liability(rs.getBigDecimal(1), rs.getBigDecimal(2));
              return new Usage(
                  rs.getBigDecimal(1),
                  rs.getBigDecimal(2),
                  occupied,
                  budget == null ? null : budget.subtract(occupied));
            })
        .single();
  }

  private Permission lock(Scope scope, UUID id) {
    return jdbc.sql(
            """
            SELECT id,run_id,revision,starts_at,ends_at,kept_profit_floor,loss_budget,
              bounded_quantity,external_boundary_evidence,scope_digest,status,allowed_operations
            FROM economics_risk_permission WHERE organization_id=:org AND account_id=:account
              AND id=:id FOR UPDATE
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", id)
        .query(this::map)
        .optional()
        .orElseThrow(
            () ->
                new BusinessException(
                    "PERMISSION_NOT_FOUND", 404, "Экономическое разрешение недоступно"));
  }

  private Permission map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Permission(
        rs.getObject("id", UUID.class),
        rs.getObject("run_id", UUID.class),
        rs.getLong("revision"),
        rs.getTimestamp("starts_at").toInstant(),
        rs.getTimestamp("ends_at").toInstant(),
        rs.getBigDecimal("kept_profit_floor"),
        rs.getBigDecimal("loss_budget"),
        rs.getBigDecimal("bounded_quantity"),
        rs.getString("external_boundary_evidence"),
        rs.getString("scope_digest"),
        rs.getString("status"),
        json.decode(rs.getString("allowed_operations"), Operations.class).values());
  }

  private record Operations(Set<Operation> values) {}

  private void requireIssuer(Scope scope, UUID permissionId) {
    record Issuer(UUID id, long membership, long account) {}
    var issuer =
        jdbc.sql(
                """
                SELECT author_id,issuer_membership_revision,issuer_account_revision
                FROM economics_risk_permission WHERE organization_id=:org AND account_id=:account AND id=:id
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("id", permissionId)
            .query(
                (rs, row) -> new Issuer(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)))
            .single();
    var current = authorization.requireActorLocked(scope, issuer.id(), GRANT_PERMISSIONS);
    if (current.membershipRevision() != issuer.membership()
        || current.accountRevision() != issuer.account()) {
      throw new BusinessException(
          "ECONOMIC_AUTHORITY_STALE",
          409,
          "Полномочия выдавшего экономическое разрешение изменились");
    }
  }

  private static void exactMoney(BigDecimal value) {
    if (value.scale() > 12 || value.precision() - value.scale() > 26) {
      throw new IllegalArgumentException("Money exceeds the supported exact precision");
    }
  }

  private void riskChanged(
      Scope scope, UUID permissionId, UUID obligationId, long revision, String action) {
    audit.record(
        scope, action, permissionId, "obligation=" + obligationId + ";revision=" + revision);
    outbox.emit(
        scope,
        action + ":" + permissionId + ":" + obligationId + ":" + revision,
        action,
        new OutboxService.EntityChange("temporary-economic-permissions", permissionId, revision));
  }

  private void changed(Scope scope, UUID id, long revision, String action) {
    audit.record(scope, action, id, "revision=" + revision);
    outbox.emit(
        scope,
        action + ":" + id + ":" + revision,
        action,
        new OutboxService.EntityChange("temporary-economic-permissions", id, revision));
  }
}
