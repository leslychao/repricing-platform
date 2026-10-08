package ru.oritas.repricer.app.read;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.automation.decision.CurrentEconomicsService;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.automation.execution.ExecutionService;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.SqlReadProjection;
import ru.oritas.repricer.platform.TableExportSource;

/**
 * Composes authorized published owners; no pricing, accounting or execution decisions live here.
 */
@Service
public final class OfferReadService implements TableExportSource {
  public record Offer(
      UUID id,
      String sku,
      String name,
      String imageUrl,
      BigDecimal sellerPrice,
      BigDecimal buyerPrice,
      String currency,
      long revision,
      Instant observedAt,
      BigDecimal cost,
      LocalDate costFrom,
      LocalDate costTo,
      String policyName,
      UUID policyId,
      String mode,
      String assignmentStatus,
      Instant lastCalculationAt,
      UUID lastDecisionId,
      String decisionStatus,
      UUID lastCommandId,
      String commandState,
      BigDecimal commission,
      BigDecimal margin,
      BigDecimal stock,
      List<Promotion> promotions,
      List<UUID> priceTargets,
      BigDecimal priceIndex,
      PriceComparison priceComparison) {}

  public record Promotion(UUID id, UUID targetId, String name, boolean processing) {}

  public record PriceComparison(
      BigDecimal buyerPrice,
      BigDecimal referencePrice,
      UUID placementId,
      String segment,
      List<UUID> observations,
      Instant calculatedAt,
      Instant validUntil) {}

  private record Selection(
      String source, String order, Map<String, Object> parameters, Set<String> rights) {}

  private static final Map<String, String> SORTS =
      Map.ofEntries(
          Map.entry("id", "id"),
          Map.entry("sku", "sku"),
          Map.entry("name", "name"),
          Map.entry("product", "name"),
          Map.entry("sellerPrice", "seller_price"),
          Map.entry("price", "seller_price"),
          Map.entry("buyerPrice", "buyer_price"),
          Map.entry("buyer", "buyer_price"),
          Map.entry("cost", "cost"),
          Map.entry("costFrom", "cost_from"),
          Map.entry("costTo", "cost_to"),
          Map.entry("policy", "policy_name"),
          Map.entry("mode", "mode"),
          Map.entry("assignmentStatus", "assignment_status"),
          Map.entry("runAt", "calculated_at"),
          Map.entry("decision", "decision_id"),
          Map.entry("decisionStatus", "decision_status"),
          Map.entry("command", "command_id"),
          Map.entry("commandState", "command_state"),
          Map.entry("commission", "commission"),
          Map.entry("margin", "margin"),
          Map.entry("stock", "stock"),
          Map.entry("promo", "promotion_names"),
          Map.entry("priceIndex", "price_index"));

  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final MarketplaceReadService catalog;
  private final EconomicsService costs;
  private final PolicyService policies;
  private final DecisionService decisions;
  private final ExecutionService execution;
  private final CurrentEconomicsService economics;
  private final ResourceService resources;
  private final JsonCodec json;

