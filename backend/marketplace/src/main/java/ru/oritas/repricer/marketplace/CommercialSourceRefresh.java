package ru.oritas.repricer.marketplace;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** Applied evidence schedules ordinary source reads without changing the confirmed command. */
@Component
public final class CommercialSourceRefresh implements OutboxRecipient, JobHandler {
  private final CatalogSyncService catalog;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final Clock clock;

  public CommercialSourceRefresh(
      CatalogSyncService catalog, JobRuntime jobs, JsonCodec json, Clock clock) {
    this.catalog = catalog;
    this.jobs = jobs;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public String name() {
    return "COMMERCIAL_SOURCE_REFRESH";
  }

  @Override
  public boolean accepts(String eventType) {
    return eventType.equals("COMMAND_APPLIED");
  }

  @Override
  public Set<String> servicePermissions() {
    return Set.of("marketplace.sync.refresh");
  }

  @Override
  public void receive(Scope scope, UUID eventId, String eventType, String payload) {
    var change = json.decode(payload, OutboxService.EntityChange.class);
    if (!change.resource().equals("commands") || change.entityId() == null) {
      throw new IllegalArgumentException("Invalid applied-command event");
    }
    jobs.submit(scope, type(), eventId.toString(), json.encode(new Refresh(clock.instant())));
  }

  @Override
  public String type() {
    return name();
  }

  @Override
  public JobOutcome execute(JobContext context) {
    Refresh refresh = json.decode(context.payload(), Refresh.class);
    try {
      if (!catalog.requestAfterReadback(context, "PRICES", refresh.confirmedAt())
          || !catalog.requestAfterReadback(context, "PROMOTIONS", refresh.confirmedAt())) {
        return JobOutcome.waiting("EARLIER_SOURCE_TRAVERSAL", clock.instant().plusSeconds(10));
      }
      return JobOutcome.succeeded("{}");
    } catch (BusinessException failure) {
      if (failure.code().equals("OUTBOX_BACKPRESSURE")) {
        return JobOutcome.waiting("OUTBOX_BACKPRESSURE", clock.instant().plusSeconds(30));
      }
      if (failure.status() == 403) {
        return JobOutcome.blocked("SOURCE_REFRESH_ACCESS_REVOKED");
      }
      throw failure;
    }
  }

  private record Refresh(Instant confirmedAt) {}
}
