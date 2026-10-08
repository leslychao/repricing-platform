package ru.oritas.repricer.app.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;

/**
 * Inventory reads preserve distinct source pools and the canonical available-quantity calculation.
 */
@Service
public final class StockReadService implements TableExportSource {
  public record Stock(
      UUID id,
      UUID offerId,
      String name,
      String warehouse,
      BigDecimal physical,
      BigDecimal available,
      BigDecimal reserved,
      Instant observedAt) {}

  private record Selection(String source, String order, Map<String, Object> parameters) {}

  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final MarketplaceReadService marketplace;
  private final ResourceService resources;

  public StockReadService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      MarketplaceReadService marketplace,
      ResourceService resources) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.marketplace = marketplace;
    this.resources = resources;
  }

  public Page<Stock> page(Scope scope, Query query) {
    var selection = selection(scope, query, List.of());
    long total =
        jdbc.sql("SELECT count(*) " + selection.source())
            .params(selection.parameters())
            .query(Long.class)
            .single();
    var rows =
        jdbc.sql(
                "SELECT s.* "
                    + selection.source()
                    + " ORDER BY "
                    + selection.order()
                    + ",s.id LIMIT :stockLimit OFFSET :stockOffset")
            .params(selection.parameters())
            .param("stockLimit", query.size())
            .param("stockOffset", Page.offset(query.page(), query.size()))
            .query(
                (row, index) ->
                    new Stock(
                        row.getObject("id", UUID.class),
                        row.getObject("offer_id", UUID.class),
                        row.getString("name"),
                        row.getString("warehouse"),
                        row.getBigDecimal("physical"),
                        row.getBigDecimal("available"),
                        row.getBigDecimal("reserved"),
                        row.getTimestamp("observed_at").toInstant()))
            .list();
    return new Page<>(rows, total, query.page(), query.size());
  }

  @Override
  public String resource() {
    return "stock-pools";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("catalog.read");
  }

  @Override
  public Projection projection(Scope scope, Query query, List<UUID> ids) {
    var selection = selection(scope, query, ids);
    return new Projection(
        """
        SELECT s.id AS canonical_id,jsonb_build_array(s.name,s.warehouse,s.physical::text,
          s.available::text,s.reserved::text,s.observed_at::text) AS cells
        """
            + selection.source()
            + " ORDER BY "
            + selection.order()
            + ",s.id",
        selection.parameters(),
        List.of(
            new Column("product", "Товар", false),
            new Column("warehouse", "Склад", false),
            new Column("physical", "Физический остаток", true),
            new Column("available", "Доступно", true),
            new Column("reserved", "В резерве площадки", true),
            new Column("observed", "Получено", false)));
  }

  private Selection selection(Scope scope, Query query, List<UUID> ids) {
    authorization.require(scope, "catalog.read");
    if (ids.size() > 1000 || query.search().length() > 200) {
      throw invalid();
    }
    String[] sorting = query.sort().split(",", -1);
    if (sorting.length != 2 || !Set.of("asc", "desc").contains(sorting[1])) {
      throw invalid();
    }
    String column =
        switch (sorting[0]) {
          case "id" -> "id";
          case "product", "name" -> "name";
          case "warehouse", "physical", "available", "reserved" -> sorting[0];
          case "observed", "observedAt" -> "observed_at";
          default -> throw invalid();
        };
    var stock = resources.poolAvailabilityProjection(scope);
    var offers = marketplace.offerProjection(scope);
    var parameters = new LinkedHashMap<String, Object>(stock.parameters());
    parameters.putAll(offers.parameters());
    parameters.put("inventorySearch", query.search());
    var source =
        new StringBuilder(
            """
            FROM (SELECT p.pool_id AS id,p.offer_id,o.name,o.sku,p.name AS warehouse,
              p.physical_quantity AS physical,p.available_quantity AS available,
              p.covered_quantity AS reserved,p.observed_at
            FROM (%s) p JOIN (%s) o ON o.id=p.offer_id) s
            WHERE position(lower(:inventorySearch) in lower(s.sku||' '||s.name||' '||s.warehouse))>0
            """
                .formatted(stock.sql(), offers.sql()));
    if (!ids.isEmpty()) {
      source.append(" AND s.id IN (:inventoryIds)");
      parameters.put("inventoryIds", ids);
    }
    for (var entry : query.filters().entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (!Set.of(
              "offerId",
              "warehouseId",
              "warehouse",
              "physicalMin",
              "physicalMax",
              "availableMin",
              "availableMax",
              "reservedMin",
              "reservedMax")
          .contains(key)) {
        throw invalid();
      }
      if (value.isEmpty()) {
        continue;
      }
      String parameter = "inventoryFilter" + parameters.size();
      if (key.equals("offerId")) {
        parameters.put(parameter, UUID.fromString(value));
        source.append(" AND s.offer_id=:").append(parameter);
      } else if (key.equals("warehouseId")) {
        var warehouses = marketplace.warehousePoolsProjection(scope);
        parameters.putAll(warehouses.parameters());
        parameters.put(parameter, UUID.fromString(value));
        source
            .append(" AND EXISTS (SELECT 1 FROM (")
            .append(warehouses.sql())
            .append(
                ") warehouse_pool WHERE warehouse_pool.pool_id=s.id AND"
                    + " warehouse_pool.warehouse_id=:")
            .append(parameter)
            .append(")");
      } else if (key.equals("warehouse")) {
        parameters.put(parameter, value);
        source
            .append(" AND position(lower(:")
            .append(parameter)
            .append(") in lower(s.warehouse))>0");
      } else {
        if (value.length() > 80 || !value.matches("[0-9]+(\\.[0-9]+)?")) {
          throw invalid();
        }
        parameters.put(parameter, new BigDecimal(value));
        source
            .append(" AND s.")
            .append(key, 0, key.length() - 3)
            .append(key.endsWith("Min") ? ">=:" : "<=:")
            .append(parameter);
      }
    }
    return new Selection(
        source.toString(), "s." + column + " " + sorting[1] + " NULLS LAST", parameters);
  }

  private static BusinessException invalid() {
    return new BusinessException("INVALID_STOCK_QUERY", 422, "Некорректные параметры запасов");
  }
}
