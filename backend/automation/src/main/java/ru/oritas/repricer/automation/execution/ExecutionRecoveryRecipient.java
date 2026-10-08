package ru.oritas.repricer.automation.execution;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.automation.execution.CommandStateMachine.State;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** Exhausting a job cannot erase an uncertain external effect or cause another SEND. */
@Component
public final class ExecutionRecoveryRecipient implements OutboxRecipient {
  private final JobRuntime jobs;
  private final ExecutionService execution;
  private final DecisionService decisions;
  private final JsonCodec json;
  private final Clock clock;
  private final ResourceService resources;

  public ExecutionRecoveryRecipient(
      JobRuntime jobs,
      ExecutionService execution,
      DecisionService decisions,
      JsonCodec json,
      Clock clock,
      ResourceService resources) {
    this.jobs = jobs;
    this.execution = execution;
    this.decisions = decisions;
    this.json = json;
    this.clock = clock;
    this.resources = resources;
  }

  @Override
  public String name() {
    return "automation.failed-execution-recovery";
  }

  @Override
  public boolean accepts(String eventType) {
    return eventType.equals("operation.changed");
  }

  @Override
  public Set<String> servicePermissions() {
    return Set.of("command.read", "command.reconcile", "platform.job.manage");
  }

  @Override
  public void receive(Scope scope, UUID eventId, String eventType, String payload) {
    if (scope.accountId() == null) {
      return;
    }
    var change = json.decode(payload, OutboxService.EntityChange.class);
    if (!change.resource().equals("operations")) {
      return;
    }
    var failure = jobs.failure(scope, change.entityId());
    if (failure.isEmpty()) {
      return;
    }
    var failed = failure.orElseThrow();
    UUID commandId =
        switch (failed.type()) {
          case "AUTOMATION_EXECUTE" ->
              json.decode(failed.payload(), DecisionService.ExecutionJob.class).commandId();
          case "AUTOMATION_RECONCILE" ->
              json.decode(failed.payload(), ExecutionService.ReconcileRequest.class).commandId();
          default -> null;
        };
    if (commandId == null) {
      return;
    }
    var command = execution.get(scope, commandId);
    if (command.state() == State.PENDING) {
      execution.cancel(scope, commandId, "EXECUTION_JOB_" + failed.state());
      decisions.stopRemainder(scope, command.decisionId(), "EXECUTION_JOB_" + failed.state());
    } else if (command.state() == State.SENT
        || command.state() == State.UNKNOWN
        || (command.prepared().resourceCommitment() != null
            && resources.pending(
                scope, commandId, command.prepared().resourceCommitment().stockPoolId()))) {
      execution.markUnknown(scope, commandId, "EXECUTION_JOB_" + failed.state());
      execution.raiseUncertaintyIncident(scope, commandId);
      var due = failed.updatedAt().plusSeconds(3600);
      jobs.schedule(
          scope,
          "AUTOMATION_RECONCILE",
          "failed:" + change.entityId(),
          json.encode(new ExecutionService.ReconcileRequest(commandId)),
          due.isAfter(clock.instant()) ? due : clock.instant());
    }
  }
}
