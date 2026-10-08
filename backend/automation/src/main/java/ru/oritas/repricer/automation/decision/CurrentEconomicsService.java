package ru.oritas.repricer.automation.decision;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.automation.reference.MarketReferenceEvaluator;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.SqlReadProjection;
import ru.oritas.repricer.platform.TableExportSource;

/** Read projection of saved unit economics; never recalculates or replaces unknown components. */
@Service
public final class CurrentEconomicsService implements TableExportSource {
  private record Selection(String source, String ordering, Map<String, Object> parameters) {}

  public record Calculation(
      UUID id,
      UUID offerId,
      String offerName,
      String sku,
      UUID decisionId,
      UUID selectedCandidateId,
      String status,
      String reason,
      boolean current,
      Instant calculatedAt,
      Instant validUntil,
      BigDecimal price,
      BigDecimal cost,
      BigDecimal profit,
      BigDecimal margin,
      BigDecimal currentPrice,
      BigDecimal currentProfit,
      long revision,
      Map<String, Long> sourceRevisions) {}

  public record UnitValue(
      BigDecimal price, BigDecimal cost, BigDecimal profit, BigDecimal margin) {}

  public record PriceComparison(
      BigDecimal value,
      BigDecimal buyerPrice,
      BigDecimal referencePrice,
      UUID placementId,
      String segment,
      List<UUID> observations,
      Instant calculatedAt,
      Instant validUntil,
      String sourceFingerprint,
      String referenceFingerprint) {}

  private record Stored(
      UUID offerId,
      String offerName,
      String sku,
      UUID decisionId,
      UUID candidateId,
      String status,
      String reason,
      DecisionService.Basis basis,
      BigDecimal price,
      BigDecimal cost,
      BigDecimal profit,
      BigDecimal margin,
      BigDecimal currentPrice,
      BigDecimal currentProfit,
      boolean current) {}

  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final AuthorizationService authorization;
  private final DecisionValidityService validity;

  public CurrentEconomicsService(
      JdbcClient jdbc,
      JsonCodec json,
      AuthorizationService authorization,
      DecisionValidityService validity) {
    this.jdbc = jdbc;
    this.json = json;
    this.authorization = authorization;
    this.validity = validity;
  }

