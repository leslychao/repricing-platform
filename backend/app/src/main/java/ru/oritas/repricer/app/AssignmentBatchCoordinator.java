package ru.oritas.repricer.app;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.app.files.SelectionService;
import ru.oritas.repricer.automation.policy.AssignmentBatchService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.OperationResult;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;

/** Bridges the immutable selection owner to the domain without crossing storage boundaries. */
@Service
public final class AssignmentBatchCoordinator implements JobHandler {
  public record BulkInput(UUID clientRequestId, long expectedRevision, UUID selectionId,
      UUID policyId, String mode) {}

  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final IdempotencyService requests;
  private final SelectionService selections;
  private final AssignmentBatchService batches;
  private final JobRuntime jobs;
  private final OutboxService outbox;
  private final Clock clock;

  public AssignmentBatchCoordinator(ScopeTransactionRunner transactions,
      AuthorizationService authorization, IdempotencyService requests, SelectionService selections,
      AssignmentBatchService batches, JobRuntime jobs, OutboxService outbox, Clock clock) {
    this.transactions = transactions;
    this.authorization = authorization;
    this.requests = requests;
    this.selections = selections;
    this.batches = batches;
    this.jobs = jobs;
    this.outbox = outbox;
    this.clock = clock;
  }

  public OperationResult submit(Scope scope, BulkInput input) {
    return transactions.run(scope, () -> {
      authorization.requireLocked(scope, Set.of("policy.assign", "policy.read", "finance.read"));
      if (input.expectedRevision() != 0) {
        throw new BusinessException("BATCH_REVISION", 409, "Для нового пакета нужна нулевая редакция");
      }
      var snapshot = selections.snapshot(scope, input.selectionId(), false);
      if (!snapshot.resource().equals("offers")) {
        throw new BusinessException("BATCH_SOURCE", 422, "Назначение доступно для выборки товаров");
      }
      return requests.execute(scope, "assignment.bulk", input.clientRequestId(), input,
          OperationResult.class, () -> {
            outbox.requireHeavyAdmission();
            UUID id = batches.beginBulk(scope, input.clientRequestId(), input.selectionId(),
                snapshot.total(), input.policyId(), input.mode());
            selections.retain(scope, input.selectionId(), "ASSIGNMENT_BATCH", id);
            return new OperationResult(id, "PENDING");
          });
    });
  }

  public OperationResult preview(Scope scope, UUID requestId, UUID assignmentId, long revision) {
    return transactions.run(scope, () -> {
      authorization.requireLocked(scope, Set.of("decision.preview", "policy.read"));
      record Request(UUID assignmentId, long revision) {}
      return requests.execute(scope, "assignment.preview", requestId,
          new Request(assignmentId, revision), OperationResult.class, () -> {
            outbox.requireHeavyAdmission();
            return new OperationResult(batches.beginPreview(scope, requestId, assignmentId, revision),
                "PENDING");
          });
    });
  }

  public AssignmentBatchService.Batch get(Scope scope, UUID id) {
    return transactions.run(scope, () -> batches.get(scope, id));
  }

  public Page<AssignmentBatchService.Row> rows(Scope scope, UUID id, int page, int size) {
    return transactions.run(scope, () -> batches.rows(scope, id, page, size));
  }

  @Override
  public String type() {
    return "ASSIGNMENT_BATCH";
  }

  @Override
  public String lane() {
    return "calculation";
  }

  @Override
  public JobOutcome execute(JobContext context) {
    return transactions.run(context.scope(), () -> {
      jobs.requireOwnership(context);
      var batch = batches.lock(context.scope(), context.id());
      if (batch.selectionId() != null) {
        selections.snapshot(context.scope(), batch.selectionId(), false);
      }
      if (batch.state().equals("CAPTURING")) {
        var ids = batch.selectionId() == null ? batches.nextPreviewOffers(context.scope(), batch)
            : selections.capturedIds(context.scope(), batch.selectionId(), "ASSIGNMENT_BATCH",
                batch.id(), batch.cursor(), 200);
        batches.capture(context.scope(), batch, ids);
      } else if (batches.applyNext(context.scope(), batch)) {
        if (batch.selectionId() != null) {
          selections.release(context.scope(), batch.selectionId(), "ASSIGNMENT_BATCH", batch.id());
        }
        jobs.requireOwnership(context);
        return JobOutcome.succeeded("{}");
      }
      jobs.requireOwnership(context);
      return JobOutcome.waiting("ASSIGNMENT_BATCH_CONTINUES", clock.instant().plusSeconds(2));
    });
  }
}
