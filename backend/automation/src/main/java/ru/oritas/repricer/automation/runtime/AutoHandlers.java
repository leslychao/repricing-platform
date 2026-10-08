package ru.oritas.repricer.automation.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.oritas.repricer.automation.decision.DecisionContextAssembler;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.ScopeExecutor;

@Configuration
public class AutoHandlers {
  @Bean
  JobHandler autoSweepHandler(
      AutoService automatic, ScopeExecutor scopes, JobRuntime jobs, JsonCodec json) {
    return new JobHandler() {
      @Override
      public String type() {
        return "AUTO_SWEEP";
      }

      @Override
      public Set<String> servicePermissions() {
        return Set.of("automation.manage", "policy.read", "catalog.read");
      }

      @Override
      public JobOutcome execute(JobContext context) {
        return scopes.execute(
            context.scope(),
            servicePermissions(),
            () -> {
              jobs.requireOwnership(context);
              automatic.sweep(
                  context.scope(),
                  context.id(),
                  json.decode(context.payload(), AutoService.Sweep.class));
              jobs.requireOwnership(context);
              return JobOutcome.succeeded("{}");
            });
      }
    };
  }

  @Bean
  JobHandler autoSourceHandler(
      AutoService automatic, ScopeExecutor scopes, JobRuntime jobs, JsonCodec json) {
    return new JobHandler() {
      @Override
      public String type() {
        return "AUTO_SOURCE";
      }

      @Override
      public JobOutcome execute(JobContext context) {
        return scopes.execute(
            context.scope(),
            Set.of("automation.manage"),
            () -> {
              jobs.requireOwnership(context);
              automatic.sourceBatch(
                  context.scope(),
                  context.id(),
                  json.decode(context.payload(), AutoService.SourceRequest.class));
              jobs.requireOwnership(context);
              return JobOutcome.succeeded("{}");
            });
      }
    };
  }

  @Bean
  JobHandler autoActivationHandler(
      AutoService automatic, ScopeExecutor scopes, JobRuntime jobs, JsonCodec json) {
    return new JobHandler() {
      @Override
      public String type() {
        return "AUTO_ACTIVATE";
      }

      @Override
      public JobOutcome execute(JobContext context) {
        return scopes.execute(
            context.scope(),
            Set.of(),
            () -> {
              jobs.requireOwnership(context);
              var request = json.decode(context.payload(), AutoService.Activate.class);
              var result = automatic.activate(context.scope(), request, context.id());
              jobs.requireOwnership(context);
              return JobOutcome.succeeded(json.encode(result));
            });
      }
    };
  }

  @Bean
  JobHandler autoCalculationHandler(
      AutoService automatic,
      DecisionContextAssembler assembler,
      DecisionService decisions,
      ScopeExecutor scopes,
      JobRuntime jobs,
      JsonCodec json,
      Clock clock) {
    return new JobHandler() {
      @Override
      public String type() {
        return "AUTO_CALCULATE";
      }

      @Override
      public String lane() {
        return "calculation";
      }

      @Override
      public JobOutcome execute(JobContext context) throws Exception {
        var work = json.decode(context.payload(), AutoService.Work.class);
        var generation =
            scopes.execute(
                context.scope(),
                Set.of("automation.manage"),
                () -> {
                  jobs.requireOwnership(context);
                  return automatic.generation(context.scope(), work);
                });
        if (generation.completed() >= generation.requested()) {
          return JobOutcome.succeeded("{}");
        }
        if (clock.instant().isBefore(generation.dueAt())) {
          return JobOutcome.waiting("SOURCE_DEBOUNCE", generation.dueAt());
        }
        String reason = "AUTO_CALCULATED";
        try {
          var assembly =
              scopes.execute(
                  context.scope(),
                  Set.of(),
                  () -> {
                    jobs.requireOwnership(context);
                    automatic.requireCurrent(context.scope(), work.grantId());
                    return assembler.assemble(
                        context.scope(),
                        new DecisionContextAssembler.PreviewRequest(
                            work.offerId(), work.targetId(), null, false));
                  });
          UUID decisionId =
              UUID.nameUUIDFromBytes(
                  (work.grantId() + ":" + work.targetId() + ":" + generation.requested())
                      .getBytes(StandardCharsets.UTF_8));
          var result = decisions.calculate(context.scope(), decisionId, assembly, context);
          if (result.selectedCandidate() != null && !result.requiresConfirmation()) {
            var snapshot = decisions.snapshot(context.scope(), decisionId);
            scopes.execute(
                context.scope(),
                Set.of(),
                () -> {
                  jobs.requireOwnership(context);
                  var current = automatic.generation(context.scope(), work);
                  if (current.requested() == generation.requested()) {
                    automatic.bindDecision(
                        context.scope(), work, generation.requested(), decisionId, snapshot);
                    decisions.submitApproval(
                        context.scope(),
                        decisionId,
                        new DecisionService.ApprovalRequest(decisionId, result.revision()));
                  }
                  return true;
                });
          }
        } catch (BusinessException failure) {
          // A rejected generation remains a recorded outcome; a later source event gets a new one.
          reason = failure.code();
        }
        boolean complete =
            scopes.execute(
                context.scope(),
                Set.of("automation.manage"),
                () -> {
                  jobs.requireOwnership(context);
                  return automatic.complete(context.scope(), work, generation.requested());
                });
        return complete
            ? JobOutcome.succeeded(json.encode(new Outcome(reason)))
            : JobOutcome.waiting("SOURCE_GENERATION_CHANGED", clock.instant().plusSeconds(2));
      }
    };
  }

  @Bean
  OutboxRecipient automaticSourceRecipient(AutoService automatic) {
    return new OutboxRecipient() {
      private final Set<String> events =
          Set.of(
              "catalog.changed",
              "stocks.changed",
              "source.changed",
              "connection.changed",
              "COMPETITOR_CHANGED",
              "COMPETITORS_IMPORTED",
              "COST_PUBLISHED",
              "TAX_PUBLISHED",
              "ECONOMICS_INPUT_PUBLISHED",
              "SAFETY_ENVELOPE_PUBLISHED",
              "OFFER_PRICE_BOUNDS_PUBLISHED",
              "PRICE_PARAMETERS_PUBLISHED",
              "SCENARIO_FINISHING",
              "SCENARIO_ECONOMIC_PERMISSION_GRANTED",
              "ECONOMIC_PERMISSION_GRANTED");

      @Override
      public String name() {
        return "automation.source-generation";
      }

      @Override
      public boolean accepts(String eventType) {
        return events.contains(eventType);
      }

      @Override
      public Set<String> servicePermissions() {
        return Set.of("automation.manage");
      }

      @Override
      public void receive(Scope scope, UUID eventId, String eventType, String payload) {
        automatic.sourceChanged(scope, eventId);
      }
    };
  }

  private record Outcome(String reason) {}
}
