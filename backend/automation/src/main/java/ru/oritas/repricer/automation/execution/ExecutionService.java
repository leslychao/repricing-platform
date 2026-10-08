package ru.oritas.repricer.automation.execution;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.automation.execution.CommandStateMachine.State;
import ru.oritas.repricer.marketplace.CommercialGateway.Outcome;
import ru.oritas.repricer.marketplace.CommercialGateway.PreparedCommercialCommand;
import ru.oritas.repricer.marketplace.CommercialGateway.Reconciliation;
import ru.oritas.repricer.marketplace.CommercialGateway.SendResult;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.SqlReadProjection;
import ru.oritas.repricer.platform.StoredFileService;

/** Durable command states. Admitted requests may be sent once, never re-admitted by a new job. */
@Service
public class ExecutionService {
  public record Command(
      UUID id,
      UUID decisionId,
      int step,
      State state,
      PreparedCommercialCommand prepared,
      UUID journalFileId,
      Instant checkedAt,
      Instant sendDeadline,
      Instant admittedAt,
      String reason,
      long revision) {}

  public record Admission(boolean allowed, Instant latestStart, String reason) {}

  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final Clock clock;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final OutboxService outbox;
  private final StoredFileService files;
  private final JobRuntime jobs;
  private final CommandStateMachine states = new CommandStateMachine();

  /** One latest command per offer, independent from its current execution result. */
  public SqlReadProjection latestCommandProjection(Scope scope) {
    authorization.require(scope, "command.read");
    return new SqlReadProjection(
        """
        SELECT DISTINCT ON (d.offer_id) d.offer_id,c.id AS command_id,c.state AS command_state,
          c.created_at FROM automation_decision d JOIN automation_command c
          ON (c.organization_id,c.account_id,c.decision_id)=(d.organization_id,d.account_id,d.id)
        WHERE d.organization_id=:commandOrganization AND d.account_id=:commandAccount
        ORDER BY d.offer_id,c.created_at DESC,c.id
        """,
        Map.of(
            "commandOrganization",
            scope.requireOrganization(),
            "commandAccount",
            scope.requireAccount()));
  }

