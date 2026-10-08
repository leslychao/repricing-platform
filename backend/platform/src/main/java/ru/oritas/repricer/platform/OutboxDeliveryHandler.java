package ru.oritas.repricer.platform;

import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public final class OutboxDeliveryHandler implements JobHandler {
  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final ScopeExecutor transactions;
  private final JobRuntime jobs;
  private final ObjectProvider<OutboxRecipient> recipients;

  public OutboxDeliveryHandler(
      JdbcClient jdbc,
      JsonCodec json,
      ScopeExecutor transactions,
      JobRuntime jobs,
      ObjectProvider<OutboxRecipient> recipients) {
    this.jdbc = jdbc;
    this.json = json;
    this.transactions = transactions;
    this.jobs = jobs;
    this.recipients = recipients;
  }

  @Override
  public String type() {
    return "OUTBOX_DELIVERY";
  }

  @Override
  public JobOutcome execute(JobContext context) {
    UUID id = json.decode(context.payload(), OutboxService.Delivery.class).id();
    Entry entry =
        transactions.execute(
            context.scope(),
            Set.of("platform.outbox.manage"),
            () ->
                jdbc.sql(
                        """
                        SELECT producer_event_id,recipient,event_type,payload::text
                        FROM platform_outbox WHERE id=:id AND deletion_after IS NULL
                        """)
                    .param("id", id)
                    .query(
                        (row, index) ->
                            new Entry(
                                row.getObject(1, UUID.class),
                                row.getString(2),
                                row.getString(3),
                                row.getString(4)))
                    .optional()
                    .orElseThrow(OutboxDeliveryHandler::expired));
    OutboxRecipient recipient =
        recipients.stream()
            .filter(item -> item.name().equals(entry.recipient()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing outbox recipient"));
    return transactions.execute(
        context.scope(),
        recipient.servicePermissions(),
        () -> {
          jobs.requireOwnership(context);
          boolean delivered =
              jdbc.sql(
                      """
                      SELECT delivered_at IS NOT NULL FROM platform_outbox
                      WHERE id=:id AND deletion_after IS NULL FOR UPDATE
                      """)
                  .param("id", id)
                  .query(Boolean.class)
                  .optional()
                  .orElseThrow(OutboxDeliveryHandler::expired);
          if (!delivered) {
            recipient.receive(context.scope(), entry.eventId(), entry.type(), entry.payload());
            jdbc.sql("UPDATE platform_outbox SET delivered_at=clock_timestamp() WHERE id=:id")
                .param("id", id)
                .update();
            jdbc.sql("SELECT repricer_outbox_pressure(:id)")
                .param("id", id)
                .query((row, index) -> Boolean.TRUE)
                .single();
          }
          return JobOutcome.succeeded("{}");
        });
  }

  private record Entry(UUID eventId, String recipient, String type, String payload) {}

  private static BusinessException expired() {
    return new BusinessException("EVENT_EXPIRED", 410, "Срок хранения события истёк");
  }
}
