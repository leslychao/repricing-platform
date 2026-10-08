package ru.oritas.repricer.economics.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Charge;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Input;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Outcome;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Safety;

class EconomicCalculatorTest {
  private final EconomicCalculator calculator = new EconomicCalculator();

  @Test
  void confirmedReturnMovementsKeepUnknownComponentsAndNeverReverseOwnExpense() {
    var withoutStock =
        calculator.returnMovements(money("1000"), BigDecimal.ZERO, money("60"), List.of());
    assertThat(withoutStock.profit()).isEqualByComparingTo("-940");
    assertThat(withoutStock.costConsumed()).isZero();
    var withStock = calculator.returnMovements(money("1000"), money("600"), money("60"), List.of());
    assertThat(withStock.profit()).isEqualByComparingTo("-340");
    assertThat(withStock.operatingExpenses()).isZero();
    var unknown = calculator.returnMovements(money("1000"), null, null, List.of());
    assertThat(unknown.complete()).isFalse();
    assertThat(unknown.profit()).isNull();
  }

  @Test
  void computesSpecificationExampleWithoutTreatingPaymentAsAnotherIncome() {
    var result =
        calculator.calculate(
            new Input(
                Outcome.KEPT_PURCHASE,
                money("1000"),
                money("600"),
                money("40"),
                money("1000"),
                money("0.06"),
                List.of(new Charge("COMMISSION", money("100"), true, true))));
    assertThat(result.profit()).isEqualByComparingTo("200");
    assertThat(result.tax()).isEqualByComparingTo("60");
    assertThat(result.margin()).isEqualByComparingTo("0.2");
  }

  @Test
  void displayedRoundingCannotSatisfyTheExactMinimumMargin() {
    var result =
        calculator.calculate(
            new Input(
                Outcome.KEPT_PURCHASE,
                money("1000"),
                money("880.05"),
                BigDecimal.ZERO,
                money("1000"),
                BigDecimal.ZERO,
                List.of()));
    assertThat(
            calculator
                .check(
                    result,
                    Outcome.KEPT_PURCHASE,
                    new Safety(money("0.12"), BigDecimal.ZERO, BigDecimal.ZERO),
                    null)
                .allowed())
        .isFalse();
    assertThat(result.margin()).isEqualByComparingTo("0.11995");
  }

  @Test
  void mandatoryUnknownExpenseBlocksWhileUnknownOptionalBenefitMayBeExcluded() {
    Input required =
        new Input(
            Outcome.KEPT_PURCHASE,
            money("1000"),
            money("600"),
            BigDecimal.ZERO,
            money("1000"),
            BigDecimal.ZERO,
            List.of(new Charge("DELIVERY", null, false, true)));
    assertThat(calculator.calculate(required).complete()).isFalse();
    assertThat(calculator.calculate(required).profit()).isNull();
    Input optional =
        new Input(
            Outcome.KEPT_PURCHASE,
            money("1000"),
            money("600"),
            BigDecimal.ZERO,
            money("1000"),
            BigDecimal.ZERO,
            List.of(new Charge("UNCONFIRMED_BONUS", null, false, false)));
    assertThat(calculator.calculate(optional).profit()).isEqualByComparingTo("400");
  }

  @Test
  void temporaryFloorReplacesKeptMinimaButDoesNotRelaxReturnLossLimit() {
    Safety safety = new Safety(money("0.4"), money("300"), money("10"));
    var kept =
        calculator.calculate(
            new Input(
                Outcome.KEPT_PURCHASE,
                money("100"),
                money("150"),
                BigDecimal.ZERO,
                money("100"),
                BigDecimal.ZERO,
                List.of()));
    assertThat(calculator.check(kept, Outcome.KEPT_PURCHASE, safety, money("-60")).allowed())
        .isTrue();
    assertThat(calculator.check(kept, Outcome.REFUSED_RETURN, safety, money("-60")).allowed())
        .isFalse();
  }

  @Test
  void undefinedRatioDoesNotEraseKnownProfitAndOverflowIsAnError() {
    var result =
        calculator.calculate(
            new Input(
                Outcome.KEPT_PURCHASE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                money("15"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of()));
    assertThat(result.profit()).isEqualByComparingTo("-15");
    assertThat(result.margin()).isNull();
    assertThat(result.roi()).isNull();
    assertThatThrownBy(
            () ->
                calculator.calculate(
                    new Input(
                        Outcome.KEPT_PURCHASE,
                        money("100000000000000000000000000"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        List.of())))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void comparisonUsesIntegerThresholdAndNeverClipsItToAvailableStock() {
    var comparison =
        new QuantityComparison()
            .compare(
                new QuantityComparison.LinearCase(money("200"), BigDecimal.ZERO),
                new QuantityComparison.LinearCase(money("150"), BigDecimal.ZERO),
                100,
                money("120"));
    assertThat(comparison.threshold()).isEqualTo(134);
    assertThat(comparison.relativeQuantityChangePercent())
        .isEqualByComparingTo("33.3333333333333333");
    assertThat(comparison.reachableWithStock()).isFalse();
    assertThat(comparison.scenarios())
        .anySatisfy(
            s -> {
              assertThat(s.quantity()).isEqualTo(160);
              assertThat(s.result()).isEqualByComparingTo("24000");
            });
  }

  @Test
  void favorableUnitResultCanReachTheBaselineWithFewerUnits() {
    var comparison =
        new QuantityComparison()
            .compare(
                new QuantityComparison.LinearCase(money("200"), BigDecimal.ZERO),
                new QuantityComparison.LinearCase(money("250"), BigDecimal.ZERO),
                100,
                null);
    assertThat(comparison.threshold()).isEqualTo(80);
    assertThat(comparison.relativeQuantityChangePercent()).isEqualByComparingTo("-20");
    assertThat(comparison.reachableWithStock()).isNull();
  }

  @Test
  void nonpositiveUnitProfitDoesNotUseThePositiveDivisionFormula() {
    var comparison =
        new QuantityComparison()
            .compare(
                new QuantityComparison.LinearCase(money("200"), BigDecimal.ZERO),
                new QuantityComparison.LinearCase(money("-50"), BigDecimal.ZERO),
                100,
                null);
    assertThat(comparison.thresholdStatus())
        .isEqualTo(QuantityComparison.ThresholdStatus.NO_FINITE_THRESHOLD);
    assertThat(comparison.relativeQuantityChangePercent()).isNull();
  }

  private static BigDecimal money(String value) {
    return new BigDecimal(value);
  }

  @Test
  void externalLiabilityUsesWorstCompleteOutcomeAndNeverOffsetsItWithProfit() {
    var kept =
        new EconomicCalculator.Input(
            Outcome.KEPT_PURCHASE,
            money("150"),
            money("50"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            java.util.List.of());
    var returned =
        new EconomicCalculator.Input(
            Outcome.DAMAGED_RETURN,
            BigDecimal.ZERO,
            money("50"),
            money("10"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            java.util.List.of());
    assertThat(calculator.maximumLiability(java.util.List.of(kept, returned), money("4")))
        .isEqualByComparingTo("240");
  }
}
