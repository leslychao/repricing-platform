package ru.oritas.repricer.automation.decision;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.automation.runtime.ScenarioService;
import ru.oritas.repricer.marketplace.CommercialState;
import ru.oritas.repricer.marketplace.EconomicTerms;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.Scope;

/** A continuation keeps the original approval and proves only its already confirmed effects. */
@Service
public final class DecisionContinuationService {
  public enum Status {
    CURRENT,
    AWAITING_SOURCE,
    STALE
  }

  public record Result(Status status, DecisionService.Basis basis, CommercialState commercial) {}

  private final JdbcClient jdbc;
  private final MarketplaceReadService marketplace;
  private final DecisionValidityService validity;
  private final ScenarioService scenarios;
  private final PolicyService policies;
  private final Clock clock;

  public DecisionContinuationService(
      JdbcClient jdbc,
      MarketplaceReadService marketplace,
      DecisionValidityService validity,
      ScenarioService scenarios,
      PolicyService policies,
      Clock clock) {
    this.jdbc = jdbc;
    this.marketplace = marketplace;
    this.validity = validity;
    this.scenarios = scenarios;
    this.policies = policies;
    this.clock = clock;
  }

  public Result current(Scope scope, UUID decisionId, DecisionService.Snapshot snapshot, int step) {
    var original = snapshot.basis();
    if (step == 0) {
      return new Result(
          validity.current(scope, original) ? Status.CURRENT : Status.STALE,
          original,
          snapshot.evidence().commercial());
    }
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " SHARE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    if (!clock.instant().isBefore(original.validUntil())
        || !clock
            .instant()
            .isBefore(original.calculatedAt().plusSeconds(original.maximumAgeSeconds()))) {
      return stale(original);
    }
    var selected =
        snapshot.candidates().stream()
            .filter(candidate -> candidate.id().equals(snapshot.result().selectedCandidate()))
            .findFirst()
            .orElseThrow();
    if (step < 1 || step > selected.path().size()) {
      return stale(original);
    }
    record ConfirmedStep(int index, Instant confirmedAt) {}
    var prefix =
        jdbc.sql(
                """
                SELECT step,confirmed_at FROM automation_command
                  WHERE organization_id=:org AND account_id=:account
                  AND decision_id=:decision AND step<:step AND state='APPLIED' ORDER BY step
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("decision", decisionId)
            .param("step", step)
            .query(
                (row, index) ->
                    new ConfirmedStep(
                        row.getInt(1),
                        row.getTimestamp(2) == null ? null : row.getTimestamp(2).toInstant()))
            .list();
    if (prefix.size() != step) {
      return stale(original);
    }
    Instant confirmedAt = Instant.MIN;
    for (int i = 0; i < prefix.size(); i++) {
      var item = prefix.get(i);
      if (item.index() != i || item.confirmedAt() == null) {
        return stale(original);
      }
      if (item.confirmedAt().isAfter(confirmedAt)) {
        confirmedAt = item.confirmedAt();
      }
    }
    long scenarioRevision =
        scenarios.continuationRevision(
            scope, original.scenarioId(), decisionId, original.scenarioRevision(), step);
    if (scenarioRevision < 0) {
      return stale(original);
    }
    var offer = marketplace.offer(scope, original.offerId());
    var current = marketplace.commercialState(scope, original.offerId());
    if (!current.complete()
        || !clock.instant().isBefore(current.validUntil())
        || !sameCommercialTerms(snapshot.evidence().commercial(), current)
        || !sameEconomicTerms(
            snapshot.evidence().terms(), marketplace.economicTerms(scope, original.offerId()))) {
      return stale(original);
    }
    var resolved = policies.resolveForOffer(scope, original.offerId());
    if (!resolved.assignmentId().equals(original.assignmentId())
        || resolved.assignmentRevision() != original.assignmentRevision()
        || !resolved.active()) {
      return stale(original);
    }
    var derived =
        new DecisionService.Basis(
            original.offerId(),
            original.policyId(),
            original.policyVersion(),
            original.assignmentId(),
            original.assignmentRevision(),
            offer.revision(),
            original.costRevision(),
            original.taxRevision(),
            original.safetyRevision(),
            original.calculatedAt(),
            original.validUntil(),
            original.maximumAgeSeconds(),
            original.executable(),
            original.offerPriceRevision(),
            original.scenarioId(),
            scenarioRevision,
            original.finishingScenario(),
            marketplace.decisionFingerprint(scope, original.offerId()),
            original.competitorSegment(),
            original.competitorFingerprint());
    if (!validity.current(scope, derived)) {
      return stale(original);
    }
    Status status =
        classifyPrefix(snapshot, selected, step, current, offer.sellerPrice(), confirmedAt);
    return status == Status.STALE ? stale(original) : new Result(status, derived, current);
  }

  static Status classifyPrefix(
      DecisionService.Snapshot snapshot,
      DecisionEngine.Candidate selected,
      int step,
      CommercialState current,
      BigDecimal basePrice,
      Instant confirmedAt) {
    if (confirmedAt == null || current.observedAt() == null) {
      return Status.STALE;
    }
    if (matchesPrefix(snapshot, selected, step, current, basePrice)) {
      return current.observedAt().isBefore(confirmedAt) ? Status.AWAITING_SOURCE : Status.CURRENT;
    }
    // A readback can precede publication by the source owner; old proven prefixes may be awaited.
    for (int earlier = 0; earlier < step; earlier++) {
      if (matchesPrefix(snapshot, selected, earlier, current, basePrice)) {
        return Status.AWAITING_SOURCE;
      }
    }
    return Status.STALE;
  }

  private static Result stale(DecisionService.Basis basis) {
    return new Result(Status.STALE, basis, null);
  }

  static boolean matchesPrefix(
      DecisionService.Snapshot snapshot,
      DecisionEngine.Candidate selected,
      int length,
      CommercialState actual,
      BigDecimal actualBase) {
    var initial = snapshot.evidence().commercial();
    record ParticipationKey(UUID targetId, UUID promotionId) {}
    Map<ParticipationKey, BigDecimal> prices = new HashMap<>();
    initial
        .currentParticipations()
        .forEach(
            value ->
                prices.put(
                    new ParticipationKey(value.targetId(), value.promotionId()),
                    value.promotionPrice()));
    BigDecimal base = selected.effects().getFirst().currentBasePrice();
    for (int index = 0; index < length; index++) {
      var action = selected.path().get(index);
      switch (action.operation()) {
        case SET_BASE_PRICE -> base = action.absolutePrice();
        case SET_PROMO_PRICE, JOIN_PROMO ->
            prices.put(
                new ParticipationKey(action.targetId(), action.promoId()), action.absolutePrice());
        case LEAVE_PROMO ->
            prices.remove(new ParticipationKey(action.targetId(), action.promoId()));
      }
    }
    if (!sameMoney(base, actualBase) || actual.currentParticipations().size() != prices.size()) {
      return false;
    }
    for (var participation : actual.currentParticipations()) {
      var key = new ParticipationKey(participation.targetId(), participation.promotionId());
      if (participation.processing()
          || !prices.containsKey(key)
          || !sameMoney(prices.get(key), participation.promotionPrice())
          || (participation.basePrice() != null && !sameMoney(base, participation.basePrice()))) {
        return false;
      }
      var previous =
          initial.currentParticipations().stream()
              .filter(
                  value ->
                      value.promotionId().equals(participation.promotionId())
                          && value.targetId().equals(participation.targetId()))
              .findFirst();
      if (previous.isPresent()) {
        var value = previous.orElseThrow();
        if (!sameMoney(value.maximumQuantity(), participation.maximumQuantity())
            || !java.util.Objects.equals(value.endsAt(), participation.endsAt())
            || value.exitConfirmedAvailable() != participation.exitConfirmedAvailable()) {
          return false;
        }
      } else {
        var option =
            initial.availablePromotions().stream()
                .filter(
                    value ->
                        value.promotionId().equals(participation.promotionId())
                            && value.targetId().equals(participation.targetId()))
                .findFirst();
        if (option.isEmpty()
            || !sameMoney(option.orElseThrow().maximumQuantity(), participation.maximumQuantity())
            || !java.util.Objects.equals(option.orElseThrow().endsAt(), participation.endsAt())) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean sameCommercialTerms(CommercialState before, CommercialState after) {
    return before.offerId().equals(after.offerId())
        && before.profileRevision() == after.profileRevision()
        && Set.copyOf(before.targetIds()).equals(Set.copyOf(after.targetIds()))
        && before.availablePromotions().equals(after.availablePromotions())
        && before.supportedOperations().equals(after.supportedOperations())
        && java.util.Objects.equals(before.stockPoolId(), after.stockPoolId())
        && sameMoney(before.verifiedQuantity(), after.verifiedQuantity())
        && before.externallyBoundedLoss() == after.externallyBoundedLoss()
        && java.util.Objects.equals(before.lossBoundary(), after.lossBoundary())
        && sameMoney(before.basePriceStep(), after.basePriceStep())
        && before.confirmedPreservedFields().equals(after.confirmedPreservedFields());
  }

  private static boolean sameEconomicTerms(List<EconomicTerms> before, List<EconomicTerms> after) {
    return before.size() <= 200
        && after.size() <= 200
        && before.stream()
            .map(DecisionContinuationService::withoutObservation)
            .collect(java.util.stream.Collectors.toSet())
            .equals(
                after.stream()
                    .map(DecisionContinuationService::withoutObservation)
                    .collect(java.util.stream.Collectors.toSet()));
  }

  private static EconomicTerms withoutObservation(EconomicTerms value) {
    return new EconomicTerms(
        value.offerId(),
        value.placementId(),
        value.targetId(),
        value.segment(),
        0,
        Instant.EPOCH,
        value.complete(),
        value.sellerRevenueMultiplier(),
        value.sellerRevenueOffset(),
        value.buyerPriceMultiplier(),
        value.buyerPriceOffset(),
        value.charges(),
        value.applicableOutcomes(),
        value.outcomes(),
        value.participationIds(),
        value.sellerRevenueField(),
        value.buyerPriceField(),
        value.effectiveSellerPriceField(),
        value.effectiveSellerPriceMultiplier(),
        value.effectiveSellerPriceOffset());
  }

  private static boolean sameMoney(BigDecimal first, BigDecimal second) {
    return first == null ? second == null : second != null && first.compareTo(second) == 0;
  }
}
