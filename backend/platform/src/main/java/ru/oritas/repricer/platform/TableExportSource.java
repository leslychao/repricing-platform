package ru.oritas.repricer.platform;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A module's authorized, parameterized projection for a coherent server-side selection. */
public interface TableExportSource {
  String resource();

  Set<String> permissions();

  /** SQL emits canonical_id UUID and cells JSONB string/null array in its declared column order. */
  Projection projection(Scope scope, Query query, List<UUID> ids);

  /**
   * Pure, bounded formatting/calculation from captured values; never reloads live business data.
   */
  default List<String> exportCells(List<Column> columns, List<String> capturedCells) {
    return capturedCells;
  }

  record Column(String key, String title, boolean numeric) {}

  record Projection(
      String sql,
      Map<String, ?> parameters,
      List<Column> columns,
      String dataStatus,
      boolean includesRowPermissions) {
    public Projection(String sql, Map<String, ?> parameters, List<Column> columns) {
      this(sql, parameters, columns, "AS_PUBLISHED", false);
    }

    public Projection(
        String sql, Map<String, ?> parameters, List<Column> columns, String dataStatus) {
      this(sql, parameters, columns, dataStatus, false);
    }

    public Projection {
      parameters = Map.copyOf(parameters);
      columns = List.copyOf(columns);
      if (columns.isEmpty()
          || columns.size() > 100
          || !Set.of("COMPLETE", "INCOMPLETE", "AS_PUBLISHED").contains(dataStatus)) {
        throw new IllegalArgumentException("Export requires 1..100 columns");
      }
    }
  }

  record Query(String search, Map<String, String> filters, String sort, int page, int size) {
    public Query {
      if (search == null
          || search.length() > 512
          || filters == null
          || filters.size() > 20
          || sort == null
          || sort.length() > 128
          || page < 0
          || size < 1
          || size > 200
          || filters.entrySet().stream()
              .anyMatch(
                  entry ->
                      entry.getKey().length() > 128
                          || entry.getValue() == null
                          || entry.getValue().length() > 512)) {
        throw new BusinessException(
            "INVALID_SELECTION_QUERY", 422, "Некорректные параметры выборки");
      }
      filters = Map.copyOf(filters);
    }
  }
}
