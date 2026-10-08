package ru.oritas.repricer.automation.decision;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import ru.oritas.repricer.automation.policy.PolicySettings.Metric;
import ru.oritas.repricer.automation.policy.PolicySettings.Operation;
import ru.oritas.repricer.automation.policy.PolicySettings.PromoMode;
import ru.oritas.repricer.automation.policy.PolicySettings.Rule;
import ru.oritas.repricer.automation.policy.PolicySettings.Strategy;
import ru.oritas.repricer.automation.policy.PolicySettings.Unreachable;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Check;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Input;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Outcome;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Result;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Safety;

/** Joint finite-state search; no adapter, database, wall clock, demand model or side effect. */
public final class DecisionEngine {
  public static final int MAX_STATES = 512;
  public static final int MAX_PRICE_POINTS = 128;
  public static final long MAX_WORK = 2_000_000;

  public record EconomicOutcome(Input input, Safety safety, BigDecimal temporaryFloor) {}

  public record Effect(
      UUID targetId,
      String segment,
      BigDecimal basePrice,
      BigDecimal currentBasePrice,
      BigDecimal comparedPrice,
      BigDecimal currentComparedPrice,
      BigDecimal currentSellerRevenue,
      BigDecimal minimumPrice,
      BigDecimal maximumPrice,
      BigDecimal maximumStep,
      BigDecimal remainingMovement,
      List<EconomicOutcome> outcomes,
      boolean preservedFields,
      boolean sourceComplete,
      UUID placementId,
      BigDecimal buyerPrice,
      BigDecimal currentBuyerPrice) {
    public Effect {
      Objects.requireNonNull(targetId);
      Objects.requireNonNull(placementId);
      Objects.requireNonNull(segment);
      Objects.requireNonNull(basePrice);
      Objects.requireNonNull(currentBasePrice);
      Objects.requireNonNull(comparedPrice);
      Objects.requireNonNull(currentComparedPrice);
      Objects.requireNonNull(currentSellerRevenue);
      Objects.requireNonNull(minimumPrice);
      Objects.requireNonNull(maximumPrice);
      Objects.requireNonNull(maximumStep);
      Objects.requireNonNull(remainingMovement);
      outcomes = List.copyOf(outcomes);
    }
  }

  public record Step(
      Operation operation,
      UUID targetId,
      UUID promoId,
      BigDecimal absolutePrice,
      boolean supported,
      boolean safeIntermediateState,
      BigDecimal maximumUnitLiability,
      BigDecimal monetaryMovement,
      BigDecimal committedQuantity) {
    public Step(
        Operation operation,
        UUID targetId,
        UUID promoId,
        BigDecimal absolutePrice,
        boolean supported,
        boolean safeIntermediateState,
        BigDecimal maximumUnitLiability,
        BigDecimal monetaryMovement) {
      this(
          operation,
          targetId,
          promoId,
          absolutePrice,
          supported,
          safeIntermediateState,
          maximumUnitLiability,
          monetaryMovement,
          null);
    }
  }

  public record Candidate(
      UUID id,
      List<Effect> effects,
      Set<UUID> participations,
      List<Step> path,
      boolean unchanged,
      boolean completionSatisfied,
      Map<UUID, BigDecimal> promotionPrices) {
    public Candidate {
      effects = List.copyOf(effects);
      participations = Set.copyOf(participations);
      path = List.copyOf(path);
      promotionPrices = Map.copyOf(promotionPrices);
    }
  }

