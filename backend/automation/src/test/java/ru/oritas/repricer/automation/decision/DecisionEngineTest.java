package ru.oritas.repricer.automation.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;

class DecisionEngineTest {
  private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private final DecisionEngine engine = new DecisionEngine();

  @Test
  void explicitLowStockRaiseOverridesOrdinaryHoldAndNeverCompounds() {
    var context = stockRaiseContext("100", "105");
    var baseline = stockCandidate("100", "100", "100", "100", "10");
    var raised = stockCandidate("105", "100", "105", "100", "10");
    var first = engine.decide(context, List.of(baseline, raised));
    assertEquals(raised.id(), first.selectedCandidate());
    assertEquals("LOW_STOCK_RAISE", first.reason());
    var observed = stockCandidate("105", "105", "105", "105", "10");
    var compounded = stockCandidate("110.25", "105", "110.25", "105", "10");
    var next = engine.decide(context, List.of(observed, compounded));
    assertEquals(observed.id(), next.selectedCandidate());
    assertEquals("LOW_STOCK_TARGET_ALREADY_REACHED", next.reason());
  }

  @Test
  void ineffectiveRaiseAndRepeatedStepBypassAreRejected() {
    var held =
        engine.decide(
            stockRaiseContext("100", "105"),
            List.of(
                stockCandidate("100", "100", "80", "80", "10"),
                stockCandidate("105", "100", "80", "80", "10")));
    assertNull(held.selectedCandidate());
    assertEquals("LOW_STOCK_RAISE_UNAVAILABLE", held.reason());
    var series =
        engine.decide(
            stockRaiseContext("100", "120"),
            List.of(
                stockCandidate("110", "110", "110", "110", "10"),
                stockCandidate("120", "110", "120", "110", "10")));
    assertNull(series.selectedCandidate());
    assertEquals("LOW_STOCK_RAISE_UNAVAILABLE", series.reason());
  }

  @Test
  void lowStockProtectsBuyerPaymentEvenWhenTheStrategyUsesBasePrice() {
    var result =
        engine.decide(
            stockRaiseContext("100", "105"),
            List.of(stockCandidate("105", "100", "79", "80", "10")));
    assertNull(result.selectedCandidate());
    assertEquals("MOVEMENT_OR_STOCK_PROTECTION", result.evaluations().getFirst().reason());
  }

  private static DecisionEngine.Context stockRaiseContext(String base, String target) {
    return new DecisionEngine.Context(
        hold(),
        null,
        Set.of(PolicySettings.Operation.SET_BASE_PRICE),
        Set.of(),
        Set.of(TARGET),
        true,
        false,
        true,
        true,
        false,
        false,
        true,
        DecisionEngine.MAX_WORK,
        false,
        BigDecimal.ZERO,
        new DecisionEngine.StockRaise(decimal(base), decimal(target)));
  }

  private static DecisionEngine.Candidate stockCandidate(
      String price, String current, String buyer, String currentBuyer, String maximumStep) {
    var source = effect(price, "COMMON");
    var value =
        new DecisionEngine.Effect(
            TARGET,
            "COMMON",
            decimal(price),
            decimal(current),
            decimal(price),
            decimal(current),
            decimal(current),
            decimal("1"),
            decimal("200"),
            decimal(maximumStep),
            decimal("100"),
            source.outcomes(),
            true,
            true,
            source.placementId(),
            decimal(buyer),
            decimal(currentBuyer));
    boolean unchanged = decimal(price).compareTo(decimal(current)) == 0;
    var path =
        unchanged
            ? List.<DecisionEngine.Step>of()
            : List.of(
                new DecisionEngine.Step(
                    PolicySettings.Operation.SET_BASE_PRICE,
                    TARGET,
                    null,
                    decimal(price),
                    true,
                    true,
                    BigDecimal.ZERO,
                    decimal(price).subtract(decimal(current)).abs()));
    return new DecisionEngine.Candidate(
        UUID.randomUUID(), List.of(value), Set.of(), path, unchanged, true, Map.of());
  }

