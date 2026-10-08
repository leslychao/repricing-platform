package ru.oritas.repricer.app;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.marketplace.MarketplaceCompetitorService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.Scope;

/** Coordinates command identity with the canonical observation owner in one scoped transaction. */
@Service
public final class CompetitorApplicationService {
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final IdempotencyService requests;
  private final MarketplaceCompetitorService competitors;
  private final MarketplaceReadService marketplace;

  public CompetitorApplicationService(
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      IdempotencyService requests,
      MarketplaceCompetitorService competitors,
      MarketplaceReadService marketplace) {
    this.transactions = transactions;
    this.authorization = authorization;
    this.requests = requests;
    this.competitors = competitors;
    this.marketplace = marketplace;
  }

  public MarketplaceCompetitorService.Observation create(
      Scope scope,
      MarketplaceCompetitorService.Input input,
      long expectedRevision,
      UUID requestId) {
    return transactions.run(
        scope,
        () -> {
          authorize(scope, input.offerId());
          if (expectedRevision != 0) {
            throw new BusinessException("STALE_REVISION", 409, "Новое наблюдение имеет редакцию 0");
          }
          return requests.execute(
              scope,
              "competitor.create",
              requestId,
              input,
              MarketplaceCompetitorService.Observation.class,
              () -> competitors.add(scope, input));
        });
  }

  public MarketplaceCompetitorService.Observation correct(
      Scope scope,
      UUID id,
      MarketplaceCompetitorService.Input input,
      long expectedRevision,
      UUID requestId) {
    return transactions.run(
        scope,
        () -> {
          authorize(scope, input.offerId());
          competitors.get(scope, id);
          record Intent(MarketplaceCompetitorService.Input observation, long revision) {}
          return requests.execute(
              scope,
              "competitor.correct:" + id,
              requestId,
              new Intent(input, expectedRevision),
              MarketplaceCompetitorService.Observation.class,
              () -> competitors.correct(scope, id, input, expectedRevision));
        });
  }

  public MarketplaceCompetitorService.Observation revoke(
      Scope scope, UUID id, long expectedRevision, UUID requestId) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("competitor.write", "competitor.read"));
          var observation = competitors.get(scope, id);
          authorize(scope, observation.offerId());
          return requests.execute(
              scope,
              "competitor.revoke:" + id,
              requestId,
              expectedRevision,
              MarketplaceCompetitorService.Observation.class,
              () -> {
                competitors.revoke(scope, id, expectedRevision);
                return competitors.get(scope, id);
              });
        });
  }

  private void authorize(Scope scope, UUID offerId) {
    authorization.requireLocked(
        scope, Set.of("competitor.write", "competitor.read", "catalog.read"));
    marketplace.offer(scope, offerId);
  }
}
