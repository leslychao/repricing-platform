package ru.oritas.repricer.automation.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.SafetyEnvelopeService;
import ru.oritas.repricer.marketplace.CommercialGateway;
import ru.oritas.repricer.marketplace.CommercialState;
import ru.oritas.repricer.marketplace.EconomicTerms;

class CommercialCandidateGeneratorTest {
  private static final UUID OFFER = UUID.randomUUID();
  private static final UUID PLACEMENT = UUID.randomUUID();
  private static final UUID PROMOTION = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

  @Test
  void changingParticipationFindsTheSafeIntermediateOrderAndConfirmsTheWholeJoin() {
    UUID nextPromotion = UUID.randomUUID();
    var rule =
        new PolicySettings.Rule(
            PolicySettings.Strategy.HOLD_PRICE,
            PolicySettings.PriceKind.EFFECTIVE_SELLER,
            decimal("1000"),
            decimal("1000"),
            decimal("1000"),
            null,
            PolicySettings.Unreachable.NEAREST_SAFE,
            null,
            null,
            PolicySettings.PromoMode.ENSURE_SELECTED,
            Set.of(nextPromotion),
            Set.of());
    var operations =
        Set.of(
            PolicySettings.Operation.SET_BASE_PRICE,
            PolicySettings.Operation.LEAVE_PROMO,
            PolicySettings.Operation.JOIN_PROMO);
    var context =
        new DecisionEngine.Context(
            rule,
            null,
            operations,
            Set.of(PROMOTION),
            Set.of(OFFER),
            true,
            false,
            true,
            false,
            false,
            false,
            true,
            DecisionEngine.MAX_WORK,
            false,
            BigDecimal.ZERO,
            null);
    var state =
        new CommercialState(
            OFFER,
            1,
            1,
            NOW,
            NOW.plusSeconds(300),
            true,
            List.of(OFFER),
            List.of(
                new CommercialState.Participation(
                    PROMOTION,
                    OFFER,
                    decimal("1000"),
                    decimal("1000"),
                    false,
                    null,
                    NOW.plusSeconds(3600),
                    true)),
            List.of(option(PROMOTION), option(nextPromotion)),
            Set.of(
                CommercialGateway.Operation.SET_PRICE,
                CommercialGateway.Operation.LEAVE_PROMO,
                CommercialGateway.Operation.JOIN_PROMO),
            UUID.randomUUID(),
            null,
            false,
            decimal("100"),
            Set.of());
    var settings =
        new PolicySettings(
            rule,
            null,
            operations,
            new PolicySettings.StockProtection(false, null, null, null),
            new PolicySettings.Stability(
                BigDecimal.ONE,
                decimal("500"),
                decimal("1000"),
                3600,
                20,
                0,
                60,
                PolicySettings.WriterMode.DECLARED_EXCLUSIVE_WRITER,
                null),
            List.of(),
            false);
    var inputs =
        new CommercialCandidateGenerator.Inputs(
            OFFER,
            OFFER,
            decimal("1000"),
            state,
            List.of(
                term(
                    Set.of(PROMOTION),
                    new EconomicTerms.PriceField(
                        EconomicTerms.PriceFieldKind.PROMOTION_SELLER, PROMOTION),
                    BigDecimal.ZERO),
                term(Set.of(), EconomicTerms.PriceField.base(), decimal("-400")),
                term(
                    Set.of(nextPromotion),
                    new EconomicTerms.PriceField(
                        EconomicTerms.PriceFieldKind.PROMOTION_SELLER, nextPromotion),
                    BigDecimal.ZERO)),
            new EconomicsService.CostInterval(
                LocalDate.of(2026, 1, 1), null, decimal("800"), BigDecimal.ZERO),
            BigDecimal.ZERO,
            new SafetyEnvelopeService.Envelope(
                1, decimal("1000"), decimal("1400"), BigDecimal.ZERO, decimal("100"), Map.of()),
            settings,
            decimal("1000"),
            decimal("1400"),
            decimal("1000"),
            null,
            null,
            Set.of(PROMOTION),
            NOW);
    var generated = new CommercialCandidateGenerator(inputs, context);
    Map<UUID, DecisionEngine.Candidate> choices = new java.util.HashMap<>();
    var result =
        new DecisionEngine()
            .decideStreaming(
                context,
                generated,
                (candidate, evaluation) -> choices.put(candidate.id(), candidate),
                () -> true);
    assertEquals("CALCULATED", result.status());
    assertTrue(result.requiresManualConfirmation());
    var chosen = choices.get(result.selectedCandidate());
    assertEquals(
        List.of(
            PolicySettings.Operation.SET_BASE_PRICE,
            PolicySettings.Operation.LEAVE_PROMO,
            PolicySettings.Operation.JOIN_PROMO),
        chosen.path().stream().map(DecisionEngine.Step::operation).toList());
    assertEquals(0, decimal("1300").compareTo(chosen.path().getFirst().absolutePrice()));
    assertTrue(chosen.path().stream().allMatch(step -> step.maximumUnitLiability() != null));
  }

