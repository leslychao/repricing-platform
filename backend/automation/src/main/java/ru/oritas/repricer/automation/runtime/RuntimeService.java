package ru.oritas.repricer.automation.runtime;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.automation.runtime.RuntimeRules.StockState;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** Owns stops, stock episodes and cumulative command limits independently of policy editions. */
@Service
public class RuntimeService {
  public record PauseState(UUID offerId, boolean paused, long revision) {}

  public record TargetState(
      UUID offerId,
      UUID targetId,
      StockState stockState,
      BigDecimal episodeBasePrice,
      boolean paused,
      BigDecimal movementUsed,
      long commandsUsed,
      int consecutiveDecreases,
      Instant lastAdmission,
      long revision) {}

  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final OutboxService outbox;
  private final Clock clock;
  private final RuntimeRules rules = new RuntimeRules();

  public RuntimeService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      AuditService audit,
      OutboxService outbox,
      Clock clock) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.audit = audit;
    this.outbox = outbox;
    this.clock = clock;
  }

  public PauseState pauseState(Scope scope, UUID offerId) {
    authorization.require(scope, "policy.read");
    return readPause(scope, offerId, false);
  }

  @Transactional
  public PauseState changePause(Scope scope, UUID offerId, boolean paused, long expectedRevision) {
    authorization.requireLocked(scope, Set.of(paused ? "automation.pause" : "automation.resume"));
    // The account row also serializes first creation of a previously absent stop.
    lockAccount(scope, true);
    PauseState current = readPause(scope, offerId, true);
    if (current.revision() != expectedRevision) {
      throw new BusinessException("STALE_REVISION", 409, "Состояние автоматики изменено");
    }
    if (current.paused() == paused && current.revision() != 0) {
      return current;
    }
    UUID key = offerId == null ? scope.accountId() : offerId;
    jdbc.sql(
            """
            INSERT INTO automation_stop(organization_id,account_id,scope_id,offer_id,paused,revision)
            VALUES (:org,:account,:key,:offer,:paused,1)
            ON CONFLICT(organization_id,account_id,scope_id) DO UPDATE
              SET paused=excluded.paused,revision=automation_stop.revision+1
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("key", key)
        .param("offer", offerId)
        .param("paused", paused)
        .update();
    changed(
        scope, key, offerId, current.revision() + 1, paused ? "AUTOMATION_PAUSED" : "AUTOMATION_RESUMED");
    return new PauseState(offerId, paused, current.revision() + 1);
  }

  @Transactional
  public TargetState observeStock(
      Scope scope,
      UUID offerId,
      UUID targetId,
      BigDecimal available,
      boolean complete,
      BigDecimal currentPrice,
      PolicySettings settings) {
    ensureTarget(scope, offerId, targetId);
    TargetState current = target(scope, offerId, targetId, settings.stability(), true);
    StockState next =
        rules.stockState(current.stockState(), available, complete, settings.stockProtection());
    BigDecimal episodeBase = current.episodeBasePrice();
    if (next == StockState.NORMAL) {
      episodeBase = null;
    } else if (next == StockState.LOW_STOCK && episodeBase == null && currentPrice != null) {
      episodeBase = currentPrice;
    }
    if (next != current.stockState()
        || !java.util.Objects.equals(episodeBase, current.episodeBasePrice())) {
      jdbc.sql(
              """
              UPDATE automation_runtime SET stock_state=:stock,episode_base_price=:base,
                revision=revision+1 WHERE organization_id=:org AND account_id=:account
                  AND offer_id=:offer AND target_id=:target
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("offer", offerId)
          .param("target", targetId)
          .param("stock", next.name())
          .param("base", episodeBase)
          .update();
    }
    return target(scope, offerId, targetId, settings.stability(), false);
  }

  /** Holds the stop rows and target counters until the SEND admission transaction commits. */
  public Instant nextAdmissionAt(
      Scope scope, UUID offerId, UUID targetId, PolicySettings settings) {
    ensureTarget(scope, offerId, targetId);
    TargetState current = target(scope, offerId, targetId, settings.stability(), true);
    return current.lastAdmission() == null
        ? clock.instant()
        : current.lastAdmission().plusSeconds(settings.stability().pauseSeconds());
  }

  /** Holds the stop rows and target counters until the SEND admission transaction commits. */
  @Transactional
  public void admit(
      Scope scope,
      UUID commandId,
      UUID offerId,
      UUID targetId,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      PolicySettings settings) {
    admit(scope, commandId, offerId, targetId, oldPrice, newPrice, settings, false);
  }

  public void admit(
      Scope scope,
      UUID commandId,
      UUID offerId,
      UUID targetId,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      PolicySettings settings,
      boolean protectiveCorrection) {
    if (protectiveCorrection && !settings.allowProtectiveCorrection()) {
      throw new IllegalArgumentException(
          "Protective correction must be enabled by the captured policy");
    }
    lockAccount(scope, false);
    if (readPause(scope, null, true).paused() || readPause(scope, offerId, true).paused()) {
      throw new BusinessException("AUTOMATION_PAUSED", 409, "Автоматика приостановлена");
    }
    ensureTarget(scope, offerId, targetId);
    TargetState state = target(scope, offerId, targetId, settings.stability(), true);
    if (jdbc.sql(
            """
            SELECT EXISTS(SELECT 1 FROM automation_movement WHERE organization_id=:org
              AND account_id=:account AND command_id=:command AND target_id=:target)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("command", commandId)
        .param("target", targetId)
        .query(Boolean.class)
        .single()) {
      return;
    }
    var stability = settings.stability();
    BigDecimal movement = newPrice.subtract(oldPrice).abs();
    boolean decrease = newPrice.compareTo(oldPrice) < 0;
    boolean stockAllows =
        state.stockState() == StockState.NORMAL
            || (state.stockState() == StockState.LOW_STOCK && !decrease);
    Instant now = clock.instant();
    if (!stockAllows
        || !rules.withinSchedule(settings.schedule(), now)
        || state.commandsUsed() >= stability.maximumCommands()
        || state.movementUsed().add(movement).compareTo(stability.movementBudget()) > 0
        || movement.compareTo(stability.maximumStep()) > 0
        || (!protectiveCorrection
            && state.lastAdmission() != null
            && now.isBefore(state.lastAdmission().plusSeconds(stability.pauseSeconds())))
        || (decrease
            && stability.maximumConsecutiveDecreases() != null
            && state.consecutiveDecreases() >= stability.maximumConsecutiveDecreases())) {
      throw new BusinessException(
          "RUNTIME_GUARD",
          409,
          "Остаток, расписание или накопленные ограничения не допускают отправку");
    }
    jdbc.sql(
            """
            INSERT INTO automation_movement(organization_id,account_id,command_id,offer_id,target_id,
              old_price,new_price,movement,admitted_at) VALUES
              (:org,:account,:command,:offer,:target,:old,:new,:movement,:now)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("command", commandId)
        .param("offer", offerId)
        .param("target", targetId)
        .param("old", oldPrice)
        .param("new", newPrice)
        .param("movement", movement)
        .param("now", Timestamp.from(now))
        .update();
    jdbc.sql(
            """
            UPDATE automation_runtime SET last_admission=:now,
              consecutive_decreases=CASE WHEN :decrease THEN consecutive_decreases+1 ELSE 0 END,
              revision=revision+1 WHERE organization_id=:org AND account_id=:account
                AND offer_id=:offer AND target_id=:target
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("offer", offerId)
        .param("target", targetId)
        .param("decrease", decrease)
        .param("now", Timestamp.from(now))
        .update();
  }

  public TargetState get(
      Scope scope, UUID offerId, UUID targetId, PolicySettings.Stability stability) {
    authorization.require(scope, "policy.read");
    return target(scope, offerId, targetId, stability, false);
  }

  private TargetState target(
      Scope scope, UUID offerId, UUID targetId, PolicySettings.Stability stability, boolean lock) {
    return jdbc.sql(
            """
            SELECT r.*,EXISTS(SELECT 1 FROM automation_stop s
              WHERE s.organization_id=r.organization_id AND s.account_id=r.account_id
                AND s.scope_id IN (r.offer_id,r.account_id) AND s.paused) paused,
              COALESCE((SELECT sum(movement) FROM automation_movement m
                WHERE m.organization_id=r.organization_id AND m.account_id=r.account_id
                  AND m.offer_id=r.offer_id AND m.target_id=r.target_id AND m.admitted_at>=:since),0) used,
              (SELECT count(*) FROM automation_movement m
                WHERE m.organization_id=r.organization_id AND m.account_id=r.account_id
                  AND m.offer_id=r.offer_id AND m.target_id=r.target_id AND m.admitted_at>=:since) commands
            FROM automation_runtime r WHERE organization_id=:org AND account_id=:account
              AND offer_id=:offer AND target_id=:target
            """
                + (lock ? " FOR UPDATE OF r" : ""))
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("offer", offerId)
        .param("target", targetId)
        .param(
            "since",
            Timestamp.from(clock.instant().minusSeconds(stability.movementWindowSeconds())))
        .query(
            (rs, row) ->
                new TargetState(
                    offerId,
                    targetId,
                    StockState.valueOf(rs.getString("stock_state")),
                    rs.getBigDecimal("episode_base_price"),
                    rs.getBoolean("paused"),
                    rs.getBigDecimal("used"),
                    rs.getLong("commands"),
                    rs.getInt("consecutive_decreases"),
                    rs.getTimestamp("last_admission") == null
                        ? null
                        : rs.getTimestamp("last_admission").toInstant(),
                    rs.getLong("revision")))
        .optional()
        .orElseGet(
            () ->
                new TargetState(
                    offerId,
                    targetId,
                    StockState.STOCK_UNKNOWN,
                    null,
                    readPause(scope, null, false).paused()
                        || readPause(scope, offerId, false).paused(),
                    BigDecimal.ZERO,
                    0,
                    0,
                    null,
                    0));
  }

  private void ensureTarget(Scope scope, UUID offerId, UUID targetId) {
    jdbc.sql(
            """
            INSERT INTO automation_runtime(organization_id,account_id,offer_id,target_id,stock_state,
              consecutive_decreases,revision) VALUES (:org,:account,:offer,:target,'STOCK_UNKNOWN',0,1)
            ON CONFLICT DO NOTHING
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("offer", offerId)
        .param("target", targetId)
        .update();
  }

  private PauseState readPause(Scope scope, UUID offerId, boolean lock) {
    return jdbc.sql(
            """
            SELECT paused,revision FROM automation_stop WHERE organization_id=:org
              AND account_id=:account AND scope_id=:key
            """
                + (lock ? " FOR UPDATE" : ""))
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("key", offerId == null ? scope.accountId() : offerId)
        .query((rs, row) -> new PauseState(offerId, rs.getBoolean(1), rs.getLong(2)))
        .optional()
        .orElse(new PauseState(offerId, false, 0));
  }

  private void lockAccount(Scope scope, boolean changingPause) {
    jdbc.sql(
            """
            SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account
            """
                + (changingPause ? " FOR UPDATE" : " FOR SHARE"))
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
  }

  private void changed(Scope scope, UUID id, UUID offerId, long revision, String action) {
    audit.recordForOffer(scope, action, id, offerId, "revision=" + revision);
    outbox.emit(
        scope,
        action + ":" + id + ":" + revision,
        action,
        new OutboxService.EntityChange("runtime", id, revision));
  }
}
