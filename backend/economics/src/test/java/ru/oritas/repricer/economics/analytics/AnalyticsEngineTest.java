package ru.oritas.repricer.economics.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnalyticsEngineTest {
  @Test
  void stockDurationUsesAvailableQuantityAndNeverInventsAnInfiniteOrUnknownRate() {
    var engine = new AnalyticsEngine();
    assertThat(engine.stockDuration(new BigDecimal("3"), new BigDecimal("2"), true).days())
        .isEqualByComparingTo("1.5");
    assertThat(engine.stockDuration(BigDecimal.ZERO, BigDecimal.ONE, true).days())
        .isEqualByComparingTo("0");
    assertThat(engine.stockDuration(BigDecimal.TEN, BigDecimal.ZERO, true).reason())
        .isEqualTo("ZERO_PACE");
    assertThat(engine.stockDuration(BigDecimal.TEN, BigDecimal.ZERO, true).days()).isNull();
    assertThat(engine.stockDuration(null, BigDecimal.ONE, true).days()).isNull();
    assertThat(engine.stockDuration(BigDecimal.TEN, BigDecimal.ONE, false).days()).isNull();
  }

  @Test
  void zeroOrderDaysRemainAndInsufficientConsecutiveDaysDoNotBecomeForecasts() {
    List<AnalyticsEngine.Day> days = new ArrayList<>();
    for (int i = 1; i <= 7; i++) {
      days.add(
          new AnalyticsEngine.Day(
              LocalDate.parse("2026-10-08").minusDays(i),
              i == 1 ? BigDecimal.ZERO : BigDecimal.ONE,
              i != 4,
              true,
              "same"));
    }
    var result =
        new AnalyticsEngine()
            .currentPace(
                days,
                ZoneId.of("Europe/Saratov"),
                Instant.parse("2026-10-08T08:00:00Z"),
                "same",
                Instant.parse("2026-10-09T00:00:00Z"),
                null);
    assertThat(result.available()).isFalse();
    assertThat(result.observedDays()).isEqualTo(3);
    assertThat(result.units()).isEqualByComparingTo("2");
  }

  @Test
  void localDayDurationAccountsForSpringClockChange() {
    List<AnalyticsEngine.Day> days = new ArrayList<>();
    LocalDate today = LocalDate.parse("2026-03-30");
    for (int i = 1; i <= 7; i++) {
      days.add(new AnalyticsEngine.Day(today.minusDays(i), BigDecimal.ONE, true, true, "same"));
    }
    var result =
        new AnalyticsEngine()
            .currentPace(
                days,
                ZoneId.of("Europe/Berlin"),
                Instant.parse("2026-03-30T08:00:00Z"),
                "same",
                Instant.parse("2026-03-31T08:00:00Z"),
                null);
    assertThat(result.available()).isTrue();
    assertThat(result.observedSeconds()).isEqualTo(601200);
    assertThat(result.futureUnits()).isGreaterThan(new BigDecimal("7"));
  }
}
