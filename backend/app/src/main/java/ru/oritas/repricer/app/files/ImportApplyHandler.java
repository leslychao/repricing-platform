package ru.oritas.repricer.app.files;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.FileWorkGate;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;

@Component
public final class ImportApplyHandler implements JobHandler {
  private final ImportService imports;
  private final ScopeTransactionRunner transactions;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final AuditService audit;
  private final FileWorkGate gate;
  private final Clock clock;

  public ImportApplyHandler(
      ImportService imports,
      ScopeTransactionRunner transactions,
      JobRuntime jobs,
      JsonCodec json,
      AuditService audit,
      FileWorkGate gate,
      Clock clock) {
    this.imports = imports;
    this.transactions = transactions;
    this.jobs = jobs;
    this.json = json;
    this.audit = audit;
    this.gate = gate;
    this.clock = clock;
  }

  @Override
  public String type() {
    return "IMPORT_APPLY";
  }

  @Override
  public String lane() {
    return "canonicalization";
  }

  @Override
  public Duration maximumAttemptDuration() {
    return Duration.ofMinutes(30);
  }

  @Override
  public JobOutcome execute(JobContext context) {
    UUID id = json.decode(context.payload(), ImportService.Work.class).importId();
    var acquired = gate.tryAcquire(context.deadline());
    if (acquired.isEmpty()) {
      return JobOutcome.waiting("FILE_SLOTS_BUSY", clock.instant().plusSeconds(5));
    }
    try (FileWorkGate.Permit permit = acquired.orElseThrow()) {
      return transactions.run(
          context.scope(),
          () -> {
            var header = imports.load(id, false);
            var target = imports.target(header.kind());
            imports.requireWrite(context.scope(), target);
            jobs.requireOwnership(context);
            permit.confirm();
            target.lockForPublication(context.scope(), header.validationId());
            var current = imports.load(id, true);
            if (!current.status().equals("PREPARING")
                || !context.id().equals(current.applyJobId())) {
              return JobOutcome.succeeded(json.encode(new Applied(id, current.status())));
            }
            if (!target.prepareNext(context.scope(), current.validationId())) {
              return JobOutcome.waiting("PREPARING_INPUT", clock.instant());
            }
            target.commit(context.scope(), current.validationId());
            imports.retainAppliedEvidence(context.scope(), current);
            jobs.requireOwnership(context);
            permit.confirm();
            imports.transition(context.scope(), current, "COMMITTED", null);
            audit.record(context.scope(), "IMPORT_COMMITTED", id, "kind=" + current.kind());
            return JobOutcome.succeeded(json.encode(new Applied(id, "COMMITTED")));
          });
    } catch (BusinessException failure) {
      if (Set.of("LEASE_EXPIRED", "FILE_LEASE_EXPIRED").contains(failure.code())) {
        throw failure;
      }
      String state = failure.code().equals("STALE_REVISION") ? "STALE" : "FAILED";
      imports.failWork(context, id, "PREPARING", state, failure.code());
      return JobOutcome.blocked(failure.code());
    } catch (RuntimeException failure) {
      if (context.attempt() >= 5) {
        imports.failWork(context, id, "PREPARING", "FAILED", "IMPORT_PROCESSING_FAILED");
        return JobOutcome.blocked("IMPORT_PROCESSING_FAILED");
      }
      throw failure;
    }
  }

  private record Applied(UUID importId, String status) {}
}