  @Test
  void readyOfferWorkloadChecks128CandidatesAcross200Placements(TestReporter reporter) {
    List<UUID> placements =
        IntStream.range(0, 200).mapToObj(index -> new UUID(0, index + 10)).toList();
    var rule = workloadRule();
    long[] durations = new long[20];
    for (int run = 0; run < durations.length; run++) {
      Iterable<DecisionEngine.Candidate> candidates =
          () ->
              IntStream.range(0, 128)
                  .mapToObj(index -> workloadCandidate(index, placements))
                  .iterator();
      AtomicInteger visited = new AtomicInteger();
      long started = System.nanoTime();
      var result =
          engine.decideStreaming(
              context(rule, true, false),
              candidates,
              (candidate, evaluation) -> {
                assertEquals(1200, evaluation.economics().size());
                assertTrue(evaluation.safe());
                assertTrue(
                    evaluation.economics().stream().allMatch(EconomicCalculator.Result::complete));
                visited.incrementAndGet();
              },
              () -> System.nanoTime() - started < Duration.ofSeconds(120).toNanos());
      durations[run] = System.nanoTime() - started;
      assertEquals(128, visited.get());
      assertEquals("CALCULATED", result.status());
      assertEquals(new UUID(1, 99), result.selectedCandidate());
      assertTrue(result.targetAchieved());
      assertEquals(128, result.evaluations().size());
      assertEquals(0, result.evaluations().getFirst().economics().size());
      assertTrue(result.workUsed() <= DecisionEngine.MAX_WORK);
    }
    Arrays.sort(durations);
    long percentile95 = durations[18];
    reporter.publishEntry(
        "profile",
        "pure ready-input engine: 128 candidates, 200 placements, 6 outcomes, 4 charges, 20 runs");
    reporter.publishEntry("p95_ms", Long.toString(Duration.ofNanos(percentile95).toMillis()));
    reporter.publishEntry("maximum_ms", Long.toString(Duration.ofNanos(durations[19]).toMillis()));
    assertTrue(percentile95 <= Duration.ofSeconds(30).toNanos());
    assertTrue(durations[19] <= Duration.ofSeconds(120).toNanos());
  }

  @Test
  void workExhaustionCannotPublishTheBestAlreadyEvaluatedCandidate() {
    var baseline = context(workloadRule(), true, false);
    var limited =
        new DecisionEngine.Context(
            baseline.rule(),
            baseline.competitorTarget(),
            baseline.allowedOperations(),
            baseline.currentParticipations(),
            baseline.originalTargets(),
            true,
            false,
            true,
            false,
            false,
            false,
            true,
            650_000,
            false,
            BigDecimal.ZERO,
            null);
    var placements = IntStream.range(0, 200).mapToObj(index -> new UUID(0, index + 10)).toList();
    Iterable<DecisionEngine.Candidate> candidates =
        () ->
            IntStream.range(0, 128)
                .mapToObj(index -> workloadCandidate(index, placements))
                .iterator();
    var result =
        engine.decideStreaming(limited, candidates, (candidate, evaluation) -> {}, () -> true);
    assertTrue(result.workUsed() > limited.maximumWork());
    assertTrue(result.evaluations().size() < 128);
    assertTrue(result.evaluations().stream().anyMatch(DecisionEngine.Evaluation::reachesTarget));
    assertIncompleteWithoutSelection(result);
  }

  @Test
  void deadlineDuringSearchAndImmediatelyAfterTheLastCandidateCannotPublishAPartialChoice() {
    var placements = List.of(new UUID(0, 10));
    for (int evaluationsBeforeExpiry : List.of(100, 128)) {
      AtomicInteger evaluated = new AtomicInteger();
      Iterable<DecisionEngine.Candidate> candidates =
          () ->
              IntStream.range(0, 128)
                  .mapToObj(index -> workloadCandidate(index, placements))
                  .iterator();
      var result =
          engine.decideStreaming(
              context(workloadRule(), true, false),
              candidates,
              (candidate, evaluation) -> evaluated.incrementAndGet(),
              () -> evaluated.get() < evaluationsBeforeExpiry);
      assertEquals(evaluationsBeforeExpiry, evaluated.get());
      assertTrue(result.evaluations().stream().anyMatch(DecisionEngine.Evaluation::reachesTarget));
      assertIncompleteWithoutSelection(result);
    }
  }

  @Test
  void generatorPreparationExhaustionAfterTheLastCandidateStillPreventsSelection() {
    var generated = new AtomicInteger();
    var finished = new AtomicBoolean();
    var source =
        new DecisionEngine.SearchSource() {
          @Override
          public long preparationWork() {
            return finished.get() ? DecisionEngine.MAX_WORK : 0;
          }

          @Override
          public Iterator<DecisionEngine.Candidate> iterator() {
            var values =
                IntStream.range(0, 128)
                    .mapToObj(index -> workloadCandidate(index, List.of(new UUID(0, 10))))
                    .iterator();
            return new Iterator<>() {
              @Override
              public boolean hasNext() {
                boolean available = values.hasNext();
                if (!available) {
                  finished.set(true);
                }
                return available;
              }

              @Override
              public DecisionEngine.Candidate next() {
                var candidate = values.next();
                generated.incrementAndGet();
                return candidate;
              }
            };
          }
        };
    var result =
        engine.decideStreaming(
            context(workloadRule(), true, false),
            source,
            (candidate, evaluation) -> {},
            () -> true);
    assertEquals(128, generated.get());
    assertEquals(128, result.evaluations().size());
    assertTrue(result.workUsed() > DecisionEngine.MAX_WORK);
    assertIncompleteWithoutSelection(result);
  }

