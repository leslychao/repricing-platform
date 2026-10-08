package ru.oritas.repricer.economics.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Conditional kept-purchase quantities, never a demand or sales forecast. */
public final class QuantityComparison {
  public static final int MAX_QUANTITY = 1_000_000;

  /** Non-unit tariffs need a separately proven volume model and cannot be extrapolated. */
  public LinearCase proportionalCase(EconomicCalculator.PricingInput pricing) {
    if (pricing.outcome() != EconomicCalculator.Outcome.KEPT_PURCHASE
        || pricing.units().compareTo(BigDecimal.ONE) != 0
        || pricing.tariffs().stream()
            .anyMatch(t -> t.unit() != EconomicCalculator.ChargeUnit.UNIT)) {
      throw new IllegalArgumentException(
          "Confirmed kept-purchase unit tariffs are required for volume comparison");
    }
    var quote = new EconomicCalculator().quote(pricing);
    if (!quote.result().complete()) {
      throw new IllegalArgumentException("Incomplete unit economics cannot be extrapolated");
    }
    return new LinearCase(quote.result().profit(), BigDecimal.ZERO);
  }

  public record LinearCase(BigDecimal unitProfit, BigDecimal fixedCosts) {
    public LinearCase {
      if (unitProfit == null || fixedCosts == null || fixedCosts.signum() < 0) {
        throw new IllegalArgumentException("Complete proportional costs are required");
      }
    }

    public BigDecimal result(int quantity) {
      return EconomicCalculator.checked(
          unitProfit.multiply(BigDecimal.valueOf(quantity)).subtract(fixedCosts));
    }
  }

  public enum ThresholdStatus {
    FOUND,
    NO_FINITE_THRESHOLD,
    LIMIT_EXCEEDED
  }

  public record Scenario(int quantity, BigDecimal result, BigDecimal difference) {}

  public record Comparison(
      int baselineQuantity,
      BigDecimal baselineResult,
      List<Scenario> scenarios,
      ThresholdStatus thresholdStatus,
      Integer threshold,
      BigDecimal relativeQuantityChangePercent,
      Boolean reachableWithStock,
      boolean hypotheticalBaseline) {
    public Comparison {
      scenarios = List.copyOf(scenarios);
    }
  }

  public Comparison compare(
      LinearCase baseline, LinearCase candidate, int quantity, BigDecimal available) {
    if (quantity < 1 || quantity > MAX_QUANTITY || (available != null && available.signum() < 0)) {
      throw new IllegalArgumentException("Invalid quantity");
    }
    BigDecimal target = baseline.result(quantity);
    Integer threshold = null;
    BigDecimal relativeQuantityChangePercent =
        baseline.unitProfit().signum() > 0
                && candidate.unitProfit().signum() > 0
                && baseline.fixedCosts().compareTo(candidate.fixedCosts()) == 0
            ? baseline
                .unitProfit()
                .divide(candidate.unitProfit(), 18, RoundingMode.HALF_EVEN)
                .subtract(BigDecimal.ONE)
                .movePointRight(2)
            : null;
    ThresholdStatus status;
    if (candidate.result(0).compareTo(target) >= 0) {
      threshold = 0;
      status = ThresholdStatus.FOUND;
    } else if (candidate.unitProfit().signum() <= 0) {
      status = ThresholdStatus.NO_FINITE_THRESHOLD;
    } else {
      BigDecimal required =
          target
              .add(candidate.fixedCosts())
              .divide(candidate.unitProfit(), 0, RoundingMode.CEILING)
              .max(BigDecimal.ZERO);
      if (required.compareTo(BigDecimal.valueOf(MAX_QUANTITY)) > 0) {
        status = ThresholdStatus.LIMIT_EXCEEDED;
      } else {
        threshold = required.intValueExact();
        if (candidate.result(threshold).compareTo(target) < 0
            || (threshold > 0 && candidate.result(threshold - 1).compareTo(target) >= 0)) {
          throw new IllegalStateException("Threshold does not satisfy exact monetary boundary");
        }
        status = ThresholdStatus.FOUND;
      }
    }
    TreeSet<Integer> quantities = new TreeSet<>();
    quantities.add(quantity);
    quantities.add(
        BigDecimal.valueOf(quantity)
            .multiply(new BigDecimal("1.2"))
            .setScale(0, RoundingMode.CEILING)
            .intValueExact());
    quantities.add(
        BigDecimal.valueOf(quantity)
            .multiply(new BigDecimal("1.6"))
            .setScale(0, RoundingMode.CEILING)
            .intValueExact());
    if (threshold != null) {
      quantities.add(threshold);
    }
    List<Scenario> scenarios = new ArrayList<>();
    for (int count : quantities) {
      if (count > MAX_QUANTITY) {
        continue;
      }
      BigDecimal result = candidate.result(count);
      scenarios.add(new Scenario(count, result, result.subtract(target)));
    }
    Boolean reachable =
        threshold == null || available == null
            ? null
            : available.compareTo(BigDecimal.valueOf(threshold)) >= 0;
    return new Comparison(
        quantity,
        target,
        scenarios,
        status,
        threshold,
        relativeQuantityChangePercent,
        reachable,
        available != null && available.compareTo(BigDecimal.valueOf(quantity)) < 0);
  }
}
