package ru.oritas.repricer.automation.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.automation.policy.PolicySettings.StockProtection;
import ru.oritas.repricer.automation.policy.PolicySettings.Window;
import ru.oritas.repricer.automation.runtime.RuntimeRules.ScenarioState;
import ru.oritas.repricer.automation.runtime.RuntimeRules.StockState;

class RuntimeRulesTest {
  private final RuntimeRules rules = new RuntimeRules();
  private final StockProtection protection =
      new StockProtection(true, new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("0.1"));

  @Test
  void sweepCadenceUsesItsStartAndDoesNotOverlapSlowCycles() {
    Instant start = Instant.parse("2026-10-08T10:00:00Z");
    assertEquals(start.plusSeconds(300), rules.nextSweepAt(start, start.plusSeconds(90)));
    assertEquals(start.plusSeconds(420), rules.nextSweepAt(start, start.plusSeconds(420)));
  }

  @Test
  void stockProtectionRequiresRecoveryThresholdAfterLowEpisode() {
    assertEquals(
        StockState.LOW_STOCK,
        rules.stockState(StockState.LOW_STOCK, new BigDecimal("9"), true, protection));
    assertEquals(
        StockState.NORMAL,
        rules.stockState(StockState.LOW_STOCK, new BigDecimal("10"), true, protection));
    assertEquals(
        StockState.NO_STOCK,
        rules.stockState(StockState.NORMAL, BigDecimal.ZERO, true, protection));
    assertEquals(
        StockState.STOCK_UNKNOWN,
        rules.stockState(StockState.NORMAL, new BigDecimal("20"), false, protection));
  }

  @Test
  void protectiveIncreaseUsesFixedEpisodeBase() {
    assertEquals(
        0,
        new BigDecimal("110").compareTo(rules.protectivePrice(new BigDecimal("100"), protection)));
  }

  @Test
  void overnightScheduleUsesStartDayAndAccountZone() {
    var windows =
        List.of(
            new Window(
                Set.of(DayOfWeek.MONDAY),
                LocalTime.of(22, 0),
                LocalTime.of(2, 0),
                ZoneId.of("Europe/Saratov")));
    assertTrue(rules.withinSchedule(windows, Instant.parse("2026-10-05T21:00:00Z")));
    assertFalse(rules.withinSchedule(windows, Instant.parse("2026-10-05T22:00:00Z")));
    assertEquals(
        Instant.parse("2026-10-12T18:00:00Z"),
        rules.nextScheduledAt(windows, Instant.parse("2026-10-05T22:00:00Z")));
    assertEquals(
        Instant.parse("2026-10-05T21:00:00Z"),
        rules.nextScheduledAt(windows, Instant.parse("2026-10-05T21:00:00Z")));
  }

  @Test
  void endOfScenarioWaitsForExternalEffectsAndCannotRestart() {
    Instant end = Instant.parse("2026-10-08T10:00:00Z");
    assertEquals(
        ScenarioState.FINISHING,
        rules.scenarioState(ScenarioState.RUNNING, end, end, false, true, false));
    assertEquals(
        ScenarioState.FINISHED,
        rules.scenarioState(ScenarioState.FINISHING, end.plusSeconds(60), end, false, true, true));
    assertEquals(
        ScenarioState.FINISHED,
        rules.scenarioState(
            ScenarioState.FINISHED, end.minusSeconds(60), end, false, false, false));
  }
}