  private static void assertIncompleteWithoutSelection(DecisionEngine.Decision result) {
    assertEquals("INCOMPLETE_SEARCH", result.status());
    assertEquals("SEARCH_LIMIT", result.reason());
    assertNull(result.selectedCandidate());
    assertFalse(result.targetAchieved());
    assertFalse(result.requiresManualConfirmation());
  }

  private static PolicySettings.Rule workloadRule() {
    return new PolicySettings.Rule(
        PolicySettings.Strategy.HOLD_PRICE,
        PolicySettings.PriceKind.BASE_SELLER,
        decimal("1100"),
        decimal("1099"),
        decimal("1101"),
        null,
        PolicySettings.Unreachable.NEAREST_SAFE,
        null,
        null,
        PolicySettings.PromoMode.PRESERVE,
        Set.of(),
        Set.of());
  }

  private static DecisionEngine.Candidate workloadCandidate(int index, List<UUID> placements) {
    BigDecimal price = BigDecimal.valueOf(1000L + index);
    var charges =
        List.of(
            new EconomicCalculator.Charge(
                "COMMISSION", price.multiply(decimal("0.10")), true, true),
            new EconomicCalculator.Charge("LOGISTICS", decimal("25"), true, true),
            new EconomicCalculator.Charge("ACQUIRING", price.multiply(decimal("0.02")), true, true),
            new EconomicCalculator.Charge("PROCESSING", decimal("5"), true, true));
    List<DecisionEngine.EconomicOutcome> outcomes = new ArrayList<>();
    for (var outcome : EconomicCalculator.Outcome.values()) {
      boolean kept = outcome == EconomicCalculator.Outcome.KEPT_PURCHASE;
      outcomes.add(
          new DecisionEngine.EconomicOutcome(
              new EconomicCalculator.Input(
                  outcome,
                  kept ? price : BigDecimal.ZERO,
                  decimal("400"),
                  decimal("10"),
                  kept ? price : BigDecimal.ZERO,
                  decimal("0.06"),
                  charges),
              new EconomicCalculator.Safety(decimal("0.10"), decimal("50"), decimal("1000")),
              null));
    }
    var effects =
        placements.stream()
            .map(
                placement ->
                    new DecisionEngine.Effect(
                        TARGET,
                        "COMMON",
                        price,
                        decimal("1000"),
                        price,
                        decimal("1000"),
                        decimal("1000"),
                        decimal("1"),
                        decimal("2000"),
                        decimal("500"),
                        decimal("1000"),
                        outcomes,
                        true,
                        true,
                        placement,
                        price,
                        decimal("1000")))
            .toList();
    var path =
        index == 0
            ? List.<DecisionEngine.Step>of()
            : List.of(
                new DecisionEngine.Step(
                    PolicySettings.Operation.SET_BASE_PRICE,
                    TARGET,
                    null,
                    price,
                    true,
                    true,
                    decimal("600"),
                    BigDecimal.valueOf(index)));
    return new DecisionEngine.Candidate(
        new UUID(1, index), effects, Set.of(), path, index == 0, true, Map.of());
  }

  @Test
  void incompleteSearchNeverSelectsEvenAnAlreadySafeCandidate() {
    var decision =
        engine.decide(
            context(hold(), false, false), List.of(candidate("100", Set.of(), List.of())));
    assertEquals("INCOMPLETE_SEARCH", decision.status());
    assertNull(decision.selectedCandidate());
  }

  @Test
  void currentStateWinsWhenBothPricesStayInsideTheTargetCorridor() {
    var current = candidate("100", Set.of(), List.of());
    var changed =
        candidate(
            "101",
            Set.of(),
            List.of(
                new DecisionEngine.Step(
                    PolicySettings.Operation.SET_BASE_PRICE,
                    TARGET,
                    null,
                    decimal("101"),
                    true,
                    true,
                    BigDecimal.ZERO,
                    BigDecimal.ONE)));
    var decision = engine.decide(context(hold(), true, false), List.of(changed, current));
    assertEquals(current.id(), decision.selectedCandidate());
    assertEquals("NO_CHANGE_REQUIRED", decision.reason());
  }

