package ru.oritas.repricer.economics.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;

/** Proportional allocation retains every monetary unit in a stable recipient order. */
public final class Allocation {
  public record Weight(UUID recipientId, BigDecimal weight) {}

  public record Part(UUID recipientId, BigDecimal amount) {}

  public List<Part> allocate(BigDecimal total, List<Weight> weights) {
    if (weights.isEmpty()) {
      throw new IllegalArgumentException("Allocation recipients are required");
    }
    List<Weight> ordered =
        weights.stream().sorted(Comparator.comparing(Weight::recipientId)).toList();
    BigDecimal denominator = BigDecimal.ZERO;
    UUID lastPositive = null;
    UUID previous = null;
    for (Weight item : ordered) {
      if (item.weight().signum() < 0 || item.recipientId().equals(previous)) {
        throw new IllegalArgumentException("Weights must be nonnegative and recipients unique");
      }
      denominator = denominator.add(item.weight());
      if (item.weight().signum() > 0) {
        lastPositive = item.recipientId();
      }
      previous = item.recipientId();
    }
    if (denominator.signum() <= 0) {
      throw new IllegalArgumentException("Positive allocation basis is required");
    }
    List<Part> result = new ArrayList<>();
    BigDecimal allocated = BigDecimal.ZERO;
    BigDecimal cumulativeWeight = BigDecimal.ZERO;
    for (int index = 0; index < ordered.size(); index++) {
      Weight item = ordered.get(index);
      cumulativeWeight = cumulativeWeight.add(item.weight());
      BigDecimal amount;
      if (item.weight().signum() == 0) {
        amount = BigDecimal.ZERO;
      } else if (item.recipientId().equals(lastPositive)) {
        amount = total.subtract(allocated);
      } else {
        BigDecimal cumulativeAmount =
            EconomicCalculator.divide(total.multiply(cumulativeWeight), denominator);
        // Products can be finer than the division scale. Rounded coverage cannot exceed the
        // actual source amount and make the final recipient cross zero.
        cumulativeAmount =
            total.signum() >= 0 ? cumulativeAmount.min(total) : cumulativeAmount.max(total);
        amount = cumulativeAmount.subtract(allocated);
      }
      result.add(new Part(item.recipientId(), amount));
      allocated = allocated.add(amount);
    }
    return List.copyOf(result);
  }

  /**
   * The next share follows cumulative coverage; only the confirmed final part gets the remainder.
   */
  public BigDecimal returnCost(
      BigDecimal originalCost,
      BigDecimal originalQuantity,
      BigDecimal cumulativeReturnedQuantity,
      BigDecimal alreadyRestored,
      boolean lastPart) {
    if (originalQuantity.signum() <= 0
        || cumulativeReturnedQuantity.signum() <= 0
        || cumulativeReturnedQuantity.compareTo(originalQuantity) > 0
        || (lastPart && cumulativeReturnedQuantity.compareTo(originalQuantity) != 0)
        || originalCost.signum() < 0
        || alreadyRestored.signum() < 0
        || alreadyRestored.compareTo(originalCost) > 0) {
      throw new IllegalArgumentException("Return must be covered by the original sale");
    }
    BigDecimal cumulativeAmount =
        lastPart
            ? originalCost
            : EconomicCalculator.divide(
                    originalCost.multiply(cumulativeReturnedQuantity), originalQuantity)
                .min(originalCost);
    BigDecimal share = cumulativeAmount.subtract(alreadyRestored);
    if (share.signum() < 0) {
      throw new IllegalArgumentException("Restored amount exceeds cumulative returned coverage");
    }
    return share;
  }
}
