package ru.oritas.repricer.marketplace;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Confirmed source terms, without monetary calculations or inferred zero charges. */
public record EconomicTerms(
    UUID offerId,
    UUID placementId,
    UUID targetId,
    String segment,
    long revision,
    Instant validUntil,
    boolean complete,
    BigDecimal sellerRevenueMultiplier,
    BigDecimal sellerRevenueOffset,
    BigDecimal buyerPriceMultiplier,
    BigDecimal buyerPriceOffset,
    List<TariffTerm> charges,
    Set<String> applicableOutcomes,
    List<OutcomeTerms> outcomes,
    Set<UUID> participationIds,
    PriceField sellerRevenueField,
    PriceField buyerPriceField,
    PriceField effectiveSellerPriceField,
    BigDecimal effectiveSellerPriceMultiplier,
    BigDecimal effectiveSellerPriceOffset) {
  public EconomicTerms {
    charges = List.copyOf(charges);
    applicableOutcomes = Set.copyOf(applicableOutcomes);
    outcomes = List.copyOf(outcomes);
    participationIds = Set.copyOf(participationIds);
    if (charges.size() > 200 || applicableOutcomes.size() > 12) {
      throw new IllegalArgumentException("Economic terms exceed bounded profile");
    }
  }

  public enum PriceFieldKind {
    BASE_SELLER,
    PROMOTION_SELLER
  }

  /** A source-proven affine dependency, not a ratio inferred from an observed price. */
  public record PriceField(PriceFieldKind kind, UUID promotionId) {
    public PriceField {
      if (kind == null || (kind == PriceFieldKind.BASE_SELLER) != (promotionId == null)) {
        throw new IllegalArgumentException("An exact price-field identity is required");
      }
    }

    public static PriceField base() {
      return new PriceField(PriceFieldKind.BASE_SELLER, null);
    }
  }

  public record TariffTerm(
      String code,
      String unit,
      BigDecimal fixedAmount,
      BigDecimal revenueRate,
      boolean confirmed,
      boolean mandatory,
      BigDecimal verifiedQuantity) {}

  public record OutcomeTerms(
      String outcome,
      BigDecimal sellerRevenueMultiplier,
      BigDecimal sellerRevenueOffset,
      BigDecimal consumedCostRatio,
      BigDecimal ownExpenseRatio,
      BigDecimal taxBaseMultiplier,
      BigDecimal taxBaseOffset,
      List<TariffTerm> charges,
      PriceField revenueField,
      PriceField taxBaseField) {
    public OutcomeTerms {
      charges = List.copyOf(charges);
    }
  }
}
