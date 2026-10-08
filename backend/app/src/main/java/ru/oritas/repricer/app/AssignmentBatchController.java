package ru.oritas.repricer.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.oritas.repricer.automation.policy.AssignmentBatchService;
import ru.oritas.repricer.platform.OperationResult;
import ru.oritas.repricer.platform.Page;

@RestController
public final class AssignmentBatchController {
  private final RequestIdentity identity;
  private final AssignmentBatchCoordinator batches;

  public AssignmentBatchController(RequestIdentity identity, AssignmentBatchCoordinator batches) {
    this.identity = identity;
    this.batches = batches;
  }

  @PostMapping("/api/v1/policy-assignments/bulk")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult bulk(HttpServletRequest request, @Valid @RequestBody BulkRequest input) {
    return batches.submit(identity.scope(request), new AssignmentBatchCoordinator.BulkInput(
        input.clientRequestId(), input.expectedRevision(), input.selectionId(), input.policyId(),
        input.mode()));
  }

  @PostMapping("/api/v1/policy-assignments/{id}/preview")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult preview(HttpServletRequest request, @PathVariable UUID id,
      @Valid @RequestBody Mutation input) {
    return batches.preview(identity.scope(request), input.clientRequestId(), id, input.expectedRevision());
  }

  @GetMapping("/api/v1/assignment-batches/{id}")
  public AssignmentBatchService.Batch get(HttpServletRequest request, @PathVariable UUID id) {
    return batches.get(identity.scope(request), id);
  }

  @GetMapping("/api/v1/assignment-batches/{id}/rows")
  public Page<AssignmentBatchService.Row> rows(HttpServletRequest request, @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return batches.rows(identity.scope(request), id, page, size);
  }

  public record Mutation(@NotNull UUID clientRequestId, @Min(0) long expectedRevision) {}

  public record BulkRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
      @NotNull UUID selectionId, @NotNull UUID policyId,
      @NotNull @Pattern(regexp = "PREVIEW|MANUAL") String mode) {}
}
