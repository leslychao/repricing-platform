package ru.oritas.repricer.marketplace;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;

/** Authorized projections of the marketplace's canonical read models. */
@Configuration
public class MarketplaceExportSources {
  private final AuthorizationService authorization;
  private final MarketplaceReadService reads;
  private final MarketplaceHistoryService history;

  public MarketplaceExportSources(
      AuthorizationService authorization,
      MarketplaceReadService reads,
      MarketplaceHistoryService history) {
    this.authorization = authorization;
    this.reads = reads;
    this.history = history;
  }

  @Bean
  TableExportSource placementsExport() {
    return source(
        "placements",
        "catalog.read",
        "marketplace_placement",
        "external_id",
        List.of(
            column("external_id", "Размещение", false),
            column("model", "Модель", false),
            column("status", "Статус", false),
            column("available", "Доступно", false),
            column("revision", "Редакция", true)),
        Map.of("id", "id"),
        Set.of("offerId"));
  }

  @Bean
  TableExportSource promotionsExport() {
    return source(
        "promotions",
        "catalog.read",
        "marketplace_promotion",
        "name",
        List.of(
            column("name", "Акция", false),
            column("promotion_type", "Механика", false),
            column("starts_at", "Начало", false),
            column("ends_at", "Окончание", false),
            column("processing", "Обрабатывается", false)),
        Map.of("id", "id"),
        Set.of());
  }

  @Bean
  TableExportSource paymentsExport() {
    return historySource("payments", MarketplaceHistoryService.HistoryTable.PAYMENTS);
  }

  @Bean
  TableExportSource returnsExport() {
    return historySource("returns", MarketplaceHistoryService.HistoryTable.RETURNS);
  }

  private TableExportSource historySource(
      String resource, MarketplaceHistoryService.HistoryTable table) {
    return new TableExportSource() {
      @Override
      public String resource() {
        return resource;
      }

      @Override
      public Set<String> permissions() {
        return Set.of("finance.read");
      }

      @Override
      public Projection projection(Scope scope, Query query, List<UUID> ids) {
        return history.historyProjection(scope, table, query, ids);
      }
    };
  }

  private TableExportSource source(
      String resource,
      String permission,
      String table,
      String searchField,
      List<TableExportSource.Column> columns,
      Map<String, String> sorting,
      Set<String> filters) {
    return new TableExportSource() {
      @Override
      public String resource() {
        return resource;
      }

      @Override
      public Set<String> permissions() {
        return Set.of(permission);
      }

      @Override
      public Projection projection(Scope scope, Query query, List<UUID> ids) {
        authorization.require(scope, permission);
        scope.requireAccount();
        if (query.search().length() > 200
            || !filters.containsAll(query.filters().keySet())
            || ids == null
            || ids.size() > 1000
            || ids.stream().anyMatch(java.util.Objects::isNull)) {
          throw invalid();
        }
        String[] sort = query.sort().split(",", -1);
        if (sort.length != 2
            || !sorting.containsKey(sort[0])
            || !Set.of("asc", "desc").contains(sort[1])) {
          throw invalid();
        }
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("search", query.search());
        String predicate = " WHERE position(lower(:search) in lower(" + searchField + "))>0";
        if (!ids.isEmpty()) {
          predicate += " AND id IN (:ids)";
          parameters.put("ids", ids);
        }
        if (query.filters().containsKey("offerId")) {
          try {
            UUID offer = UUID.fromString(query.filters().get("offerId"));
            reads.offer(scope, offer);
            parameters.put("offer", offer);
            predicate += " AND offer_id=:offer";
          } catch (IllegalArgumentException exception) {
            throw invalid();
          }
        }
        String cells =
            String.join(",", columns.stream().map(column -> column.key() + "::text").toList());
        String sql =
            "SELECT id AS canonical_id,jsonb_build_array("
                + cells
                + ") AS cells FROM "
                + table
                + predicate
                + " ORDER BY "
                + sorting.get(sort[0])
                + " "
                + sort[1]
                + " NULLS LAST,id";
        return new Projection(sql, parameters, columns);
      }
    };
  }

  private static TableExportSource.Column column(String key, String title, boolean numeric) {
    return new TableExportSource.Column(key, title, numeric);
  }

  private static BusinessException invalid() {
    return new BusinessException(
        "INVALID_SELECTION_QUERY", 422, "Параметры не соответствуют таблице");
  }
}
