package ru.oritas.repricer.automation.reference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.automation.policy.PolicySettings.Aggregation;
import ru.oritas.repricer.automation.policy.PolicySettings.Competitor;
import ru.oritas.repricer.automation.reference.MarketReferenceEvaluator.Observation;

class MarketReferenceEvaluatorTest {
  private final Instant now = Instant.parse("2026-10-08T10:00:00Z");
  private final UUID source = UUID.randomUUID();
  private final Competitor rule =
      new Competitor(
          Aggregation.MIN,
          null,
          1,
          "same-region",
          new BigDecimal("-1"),
          null,
          BigDecimal.ZERO,
          3600);

  @Test
  void newerOutOfStockObservationDoesNotFallBackToOldCheapPrice() {
    var result =
        new MarketReferenceEvaluator()
            .evaluate(
                List.of(
                    observation(now.minusSeconds(60), true, false),
                    observation(now.minusSeconds(10), false, false)),
                rule,
                now);
    assertFalse(result.available());
  }

  @Test
  void revokedLatestObservationDoesNotUncoverEarlierObservation() {
    var result =
        new MarketReferenceEvaluator()
            .evaluate(
                List.of(
                    observation(now.minusSeconds(60), true, false),
                    observation(now.minusSeconds(10), true, true)),
                rule,
                now);
    assertFalse(result.available());
  }

  @Test
  void unknownDeliveryIsNotTreatedAsFreeDelivery() {
    var observation =
        new Observation(
            UUID.randomUUID(),
            source,
            "same-region",
            now,
            1,
            false,
            false,
            true,
            new BigDecimal("100"),
            null);
    assertFalse(
        new MarketReferenceEvaluator().evaluate(List.of(observation), rule, now).available());
  }

  @Test
  void comparesFullBuyerPriceBeforeOffset() {
    var result =
        new MarketReferenceEvaluator()
            .evaluate(List.of(observation(now.minusSeconds(10), true, false)), rule, now);
    assertEquals(0, new BigDecimal("104").compareTo(result.target()));
  }

  @Test
  void displayedIndexUsesComparableTotalBeforeOffsetAndRejectsAmbiguousPlacements() {
    var evaluator = new MarketReferenceEvaluator();
    var reference =
        evaluator.evaluate(List.of(observation(now.minusSeconds(10), true, false)), rule, now);
    var buyer =
        new MarketReferenceEvaluator.BuyerObservation(
            UUID.randomUUID(), "same-region", new BigDecimal("94.5"));
    var index = evaluator.priceIndex(List.of(buyer), rule.segment(), reference).orElseThrow();
    assertEquals(0, new BigDecimal("0.9").compareTo(index.value()));
    assertEquals(0, new BigDecimal("105").compareTo(index.referencePrice()));
    assertFalse(evaluator.priceIndex(List.of(buyer, buyer), rule.segment(), reference).isPresent());
    assertFalse(evaluator.priceIndex(List.of(buyer), "another-region", reference).isPresent());
  }

  private Observation observation(Instant at, boolean inStock, boolean revoked) {
    return new Observation(
        UUID.randomUUID(),
        source,
        "same-region",
        at,
        1,
        revoked,
        false,
        inStock,
        new BigDecimal("100"),
        new BigDecimal("5"));
  }
}
