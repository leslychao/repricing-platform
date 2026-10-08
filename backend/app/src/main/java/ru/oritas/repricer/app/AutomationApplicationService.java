package ru.oritas.repricer.app;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.AutomationGrantService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.automation.decision.CurrentEconomicsService;
import ru.oritas.repricer.automation.decision.DecisionContextAssembler;
import ru.oritas.repricer.automation.decision.DecisionEngine;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.automation.decision.QuantityComparisonService;
import ru.oritas.repricer.automation.execution.ExecutionService;
import ru.oritas.repricer.automation.policy.AssignmentLifecycleService;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.automation.runtime.AutoService;
import ru.oritas.repricer.automation.runtime.RuntimeService;
import ru.oritas.repricer.automation.runtime.ScenarioCompletion;
import ru.oritas.repricer.automation.runtime.ScenarioService;
import ru.oritas.repricer.economics.RiskLimitService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.OperationResult;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource.Query;

/** HTTP use cases share canonical domain owners and their scoped transaction boundaries. */
@Service
public final class AutomationApplicationService {
  public record Policy(
      UUID id,
      String name,
      String description,
      String status,
      long version,
      long revision,
      PolicySettings settings,
      PolicySettings draft,
      Long assignmentsCount,
      List<String> assignmentScopes,
      boolean hasDraft) {}

  public record Assignment(
      UUID id,
      UUID policyId,
      UUID accountId,
      String scope,
      UUID targetId,
      String targetName,
      String mode,
      String status,
      long revision,
      PolicyService.LocalReferences regularReferences,
      PolicyService.LocalReferences temporaryReferences) {}

  public record Version(String id, long version, String status, Instant createdAt, UUID actor) {}

  public record Step(String id, String type, String description, String status) {}

  public record Decision(
      UUID id,
      UUID offerId,
      Instant createdAt,
      String status,
      String strategy,
      long policyVersion,
      BigDecimal currentPrice,
      BigDecimal proposedPrice,
      BigDecimal profit,
      BigDecimal margin,
      List<String> reasons,
      List<Step> steps,
      long revision,
      Map<String, String> sourceRevisions,
      List<UUID> placements) {}

  public record Attempt(String id, Instant startedAt, String outcome, String message) {}

  public record AutoGrantState(AutomationGrantService.Grant grant) {}

  public record TemporaryPermissionState(
      RiskLimitService.Permission permission, RiskLimitService.Usage usage) {}

  /** Safe scenario history; private frozen policy parameters stay in the domain snapshot. */
  public record TemporaryRun(
      UUID id,
      UUID assignmentId,
      long assignmentRevision,
      UUID policyId,
      long policyVersion,
      String state,
      Instant startsAt,
      Instant endsAt,
      BigDecimal maximumAcceptedQuantity,
      BigDecimal minimumRemainingStock,
      BigDecimal acceptedQuantity,
      String scopeDigest,
      String reason,
      long revision,
      ScenarioCompletion.Review completionReview) {}

  public record Command(
      UUID id,
      UUID decisionId,
      String status,
      Instant createdAt,
      long revision,
      BigDecimal targetPrice,
      List<Step> effects,
      List<Attempt> attempts) {}

  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final IdempotencyService requests;
  private final PolicyService policies;
  private final AssignmentLifecycleService assignments;
  private final DecisionService decisions;
  private final ExecutionService execution;
  private final RuntimeService runtime;
  private final ScenarioService scenarios;
  private final CurrentEconomicsService currentEconomics;
  private final AutoService auto;
  private final AutomationGrantService autoGrants;
  private final RiskLimitService risks;
  private final QuantityComparisonService quantities;
  private final MarketplaceReadService marketplace;

