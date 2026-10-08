package ru.oritas.repricer.economics.analytics;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.ScopeExecutor;

@Configuration
public class SalesPaceHandlers {
  @Bean
  OutboxRecipient paceSourceRecipient(SalesPaceService pace, JsonCodec json) {
    return new OutboxRecipient() {
      @Override
      public String name() {
        return "economics.sales-pace";
      }

      @Override
      public boolean accepts(String eventType) {
        return eventType.equals("source.changed") || eventType.equals("catalog.changed");
      }

      @Override
      public Set<String> servicePermissions() {
        return Set.of("finance.read");
      }

      @Override
      public void receive(Scope scope, UUID eventId, String eventType, String payload) {
        var change = json.decode(payload, OutboxService.EntityChange.class);
        if (Set.of("sales-pace", "offers", "promotions").contains(change.resource())) {
          pace.submitRefresh(scope, eventId);
        }
      }
    };
  }

  @Bean
  JobHandler paceRefreshHandler(
      SalesPaceService pace, JobRuntime jobs, ScopeExecutor scopes, Clock clock) {
    return new JobHandler() {
      @Override
      public String type() {
        return "SALES_PACE_REFRESH";
      }

      @Override
      public String lane() {
        return "calculation";
      }

      @Override
      public Set<String> servicePermissions() {
        return Set.of("finance.read", "account.read");
      }

      @Override
      public JobOutcome execute(JobContext context) {
        return scopes.execute(
            context.scope(),
            servicePermissions(),
            () -> {
              jobs.requireOwnership(context);
              boolean completed = pace.prepareNext(context.scope(), context.id());
              jobs.requireOwnership(context);
              return completed
                  ? JobOutcome.succeeded("{}")
                  : JobOutcome.waiting("PACE_REFRESH_CONTINUES", clock.instant().plusSeconds(1));
            });
      }
    };
  }
}
