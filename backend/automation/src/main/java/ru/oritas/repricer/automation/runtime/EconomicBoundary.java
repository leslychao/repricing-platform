package ru.oritas.repricer.automation.runtime;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Outcome;
import ru.oritas.repricer.marketplace.CommercialState;
import ru.oritas.repricer.marketplace.CommercialState.LossBoundary;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;

/** Verifies one externally enforced boundary once even when several offers share it. */
public final class EconomicBoundary {
  private EconomicBoundary() {}

  public record Coverage(String digest, BigDecimal quantity, List<LossBoundary> boundaries) {
    public Coverage {
      boundaries = List.copyOf(boundaries);
    }
  }

  public static Coverage require(List<CommercialState> states, Instant now) {
    Map<UUID, LossBoundary> unique = new HashMap<>();
    Set<UUID> expectedTargets = new HashSet<>();
    Set<String> outcomes =
        EnumSet.allOf(Outcome.class).stream().map(Enum::name).collect(Collectors.toSet());
    for (var state : states) {
      var boundary = state.lossBoundary();
      if (!state.complete()
          || state.validUntil() == null
          || !state.validUntil().isAfter(now)
          || boundary == null
          || !boundary.validUntil().isAfter(now)
          || boundary.observedAt().isAfter(now)
          || !boundary.targetIds().containsAll(state.targetIds())
          || !boundary.coveredOutcomes().containsAll(outcomes)) {
        throw missing();
      }
      var previous = unique.putIfAbsent(boundary.id(), boundary);
      if (previous != null && !previous.equals(boundary)) {
        throw missing();
      }
      expectedTargets.addAll(state.targetIds());
    }
    if (states.isEmpty()) {
      throw missing();
    }
    Set<UUID> targets = new HashSet<>();
    BigDecimal quantity = BigDecimal.ZERO;
    List<String> parts = new ArrayList<>();
    var boundaries =
        unique.values().stream().sorted(java.util.Comparator.comparing(LossBoundary::id)).toList();
    for (var boundary : boundaries) {
      for (UUID target : boundary.targetIds()) {
        if (!targets.add(target)) {
          throw missing();
        }
      }
      quantity = quantity.add(boundary.maximumQuantity());
      parts.add(
          boundary.id()
              + ":"
              + boundary.revision()
              + ":"
              + boundary.externalScopeKey()
              + ":"
              + boundary.maximumQuantity().stripTrailingZeros().toPlainString()
              + ":"
              + boundary.targetIds().stream().sorted().toList()
              + ":"
              + boundary.coveredOutcomes().stream().sorted().toList());
    }
    if (!targets.equals(expectedTargets)) {
      throw missing();
    }
    return new Coverage(
        IdempotencyService.sha256(String.join(";", parts).getBytes(StandardCharsets.UTF_8)),
        quantity,
        boundaries);
  }

  private static BusinessException missing() {
    return new BusinessException(
        "EXTERNAL_LOSS_BOUND_REQUIRED",
        409,
        "Нет полного внешнего ограничения всех исходов в точном охвате эпизода");
  }
}