  public record Context(
      Rule rule,
      BigDecimal competitorTarget,
      Set<Operation> allowedOperations,
      Set<UUID> currentParticipations,
      Set<UUID> originalTargets,
      boolean authorized,
      boolean paused,
      boolean inputsComplete,
      boolean lowStock,
      boolean noStock,
      boolean mandatoryCompletion,
      boolean searchDomainComplete,
      long maximumWork,
      boolean allowProtectiveCorrection,
      BigDecimal significantStep,
      StockRaise stockRaise) {
    public Context {
      Objects.requireNonNull(rule);
      allowedOperations = Set.copyOf(allowedOperations);
      currentParticipations = Set.copyOf(currentParticipations);
      originalTargets = Set.copyOf(originalTargets);
      if (maximumWork < 1 || maximumWork > MAX_WORK) {
        throw new IllegalArgumentException("Invalid work budget");
      }
    }
  }

  public record StockRaise(BigDecimal episodeBase, BigDecimal target) {
    public StockRaise {
      Objects.requireNonNull(episodeBase);
      Objects.requireNonNull(target);
      if (episodeBase.signum() <= 0 || target.compareTo(episodeBase) <= 0) {
        throw new IllegalArgumentException("Stock protection requires a fixed positive raise");
      }
    }
  }

  public record Evaluation(
      UUID candidateId,
      boolean safe,
      String reason,
      boolean reachesTarget,
      BigDecimal worstDeviation,
      BigDecimal totalDeviation,
      BigDecimal absoluteMovement,
      int actions,
      int participationChanges,
      boolean unchanged,
      boolean requiresJoinApproval,
      List<BigDecimal> comparedPrices,
      List<Result> economics) {
    public Evaluation {
      comparedPrices = List.copyOf(comparedPrices);
      economics = List.copyOf(economics);
    }
  }

  public record Decision(
      String status,
      String reason,
      UUID selectedCandidate,
      boolean targetAchieved,
      boolean requiresManualConfirmation,
      long workUsed,
      List<Evaluation> evaluations) {
    public Decision {
      evaluations = List.copyOf(evaluations);
    }
  }

  private final EconomicCalculator calculator = new EconomicCalculator();

