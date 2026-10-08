package ru.oritas.repricer.automation.runtime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import ru.oritas.repricer.automation.policy.PolicySettings.StockProtection;
import ru.oritas.repricer.automation.policy.PolicySettings.Window;

public final class RuntimeRules {
  public enum StockState {
    NORMAL,
    LOW_STOCK,
    NO_STOCK,
    STOCK_UNKNOWN
  }

  public enum ScenarioState {
    READY,
    RUNNING,
    FINISHING,
    FINISHED
  }

  public StockState stockState(
      StockState previous,
      BigDecimal available,
      boolean freshComplete,
      StockProtection protection) {
    if (!freshComplete || available == null || available.signum() < 0) {
      return StockState.STOCK_UNKNOWN;
    }
    if (available.signum() == 0) {
      return StockState.NO_STOCK;
    }
    if (!protection.enabled()) {
      return StockState.NORMAL;
    }
    if (available.compareTo(protection.floor()) <= 0) {
      return StockState.LOW_STOCK;
    }
    if (previous != StockState.NORMAL && available.compareTo(protection.recoveryQuantity()) < 0) {
      return StockState.LOW_STOCK;
    }
    return StockState.NORMAL;
  }

  public BigDecimal protectivePrice(BigDecimal episodeBase, StockProtection protection) {
    if (episodeBase == null || episodeBase.signum() <= 0 || protection.raiseRatio() == null) {
      throw new IllegalArgumentException("A fixed episode base and explicit consent are required");
    }
    return episodeBase.multiply(BigDecimal.ONE.add(protection.raiseRatio()));
  }

  public Instant nextSweepAt(Instant startedAt, Instant completedAt) {
    Instant intervalEnd = startedAt.plusSeconds(300);
    return intervalEnd.isAfter(completedAt) ? intervalEnd : completedAt;
  }

  public boolean withinSchedule(List<Window> windows, Instant instant) {
    if (windows.isEmpty()) {
      return true;
    }
    for (Window window : windows) {
      ZonedDateTime local = instant.atZone(window.zone());
      if (window.start().isBefore(window.end())) {
        if (window.days().contains(local.getDayOfWeek())
            && !local.toLocalTime().isBefore(window.start())
            && local.toLocalTime().isBefore(window.end())) {
          return true;
        }
      } else if ((!local.toLocalTime().isBefore(window.start())
              && window.days().contains(local.getDayOfWeek()))
          || (local.toLocalTime().isBefore(window.end())
              && window.days().contains(local.minusDays(1).getDayOfWeek()))) {
        return true;
      }
    }
    return false;
  }

  /** The recurring weekly window can delay completion; endAt does not override it. */
  public Instant nextScheduledAt(List<Window> windows, Instant from) {
    if (withinSchedule(windows, from)) {
      return from;
    }
    Instant next = null;
    for (Window window : windows) {
      var localDate = from.atZone(window.zone()).toLocalDate();
      for (int offset = 0; offset <= 7; offset++) {
        var date = localDate.plusDays(offset);
        if (!window.days().contains(date.getDayOfWeek())) {
          continue;
        }
        var start = date.atTime(window.start()).atZone(window.zone()).toInstant();
        if (!start.isBefore(from)
            && withinSchedule(List.of(window), start)
            && (next == null || start.isBefore(next))) {
          next = start;
        }
      }
    }
    if (next == null) {
      throw new IllegalArgumentException("Schedule has no reachable weekly window");
    }
    return next;
  }

  public ScenarioState scenarioState(
      ScenarioState current,
      Instant now,
      Instant endAt,
      boolean stopCondition,
      boolean anyPossibleEffect,
      boolean allEffectsFinished) {
    if (current == ScenarioState.FINISHED) {
      return current;
    }
    if (current == ScenarioState.FINISHING || !now.isBefore(endAt) || stopCondition) {
      return allEffectsFinished ? ScenarioState.FINISHED : ScenarioState.FINISHING;
    }
    return anyPossibleEffect ? ScenarioState.RUNNING : current;
  }
}
