package ru.oritas.repricer.automation.decision;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.automation.policy.PolicySettings.Operation;
import ru.oritas.repricer.automation.policy.PolicySettings.PriceKind;
import ru.oritas.repricer.automation.runtime.RuntimeRules;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.SafetyEnvelopeService;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Outcome;
import ru.oritas.repricer.marketplace.CommercialState;
import ru.oritas.repricer.marketplace.EconomicTerms;
import ru.oritas.repricer.marketplace.EconomicTerms.PriceField;
import ru.oritas.repricer.marketplace.EconomicTerms.PriceFieldKind;

/** Lazy joint search over confirmed participation states, price fields and safe action orders. */
public final class CommercialCandidateGenerator implements DecisionEngine.SearchSource {
  public record Inputs(
      UUID offerId,
      UUID targetId,
      BigDecimal currentBasePrice,
      CommercialState commercial,
      List<EconomicTerms> terms,
      EconomicsService.CostInterval cost,
      BigDecimal taxRate,
      SafetyEnvelopeService.Envelope safety,
      PolicySettings settings,
      BigDecimal minimumPrice,
      BigDecimal maximumPrice,
      BigDecimal remainingMovement,
      BigDecimal temporaryFloor,
      BigDecimal protectiveBase,
      Set<UUID> completionAllowedPromotions,
      Instant now,
      BigDecimal availableQuantity) {
    public Inputs(
        UUID offerId,
        UUID targetId,
        BigDecimal currentBasePrice,
        CommercialState commercial,
        List<EconomicTerms> terms,
        EconomicsService.CostInterval cost,
        BigDecimal taxRate,
        SafetyEnvelopeService.Envelope safety,
        PolicySettings settings,
        BigDecimal minimumPrice,
        BigDecimal maximumPrice,
        BigDecimal remainingMovement,
        BigDecimal temporaryFloor,
        BigDecimal protectiveBase,
        Set<UUID> completionAllowedPromotions,
        Instant now) {
      this(
          offerId,
          targetId,
          currentBasePrice,
          commercial,
          terms,
          cost,
          taxRate,
          safety,
          settings,
          minimumPrice,
          maximumPrice,
          remainingMovement,
          temporaryFloor,
          protectiveBase,
          completionAllowedPromotions,
          now,
          null);
    }

    public Inputs {
      terms = List.copyOf(terms);
      completionAllowedPromotions = Set.copyOf(completionAllowedPromotions);
    }
  }

  private record Prices(BigDecimal base, Map<UUID, BigDecimal> promotions) {
    private Prices {
      promotions = Map.copyOf(promotions);
    }

    BigDecimal value(PriceField field) {
      if (field == null) {
        throw incomplete("PRICE_FIELD_BINDING_UNKNOWN");
      }
      BigDecimal result =
          field.kind() == PriceFieldKind.BASE_SELLER ? base : promotions.get(field.promotionId());
      if (result == null) {
        throw incomplete("PRICE_FIELD_OUTSIDE_PARTICIPATION");
      }
      return result;
    }
  }

  private record Action(Operation operation, UUID promotionId, BigDecimal price) {}

  private final Inputs input;
  private final DecisionEngine.Context context;
  private final EconomicCalculator calculator = new EconomicCalculator();
  private final DecisionEngine engine = new DecisionEngine();
  private final Map<UUID, CommercialState.Participation> current;
  private final Map<UUID, CommercialState.PromotionOption> options;
  private final Prices currentPrices;
  private final Set<UUID> placements;
  private long work;

  @Override
  public long preparationWork() {
    return work;
  }

  public CommercialCandidateGenerator(Inputs input, DecisionEngine.Context context) {
    this.input = input;
    this.context = context;
    current =
        input.commercial().currentParticipations().stream()
            .filter(value -> value.targetId().equals(input.targetId()))
            .collect(
                Collectors.toUnmodifiableMap(
                    CommercialState.Participation::promotionId, value -> value));
    options =
        input.commercial().availablePromotions().stream()
            .filter(value -> value.targetId().equals(input.targetId()))
            .collect(
                Collectors.toUnmodifiableMap(
                    CommercialState.PromotionOption::promotionId, value -> value));
    Map<UUID, BigDecimal> prices = new HashMap<>();
    for (var participation : current.values()) {
      if (participation.promotionPrice() != null) {
        prices.put(participation.promotionId(), participation.promotionPrice());
      }
    }
    currentPrices = new Prices(input.currentBasePrice(), prices);
    placements =
        stateTerms(current.keySet()).stream()
            .map(EconomicTerms::placementId)
            .collect(Collectors.toSet());
  }

