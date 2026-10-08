package ru.oritas.repricer.economics.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The only owner of monetary calculations. Inputs describe one explicit outcome and quantity. */
public final class EconomicCalculator {
  public static final int DIVISION_SCALE = 18;

  public enum Outcome {
    KEPT_PURCHASE,
    REFUSED_RETURN,
    SALEABLE_RETURN,
    DAMAGED_RETURN,
    CANCELLED,
    LOST
  }

  public enum Status {
    COMPLETE,
    INCOMPLETE
  }

  public record Charge(String code, BigDecimal amount, boolean confirmed, boolean mandatory) {
    public Charge {
      Objects.requireNonNull(code);
      if (code.isBlank()) {
        throw new IllegalArgumentException("Charge code is required");
      }
      if (amount != null) {
        checkPrecision(amount);
      }
    }
  }

  public record Input(
      Outcome outcome,
      BigDecimal sellerRevenue,
      BigDecimal costConsumed,
      BigDecimal ownExpense,
      BigDecimal taxBase,
      BigDecimal taxRate,
      List<Charge> charges) {
    public Input {
      Objects.requireNonNull(outcome);
      charges = List.copyOf(charges);
      nonnegative(costConsumed, "Cost");
      nonnegative(ownExpense, "Own expense");
      if (taxRate != null && (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) >= 0)) {
        throw new IllegalArgumentException("Tax rate must be in [0, 1)");
      }
    }
  }

  public record Result(
      Status status,
      BigDecimal sellerRevenue,
      BigDecimal costConsumed,
      BigDecimal operatingExpenses,
      BigDecimal tax,
      BigDecimal profit,
      BigDecimal margin,
      BigDecimal roi,
      boolean preliminary,
      List<String> missing) {
    public Result {
      missing = List.copyOf(missing);
    }

    public boolean complete() {
      return status == Status.COMPLETE;
    }
  }

  public record Safety(
      BigDecimal minimumMargin, BigDecimal minimumProfit, BigDecimal maximumOutcomeLoss) {
    public Safety {
      Objects.requireNonNull(minimumMargin);
      Objects.requireNonNull(minimumProfit);
      Objects.requireNonNull(maximumOutcomeLoss);
      if (minimumMargin.signum() < 0
          || minimumMargin.compareTo(BigDecimal.ONE) >= 0
          || minimumProfit.signum() < 0
          || maximumOutcomeLoss.signum() < 0) {
        throw new IllegalArgumentException("Invalid ordinary financial limits");
      }
    }
  }

  public record Check(boolean allowed, String reason, BigDecimal directLoss) {}

  public record UnitAmounts(BigDecimal income, BigDecimal cost, BigDecimal profit) {}

  public UnitAmounts perRecognizedUnit(Result result, BigDecimal units) {
    if (!result.complete() || units == null || units.signum() <= 0) {
      throw new IllegalArgumentException("A complete result and recognized quantity are required");
    }
    return new UnitAmounts(
        divide(result.sellerRevenue(), units),
        divide(result.costConsumed(), units),
        divide(result.profit(), units));
  }

  /** One factual marketplace service amount, independent of sale or payment recognition. */
  public Result serviceExpense(BigDecimal amount) {
    return serviceExpense(amount, true);
  }

  public Result serviceExpense(BigDecimal amount, boolean confirmed) {
    if (amount != null) {
      checkPrecision(amount);
    }
    return new Result(
        amount == null ? Status.INCOMPLETE : Status.COMPLETE,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        amount,
        BigDecimal.ZERO,
        amount == null ? null : checked(amount.negate()),
        null,
        null,
        !confirmed,
        amount == null ? List.of("SERVICE_AMOUNT") : List.of());
  }

  public enum ChargeUnit {
    UNIT,
    ORDER,
    SHIPMENT,
    DAY
  }

  public record TariffTerm(
      String code,
      ChargeUnit unit,
      BigDecimal fixedAmount,
      BigDecimal revenueRate,
      boolean confirmed,
      boolean mandatory,
      BigDecimal verifiedQuantity) {}

  public record PricingInput(
      Outcome outcome,
      BigDecimal basePrice,
      BigDecimal units,
      BigDecimal sellerRevenueMultiplier,
      BigDecimal sellerRevenueOffset,
      BigDecimal buyerPriceMultiplier,
      BigDecimal buyerPriceOffset,
      BigDecimal costPerUnit,
      BigDecimal ownExpensePerUnit,
      BigDecimal taxRate,
      List<TariffTerm> tariffs) {
    public PricingInput {
      tariffs = List.copyOf(tariffs);
      if (basePrice == null || basePrice.signum() <= 0 || units == null || units.signum() <= 0) {
        throw new IllegalArgumentException("Positive price and explicit quantity are required");
      }
    }
  }

  public record PriceQuote(BigDecimal buyerTotal, Input input, Result result) {}

  /** The caller selects an explicit source-proven price transformation, never seller revenue. */
  public BigDecimal priceArgumentFor(BigDecimal target, BigDecimal multiplier, BigDecimal offset) {
    if (target == null || multiplier == null || offset == null || multiplier.signum() <= 0) {
      return null;
    }
    return divide(target.subtract(offset), multiplier);
  }

  public BigDecimal transformedPrice(
      BigDecimal argument, BigDecimal multiplier, BigDecimal offset) {
    return argument == null || multiplier == null || offset == null
        ? null
        : checked(argument.multiply(multiplier).add(offset));
  }

  /** Evaluate normalized terms. Nonlinear or unconfirmed transformations must remain absent. */
  public PriceQuote quote(PricingInput pricing) {
    return quote(pricing, pricing.basePrice());
  }

  public PriceQuote quote(PricingInput pricing, BigDecimal buyerPriceArgument) {
    BigDecimal revenue =
        pricing.sellerRevenueMultiplier() == null || pricing.sellerRevenueOffset() == null
            ? null
            : pricing
                .basePrice()
                .multiply(pricing.sellerRevenueMultiplier())
                .add(pricing.sellerRevenueOffset())
                .multiply(pricing.units());
    BigDecimal buyer =
        pricing.buyerPriceMultiplier() == null
                || pricing.buyerPriceOffset() == null
                || buyerPriceArgument == null
            ? null
            : buyerPriceArgument
                .multiply(pricing.buyerPriceMultiplier())
                .add(pricing.buyerPriceOffset());
    List<Charge> charges = new ArrayList<>();
    for (TariffTerm tariff : pricing.tariffs()) {
      BigDecimal count =
          tariff.unit() == ChargeUnit.UNIT ? pricing.units() : tariff.verifiedQuantity();
      BigDecimal amount = null;
      if (count != null
          && tariff.fixedAmount() != null
          && tariff.revenueRate() != null
          && (revenue != null || tariff.revenueRate().signum() == 0)) {
        amount = tariff.fixedAmount().multiply(count);
        if (tariff.revenueRate().signum() != 0) {
          amount = amount.add(revenue.multiply(tariff.revenueRate()));
        }
      }
      charges.add(new Charge(tariff.code(), amount, tariff.confirmed(), tariff.mandatory()));
    }
    Input input =
        new Input(
            pricing.outcome(),
            revenue,
            pricing.costPerUnit() == null ? null : pricing.costPerUnit().multiply(pricing.units()),
            pricing.ownExpensePerUnit() == null
                ? null
                : pricing.ownExpensePerUnit().multiply(pricing.units()),
            revenue,
            pricing.taxRate(),
            charges);
    return new PriceQuote(buyer, input, calculate(input));
  }

  public PriceQuote quoteOutcome(
      PricingInput pricing,
      BigDecimal consumedCostRatio,
      BigDecimal ownExpenseRatio,
      BigDecimal taxBaseMultiplier,
      BigDecimal taxBaseOffset) {
    return quoteOutcome(
        pricing,
        consumedCostRatio,
        ownExpenseRatio,
        taxBaseMultiplier,
        taxBaseOffset,
        pricing.basePrice(),
        pricing.basePrice());
  }

  public PriceQuote quoteOutcome(
      PricingInput pricing,
      BigDecimal consumedCostRatio,
      BigDecimal ownExpenseRatio,
      BigDecimal taxBaseMultiplier,
      BigDecimal taxBaseOffset,
      BigDecimal buyerPriceArgument,
      BigDecimal taxPriceArgument) {
    PriceQuote regular = quote(pricing, buyerPriceArgument);
    BigDecimal cost =
        regular.input().costConsumed() == null || consumedCostRatio == null
            ? null
            : regular.input().costConsumed().multiply(consumedCostRatio);
    BigDecimal own =
        regular.input().ownExpense() == null || ownExpenseRatio == null
            ? null
            : regular.input().ownExpense().multiply(ownExpenseRatio);
    BigDecimal taxBase =
        taxBaseMultiplier == null || taxBaseOffset == null || taxPriceArgument == null
            ? null
            : taxPriceArgument
                .multiply(taxBaseMultiplier)
                .add(taxBaseOffset)
                .multiply(pricing.units());
    Input input =
        new Input(
            pricing.outcome(),
            regular.input().sellerRevenue(),
            cost,
            own,
            taxBase,
            pricing.taxRate(),
            regular.input().charges());
    return new PriceQuote(regular.buyerTotal(), input, calculate(input));
  }

  /** Boundary of a proven affine normalized tariff profile; not used for stepped tariffs. */
  public BigDecimal profitabilityBoundary(
      PricingInput pricing, BigDecimal target, boolean marginTarget) {
    Result first = quote(atPrice(pricing, BigDecimal.ONE)).result();
    Result second = quote(atPrice(pricing, BigDecimal.valueOf(2))).result();
    if (!first.complete() || !second.complete()) {
      return null;
    }
    BigDecimal firstGap =
        first.profit().subtract(marginTarget ? target.multiply(first.sellerRevenue()) : target);
    BigDecimal secondGap =
        second.profit().subtract(marginTarget ? target.multiply(second.sellerRevenue()) : target);
    BigDecimal slope = secondGap.subtract(firstGap);
    if (slope.signum() <= 0) {
      return null;
    }
    return BigDecimal.ONE.subtract(divide(firstGap, slope));
  }

  private static PricingInput atPrice(PricingInput input, BigDecimal price) {
    return new PricingInput(
        input.outcome(),
        price,
        input.units(),
        input.sellerRevenueMultiplier(),
        input.sellerRevenueOffset(),
        input.buyerPriceMultiplier(),
        input.buyerPriceOffset(),
        input.costPerUnit(),
        input.ownExpensePerUnit(),
        input.taxRate(),
        input.tariffs());
  }

  public Result calculate(Input input) {
    List<String> missing = new ArrayList<>();
    required(input.sellerRevenue(), "SELLER_REVENUE", missing);
    required(input.costConsumed(), "COST", missing);
    required(input.ownExpense(), "OWN_EXPENSE", missing);
    required(input.taxBase(), "TAX_BASE", missing);
    required(input.taxRate(), "TAX_RATE", missing);
    BigDecimal expenses = input.ownExpense() == null ? BigDecimal.ZERO : input.ownExpense();
    boolean preliminary = false;
    for (Charge charge : input.charges()) {
      if (charge.amount() == null) {
        if (charge.mandatory()) {
          missing.add(charge.code());
        }
      } else {
        expenses = expenses.add(charge.amount());
        preliminary |= !charge.confirmed();
      }
    }
    if (!missing.isEmpty()) {
      return new Result(
          Status.INCOMPLETE,
          input.sellerRevenue(),
          input.costConsumed(),
          input.ownExpense() == null ? null : checked(expenses),
          input.taxBase() == null || input.taxRate() == null
              ? null
              : checked(input.taxBase().multiply(input.taxRate())),
          null,
          null,
          null,
          preliminary,
          missing);
    }
    BigDecimal tax = checked(input.taxBase().multiply(input.taxRate()));
    BigDecimal profit =
        checked(
            input.sellerRevenue().subtract(input.costConsumed()).subtract(expenses).subtract(tax));
    BigDecimal margin = positiveRatio(profit, input.sellerRevenue());
    BigDecimal roi = positiveRatio(profit, input.costConsumed());
    return new Result(
        Status.COMPLETE,
        input.sellerRevenue(),
        input.costConsumed(),
        checked(expenses),
        tax,
        profit,
        margin,
        roi,
        preliminary,
        List.of());
  }

  /** Independent confirmed return movements; no seller expense is reversed without its source. */
  public Result returnMovements(
      BigDecimal refundedRevenue,
      BigDecimal restoredCost,
      BigDecimal reversedTax,
      List<String> missingProofs) {
    nonnegative(refundedRevenue, "Refunded revenue");
    nonnegative(restoredCost, "Restored cost");
    nonnegative(reversedTax, "Reversed tax");
    List<String> missing = new ArrayList<>(missingProofs);
    required(refundedRevenue, "REFUNDED_REVENUE", missing);
    required(restoredCost, "RESTORED_COST", missing);
    required(reversedTax, "ORIGINAL_TAX_BASIS", missing);
    BigDecimal income = refundedRevenue == null ? null : refundedRevenue.negate();
    BigDecimal cost = restoredCost == null ? null : restoredCost.negate();
    BigDecimal tax = reversedTax == null ? null : reversedTax.negate();
    BigDecimal profit =
        income == null || cost == null || tax == null
            ? null
            : checked(income.subtract(cost).subtract(tax));
    return new Result(
        missing.isEmpty() ? Status.COMPLETE : Status.INCOMPLETE,
        income,
        cost,
        BigDecimal.ZERO,
        tax,
        profit,
        null,
        null,
        false,
        missing);
  }

  /** A temporary floor replaces only kept-purchase profit and margin limits. */
  public Check check(Result result, Outcome outcome, Safety safety, BigDecimal temporaryFloor) {
    if (!result.complete()) {
      return new Check(false, "INCOMPLETE_ECONOMICS", null);
    }
    BigDecimal loss = directLoss(result.profit());
    if (outcome != Outcome.KEPT_PURCHASE) {
      return new Check(
          loss.compareTo(safety.maximumOutcomeLoss()) <= 0,
          loss.compareTo(safety.maximumOutcomeLoss()) <= 0 ? "SAFE" : "OUTCOME_LOSS",
          loss);
    }
    if (temporaryFloor != null) {
      boolean allowed = result.profit().compareTo(temporaryFloor) >= 0;
      return new Check(allowed, allowed ? "TEMPORARY_PERMISSION" : "TEMPORARY_FLOOR", loss);
    }
    if (result.sellerRevenue().signum() <= 0) {
      return new Check(false, "MARGIN_UNDEFINED", loss);
    }
    if (result.profit().compareTo(safety.minimumProfit()) < 0) {
      return new Check(false, "MINIMUM_PROFIT", loss);
    }
    boolean allowed =
        result.profit().compareTo(safety.minimumMargin().multiply(result.sellerRevenue())) >= 0;
    return new Check(allowed, allowed ? "SAFE" : "MINIMUM_MARGIN", loss);
  }

  public BigDecimal directLoss(BigDecimal fullResult) {
    return checked(fullResult.negate().max(BigDecimal.ZERO));
  }

  public BigDecimal liability(BigDecimal finalLoss, BigDecimal remainingMaximumLoss) {
    nonnegative(finalLoss, "Recognized loss");
    nonnegative(remainingMaximumLoss, "Remaining liability");
    return checked(
        Objects.requireNonNull(finalLoss).add(Objects.requireNonNull(remainingMaximumLoss)));
  }

  /** Bounds a mutually exclusive set of outcomes for a confirmed total external quantity. */
  public BigDecimal maximumLiability(List<Input> outcomes, BigDecimal externallyBoundedQuantity) {
    if (outcomes.isEmpty()
        || outcomes.size() > 2000
        || externallyBoundedQuantity == null
        || externallyBoundedQuantity.signum() <= 0) {
      throw new IllegalArgumentException(
          "Complete outcomes and a positive external bound are required");
    }
    BigDecimal unitMaximum = BigDecimal.ZERO;
    for (Input outcome : outcomes) {
      Result result = calculate(outcome);
      if (!result.complete()) {
        throw new IllegalArgumentException("An incomplete outcome cannot bound liability");
      }
      unitMaximum = unitMaximum.max(directLoss(result.profit()));
    }
    return checked(unitMaximum.multiply(externallyBoundedQuantity));
  }

  public BigDecimal priceAtStep(BigDecimal price, BigDecimal step, RoundingMode rounding) {
    if (price.signum() <= 0 || step.signum() <= 0) {
      throw new IllegalArgumentException("Price and step must be positive");
    }
    return checked(price.divide(step, 0, rounding).multiply(step));
  }

  public static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
    return numerator.divide(denominator, DIVISION_SCALE, RoundingMode.HALF_EVEN);
  }

  public static BigDecimal checked(BigDecimal amount) {
    checkPrecision(amount);
    return amount;
  }

  private static BigDecimal positiveRatio(BigDecimal numerator, BigDecimal denominator) {
    return denominator.signum() > 0 ? divide(numerator, denominator) : null;
  }

  private static void required(BigDecimal value, String code, List<String> missing) {
    if (value == null) {
      missing.add(code);
    } else {
      checkPrecision(value);
    }
  }

  private static void nonnegative(BigDecimal value, String label) {
    if (value != null && value.signum() < 0) {
      throw new IllegalArgumentException(label + " cannot be negative");
    }
  }

  private static void checkPrecision(BigDecimal value) {
    if (value.precision() - value.scale() > 26) {
      throw new ArithmeticException("Monetary amount exceeds supported range");
    }
  }
}