  @Test
  void joiningAPromotionRequiresConfirmationEvenWhenFullyProfitable() {
    UUID promotion = UUID.randomUUID();
    var rule =
        new PolicySettings.Rule(
            PolicySettings.Strategy.HOLD_PRICE,
            PolicySettings.PriceKind.BASE_SELLER,
            decimal("100"),
            decimal("99"),
            decimal("101"),
            null,
            PolicySettings.Unreachable.KEEP_SAFE,
            null,
            null,
            PolicySettings.PromoMode.ENSURE_SELECTED,
            Set.of(promotion),
            Set.of());
    var candidate =
        candidate(
            "100",
            Set.of(promotion),
            List.of(
                new DecisionEngine.Step(
                    PolicySettings.Operation.JOIN_PROMO,
                    TARGET,
                    promotion,
                    decimal("100"),
                    true,
                    true,
                    BigDecimal.ZERO,
                    BigDecimal.ONE)));
    var decision = engine.decide(context(rule, true, false), List.of(candidate));
    assertEquals("CALCULATED", decision.status());
    assertTrue(decision.requiresManualConfirmation());
  }

  @Test
  void conflictingPricesForSharedTargetAreRejectedAcrossPlacements() {
    var first = effect("100", "FBO");
    var second = effect("101", "FBS");
    var candidate =
        new DecisionEngine.Candidate(
            UUID.randomUUID(),
            List.of(first, second),
            Set.of(),
            List.of(),
            false,
            true,
            java.util.Map.of());
    var decision = engine.decide(context(hold(), true, false), List.of(candidate));
    assertNull(decision.selectedCandidate());
    assertEquals("CONFLICTING_COMMON_PRICE", decision.evaluations().getFirst().reason());
  }

  @Test
  void protectiveCorrectionChoosesTheSmallestRepairAndMayBypassSignificanceOnly() {
    var rule =
        new PolicySettings.Rule(
            PolicySettings.Strategy.HOLD_PRICE,
            PolicySettings.PriceKind.BASE_SELLER,
            decimal("120"),
            decimal("120"),
            decimal("120"),
            null,
            PolicySettings.Unreachable.KEEP_SAFE,
            null,
            null,
            PolicySettings.PromoMode.PRESERVE,
            Set.of(),
            Set.of());
    var context =
        new DecisionEngine.Context(
            rule,
            null,
            Set.of(PolicySettings.Operation.SET_BASE_PRICE),
            Set.of(),
            Set.of(TARGET),
            true,
            false,
            true,
            false,
            false,
            false,
            true,
            DecisionEngine.MAX_WORK,
            true,
            decimal("5"),
            null);
    var baseline = repairCandidate("100");
    var minimum = repairCandidate("101");
    var target = repairCandidate("120");
    var decision = engine.decide(context, List.of(baseline, target, minimum));
    assertEquals("PROTECTIVE_CORRECTION", decision.reason());
    assertEquals(minimum.id(), decision.selectedCandidate());
    assertEquals(false, decision.targetAchieved());
  }

  private static DecisionEngine.Candidate repairCandidate(String price) {
    var source = effect(price, "FBO");
    var outcome =
        new DecisionEngine.EconomicOutcome(
            source.outcomes().getFirst().input(),
            new EconomicCalculator.Safety(BigDecimal.ZERO, decimal("51"), BigDecimal.ZERO),
            null);
    var repaired =
        new DecisionEngine.Effect(
            source.targetId(),
            source.segment(),
            source.basePrice(),
            source.currentBasePrice(),
            source.comparedPrice(),
            source.currentComparedPrice(),
            source.currentSellerRevenue(),
            source.minimumPrice(),
            source.maximumPrice(),
            source.maximumStep(),
            source.remainingMovement(),
            List.of(outcome),
            true,
            true,
            source.placementId(),
            source.buyerPrice(),
            source.currentBuyerPrice());
    var movement = decimal(price).subtract(decimal("100")).abs();
    return new DecisionEngine.Candidate(
        UUID.randomUUID(),
        List.of(repaired),
        Set.of(),
        price.equals("100")
            ? List.of()
            : List.of(
                new DecisionEngine.Step(
                    PolicySettings.Operation.SET_BASE_PRICE,
                    TARGET,
                    null,
                    decimal(price),
                    true,
                    true,
                    BigDecimal.ZERO,
                    movement)),
        price.equals("100"),
        true,
        java.util.Map.of());
  }

