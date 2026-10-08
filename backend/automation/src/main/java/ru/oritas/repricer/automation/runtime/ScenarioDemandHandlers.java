package ru.oritas.repricer.automation.runtime;

import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.oritas.repricer.marketplace.MarketplaceHistoryService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.ScopeExecutor;

/**
 * Published original demand updates episode counters even after the initiating user loses access.
 */
@Configuration
public class ScenarioDemandHandlers {
  private static final String JOB_TYPE = "SCENARIO_ORIGINAL_DEMAND";
  private static final int PAGE_SIZE = 50;
  private static final Set<String> PERMISSIONS =
      Set.of("finance.read", "automation.manage", "catalog.read");

  @Bean
  OutboxRecipient scenarioDemandRecipient(JobRuntime jobs, JsonCodec json) {
    return new OutboxRecipient() {
      @Override
      public String name() {
        return "automation.original-demand";
      }

      @Override
      public boolean accepts(String eventType) {
        return eventType.equals("history.published");
      }

      @Override
      public Set<String> servicePermissions() {
        return PERMISSIONS;
      }

      @Override
      public void receive(Scope scope, UUID eventId, String eventType, String payload) {
        var publication = json.decode(payload, MarketplaceHistoryService.HistoryPublication.class);
        schedule(jobs, json, scope, new DemandPage(publication.id(), null));
      }
    };
  }

  @Bean
  JobHandler scenarioDemandHandler(
      MarketplaceHistoryService history,
      ScenarioService scenarios,
      ScopeExecutor scopes,
      JobRuntime jobs,
      JsonCodec json) {
    return new JobHandler() {
      @Override
      public String type() {
        return JOB_TYPE;
      }

      @Override
      public String lane() {
        return "canonicalization";
      }

      @Override
      public Set<String> servicePermissions() {
        return PERMISSIONS;
      }

      @Override
      public JobOutcome execute(JobContext context) {
        var request = json.decode(context.payload(), DemandPage.class);
        return scopes.execute(
            context.scope(),
            PERMISSIONS,
            () -> {
              jobs.requireOwnership(context);
              var demands =
                  history.originalDemand(
                      context.scope(), request.publicationId(), request.afterId(), PAGE_SIZE);
              for (var demand : demands) {
                scenarios.observeOriginalDemand(context.scope(), demand);
              }
              if (demands.size() == PAGE_SIZE) {
                schedule(
                    jobs,
                    json,
                    context.scope(),
                    new DemandPage(request.publicationId(), demands.getLast().id()));
              }
              jobs.requireOwnership(context);
              return JobOutcome.succeeded("{}");
            });
      }
    };
  }

  private static void schedule(JobRuntime jobs, JsonCodec json, Scope scope, DemandPage page) {
    jobs.submit(
        scope,
        JOB_TYPE,
        page.publicationId() + ":" + (page.afterId() == null ? "start" : page.afterId()),
        json.encode(page));
  }

  record DemandPage(UUID publicationId, UUID afterId) {
    DemandPage {
      if (publicationId == null) {
        throw new IllegalArgumentException("A published original demand source is required");
      }
    }
  }
}