  public AutomationApplicationService(
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      IdempotencyService requests,
      PolicyService policies,
      DecisionService decisions,
      ExecutionService execution,
      RuntimeService runtime,
      ScenarioService scenarios,
      CurrentEconomicsService currentEconomics,
      AutoService auto,
      AutomationGrantService autoGrants,
      RiskLimitService risks,
      QuantityComparisonService quantities,
      AssignmentLifecycleService assignments,MarketplaceReadService marketplace) {
    this.transactions = transactions;
    this.authorization = authorization;
    this.requests = requests;
    this.policies = policies;
    this.decisions = decisions;
    this.execution = execution;
    this.runtime = runtime;
    this.scenarios = scenarios;
    this.currentEconomics = currentEconomics;
    this.auto = auto;
    this.autoGrants = autoGrants;
    this.risks = risks;
    this.quantities = quantities;
    this.assignments = assignments;
    this.marketplace = marketplace;
  }

  public Page<Policy> policies(Scope scope, Query query) {
    return transactions.run(
        scope,
        () -> {
          var result = policies.page(scope, query);
          return new Page<>(
              result.items().stream().map(AutomationApplicationService::policy).toList(),
              result.total(),
              result.page(),
              result.size());
        });
  }

  public Policy policy(Scope scope, UUID id) {
    return transactions.run(scope, () -> policy(policies.get(scope, id)));
  }