  public ExecutionService(
      JdbcClient jdbc,
      JsonCodec json,
      Clock clock,
      AuthorizationService authorization,
      AuditService audit,
      OutboxService outbox,
      StoredFileService files,
      JobRuntime jobs) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.authorization = authorization;
    this.audit = audit;
    this.outbox = outbox;
    this.files = files;
    this.jobs = jobs;
  }

  @Transactional
  public Command prepare(
      Scope scope,
      UUID decisionId,
      int step,
      PreparedCommercialCommand prepared,
      Instant checkedAt,
      Instant deadline) {
    authorization.require(scope, "decision.approve");
    String decisionState =
        jdbc.sql(
                """
                SELECT state FROM automation_decision WHERE organization_id=:org AND account_id=:account
                  AND id=:id FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", decisionId)
            .query(String.class)
            .optional()
            .orElseThrow(
                () -> new BusinessException("DECISION_NOT_FOUND", 404, "Решение недоступно"));
    if (!List.of("APPROVED", "EXECUTING").contains(decisionState)) {
      throw new BusinessException("DECISION_NOT_APPROVED", 409, "Решение не подтверждено");
    }
    if (step < 0
        || step > 7
        || checkedAt == null
        || deadline == null
        || !deadline.isAfter(checkedAt)
        || !prepared.profileExpiresAt().isAfter(checkedAt)) {
      throw new IllegalArgumentException("Invalid immutable preparation boundary");
    }
    var existing =
        jdbc.sql(
                """
                SELECT * FROM automation_command WHERE organization_id=:org AND account_id=:account
                  AND decision_id=:decision AND step=:step FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("decision", decisionId)
            .param("step", step)
            .query(this::map)
            .optional();
    if (existing.isPresent()) {
      Command command = existing.orElseThrow();
      if (!command.prepared().digest().equals(prepared.digest())) {
        throw new BusinessException("PREPARATION_CONFLICT", 409, "Запрос команды уже закреплён");
      }
      return command;
    }
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO automation_command(organization_id,account_id,id,decision_id,step,state,
              prepared,checked_at,send_deadline,revision)
            VALUES (:org,:account,:id,:decision,:step,'PENDING',CAST(:prepared AS jsonb),
              :checked,:deadline,1)
            """)
        .param("org", scope.requireOrganization())
        .param("account", scope.requireAccount())
        .param("id", id)
        .param("decision", decisionId)
        .param("step", step)
        .param("prepared", json.encode(prepared))
        .param("checked", Timestamp.from(checkedAt))
        .param("deadline", Timestamp.from(deadline))
        .update();
    for (var effect :
        prepared.effects().stream()
            .sorted(java.util.Comparator.comparing(e -> e.targetId() + ":" + e.field()))
            .toList()) {
      jdbc.sql(
              """
              INSERT INTO automation_field_claim(organization_id,account_id,target_id,field,
                command_id) VALUES (:org,:account,:target,:field,:command)
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("target", effect.targetId())
          .param("field", effect.field())
          .param("command", id)
          .update();
    }
    changed(scope, id, 1, "COMMAND_PREPARED");
    return getInternal(scope, id, false);
  }

  @Transactional
  public void attachJournal(Scope scope, UUID commandId, UUID fileId) {
    Command command = getInternal(scope, commandId, true);
    if (command.journalFileId() != null) {
      if (!command.journalFileId().equals(fileId)) {
        throw new BusinessException("JOURNAL_CONFLICT", 409, "Журнал команды уже закреплён");
      }
      return;
    }
    if (command.state() != State.PENDING) {
      throw new BusinessException("COMMAND_ALREADY_SENT", 409, "Команда могла быть отправлена");
    }
    files.retain(fileId, "COMMAND_JOURNAL", commandId, scope);
    jdbc.sql(
            """
            UPDATE automation_command SET journal_file_id=:file,revision=revision+1
            WHERE organization_id=:org AND account_id=:account AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", commandId)
        .param("file", fileId)
        .update();
  }

  /** The caller must pass fresh canonical admission facts, never values from an HTTP body. */
  @Transactional
  public Admission admit(
      Scope scope,
      UUID commandId,
      long currentSourceRevision,
      long currentProfileRevision,
      boolean allCurrentGuardsAllow,
      boolean quotaReserved,
      Instant inputsValidUntil,
      int maximumAgeSeconds) {
    authorization.require(scope, "decision.approve");
    Command command = getInternal(scope, commandId, true);
    if (command.state() != State.PENDING) {
      return new Admission(false, null, "SEND_ALREADY_POSSIBLE");
    }
    if (command.journalFileId() == null
        || !allCurrentGuardsAllow
        || !quotaReserved
        || currentSourceRevision != command.prepared().sourceRevision()
        || currentProfileRevision != command.prepared().profileRevision()
        || inputsValidUntil == null
        || maximumAgeSeconds < 1
        || maximumAgeSeconds > 300) {
      return new Admission(false, null, "ADMISSION_GUARD");
    }
    Instant now = clock.instant();
    Instant latest =
        List.of(
                command.sendDeadline(),
                inputsValidUntil,
                command.checkedAt().plusSeconds(maximumAgeSeconds),
                command.prepared().profileExpiresAt(),
                now.plusSeconds(5))
            .stream()
            .min(Instant::compareTo)
            .orElseThrow();
    if (!now.isBefore(latest)) {
      cancel(scope, commandId, "STALE_BEFORE_SEND");
      return new Admission(false, null, "STALE_BEFORE_SEND");
    }
    int changed =
        jdbc.sql(
                """
                UPDATE automation_command SET state='SENT',admitted_at=:now,revision=revision+1
                WHERE organization_id=:org AND account_id=:account AND id=:id AND state='PENDING'
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", commandId)
            .param("now", Timestamp.from(now))
            .update();
    if (changed != 1) {
      return new Admission(false, null, "SEND_ALREADY_POSSIBLE");
    }
    jdbc.sql(
            """
            INSERT INTO automation_send_attempt(organization_id,account_id,command_id,admitted_at,
              latest_start,state) VALUES (:org,:account,:command,:now,:deadline,'ADMITTED')
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("command", commandId)
        .param("now", Timestamp.from(now))
        .param("deadline", Timestamp.from(latest))
        .update();
    jdbc.sql(
            """
            UPDATE automation_decision SET state='EXECUTING',revision=revision+1
            WHERE organization_id=:org AND account_id=:account AND id=:decision
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("decision", command.decisionId())
        .update();
    changed(scope, commandId, command.revision() + 1, "COMMAND_ADMITTED");
    return new Admission(true, latest, "SEND_ONCE");
  }

  @Transactional
  public State reconcile(Scope scope, UUID commandId, Reconciliation evidence) {
    Command command = getInternal(scope, commandId, true);
    State next = states.reconcile(command.state(), command.prepared().effects(), evidence);
    jdbc.sql(
            """
            INSERT INTO automation_readback(organization_id,account_id,id,command_id,evidence)
            VALUES (:org,:account,:id,:command,CAST(:evidence AS jsonb))
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", UUID.randomUUID())
        .param("command", commandId)
        .param("evidence", json.encode(evidence))
        .update();
    for (var effect : evidence.effects()) {
      if (effect.rawFileId() != null) {
        files.retain(effect.rawFileId(), "COMMAND_READBACK", commandId, scope);
      }
    }
    for (var obligation : evidence.obligations()) {
      if (obligation.rawFileId() != null) {
        files.retain(obligation.rawFileId(), "COMMAND_QUANTITY_EVIDENCE", commandId, scope);
      }
    }
    if (next != command.state()) {
      updateState(scope, command, next, next.name());
    }
    if (next == State.UNKNOWN || next == State.SENT) {
      raiseUncertaintyIncident(scope, commandId);
    }
    return next;
  }

  public void raiseUncertaintyIncident(Scope scope, UUID commandId) {
    var revision =
        jdbc.sql(
                """
                UPDATE automation_command SET uncertainty_incident_at=:now,
                  reason='EXTERNAL_EFFECT_UNKNOWN_24H',revision=revision+1
                WHERE organization_id=:org AND account_id=:account AND id=:command
                  AND state IN ('SENT','UNKNOWN') AND uncertainty_incident_at IS NULL
                  AND admitted_at<=:threshold RETURNING revision
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("command", commandId)
            .param("now", Timestamp.from(clock.instant()))
            .param("threshold", Timestamp.from(clock.instant().minusSeconds(86400)))
            .query(Long.class)
            .optional();
    revision.ifPresent(value -> changed(scope, commandId, value, "COMMAND_UNCERTAINTY_INCIDENT"));
  }

  @Transactional
  public void recordSend(Scope scope, UUID commandId, SendResult result) {
    Command command = getInternal(scope, commandId, true);
    if (states.terminal(command.state())) {
      return;
    }
    if (command.state() != State.SENT && command.state() != State.UNKNOWN) {
      throw new IllegalStateException("Transport result without durable admission");
    }
    if (result.rawFileId() != null) {
      files.retain(result.rawFileId(), "COMMAND_RESPONSE", commandId, scope);
    }
    jdbc.sql(
            """
            UPDATE automation_send_attempt SET state=:state,raw_response_file_id=:file
            WHERE organization_id=:org AND account_id=:account AND command_id=:command
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("command", commandId)
        .param("state", result.outcome().name())
        .param("file", result.rawFileId())
        .update();
    if (result.outcome() == Outcome.REJECTED && result.rawFileId() != null) {
      updateState(scope, command, State.FAILED, result.reason());
    } else if (result.outcome() == Outcome.UNKNOWN || result.rawFileId() == null) {
      updateState(scope, command, State.UNKNOWN, result.reason());
    }
  }

  /** Only the worker holding the fresh admission may assert this before calling the transport. */
  @Transactional
  public void notSent(Scope scope, UUID commandId) {
    Command command = getInternal(scope, commandId, true);
    if (command.state() != State.SENT) {
      throw new IllegalStateException("NOT_SENT requires the original unstarted admission");
    }
    jdbc.sql(
            """
            UPDATE automation_send_attempt SET state='NOT_SENT'
            WHERE organization_id=:org AND account_id=:account AND command_id=:command
              AND state='ADMITTED'
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("command", commandId)
        .update();
    updateState(scope, command, State.FAILED, "SEND_DEADLINE_PASSED_WITHOUT_TRANSPORT");
  }

  @Transactional
  public void markUnknown(Scope scope, UUID commandId, String reason) {
    Command command = getInternal(scope, commandId, true);
    if (command.state() == State.SENT) {
      updateState(scope, command, State.UNKNOWN, reason);
    }
  }

  @Transactional
  public void cancel(Scope scope, UUID commandId, String reason) {
    Command command = getInternal(scope, commandId, true);
    if (command.state() != State.PENDING) {
      throw new BusinessException(
          "EXTERNAL_EFFECT_POSSIBLE", 409, "Возможную отправку нельзя отменить как неисполненную");
    }
    updateState(scope, command, State.CANCELLED, reason);
  }

  @Transactional
  public void cancel(Scope scope, UUID commandId, long expectedRevision, String reason) {
    authorization.require(scope, "automation.pause");
    if (getInternal(scope, commandId, true).revision() != expectedRevision) {
      throw new BusinessException("STALE_REVISION", 409, "Команда уже изменена");
    }
    cancel(scope, commandId, reason);
  }

  @Transactional
  public UUID submitReconcile(Scope scope, UUID requestId, UUID commandId, long expectedRevision) {
    authorization.require(scope, "command.reconcile");
    Command command = getInternal(scope, commandId, true);
    if (command.revision() != expectedRevision) {
      throw new BusinessException("STALE_REVISION", 409, "Команда уже изменена");
    }
    if (command.state() == State.PENDING) {
      throw new BusinessException("COMMAND_NOT_SENT", 409, "Команда ещё не отправлялась");
    }
    return jobs.submit(
        scope,
        "AUTOMATION_RECONCILE",
        requestId.toString(),
        json.encode(new ReconcileRequest(commandId)));
  }

  public record ReconcileRequest(UUID commandId) {}

  public Command get(Scope scope, UUID commandId) {
    authorization.require(scope, "command.read");
    return getInternal(scope, commandId, false);
  }

  public List<Command> list(Scope scope, int limit, int offset) {
    authorization.require(scope, "command.read");
    if (limit < 1 || limit > 200 || offset < 0) {
      throw new IllegalArgumentException("Invalid page");
    }
    return jdbc.sql(
            """
            SELECT * FROM automation_command WHERE organization_id=:org AND account_id=:account
            ORDER BY created_at DESC,id LIMIT :limit OFFSET :offset
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("limit", limit)
        .param("offset", offset)
        .query(this::map)
        .list();
  }

  private void updateState(Scope scope, Command command, State state, String reason) {
    jdbc.sql(
            """
              UPDATE automation_command SET state=:state,reason=:reason,revision=revision+1,
                confirmed_at=CASE WHEN :state='APPLIED'
                  THEN COALESCE(confirmed_at,clock_timestamp()) ELSE confirmed_at END
            WHERE organization_id=:org AND account_id=:account AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", command.id())
        .param("state", state.name())
        .param("reason", reason)
        .update();
    if (states.terminal(state)) {
      jdbc.sql(
              """
              DELETE FROM automation_field_claim WHERE organization_id=:org AND account_id=:account
                AND command_id=:command
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("command", command.id())
          .update();
    }
    changed(scope, command.id(), command.revision() + 1, "COMMAND_" + state.name());
  }

  private Command getInternal(Scope scope, UUID id, boolean lock) {
    return jdbc.sql(
            """
            SELECT * FROM automation_command WHERE organization_id=:org AND account_id=:account
              AND id=:id
            """
                + (lock ? " FOR UPDATE" : ""))
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", id)
        .query(this::map)
        .optional()
        .orElseThrow(() -> new BusinessException("COMMAND_NOT_FOUND", 404, "Команда недоступна"));
  }

  private Command map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Command(
        rs.getObject("id", UUID.class),
        rs.getObject("decision_id", UUID.class),
        rs.getInt("step"),
        State.valueOf(rs.getString("state")),
        json.decode(rs.getString("prepared"), PreparedCommercialCommand.class),
        rs.getObject("journal_file_id", UUID.class),
        rs.getTimestamp("checked_at").toInstant(),
        rs.getTimestamp("send_deadline").toInstant(),
        rs.getTimestamp("admitted_at") == null ? null : rs.getTimestamp("admitted_at").toInstant(),
        rs.getString("reason"),
        rs.getLong("revision"));
  }

  private void changed(Scope scope, UUID id, long revision, String action) {
    UUID offerId =
        jdbc.sql(
                """
                SELECT d.offer_id FROM automation_command c JOIN automation_decision d
                  ON (d.organization_id,d.account_id,d.id)=(c.organization_id,c.account_id,c.decision_id)
                WHERE c.organization_id=:org AND c.account_id=:account AND c.id=:id
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", id)
            .query(UUID.class)
            .single();
    audit.recordForOffer(scope, action, id, offerId, "revision=" + revision);
    outbox.emit(
        scope,
        action + ":" + id + ":" + revision,
        action,
        new OutboxService.EntityChange("commands", id, revision));
  }
}
