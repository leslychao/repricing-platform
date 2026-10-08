package ru.oritas.repricer.automation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.marketplace.CommercialState;
import ru.oritas.repricer.platform.BusinessException;

class EconomicBoundaryTest {
  private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

  @Test
  void sharedExternalBoundaryCountsQuantityOnceAndRejectsUncoveredTargets() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    var proof =
        new CommercialState.LossBoundary(
            UUID.randomUUID(),
            "exact:external-scope",
            new BigDecimal("100"),
            NOW.minusSeconds(5),
            NOW.plusSeconds(60),
            1,
            UUID.randomUUID(),
            Set.of(first, second),
            Set.of(
                "KEPT_PURCHASE",
                "REFUSED_RETURN",
                "SALEABLE_RETURN",
                "DAMAGED_RETURN",
                "CANCELLED",
                "LOST"));
    var coverage =
        EconomicBoundary.require(List.of(state(first, proof), state(second, proof)), NOW);
    assertEquals(0, new BigDecimal("100").compareTo(coverage.quantity()));
    assertEquals(1, coverage.boundaries().size());
    assertThrows(
        BusinessException.class, () -> EconomicBoundary.require(List.of(state(first, proof)), NOW));
    assertThrows(
        BusinessException.class, () -> EconomicBoundary.require(List.of(state(first, null)), NOW));
    assertThrows(
        BusinessException.class,
        () ->
            EconomicBoundary.require(
                List.of(state(first, proof), state(second, proof)), NOW.plusSeconds(61)));
  }

  private static CommercialState state(UUID target, CommercialState.LossBoundary proof) {
    return new CommercialState(
        target,
        1,
        1,
        NOW.minusSeconds(10),
        NOW.plusSeconds(300),
        true,
        List.of(target),
        List.of(),
        List.of(),
        Set.of(),
        UUID.randomUUID(),
        new BigDecimal("100"),
        true,
        BigDecimal.ONE,
        Set.of(),
        proof);
  }
}
