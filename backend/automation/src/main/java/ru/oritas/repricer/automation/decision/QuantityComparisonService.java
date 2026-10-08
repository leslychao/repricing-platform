package ru.oritas.repricer.automation.decision;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.economics.calculation.QuantityComparison;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;

/** Immutable conditional quantities bound to the same saved decision and explicit placement. */
@Service
public final class QuantityComparisonService {
  public record Saved(
      UUID id,
      UUID decisionId,
      UUID placementId,
      Instant createdAt,
      int baselineQuantity,
      String status,
      String reason,
      UUID baselineCandidateId,
      UUID candidateId,
      BigDecimal availableQuantity,
      BigDecimal baselineUnitProfit,
      BigDecimal candidateUnitProfit,
      QuantityComparison.Comparison comparison,
      String condition,
      long stockRevision,
      Instant stockObservedAt,
      Map<String, Long> sourceRevisions) {}

  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final JsonCodec json;
  private final Clock clock;
  private final OutboxService outbox;
  private final QuantityComparison calculator = new QuantityComparison();

  public QuantityComparisonService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      JsonCodec json,
      Clock clock,
      OutboxService outbox) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.json = json;
    this.clock = clock;
    this.outbox = outbox;
  }

  public void publishDefault(Scope scope, UUID decisionId, DecisionService.Snapshot snapshot) {
    var placements = placements(snapshot);
    UUID placement = placements.size() == 1 ? placements.getFirst() : null;
    persist(scope, decisionId, decisionId, placement, 100, snapshot);
  }

  public Saved create(
      Scope scope,
      UUID decisionId,
      UUID placementId,
      int quantity,
      DecisionService.Snapshot snapshot) {
    authorization.require(scope, "finance.read");
    authorization.require(scope, "decision.preview");
    requireDecision(scope, decisionId);
    if (placementId == null || !placements(snapshot).contains(placementId)) {
      throw new BusinessException(
          "INVALID_PLACEMENT", 422, "Выберите размещение сохранённого решения");
    }
    Saved saved = persist(scope, UUID.randomUUID(), decisionId, placementId, quantity, snapshot);
    outbox.emit(
        scope,
        saved.id().toString(),
        "QUANTITY_COMPARISON_CREATED",
        new OutboxService.EntityChange("decisions", decisionId, 1));
    return saved;
  }

  public Page<Saved> page(Scope scope, UUID decisionId, int page, int size) {
    authorization.require(scope, "finance.read");
    requireDecision(scope, decisionId);
    int offset = Page.offset(page, size);
    long total =
        jdbc.sql(
                """
                SELECT count(*) FROM automation_quantity_comparison
                WHERE organization_id=:org AND account_id=:account AND decision_id=:decision
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("decision", decisionId)
            .query(Long.class)
            .single();
    var rows =
        jdbc.sql(
                """
                SELECT result::text FROM automation_quantity_comparison
                WHERE organization_id=:org AND account_id=:account AND decision_id=:decision
                ORDER BY created_at DESC,id LIMIT :size OFFSET :offset
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("decision", decisionId)
            .param("size", size)
            .param("offset", offset)
            .query((row, index) -> json.decode(row.getString(1), Saved.class))
            .list();
    return new Page<>(rows, total, page, size);
  }

  public static List<UUID> placements(DecisionService.Snapshot snapshot) {
    return snapshot.candidates().stream()
        .filter(candidate -> candidate.id().equals(snapshot.result().selectedCandidate()))
        .flatMap(candidate -> candidate.effects().stream())
        .map(DecisionEngine.Effect::placementId)
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
  }

  private Saved persist(
      Scope scope,
      UUID id,
      UUID decisionId,
      UUID placementId,
      int quantity,
      DecisionService.Snapshot snapshot) {
    ScopeTransactionRunner.requireCurrent(scope);
    if (quantity < 1 || quantity > QuantityComparison.MAX_QUANTITY) {
      throw new BusinessException("INVALID_QUANTITY", 422, "Количество: от 1 до 1 000 000");
    }
    var unchanged =
        snapshot.candidates().stream()
            .filter(DecisionEngine.Candidate::unchanged)
            .limit(2)
            .toList();
    UUID baseline = unchanged.size() == 1 ? unchanged.getFirst().id() : null;
    UUID selected = snapshot.result().selectedCandidate();
    String reason =
        placementId == null
            ? "EXPLICIT_PLACEMENT_REQUIRED"
            : baseline == null
                ? "BASELINE_NOT_CONFIRMED"
                : selected == null ? "NO_SELECTED_CANDIDATE" : null;
    BigDecimal baselineProfit = null;
    BigDecimal candidateProfit = null;
    QuantityComparison.Comparison comparison = null;
    BigDecimal available = snapshot.evidence().availableQuantity();
    if (reason == null) {
      try {
        var base =
            calculator.proportionalCase(
                DecisionContextAssembler.quantityPricing(snapshot, baseline, placementId));
        var candidate =
            calculator.proportionalCase(
                DecisionContextAssembler.quantityPricing(snapshot, selected, placementId));
        baselineProfit = base.unitProfit();
        candidateProfit = candidate.unitProfit();
        comparison = calculator.compare(base, candidate, quantity, available);
      } catch (BusinessException exception) {
        reason = exception.code();
      } catch (IllegalArgumentException exception) {
        reason = "PROPORTIONAL_VOLUME_NOT_CONFIRMED";
      }
    }
    var basis = snapshot.basis();
    Saved saved =
        new Saved(
            id,
            decisionId,
            placementId,
            clock.instant(),
            quantity,
            comparison == null ? "INCOMPLETE" : "COMPLETE",
            reason == null ? "KEPT_PURCHASE_FIXED_CONDITIONS" : reason,
            baseline,
            selected,
            available,
            baselineProfit,
            candidateProfit,
            comparison,
            "KEPT_PURCHASE",
            snapshot.evidence().stockRevision(),
            snapshot.evidence().stockObservedAt(),
            Map.of(
                "offer",
                basis.offerRevision(),
                "cost",
                basis.costRevision(),
                "tax",
                basis.taxRevision(),
                "safety",
                basis.safetyRevision(),
                "policy",
                basis.policyVersion(),
                "assignment",
                basis.assignmentRevision()));
    jdbc.sql(
            """
            INSERT INTO automation_quantity_comparison
              (organization_id,account_id,id,decision_id,created_at,result)
            VALUES (:org,:account,:id,:decision,:created,CAST(:result AS jsonb))
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", id)
        .param("decision", decisionId)
        .param("created", Timestamp.from(saved.createdAt()))
        .param("result", json.encode(saved))
        .update();
    return saved;
  }

  private void requireDecision(Scope scope, UUID decisionId) {
    boolean exists =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM automation_decision
                  WHERE organization_id=:org AND account_id=:account AND id=:id)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", decisionId)
            .query(Boolean.class)
            .single();
    if (!exists) {
      throw new BusinessException("DECISION_NOT_FOUND", 404, "Решение недоступно");
    }
  }
}
