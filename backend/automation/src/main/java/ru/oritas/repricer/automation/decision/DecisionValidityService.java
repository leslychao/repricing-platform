package ru.oritas.repricer.automation.decision;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.marketplace.MarketplaceCompetitorService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Scope;

/** Canonical captured-head comparison for locked admission and SQL-composed read projections. */
@Service
public final class DecisionValidityService {
  private record Entry(UUID id, DecisionService.Basis basis) {}

  public record Projection(String sql, Map<String, Object> parameters) {}

  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final Clock clock;
  private final MarketplaceReadService marketplace;
  private final MarketplaceCompetitorService competitors;
  private final PolicyService policies;

  public DecisionValidityService(
      JdbcClient jdbc,
      JsonCodec json,
      Clock clock,
      MarketplaceReadService marketplace,
      MarketplaceCompetitorService competitors,
      PolicyService policies) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.marketplace = marketplace;
    this.competitors = competitors;
    this.policies = policies;
  }

  public boolean current(Scope scope, DecisionService.Basis basis) {
    return current(scope, Map.of(basis.offerId(), basis)).contains(basis.offerId());
  }

  public Set<UUID> current(Scope scope, Map<UUID, DecisionService.Basis> bases) {
    ScopeTransactionRunner.requireCurrent(scope);
    if (bases.size() > 200) {
      throw new IllegalArgumentException("At most 200 calculation bases may be checked together");
    }
    if (bases.isEmpty()) {
      return Set.of();
    }
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " SHARE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    List<Entry> entries =
        bases.entrySet().stream()
            .map(entry -> new Entry(entry.getKey(), entry.getValue()))
            .toList();
    Projection projection =
        projection(
            scope,
            """
            SELECT (value->>'id')::uuid AS id,value->'basis' AS b
            FROM jsonb_array_elements(CAST(:bases AS jsonb))
            """);
    return Set.copyOf(
        jdbc.sql(projection.sql() + " FOR SHARE OF o,p,a,c,t,s")
            .params(projection.parameters())
            .param("bases", json.encode(entries))
            .query(UUID.class)
            .list());
  }

  /** capturedSql is an owner-built SELECT of id,b (JSON basis), never client SQL. */
  public Projection projection(Scope scope, String capturedSql) {
    ScopeTransactionRunner.requireCurrent(scope);
    var assignments = policies.effectiveAssignmentProjection(scope);
    var inputs = marketplace.decisionInputsProjection(scope);
    var references = competitors.referenceProjection(scope);
    Map<String, Object> parameters = new LinkedHashMap<>(inputs.parameters());
    parameters.putAll(references.parameters());
    parameters.putAll(assignments.parameters());
    parameters.put("validityOrg", scope.organizationId());
    parameters.put("validityAccount", scope.requireAccount());
    parameters.put("validityNow", Timestamp.from(clock.instant()));
    String sql =
        "WITH captured AS ("
            + capturedSql
            + ") "
            + """
            SELECT captured.id FROM captured
              JOIN marketplace_offer o ON o.id=(b->>'offerId')::uuid
              JOIN automation_policy p ON p.organization_id=o.organization_id
                AND p.id=(b->>'policyId')::uuid
              JOIN automation_assignment a ON a.organization_id=o.organization_id
                AND a.account_id=o.account_id AND a.id=(b->>'assignmentId')::uuid
              JOIN (%s) effective ON effective.offer_id=o.id AND effective.assignment_id=a.id
              JOIN economics_cost_head c ON c.organization_id=o.organization_id
                AND c.account_id=o.account_id AND c.offer_id=o.id
              JOIN economics_tax_head t ON t.organization_id=o.organization_id
                AND t.account_id=o.account_id
              JOIN economics_safety_envelope s ON s.organization_id=o.organization_id
                AND s.account_id=o.account_id AND s.current_revision
              JOIN (%s) inputs ON inputs.offer_id=o.id
              LEFT JOIN (%s) reference ON reference.offer_id=o.id
                AND reference.segment=b->>'competitorSegment'
            WHERE o.organization_id=:validityOrg AND o.account_id=:validityAccount
              AND :validityNow<(b->>'validUntil')::timestamptz
              AND :validityNow<(b->>'calculatedAt')::timestamptz
                + make_interval(secs=>(b->>'maximumAgeSeconds')::integer)
              AND o.revision=(b->>'offerRevision')::bigint AND p.status='ACTIVE'
              AND (((b->>'scenarioId') IS NULL AND p.version=(b->>'policyVersion')::bigint)
                OR EXISTS(SELECT 1 FROM automation_scenario r WHERE r.organization_id=:validityOrg
                  AND r.account_id=:validityAccount AND r.id=(b->>'scenarioId')::uuid
                  AND r.revision=(b->>'scenarioRevision')::bigint
                  AND r.policy_version=(b->>'policyVersion')::bigint AND r.state<>'FINISHED'))
              AND a.revision=(b->>'assignmentRevision')::bigint AND a.enabled AND NOT a.paused
              AND c.revision=(b->>'costRevision')::bigint
              AND t.revision=(b->>'taxRevision')::bigint
              AND s.revision=(b->>'safetyRevision')::bigint
              AND COALESCE((SELECT bounds.revision FROM economics_offer_price_bounds bounds
                WHERE bounds.organization_id=o.organization_id AND bounds.account_id=o.account_id
                  AND bounds.offer_id=o.id AND bounds.current_revision),0)
                  =(b->>'offerPriceRevision')::bigint
              AND inputs.complete AND :validityNow<inputs.valid_until
              AND inputs.fingerprint=b->>'marketplaceFingerprint'
              AND b->>'competitorFingerprint' IS NOT NULL
              AND ((b->>'competitorSegment') IS NULL OR (COALESCE(reference.complete,true)
                AND COALESCE(reference.fingerprint,'EMPTY')=b->>'competitorFingerprint'))
            """
                .formatted(assignments.sql(), inputs.sql(), references.sql());
    return new Projection(sql, parameters);
  }

  /** Nonfinancial index freshness compares only buyer inputs and the whole market reference. */
  public Projection comparisonProjection(Scope scope, String capturedSql) {
    ScopeTransactionRunner.requireCurrent(scope);
    var buyers = marketplace.buyerComparisonProjection(scope);
    var references = competitors.referenceProjection(scope);
    Map<String, Object> parameters = new LinkedHashMap<>(buyers.parameters());
    parameters.putAll(references.parameters());
    parameters.put("comparisonNow", Timestamp.from(clock.instant()));
    String sql =
        "WITH captured AS ("
            + capturedSql
            + ") "
            + """
            SELECT captured.id FROM captured
            JOIN (%s) buyer ON buyer.offer_id=captured.offer_id
              AND buyer.placement_id=(comparison->>'placementId')::uuid
            JOIN (%s) reference ON reference.offer_id=captured.offer_id
              AND reference.segment=comparison->>'segment'
            WHERE :comparisonNow<(comparison->>'validUntil')::timestamptz
              AND :comparisonNow<buyer.valid_until
              AND buyer.fingerprint=comparison->>'sourceFingerprint'
              AND reference.complete AND reference.fingerprint=comparison->>'referenceFingerprint'
            """
                .formatted(buyers.sql(), references.sql());
    return new Projection(sql, parameters);
  }
}
