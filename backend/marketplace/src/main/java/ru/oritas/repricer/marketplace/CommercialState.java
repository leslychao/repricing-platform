package ru.oritas.repricer.marketplace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record CommercialState(
    UUID offerId,
    long revision,
    long profileRevision,
    Instant observedAt,
    Instant validUntil,
    boolean complete,
    List<UUID> targetIds,
    List<Participation> currentParticipations,
    List<PromotionOption> availablePromotions,
    Set<CommercialGateway.Operation> supportedOperations,
    UUID stockPoolId,
    BigDecimal verifiedQuantity,
    boolean externallyBoundedLoss,
    BigDecimal basePriceStep,
    Set<String> confirmedPreservedFields,
    LossBoundary lossBoundary) {
  public CommercialState(
      UUID offerId,
      long revision,
      long profileRevision,
      Instant observedAt,
      Instant validUntil,
      boolean complete,
      List<UUID> targetIds,
      List<Participation> currentParticipations,
      List<PromotionOption> availablePromotions,
      Set<CommercialGateway.Operation> supportedOperations,
      UUID stockPoolId,
      BigDecimal verifiedQuantity,
      boolean externallyBoundedLoss,
      BigDecimal basePriceStep,
      Set<String> confirmedPreservedFields) {
    this(
        offerId,
        revision,
        profileRevision,
        observedAt,
        validUntil,
        complete,
        targetIds,
        currentParticipations,
        availablePromotions,
        supportedOperations,
        stockPoolId,
        verifiedQuantity,
        externallyBoundedLoss,
        basePriceStep,
        confirmedPreservedFields,
        null);
  }

  public CommercialState {
    targetIds = List.copyOf(targetIds);
    currentParticipations = List.copyOf(currentParticipations);
    availablePromotions = List.copyOf(availablePromotions);
    supportedOperations = Set.copyOf(supportedOperations);
    confirmedPreservedFields =
        confirmedPreservedFields == null ? Set.of() : Set.copyOf(confirmedPreservedFields);
    if (targetIds.size() > 200
        || currentParticipations.size() > 100
        || availablePromotions.size() > 100) {
      throw new IllegalArgumentException("Commercial scope exceeds supported complete profile");
    }
  }

  /** Positive external proof covering all admissions, delayed orders and return re-entry. */
  public record LossBoundary(
      UUID id,
      String externalScopeKey,
      BigDecimal maximumQuantity,
      Instant observedAt,
      Instant validUntil,
      long revision,
      UUID rawFileId,
      Set<UUID> targetIds,
      Set<String> coveredOutcomes) {
    public LossBoundary {
      targetIds = Set.copyOf(targetIds);
      coveredOutcomes = Set.copyOf(coveredOutcomes);
      if (id == null
          || externalScopeKey == null
          || externalScopeKey.isBlank()
          || externalScopeKey.length() > 500
          || maximumQuantity == null
          || maximumQuantity.signum() <= 0
          || maximumQuantity.scale() > 12
          || maximumQuantity.precision() - maximumQuantity.scale() > 26
          || observedAt == null
          || validUntil == null
          || !validUntil.isAfter(observedAt)
          || revision < 1
          || rawFileId == null
          || targetIds.isEmpty()
          || targetIds.size() > 200
          || coveredOutcomes.isEmpty()
          || coveredOutcomes.size() > 6) {
        throw new IllegalArgumentException("A complete exact external loss boundary is required");
      }
    }
  }

  public record Participation(
      UUID promotionId,
      UUID targetId,
      BigDecimal basePrice,
      BigDecimal promotionPrice,
      boolean processing,
      BigDecimal maximumQuantity,
      Instant endsAt,
      boolean exitConfirmedAvailable) {}

  public record PromotionOption(
      UUID promotionId,
      UUID targetId,
      String type,
      BigDecimal minimumPrice,
      BigDecimal maximumPrice,
      BigDecimal priceStep,
      BigDecimal maximumQuantity,
      Instant endsAt,
      Set<UUID> compatiblePromotions,
      Set<String> requiredPreservedFields,
      QuantityTerms quantityTerms,
      boolean exitConfirmedAvailable) {
    public PromotionOption(
        UUID promotionId,
        UUID targetId,
        String type,
        BigDecimal minimumPrice,
        BigDecimal maximumPrice,
        BigDecimal priceStep,
        BigDecimal maximumQuantity,
        Instant endsAt,
        Set<UUID> compatiblePromotions,
        Set<String> requiredPreservedFields,
        boolean exitConfirmedAvailable) {
      this(
          promotionId,
          targetId,
          type,
          minimumPrice,
          maximumPrice,
          priceStep,
          maximumQuantity,
          endsAt,
          compatiblePromotions,
          requiredPreservedFields,
          null,
          exitConfirmedAvailable);
    }

    public PromotionOption {
      compatiblePromotions = Set.copyOf(compatiblePromotions);
      requiredPreservedFields = Set.copyOf(requiredPreservedFields);
    }
  }

  /** Explicit, positively verified allocation terms of a quantity-bearing promotion operation. */
  public record QuantityTerms(
      BigDecimal minimum, BigDecimal maximum, BigDecimal step, String externalScopeKey) {
    public QuantityTerms {
      if (minimum == null
          || maximum == null
          || step == null
          || minimum.signum() <= 0
          || maximum.compareTo(minimum) < 0
          || step.signum() <= 0
          || externalScopeKey == null
          || externalScopeKey.isBlank()) {
        throw new IllegalArgumentException("Complete allocation terms required");
      }
    }
  }
}
