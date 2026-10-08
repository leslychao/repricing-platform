package ru.oritas.repricer.automation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.marketplace.CommercialGateway;
import ru.oritas.repricer.marketplace.CommercialState;
import ru.oritas.repricer.platform.BusinessException;

class ScenarioCompletionTest {
  private static final UUID OFFER = UUID.randomUUID();
  private static final UUID PROMOTION = UUID.randomUUID();
  private static final Instant START = Instant.parse("2026-10-05T10:00:00Z");

  @Test
  void activationDisclosesExitAndWaitsForTheNextPermittedWindow() {
    var review =
        ScenarioCompletion.review(
            settings(Set.of(), true), List.of(state()), START, START.plusSeconds(3600), START);
    assertEquals(Instant.parse("2026-10-12T10:00:00Z"), review.earliestCompletionAt());
    assertEquals(Set.of(PROMOTION), review.targets().getFirst().possibleExits());
  }

  @Test
  void impossibleWindowOrForbiddenOrProtectedExitCannotActivate() {
    assertEquals(
        "SCENARIO_EXIT_UNCONFIRMED",
        assertThrows(
                BusinessException.class,
                () ->
                    ScenarioCompletion.review(
                        settings(Set.of(), false),
                        List.of(state()),
                        START,
                        START.plusSeconds(3600),
                        START))
            .code());
    assertEquals(
        "SCENARIO_PROTECTED_EXIT",
        assertThrows(
                BusinessException.class,
                () ->
                    ScenarioCompletion.review(
                        settings(Set.of(PROMOTION), true),
                        List.of(state()),
                        START,
                        START.plusSeconds(3600),
                        START))
            .code());
    assertEquals(
        "SCENARIO_SCHEDULE_CONFLICT",
        assertThrows(
                BusinessException.class,
                () ->
                    ScenarioCompletion.review(
                        settings(Set.of(), true),
                        List.of(state()),
                        START.plusSeconds(3600),
                        START.plusSeconds(7200),
                        START))
            .code());
  }

  private static PolicySettings settings(Set<UUID> protectedPromos, boolean allowExit) {
    var regular = rule(PolicySettings.PromoMode.PRESERVE, Set.of(), protectedPromos);
    var temporary = rule(PolicySettings.PromoMode.ENSURE_SELECTED, Set.of(PROMOTION), Set.of());
    return new PolicySettings(
        regular,
        temporary,
        allowExit
            ? Set.of(PolicySettings.Operation.JOIN_PROMO, PolicySettings.Operation.LEAVE_PROMO)
            : Set.of(PolicySettings.Operation.JOIN_PROMO),
        new PolicySettings.StockProtection(false, null, null, null),
        new PolicySettings.Stability(
            BigDecimal.ONE,
            BigDecimal.TEN,
            BigDecimal.TEN,
            3600,
            10,
            0,
            300,
            PolicySettings.WriterMode.REQUIRE_CONDITIONAL,
            null),
        List.of(
            new PolicySettings.Window(
                Set.of(DayOfWeek.MONDAY),
                LocalTime.of(10, 0),
                LocalTime.of(11, 0),
                ZoneId.of("UTC"))),
        false);
  }

  private static PolicySettings.Rule rule(
      PolicySettings.PromoMode mode, Set<UUID> selected, Set<UUID> protectedPromos) {
    return new PolicySettings.Rule(
        PolicySettings.Strategy.HOLD_PRICE,
        PolicySettings.PriceKind.BASE_SELLER,
        BigDecimal.TEN,
        BigDecimal.TEN,
        BigDecimal.TEN,
        null,
        PolicySettings.Unreachable.KEEP_SAFE,
        null,
        null,
        mode,
        selected,
        protectedPromos);
  }

  @Test
  void entryEligibilityDoesNotProveExitAndCannotOverrideAnExistingExitRestriction() {
    for (var source : List.of(state(false, false), state(true, true))) {
      var policy = settings(Set.of(), true);
      if (!source.currentParticipations().isEmpty()) {
        policy =
            new PolicySettings(
                rule(PolicySettings.PromoMode.FORBID_SELECTED, Set.of(PROMOTION), Set.of()),
                policy.temporaryRule(),
                policy.allowedOperations(),
                policy.stockProtection(),
                policy.stability(),
                policy.schedule(),
                policy.allowProtectiveCorrection());
      }
      var selected = policy;
      assertEquals(
          "SCENARIO_EXIT_UNCONFIRMED",
          assertThrows(
                  BusinessException.class,
                  () ->
                      ScenarioCompletion.review(
                          selected, List.of(source), START, START.plusSeconds(3600), START))
              .code());
    }
  }

  private static CommercialState state() {
    return state(true, false);
  }

  private static CommercialState state(boolean optionExit, boolean currentExitForbidden) {
    return new CommercialState(
        OFFER,
        1,
        1,
        START,
        START.plusSeconds(300),
        true,
        List.of(OFFER),
        currentExitForbidden
            ? List.of(
                new CommercialState.Participation(
                    PROMOTION,
                    OFFER,
                    BigDecimal.TEN,
                    BigDecimal.ONE,
                    false,
                    null,
                    START.plusSeconds(9000),
                    false))
            : List.of(),
        List.of(
            new CommercialState.PromotionOption(
                PROMOTION,
                OFFER,
                "CONFIRMED",
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.ONE,
                BigDecimal.ONE,
                START.plusSeconds(9000),
                Set.of(),
                Set.of(),
                optionExit)),
        Set.of(CommercialGateway.Operation.JOIN_PROMO, CommercialGateway.Operation.LEAVE_PROMO),
        null,
        null,
        false,
        BigDecimal.ONE,
        Set.of());
  }
}
