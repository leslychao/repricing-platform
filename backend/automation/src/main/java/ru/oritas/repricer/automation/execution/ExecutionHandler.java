package ru.oritas.repricer.automation.execution;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.automation.decision.DecisionAuthorization;
import ru.oritas.repricer.automation.decision.DecisionContinuationService;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.automation.execution.CommandStateMachine.State;
import ru.oritas.repricer.automation.policy.PolicySettings.Operation;
import ru.oritas.repricer.automation.runtime.AutoService;
import ru.oritas.repricer.automation.runtime.RuntimeService;
import ru.oritas.repricer.automation.runtime.ScenarioService;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.economics.RiskLimitService;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.marketplace.CommercialGateway;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.ScopeExecutor;

/** A recovered SENT job only reconciles. It never repeats the possible external write. */
@Component
public final class ExecutionHandler implements JobHandler {
  private static final Set<String> RECOVERY =
      Set.of("command.read", "command.reconcile", "file.read", "catalog.read");
  private final ExecutionService execution;
  private final DecisionService decisions;
  private final CommercialGateway gateway;
  private final ScopeExecutor scopes;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final Clock clock;
  private final RuntimeService runtime;
  private final MarketplaceReadService marketplace;
  private final ScenarioService scenarios;
  private final RiskLimitService risks;
  private final AutoService automatic;
  private final ResourceService resources;
  private final CommandStateMachine states = new CommandStateMachine();

  public ExecutionHandler(
      ExecutionService execution,
      DecisionService decisions,
      CommercialGateway gateway,
      ScopeExecutor scopes,
      JobRuntime jobs,
      JsonCodec json,
      Clock clock,
      RuntimeService runtime,
      MarketplaceReadService marketplace,
      ScenarioService scenarios,
      RiskLimitService risks,
      AutoService automatic,
      ResourceService resources) {
    this.execution = execution;
    this.decisions = decisions;
    this.gateway = gateway;
    this.scopes = scopes;
    this.jobs = jobs;
    this.json = json;
    this.clock = clock;
    this.runtime = runtime;
    this.marketplace = marketplace;
    this.scenarios = scenarios;
    this.risks = risks;
    this.automatic = automatic;
    this.resources = resources;
  }

  @Override
  public String type() {
    return "AUTOMATION_EXECUTE";
  }

  @Override
  public String lane() {
    return "execution";
  }