  @Override
  public Iterator<DecisionEngine.Candidate> iterator() {
    return new Iterator<>() {
      private boolean baseline = true;
      private final Iterator<Set<UUID>> states = states().iterator();
      private Iterator<Prices> prices = List.<Prices>of().iterator();
      private Set<UUID> participations = Set.of();
      private DecisionEngine.Candidate pending;

      public boolean hasNext() {
        if (currentPrices.promotions().size() != current.size()) {
          throw incomplete("PROMOTION_PRICE_UNKNOWN");
        }
        if (pending != null) {
          return true;
        }
        if (baseline) {
          baseline = false;
          pending = candidate(current.keySet(), currentPrices, List.of(), true);
          return true;
        }
        while (true) {
          while (prices.hasNext()) {
            Prices next = prices.next();
            spend(1);
            if (participations.equals(current.keySet()) && same(next, currentPrices)) {
              continue;
            }
            List<Action> actions = actions(participations, next);
            List<DecisionEngine.Step> path = path(actions, participations, next);
            if (path == null) {
              path =
                  actions.stream()
                      .map(
                          action ->
                              new DecisionEngine.Step(
                                  action.operation(),
                                  input.targetId(),
                                  action.promotionId(),
                                  action.price(),
                                  context.allowedOperations().contains(action.operation()),
                                  false,
                                  null,
                                  null))
                      .toList();
            }
            pending = candidate(participations, next, path, false);
            return true;
          }
          if (!states.hasNext()) {
            return false;
          }
          participations = states.next();
          prices = prices(participations);
        }
      }

      public DecisionEngine.Candidate next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        var result = pending;
        pending = null;
        return result;
      }
    };
  }

  private List<Set<UUID>> states() {
    if (!context.rule().discoveryProfiles().isEmpty()) {
      throw incomplete("DISCOVERY_PROFILE_UNCONFIRMED");
    }
    Set<UUID> fixed = new HashSet<>(context.rule().protectedPromos());
    fixed.retainAll(current.keySet());
    for (var participation : current.values()) {
      if (!participation.exitConfirmedAvailable()
          || !context.allowedOperations().contains(Operation.LEAVE_PROMO)) {
        fixed.add(participation.promotionId());
      }
    }
    if (context.rule().promoMode() == PolicySettings.PromoMode.PRESERVE) {
      if (context.mandatoryCompletion()) {
        Set<UUID> remaining = new HashSet<>(current.keySet());
        remaining.retainAll(input.completionAllowedPromotions());
        remaining.addAll(fixed);
        return List.of(Set.copyOf(remaining));
      }
      return List.of(Set.copyOf(current.keySet()));
    }
    if (context.rule().promoMode() == PolicySettings.PromoMode.FORBID_SELECTED) {
      Set<UUID> remaining = new HashSet<>(current.keySet());
      remaining.removeAll(context.rule().selectedPromos());
      remaining.addAll(fixed);
      return List.of(Set.copyOf(remaining));
    }
    if (!context.rule().allowedCombinations().isEmpty()) {
      Set<Set<UUID>> permitted = new java.util.LinkedHashSet<>();
      permitted.add(Set.copyOf(current.keySet()));
      permitted.add(Set.copyOf(fixed));
      for (Set<UUID> combination : context.rule().allowedCombinations()) {
        Set<UUID> candidate = new HashSet<>(fixed);
        candidate.addAll(combination);
        if (compatible(candidate)) {
          permitted.add(Set.copyOf(candidate));
        }
      }
      if (permitted.stream().filter(value -> !value.isEmpty()).count() > 16) {
        throw incomplete("PARTICIPATION_COMBINATION_LIMIT");
      }
      return List.copyOf(permitted);
    }
    Set<UUID> mutable = new HashSet<>(current.keySet());
    if (context.allowedOperations().contains(Operation.JOIN_PROMO)) {
      mutable.addAll(context.rule().selectedPromos());
    }
    mutable.removeAll(fixed);
    if (mutable.size() > 8) {
      throw incomplete("PARTICIPATION_DOMAIN_LIMIT");
    }
    List<UUID> ordered = mutable.stream().sorted().toList();
    List<Set<UUID>> result = new ArrayList<>();
    for (int mask = 0; mask < (1 << ordered.size()); mask++) {
      spend(1);
      Set<UUID> selected = new HashSet<>(fixed);
      for (int bit = 0; bit < ordered.size(); bit++) {
        if ((mask & (1 << bit)) != 0) {
          selected.add(ordered.get(bit));
        }
      }
      if (compatible(selected)) {
        result.add(Set.copyOf(selected));
        if (result.stream().filter(value -> !value.isEmpty()).count() > 16) {
          throw incomplete("PARTICIPATION_COMBINATION_LIMIT");
        }
      }
    }
    return result;
  }

  private boolean compatible(Set<UUID> state) {
    for (UUID first : state) {
      for (UUID second : state) {
        spend(1);
        if (first.equals(second) || (current.containsKey(first) && current.containsKey(second))) {
          continue;
        }
        var left = options.get(first);
        var right = options.get(second);
        if (left == null || right == null) {
          throw incomplete("PROMOTION_COMPATIBILITY_UNKNOWN");
        }
        if (!left.compatiblePromotions().contains(second)
            || !right.compatiblePromotions().contains(first)) {
          return false;
        }
      }
    }
    return true;
  }

  private Iterator<Prices> prices(Set<UUID> state) {
    List<EconomicTerms> terms = stateTerms(state);
    List<PriceField> fields = new ArrayList<>();
    List<List<BigDecimal>> domains = new ArrayList<>();
    fields.add(PriceField.base());
    domains.add(
        pricePoints(
            PriceField.base(),
            input.currentBasePrice(),
            input.minimumPrice(),
            input.maximumPrice(),
            input.commercial().basePriceStep(),
            terms));
    for (UUID promotion : state.stream().sorted().toList()) {
      fields.add(new PriceField(PriceFieldKind.PROMOTION_SELLER, promotion));
      boolean mutable =
          !context.rule().protectedPromos().contains(promotion)
              && (current.containsKey(promotion)
                  ? context.allowedOperations().contains(Operation.SET_PROMO_PRICE)
                  : context.allowedOperations().contains(Operation.JOIN_PROMO));
      if (!mutable) {
        BigDecimal fixed = currentPrices.promotions().get(promotion);
        if (fixed == null) {
          throw incomplete("PROMOTION_PRICE_UNKNOWN");
        }
        domains.add(List.of(fixed));
        continue;
      }
      var option = options.get(promotion);
      if (option == null
          || option.minimumPrice() == null
          || option.maximumPrice() == null
          || option.priceStep() == null
          || option.minimumPrice().signum() <= 0
          || option.priceStep().signum() <= 0
          || option.minimumPrice().compareTo(option.maximumPrice()) > 0
          || option.endsAt() == null
          || !option.endsAt().isAfter(input.now())) {
        throw incomplete("PROMOTION_PRICE_DOMAIN_UNKNOWN");
      }
      BigDecimal present =
          currentPrices.promotions().getOrDefault(promotion, option.minimumPrice());
      domains.add(
          pricePoints(
              fields.getLast(),
              present,
              option.minimumPrice(),
              option.maximumPrice(),
              option.priceStep(),
              terms));
    }
    return new Iterator<>() {
      private final int[] position = new int[fields.size()];
      private boolean remaining = domains.stream().noneMatch(List::isEmpty);

      public boolean hasNext() {
        return remaining;
      }

      public Prices next() {
        if (!remaining) {
          throw new NoSuchElementException();
        }
        Map<UUID, BigDecimal> promoPrices = new HashMap<>();
        for (int index = 1; index < fields.size(); index++) {
          promoPrices.put(fields.get(index).promotionId(), domains.get(index).get(position[index]));
        }
        Prices result = new Prices(domains.getFirst().get(position[0]), promoPrices);
        for (int index = position.length - 1; index >= 0; index--) {
          position[index]++;
          if (position[index] < domains.get(index).size()) {
            return result;
          }
          position[index] = 0;
        }
        remaining = false;
        return result;
      }
    };
  }

  private List<BigDecimal> pricePoints(
      PriceField field,
      BigDecimal present,
      BigDecimal minimum,
      BigDecimal maximum,
      BigDecimal quantum,
      List<EconomicTerms> terms) {
    List<BigDecimal> boundaries = new ArrayList<>();
    if (field.kind() == PriceFieldKind.BASE_SELLER
        && input.protectiveBase() != null
        && input.settings().stockProtection().raiseRatio() != null) {
      boundaries.add(
          new RuntimeRules()
              .protectivePrice(input.protectiveBase(), input.settings().stockProtection()));
    }
    for (EconomicTerms term : terms) {
      var template = pricing(term, present);
      PriceField reference =
          switch (context.rule().priceKind()) {
            case BASE_SELLER -> PriceField.base();
            case EFFECTIVE_SELLER -> term.effectiveSellerPriceField();
            case BUYER_TOTAL -> term.buyerPriceField();
          };
      if (field.equals(reference)) {
        if (context.rule().strategy() == PolicySettings.Strategy.HOLD_PRICE) {
          boundary(boundaries, referenceArgumentFor(term, context.rule().target()));
          boundary(boundaries, referenceArgumentFor(term, context.rule().corridorMinimum()));
          boundary(boundaries, referenceArgumentFor(term, context.rule().corridorMaximum()));
        } else if (context.competitorTarget() != null) {
          boundary(boundaries, referenceArgumentFor(term, context.competitorTarget()));
        }
      }
      if (field.equals(term.sellerRevenueField())) {
        boundary(
            boundaries,
            calculator.profitabilityBoundary(
                template,
                input.temporaryFloor() == null
                    ? input.safety().minimumMargin()
                    : input.temporaryFloor(),
                input.temporaryFloor() == null));
        if (input.temporaryFloor() == null && input.safety().minimumProfit() != null) {
          boundary(
              boundaries,
              calculator.profitabilityBoundary(template, input.safety().minimumProfit(), false));
        }
        if (context.rule().strategy() == PolicySettings.Strategy.TARGET_PROFITABILITY) {
          boundary(
              boundaries,
              calculator.profitabilityBoundary(
                  template,
                  context.rule().target(),
                  context.rule().metric() == PolicySettings.Metric.MARGIN));
        }
      }
    }
    try {
      return engine.pricePoints(
          present,
          minimum,
          maximum,
          quantum,
          input.settings().stability().maximumStep().min(input.remainingMovement()),
          boundaries);
    } catch (IllegalStateException limit) {
      throw incomplete("PRICE_POINT_LIMIT");
    }
  }

  private DecisionEngine.Candidate candidate(
      Set<UUID> state, Prices prices, List<DecisionEngine.Step> path, boolean unchanged) {
    List<DecisionEngine.Effect> effects = new ArrayList<>();
    Map<UUID, EconomicTerms> baseline =
        stateTerms(current.keySet()).stream()
            .collect(Collectors.toMap(EconomicTerms::placementId, value -> value));
    for (EconomicTerms term : stateTerms(state)) {
      spend(1 + term.charges().size());
      EconomicTerms original = baseline.get(term.placementId());
      var quote =
          calculator.quote(
              pricing(term, prices.value(term.sellerRevenueField())),
              optionalPrice(prices, term.buyerPriceField()));
      var before =
          calculator.quote(
              pricing(original, currentPrices.value(original.sellerRevenueField())),
              optionalPrice(currentPrices, original.buyerPriceField()));
      List<DecisionEngine.EconomicOutcome> outcomes = new ArrayList<>();
      outcomes.add(
          new DecisionEngine.EconomicOutcome(
              quote.input(),
              new EconomicCalculator.Safety(
                  input.safety().minimumMargin(),
                  input.safety().minimumProfit() == null
                      ? BigDecimal.ZERO
                      : input.safety().minimumProfit(),
                  BigDecimal.ZERO),
              input.temporaryFloor()));
      Set<String> covered = new HashSet<>(Set.of(Outcome.KEPT_PURCHASE.name()));
      for (var outcome : term.outcomes()) {
        spend(1 + outcome.charges().size());
        Outcome kind = Outcome.valueOf(outcome.outcome());
        if (kind == Outcome.KEPT_PURCHASE) {
          continue;
        }
        BigDecimal cap = input.safety().maximumOutcomeLoss().get(kind);
        if (cap == null) {
          throw incomplete("OUTCOME_LOSS_LIMIT_REQUIRED");
        }
        var profile =
            new EconomicCalculator.PricingInput(
                kind,
                prices.value(outcome.revenueField()),
                BigDecimal.ONE,
                outcome.sellerRevenueMultiplier(),
                outcome.sellerRevenueOffset(),
                term.buyerPriceMultiplier(),
                term.buyerPriceOffset(),
                input.cost().amount(),
                input.cost().extraExpense(),
                input.taxRate(),
                tariffs(outcome.charges()));
        var result =
            calculator.quoteOutcome(
                profile,
                outcome.consumedCostRatio(),
                outcome.ownExpenseRatio(),
                outcome.taxBaseMultiplier(),
                outcome.taxBaseOffset(),
                optionalPrice(prices, term.buyerPriceField()),
                prices.value(outcome.taxBaseField()));
        outcomes.add(
            new DecisionEngine.EconomicOutcome(
                result.input(),
                new EconomicCalculator.Safety(input.safety().minimumMargin(), BigDecimal.ZERO, cap),
                null));
        covered.add(kind.name());
      }
      if (!covered.containsAll(term.applicableOutcomes())) {
        throw incomplete("OUTCOME_PROFILE_INCOMPLETE");
      }
      BigDecimal compared = compared(context.rule().priceKind(), prices, term, quote);
      BigDecimal originalCompared =
          compared(context.rule().priceKind(), currentPrices, original, before);
      if (compared == null || originalCompared == null || before.input().sellerRevenue() == null) {
        throw incomplete("COMPARED_PRICE_UNKNOWN");
      }
      boolean preserved =
          state.stream()
              .allMatch(
                  promotion ->
                      options.get(promotion) == null
                          || input
                              .commercial()
                              .confirmedPreservedFields()
                              .containsAll(options.get(promotion).requiredPreservedFields()));
      effects.add(
          new DecisionEngine.Effect(
              input.targetId(),
              term.segment(),
              prices.base(),
              currentPrices.base(),
              compared,
              originalCompared,
              before.input().sellerRevenue(),
              input.minimumPrice(),
              input.maximumPrice(),
              input.settings().stability().maximumStep(),
              input.remainingMovement(),
              outcomes,
              preserved,
              true,
              term.placementId(),
              quote.buyerTotal(),
              before.buyerTotal()));
    }
    String key =
        input.offerId()
            + ":"
            + input.targetId()
            + ":"
            + prices.base().stripTrailingZeros().toPlainString()
            + ":"
            + state.stream()
                .sorted()
                .map(
                    id ->
                        id + "=" + prices.promotions().get(id).stripTrailingZeros().toPlainString())
                .collect(Collectors.joining(","));
    return new DecisionEngine.Candidate(
        UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),
        effects,
        state,
        path,
        unchanged,
        !context.mandatoryCompletion() || input.completionAllowedPromotions().containsAll(state),
        prices.promotions());
  }

  private List<Action> actions(Set<UUID> state, Prices prices) {
    List<Action> actions = new ArrayList<>();
    for (UUID promotion : current.keySet().stream().sorted().toList()) {
      if (!state.contains(promotion)) {
        actions.add(new Action(Operation.LEAVE_PROMO, promotion, null));
      }
    }
    if (prices.base().compareTo(currentPrices.base()) != 0) {
      actions.add(new Action(Operation.SET_BASE_PRICE, null, prices.base()));
    }
    for (UUID promotion : state.stream().sorted().toList()) {
      if (!current.containsKey(promotion)) {
        actions.add(
            new Action(Operation.JOIN_PROMO, promotion, prices.promotions().get(promotion)));
      } else if (prices
              .promotions()
              .get(promotion)
              .compareTo(currentPrices.promotions().get(promotion))
          != 0) {
        actions.add(
            new Action(Operation.SET_PROMO_PRICE, promotion, prices.promotions().get(promotion)));
      }
    }
    return actions;
  }

  private List<DecisionEngine.Step> path(List<Action> actions, Set<UUID> state, Prices prices) {
    if (actions.size() > 8) {
      throw incomplete("EXECUTION_PATH_LIMIT");
    }
    BigDecimal movement = prices.base().subtract(currentPrices.base()).abs();
    for (UUID promotion : state) {
      if (currentPrices.promotions().containsKey(promotion)) {
        BigDecimal change =
            prices
                .promotions()
                .get(promotion)
                .subtract(currentPrices.promotions().get(promotion))
                .abs();
        if (change.compareTo(input.settings().stability().maximumStep()) > 0) {
          return null;
        }
        movement = movement.add(change);
      }
    }
    if (movement.compareTo(input.remainingMovement()) > 0) {
      return null;
    }
    return findPath(actions, new HashSet<>(current.keySet()), currentPrices, new ArrayList<>());
  }

  private List<DecisionEngine.Step> findPath(
      List<Action> remaining, Set<UUID> state, Prices prices, List<DecisionEngine.Step> previous) {
    if (remaining.isEmpty()) {
      return List.copyOf(previous);
    }
    for (int index = 0; index < remaining.size(); index++) {
      spend(1);
      Action action = remaining.get(index);
      if (!context.allowedOperations().contains(action.operation())) {
        continue;
      }
      BigDecimal committedQuantity = allocationQuantity(action);
      if (action.operation() == Operation.JOIN_PROMO
          && options.get(action.promotionId()).quantityTerms() != null
          && committedQuantity == null) {
        continue;
      }
      Set<UUID> nextState = new HashSet<>(state);
      Map<UUID, BigDecimal> nextPromos = new HashMap<>(prices.promotions());
      BigDecimal base = prices.base();
      switch (action.operation()) {
        case LEAVE_PROMO -> {
          nextState.remove(action.promotionId());
          nextPromos.remove(action.promotionId());
        }
        case JOIN_PROMO -> {
          nextState.add(action.promotionId());
          nextPromos.put(action.promotionId(), action.price());
        }
        case SET_PROMO_PRICE -> nextPromos.put(action.promotionId(), action.price());
        case SET_BASE_PRICE -> base = action.price();
      }
      if (!compatible(nextState)) {
        continue;
      }
      Prices nextPrices = new Prices(base, nextPromos);
      var step =
          new DecisionEngine.Step(
              action.operation(),
              input.targetId(),
              action.promotionId(),
              action.price(),
              true,
              true,
              BigDecimal.ZERO,
              BigDecimal.ZERO,
              committedQuantity);
      var intermediate = candidate(nextState, nextPrices, List.of(step), false);
      if (!engine.evaluateIntermediate(context, intermediate).safe()) {
        continue;
      }
      BigDecimal liability =
          calculator.maximumLiability(
              intermediate.effects().stream()
                  .flatMap(effect -> effect.outcomes().stream())
                  .map(DecisionEngine.EconomicOutcome::input)
                  .toList(),
              BigDecimal.ONE);
      step =
          new DecisionEngine.Step(
              action.operation(),
              input.targetId(),
              action.promotionId(),
              action.price(),
              true,
              true,
              liability,
              switch (action.operation()) {
                case SET_BASE_PRICE -> action.price().subtract(prices.base()).abs();
                case SET_PROMO_PRICE ->
                    action.price().subtract(prices.promotions().get(action.promotionId())).abs();
                case JOIN_PROMO, LEAVE_PROMO -> BigDecimal.ZERO;
              },
              committedQuantity);
      List<Action> tail = new ArrayList<>(remaining);
      tail.remove(index);
      previous.add(step);
      var path = findPath(tail, nextState, nextPrices, previous);
      previous.removeLast();
      if (path != null) {
        return path;
      }
    }
    return null;
  }

  private BigDecimal allocationQuantity(Action action) {
    if (action.operation() != Operation.JOIN_PROMO) {
      return null;
    }
    var option = options.get(action.promotionId());
    var terms = option == null ? null : option.quantityTerms();
    if (terms == null || input.availableQuantity() == null) {
      return null;
    }
    BigDecimal maximum = terms.maximum().min(input.availableQuantity());
    if (maximum.compareTo(terms.minimum()) < 0) {
      return null;
    }
    return maximum
        .subtract(terms.minimum())
        .divide(terms.step(), 0, java.math.RoundingMode.FLOOR)
        .multiply(terms.step())
        .add(terms.minimum());
  }

  private List<EconomicTerms> stateTerms(Set<UUID> state) {
    List<EconomicTerms> result =
        input.terms().stream()
            .filter(
                term ->
                    term.targetId().equals(input.targetId())
                        && term.participationIds().equals(state))
            .sorted(Comparator.comparing(EconomicTerms::placementId))
            .toList();
    if (result.isEmpty()
        || result.stream()
            .anyMatch(
                term ->
                    !term.complete()
                        || term.validUntil() == null
                        || !term.validUntil().isAfter(input.now()))) {
      throw incomplete("PARTICIPATION_ECONOMICS_UNKNOWN");
    }
    Set<UUID> scope = result.stream().map(EconomicTerms::placementId).collect(Collectors.toSet());
    if (scope.size() != result.size() || (placements != null && !scope.equals(placements))) {
      throw incomplete("PARTICIPATION_PLACEMENT_SCOPE_CHANGED");
    }
    return result;
  }

  private EconomicCalculator.PricingInput pricing(EconomicTerms term, BigDecimal price) {
    return new EconomicCalculator.PricingInput(
        Outcome.KEPT_PURCHASE,
        price,
        BigDecimal.ONE,
        term.sellerRevenueMultiplier(),
        term.sellerRevenueOffset(),
        term.buyerPriceMultiplier(),
        term.buyerPriceOffset(),
        input.cost().amount(),
        input.cost().extraExpense(),
        input.taxRate(),
        tariffs(term.charges()));
  }

  private static List<EconomicCalculator.TariffTerm> tariffs(
      List<EconomicTerms.TariffTerm> charges) {
    return charges.stream()
        .map(
            value ->
                new EconomicCalculator.TariffTerm(
                    value.code(),
                    EconomicCalculator.ChargeUnit.valueOf(value.unit()),
                    value.fixedAmount(),
                    value.revenueRate(),
                    value.confirmed(),
                    value.mandatory(),
                    value.verifiedQuantity()))
        .toList();
  }

  private BigDecimal referenceArgumentFor(EconomicTerms term, BigDecimal target) {
    return switch (context.rule().priceKind()) {
      case BASE_SELLER -> target;
      case EFFECTIVE_SELLER ->
          calculator.priceArgumentFor(
              target, term.effectiveSellerPriceMultiplier(), term.effectiveSellerPriceOffset());
      case BUYER_TOTAL ->
          calculator.priceArgumentFor(target, term.buyerPriceMultiplier(), term.buyerPriceOffset());
    };
  }

  private static BigDecimal optionalPrice(Prices prices, PriceField field) {
    return field == null ? null : prices.value(field);
  }

  private BigDecimal compared(
      PriceKind kind, Prices prices, EconomicTerms term, EconomicCalculator.PriceQuote quote) {
    return switch (kind) {
      case BASE_SELLER -> prices.base();
      case EFFECTIVE_SELLER ->
          calculator.transformedPrice(
              optionalPrice(prices, term.effectiveSellerPriceField()),
              term.effectiveSellerPriceMultiplier(),
              term.effectiveSellerPriceOffset());
      case BUYER_TOTAL -> quote.buyerTotal();
    };
  }

  private static boolean same(Prices left, Prices right) {
    return left.base().compareTo(right.base()) == 0
        && left.promotions().keySet().equals(right.promotions().keySet())
        && left.promotions().entrySet().stream()
            .allMatch(
                entry -> entry.getValue().compareTo(right.promotions().get(entry.getKey())) == 0);
  }

  private static void boundary(List<BigDecimal> boundaries, BigDecimal value) {
    if (value != null && value.signum() > 0) {
      boundaries.add(value);
    }
  }

  private void spend(long amount) {
    work += amount;
    if (work > DecisionEngine.MAX_WORK || Thread.currentThread().isInterrupted()) {
      throw incomplete("SEARCH_LIMIT");
    }
  }

  private static DecisionEngine.IncompleteSearch incomplete(String reason) {
    return new DecisionEngine.IncompleteSearch(reason);
  }
}