  public OfferReadService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      MarketplaceReadService catalog,
      EconomicsService costs,
      PolicyService policies,
      DecisionService decisions,
      ExecutionService execution,
      CurrentEconomicsService economics,
      ResourceService resources,
      JsonCodec json) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.catalog = catalog;
    this.costs = costs;
    this.policies = policies;
    this.decisions = decisions;
    this.execution = execution;
    this.economics = economics;
    this.resources = resources;
    this.json = json;
  }

  public Page<Offer> page(Scope scope, Query query) {
    Selection selection = selection(scope, query, List.of());
    int offset = Page.offset(query.page(), query.size());
    long total =
        jdbc.sql("SELECT count(*) " + selection.source())
            .params(selection.parameters())
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                "SELECT o.* "
                    + selection.source()
                    + " ORDER BY "
                    + selection.order()
                    + ",o.id LIMIT :pageSize OFFSET :pageOffset")
            .params(selection.parameters())
            .param("pageSize", query.size())
            .param("pageOffset", offset)
            .query(this::map)
            .list();
    return new Page<>(items, total, query.page(), query.size());
  }

  public Offer get(Scope scope, UUID id) {
    Selection selection = selection(scope, new Query("", Map.of(), "id,asc", 0, 1), List.of(id));
    return jdbc.sql("SELECT o.* " + selection.source())
        .params(selection.parameters())
        .query(this::map)
        .optional()
        .orElseThrow(() -> new BusinessException("OFFER_NOT_FOUND", 404, "Товар недоступен"));
  }

  @Override
  public String resource() {
    return "offers";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("catalog.read");
  }

  @Override
  public Projection projection(Scope scope, Query query, List<UUID> ids) {
    Selection selection = selection(scope, query, ids);
    List<Column> columns =
        new ArrayList<>(
            List.of(
                new Column("sku", "Артикул", false),
                new Column("name", "Товар", false),
                new Column("seller_price", "Цена", true),
                new Column("buyer_price", "Наблюдаемая цена", true)));
    List<String> required = new ArrayList<>(permissions());
    if (selection.rights().contains("finance.read")) {
      required.add("finance.read");
      columns.addAll(
          List.of(
              new Column("cost", "Себестоимость", true),
              new Column("cost_from", "Действует с", false),
              new Column("cost_to", "Действует до", false),
              new Column("commission", "Комиссия", true),
              new Column("margin", "Маржа", true)));
    }
    if (selection.rights().contains("policy.read")) {
      required.add("policy.read");
      columns.addAll(
          List.of(
              new Column("policy_name", "Политика", false),
              new Column("mode", "Режим назначения", false),
              new Column("assignment_status", "Состояние назначения", false)));
    }
    if (selection.rights().contains("command.read")) {
      required.add("command.read");
      columns.addAll(
          List.of(
              new Column("calculated_at", "Последний прогон", false),
              new Column("decision_id", "Расчёт", false),
              new Column("decision_status", "Результат прогона", false),
              new Column("command_id", "Команда", false),
              new Column("command_state", "Состояние команды", false)));
    }
    columns.addAll(
        List.of(
            new Column("stock", "Доступный остаток", true),
            new Column("promotion_names", "Промо", false)));
    if (selection.rights().contains("competitor.read")) {
      required.add("competitor.read");
      columns.add(new Column("price_index", "Сравнение с конкурентным ориентиром", true));
    }
    String values =
        String.join(",", columns.stream().map(column -> "o." + column.key() + "::text").toList());
    var parameters = new LinkedHashMap<>(selection.parameters());
    parameters.put("offerRowPermissions", json.encode(required));
    return new Projection(
        "SELECT o.id AS canonical_id,jsonb_build_array("
            + values
            + ") AS cells,CAST(:offerRowPermissions AS jsonb) AS permissions "
            + selection.source()
            + " ORDER BY "
            + selection.order()
            + ",o.id",
        parameters,
        columns,
        "AS_PUBLISHED",
        true);
  }

  private Selection selection(Scope scope, Query query, List<UUID> ids) {
    authorization.require(scope, "catalog.read");
    Set<String> rights = authorization.permissions(scope);
    if (ids.size() > 1000 || query.search().length() > 200) {
      throw invalid("Недопустимый размер выборки или поиска");
    }
    String[] sorting = query.sort().split(",", -1);
    if (sorting.length != 2
        || !SORTS.containsKey(sorting[0])
        || !Set.of("asc", "desc").contains(sorting[1])) {
      throw invalid("Недопустимая сортировка товаров");
    }
    String sortColumn = SORTS.get(sorting[0]);
    requireColumn(scope, sortColumn);
    var base = catalog.offerProjection(scope);
    var parameters = new LinkedHashMap<String, Object>(base.parameters());
    StringBuilder fields = new StringBuilder("base.*");
    StringBuilder joins = new StringBuilder();
    join(joins, parameters, catalog.commercialPresentationProjection(scope), "commercial");
    fields.append(",commercial.promotions,commercial.promotion_names,commercial.price_targets");
    if (rights.contains("competitor.read")) {
      join(joins, parameters, economics.offerComparisonProjection(scope), "comparison");
      fields.append(",comparison.price_index,comparison.price_comparison");
    } else {
      fields.append(",NULL::numeric AS price_index,NULL::jsonb AS price_comparison");
    }
    if (rights.contains("finance.read")) {
      join(joins, parameters, costs.currentCostProjection(scope), "costs");
      join(joins, parameters, economics.offerEconomicsProjection(scope), "economics");
      fields
          .append(
              ",costs.amount AS cost,costs.valid_from AS cost_from,costs.valid_until AS cost_to")
          .append(",economics.margin,NULL::numeric AS commission");
    } else {
      fields
          .append(",NULL::numeric AS cost,NULL::date AS cost_from,NULL::date AS cost_to")
          .append(",NULL::numeric AS margin,NULL::numeric AS commission");
    }
    if (rights.contains("policy.read")) {
      join(joins, parameters, policies.effectiveAssignmentProjection(scope), "policy");
      fields
          .append(",policy.policy_id,policy.policy_name,policy.mode,CASE ")
          .append(
              "WHEN policy.assignment_id IS NULL THEN NULL WHEN NOT policy.enabled THEN 'DISABLED'"
                  + " ")
          .append(
              "WHEN policy.paused THEN 'PAUSED' ELSE policy.policy_status END AS"
                  + " assignment_status");
    } else {
      fields
          .append(",NULL::uuid AS policy_id,NULL::text AS policy_name,NULL::text AS mode")
          .append(",NULL::text AS assignment_status");
    }
    if (rights.contains("command.read")) {
      join(joins, parameters, decisions.latestDecisionProjection(scope), "decision");
      join(joins, parameters, execution.latestCommandProjection(scope), "command");
      fields
          .append(",decision.decision_id,decision.calculated_at,decision.state AS decision_status")
          .append(",command.command_id,command.command_state");
    } else {
      fields
          .append(",NULL::uuid AS decision_id,NULL::timestamptz AS calculated_at")
          .append(
              ",NULL::text AS decision_status,NULL::uuid AS command_id,NULL::text AS"
                  + " command_state");
    }
    join(joins, parameters, resources.offerAvailabilityProjection(scope), "stock");
    fields.append(",stock.available_quantity AS stock");
    StringBuilder source =
        new StringBuilder("FROM (SELECT ")
            .append(fields)
            .append(" FROM (")
            .append(base.sql())
            .append(") base")
            .append(joins)
            .append(") o WHERE position(lower(:offerSearch) in lower(o.sku||' '||o.name))>0");
    parameters.put("offerSearch", query.search());
    if (!ids.isEmpty()) {
      source.append(" AND o.id IN (:offerIds)");
      parameters.put("offerIds", ids);
    }
    query.filters().forEach((key, value) -> appendFilter(scope, source, parameters, key, value));
    return new Selection(
        source.toString(),
        "o." + sortColumn + " " + sorting[1] + " NULLS LAST",
        parameters,
        rights);
  }

  private void requireColumn(Scope scope, String column) {
    if (column.equals("price_index")) {
      authorization.require(scope, "competitor.read");
    } else if (Set.of("cost", "cost_from", "cost_to", "margin", "commission").contains(column)) {
      authorization.require(scope, "finance.read");
    } else if (Set.of("policy_id", "policy_name", "mode", "assignment_status").contains(column)) {
      authorization.require(scope, "policy.read");
    } else if (Set.of(
            "decision_id", "calculated_at", "decision_status", "command_id", "command_state")
        .contains(column)) {
      authorization.require(scope, "command.read");
    }
  }

  private void appendFilter(
      Scope scope, StringBuilder source, Map<String, Object> parameters, String key, String value) {
    String column;
    String operator = "=";
    Object parsed;
    switch (key) {
      case "runFrom", "runTo" -> {
        requireColumn(scope, "calculated_at");
        if (!value.isEmpty()) {
          LocalDate date = LocalDate.parse(value);
          String parameter = "offerFilter" + parameters.size();
          parameters.put(parameter, key.equals("runTo") ? date.plusDays(1) : date);
          source
              .append(" AND o.calculated_at")
              .append(key.equals("runTo") ? "<" : ">=")
              .append("(CAST(:")
              .append(parameter)
              .append(" AS timestamp) AT TIME ZONE o.account_timezone)");
        }
        return;
      }
      case "name", "sku", "policy", "promo" -> {
        column =
            switch (key) {
              case "policy" -> "policy_name";
              case "promo" -> "promotion_names";
              default -> key;
            };
        parsed = value;
        operator = "contains";
      }
      case "policyId", "decisionId", "commandId", "offerId" -> {
        column =
            switch (key) {
              case "policyId" -> "policy_id";
              case "decisionId" -> "decision_id";
              case "commandId" -> "command_id";
              default -> "id";
            };
        parsed = value.isEmpty() ? null : UUID.fromString(value);
      }
      case "mode", "assignmentStatus", "decisionStatus", "commandState" -> {
        column =
            switch (key) {
              case "assignmentStatus" -> "assignment_status";
              case "decisionStatus" -> "decision_status";
              case "commandState" -> "command_state";
              default -> "mode";
            };
        parsed = value;
      }
      case "costFrom", "costTo" -> {
        column = key.equals("costFrom") ? "cost_from" : "cost_to";
        parsed = value.isEmpty() ? null : LocalDate.parse(value);
      }
      default -> {
        boolean minimum = key.endsWith("Min");
        if (!minimum && !key.endsWith("Max")) {
          throw invalid("Неизвестный фильтр товаров");
        }
        String name = key.substring(0, key.length() - 3);
        column =
            switch (name) {
              case "price" -> "seller_price";
              case "buyer" -> "buyer_price";
              case "priceIndex" -> "price_index";
              case "cost", "commission", "margin", "stock" -> name;
              default -> throw invalid("Неизвестный числовой фильтр товаров");
            };
        if (!value.isEmpty() && (value.length() > 80 || !value.matches("-?[0-9]+(\\.[0-9]+)?"))) {
          throw invalid("Число должно быть десятичной строкой с точкой");
        }
        parsed = value.isEmpty() ? null : new BigDecimal(value);
        operator = minimum ? ">=" : "<=";
      }
    }
    requireColumn(scope, column);
    if (value.isEmpty()) {
      return;
    }
    String parameter = "offerFilter" + parameters.size();
    parameters.put(parameter, parsed);
    if (operator.equals("contains")) {
      source
          .append(" AND position(lower(:")
          .append(parameter)
          .append(") in lower(o.")
          .append(column)
          .append("))>0");
    } else {
      source.append(" AND o.").append(column).append(operator).append(":").append(parameter);
    }
  }

  private static void join(
      StringBuilder joins,
      Map<String, Object> parameters,
      SqlReadProjection projection,
      String alias) {
    joins
        .append(" LEFT JOIN (")
        .append(projection.sql())
        .append(") ")
        .append(alias)
        .append(" ON ")
        .append(alias)
        .append(".offer_id=base.id");
    parameters.putAll(projection.parameters());
  }

  private Offer map(ResultSet row, int index) throws SQLException {
    var calculated = row.getTimestamp("calculated_at");
    String promotions = row.getString("promotions");
    String targets = row.getString("price_targets");
    String comparison = row.getString("price_comparison");
    return new Offer(
        row.getObject("id", UUID.class),
        row.getString("sku"),
        row.getString("name"),
        row.getString("image_url"),
        row.getBigDecimal("seller_price"),
        row.getBigDecimal("buyer_price"),
        row.getString("currency"),
        row.getLong("revision"),
        row.getTimestamp("observed_at").toInstant(),
        row.getBigDecimal("cost"),
        row.getObject("cost_from", LocalDate.class),
        row.getObject("cost_to", LocalDate.class),
        row.getString("policy_name"),
        row.getObject("policy_id", UUID.class),
        row.getString("mode"),
        row.getString("assignment_status"),
        calculated == null ? null : calculated.toInstant(),
        row.getObject("decision_id", UUID.class),
        row.getString("decision_status"),
        row.getObject("command_id", UUID.class),
        row.getString("command_state"),
        row.getBigDecimal("commission"),
        row.getBigDecimal("margin"),
        row.getBigDecimal("stock"),
        promotions == null ? null : List.of(json.decode(promotions, Promotion[].class)),
        targets == null ? null : List.of(json.decode(targets, UUID[].class)),
        row.getBigDecimal("price_index"),
        comparison == null ? null : json.decode(comparison, PriceComparison.class));
  }

  private static BusinessException invalid(String message) {
    return new BusinessException("INVALID_OFFER_QUERY", 422, message);
  }
}