  @Override
  public JobOutcome execute(JobContext job) throws Exception {
    var request = json.decode(job.payload(), DecisionService.ExecutionJob.class);
    var command =
        scopes.execute(
            job.scope(),
            RECOVERY,
            () -> {
              jobs.requireOwnership(job);
              return execution.get(job.scope(), request.commandId());
            });
    var snapshot = decisions.snapshotForReconciliation(job.scope(), command.decisionId());
    if (states.terminal(command.state()) && !quantityPending(job, command)) {
      return finish(job, command, snapshot);
    }
    if (command.state() != State.PENDING) {
      return reconcile(job, command, snapshot);
    }

    if (command.journalFileId() == null) {
      var journal = decisions.storeJournal(job.scope(), command.id(), snapshot, command.prepared());
      scopes.execute(
          job.scope(),
          Set.of(),
          () -> {
            jobs.requireOwnership(job);
            execution.attachJournal(job.scope(), command.id(), journal.id());
            return true;
          });
    }
    var admission =
        scopes.execute(
            job.scope(),
            DecisionAuthorization.INTERNAL_INPUTS,
            () -> {
              decisions.requireApprovalAuthority(job.scope(), command.decisionId());
              jobs.requireOwnership(job);
              automatic.requireDecisionAuthority(job.scope(), command.decisionId());
              var current =
                  decisions.currentStep(
                      job.scope(), command.decisionId(), snapshot, command.step());
              if (current.status() == DecisionContinuationService.Status.AWAITING_SOURCE) {
                return new Attempt(false, clock.instant().plusSeconds(2), null);
              }
              if (current.status() != DecisionContinuationService.Status.CURRENT) {
                execution.cancel(job.scope(), command.id(), "STALE_BEFORE_SEND");
                return new Attempt(false, null, null);
              }
              var candidate =
                  snapshot.candidates().stream()
                      .filter(c -> c.id().equals(snapshot.result().selectedCandidate()))
                      .findFirst()
                      .orElseThrow();
              var step = candidate.path().get(command.step());
              var currentBasePrice =
                  marketplace.offer(job.scope(), snapshot.basis().offerId()).sellerPrice();
              var nextAdmission =
                  runtime.nextAdmissionAt(
                      job.scope(),
                      snapshot.basis().offerId(),
                      step.targetId(),
                      snapshot.evidence().settings());
              boolean protectiveCorrection =
                  snapshot.result().reason().equals("PROTECTIVE_CORRECTION");
              if (!protectiveCorrection && clock.instant().isBefore(nextAdmission)) {
                return new Attempt(false, nextAdmission, null);
              }
              var vendor = gateway.admit(job.scope(), command.id(), command.prepared());
              if (!vendor.admitted()) {
                return new Attempt(false, vendor.retryAt(), null);
              }
              var permission = snapshot.evidence().economicPermission();
              if (permission != null) {
                var operation =
                    switch (step.operation()) {
                      case SET_BASE_PRICE -> CommercialGateway.Operation.SET_PRICE;
                      case SET_PROMO_PRICE -> CommercialGateway.Operation.SET_PROMO_PRICE;
                      case JOIN_PROMO -> CommercialGateway.Operation.JOIN_PROMO;
                      case LEAVE_PROMO -> CommercialGateway.Operation.LEAVE_PROMO;
                    };
                if (permission.keptProfitFloor().signum() < 0) {
                  var boundary =
                      scenarios.requireEconomicBoundary(
                          job.scope(),
                          permission.runId(),
                          permission.externalBoundaryEvidence(),
                          step.targetId());
                  EconomicCalculator calculator = new EconomicCalculator();
                  var outcomes =
                      candidate.effects().stream()
                          .filter(
                              candidateEffect -> candidateEffect.targetId().equals(step.targetId()))
                          .flatMap(candidateEffect -> candidateEffect.outcomes().stream())
                          .map(outcome -> outcome.input())
                          .toList();
                  UUID obligation =
                      UUID.nameUUIDFromBytes(
                          (permission.runId() + ":" + boundary.id())
                              .getBytes(StandardCharsets.UTF_8));
                  var liability = calculator.maximumLiability(outcomes, boundary.maximumQuantity());
                  for (var planned : candidate.path()) {
                    if (planned.maximumUnitLiability() == null) {
                      throw new BusinessException(
                          "INTERMEDIATE_LIABILITY_UNKNOWN",
                          409,
                          "Ответственность промежуточного состояния не подтверждена");
                    }
                    liability =
                        liability.max(
                            EconomicCalculator.checked(
                                planned
                                    .maximumUnitLiability()
                                    .multiply(boundary.maximumQuantity())));
                  }
                  risks.reserveForCommand(
                      job.scope(),
                      permission.id(),
                      obligation,
                      command.id(),
                      liability,
                      permission.scopeDigest(),
                      permission.runId(),
                      operation);
                } else {
                  risks.requireCurrent(
                      job.scope(),
                      permission.id(),
                      snapshot.basis().scenarioId(),
                      permission.scopeDigest(),
                      operation);
                }
              }
              var availability =
                  resources.availability(
                      job.scope(), snapshot.evidence().commercial().stockPoolId());
              var stock = availability.stock();
              boolean stockComplete =
                  stock.complete()
                      && stock.obligationsCovered() != null
                      && availability.quantity() != null
                      && stock.validUntil().isAfter(clock.instant());
              var commitment = command.prepared().resourceCommitment();
              if (step.committedQuantity() != null || commitment != null) {
                var quantityTerms =
                    current.commercial().availablePromotions().stream()
                        .filter(
                            option ->
                                option.promotionId().equals(step.promoId())
                                    && option.targetId().equals(step.targetId()))
                        .map(option -> option.quantityTerms())
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null);
                if (commitment == null
                    || step.committedQuantity() == null
                    || quantityTerms == null
                    || commitment.quantity().compareTo(step.committedQuantity()) != 0
                    || !commitment.stockPoolId().equals(current.commercial().stockPoolId())
                    || !commitment.externalScopeKey().equals(quantityTerms.externalScopeKey())
                    || commitment.stockRevision() != stock.revision()) {
                  throw new BusinessException(
                      "QUANTITY_BINDING_CHANGED",
                      409,
                      "Отправляемое количество не совпадает с подтверждённым планом");
                }
                resources.reserve(
                    job.scope(),
                    command.id(),
                    commitment.stockPoolId(),
                    commitment.quantity(),
                    commitment.stockRevision());
              }
              runtime.observeStock(
                  job.scope(),
                  snapshot.basis().offerId(),
                  step.targetId(),
                  availability.quantity(),
                  stockComplete,
                  currentBasePrice,
                  snapshot.evidence().settings());
              runtime.admit(
                  job.scope(),
                  command.id(),
                  snapshot.basis().offerId(),
                  step.targetId(),
                  step.operation() == Operation.SET_PROMO_PRICE
                      ? current.commercial().currentParticipations().stream()
                          .filter(
                              value ->
                                  value.targetId().equals(step.targetId())
                                      && value.promotionId().equals(step.promoId()))
                          .findFirst()
                          .orElseThrow()
                          .promotionPrice()
                      : currentBasePrice,
                  step.operation() == Operation.SET_PROMO_PRICE
                      ? step.absolutePrice()
                      : step.operation() == Operation.SET_BASE_PRICE
                          ? step.absolutePrice()
                          : currentBasePrice,
                  snapshot.evidence().settings(),
                  protectiveCorrection);
              scenarios.admit(
                  job.scope(),
                  snapshot.basis().scenarioId(),
                  current.basis().scenarioRevision(),
                  command.id());
              var admitted =
                  execution.admit(
                      job.scope(),
                      command.id(),
                      current.basis().offerRevision(),
                      snapshot.evidence().commercial().profileRevision(),
                      true,
                      true,
                      snapshot.basis().validUntil(),
                      snapshot.basis().maximumAgeSeconds());
              if (!admitted.allowed()) {
                // Undo the quota/transport reservation together with the failed domain admission.
                throw new BusinessException(
                    "ADMISSION_CHANGED", 409, "Условия отправки изменились");
              }
              jobs.requireOwnership(job);
              return new Attempt(true, null, admitted.latestStart());
            });
    if (!admission.admitted()) {
      return admission.retryAt() == null
          ? finish(job, command, snapshot)
          : JobOutcome.waiting("QUOTA_WAIT", admission.retryAt());
    }
    if (!clock.instant().isBefore(admission.latestStart())) {
      scopes.execute(
          job.scope(),
          RECOVERY,
          () -> {
            jobs.requireOwnership(job);
            execution.notSent(job.scope(), command.id());
            releaseQuantity(job, command);
            return true;
          });
      return finish(job, command, snapshot);
    }

