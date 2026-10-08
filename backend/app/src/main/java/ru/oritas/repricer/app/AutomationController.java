package ru.oritas.repricer.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.oritas.repricer.automation.decision.CurrentEconomicsService;
import ru.oritas.repricer.automation.decision.DecisionContextAssembler;
import ru.oritas.repricer.automation.decision.QuantityComparisonService;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.automation.runtime.AutoService;
import ru.oritas.repricer.automation.runtime.RuntimeService;
import ru.oritas.repricer.automation.runtime.ScenarioCompletion;
import ru.oritas.repricer.automation.runtime.ScenarioService;
import ru.oritas.repricer.economics.RiskLimitService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.OperationResult;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.TableExportSource.Query;

@RestController
public final class AutomationController {
  private final RequestIdentity identity;
  private final AutomationApplicationService automation;

  public AutomationController(RequestIdentity identity, AutomationApplicationService automation) {
    this.identity = identity;
    this.automation = automation;
  }

  @GetMapping("/api/v1/policies")
  public Page<AutomationApplicationService.Policy> policies(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "name") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam Map<String, String> parameters) {
    Map<String, String> filters = new LinkedHashMap<>(parameters);
    filters.keySet().removeAll(Set.of("page", "size", "search", "sort", "direction"));
    return automation.policies(
        identity.scope(request), new Query(search, filters, sort + "," + direction, page, size));
  }

  @GetMapping("/api/v1/automation-sweeps")
  public Page<AutoService.SweepStatus> sweeps(HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return automation.sweeps(identity.scope(request), page, size);
  }

  @GetMapping("/api/v1/policies/{id}")
  public AutomationApplicationService.Policy policy(
      HttpServletRequest request, @PathVariable UUID id) {
    return automation.policy(identity.scope(request), id);
  }

  @PostMapping("/api/v1/policies")
  @ResponseStatus(HttpStatus.CREATED)
  public AutomationApplicationService.Policy create(
      HttpServletRequest request, @Valid @RequestBody PolicyRequest input) {
    return automation.savePolicy(
        identity.scope(request), null, input.clientRequestId(), input.value());
  }

  @PutMapping("/api/v1/policies/{id}/draft")
  public AutomationApplicationService.Policy draft(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody PolicyRequest input) {
    return automation.savePolicy(
        identity.scope(request), id, input.clientRequestId(), input.value());
  }

  @PostMapping("/api/v1/policies/{id}/publish")
  public AutomationApplicationService.Policy publish(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return automation.publish(
        identity.scope(request), id, input.clientRequestId(), input.expectedRevision());
  }

  @GetMapping("/api/v1/policies/{id}/versions")
  public Page<AutomationApplicationService.Version> versions(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return automation.versions(identity.scope(request), id, page, size);
  }

  @PostMapping("/api/v1/policies/{id}/status")
  public AutomationApplicationService.Policy policyStatus(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody PolicyStatusRequest input) {
    return automation.changePolicyStatus(
        identity.scope(request),
        id,
        input.clientRequestId(),
        input.status(),
        input.expectedRevision());
  }

  @GetMapping("/api/v1/temporary-runs")
  public Page<AutomationApplicationService.TemporaryRun> temporaryRuns(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) UUID offerId) {
    return automation.temporaryRuns(identity.scope(request), page, size, offerId);
  }

  @GetMapping("/api/v1/temporary-runs/{id}")
  public AutomationApplicationService.TemporaryRun temporaryRun(
      HttpServletRequest request, @PathVariable UUID id) {
    return automation.temporaryRun(identity.scope(request), id);
  }

  @GetMapping("/api/v1/temporary-runs/{id}/permission")
  public AutomationApplicationService.TemporaryPermissionState temporaryPermission(
      HttpServletRequest request, @PathVariable UUID id) {
    return automation.temporaryPermission(identity.scope(request), id);
  }

  @GetMapping("/api/v1/policy-assignments/{id}")
  public AutomationApplicationService.Assignment assignment(
      HttpServletRequest request, @PathVariable UUID id) {
    return automation.getAssignment(identity.scope(request), id);
  }

  @PostMapping("/api/v1/temporary-runs/{id}/permission/revoke")
  public AutomationApplicationService.TemporaryRun revokeTemporaryPermission(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody PermissionRevocationRequest input) {
    return automation.revokeTemporaryPermission(
        identity.scope(request),
        id,
        input.clientRequestId(),
        input.expectedRevision(),
        input.expectedPermissionRevision());
  }

  @GetMapping("/api/v1/policy-assignments/{id}/auto-grant")
  public AutomationApplicationService.AutoGrantState autoGrant(
      HttpServletRequest request, @PathVariable UUID id) {
    return automation.autoGrant(identity.scope(request), id);
  }

  @PostMapping("/api/v1/policy-assignments/{id}/activate-auto")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult activateAuto(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody AutoActivationRequest input) {
    return automation.activateAuto(
        identity.scope(request),
        id,
        input.clientRequestId(),
        input.expectedRevision(),
        input.expectedGrantRevision(),
        input.expectedPolicyVersion(),
        input.expectedScopeDigest());
  }

  @GetMapping("/api/v1/policy-assignments/{id}/auto-review")
  public AutoService.Review previewAuto(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam @Min(1) long expectedRevision) {
    return automation.previewAuto(identity.scope(request), id, expectedRevision);
  }

  @PostMapping("/api/v1/policy-assignments/{id}/auto-grant/revoke")
  public AutomationApplicationService.AutoGrantState revokeAuto(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return automation.revokeAuto(
        identity.scope(request), id, input.clientRequestId(), input.expectedRevision());
  }

  @PostMapping("/api/v1/temporary-runs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult createTemporaryRun(
      HttpServletRequest request, @Valid @RequestBody TemporaryRunRequest input) {
    return automation.createTemporaryRun(
        identity.scope(request),
        input.clientRequestId(),
        new ScenarioService.Input(
            input.assignmentId(),
            input.expectedRevision(),
            input.offerIds(),
            input.startsAt(),
            input.endsAt(),
            input.maximumAcceptedQuantity(),
            input.minimumRemainingStock(),
            input.expectedCompletionReviewDigest()));
  }

  @PostMapping("/api/v1/temporary-runs/review")
  public ScenarioCompletion.Review reviewTemporaryRun(
      HttpServletRequest request, @Valid @RequestBody TemporaryRunReviewRequest input) {
    return automation.reviewTemporaryRun(
        identity.scope(request),
        new ScenarioService.Input(
            input.assignmentId(),
            input.expectedRevision(),
            input.offerIds(),
            input.startsAt(),
            input.endsAt(),
            input.maximumAcceptedQuantity(),
            input.minimumRemainingStock(),
            null));
  }

  @PostMapping("/api/v1/temporary-runs/{id}/finish")
  public AutomationApplicationService.TemporaryRun finishTemporaryRun(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return automation.finishTemporaryRun(
        identity.scope(request), id, input.clientRequestId(), input.expectedRevision());
  }

  @PostMapping("/api/v1/temporary-runs/{id}/permission")
  public RiskLimitService.Permission permitTemporaryRun(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody TemporaryPermissionRequest input) {
    return automation.permitTemporaryRun(
        identity.scope(request),
        id,
        input.clientRequestId(),
        input.expectedRevision(),
        input.keptProfitFloor(),
        input.lossBudget());
  }

  @GetMapping("/api/v1/economics/calculations")
  public Page<CurrentEconomicsService.Calculation> calculations(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "id") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam(required = false) UUID offerId,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    return automation.calculations(
        identity.scope(request), page, size, search, status, sort, direction, offerId, from, to);
  }

  @GetMapping("/api/v1/policy-assignments")
  public Page<AutomationApplicationService.Assignment> assignments(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(required = false) UUID policyId,
      @RequestParam(defaultValue = "") String kind,
      @RequestParam(defaultValue = "") String mode,
      @RequestParam(required = false) UUID offerId) {
    return automation.assignments(
        identity.scope(request), page, size, policyId, kind, mode, offerId);
  }

  @PostMapping("/api/v1/policy-assignments")
  @ResponseStatus(HttpStatus.CREATED)
  public AutomationApplicationService.Assignment assign(
      HttpServletRequest request, @Valid @RequestBody AssignmentRequest input) {
    var scope = identity.scope(request);
    if (!scope.requireAccount().equals(input.accountId())) {
      throw new BusinessException("INVALID_SCOPE", 422, "Выберите кабинет назначения");
    }
    return automation.assign(
        scope,
        input.clientRequestId(),
        new PolicyService.AssignmentInput(
            input.policyId(),
            input.scope(),
            input.targetId(),
            input.mode(),
            true,
            input.expectedRevision(),
            input.regularReferences(),
            input.temporaryReferences()));
  }

  @GetMapping("/api/v1/policy-assignments/{id}/detach-review")
  public PolicyService.DetachReview reviewDetach(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam @Min(1) long expectedRevision) {
    return automation.reviewDetach(identity.scope(request), id, expectedRevision);
  }

  @PostMapping("/api/v1/policy-assignments/{id}/detach")
  public AutomationApplicationService.Assignment detachAssignment(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody DetachRequest input) {
    return automation.detachAssignment(
        identity.scope(request),
        id,
        input.clientRequestId(),
        input.expectedRevision(),
        input.expectedInheritanceDigest());
  }

  public record DetachRequest(
      @NotNull UUID clientRequestId,
      @Min(1) long expectedRevision,
      @NotBlank @Size(min = 64, max = 64) String expectedInheritanceDigest) {}

  @PostMapping("/api/v1/policy-assignments/{id}/pause")
  public AutomationApplicationService.Assignment pauseAssignment(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return automation.pauseAssignment(
        identity.scope(request), id, input.clientRequestId(), input.expectedRevision(), true);
  }

  @PostMapping("/api/v1/policy-assignments/{id}/resume")
  public AutomationApplicationService.Assignment resumeAssignment(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return automation.pauseAssignment(
        identity.scope(request), id, input.clientRequestId(), input.expectedRevision(), false);
  }

  @GetMapping("/api/v1/automation/pause")
  public RuntimeService.PauseState pauseState(
      HttpServletRequest request, @RequestParam(required = false) UUID offerId) {
    return automation.pauseState(identity.scope(request), offerId);
  }

  @PostMapping("/api/v1/automation/pause")
  public RuntimeService.PauseState pause(
      HttpServletRequest request, @Valid @RequestBody PauseRequest input) {
    return automation.pause(
        identity.scope(request),
        input.offerId(),
        input.paused(),
        input.clientRequestId(),
        input.expectedRevision());
  }

  @PostMapping("/api/v1/decisions/preview")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult preview(
      HttpServletRequest request, @Valid @RequestBody PreviewRequest input) {
    return automation.preview(
        identity.scope(request),
        input.clientRequestId(),
        new DecisionContextAssembler.PreviewRequest(
            input.offerId(), input.targetId(), input.policyId(), input.draft()));
  }

  @PostMapping("/api/v1/offers/fixed-target/preview")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult fixedTarget(
      HttpServletRequest request, @Valid @RequestBody FixedTargetRequest input) {
    return automation.preview(
        identity.scope(request),
        input.clientRequestId(),
        new DecisionContextAssembler.PreviewRequest(
            input.offerId(),
            input.targetId(),
            null,
            false,
            new DecisionContextAssembler.FixedTarget(input.priceKind(), input.target())));
  }

  @PostMapping("/api/v1/decisions/{id}/approve")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult approve(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return automation.approve(
        identity.scope(request), id, input.clientRequestId(), input.expectedRevision());
  }

  @GetMapping("/api/v1/decisions/{id}")
  public AutomationApplicationService.Decision decision(
      HttpServletRequest request, @PathVariable UUID id) throws IOException {
    return automation.decision(identity.scope(request), id);
  }

  @GetMapping("/api/v1/commands/{id}")
  public AutomationApplicationService.Command command(
      HttpServletRequest request, @PathVariable UUID id) {
    return automation.command(identity.scope(request), id);
  }

  @GetMapping("/api/v1/decisions/{id}/quantity-comparisons")
  public Page<QuantityComparisonService.Saved> quantities(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return automation.quantities(identity.scope(request), id, page, size);
  }

  @PostMapping("/api/v1/decisions/{id}/quantity-comparisons")
  public QuantityComparisonService.Saved compareQuantity(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody QuantityRequest input)
      throws IOException {
    return automation.compareQuantity(
        identity.scope(request),
        id,
        input.clientRequestId(),
        input.placementId(),
        input.quantity());
  }

  @PostMapping("/api/v1/commands/{id}/reconcile")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult reconcile(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return automation.reconcile(
        identity.scope(request), id, input.clientRequestId(), input.expectedRevision());
  }

  @PostMapping("/api/v1/commands/{id}/cancel")
  public AutomationApplicationService.Command cancel(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return automation.cancel(
        identity.scope(request), id, input.clientRequestId(), input.expectedRevision());
  }

  public record RevisionRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision) {}

  public record QuantityRequest(
      @NotNull UUID clientRequestId,
      @NotNull UUID placementId,
      @Min(1) @Max(1000000) int quantity) {}

  public record AutoActivationRequest(
      @NotNull UUID clientRequestId,
      @Min(1) long expectedRevision,
      @Min(0) long expectedGrantRevision,
      @Min(1) long expectedPolicyVersion,
      @NotBlank @Size(min = 64, max = 64) String expectedScopeDigest) {}

  public record PermissionRevocationRequest(
      @NotNull UUID clientRequestId,
      @Min(1) long expectedRevision,
      @Min(1) long expectedPermissionRevision) {}

  public record PolicyStatusRequest(
      @NotNull UUID clientRequestId, @Min(1) long expectedRevision, @NotBlank String status) {}

  public record TemporaryRunRequest(
      @NotNull UUID clientRequestId,
      @Min(1) long expectedRevision,
      @NotNull UUID assignmentId,
      @NotNull @Size(min = 1, max = 1000) List<@NotNull UUID> offerIds,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt,
      BigDecimal maximumAcceptedQuantity,
      BigDecimal minimumRemainingStock,
      @NotBlank @Size(min = 64, max = 64) String expectedCompletionReviewDigest) {}

  public record TemporaryRunReviewRequest(
      @Min(1) long expectedRevision,
      @NotNull UUID assignmentId,
      @NotNull @Size(min = 1, max = 1000) List<@NotNull UUID> offerIds,
      @NotNull Instant startsAt,
      @NotNull Instant endsAt,
      BigDecimal maximumAcceptedQuantity,
      BigDecimal minimumRemainingStock) {}

  public record TemporaryPermissionRequest(
      @NotNull UUID clientRequestId,
      @Min(1) long expectedRevision,
      @NotNull BigDecimal keptProfitFloor,
      BigDecimal lossBudget) {}

  public record PolicyRequest(
      @NotNull UUID clientRequestId,
      @Min(0) long expectedRevision,
      @NotBlank @Size(max = 120) String name,
      @NotNull @Size(max = 2000) String description,
      @NotNull PolicySettings settings) {
    PolicyService.PolicyInput value() {
      return new PolicyService.PolicyInput(name, description, settings, expectedRevision);
    }
  }

  public record AssignmentRequest(
      @NotNull UUID clientRequestId,
      @Min(0) long expectedRevision,
      @NotNull UUID policyId,
      @NotNull UUID accountId,
      @NotBlank String scope,
      UUID targetId,
      @NotBlank String mode,
      @NotNull PolicyService.LocalReferences regularReferences,
      @NotNull PolicyService.LocalReferences temporaryReferences) {}

  public record PreviewRequest(
      @NotNull UUID clientRequestId,
      @Min(0) long expectedRevision,
      @NotNull UUID offerId,
      UUID targetId,
      UUID policyId,
      boolean draft) {}

  public record PauseRequest(
      @NotNull UUID clientRequestId, @Min(0) long expectedRevision, UUID offerId, boolean paused) {}

  public record FixedTargetRequest(
      @NotNull UUID clientRequestId,
      @Min(0) long expectedRevision,
      @NotNull UUID offerId,
      UUID targetId,
      @NotNull PolicySettings.PriceKind priceKind,
      @NotNull BigDecimal target) {}
}
