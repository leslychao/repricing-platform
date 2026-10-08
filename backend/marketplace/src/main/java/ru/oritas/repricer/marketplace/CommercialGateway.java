package ru.oritas.repricer.marketplace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ru.oritas.repricer.platform.Scope;

/** Vendor-independent contract; a send is called exactly once after durable admission. */
public interface CommercialGateway {
  PreparedCommercialCommand prepare(Scope scope, CommercialIntent intent);

  /** Reserves a send in the caller's final admission transaction, without network access. */
  Admission admit(Scope scope, UUID commandId, PreparedCommercialCommand command);

  SendResult send(Scope scope, UUID commandId, PreparedCommercialCommand command);

  Reconciliation reconcile(Scope scope, UUID commandId, PreparedCommercialCommand command);

  enum Operation {
    SET_PRICE,
    JOIN_PROMO,
    LEAVE_PROMO,
    SET_PROMO_PRICE
  }

  enum Outcome {
    ACCEPTED,
    REJECTED,
    UNKNOWN
  }

  enum EffectState {
    CONFIRMED,
    REJECTED,
    UNKNOWN
  }

  record CommercialIntent(
      UUID offerId,
      UUID targetId,
      Operation operation,
      BigDecimal price,
      UUID promotionId,
      long expectedProfileRevision,
      long expectedSourceRevision,
      boolean renewMinimumProtection,
      BigDecimal committedQuantity) {
    public CommercialIntent(
        UUID offerId,
        UUID targetId,
        Operation operation,
        BigDecimal price,
        UUID promotionId,
        long expectedProfileRevision,
        long expectedSourceRevision,
        boolean renewMinimumProtection) {
      this(
          offerId,
          targetId,
          operation,
          price,
          promotionId,
          expectedProfileRevision,
          expectedSourceRevision,
          renewMinimumProtection,
          null);
    }
  }

  record CommercialEffect(
      UUID targetId, String field, BigDecimal expectedValue, Boolean expectedParticipation) {}

  record PreparedCommercialCommand(
      UUID profileId,
      long profileRevision,
      Instant profileExpiresAt,
      String method,
      String endpoint,
      String canonicalBody,
      String digest,
      long sourceRevision,
      List<CommercialEffect> effects,
      ResourceCommitment resourceCommitment) {
    public PreparedCommercialCommand(
        UUID profileId,
        long profileRevision,
        Instant profileExpiresAt,
        String method,
        String endpoint,
        String canonicalBody,
        String digest,
        long sourceRevision,
        List<CommercialEffect> effects) {
      this(
          profileId,
          profileRevision,
          profileExpiresAt,
          method,
          endpoint,
          canonicalBody,
          digest,
          sourceRevision,
          effects,
          null);
    }

    public PreparedCommercialCommand {
      effects = List.copyOf(effects);
      if (canonicalBody.length() > 65536 || effects.isEmpty() || effects.size() > 200) {
        throw new IllegalArgumentException("Commercial command exceeds bounded scope");
      }
    }
  }

  /** An actual quantity-bearing external allocation, never a forecast of sales. */
  record ResourceCommitment(
      UUID stockPoolId, BigDecimal quantity, long stockRevision, String externalScopeKey) {
    public ResourceCommitment {
      if (stockPoolId == null
          || quantity == null
          || quantity.signum() <= 0
          || quantity.scale() > 12
          || stockRevision < 1
          || externalScopeKey == null
          || externalScopeKey.isBlank()
          || externalScopeKey.length() > 500) {
        throw new IllegalArgumentException("Confirmed explicit quantity allocation required");
      }
    }
  }

  record SendResult(Outcome outcome, UUID rawFileId, String reason) {}

  record Admission(boolean admitted, Instant retryAt) {}

  record EffectEvidence(
      UUID targetId, String field, EffectState state, UUID rawFileId, String reason) {}

  record Reconciliation(
      boolean completeOriginalScope,
      boolean processingComplete,
      List<EffectEvidence> effects,
      List<ObligationEvidence> obligations) {
    public Reconciliation(
        boolean completeOriginalScope, boolean processingComplete, List<EffectEvidence> effects) {
      this(completeOriginalScope, processingComplete, effects, List.of());
    }

    public Reconciliation {
      effects = List.copyOf(effects);
      obligations = obligations == null ? List.of() : List.copyOf(obligations);
      if (obligations.size() > 200) {
        throw new IllegalArgumentException("Obligation evidence scope exceeds 200 rows");
      }
    }
  }

  record ObligationEvidence(
      UUID commandId,
      UUID stockPoolId,
      String externalObligationId,
      BigDecimal committedQuantity,
      BigDecimal remainingQuantity,
      boolean reflectedInStock,
      boolean complete,
      boolean admissionClosed,
      Instant observedAt,
      long sourceRevision,
      UUID rawFileId) {}
}