  @Test
  void prefixProofRejectsFinalStateAheadOfThePlanAndUnexpectedPrices() {
    var context = context(rule(PolicySettings.Strategy.HOLD_PRICE));
    var candidates = new java.util.ArrayList<DecisionEngine.Candidate>();
    generator(context, true).forEach(candidates::add);
    var initialCandidate = candidates.getFirst();
    var state =
        new CommercialState(
            OFFER,
            1,
            1,
            NOW,
            NOW.plusSeconds(300),
            true,
            List.of(OFFER),
            List.of(
                new CommercialState.Participation(
                    PROMOTION,
                    OFFER,
                    decimal("1200"),
                    decimal("1050"),
                    false,
                    null,
                    NOW.plusSeconds(3600),
                    true)),
            List.of(),
            Set.of(),
            UUID.randomUUID(),
            null,
            false,
            decimal("100"),
            Set.of());
    var evidence =
        new DecisionContextAssembler.Evidence(
            null, null, null, null, state, List.of(), List.of(), null, null, null, 1, NOW, true,
            Map.of());
    var snapshot = new DecisionService.Snapshot(null, null, List.of(), null, evidence, null);
    var selected =
        new DecisionEngine.Candidate(
            UUID.randomUUID(),
            initialCandidate.effects(),
            Set.of(),
            List.of(
                new DecisionEngine.Step(
                    PolicySettings.Operation.LEAVE_PROMO,
                    OFFER,
                    PROMOTION,
                    null,
                    true,
                    true,
                    BigDecimal.ZERO,
                    BigDecimal.ONE),
                new DecisionEngine.Step(
                    PolicySettings.Operation.SET_BASE_PRICE,
                    OFFER,
                    null,
                    decimal("1300"),
                    true,
                    true,
                    BigDecimal.ZERO,
                    BigDecimal.ONE)),
            false,
            true,
            Map.of());
    var afterExit =
        new CommercialState(
            OFFER,
            2,
            1,
            NOW,
            NOW.plusSeconds(300),
            true,
            List.of(OFFER),
            List.of(),
            List.of(),
            Set.of(),
            state.stockPoolId(),
            null,
            false,
            decimal("100"),
            Set.of());
    assertTrue(
        DecisionContinuationService.matchesPrefix(
            snapshot, selected, 1, afterExit, decimal("1200")));
    assertEquals(
        DecisionContinuationService.Status.AWAITING_SOURCE,
        DecisionContinuationService.classifyPrefix(
            snapshot, selected, 1, afterExit, decimal("1200"), NOW.plusSeconds(1)));
    assertEquals(
        DecisionContinuationService.Status.CURRENT,
        DecisionContinuationService.classifyPrefix(
            snapshot, selected, 1, afterExit, decimal("1200"), NOW));
    assertEquals(
        DecisionContinuationService.Status.STALE,
        DecisionContinuationService.classifyPrefix(
            snapshot, selected, 1, afterExit, decimal("1300"), NOW));
    assertEquals(
        DecisionContinuationService.Status.AWAITING_SOURCE,
        DecisionContinuationService.classifyPrefix(
            snapshot,
            selected,
            selected.path().size(),
            afterExit,
            decimal("1300"),
            NOW.plusSeconds(1)));
    assertEquals(
        DecisionContinuationService.Status.CURRENT,
        DecisionContinuationService.classifyPrefix(
            snapshot, selected, selected.path().size(), afterExit, decimal("1300"), NOW));
    assertFalse(
        DecisionContinuationService.matchesPrefix(
            snapshot, selected, 1, afterExit, decimal("1300")));
    assertFalse(
        DecisionContinuationService.matchesPrefix(snapshot, selected, 1, state, decimal("1200")));
    assertTrue(
        DecisionContinuationService.matchesPrefix(
            snapshot, selected, 2, afterExit, decimal("1300")));
  }

  private static CommercialState.PromotionOption option(UUID id) {
    return new CommercialState.PromotionOption(
        id,
        OFFER,
        "DISCOUNT",
        decimal("1000"),
        decimal("1000"),
        decimal("100"),
        null,
        NOW.plusSeconds(3600),
        Set.of(),
        Set.of(),
        true);
  }

