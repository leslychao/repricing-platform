package ru.oritas.repricer.economics.accounting;

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
public class LedgerHandlers {
  private record Work(UUID publicationId) {}

  private record Rebuild(long revision, UUID afterId) {}

  @Bean
  OutboxRecipient financialFactsRecipient(JobRuntime jobs, JsonCodec json) {
    return new OutboxRecipient() {
      @Override
      public String name() {
        return "economics.financial-source";
      }

      @Override
      public boolean accepts(String eventType) {
        return eventType.equals("source.changed") || eventType.equals("FINANCIAL_BASIS_CHANGED");
      }

      @Override
      public Set<String> servicePermissions() {
        return Set.of("finance.read");
      }

      @Override
      public void receive(Scope scope, UUID eventId, String eventType, String payload) {
        var change = json.decode(payload, OutboxService.EntityChange.class);
        if (eventType.equals("FINANCIAL_BASIS_CHANGED")) {
          jobs.submit(
              scope,
              "FINANCIAL_REBUILD",
              change.revision() + ":recognition:" + LedgerService.RECOGNITION_VERSION,
              json.encode(new Rebuild(change.revision(), null)));
        } else if (Set.of("services", "realization", "accruals").contains(change.resource())) {
          jobs.submit(
              scope,
              "FINANCIAL_LEDGER",
              change.entityId() + ":recognition:" + LedgerService.RECOGNITION_VERSION,
              json.encode(new Work(change.entityId())));
        }
      }
    };
  }

  @Bean
  JobHandler financialRebuildHandler(
      LedgerService ledger, JobRuntime jobs, ScopeExecutor scopes, JsonCodec json) {
    return new JobHandler() {
      @Override
      public String type() {
        return "FINANCIAL_REBUILD";
      }

      @Override
      public String lane() {
        return "canonicalization";
      }

      @Override
      public JobOutcome execute(JobContext context) {
        return scopes.execute(
            context.scope(),
            Set.of("finance.read"),
            () -> {
              jobs.requireOwnership(context);
              var work = json.decode(context.payload(), Rebuild.class);
              var sources = ledger.activeSources(context.scope(), work.afterId());
              for (UUID source : sources) {
                jobs.submit(
                    context.scope(),
                    "FINANCIAL_LEDGER",
                    source
                        + ":"
                        + work.revision()
                        + ":recognition:"
                        + LedgerService.RECOGNITION_VERSION,
                    json.encode(new Work(source)));
              }
              if (sources.size() == 100) {
                jobs.submit(
                    context.scope(),
                    "FINANCIAL_REBUILD",
                    work.revision()
                        + ":"
                        + sources.getLast()
                        + ":recognition:"
                        + LedgerService.RECOGNITION_VERSION,
                    json.encode(new Rebuild(work.revision(), sources.getLast())));
              }
              jobs.requireOwnership(context);
              return JobOutcome.succeeded("{}");
            });
      }
    };
  }

  @Bean
  JobHandler financialLedgerHandler(
      LedgerService ledger, JobRuntime jobs, ScopeExecutor scopes, JsonCodec json, Clock clock) {
    return new JobHandler() {
      @Override
      public String type() {
        return "FINANCIAL_LEDGER";
      }

      @Override
      public String lane() {
        return "canonicalization";
      }

      @Override
      public JobOutcome execute(JobContext context) {
        return scopes.execute(
            context.scope(),
            Set.of("finance.read"),
            () -> {
              jobs.requireOwnership(context);
              var work = json.decode(context.payload(), Work.class);
              boolean ready = ledger.prepareNext(context.scope(), work.publicationId());
              jobs.requireOwnership(context);
              return ready
                  ? JobOutcome.succeeded("{}")
                  : JobOutcome.waiting(
                      "FINANCIAL_SOURCE_PREPARING", clock.instant().plusSeconds(1));
            });
      }
    };
  }
}
