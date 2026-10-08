package ru.oritas.repricer.economics.analytics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;

/** Observed pace under unchanged conditions. No inferred price elasticity or conversion rate. */
public final class AnalyticsEngine {
  public record StockDuration(BigDecimal days, String reason) {}

  public StockDuration stockDuration(
      BigDecimal availableQuantity, BigDecimal dailyPace, boolean currentPace) {
    if (availableQuantity == null) {
      return new StockDuration(null, "STOCK_UNKNOWN");
    }
    if (!currentPace || dailyPace == null) {
      return new StockDuration(null, "PACE_UNKNOWN");
    }
    if (dailyPace.signum() <= 0) {
      return new StockDuration(null, "ZERO_PACE");
    }
    return new StockDuration(
        EconomicCalculator.divide(availableQuantity, dailyPace), "CURRENT_PACE_ONLY");
  }

  public record Day(
      LocalDate date,
      BigDecimal orderedUnits,
      boolean complete,
      boolean continuouslyAvailable,
      String regime) {
    public Day {
      if (orderedUnits == null || orderedUnits.signum() < 0) {
        throw new IllegalArgumentException("Ordered units must be nonnegative");
      }
    }
  }

  public record Pace(
      boolean available,
      String reason,
      LocalDate firstDay,
      LocalDate lastDay,
      int observedDays,
      long observedSeconds,
      BigDecimal units,
      BigDecimal dailyPace,
      BigDecimal futureUnits,
      Instant futureStart,
      Instant futureEnd,
      Instant validUntil) {}

  public Pace currentPace(
      List<Day> history,
      ZoneId zone,
      Instant now,
      String regime,
      Instant sourcesValidUntil,
      Instant knownFutureChange) {
    LocalDate today = now.atZone(zone).toLocalDate();
    LocalDate expected = today.minusDays(1);
    List<Day> ordered =
        history.stream().sorted(Comparator.comparing(Day::date).reversed()).toList();
    List<Day> accepted = new ArrayList<>(14);
    BigDecimal units = BigDecimal.ZERO;
    for (Day day : ordered) {
      if (day.date().isAfter(expected) && accepted.isEmpty()) {
        continue;
      }
      if (!day.date().equals(expected)
          || !day.complete()
          || !day.continuouslyAvailable()
          || !regime.equals(day.regime())) {
        break;
      }
      accepted.add(day);
      units = units.add(day.orderedUnits());
      expected = expected.minusDays(1);
      if (accepted.size() == 14) {
        break;
      }
    }
    Instant futureStart = today.plusDays(1).atStartOfDay(zone).toInstant();
    Instant futureEnd = today.plusDays(8).atStartOfDay(zone).toInstant();
    Instant validUntil = sourcesValidUntil.isBefore(futureStart) ? sourcesValidUntil : futureStart;
    LocalDate first = accepted.isEmpty() ? null : accepted.get(accepted.size() - 1).date();
    LocalDate last = accepted.isEmpty() ? null : accepted.getFirst().date();
    long seconds =
        first == null
            ? 0
            : Duration.between(first.atStartOfDay(zone), today.atStartOfDay(zone)).getSeconds();
    if (accepted.size() < 7 || !sourcesValidUntil.isAfter(now)) {
      return new Pace(
          false,
          accepted.size() < 7 ? "INSUFFICIENT_CONSECUTIVE_HISTORY" : "STALE_SOURCE",
          first,
          last,
          accepted.size(),
          seconds,
          units,
          null,
          null,
          futureStart,
          futureEnd,
          validUntil);
    }
    BigDecimal pace =
        EconomicCalculator.divide(
            units.multiply(BigDecimal.valueOf(86400)), BigDecimal.valueOf(seconds));
    if (knownFutureChange != null && knownFutureChange.isBefore(futureEnd)) {
      return new Pace(
          false,
          "KNOWN_FUTURE_CHANGE",
          first,
          last,
          accepted.size(),
          seconds,
          units,
          pace,
          null,
          futureStart,
          futureEnd,
          validUntil);
    }
    BigDecimal futureUnits =
        EconomicCalculator.divide(
            units.multiply(
                BigDecimal.valueOf(Duration.between(futureStart, futureEnd).getSeconds())),
            BigDecimal.valueOf(seconds));
    return new Pace(
        true,
        "CURRENT_PACE_ONLY",
        first,
        last,
        accepted.size(),
        seconds,
        units,
        pace,
        futureUnits,
        futureStart,
        futureEnd,
        validUntil);
  }
}