  private static EconomicTerms term(
      Set<UUID> promotions, EconomicTerms.PriceField field, BigDecimal offset) {
    return new EconomicTerms(
        OFFER,
        PLACEMENT,
        OFFER,
        "FBO",
        1,
        NOW.plusSeconds(300),
        true,
        BigDecimal.ONE,
        offset,
        BigDecimal.ONE,
        offset,
        List.of(),
        Set.of("KEPT_PURCHASE"),
        List.of(),
        promotions,
        field,
        field,
        field,
        BigDecimal.ONE,
        offset);
  }

  @Test
  void effectivePriceHoldingChooses1100WithinPromotionRange() {
    var rule = rule(PolicySettings.Strategy.HOLD_PRICE);
    var context = context(rule);
    var result = new DecisionEngine().decide(context, generator(context, true));
    assertEquals("CALCULATED", result.status());
    assertFalse(result.targetAchieved());
    var chosen =
        result.evaluations().stream()
            .filter(value -> value.candidateId().equals(result.selectedCandidate()))
            .findFirst()
            .orElseThrow();
    assertEquals(0, new BigDecimal("1100").compareTo(chosen.comparedPrices().getFirst()));
  }

  @Test
  void effectiveSellerPriceDoesNotBecomeRevenueAndDoesNotRequireBuyerPrice() {
    var context = context(rule(PolicySettings.Strategy.HOLD_PRICE));
    var result =
        new DecisionEngine()
            .decide(context, generator(context, true, new BigDecimal("500"), false, true));
    assertEquals("CALCULATED", result.status());
    var chosen =
        result.evaluations().stream()
            .filter(value -> value.candidateId().equals(result.selectedCandidate()))
            .findFirst()
            .orElseThrow();
    assertEquals(0, new BigDecimal("1100").compareTo(chosen.comparedPrices().getFirst()));
    assertEquals(
        0, new BigDecimal("1600").compareTo(chosen.economics().getFirst().sellerRevenue()));
  }

  @Test
  void unknownEffectiveSellerPriceCannotBorrowKnownSellerRevenue() {
    var context = context(rule(PolicySettings.Strategy.HOLD_PRICE));
    var result =
        new DecisionEngine()
            .decide(context, generator(context, true, new BigDecimal("500"), true, false));
    assertEquals("INCOMPLETE_SEARCH", result.status());
    assertEquals("COMPARED_PRICE_UNKNOWN", result.reason());
    assertNull(result.selectedCandidate());
  }

  @Test
  void profitabilityChooses1000RatherThanTheMaximumPromotionPrice() {
    var context = context(rule(PolicySettings.Strategy.TARGET_PROFITABILITY));
    var result = new DecisionEngine().decide(context, generator(context, true));
    assertEquals("CALCULATED", result.status());
    var chosen =
        result.evaluations().stream()
            .filter(value -> value.candidateId().equals(result.selectedCandidate()))
            .findFirst()
            .orElseThrow();
    assertEquals(0, new BigDecimal("1000").compareTo(chosen.comparedPrices().getFirst()));
  }

  @Test
  void unconfirmedPriceFieldCannotBorrowObservedBasePrice() {
    var context = context(rule(PolicySettings.Strategy.HOLD_PRICE));
    var result = new DecisionEngine().decide(context, generator(context, false));
    assertEquals("INCOMPLETE_SEARCH", result.status());
    assertEquals("PRICE_FIELD_BINDING_UNKNOWN", result.reason());
    assertNull(result.selectedCandidate());
  }

  @Test
  void streamingSearchReturnsCompactRankingsAndWritesEveryFullEvaluation() {
    var context = context(rule(PolicySettings.Strategy.HOLD_PRICE));
    var visits = new java.util.concurrent.atomic.AtomicInteger();
    var result =
        new DecisionEngine()
            .decideStreaming(
                context,
                generator(context, true),
                (candidate, evaluation) -> {
                  visits.incrementAndGet();
                  assertNotNull(candidate.promotionPrices().get(PROMOTION));
                  if (evaluation.safe()) {
                    assertFalse(evaluation.economics().isEmpty());
                  }
                },
                () -> true);
    assertEquals(result.evaluations().size(), visits.get());
    assertEquals(
        0, result.evaluations().stream().mapToInt(value -> value.economics().size()).sum());
  }

  private static CommercialCandidateGenerator generator(
      DecisionEngine.Context context, boolean binding) {
    return generator(context, binding, BigDecimal.ZERO, true, true);
  }