  @Test
  void repeatedPlacementsDoNotDuplicateTheSameCommercialDeviation() {
    var first = effect("110", "common");
    var second =
        new DecisionEngine.Effect(
            first.targetId(),
            first.segment(),
            first.basePrice(),
            first.currentBasePrice(),
            first.comparedPrice(),
            first.currentComparedPrice(),
            first.currentSellerRevenue(),
            first.minimumPrice(),
            first.maximumPrice(),
            first.maximumStep(),
            first.remainingMovement(),
            first.outcomes(),
            true,
            true,
            UUID.randomUUID(),
            first.buyerPrice(),
            first.currentBuyerPrice());
    var candidate =
        new DecisionEngine.Candidate(
            UUID.randomUUID(),
            List.of(first, second),
            Set.of(),
            List.of(),
            false,
            true,
            java.util.Map.of());
    var result = engine.decide(context(hold(), true, false), List.of(candidate));
    assertEquals(1, result.evaluations().getFirst().comparedPrices().size());
    assertEquals(0, decimal("9").compareTo(result.evaluations().getFirst().totalDeviation()));
    assertEquals(2, result.evaluations().getFirst().economics().size());
  }

  @Test
  void safetyIsCheckedAfterPriceStepRounding() {
    var input =
        new EconomicCalculator.Input(
            EconomicCalculator.Outcome.KEPT_PURCHASE,
            decimal("100"),
            decimal("88.005"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of());
    var outcome =
        new DecisionEngine.EconomicOutcome(
            input,
            new EconomicCalculator.Safety(decimal("0.12"), BigDecimal.ZERO, BigDecimal.ZERO),
            null);
    var regular = effect("100", "FBO");
    var rounded =
        new DecisionEngine.Effect(
            TARGET,
            "FBO",
            regular.basePrice(),
            regular.currentBasePrice(),
            regular.comparedPrice(),
            regular.currentComparedPrice(),
            regular.currentSellerRevenue(),
            regular.minimumPrice(),
            regular.maximumPrice(),
            regular.maximumStep(),
            regular.remainingMovement(),
            List.of(outcome),
            true,
            true,
            regular.placementId(),
            regular.buyerPrice(),
            regular.currentBuyerPrice());
    var decision =
        engine.decide(
            context(hold(), true, false),
            List.of(
                new DecisionEngine.Candidate(
                    UUID.randomUUID(),
                    List.of(rounded),
                    Set.of(),
                    List.of(),
                    true,
                    true,
                    java.util.Map.of())));
    assertNull(decision.selectedCandidate());
    assertEquals("MINIMUM_MARGIN", decision.evaluations().getFirst().reason());
  }

  private static DecisionEngine.Candidate candidate(
      String price, Set<UUID> promotions, List<DecisionEngine.Step> steps) {
    return new DecisionEngine.Candidate(
        UUID.randomUUID(),
        List.of(effect(price, "FBO")),
        promotions,
        steps,
        price.equals("100") && steps.isEmpty(),
        true,
        java.util.Map.of());
  }

  private static DecisionEngine.Effect effect(String price, String segment) {
    var input =
        new EconomicCalculator.Input(
            EconomicCalculator.Outcome.KEPT_PURCHASE,
            decimal(price),
            decimal("50"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            List.of());
    var outcome =
        new DecisionEngine.EconomicOutcome(
            input,
            new EconomicCalculator.Safety(decimal("0.10"), BigDecimal.ZERO, BigDecimal.ZERO),
            null);
    return new DecisionEngine.Effect(
        TARGET,
        segment,
        decimal(price),
        decimal("100"),
        decimal(price),
        decimal("100"),
        decimal("100"),
        decimal("1"),
        decimal("200"),
        decimal("100"),
        decimal("100"),
        List.of(outcome),
        true,
        true,
        UUID.nameUUIDFromBytes(segment.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        decimal(price),
        decimal("100"));
  }

  private static PolicySettings.Rule hold() {
    return new PolicySettings.Rule(
        PolicySettings.Strategy.HOLD_PRICE,
        PolicySettings.PriceKind.BASE_SELLER,
        decimal("100"),
        decimal("99"),
        decimal("101"),
        null,
        PolicySettings.Unreachable.KEEP_SAFE,
        null,
        null,
        PolicySettings.PromoMode.PRESERVE,
        Set.of(),
        Set.of());
  }

  private static DecisionEngine.Context context(
      PolicySettings.Rule rule, boolean complete, boolean lowStock) {
    return new DecisionEngine.Context(
        rule,
        null,
        Set.of(PolicySettings.Operation.SET_BASE_PRICE, PolicySettings.Operation.JOIN_PROMO),
        Set.of(),
        Set.of(TARGET),
        true,
        false,
        true,
        lowStock,
        false,
        false,
        complete,
        DecisionEngine.MAX_WORK,
        false,
        BigDecimal.ZERO,
        null);
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
