package ru.oritas.repricer.economics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CostTimelineTest {
  @Test
  void retroactiveCorrectionSplitsOnlyTheCoveredPeriodAndRetainsFutureCost() {
    var old =
        List.of(interval("2026-09-01", "2026-10-01", "500"), interval("2026-10-01", null, "550"));
    var revised = EconomicsService.replace(old, interval("2026-09-15", "2026-09-20", "520"));
    assertThat(revised)
        .containsExactly(
            interval("2026-09-01", "2026-09-15", "500"),
            interval("2026-09-15", "2026-09-20", "520"),
            interval("2026-09-20", "2026-10-01", "500"),
            interval("2026-10-01", null, "550"));
    assertThat(old).hasSize(2);
  }

  private static EconomicsService.CostInterval interval(String start, String end, String amount) {
    return new EconomicsService.CostInterval(
        LocalDate.parse(start),
        end == null ? null : LocalDate.parse(end),
        new BigDecimal(amount),
        BigDecimal.ZERO);
  }
}