  public void publish(Scope scope, UUID decisionId, DecisionService.Snapshot snapshot) {
    ScopeTransactionRunner.requireCurrent(scope);
    UnitValue selected = unitValue(snapshot, snapshot.result().selectedCandidate());
    var unchanged =
        snapshot.candidates().stream()
            .filter(DecisionEngine.Candidate::unchanged)
            .limit(2)
            .toList();
    UnitValue current =
        unchanged.size() == 1 ? unitValue(snapshot, unchanged.getFirst().id()) : null;
    PriceComparison comparison = priceComparison(snapshot);
    jdbc.sql(
            """
            INSERT INTO automation_economics_projection
              (organization_id,account_id,id,offer_id,calculated_at,basis,selected_candidate,
                result_status,result_reason,price,cost,profit,margin,current_price,current_profit,current_margin,
                price_index,price_comparison)
            VALUES (:org,:account,:id,:offer,:calculated,CAST(:basis AS jsonb),:candidate,
              :status,:reason,:price,:cost,:profit,:margin,:currentPrice,:currentProfit,:currentMargin,
              :priceIndex,CAST(:comparison AS jsonb))
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", decisionId)
        .param("offer", snapshot.basis().offerId())
        .param("calculated", Timestamp.from(snapshot.basis().calculatedAt()))
        .param("basis", json.encode(snapshot.basis()))
        .param("candidate", snapshot.result().selectedCandidate())
        .param("status", snapshot.result().status())
        .param("reason", snapshot.result().reason())
        .param("price", selected == null ? null : selected.price())
        .param("cost", selected == null ? null : selected.cost())
        .param("profit", selected == null ? null : selected.profit())
        .param("margin", selected == null ? null : selected.margin())
        .param("currentPrice", current == null ? null : current.price())
        .param("currentProfit", current == null ? null : current.profit())
        .param("currentMargin", current == null ? null : current.margin())
        .param("priceIndex", comparison == null ? null : comparison.value())
        .param("comparison", comparison == null ? null : json.encode(comparison))
        .update();
  }

  private static PriceComparison priceComparison(DecisionService.Snapshot snapshot) {
    var rule = snapshot.context().rule().competitor();
    if (rule == null || snapshot.evidence() == null) {
      return null;
    }
    var unchanged =
        snapshot.candidates().stream()
            .filter(DecisionEngine.Candidate::unchanged)
            .limit(2)
            .toList();
    if (unchanged.size() != 1
        || unchanged.getFirst().effects().size() != 1
        || !unchanged.getFirst().effects().getFirst().sourceComplete()) {
      return null;
    }
    var evaluator = new MarketReferenceEvaluator();
    var reference =
        evaluator.evaluate(
            snapshot.evidence().competitors(), rule, snapshot.basis().calculatedAt());
    var buyers =
        unchanged.getFirst().effects().stream()
            .map(
                effect ->
                    new MarketReferenceEvaluator.BuyerObservation(
                        effect.placementId(), effect.segment(), effect.currentBuyerPrice()))
            .toList();
    var comparison = evaluator.priceIndex(buyers, rule.segment(), reference).orElse(null);
    if (comparison == null) {
      return null;
    }
    var input = snapshot.evidence().buyerComparisonInputs().get(comparison.placementId());
    if (input == null) {
      return null;
    }
    Set<UUID> used = Set.copyOf(reference.observations());
    Instant validUntil =
        snapshot.evidence().competitors().stream()
            .filter(item -> used.contains(item.id()))
            .map(item -> item.observedAt().plusSeconds(rule.maxAgeSeconds()))
            .min(Instant::compareTo)
            .orElseThrow();
    if (input.validUntil().isBefore(validUntil)) {
      validUntil = input.validUntil();
    }
    return new PriceComparison(
        comparison.value(),
        comparison.buyerPrice(),
        comparison.referencePrice(),
        comparison.placementId(),
        comparison.segment(),
        comparison.observations(),
        snapshot.basis().calculatedAt(),
        validUntil,
        input.fingerprint(),
        snapshot.basis().competitorFingerprint());
  }

  public SqlReadProjection offerComparisonProjection(Scope scope) {
    authorization.require(scope, "catalog.read");
    authorization.require(scope, "competitor.read");
    var fresh =
        validity.comparisonProjection(
            scope, "SELECT p.id,p.offer_id,p.price_comparison AS comparison");
    var parameters = new LinkedHashMap<String, Object>(fresh.parameters());
    parameters.put("comparisonOrganization", scope.requireOrganization());
    parameters.put("comparisonAccount", scope.requireAccount());
    return new SqlReadProjection(
        """
        SELECT p.offer_id,CASE WHEN fresh.id IS NOT NULL THEN p.price_index END AS price_index,
          CASE WHEN fresh.id IS NOT NULL THEN p.price_comparison END AS price_comparison
        FROM (SELECT DISTINCT ON (offer_id) * FROM automation_economics_projection
          WHERE organization_id=:comparisonOrganization AND account_id=:comparisonAccount
          ORDER BY offer_id,calculated_at DESC,id) p
        LEFT JOIN LATERAL (%s) fresh ON true
        """
            .formatted(fresh.sql()),
        parameters);
  }

  /**
   * Latest saved current-price economics, with the same canonical freshness as calculation reads.
   */
  public SqlReadProjection offerEconomicsProjection(Scope scope) {
    authorization.require(scope, "finance.read");
    var fresh = validity.projection(scope, "SELECT p.id,p.basis AS b");
    var parameters = new LinkedHashMap<String, Object>(fresh.parameters());
    parameters.put("economicsOrganization", scope.requireOrganization());
    parameters.put("economicsAccount", scope.requireAccount());
    return new SqlReadProjection(
        """
        SELECT p.offer_id,CASE WHEN fresh.id IS NOT NULL THEN p.current_margin END AS margin
        FROM (SELECT DISTINCT ON (offer_id) * FROM automation_economics_projection
          WHERE organization_id=:economicsOrganization AND account_id=:economicsAccount
          ORDER BY offer_id,calculated_at DESC,id) p
        LEFT JOIN LATERAL (%s) fresh ON true
        """
            .formatted(fresh.sql()),
        parameters);
  }

  /** Extract only one unambiguous kept purchase from the already evaluated candidate. */
  public static UnitValue unitValue(DecisionService.Snapshot snapshot, UUID candidateId) {
    if (candidateId == null) {
      return null;
    }
    var candidate =
        snapshot.candidates().stream()
            .filter(item -> item.id().equals(candidateId))
            .findFirst()
            .orElse(null);
    var evaluation =
        snapshot.result().evaluations().stream()
            .filter(item -> item.candidateId().equals(candidateId))
            .findFirst()
            .orElse(null);
    if (candidate == null || candidate.effects().size() != 1 || evaluation == null) {
      return null;
    }
    var effect = candidate.effects().getFirst();
    if (evaluation.economics().size() != effect.outcomes().size()) {
      return null;
    }
    UnitValue result = null;
    for (int index = 0; index < effect.outcomes().size(); index++) {
      if (effect.outcomes().get(index).input().outcome()
          == EconomicCalculator.Outcome.KEPT_PURCHASE) {
        if (result != null) {
          return null;
        }
        var saved = evaluation.economics().get(index);
        result =
            new UnitValue(
                effect.comparedPrice(), saved.costConsumed(), saved.profit(), saved.margin());
      }
    }
    return result;
  }

  public Page<Calculation> page(
      Scope scope,
      int page,
      int size,
      String search,
      String status,
      String sort,
      String direction,
      UUID offerId,
      LocalDate from,
      LocalDate until) {
    authorization.require(scope, "finance.read");
    int offset = Page.offset(page, size);
    Map<String, String> filters = new LinkedHashMap<>();
    if (status != null && !status.isEmpty()) {
      filters.put("status", status);
    }
    if (offerId != null) {
      filters.put("offerId", offerId.toString());
    }
    if (from != null) {
      filters.put("from", from.toString());
    }
    if (until != null) {
      filters.put("to", until.toString());
    }
    Selection selection =
        selection(scope, new Query(search, filters, sort + "," + direction, page, size), List.of());
    String source = selection.source();
    Map<String, Object> parameters = selection.parameters();
    long total =
        jdbc.sql("SELECT count(*) " + source).params(parameters).query(Long.class).single();
    List<Stored> rows =
        jdbc.sql(
                "SELECT o.id AS offer,o.name,o.sku,p.*,fresh.id IS NOT NULL AS current "
                    + source
                    + " ORDER BY "
                    + selection.ordering()
                    + ",o.id LIMIT :size OFFSET :offset")
            .params(parameters)
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) ->
                    new Stored(
                        row.getObject("offer", UUID.class),
                        row.getString("name"),
                        row.getString("sku"),
                        row.getObject("id", UUID.class),
                        row.getObject("selected_candidate", UUID.class),
                        row.getString("result_status"),
                        row.getString("result_reason"),
                        row.getString("basis") == null
                            ? null
                            : json.decode(row.getString("basis"), DecisionService.Basis.class),
                        row.getBigDecimal("price"),
                        row.getBigDecimal("cost"),
                        row.getBigDecimal("profit"),
                        row.getBigDecimal("margin"),
                        row.getBigDecimal("current_price"),
                        row.getBigDecimal("current_profit"),
                        row.getBoolean("current")))
            .list();
    var items =
        rows.stream()
            .map(
                row -> {
                  var basis = row.basis();
                  boolean fresh = basis != null && row.current();
                  Map<String, Long> revisions =
                      basis == null
                          ? Map.of()
                          : Map.of(
                              "offer",
                              basis.offerRevision(),
                              "cost",
                              basis.costRevision(),
                              "tax",
                              basis.taxRevision(),
                              "safety",
                              basis.safetyRevision(),
                              "assignment",
                              basis.assignmentRevision(),
                              "policy",
                              basis.policyVersion(),
                              "offerPrice",
                              basis.offerPriceRevision());
                  return new Calculation(
                      row.offerId(),
                      row.offerId(),
                      row.offerName(),
                      row.sku(),
                      row.decisionId(),
                      row.candidateId(),
                      Objects.requireNonNullElse(row.status(), "NOT_CALCULATED"),
                      basis == null ? "NO_SAVED_CALCULATION" : row.reason(),
                      fresh,
                      basis == null ? null : basis.calculatedAt(),
                      basis == null ? null : basis.validUntil(),
                      row.price(),
                      row.cost(),
                      row.profit(),
                      row.margin(),
                      row.currentPrice(),
                      row.currentProfit(),
                      basis == null ? 0 : 1,
                      revisions);
                })
            .toList();
    return new Page<>(items, total, page, size);
  }

  @Override
  public String resource() {
    return "economics/calculations";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("finance.read", "export");
  }

  @Override
  public Projection projection(Scope scope, Query query, List<UUID> ids) {
    authorization.require(scope, "finance.read");
    Selection selection = selection(scope, query, ids);
    String sql =
        """
        SELECT o.id AS canonical_id,jsonb_build_array(o.name,o.sku,p.price::text,p.cost::text,
          p.profit::text,COALESCE(p.result_status,'NOT_CALCULATED'),p.result_reason,
          p.calculated_at::text,p.basis->>'validUntil',p.id::text,p.basis::text,
          CASE WHEN p.id IS NULL THEN 'NOT_CALCULATED' WHEN fresh.id IS NOT NULL THEN 'CURRENT'
            ELSE 'STALE' END) AS cells
        """
            + selection.source()
            + " ORDER BY "
            + selection.ordering()
            + ",o.id";
    return new Projection(
        sql,
        selection.parameters(),
        List.of(
            new Column("name", "Товар", false),
            new Column("sku", "Артикул", false),
            new Column("price", "Цена сохранённого расчёта", true),
            new Column("cost", "Себестоимость сохранённого расчёта", true),
            new Column("profit", "Прибыль сохранённого расчёта", true),
            new Column("status", "Результат расчёта", false),
            new Column("reason", "Причина", false),
            new Column("calculatedAt", "Рассчитано", false),
            new Column("validUntil", "Срок исходных данных", false),
            new Column("decisionId", "Идентификатор решения", false),
            new Column("basis", "Сохранённые редакции входов", false),
            new Column("freshness", "Актуальность сохранённого расчёта", false)));
  }

  private Selection selection(Scope scope, Query query, List<UUID> ids) {
    if (ids.size() > 1000
        || !Set.of("status", "offerId", "from", "to").containsAll(query.filters().keySet())) {
      throw new IllegalArgumentException("Invalid calculation filters or selection");
    }
    String[] sorting = query.sort().split(",", -1);
    if (sorting.length > 2) {
      throw new IllegalArgumentException("Invalid calculation sort");
    }
    String field = sorting[0];
    String direction = sorting.length == 2 ? sorting[1] : "asc";
    String order =
        switch (field) {
          case "", "id" -> "o.id";
          case "name", "product" -> "o.name";
          case "price" -> "p.price";
          case "cost" -> "p.cost";
          case "profit" -> "p.profit";
          case "status" -> "p.result_status";
          case "freshness" -> "CASE WHEN p.id IS NULL THEN NULL ELSE fresh.id IS NOT NULL END";
          case "date", "calculatedAt" -> "p.calculated_at";
          default -> throw new IllegalArgumentException("Unknown calculation sort");
        };
    String ordering =
        switch (direction) {
          case "asc" -> " ASC NULLS LAST";
          case "desc" -> " DESC NULLS LAST";
          default -> throw new IllegalArgumentException("Unknown calculation direction");
        };
    var freshness = validity.projection(scope, "SELECT p.id,p.basis AS b");
    String source =
        """
        FROM marketplace_offer o JOIN marketplace_account a
          ON a.organization_id=o.organization_id AND a.id=o.account_id
        LEFT JOIN LATERAL (
          SELECT projection.* FROM automation_economics_projection projection
          WHERE (projection.organization_id,projection.account_id,projection.offer_id)=
            (o.organization_id,o.account_id,o.id)
          ORDER BY projection.calculated_at DESC,projection.id LIMIT 1
        ) p ON true
        LEFT JOIN LATERAL (%s) fresh ON true
        WHERE o.organization_id=:org AND o.account_id=:account
          AND position(lower(:search) in lower(o.sku||' '||o.name))>0
        """;
    source = source.formatted(freshness.sql());
    Map<String, Object> parameters = new LinkedHashMap<>(freshness.parameters());
    parameters.put("org", scope.organizationId());
    parameters.put("account", scope.requireAccount());
    parameters.put("search", query.search());
    if (!query.filters().getOrDefault("status", "").isEmpty()) {
      parameters.put("status", query.filters().get("status"));
      source += " AND COALESCE(p.result_status,'NOT_CALCULATED')=:status";
    }
    if (!query.filters().getOrDefault("offerId", "").isEmpty()) {
      parameters.put("offer", UUID.fromString(query.filters().get("offerId")));
      source += " AND o.id=:offer";
    }
    String start = query.filters().getOrDefault("from", "");
    String end = query.filters().getOrDefault("to", "");
    if (start.isEmpty() != end.isEmpty()) {
      throw new IllegalArgumentException("Both dates are required");
    }
    if (!start.isEmpty()) {
      LocalDate from = LocalDate.parse(start);
      LocalDate until = LocalDate.parse(end);
      if (until.isBefore(from)) {
        throw new IllegalArgumentException("Invalid calculation period");
      }
      parameters.put("from", from);
      parameters.put("until", until);
      source += " AND (p.calculated_at AT TIME ZONE a.timezone)::date BETWEEN :from AND :until";
    }
    if (!ids.isEmpty()) {
      parameters.put("selected", List.copyOf(ids));
      source += " AND o.id IN (:selected)";
    }
    return new Selection(source, order + ordering, parameters);
  }
}
