package ru.oritas.repricer.automation.reference;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ru.oritas.repricer.automation.policy.PolicySettings.Aggregation;
import ru.oritas.repricer.automation.policy.PolicySettings.Competitor;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;

public final class MarketReferenceEvaluator {
  public record Observation(
      UUID id,
      UUID sourceId,
      String segment,
      Instant observedAt,
      long revision,
      boolean revoked,
      boolean ownOffer,
      Boolean inStock,
      BigDecimal price,
      BigDecimal delivery) {}

  public record Reference(
      boolean available,
      String reason,
      BigDecimal value,
      BigDecimal target,
      List<UUID> observations) {
    public Reference {
      observations = List.copyOf(observations);
    }
  }

  public record BuyerObservation(UUID placementId, String segment, BigDecimal total) {}

  public record PriceIndex(
      UUID placementId,
      String segment,
      BigDecimal buyerPrice,
      BigDecimal referencePrice,
      BigDecimal value,
      List<UUID> observations) {
    public PriceIndex {
      observations = List.copyOf(observations);
    }
  }

  /** The displayed comparison uses the comparable reference before the strategy's offset. */
  public Optional<PriceIndex> priceIndex(
      List<BuyerObservation> buyers, String segment, Reference reference) {
    if (!reference.available()
        || reference.value() == null
        || reference.value().signum() <= 0
        || buyers.size() != 1) {
      return Optional.empty();
    }
    BuyerObservation buyer = buyers.getFirst();
    if (buyer.placementId() == null
        || !segment.equals(buyer.segment())
        || buyer.total() == null
        || buyer.total().signum() <= 0) {
      return Optional.empty();
    }
    return Optional.of(
        new PriceIndex(
            buyer.placementId(),
            segment,
            buyer.total(),
            reference.value(),
            EconomicCalculator.divide(buyer.total(), reference.value()),
            reference.observations()));
  }

  public Reference evaluate(List<Observation> observations, Competitor rule, Instant now) {
    Map<UUID, Observation> latest = new HashMap<>();
    for (Observation item : observations) {
      if (!rule.segment().equals(item.segment())) {
        continue;
      }
      Observation old = latest.get(item.sourceId());
      if (old != null
          && item.observedAt().equals(old.observedAt())
          && !item.id().equals(old.id())) {
        return unavailable("CONFLICTING_OBSERVATIONS");
      }
      if (old == null
          || item.observedAt().isAfter(old.observedAt())
          || (item.observedAt().equals(old.observedAt()) && item.revision() > old.revision())) {
        latest.put(item.sourceId(), item);
      } else if (item.observedAt().equals(old.observedAt())
          && item.revision() == old.revision()
          && !item.equals(old)) {
        return unavailable("CONFLICTING_OBSERVATIONS");
      }
    }
    List<Observation> usable = new ArrayList<>();
    Instant cutoff = now.minusSeconds(rule.maxAgeSeconds());
    for (Observation item : latest.values()) {
      if (item.revoked()
          || item.ownOffer()
          || !Boolean.TRUE.equals(item.inStock())
          || item.price() == null
          || item.price().signum() <= 0
          || item.delivery() == null
          || item.delivery().signum() < 0
          || item.observedAt().isBefore(cutoff)
          || item.observedAt().isAfter(now.plusSeconds(60))
          || (rule.aggregation() == Aggregation.SELECTED_SELLER
              && !item.sourceId().equals(rule.selectedSource()))) {
        continue;
      }
      usable.add(item);
    }
    if (usable.size() < rule.minimumSources()) {
      return unavailable("INSUFFICIENT_COMPARABLE_SOURCES");
    }
    usable.sort(
        Comparator.comparing((Observation o) -> o.price().add(o.delivery()))
            .thenComparing(Observation::sourceId));
    List<BigDecimal> prices = usable.stream().map(o -> o.price().add(o.delivery())).toList();
    BigDecimal reference =
        switch (rule.aggregation()) {
          case MIN, SELECTED_SELLER -> prices.getFirst();
          case SECOND -> prices.get(1);
          case MEDIAN ->
              prices.size() % 2 == 1
                  ? prices.get(prices.size() / 2)
                  : EconomicCalculator.divide(
                      prices.get(prices.size() / 2 - 1).add(prices.get(prices.size() / 2)),
                      BigDecimal.valueOf(2));
          case MEAN ->
              EconomicCalculator.divide(
                  prices.stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                  BigDecimal.valueOf(prices.size()));
        };
    BigDecimal target =
        rule.amountOffset() != null
            ? reference.add(rule.amountOffset())
            : reference.multiply(BigDecimal.ONE.add(rule.ratioOffset()));
    if (target.signum() <= 0) {
      return unavailable("NONPOSITIVE_COMPETITOR_TARGET");
    }
    return new Reference(
        true, "COMPARABLE", reference, target, usable.stream().map(Observation::id).toList());
  }

  private static Reference unavailable(String reason) {
    return new Reference(false, reason, null, null, List.of());
  }
}
