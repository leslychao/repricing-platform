package ru.oritas.repricer.platform;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Shared query contract for dated activity lists and their exact exports. */
public record HistoryQuery(
    String search,
    String status,
    LocalDate from,
    LocalDate to,
    String sort,
    String direction,
    int page,
    int size) {
  public HistoryQuery {
    Page.offset(page, size);
    if (search == null
        || search.length() > 512
        || status == null
        || status.length() > 128
        || sort == null
        || sort.length() > 128
        || direction == null
        || !Set.of("asc", "desc").contains(direction)
        || from != null && to != null && from.isAfter(to)) {
      throw new BusinessException("INVALID_HISTORY_QUERY", 422, "Некорректный фильтр журнала");
    }
  }

  public static HistoryQuery from(TableExportSource.Query query) {
    if (!Set.of("status", "from", "to").containsAll(query.filters().keySet())) {
      throw new BusinessException("INVALID_HISTORY_FILTER", 422, "Неизвестный фильтр журнала");
    }
    String[] order = query.sort().split(",", -1);
    if (order.length != 2) {
      throw new BusinessException("INVALID_SORT", 422, "Некорректная сортировка");
    }
    return new HistoryQuery(
        query.search(),
        query.filters().getOrDefault("status", ""),
        date(query.filters().get("from")),
        date(query.filters().get("to")),
        order[0],
        order[1],
        query.page(),
        query.size());
  }

  public Map<String, Object> parameters(ZoneId zone) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("search", search);
    values.put("status", status);
    if (from != null) {
      values.put("fromDate", Timestamp.from(from.atStartOfDay(zone).toInstant()));
    }
    if (to != null) {
      values.put("untilDate", Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));
    }
    return values;
  }

  /** Column comes only from the owning module's fixed SQL, never request text. */
  public String datePredicate(String column) {
    return (from == null ? "" : " AND " + column + ">=:fromDate")
        + (to == null ? "" : " AND " + column + "<:untilDate");
  }

  private static LocalDate date(String value) {
    return value == null || value.isBlank() ? null : LocalDate.parse(value);
  }
}
