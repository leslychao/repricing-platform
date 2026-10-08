package ru.oritas.repricer.automation.decision;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.automation.policy.PolicySettings.Operation;
import ru.oritas.repricer.automation.policy.PolicySettings.PriceKind;
import ru.oritas.repricer.automation.policy.PolicySettings.Strategy;
import ru.oritas.repricer.automation.reference.MarketReferenceEvaluator;
import ru.oritas.repricer.automation.runtime.RuntimeRules;
import ru.oritas.repricer.automation.runtime.RuntimeRules.ScenarioState;
import ru.oritas.repricer.automation.runtime.RuntimeService;
import ru.oritas.repricer.automation.runtime.ScenarioService;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.economics.RiskLimitService;
import ru.oritas.repricer.economics.SafetyEnvelopeService;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Outcome;
import ru.oritas.repricer.marketplace.CommercialState;
import ru.oritas.repricer.marketplace.EconomicTerms;
import ru.oritas.repricer.marketplace.MarketplaceCompetitorService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Scope;

/** Builds calculation inputs exclusively from current authorized canonical owners. */
@Service
public class DecisionContextAssembler {
  public record FixedTarget(PriceKind priceKind, BigDecimal target) {
    public FixedTarget {
      if (priceKind == null || target == null || target.signum() <= 0 || target.scale() > 12) {
        throw new IllegalArgumentException("Invalid fixed price intent");
      }
    }
  }

  public record PreviewRequest(
      UUID offerId,
      UUID targetId,
      UUID policyId,
      boolean draft,
      FixedTarget fixedTarget,
      PolicyService.CapturedOffer expectedOffer) {
    public PreviewRequest(UUID offerId, UUID targetId, UUID policyId, boolean draft) {
      this(offerId, targetId, policyId, draft, null);
    }

    public PreviewRequest(
        UUID offerId, UUID targetId, UUID policyId, boolean draft, FixedTarget fixedTarget) {
      this(offerId, targetId, policyId, draft, fixedTarget, null);
    }

    public PreviewRequest {
      if (offerId == null
          || (draft && policyId == null)
          || (expectedOffer != null && !offerId.equals(expectedOffer.offerId()))) {
        throw new IllegalArgumentException("An offer and explicit draft policy are required");
      }
    }
  }

  public record Evidence(
      PolicySettings settings,
      EconomicsService.CostView costs,
      EconomicsService.TaxView tax,
      SafetyEnvelopeService.Envelope safety,
      CommercialState commercial,
      List<EconomicTerms> terms,
      List<MarketReferenceEvaluator.Observation> competitors,
      SafetyEnvelopeService.OfferBounds offerBounds,
      RiskLimitService.Permission economicPermission,
      BigDecimal availableQuantity,
      long stockRevision,
      Instant stockObservedAt,
      boolean effectiveAssignment,
      Map<UUID, MarketplaceReadService.BuyerComparisonHead> buyerComparisonInputs) {
    public Evidence {
      terms = List.copyOf(terms);
      competitors = List.copyOf(competitors);
      buyerComparisonInputs =
          buyerComparisonInputs == null ? Map.of() : Map.copyOf(buyerComparisonInputs);
    }
  }

  public record Assembly(
      DecisionService.Basis basis,
      DecisionEngine.Context context,
      Iterable<DecisionEngine.Candidate> candidates,
      Evidence evidence) {}

  private final MarketplaceReadService marketplace;
  private final EconomicsService economics;
  private final SafetyEnvelopeService safety;
  private final PolicyService policies;
  private final MarketplaceCompetitorService competitors;
  private final AuthorizationService authorization;
  private final Clock clock;
  private final RuntimeService runtime;
  private final ScenarioService scenarios;
  private final RiskLimitService risks;
  private final ResourceService resources;
  private final EconomicCalculator calculator = new EconomicCalculator();
  private final DecisionEngine engine = new DecisionEngine();

