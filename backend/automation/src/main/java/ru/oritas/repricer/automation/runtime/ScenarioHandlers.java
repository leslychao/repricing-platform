package ru.oritas.repricer.automation.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.oritas.repricer.automation.decision.DecisionContextAssembler;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.automation.runtime.RuntimeRules.ScenarioState;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.ScopeExecutor;

@Configuration
public class ScenarioHandlers {
  @Bean
  JobHandler scenarioCreateHandler(
      ScenarioService scenarios, ScopeExecutor scopes, JobRuntime jobs, JsonCodec json) {
    return new JobHandler() {
      @Override
      public String type() {
        return "SCENARIO_CREATE";
      }

      @Override
      public JobOutcome execute(JobContext context) {
        return scopes.execute(
            context.scope(),
            Set.of(),
            () -> {
              jobs.requireOwnership(context);
              var input = json.decode(context.payload(), ScenarioService.Input.class);
              var run = scenarios.create(context.scope(), context.id(), input);
              jobs.requireOwnership(context);
              return JobOutcome.succeeded(
                  json.encode(
                      new OutboxService.EntityChange("temporary-runs", run.id(), run.revision())));
            });
      }
    };
  }

  @Bean
  JobHandler scenarioProgressHandler(
      ScenarioService scenarios,
      DecisionService decisions,
      ScopeExecutor scopes,
      JobRuntime jobs,
      JsonCodec json,
      Clock clock) {
    return new JobHandler() {
      @Override
      public String type() {
        return "SCENARIO_PROGRESS";
      }

      @Override
      public JobOutcome execute(JobContext context) {
        var request = json.decode(context.payload(), ScenarioService.RunJob.class);
        var run =
            scopes.execute(
                context.scope(),
                Set.of("automation.manage", "catalog.read"),
                () -> {
                  jobs.requireOwnership(context);
                  return scenarios.progress(context.scope(), request.runId());
                });
        if (run.state() == ScenarioState.FINISHED) {
          return JobOutcome.succeeded(
              json.encode(
                  new OutboxService.EntityChange("temporary-runs", run.id(), run.revision())));
        }
        if (clock.instant().isBefore(run.startsAt())) {
          return JobOutcome.waiting("SCENARIO_START", run.startsAt());
        }
        try {
          scopes.execute(
              context.scope(),
              Set.of(),
              () -> {
                jobs.requireOwnership(context);
                for (var target : scenarios.targets(context.scope(), run.id())) {
                  UUID requestId =
                      UUID.nameUUIDFromBytes(
                          (run.id() + ":" + run.revision() + ":" + target.targetId())
                              .getBytes(StandardCharsets.UTF_8));
                  decisions.submitPreview(
                      context.scope(),
                      requestId,
                      new DecisionContextAssembler.PreviewRequest(
                          target.offerId(), target.targetId(), null, false));
                }
                return true;
              });
        } catch (BusinessException failure) {
          if (failure.status() != 403) {
            throw failure;
          }
          return JobOutcome.waiting("SCENARIO_AUTHORITY_REVOKED", clock.instant().plusSeconds(60));
        }
        return JobOutcome.waiting(
            run.state() == ScenarioState.FINISHING
                ? "FINISHING_REQUIRES_CONFIRMED_REGULAR_STATE"
                : "SCENARIO_PERIOD",
            run.state() == ScenarioState.FINISHING
                ? clock.instant().plusSeconds(60)
                : run.endsAt());
      }
    };
  }
}
