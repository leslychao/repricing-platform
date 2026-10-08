package ru.oritas.repricer.automation.runtime;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.automation.policy.PolicySettings.Operation;
import ru.oritas.repricer.automation.policy.PolicySettings.PromoMode;
import ru.oritas.repricer.automation.policy.PolicySettings.Rule;
import ru.oritas.repricer.marketplace.CommercialGateway;
import ru.oritas.repricer.marketplace.CommercialState;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;

/** One rule for the disclosed completion scope and its later allowed regular state. */
public final class ScenarioCompletion {
  public record Target(
      UUID offerId, UUID targetId, Set<UUID> possibleExits, Set<String> managedFields) {
    public Target {
      possibleExits = Set.copyOf(possibleExits);
      managedFields = Set.copyOf(managedFields);
    }
  }

  public record Review(
      Instant firstActiveAt, Instant earliestCompletionAt, List<Target> targets, String digest) {
    public Review {
      targets = List.copyOf(targets);
    }
  }

  private ScenarioCompletion() {}

  public static Set<UUID> allowedRegularParticipations(Set<UUID> initial, Rule regular) {
    Set<UUID> allowed = new HashSet<>(initial);
    if (regular.promoMode() == PromoMode.CONSIDER
        || regular.promoMode() == PromoMode.ENSURE_SELECTED) {
      allowed.addAll(regular.selectedPromos());
    } else if (regular.promoMode() == PromoMode.FORBID_SELECTED) {
      allowed.removeAll(regular.selectedPromos());
    }
    return Set.copyOf(allowed);
  }

  public static Review review(
      PolicySettings settings,
      List<CommercialState> sources,
      Instant startsAt,
      Instant endsAt,
      Instant now) {
    if (settings.temporaryRule() == null) {
      throw rejected("SCENARIO_TEMPORARY_RULE_REQUIRED", "Временное правило не задано");
    }
    var runtime = new RuntimeRules();
    Instant first =
        runtime.nextScheduledAt(settings.schedule(), startsAt.isAfter(now) ? startsAt : now);
    if (!first.isBefore(endsAt)) {
      throw rejected("SCENARIO_SCHEDULE_CONFLICT", "В периоде эпизода нет разрешённого окна");
    }
    Instant completion = runtime.nextScheduledAt(settings.schedule(), endsAt);
    var temporary = settings.temporaryRule();
    var regular = settings.regularRule();
    if (!temporary.discoveryProfiles().isEmpty() || !regular.discoveryProfiles().isEmpty()) {
      throw rejected(
          "DISCOVERY_PROFILE_UNCONFIRMED", "Не подтверждён полный охват обнаружения акций");
    }
    List<Target> targets = new ArrayList<>();
    for (var source : sources) {
      for (UUID targetId : source.targetIds()) {
        Set<UUID> initial =
            source.currentParticipations().stream()
                .filter(p -> p.targetId().equals(targetId))
                .map(CommercialState.Participation::promotionId)
                .collect(java.util.stream.Collectors.toSet());
        Set<UUID> retained = allowedRegularParticipations(initial, regular);
        Set<UUID> possible = new HashSet<>(initial);
        if (temporary.promoMode() == PromoMode.CONSIDER
            || temporary.promoMode() == PromoMode.ENSURE_SELECTED) {
          possible.addAll(temporary.selectedPromos());
        }
        possible.removeAll(retained);
        for (UUID promotion : possible) {
          if (regular.protectedPromos().contains(promotion)
              || temporary.protectedPromos().contains(promotion)) {
            throw rejected("SCENARIO_PROTECTED_EXIT", "Защищённое участие препятствует завершению");
          }
          var participation =
              source.currentParticipations().stream()
                  .filter(p -> p.targetId().equals(targetId) && p.promotionId().equals(promotion))
                  .findFirst();
          boolean exitConfirmed =
              participation
                  .map(CommercialState.Participation::exitConfirmedAvailable)
                  .orElseGet(
                      () ->
                          source.availablePromotions().stream()
                              .anyMatch(
                                  p ->
                                      p.targetId().equals(targetId)
                                          && p.promotionId().equals(promotion)
                                          && p.exitConfirmedAvailable()));
          if (!settings.allowedOperations().contains(Operation.LEAVE_PROMO)
              || !source.supportedOperations().contains(CommercialGateway.Operation.LEAVE_PROMO)
              || !exitConfirmed) {
            throw rejected(
                "SCENARIO_EXIT_UNCONFIRMED",
                "Разрешённый выход из временного участия не подтверждён");
          }
        }
        Set<String> fields = new HashSet<>();
        if (settings.allowedOperations().contains(Operation.SET_BASE_PRICE)) {
          fields.add("BASE_PRICE");
        }
        if (settings.allowedOperations().contains(Operation.SET_PROMO_PRICE)) {
          fields.add("PROMOTION_PRICE");
        }
        targets.add(new Target(source.offerId(), targetId, possible, fields));
        if (targets.size() > 1000) {
          throw rejected("SCENARIO_SCOPE_LIMIT", "Охват эпизода превышает 1000 канонических целей");
        }
      }
    }
    String targetDigest =
        targets.stream()
            .map(
                target ->
                    target.offerId()
                        + ":"
                        + target.targetId()
                        + ":"
                        + target.possibleExits().stream().sorted().toList()
                        + ":"
                        + target.managedFields().stream().sorted().toList())
            .sorted()
            .collect(java.util.stream.Collectors.joining(";"));
    String digest =
        IdempotencyService.sha256(
            (startsAt + "|" + endsAt + "|" + completion + "|" + targetDigest)
                .getBytes(StandardCharsets.UTF_8));
    return new Review(first, completion, targets, digest);
  }

  private static BusinessException rejected(String code, String message) {
    return new BusinessException(code, 409, message);
  }
}