  public Policy savePolicy(Scope scope, UUID id, UUID requestId, PolicyService.PolicyInput input) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("organization.policy.manage"));
          return requests.execute(
              scope,
              id == null ? "policy.create" : "policy.draft:" + id,
              requestId,
              input,
              Policy.class,
              () ->
                  policy(
                      id == null
                          ? policies.create(scope, input)
                          : policies.saveDraft(scope, id, input)));
        });
  }

  public Policy publish(Scope scope, UUID id, UUID requestId, long revision) {
    return transactions.run(
        scope,
        () -> {
          policies.requirePublicationPermissions(scope, id);
          return requests.execute(
              scope,
              "policy.publish:" + id,
              requestId,
              revision,
              Policy.class,
              () -> policy(policies.publish(scope, id, revision)));
        });
  }

  public Page<Version> versions(Scope scope, UUID id, int page, int size) {
    return transactions.run(
        scope,
        () -> {
          var result = policies.publications(scope, id, page, size);
          return new Page<>(
              result.items().stream()
                  .map(
                      item ->
                          new Version(
                              id + ":" + item.version(),
                              item.version(),
                              "PUBLISHED",
                              item.publishedAt(),
                              item.authorId()))
                  .toList(),
              result.total(),
              result.page(),
              result.size());
        });
  }

  public Policy changePolicyStatus(
      Scope scope, UUID id, UUID requestId, String status, long revision) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("organization.policy.manage"));
          if (status.equals("ACTIVE")) {
            policies.requirePublicationPermissions(scope, id);
          }
          record Input(String status, long revision) {}
          return requests.execute(
              scope,
              "policy.status:" + id,
              requestId,
              new Input(status, revision),
              Policy.class,
              () -> policy(policies.changeStatus(scope, id, status, revision)));
        });
  }

  public Page<TemporaryRun> temporaryRuns(Scope scope, int page, int size, UUID offerId) {
    return transactions.run(
        scope,
        () -> {
          var result = scenarios.page(scope, page, size, offerId);
          return new Page<>(
              result.items().stream().map(AutomationApplicationService::temporaryRunView).toList(),
              result.total(),
              result.page(),
              result.size());
        });
  }

  public TemporaryRun temporaryRun(Scope scope, UUID id) {
    return transactions.run(scope, () -> temporaryRunView(scenarios.get(scope, id)));
  }

  public TemporaryPermissionState temporaryPermission(Scope scope, UUID id) {
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "finance.read");
          scenarios.get(scope, id);
          var permission = risks.forRun(scope, id).orElse(null);
          return new TemporaryPermissionState(
              permission, permission == null ? null : risks.getUsage(scope, permission.id()));
        });
  }

  public TemporaryRun revokeTemporaryPermission(
      Scope scope,
      UUID id,
      UUID requestId,
      long expectedRevision,
      long expectedPermissionRevision) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(
              scope, Set.of("automation.manage", "finance.write", "resource.manage"));
          record Input(long revision, long permissionRevision) {}
          return requests.execute(
              scope,
              "scenario.permission.revoke:" + id,
              requestId,
              new Input(expectedRevision, expectedPermissionRevision),
              TemporaryRun.class,
              () ->
                  temporaryRunView(
                      scenarios.revokePermission(
                          scope, id, expectedRevision, expectedPermissionRevision)));
        });
  }

  public Assignment getAssignment(Scope scope, UUID id) {
    return transactions.run(scope, () -> assignment(scope, policies.getAssignment(scope, id)));
  }

  public AutoGrantState autoGrant(Scope scope, UUID assignmentId) {
    return transactions.run(
        scope,
        () -> {
          policies.getAssignment(scope, assignmentId);
          return new AutoGrantState(autoGrants.latest(scope, assignmentId).orElse(null));
        });
  }

  public OperationResult activateAuto(
      Scope scope,
      UUID id,
      UUID requestId,
      long expectedRevision,
      long expectedGrantRevision,
      long expectedPolicyVersion,
      String expectedScopeDigest) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(
              scope, Set.of("automation.manage", "finance.read", "decision.approve"));
          var input =
              new AutoService.Activate(
                  id,
                  expectedRevision,
                  expectedGrantRevision,
                  expectedPolicyVersion,
                  expectedScopeDigest);
          return requests.execute(
              scope,
              "assignment.auto:" + id,
              requestId,
              input,
              OperationResult.class,
              () -> new OperationResult(auto.submitActivation(scope, requestId, input), "PENDING"));
        });
  }

  public AutoService.Review previewAuto(Scope scope, UUID id, long expectedRevision) {
    return transactions.run(scope, () -> auto.previewActivation(scope, id, expectedRevision));
  }

  public Page<AutoService.SweepStatus> sweeps(Scope scope, int page, int size) {
    return transactions.run(scope, () -> auto.sweeps(scope, page, size));
  }

  public AutoGrantState revokeAuto(
      Scope scope, UUID assignmentId, UUID requestId, long expectedRevision) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("automation.manage"));
          policies.getAssignment(scope, assignmentId);
          return requests.execute(
              scope,
              "assignment.auto.revoke:" + assignmentId,
              requestId,
              expectedRevision,
              AutoGrantState.class,
              () -> {
                var grant = autoGrants.current(scope, assignmentId);
                autoGrants.revoke(scope, grant.id(), expectedRevision);
                return new AutoGrantState(autoGrants.latest(scope, assignmentId).orElse(null));
              });
        });
  }

  public RiskLimitService.Permission permitTemporaryRun(
      Scope scope,
      UUID id,
      UUID requestId,
      long revision,
      BigDecimal profitFloor,
      BigDecimal lossBudget) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(
              scope, Set.of("automation.manage", "finance.write", "resource.manage"));
          record Input(long revision, BigDecimal profitFloor, BigDecimal lossBudget) {}
          return requests.execute(
              scope,
              "scenario.permission:" + id,
              requestId,
              new Input(revision, profitFloor, lossBudget),
              RiskLimitService.Permission.class,
              () -> scenarios.grantPermission(scope, id, revision, profitFloor, lossBudget));
        });
  }

  public Page<CurrentEconomicsService.Calculation> calculations(
      Scope scope,
      int page,
      int size,
      String search,
      String status,
      String sort,
      String direction,
      UUID offerId,
      LocalDate from,
      LocalDate until) {
    return transactions.run(
        scope,
        () ->
            currentEconomics.page(
                scope, page, size, search, status, sort, direction, offerId, from, until));
  }

  public OperationResult createTemporaryRun(
      Scope scope, UUID requestId, ScenarioService.Input input) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("automation.manage", "finance.read"));
          return requests.execute(
              scope,
              "scenario.create",
              requestId,
              input,
              OperationResult.class,
              () -> new OperationResult(scenarios.submit(scope, requestId, input), "PENDING"));
        });
  }

  public ScenarioCompletion.Review reviewTemporaryRun(Scope scope, ScenarioService.Input input) {
    return transactions.run(scope, () -> scenarios.review(scope, input));
  }

  public TemporaryRun finishTemporaryRun(Scope scope, UUID id, UUID requestId, long revision) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("automation.manage"));
          return requests.execute(
              scope,
              "scenario.finish:" + id,
              requestId,
              revision,
              TemporaryRun.class,
              () -> temporaryRunView(scenarios.finish(scope, id, revision)));
        });
  }

  static TemporaryRun temporaryRunView(ScenarioService.Run source) {
    return new TemporaryRun(
        source.id(),
        source.assignmentId(),
        source.assignmentRevision(),
        source.policyId(),
        source.policyVersion(),
        source.state().name(),
        source.startsAt(),
        source.endsAt(),
        source.maximumAcceptedQuantity(),
        source.minimumRemainingStock(),
        source.acceptedQuantity(),
        source.scopeDigest(),
        source.reason(),
        source.revision(),
        source.completionReview());
  }

  public Page<Assignment> assignments(
      Scope scope, int page, int size, UUID policyId, String kind, String mode, UUID offerId) {
    return transactions.run(
        scope,
        () -> {
          var result = policies.assignmentPage(scope, page, size, policyId, kind, mode, offerId);
          var names=assignmentNames(scope,result.items());
          return new Page<>(
              result.items().stream().map(item -> assignment(scope, item,names)).toList(),
              result.total(),
              result.page(),
              result.size());
        });
  }

  public Assignment assign(Scope scope, UUID requestId, PolicyService.AssignmentInput input) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("policy.assign", "finance.read"));
          return requests.execute(
              scope,
              "assignment.create",
              requestId,
              input,
              Assignment.class,
              () -> assignment(scope, policies.assign(scope, input)));
        });
  }

  public PolicyService.DetachReview reviewDetach(Scope scope, UUID id, long revision) {
    return transactions.run(scope, () -> assignments.review(scope, id, revision));
  }

  public Assignment detachAssignment(
      Scope scope, UUID id, UUID requestId, long revision, String digest) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("policy.assign", "finance.read"));
          policies.getAssignment(scope, id);
          record Intent(long revision, String digest) {}
          return requests.execute(
              scope,
              "assignment.detach:" + id,
              requestId,
              new Intent(revision, digest),
              Assignment.class,
              () -> assignment(scope, assignments.detach(scope, id, revision, digest)));
        });
  }

  public Assignment pauseAssignment(
      Scope scope, UUID id, UUID requestId, long revision, boolean paused) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(
              scope, Set.of(paused ? "automation.pause" : "automation.resume"));
          return requests.execute(
              scope,
              "assignment.pause:" + id + ":" + paused,
              requestId,
              revision,
              Assignment.class,
              () -> assignment(scope, policies.pauseAssignment(scope, id, revision, paused)));
        });
  }

  public RuntimeService.PauseState pauseState(Scope scope, UUID offerId) {
    return transactions.run(scope, () -> runtime.pauseState(scope, offerId));
  }

  public RuntimeService.PauseState pause(
      Scope scope, UUID offerId, boolean paused, UUID requestId, long revision) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(
              scope, Set.of(paused ? "automation.pause" : "automation.resume"));
          return requests.execute(
              scope,
              "automation.pause:" + offerId + ":" + paused,
              requestId,
              revision,
              RuntimeService.PauseState.class,
              () -> runtime.changePause(scope, offerId, paused, revision));
        });
  }

  public OperationResult preview(
      Scope scope, UUID requestId, DecisionContextAssembler.PreviewRequest input) {
    return transactions.run(
        scope,
        () -> {
          decisions.requirePreviewAuthority(scope, input);
          return requests.execute(
              scope,
              "decision.preview",
              requestId,
              input,
              OperationResult.class,
              () ->
                  new OperationResult(decisions.submitPreview(scope, requestId, input), "PENDING"));
        });
  }

  public OperationResult approve(Scope scope, UUID id, UUID requestId, long revision) {
    return transactions.run(
        scope,
        () -> {
          decisions.requireApprovalAuthority(scope, id);
          var input = new DecisionService.ApprovalRequest(id, revision);
          return requests.execute(
              scope,
              "decision.approve:" + id,
              requestId,
              input,
              OperationResult.class,
              () ->
                  new OperationResult(
                      decisions.submitApproval(scope, requestId, input), "PENDING"));
        });
  }

  public Decision decision(Scope scope, UUID id) throws IOException {
    // Snapshot storage is read outside a database transaction, by the decision owner.
    var snapshot = decisions.snapshotForDisplay(scope, id);
    record Access(DecisionService.DecisionView view, boolean finance) {}
    var access =
        transactions.run(
            scope,
            () -> {
              decisions.requireDisplayAuthority(scope, id);
              return new Access(
                  decisions.get(scope, id), authorization.hasPermission(scope, "finance.read"));
            });
    var view = access.view();
    var result = snapshot.result();
    var selected =
        snapshot.candidates().stream()
            .filter(candidate -> Objects.equals(candidate.id(), result.selectedCandidate()))
            .findFirst();
    List<Step> steps = new ArrayList<>();
    BigDecimal current = null;
    BigDecimal proposed = null;
    if (selected.isPresent()) {
      var candidate = selected.orElseThrow();
      for (int index = 0; index < candidate.path().size(); index++) {
        DecisionEngine.Step step = candidate.path().get(index);
        String description =
            "Цель "
                + step.targetId()
                + (step.promoId() == null ? "" : " · акция " + step.promoId())
                + (step.absolutePrice() == null
                    ? ""
                    : " · " + step.absolutePrice().toPlainString());
        steps.add(
            new Step(
                id + ":" + index,
                step.operation().name(),
                description,
                step.supported() && step.safeIntermediateState() ? "READY" : "BLOCKED"));
      }
      if (candidate.effects().size() == 1) {
        current = candidate.effects().getFirst().currentComparedPrice();
        proposed = candidate.effects().getFirst().comparedPrice();
      }
    }
    var monetary = CurrentEconomicsService.unitValue(snapshot, result.selectedCandidate());
    BigDecimal profit = !access.finance() || monetary == null ? null : monetary.profit();
    BigDecimal margin = !access.finance() || monetary == null ? null : monetary.margin();
    var basis = snapshot.basis();
    Map<String, String> revisions = new LinkedHashMap<>();
    revisions.put("offer", Long.toString(basis.offerRevision()));
    revisions.put("assignment", Long.toString(basis.assignmentRevision()));
    if (access.finance()) {
      revisions.put("cost", Long.toString(basis.costRevision()));
      revisions.put("tax", Long.toString(basis.taxRevision()));
      revisions.put("safety", Long.toString(basis.safetyRevision()));
    }
    String status = view.state().equals("CALCULATED") ? result.status() : view.state();
    return new Decision(
        id,
        view.offerId(),
        view.calculatedAt(),
        status,
        snapshot.context().rule().strategy().name(),
        basis.policyVersion(),
        current,
        proposed,
        profit,
        margin,
        List.of(result.reason()),
        List.copyOf(steps),
        view.revision(),
        revisions,
        QuantityComparisonService.placements(snapshot));
  }

  public Page<QuantityComparisonService.Saved> quantities(
      Scope scope, UUID id, int page, int size) {
    return transactions.run(scope, () -> quantities.page(scope, id, page, size));
  }

  public QuantityComparisonService.Saved compareQuantity(
      Scope scope, UUID id, UUID requestId, UUID placementId, int quantity) throws IOException {
    var snapshot = decisions.snapshot(scope, id);
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("finance.read", "decision.preview"));
          record Input(UUID placementId, int quantity) {}
          return requests.execute(
              scope,
              "decision.quantity:" + id,
              requestId,
              new Input(placementId, quantity),
              QuantityComparisonService.Saved.class,
              () -> quantities.create(scope, id, placementId, quantity, snapshot));
        });
  }

  public Command command(Scope scope, UUID id) {
    return transactions.run(
        scope,
        () -> {
          var command = execution.get(scope, id);
          List<Step> effects = new ArrayList<>();
          var prepared = command.prepared();
          for (int index = 0; index < prepared.effects().size(); index++) {
            var effect = prepared.effects().get(index);
            String value =
                effect.expectedValue() == null
                    ? String.valueOf(effect.expectedParticipation())
                    : effect.expectedValue().toPlainString();
            effects.add(
                new Step(
                    id + ":" + index,
                    effect.field(),
                    "Цель " + effect.targetId() + " · " + value,
                    command.state().name()));
          }
          var attempts =
              command.admittedAt() == null
                  ? List.<Attempt>of()
                  : List.of(
                      new Attempt(
                          id + ":send",
                          command.admittedAt(),
                          command.state().name(),
                          command.reason()));
          BigDecimal target =
              prepared.effects().size() == 1 ? prepared.effects().getFirst().expectedValue() : null;
          return new Command(
              id,
              command.decisionId(),
              command.state().name(),
              command.checkedAt(),
              command.revision(),
              target,
              List.copyOf(effects),
              attempts);
        });
  }

  public OperationResult reconcile(Scope scope, UUID id, UUID requestId, long revision) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("command.reconcile"));
          return requests.execute(
              scope,
              "command.reconcile:" + id,
              requestId,
              revision,
              OperationResult.class,
              () ->
                  new OperationResult(
                      execution.submitReconcile(scope, requestId, id, revision), "PENDING"));
        });
  }

  public Command cancel(Scope scope, UUID id, UUID requestId, long revision) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("automation.pause"));
          return requests.execute(
              scope,
              "command.cancel:" + id,
              requestId,
              revision,
              Command.class,
              () -> {
                execution.cancel(scope, id, revision, "USER_CANCELLED");
                return command(scope, id);
              });
        });
  }

  private static Policy policy(PolicyService.Policy source) {
    PolicySettings published = source.settings() == null ? source.draft() : source.settings();
    return new Policy(
        source.id(),
        source.name(),
        source.description(),
        source.status(),
        source.version(),
        source.revision(),
        published,
        source.draft(),
        source.assignmentsCount(),
        source.assignmentScopes(),
        source.draft() != null && !source.draft().equals(source.settings()));
  }

  private MarketplaceReadService.CatalogNames assignmentNames(Scope scope,List<PolicyService.Assignment> items){
    if(!authorization.hasPermission(scope,"catalog.read")){return new MarketplaceReadService.CatalogNames(Map.of(),Map.of());}
    Set<UUID> offers=new HashSet<>();Set<UUID> categories=new HashSet<>();
    for(var item:items){
      if(item.scope().equals("OFFER")){offers.add(item.targetId());}
      if(item.scope().equals("CATEGORY")){categories.add(item.targetId());}
    }
    return marketplace.catalogNames(scope,offers,categories);
  }

  private Assignment assignment(Scope scope, PolicyService.Assignment source) {
    return assignment(scope,source,assignmentNames(scope,List.of(source)));
  }

  private static Assignment assignment(Scope scope, PolicyService.Assignment source,MarketplaceReadService.CatalogNames names) {
    String status = "DISABLED";
    if (source.detached()) {
      status = "DETACHED";
    } else if (source.paused()) {
      status = "PAUSED";
    } else if (source.enabled()) {
      status = "ACTIVE";
    }
    return new Assignment(
        source.id(),
        source.policyId(),
        scope.accountId(),
        source.scope(),
        source.targetId(),
        switch(source.scope()){
          case "ACCOUNT" -> "Весь кабинет";
          case "OFFER" -> names.offers().get(source.targetId());
          case "CATEGORY" -> names.categories().get(source.targetId());
          default -> throw new IllegalStateException("Unexpected assignment scope");
        },
        source.mode(),
        status,
        source.revision(),
        source.regularReferences(),
        source.temporaryReferences());
  }
}