  public static final class IncompleteSearch extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    public IncompleteSearch(String reason) {
      super(reason);
    }
  }

  public interface SearchSource extends Iterable<Candidate> {
    long preparationWork();
  }

  public Decision decide(Context context, Iterable<Candidate> candidates) {
    return decide(context, candidates, (candidate, evaluation) -> {}, () -> true, false);
  }

  public Decision decideStreaming(
      Context context,
      Iterable<Candidate> candidates,
      BiConsumer<Candidate, Evaluation> trace,
      BooleanSupplier withinDeadline) {
    return decide(context, candidates, trace, withinDeadline, true);
  }

  private Decision decide(
      Context context,
      Iterable<Candidate> candidates,
      BiConsumer<Candidate, Evaluation> trace,
      BooleanSupplier withinDeadline,
      boolean compact) {
    if (!context.authorized()
        || context.paused()
        || !context.inputsComplete()
        || context.originalTargets().isEmpty()
        || context.originalTargets().size() > 1000) {
      return blocked(
          !context.authorized()
              ? "NOT_AUTHORIZED"
              : context.paused() ? "PAUSED" : "INCOMPLETE_INPUTS");
    }
    if (context.rule().strategy() == Strategy.FOLLOW_COMPETITOR
        && context.competitorTarget() == null) {
      return blocked("COMPETITOR_REFERENCE_UNAVAILABLE");
    }
    long work = 0;
    long evaluationWork = 0;
    boolean correctionRequired = false;
    List<Evaluation> evaluations = new ArrayList<>();
    List<Evaluation> feasible = new ArrayList<>();
    Set<UUID> stockRaises = new java.util.HashSet<>();
    try {
      for (Candidate candidate : candidates) {
        evaluationWork += 1 + candidate.effects().size() + candidate.path().size();
        for (Effect effect : candidate.effects()) {
          for (EconomicOutcome outcome : effect.outcomes()) {
            evaluationWork += 1 + outcome.input().charges().size();
          }
        }
        work =
            evaluationWork
                + (candidates instanceof SearchSource source ? source.preparationWork() : 0);
        if (evaluations.size() >= MAX_STATES
            || work > context.maximumWork()
            || !withinDeadline.getAsBoolean()) {
          return new Decision(
              "INCOMPLETE_SEARCH", "SEARCH_LIMIT", null, false, false, work, evaluations);
        }
        Evaluation evaluation = evaluate(context, candidate);
        if (candidate.unchanged() && !evaluation.safe() && context.allowProtectiveCorrection()) {
          correctionRequired =
              context.mandatoryCompletion()
                  || (context.rule().promoMode() == PromoMode.FORBID_SELECTED
                      && evaluation.reason().equals("PROMO_INTENT"))
                  || Set.of(
                          "MINIMUM_PROFIT",
                          "MINIMUM_MARGIN",
                          "OUTCOME_LOSS",
                          "TEMPORARY_FLOOR",
                          "MARGIN_UNDEFINED")
                      .contains(evaluation.reason());
        }
        trace.accept(candidate, evaluation);
        if (compact) {
          evaluation = compact(evaluation);
        }
        evaluations.add(evaluation);
        if (evaluation.safe()) {
          feasible.add(evaluation);
          if (context.stockRaise() != null && satisfiesStockRaise(context, candidate)) {
            stockRaises.add(candidate.id());
          }
        }
      }
    } catch (IncompleteSearch incomplete) {
      work =
          evaluationWork
              + (candidates instanceof SearchSource source ? source.preparationWork() : 0);
      return new Decision(
          "INCOMPLETE_SEARCH", incomplete.getMessage(), null, false, false, work, evaluations);
    }
    work =
        evaluationWork + (candidates instanceof SearchSource source ? source.preparationWork() : 0);
    if (work > context.maximumWork() || !withinDeadline.getAsBoolean()) {
      return new Decision(
          "INCOMPLETE_SEARCH", "SEARCH_LIMIT", null, false, false, work, evaluations);
    }
    if (!context.searchDomainComplete()) {
      return new Decision(
          "INCOMPLETE_SEARCH", "INCOMPLETE_DOMAIN", null, false, false, work, evaluations);
    }
    if (feasible.isEmpty()) {
      return new Decision("BLOCKED", "NO_SAFE_CANDIDATE", null, false, false, work, evaluations);
    }
    if (correctionRequired) {
      Evaluation chosen =
          feasible.stream()
              .min(
                  Comparator.comparingInt(Evaluation::participationChanges)
                      .thenComparing(Evaluation::absoluteMovement)
                      .thenComparingInt(Evaluation::actions)
                      .thenComparing(Evaluation::candidateId))
              .orElseThrow();
      return new Decision(
          "CALCULATED",
          "PROTECTIVE_CORRECTION",
          chosen.candidateId(),
          chosen.reachesTarget(),
          chosen.requiresJoinApproval(),
          work,
          evaluations);
    }
    feasible.removeIf(
        evaluation ->
            !evaluation.unchanged()
                && evaluation.participationChanges() == 0
                && context.significantStep() != null
                && evaluation.absoluteMovement().compareTo(context.significantStep()) < 0);
    if (feasible.isEmpty()) {
      return new Decision(
          "BLOCKED", "BELOW_SIGNIFICANT_CHANGE", null, false, false, work, evaluations);
    }
    if (context.lowStock() && context.stockRaise() != null && !context.mandatoryCompletion()) {
      feasible.removeIf(evaluation -> !stockRaises.contains(evaluation.candidateId()));
      if (feasible.isEmpty()) {
        return new Decision(
            "BLOCKED", "LOW_STOCK_RAISE_UNAVAILABLE", null, false, false, work, evaluations);
      }
      var chosen =
          feasible.stream()
              .min(
                  Comparator.comparing(Evaluation::absoluteMovement)
                      .thenComparingInt(Evaluation::actions)
                      .thenComparing(Evaluation::candidateId))
              .orElseThrow();
      return new Decision(
          "CALCULATED",
          chosen.unchanged() ? "LOW_STOCK_TARGET_ALREADY_REACHED" : "LOW_STOCK_RAISE",
          chosen.candidateId(),
          chosen.reachesTarget(),
          false,
          work,
          evaluations);
    }
    List<Evaluation> reaching = feasible.stream().filter(Evaluation::reachesTarget).toList();
    if (!reaching.isEmpty()) {
      feasible = new ArrayList<>(reaching);
    } else if (context.rule().unreachable() == Unreachable.KEEP_SAFE) {
      feasible.removeIf(e -> !e.unchanged());
      if (feasible.isEmpty()) {
        return new Decision("BLOCKED", "TARGET_UNREACHABLE", null, false, false, work, evaluations);
      }
    }
    if (context.rule().strategy() == Strategy.TARGET_PROFITABILITY && !reaching.isEmpty()) {
      List<Evaluation> minima = new ArrayList<>();
      for (Evaluation candidate : feasible) {
        boolean dominated = false;
        for (Evaluation other : feasible) {
          work++;
          if (work > context.maximumWork() || !withinDeadline.getAsBoolean()) {
            return new Decision(
                "INCOMPLETE_SEARCH", "SEARCH_LIMIT", null, false, false, work, evaluations);
          }
          if (dominates(other.comparedPrices(), candidate.comparedPrices())) {
            dominated = true;
            break;
          }
        }
        if (!dominated) {
          minima.add(candidate);
        }
      }
      List<BigDecimal> vector = minima.getFirst().comparedPrices();
      if (minima.stream().anyMatch(e -> !sameVector(vector, e.comparedPrices()))) {
        return new Decision(
            "MANUAL_INTENT_REQUIRED",
            "INCOMPARABLE_PRICE_VECTORS",
            null,
            false,
            true,
            work,
            evaluations);
      }
      feasible = minima;
    }
    Comparator<Evaluation> scoring =
        Comparator.comparing(Evaluation::worstDeviation)
            .thenComparing(Evaluation::totalDeviation)
            .thenComparing(e -> !e.unchanged())
            .thenComparing(Evaluation::absoluteMovement)
            .thenComparingInt(Evaluation::actions)
            .thenComparingInt(Evaluation::participationChanges)
            .thenComparing(Evaluation::candidateId);
    Evaluation chosen = feasible.stream().min(scoring).orElseThrow();
    String reason =
        chosen.unchanged()
            ? "NO_CHANGE_REQUIRED"
            : chosen.reachesTarget() ? "TARGET_ACHIEVED" : "TARGET_LIMITED";
    return new Decision(
        "CALCULATED",
        reason,
        chosen.candidateId(),
        chosen.reachesTarget(),
        chosen.requiresJoinApproval(),
        work,
        evaluations);
  }

  public Evaluation evaluateIntermediate(Context context, Candidate candidate) {
    Rule rule = context.rule();
    Rule intermediate =
        new Rule(
            rule.strategy(),
            rule.priceKind(),
            rule.target(),
            rule.corridorMinimum(),
            rule.corridorMaximum(),
            rule.metric(),
            rule.unreachable(),
            rule.competitor(),
            rule.reserve(),
            PromoMode.CONSIDER,
            Set.of(),
            rule.protectedPromos());
    return evaluate(
        new Context(
            intermediate,
            context.competitorTarget(),
            context.allowedOperations(),
            context.currentParticipations(),
            context.originalTargets(),
            context.authorized(),
            context.paused(),
            context.inputsComplete(),
            context.lowStock(),
            context.noStock(),
            false,
            true,
            context.maximumWork(),
            false,
            BigDecimal.ZERO,
            null),
        candidate);
  }

  private static boolean satisfiesStockRaise(Context context, Candidate candidate) {
    if (!candidate.participations().equals(context.currentParticipations())
        || candidate.path().stream()
            .anyMatch(step -> step.operation() != Operation.SET_BASE_PRICE)) {
      return false;
    }
    StockRaise raise = context.stockRaise();
    for (Effect effect : candidate.effects()) {
      if (candidate.unchanged()) {
        if (effect.currentBasePrice().compareTo(raise.target()) < 0) {
          return false;
        }
      } else if (effect.basePrice().compareTo(raise.target()) != 0
          || effect.basePrice().subtract(raise.episodeBase()).abs().compareTo(effect.maximumStep())
              > 0
          || effect.buyerPrice() == null
          || effect.currentBuyerPrice() == null
          || effect.buyerPrice().compareTo(effect.currentBuyerPrice()) <= 0) {
        return false;
      }
    }
    return true;
  }

  private static Evaluation compact(Evaluation evaluation) {
    return new Evaluation(
        evaluation.candidateId(),
        evaluation.safe(),
        evaluation.reason(),
        evaluation.reachesTarget(),
        evaluation.worstDeviation(),
        evaluation.totalDeviation(),
        evaluation.absoluteMovement(),
        evaluation.actions(),
        evaluation.participationChanges(),
        evaluation.unchanged(),
        evaluation.requiresJoinApproval(),
        evaluation.comparedPrices(),
        List.of());
  }

  public List<BigDecimal> pricePoints(
      BigDecimal current,
      BigDecimal minimum,
      BigDecimal maximum,
      BigDecimal step,
      BigDecimal maximumMovement,
      List<BigDecimal> breakpoints) {
    if (step.signum() <= 0 || minimum.signum() <= 0 || minimum.compareTo(maximum) > 0) {
      throw new IllegalArgumentException("Invalid price domain");
    }
    TreeSet<BigDecimal> points = new TreeSet<>();
    List<BigDecimal> seeds = new ArrayList<>(breakpoints);
    seeds.add(current);
    seeds.add(minimum);
    seeds.add(maximum);
    seeds.add(current.subtract(maximumMovement));
    seeds.add(current.add(maximumMovement));
    for (BigDecimal seed : seeds) {
      for (java.math.RoundingMode mode :
          List.of(java.math.RoundingMode.FLOOR, java.math.RoundingMode.CEILING)) {
        BigDecimal quantized = seed.divide(step, 0, mode).multiply(step);
        for (BigDecimal value : List.of(quantized.subtract(step), quantized, quantized.add(step))) {
          if (value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0) {
            points.add(value);
          }
        }
      }
      if (points.size() > MAX_PRICE_POINTS) {
        throw new IncompleteSearch("PRICE_POINT_LIMIT");
      }
    }
    return List.copyOf(points);
  }

  private Evaluation evaluate(Context context, Candidate candidate) {
    if (!candidate.effects().stream()
        .map(Effect::targetId)
        .collect(java.util.stream.Collectors.toSet())
        .equals(context.originalTargets())) {
      return rejected(candidate, "INCOMPLETE_SCOPE");
    }
    if (candidate.path().size() > 8
        || (context.noStock() && !candidate.unchanged())
        || (context.mandatoryCompletion() && !candidate.completionSatisfied())) {
      return rejected(candidate, context.noStock() ? "NO_STOCK" : "INVALID_PATH");
    }
    if (!candidate.participations().containsAll(context.rule().protectedPromos())) {
      return rejected(candidate, "PROTECTED_PARTICIPATION");
    }
    PromoMode mode = context.rule().promoMode();
    if ((mode == PromoMode.PRESERVE
            && !candidate.participations().equals(context.currentParticipations())
            && !(context.mandatoryCompletion()
                && context.currentParticipations().containsAll(candidate.participations())))
        || (mode == PromoMode.ENSURE_SELECTED
            && !candidate.participations().containsAll(context.rule().selectedPromos()))
        || (mode == PromoMode.FORBID_SELECTED
            && (candidate.participations().stream()
                    .anyMatch(context.rule().selectedPromos()::contains)
                || !context.currentParticipations().containsAll(candidate.participations())))) {
      return rejected(candidate, "PROMO_INTENT");
    }
    boolean join = false;
    for (Step step : candidate.path()) {
      if (!context.allowedOperations().contains(step.operation())
          || !step.supported()
          || !step.safeIntermediateState()) {
        return rejected(candidate, "UNSAFE_OR_UNSUPPORTED_PATH");
      }
      join |= step.operation() == Operation.JOIN_PROMO;
    }
    if (context.lowStock() && join) {
      return rejected(candidate, "LOW_STOCK_JOIN");
    }
    BigDecimal worst = BigDecimal.ZERO;
    BigDecimal sum = BigDecimal.ZERO;
    BigDecimal movement = BigDecimal.ZERO;
    List<Result> results = new ArrayList<>();
    List<BigDecimal> prices = new ArrayList<>();
    java.util.Map<UUID, BigDecimal> targetPrices = new java.util.HashMap<>();
    Map<String, BigDecimal> commercialPrices = new java.util.HashMap<>();
    Map<String, BigDecimal> commercialDeviations = new java.util.HashMap<>();
    java.util.Set<String> uniqueEffects = new java.util.HashSet<>();
    List<Effect> effects =
        candidate.effects().stream()
            .sorted(
                Comparator.comparing(Effect::targetId)
                    .thenComparing(Effect::segment)
                    .thenComparing(Effect::placementId))
            .toList();
    for (Effect effect : effects) {
      if (!uniqueEffects.add(
          effect.targetId() + ":" + effect.placementId() + ":" + effect.segment())) {
        return rejected(candidate, "DUPLICATE_EFFECT");
      }
      if (!effect.preservedFields()
          || !effect.sourceComplete()
          || effect.outcomes().isEmpty()
          || effect.basePrice().compareTo(effect.minimumPrice()) < 0
          || effect.basePrice().compareTo(effect.maximumPrice()) > 0) {
        return rejected(candidate, "INCOMPLETE_OR_OUTSIDE_BOUNDS");
      }
      BigDecimal change = effect.basePrice().subtract(effect.currentBasePrice()).abs();
      if (context.lowStock()
          && (effect.buyerPrice() == null || effect.currentBuyerPrice() == null)) {
        return rejected(candidate, "BUYER_PRICE_UNKNOWN");
      }
      if (change.compareTo(effect.maximumStep()) > 0
          || change.compareTo(effect.remainingMovement()) > 0
          || (context.lowStock()
              && (effect.comparedPrice().compareTo(effect.currentComparedPrice()) < 0
                  || effect.buyerPrice().compareTo(effect.currentBuyerPrice()) < 0))) {
        return rejected(candidate, "MOVEMENT_OR_STOCK_PROTECTION");
      }
      BigDecimal previousPrice = targetPrices.putIfAbsent(effect.targetId(), effect.basePrice());
      if (previousPrice == null) {
        movement = movement.add(change);
      } else if (previousPrice.compareTo(effect.basePrice()) != 0) {
        return rejected(candidate, "CONFLICTING_COMMON_PRICE");
      }
      String commercialEffect = effect.targetId() + ":" + effect.segment();
      BigDecimal earlierCommercialPrice =
          commercialPrices.putIfAbsent(commercialEffect, effect.comparedPrice());
      if (earlierCommercialPrice == null) {
        prices.add(effect.comparedPrice());
      } else if (earlierCommercialPrice.compareTo(effect.comparedPrice()) != 0) {
        return rejected(candidate, "CONFLICTING_COMMERCIAL_SEGMENT");
      }
      Result kept = null;
      for (EconomicOutcome outcome : effect.outcomes()) {
        Result result = calculator.calculate(outcome.input());
        Check check =
            calculator.check(
                result, outcome.input().outcome(), outcome.safety(), outcome.temporaryFloor());
        if (!check.allowed()) {
          return rejected(candidate, check.reason());
        }
        results.add(result);
        if (outcome.input().outcome() == Outcome.KEPT_PURCHASE) {
          if (kept != null) {
            return rejected(candidate, "DUPLICATE_KEPT_OUTCOME");
          }
          kept = result;
        }
      }
      if (kept == null
          || (context.lowStock()
              && kept.sellerRevenue().compareTo(effect.currentSellerRevenue()) < 0)) {
        return rejected(candidate, "MISSING_OR_DECREASING_SELLER_REVENUE");
      }
      BigDecimal deviation = deviation(context, effect.comparedPrice(), kept);
      if (deviation == null) {
        return rejected(candidate, "UNDEFINED_TARGET_METRIC");
      }
      worst = worst.max(deviation);
      BigDecimal earlierDeviation = commercialDeviations.putIfAbsent(commercialEffect, deviation);
      if (earlierDeviation == null) {
        sum = sum.add(deviation);
      } else if (deviation.compareTo(earlierDeviation) > 0) {
        sum = sum.add(deviation.subtract(earlierDeviation));
        commercialDeviations.put(commercialEffect, deviation);
      }
    }
    int participationChanges =
        (int)
                candidate.participations().stream()
                    .filter(p -> !context.currentParticipations().contains(p))
                    .count()
            + (int)
                context.currentParticipations().stream()
                    .filter(p -> !candidate.participations().contains(p))
                    .count();
    if (!candidate.path().isEmpty()) {
      movement = BigDecimal.ZERO;
      for (Step step : candidate.path()) {
        if (step.monetaryMovement() == null || step.monetaryMovement().signum() < 0) {
          return rejected(candidate, "MOVEMENT_UNKNOWN");
        }
        movement = movement.add(step.monetaryMovement());
      }
    }
    return new Evaluation(
        candidate.id(),
        true,
        "SAFE",
        worst.signum() == 0,
        worst,
        sum,
        movement,
        candidate.path().size(),
        participationChanges,
        candidate.unchanged(),
        join,
        prices,
        results);
  }

  private static BigDecimal deviation(Context context, BigDecimal price, Result result) {
    Rule rule = context.rule();
    return switch (rule.strategy()) {
      case HOLD_PRICE ->
          price.compareTo(rule.corridorMinimum()) < 0
              ? rule.corridorMinimum().subtract(price)
              : price.subtract(rule.corridorMaximum()).max(BigDecimal.ZERO);
      case FOLLOW_COMPETITOR ->
          price
              .subtract(context.competitorTarget())
              .abs()
              .subtract(rule.competitor().tolerance())
              .max(BigDecimal.ZERO);
      case TARGET_PROFITABILITY -> {
        BigDecimal metric = rule.metric() == Metric.PROFIT ? result.profit() : result.margin();
        yield metric == null ? null : rule.target().subtract(metric).max(BigDecimal.ZERO);
      }
    };
  }

  private static boolean dominates(List<BigDecimal> first, List<BigDecimal> second) {
    boolean cheaper = false;
    for (int i = 0; i < first.size(); i++) {
      int compared = first.get(i).compareTo(second.get(i));
      if (compared > 0) {
        return false;
      }
      cheaper |= compared < 0;
    }
    return cheaper;
  }

  private static boolean sameVector(List<BigDecimal> first, List<BigDecimal> second) {
    for (int i = 0; i < first.size(); i++) {
      if (first.get(i).compareTo(second.get(i)) != 0) {
        return false;
      }
    }
    return true;
  }

  private static Evaluation rejected(Candidate candidate, String reason) {
    return new Evaluation(
        candidate.id(),
        false,
        reason,
        false,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        candidate.path().size(),
        0,
        candidate.unchanged(),
        false,
        List.of(),
        List.of());
  }

  private static Decision blocked(String reason) {
    return new Decision("BLOCKED", reason, null, false, false, 0, List.of());
  }
}