  private static CommercialCandidateGenerator generator(
      DecisionEngine.Context context,
      boolean binding,
      BigDecimal revenueOffset,
      boolean buyerKnown,
      boolean effectiveKnown) {
    var promotionField =
        new EconomicTerms.PriceField(EconomicTerms.PriceFieldKind.PROMOTION_SELLER, PROMOTION);
    var terms =
        new EconomicTerms(
            OFFER,
            PLACEMENT,
            OFFER,
            "FBO",
            1,
            NOW.plusSeconds(300),
            true,
            BigDecimal.ONE,
            revenueOffset,
            buyerKnown ? BigDecimal.ONE : null,
            buyerKnown ? BigDecimal.ZERO : null,
            List.of(),
            Set.of("KEPT_PURCHASE"),
            List.of(),
            Set.of(PROMOTION),
            binding ? promotionField : null,
            binding && buyerKnown ? promotionField : null,
            binding && effectiveKnown ? promotionField : null,
            effectiveKnown ? BigDecimal.ONE : null,
            effectiveKnown ? BigDecimal.ZERO : null);
    var state =
        new CommercialState(
            OFFER,
            1,
            1,
            NOW,
            NOW.plusSeconds(300),
            true,
            List.of(OFFER),
            List.of(
                new CommercialState.Participation(
                    PROMOTION,
                    OFFER,
                    decimal("1200"),
                    decimal("1050"),
                    false,
                    null,
                    NOW.plusSeconds(3600),
                    true)),
            List.of(
                new CommercialState.PromotionOption(
                    PROMOTION,
                    OFFER,
                    "DISCOUNT",
                    decimal("1000"),
                    decimal("1100"),
                    decimal("50"),
                    null,
                    NOW.plusSeconds(3600),
                    Set.of(),
                    Set.of(),
                    true)),
            Set.of(
                CommercialGateway.Operation.SET_PRICE, CommercialGateway.Operation.SET_PROMO_PRICE),
            UUID.randomUUID(),
            null,
            false,
            decimal("100"),
            Set.of());
    var settings =
        new PolicySettings(
            context.rule(),
            null,
            context.allowedOperations(),
            new PolicySettings.StockProtection(false, null, null, null),
            new PolicySettings.Stability(
                BigDecimal.ONE,
                decimal("500"),
                decimal("1000"),
                3600,
                20,
                0,
                60,
                PolicySettings.WriterMode.DECLARED_EXCLUSIVE_WRITER,
                null),
            List.of(),
            false);
    return new CommercialCandidateGenerator(
        new CommercialCandidateGenerator.Inputs(
            OFFER,
            OFFER,
            decimal("1200"),
            state,
            List.of(terms),
            new EconomicsService.CostInterval(
                LocalDate.of(2026, 1, 1), null, decimal("500"), BigDecimal.ZERO),
            BigDecimal.ZERO,
            new SafetyEnvelopeService.Envelope(
                1, decimal("1000"), decimal("1300"), BigDecimal.ZERO, BigDecimal.ZERO, Map.of()),
            settings,
            decimal("1000"),
            decimal("1300"),
            decimal("1000"),
            null,
            null,
            Set.of(PROMOTION),
            NOW),
        context);
  }

  private static PolicySettings.Rule rule(PolicySettings.Strategy strategy) {
    boolean price = strategy == PolicySettings.Strategy.HOLD_PRICE;
    return new PolicySettings.Rule(
        strategy,
        PolicySettings.PriceKind.EFFECTIVE_SELLER,
        price ? decimal("1200") : decimal("500"),
        price ? decimal("1200") : null,
        price ? decimal("1200") : null,
        price ? null : PolicySettings.Metric.PROFIT,
        PolicySettings.Unreachable.NEAREST_SAFE,
        null,
        null,
        PolicySettings.PromoMode.PRESERVE,
        Set.of(),
        Set.of());
  }

  private static DecisionEngine.Context context(PolicySettings.Rule rule) {
    return new DecisionEngine.Context(
        rule,
        null,
        Set.of(PolicySettings.Operation.SET_BASE_PRICE, PolicySettings.Operation.SET_PROMO_PRICE),
        Set.of(PROMOTION),
        Set.of(OFFER),
        true,
        false,
        true,
        false,
        false,
        false,
        true,
        DecisionEngine.MAX_WORK,
        false,
        BigDecimal.ZERO,
        null);
  }

  private static BigDecimal decimal(String value) {
    return new BigDecimal(value);
  }
}
