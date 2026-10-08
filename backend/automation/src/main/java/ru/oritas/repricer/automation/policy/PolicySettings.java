package ru.oritas.repricer.automation.policy;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable commercial publication. Monetary safety belongs to economics, not a policy. */
public record PolicySettings(
    Rule regularRule,
    Rule temporaryRule,
    Set<Operation> allowedOperations,
    StockProtection stockProtection,
    Stability stability,
    List<Window> schedule,
    boolean allowProtectiveCorrection) {
  public PolicySettings {
    Objects.requireNonNull(regularRule);
    Objects.requireNonNull(stockProtection);
    Objects.requireNonNull(stability);
    allowedOperations = Set.copyOf(allowedOperations);
    schedule = List.copyOf(schedule);
  }

  public enum Strategy {
    HOLD_PRICE,
    TARGET_PROFITABILITY,
    FOLLOW_COMPETITOR
  }

  public enum PriceKind {
    BASE_SELLER,
    EFFECTIVE_SELLER,
    BUYER_TOTAL
  }

  public enum Metric {
    PROFIT,
    MARGIN
  }

  public enum Unreachable {
    KEEP_SAFE,
    NEAREST_SAFE
  }

  public enum PromoMode {
    PRESERVE,
    CONSIDER,
    ENSURE_SELECTED,
    FORBID_SELECTED
  }

  public enum Operation {
    SET_BASE_PRICE,
    SET_PROMO_PRICE,
    JOIN_PROMO,
    LEAVE_PROMO
  }

  public enum WriterMode {
    REQUIRE_CONDITIONAL,
    DECLARED_EXCLUSIVE_WRITER
  }

  public enum Aggregation {
    SELECTED_SELLER,
    MIN,
    SECOND,
    MEDIAN,
    MEAN
  }

  public record Rule(
      Strategy strategy,
      PriceKind priceKind,
      BigDecimal target,
      BigDecimal corridorMinimum,
      BigDecimal corridorMaximum,
      Metric metric,
      Unreachable unreachable,
      Competitor competitor,
      Rule reserve,
      PromoMode promoMode,
      Set<UUID> selectedPromos,
      Set<UUID> protectedPromos,
      List<Set<UUID>> allowedCombinations,
      Set<DiscoveryProfile> discoveryProfiles,
      Set<UUID> excludedPromos) {
    public Rule(
        Strategy strategy,
        PriceKind priceKind,
        BigDecimal target,
        BigDecimal corridorMinimum,
        BigDecimal corridorMaximum,
        Metric metric,
        Unreachable unreachable,
        Competitor competitor,
        Rule reserve,
        PromoMode promoMode,
        Set<UUID> selectedPromos,
        Set<UUID> protectedPromos) {
      this(
          strategy,
          priceKind,
          target,
          corridorMinimum,
          corridorMaximum,
          metric,
          unreachable,
          competitor,
          reserve,
          promoMode,
          selectedPromos,
          protectedPromos,
          List.of(),
          Set.of(),
          Set.of());
    }

    public Rule {
      Objects.requireNonNull(strategy);
      Objects.requireNonNull(priceKind);
      Objects.requireNonNull(unreachable);
      Objects.requireNonNull(promoMode);
      selectedPromos = Set.copyOf(selectedPromos);
      protectedPromos = Set.copyOf(protectedPromos);
      allowedCombinations =
          allowedCombinations == null
              ? List.of()
              : allowedCombinations.stream().map(Set::copyOf).toList();
      discoveryProfiles = discoveryProfiles == null ? Set.of() : Set.copyOf(discoveryProfiles);
      excludedPromos = excludedPromos == null ? Set.of() : Set.copyOf(excludedPromos);
      validatePromotionReferences(
          selectedPromos, protectedPromos, allowedCombinations, discoveryProfiles, excludedPromos);
      if (strategy == Strategy.HOLD_PRICE) {
        positive(target, "Price target");
        if (corridorMinimum == null
            || corridorMaximum == null
            || corridorMinimum.signum() <= 0
            || corridorMinimum.compareTo(corridorMaximum) > 0
            || target.compareTo(corridorMinimum) < 0
            || target.compareTo(corridorMaximum) > 0) {
          throw new IllegalArgumentException("Invalid target corridor");
        }
        if (metric != null || competitor != null) {
          throw new IllegalArgumentException("Unexpected HOLD_PRICE fields");
        }
      } else if (strategy == Strategy.TARGET_PROFITABILITY) {
        Objects.requireNonNull(metric);
        if (target == null
            || target.signum() < 0
            || (metric == Metric.MARGIN && target.compareTo(BigDecimal.ONE) >= 0)
            || competitor != null
            || corridorMinimum != null
            || corridorMaximum != null) {
          throw new IllegalArgumentException("Invalid profitability target");
        }
      } else {
        Objects.requireNonNull(competitor);
        if (target != null
            || metric != null
            || corridorMinimum != null
            || corridorMaximum != null) {
          throw new IllegalArgumentException("Unexpected FOLLOW_COMPETITOR fields");
        }
      }
      if (reserve != null
          && (strategy != Strategy.FOLLOW_COMPETITOR
              || reserve.strategy() == Strategy.FOLLOW_COMPETITOR
              || reserve.reserve() != null)) {
        throw new IllegalArgumentException("Only one noncompetitive reserve is allowed");
      }
      if (promoMode == PromoMode.FORBID_SELECTED
          && selectedPromos.stream().anyMatch(protectedPromos::contains)) {
        throw new IllegalArgumentException("Protected participation cannot be forbidden");
      }
    }
  }

  public record DiscoveryProfile(UUID id, long revision) {
    public DiscoveryProfile {
      if (id == null || revision < 1) {
        throw new IllegalArgumentException("An immutable confirmed discovery profile is required");
      }
    }
  }

  static void validatePromotionReferences(
      Set<UUID> selected,
      Set<UUID> protectedPromos,
      List<Set<UUID>> combinations,
      Set<DiscoveryProfile> discovery,
      Set<UUID> excluded) {
    if (selected.size() > 8
        || combinations.size() > 16
        || combinations.stream().distinct().count() != combinations.size()
        || combinations.stream().anyMatch(value -> !selected.containsAll(value))
        || (!discovery.isEmpty() && (!selected.isEmpty() || !combinations.isEmpty()))
        || (discovery.isEmpty() && !excluded.isEmpty())
        || selected.size() + protectedPromos.size() + discovery.size() + excluded.size() > 500) {
      throw new IllegalArgumentException("Invalid explicit or discovered promotion scope");
    }
  }

  public record Competitor(
      Aggregation aggregation,
      UUID selectedSource,
      int minimumSources,
      String segment,
      BigDecimal amountOffset,
      BigDecimal ratioOffset,
      BigDecimal tolerance,
      int maxAgeSeconds) {
    public Competitor {
      Objects.requireNonNull(aggregation);
      if (minimumSources < 1
          || segment == null
          || segment.isBlank()
          || tolerance == null
          || tolerance.signum() < 0
          || maxAgeSeconds < 1
          || maxAgeSeconds > 86400
          || ((amountOffset == null) == (ratioOffset == null))
          || (ratioOffset != null && ratioOffset.abs().compareTo(BigDecimal.ONE) > 0)
          || (aggregation == Aggregation.SECOND && minimumSources < 2)) {
        throw new IllegalArgumentException("Invalid competitor rule");
      }
    }
  }

  public record StockProtection(
      boolean enabled, BigDecimal floor, BigDecimal recoveryQuantity, BigDecimal raiseRatio) {
    public StockProtection {
      if (enabled
          && (floor == null
              || recoveryQuantity == null
              || floor.signum() < 0
              || recoveryQuantity.compareTo(floor) <= 0)) {
        throw new IllegalArgumentException("Invalid stock hysteresis");
      }
      if (raiseRatio != null
          && (raiseRatio.signum() <= 0 || raiseRatio.compareTo(BigDecimal.ONE) > 0 || !enabled)) {
        throw new IllegalArgumentException("Invalid stock price protection");
      }
    }
  }

  public record Stability(
      BigDecimal significantStep,
      BigDecimal maximumStep,
      BigDecimal movementBudget,
      int movementWindowSeconds,
      int maximumCommands,
      int pauseSeconds,
      int decisionAgeSeconds,
      WriterMode writerMode,
      Integer maximumConsecutiveDecreases) {
    public Stability {
      positive(significantStep, "Significant step");
      positive(maximumStep, "Maximum step");
      positive(movementBudget, "Movement budget");
      if (movementWindowSeconds < 60
          || movementWindowSeconds > 86400
          || maximumCommands < 1
          || maximumCommands > 1000
          || pauseSeconds < 0
          || pauseSeconds > 86400
          || decisionAgeSeconds < 1
          || decisionAgeSeconds > 300
          || (maximumConsecutiveDecreases != null && maximumConsecutiveDecreases < 1)) {
        throw new IllegalArgumentException("Invalid stability limits");
      }
    }
  }

  public record Window(Set<DayOfWeek> days, LocalTime start, LocalTime end, ZoneId zone) {
    public Window {
      days = Set.copyOf(days);
      if (days.isEmpty() || start == null || end == null || start.equals(end) || zone == null) {
        throw new IllegalArgumentException("Explicit nonempty schedule is required");
      }
    }
  }

  public Duration maximumDecisionAge() {
    return Duration.ofSeconds(stability.decisionAgeSeconds());
  }

  private static void positive(BigDecimal value, String name) {
    if (value == null || value.signum() <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