    CommercialGateway.SendResult sent;
    try {
      sent = gateway.send(job.scope(), command.id(), command.prepared());
    } catch (RuntimeException failure) {
      // A transport exception cannot establish whether the remote system applied the request.
      sent =
          new CommercialGateway.SendResult(
              CommercialGateway.Outcome.UNKNOWN, null, "TRANSPORT_RESULT_UNKNOWN");
    }
    CommercialGateway.SendResult result = sent;
    scopes.execute(
        job.scope(),
        RECOVERY,
        () -> {
          jobs.requireOwnership(job);
          execution.recordSend(job.scope(), command.id(), result);
          if (result.outcome() == CommercialGateway.Outcome.REJECTED
              && result.rawFileId() != null) {
            releaseQuantity(job, command);
          }
          return true;
        });
    return JobOutcome.waiting("READBACK_REQUIRED", clock.instant().plusSeconds(30));
  }

  private JobOutcome reconcile(
      JobContext job, ExecutionService.Command command, DecisionService.Snapshot snapshot) {
    var evidence = gateway.reconcile(job.scope(), command.id(), command.prepared());
    State state =
        scopes.execute(
            job.scope(),
            RECOVERY,
            () -> {
              jobs.requireOwnership(job);
              var reconciled = execution.reconcile(job.scope(), command.id(), evidence);
              var commitment = command.prepared().resourceCommitment();
              if (commitment != null) {
                if (evidence.obligations().size() > 1) {
                  throw new BusinessException(
                      "ALLOCATION_SCOPE_AMBIGUOUS",
                      409,
                      "Одна точная аллокация не допускает подмену несколькими обязательствами");
                }
                for (var obligation : evidence.obligations()) {
                  resources.observe(
                      job.scope(), command.id(), commitment, command.admittedAt(), obligation);
                }
              }
              if (reconciled == State.FAILED
                  && evidence.obligations().isEmpty()
                  && states.reconcile(State.SENT, command.prepared().effects(), evidence)
                      == State.FAILED) {
                releaseQuantity(job, command);
              }
              return reconciled;
            });
    if (states.terminal(state)) {
      var finished = finish(job, command, snapshot);
      return quantityPending(job, command)
          ? JobOutcome.waiting(
              "EXTERNAL_QUANTITY_OBLIGATION_OPEN", clock.instant().plusSeconds(3600))
          : finished;
    }
    long elapsed =
        command.admittedAt() == null
            ? 0
            : Duration.between(command.admittedAt(), clock.instant()).toSeconds();
    long delay =
        elapsed < 60 ? 30 : elapsed < 180 ? 60 : elapsed < 600 ? 120 : elapsed < 86400 ? 300 : 3600;
    return JobOutcome.waiting("EXTERNAL_EFFECT_UNKNOWN", clock.instant().plusSeconds(delay));
  }

  public JobOutcome reconcileOnly(JobContext job, java.util.UUID commandId) throws Exception {
    var command =
        scopes.execute(
            job.scope(),
            RECOVERY,
            () -> {
              jobs.requireOwnership(job);
              return execution.get(job.scope(), commandId);
            });
    if (command.state() == State.PENDING) {
      return JobOutcome.blocked("COMMAND_NOT_SENT");
    }
    var snapshot = decisions.snapshotForReconciliation(job.scope(), command.decisionId());
    return states.terminal(command.state()) && !quantityPending(job, command)
        ? finish(job, command, snapshot)
        : reconcile(job, command, snapshot);
  }

  private boolean quantityPending(JobContext job, ExecutionService.Command command) {
    var commitment = command.prepared().resourceCommitment();
    return commitment != null
        && scopes.execute(
            job.scope(),
            RECOVERY,
            () -> resources.pending(job.scope(), command.id(), commitment.stockPoolId()));
  }

  private void releaseQuantity(JobContext job, ExecutionService.Command command) {
    risks.releaseAbsentEffect(job.scope(), command.id());
    var commitment = command.prepared().resourceCommitment();
    if (commitment != null) {
      resources.releaseUnsent(job.scope(), command.id(), commitment.stockPoolId(), true);
    }
  }

  private JobOutcome finish(
      JobContext job, ExecutionService.Command command, DecisionService.Snapshot snapshot) {
    try {
      boolean complete =
          scopes.execute(
              job.scope(),
              DecisionAuthorization.INTERNAL_INPUTS,
              () -> {
                decisions.requireApprovalAuthority(job.scope(), command.decisionId());
                jobs.requireOwnership(job);
                return decisions.finishStep(job.scope(), command.decisionId(), snapshot);
              });
      if (!complete) {
        return JobOutcome.waiting(
            "CONFIRMED_PREFIX_PUBLICATION_REQUIRED", clock.instant().plusSeconds(2));
      }
    } catch (BusinessException failure) {
      if (failure.status() != 403) {
        throw failure;
      }
      scopes.execute(
          job.scope(),
          RECOVERY,
          () -> {
            jobs.requireOwnership(job);
            decisions.stopRemainder(job.scope(), command.decisionId(), "AUTHORITY_REVOKED");
            return true;
          });
    }
    return JobOutcome.succeeded(json.encode(new Completed(command.id())));
  }

  private record Attempt(boolean admitted, Instant retryAt, Instant latestStart) {}

  private record Completed(java.util.UUID commandId) {}
}
