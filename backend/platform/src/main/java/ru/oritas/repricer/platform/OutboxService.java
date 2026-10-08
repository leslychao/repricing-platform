package ru.oritas.repricer.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public final class OutboxService {
  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final JobRuntime jobs;
  private final ObjectProvider<OutboxRecipient> recipients;

  public OutboxService(
      JdbcClient jdbc,
      JsonCodec json,
      JobRuntime jobs,
      ObjectProvider<OutboxRecipient> recipients) {
    this.jdbc = jdbc;
    this.json = json;
    this.jobs = jobs;
    this.recipients = recipients;
  }

  public UUID emit(Scope scope, String eventKey, String type, Object payload) {
    scope.requireOrganization();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Outbox must share the producer transaction");
    }
    if (eventKey.isBlank() || eventKey.length() > 512 || type.isBlank() || type.length() > 100) {
      throw new IllegalArgumentException("Invalid event identity");
    }
    UUID eventId =
        UUID.nameUUIDFromBytes(
            (scope.organizationId() + ":" + scope.accountId() + ":" + type + ":" + eventKey)
                .getBytes(StandardCharsets.UTF_8));
    String encoded = json.encode(payload);
    for (OutboxRecipient recipient : recipients) {
      if (!recipient.accepts(type)) {
        continue;
      }
      UUID deliveryId = UUID.randomUUID();
      int inserted =
          jdbc.sql(
                  """
                  INSERT INTO platform_outbox(id,organization_id,account_id,subject_id,
                    producer_event_id,recipient,event_type,payload)
                  VALUES (:id,:org,:account,:subject,:event,:recipient,:type,CAST(:payload AS jsonb))
                  ON CONFLICT(producer_event_id,recipient) DO NOTHING
                  """)
              .param("id", deliveryId)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("subject", scope.subjectId())
              .param("event", eventId)
              .param("recipient", recipient.name())
              .param("type", type)
              .param("payload", encoded)
              .update();
      if (inserted == 1) {
        jdbc.sql("SELECT repricer_outbox_pressure(:id)")
            .param("id", deliveryId)
            .query((row, index) -> Boolean.TRUE)
            .single();
        jobs.submit(
            scope, "OUTBOX_DELIVERY", deliveryId.toString(), json.encode(new Delivery(deliveryId)));
      } else {
        boolean retained =
            jdbc.sql(
                    """
                    SELECT deletion_after IS NULL FROM platform_outbox
                    WHERE producer_event_id=:event AND recipient=:recipient FOR SHARE
                    """)
                .param("event", eventId)
                .param("recipient", recipient.name())
                .query(Boolean.class)
                .optional()
                .orElse(false);
        if (!retained) {
          throw new BusinessException("EVENT_EXPIRED", 410, "Срок хранения события истёк");
        }
      }
    }
    return eventId;
  }

  public record EntityChange(String resource, UUID entityId, long revision) {}

  /**
   * Only new heavy work is deferred; delivery, stops, revocation and reconciliation remain open.
   */
  public boolean heavyAllowed() {
    return jdbc.sql("SELECT repricer_heavy_admission()").query(Boolean.class).single();
  }

  public void requireHeavyAdmission() {
    if (!heavyAllowed()) {
      throw new BusinessException(
          "OUTBOX_BACKPRESSURE",
          429,
          "Обработка новых больших операций приостановлена до разгрузки очереди событий");
    }
  }

  public record Delivery(UUID id) {}
}