  public DecisionContextAssembler(
      MarketplaceReadService marketplace,
      EconomicsService economics,
      SafetyEnvelopeService safety,
      PolicyService policies,
      MarketplaceCompetitorService competitors,
      AuthorizationService authorization,
      Clock clock,
      RuntimeService runtime,
      ScenarioService scenarios,
      RiskLimitService risks,
      ResourceService resources) {
    this.marketplace = marketplace;
    this.economics = economics;
    this.safety = safety;
    this.policies = policies;
    this.competitors = competitors;
    this.authorization = authorization;
    this.clock = clock;
    this.runtime = runtime;
    this.scenarios = scenarios;
    this.risks = risks;
    this.resources = resources;
  }

  public Assembly assemble(Scope scope, PreviewRequest request) {
    authorization.requireActorLocked(
        scope, scope.subjectId(), DecisionAuthorization.preview(request));
    if (request.expectedOffer() != null) {
      policies.requireCaptured(scope, request.expectedOffer());
    }
    var offer = marketplace.offer(scope, request.offerId());
    var commercial = marketplace.commercialState(scope, request.offerId());
    if (!commercial.complete()
        || commercial.basePriceStep() == null
        || commercial.basePriceStep().signum() <= 0
        || commercial.targetIds().isEmpty()) {
      throw unavailable("COMMERCIAL_SCOPE_UNKNOWN");
    }
    UUID target = request.targetId();
    if (target == null) {
      if (commercial.targetIds().size() != 1) {
        throw unavailable("EXPLICIT_PRICE_TARGET_REQUIRED");
      }
      target = commercial.targetIds().getFirst();
    }
    if (!commercial.targetIds().contains(target)) {
      throw unavailable("PRICE_TARGET_NOT_IN_SCOPE");
    }
    PolicyService.ActivePolicy active = policies.resolveForOffer(scope, request.offerId());
    PolicySettings settings = active.settings();
    UUID policyId = active.policyId();
    long policyVersion = active.version();
    if (request.policyId() != null) {
      PolicyService.Policy explicit = policies.get(scope, request.policyId());
      settings = request.draft() ? explicit.draft() : explicit.settings();
      policyId = explicit.id();
      policyVersion = explicit.version();
      if (settings == null) {
        throw unavailable("POLICY_UNPUBLISHED");
      }
    }
    ScenarioService.Run run =
        request.policyId() == null && !request.draft()
            ? scenarios.active(scope, offer.id(), target).orElse(null)
            : null;
    if (run != null) {
      run = scenarios.progress(scope, run.id());
      if (run.state() == ScenarioState.FINISHED) {
        run = null;
      } else {
        settings = run.settings();
        policyId = run.policyId();
        policyVersion = run.policyVersion();
      }
    }
    Instant now = clock.instant();
    LocalDate date = now.atZone(marketplace.zoneId(scope)).toLocalDate();
    var costs = economics.getCost(scope, request.offerId());
    var tax = economics.getTax(scope);
    var envelope = safety.get(scope);
    var offerBounds = safety.offerBounds(scope, offer.id());
    BigDecimal minimumPrice =
        offerBounds.enabled() && offerBounds.minimumPrice() != null
            ? envelope.minimumPrice().max(offerBounds.minimumPrice())
            : envelope.minimumPrice();
    BigDecimal maximumPrice =
        offerBounds.enabled() && offerBounds.maximumPrice() != null
            ? envelope.maximumPrice().min(offerBounds.maximumPrice())
            : envelope.maximumPrice();
    if (minimumPrice.compareTo(maximumPrice) > 0) {
      throw unavailable("EMPTY_PRICE_INTERSECTION");
    }
    var cost =
        costs.intervals().stream()
            .filter(
                i ->
                    !date.isBefore(i.validFrom())
                        && (i.validUntil() == null || date.isBefore(i.validUntil())))
            .findFirst()
            .orElseThrow(() -> unavailable("COST_REQUIRED"));
    var taxInterval =
        tax.intervals().stream()
            .filter(
                i ->
                    !date.isBefore(i.validFrom())
                        && (i.validUntil() == null || date.isBefore(i.validUntil())))
            .findFirst()
            .orElseThrow(() -> unavailable("TAX_REQUIRED"));
    List<EconomicTerms> allTerms = marketplace.economicTerms(scope, request.offerId());
    if (allTerms.isEmpty() || allTerms.size() > 200) {
      throw unavailable(allTerms.isEmpty() ? "TARIFFS_REQUIRED" : "ECONOMIC_SCOPE_LIMIT");
    }
    UUID selectedTarget = target;
    Set<UUID> currentPromos =
        commercial.currentParticipations().stream()
            .filter(p -> p.targetId().equals(selectedTarget))
            .map(CommercialState.Participation::promotionId)
            .collect(java.util.stream.Collectors.toSet());
    List<EconomicTerms> terms =
        allTerms.stream()
            .filter(
                t ->
                    t.targetId().equals(selectedTarget)
                        && t.participationIds().equals(currentPromos))
            .toList();
    if (terms.isEmpty()
        || terms.stream()
            .anyMatch(
                t -> !t.complete() || t.validUntil() == null || !t.validUntil().isAfter(now))) {
      throw unavailable("ECONOMICS_UNCONFIRMED");
    }
    boolean temporary =
        run != null
            && run.state() != ScenarioState.FINISHING
            && !now.isBefore(run.startsAt())
            && now.isBefore(run.endsAt());
    RiskLimitService.Permission permission =
        temporary ? risks.forRun(scope, run.id()).orElse(null) : null;
    BigDecimal temporaryFloor =
        permission != null
                && permission.status().equals("ACTIVE")
                && !now.isBefore(permission.startsAt())
                && now.isBefore(permission.endsAt())
                && permission.scopeDigest().equals(run.scopeDigest())
            ? permission.keptProfitFloor()
            : null;
    PolicySettings.Rule rule = temporary ? settings.temporaryRule() : settings.regularRule();
    if (request.fixedTarget() != null) {
      var fixed = request.fixedTarget();
      rule =
          new PolicySettings.Rule(
              Strategy.HOLD_PRICE,
              fixed.priceKind(),
              fixed.target(),
              fixed.target(),
              fixed.target(),
              null,
              PolicySettings.Unreachable.KEEP_SAFE,
              null,
              null,
              PolicySettings.PromoMode.PRESERVE,
              Set.of(),
              settings.regularRule().protectedPromos());
    }
    List<MarketReferenceEvaluator.Observation> observations = List.of();
    BigDecimal competitorTarget = null;
    String competitorSegment = null;
    String competitorFingerprint = "";
    Instant referenceValidUntil = null;
    if (rule.strategy() == Strategy.FOLLOW_COMPETITOR) {
      competitorSegment = rule.competitor().segment();
      var head = competitors.referenceHead(scope, request.offerId(), competitorSegment);
      if (!head.complete()) {
        throw unavailable("COMPETITOR_SOURCE_LIMIT");
      }
      competitorFingerprint = head.fingerprint();
      observations =
          competitors.latest(scope, request.offerId(), rule.competitor().segment()).stream()
              .map(
                  o ->
                      new MarketReferenceEvaluator.Observation(
                          o.id(),
                          o.sourceId(),
                          o.segment(),
                          o.observedAt(),
                          o.revision(),
                          o.revoked(),
                          o.ownOffer(),
                          o.inStock(),
                          o.price(),
                          o.delivery()))
              .toList();
      var reference = new MarketReferenceEvaluator().evaluate(observations, rule.competitor(), now);
      if (reference.available()) {
        competitorTarget = reference.target();
        Set<UUID> used = Set.copyOf(reference.observations());
        long maximumAge = rule.competitor().maxAgeSeconds();
        referenceValidUntil =
            observations.stream()
                .filter(item -> used.contains(item.id()))
                .map(item -> item.observedAt().plusSeconds(maximumAge))
                .min(Comparator.naturalOrder())
                .orElseThrow();
      } else if (rule.reserve() != null) {
        rule = rule.reserve();
      }
    }
    if (offer.sellerPrice() == null || offer.sellerPrice().signum() <= 0) {
      throw unavailable("CURRENT_PRICE_UNKNOWN");
    }
    boolean stockComplete = false;
    boolean noStock = false;
    boolean lowStock = false;
    RuntimeService.TargetState runtimeState =
        runtime.get(scope, offer.id(), target, settings.stability());
    Instant stockValidUntil = now;
    BigDecimal availableQuantity = null;
    long stockRevision = 0;
    Instant stockObservedAt = null;
    if (commercial.stockPoolId() != null) {
      var availability = resources.availability(scope, commercial.stockPoolId());
      var stock = availability.stock();
      availableQuantity = availability.quantity();
      stockRevision = stock.revision();
      stockObservedAt = stock.observedAt();
      stockComplete =
          stock.complete()
              && stock.obligationsCovered() != null
              && stock.validUntil().isAfter(now)
              && availableQuantity != null;
      runtimeState =
          runtime.observeStock(
              scope,
              offer.id(),
              target,
              availableQuantity,
              stockComplete,
              offer.sellerPrice(),
              settings);
      RuntimeRules.StockState state = runtimeState.stockState();
      stockValidUntil = stock.validUntil();
      noStock = state == RuntimeRules.StockState.NO_STOCK;
      lowStock = state == RuntimeRules.StockState.LOW_STOCK;
    }
    Set<Operation> supported =
        commercial.supportedOperations().stream()
            .map(
                op ->
                    switch (op) {
                      case SET_PRICE -> Operation.SET_BASE_PRICE;
                      case SET_PROMO_PRICE -> Operation.SET_PROMO_PRICE;
                      case JOIN_PROMO -> Operation.JOIN_PROMO;
                      case LEAVE_PROMO -> Operation.LEAVE_PROMO;
                    })
            .filter(settings.allowedOperations()::contains)
            .collect(java.util.stream.Collectors.toSet());
    Instant validUntil =
        allTerms.stream()
            .map(EconomicTerms::validUntil)
            .filter(java.util.Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElseThrow();
    validUntil =
        validUntil.isBefore(commercial.validUntil()) ? validUntil : commercial.validUntil();
    Instant age = now.plusSeconds(settings.stability().decisionAgeSeconds());
    validUntil = validUntil.isBefore(age) ? validUntil : age;
    validUntil = validUntil.isBefore(stockValidUntil) ? validUntil : stockValidUntil;
    if (referenceValidUntil != null && referenceValidUntil.isBefore(validUntil)) {
      validUntil = referenceValidUntil;
    }
    var zone = marketplace.zoneId(scope);
    if (cost.validUntil() != null) {
      Instant costEnd = cost.validUntil().atStartOfDay(zone).toInstant();
      validUntil = validUntil.isBefore(costEnd) ? validUntil : costEnd;
    }
    if (taxInterval.validUntil() != null) {
      Instant taxEnd = taxInterval.validUntil().atStartOfDay(zone).toInstant();
      validUntil = validUntil.isBefore(taxEnd) ? validUntil : taxEnd;
    }
    boolean executable =
        request.policyId() == null
            && !request.draft()
            && !active.mode().equals("PREVIEW")
            && settings.stability().writerMode() != null;
    executable &= run == null || !now.isBefore(run.startsAt());
    if (temporary && run.endsAt().isBefore(validUntil)) {
      validUntil = run.endsAt();
    }
    var basis =
        new DecisionService.Basis(
            offer.id(),
            policyId,
            policyVersion,
            active.assignmentId(),
            run == null ? active.assignmentRevision() : run.assignmentRevision(),
            offer.revision(),
            costs.revision(),
            tax.revision(),
            envelope.revision(),
            now,
            validUntil,
            settings.stability().decisionAgeSeconds(),
            executable,
            offerBounds.revision(),
            run == null ? null : run.id(),
            run == null ? 0 : run.revision(),
            run != null && run.state() == ScenarioState.FINISHING,
            marketplace.decisionFingerprint(scope, offer.id()),
            competitorSegment,
            competitorFingerprint);
    boolean scheduleOpen = new RuntimeRules().withinSchedule(settings.schedule(), now);
    var context =
        new DecisionEngine.Context(
            rule,
            competitorTarget,
            supported,
            currentPromos,
            Set.of(target),
            true,
            !active.active() || !scheduleOpen || runtimeState.paused(),
            stockComplete,
            lowStock,
            noStock,
            run != null && run.state() == ScenarioState.FINISHING,
            true,
            DecisionEngine.MAX_WORK,
            settings.allowProtectiveCorrection(),
            settings.stability().significantStep(),
            lowStock && settings.stockProtection().raiseRatio() != null
                ? new DecisionEngine.StockRaise(
                    runtimeState.episodeBasePrice(),
                    new RuntimeRules()
                        .protectivePrice(
                            runtimeState.episodeBasePrice(), settings.stockProtection())
                        .divide(commercial.basePriceStep(), 0, java.math.RoundingMode.CEILING)
                        .multiply(commercial.basePriceStep()))
                : null);
    var candidates =
        new CommercialCandidateGenerator(
            new CommercialCandidateGenerator.Inputs(
                offer.id(),
                target,
                offer.sellerPrice(),
                commercial,
                allTerms,
                cost,
                taxInterval.rate(),
                envelope,
                settings,
                minimumPrice,
                maximumPrice,
                settings
                    .stability()
                    .movementBudget()
                    .subtract(runtimeState.movementUsed())
                    .max(BigDecimal.ZERO),
                temporaryFloor,
                runtimeState.episodeBasePrice(),
                run != null && run.state() == ScenarioState.FINISHING
                    ? scenarios.completionParticipations(scope, run.id(), offer.id(), target)
                    : currentPromos,
                now,
                availableQuantity),
            context);
    return new Assembly(
        basis,
        context,
        candidates,
        new Evidence(
            settings,
            costs,
            tax,
            envelope,
            commercial,
            allTerms,
            observations,
            offerBounds,
            permission,
            availableQuantity,
            stockRevision,
            stockObservedAt,
            DecisionAuthorization.effectiveAssignment(request),
            marketplace.buyerComparisonInputs(scope, offer.id())));
  }

  public static EconomicCalculator.PricingInput quantityPricing(
      DecisionService.Snapshot snapshot, UUID candidateId, UUID placementId) {
    var candidate =
        snapshot.candidates().stream()
            .filter(value -> value.id().equals(candidateId))
            .findFirst()
            .orElseThrow(() -> unavailable("CANDIDATE_NOT_FOUND"));
    var effects =
        candidate.effects().stream()
            .filter(value -> placementId.equals(value.placementId()))
            .toList();
    if (effects.size() != 1) {
      throw unavailable("EXPLICIT_PLACEMENT_REQUIRED");
    }
    var effect = effects.getFirst();
    var terms =
        snapshot.evidence().terms().stream()
            .filter(
                value ->
                    value.placementId().equals(placementId)
                        && value.targetId().equals(effect.targetId())
                        && value.participationIds().equals(candidate.participations()))
            .toList();
    if (terms.size() != 1 || !terms.getFirst().complete()) {
      throw unavailable("ECONOMICS_UNCONFIRMED");
    }
    var kept =
        effect.outcomes().stream()
            .filter(value -> value.input().outcome() == Outcome.KEPT_PURCHASE)
            .findFirst()
            .orElseThrow(() -> unavailable("KEPT_PURCHASE_REQUIRED"))
            .input();
    var field = terms.getFirst().sellerRevenueField();
    if (field == null) {
      throw unavailable("PRICE_FIELD_BINDING_UNKNOWN");
    }
    BigDecimal price =
        field.kind() == EconomicTerms.PriceFieldKind.BASE_SELLER
            ? effect.basePrice()
            : candidate.promotionPrices().get(field.promotionId());
    if (price == null) {
      throw unavailable("PRICE_FIELD_OUTSIDE_PARTICIPATION");
    }
    return new EconomicCalculator.PricingInput(
        Outcome.KEPT_PURCHASE,
        price,
        BigDecimal.ONE,
        terms.getFirst().sellerRevenueMultiplier(),
        terms.getFirst().sellerRevenueOffset(),
        terms.getFirst().buyerPriceMultiplier(),
        terms.getFirst().buyerPriceOffset(),
        kept.costConsumed(),
        kept.ownExpense(),
        kept.taxRate(),
        tariffs(terms.getFirst().charges()));
  }

  private static List<EconomicCalculator.TariffTerm> tariffs(List<EconomicTerms.TariffTerm> terms) {
    return terms.stream()
        .map(
            t ->
                new EconomicCalculator.TariffTerm(
                    t.code(),
                    EconomicCalculator.ChargeUnit.valueOf(t.unit()),
                    t.fixedAmount(),
                    t.revenueRate(),
                    t.confirmed(),
                    t.mandatory(),
                    t.verifiedQuantity()))
        .toList();
  }

  private static BusinessException unavailable(String reason) {
    return new BusinessException(reason, 422, "Для расчёта нужны подтверждённые данные: " + reason);
  }
}
