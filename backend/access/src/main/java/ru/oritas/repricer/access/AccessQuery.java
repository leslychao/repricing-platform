package ru.oritas.repricer.access;

import java.util.Map;
import java.util.Set;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.TableExportSource;

/** One selection contract for access lists and their exports. */
public record AccessQuery(
    String search, String status, String sort, String direction, int page, int size) {
  public AccessQuery {
    Page.offset(page, size);
    if (search == null
        || search.length() > 512
        || status == null
        || status.length() > 32
        || sort == null
        || sort.length() > 32
        || !Set.of("asc", "desc").contains(direction)) {
      throw invalid();
    }
  }

  public static AccessQuery from(TableExportSource.Query query) {
    if (!Set.of("status").containsAll(query.filters().keySet())) {
      throw invalid();
    }
    String[] order = query.sort().split(",", -1);
    if (order.length != 2) {
      throw invalid();
    }
    return new AccessQuery(
        query.search(),
        query.filters().getOrDefault("status", ""),
        order[0],
        order[1],
        query.page(),
        query.size());
  }

  public Map<String, Object> parameters() {
    return Map.of("search", search, "status", status);
  }

  String order(Map<String, String> columns, String id) {
    String column = columns.get(sort);
    if (column == null) {
      throw invalid();
    }
    return column + " " + direction + " NULLS LAST," + id;
  }

  void requireStatuses(Set<String> allowed) {
    if (!status.isEmpty() && !allowed.contains(status)) {
      throw invalid();
    }
  }

  private static BusinessException invalid() {
    return new BusinessException("INVALID_ACCESS_QUERY", 422, "Некорректный фильтр доступа");
  }
}
