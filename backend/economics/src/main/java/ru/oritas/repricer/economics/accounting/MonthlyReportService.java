package ru.oritas.repricer.economics.accounting;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.marketplace.FinancialSourceService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;

/** Month-pinned factual reports; current price simulations never enter the financial ledger. */
@Configuration
public class MonthlyReportService {
  public record Coverage(
      YearMonth month,
      String status,
      String reason,
      long recognizedEvents,
      long preliminaryEvents,
      long unallocatedEvents) {}

  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final FinancialSourceService sources;

  public MonthlyReportService(
      JdbcClient jdbc, AuthorizationService authorization, FinancialSourceService sources) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.sources = sources;
  }

  public Coverage coverage(Scope scope, YearMonth month) {
    authorization.require(scope, "finance.read");
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " SHARE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    var sourceEvidence = sources.coverageEvidence(scope, month);
    var confirmed = sourceEvidence.completeByComponent();
    long recognizedPublications =
        sourceEvidence.publicationIds().isEmpty()
            ? 0
            : jdbc.sql(
                    """
                    SELECT count(DISTINCT source_publication_id) FROM economics_ledger_publication
                    WHERE organization_id=:org AND account_id=:account AND state='PUBLISHED'
                      AND source_publication_id IN (:sources)
                    """)
                .param("org", scope.organizationId())
                .param("account", scope.requireAccount())
                .param("sources", sourceEvidence.publicationIds())
                .query(Long.class)
                .single();
    boolean sourceCurrent = recognizedPublications == sourceEvidence.publicationIds().size();
    var missing =
        Set.of("REVENUE", "REFUND", "SERVICE", "COMPENSATION").stream()
            .filter(component -> !Boolean.TRUE.equals(confirmed.get(component)))
            .sorted()
            .toList();
    boolean declarationsCurrent =
        jdbc.sql(
                """
                SELECT NOT EXISTS(SELECT 1 FROM economics_ledger_publication p
                WHERE p.organization_id=:org AND p.account_id=:account AND p.state='PUBLISHED'
                  AND p.until_day>:start AND p.from_day<:end
                  AND (p.accounting_revision<>coalesce((SELECT revision FROM economics_accounting_basis
                    WHERE organization_id=:org AND account_id=:account),0)
                    OR p.recognition_version<>:recognition))
                  AND NOT EXISTS(SELECT 1 FROM economics_financial_event e
                    WHERE e.organization_id=:org AND e.account_id=:account AND e.current_revision
                      AND e.component<>'PAYMENT' AND e.accounting_date>=:start
                      AND e.accounting_date<:end
                      AND (e.accounting_revision<>coalesce((SELECT revision
                        FROM economics_accounting_basis
                        WHERE organization_id=:org AND account_id=:account),0)
                        OR NOT EXISTS(SELECT 1 FROM economics_ledger_publication p
                          WHERE p.organization_id=e.organization_id AND p.account_id=e.account_id
                            AND p.id=e.preparation_id AND p.recognition_version=:recognition)))
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("start", month.atDay(1))
            .param("end", month.plusMonths(1).atDay(1))
            .param("recognition", LedgerService.RECOGNITION_VERSION)
            .query(Boolean.class)
            .single();
    return jdbc.sql(
            """
            SELECT count(*),count(*) FILTER(WHERE NOT complete OR preliminary IS NULL),count(*) FILTER(WHERE offer_id IS NULL),
              coalesce(bool_and(NOT EXISTS(SELECT 1 FROM economics_source_fact f
                JOIN economics_recognition_head h ON h.organization_id=f.organization_id
                  AND h.account_id=f.account_id AND h.scope_key=f.recognition_scope
                WHERE f.organization_id=e.organization_id AND f.account_id=e.account_id
                  AND f.publication_id=e.publication_id AND f.source_id=e.source_id
                  AND NOT h.coverage_complete)),true),count(*) FILTER(WHERE preliminary)
            FROM economics_financial_event e WHERE organization_id=:org AND account_id=:account
              AND current_revision AND component<>'PAYMENT' AND accounting_date>=:start AND accounting_date<:end
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("start", month.atDay(1))
        .param("end", month.plusMonths(1).atDay(1))
        .query(
            (rs, row) -> {
              String reason;
              if (!missing.isEmpty()) {
                reason = "MISSING_COMPONENTS:" + String.join(",", missing);
              } else if (!sourceCurrent) {
                reason = "SOURCE_RECOGNITION_PENDING";
              } else if (!declarationsCurrent) {
                reason = "ACCOUNTING_RECALCULATION_PENDING";
              } else if (rs.getLong(2) > 0) {
                reason = "RECOGNITION_BASIS_INCOMPLETE";
              } else if (!rs.getBoolean(4)) {
                reason = "SERVICE_COVERAGE_INCOMPLETE";
              } else if (rs.getLong(5) > 0) {
                reason = "PRELIMINARY_AMOUNTS";
              } else {
                reason = "COMPLETE";
              }
              return new Coverage(
                  month,
                  reason.equals("COMPLETE") ? "COMPLETE" : "INCOMPLETE",
                  reason,
                  rs.getLong(1),
                  rs.getLong(5),
                  rs.getLong(3));
            })
        .single();
  }

  public record Totals(
      BigDecimal income, BigDecimal cost, BigDecimal expenses, BigDecimal tax, BigDecimal profit) {}

  public record Breakdown(String certainty, String allocation, long events, Totals totals) {}

  public record DailyIncome(LocalDate date, BigDecimal income, long events, boolean complete) {}

  public record Summary(
      Coverage coverage, Totals totals, List<Breakdown> breakdown, List<DailyIncome> daily) {
    public Summary {
      breakdown = List.copyOf(breakdown);
      daily = List.copyOf(daily);
    }
  }

  /** Totals and classification share the same protected accounting publication snapshot. */
  public Summary summary(Scope scope, YearMonth month) {
    Coverage coverage = coverage(scope, month);
    record Aggregate(
        boolean total, String certainty, boolean unallocated, long events, Totals totals) {}
    var values =
        jdbc.sql(
                """
                SELECT grouping(preliminary),
                  %s,
                  offer_id IS NULL AS unallocated,count(*),
                  CASE WHEN count(income)=count(*) THEN sum(income) END,
                  CASE WHEN count(cost)=count(*) THEN sum(cost) END,
                  CASE WHEN count(expenses)=count(*) THEN sum(expenses) END,
                  CASE WHEN count(tax)=count(*) THEN sum(tax) END,
                  CASE WHEN count(profit)=count(*) THEN sum(profit) END
                FROM economics_financial_event
                WHERE organization_id=:org AND account_id=:account AND current_revision
                  AND component<>'PAYMENT' AND accounting_date>=:start AND accounting_date<:end
                GROUP BY GROUPING SETS ((),(preliminary,(offer_id IS NULL)))
                ORDER BY grouping(preliminary) DESC,preliminary ASC NULLS LAST,unallocated
                """
                    .formatted(AccountingService.CERTAINTY_SQL))
            .params(parameters(scope, month, coverage))
            .query(
                (rs, index) ->
                    new Aggregate(
                        rs.getInt(1) == 1,
                        rs.getString(2),
                        rs.getBoolean(3),
                        rs.getLong(4),
                        new Totals(
                            rs.getBigDecimal(5),
                            rs.getBigDecimal(6),
                            rs.getBigDecimal(7),
                            rs.getBigDecimal(8),
                            rs.getBigDecimal(9))))
            .list();
    var total = values.getFirst();
    Totals amounts =
        total.events() == 0 && coverage.status().equals("COMPLETE")
            ? new Totals(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
            : total.totals();
    return new Summary(
        coverage,
        amounts,
        values.stream()
            .filter(value -> !value.total())
            .map(
                value ->
                    new Breakdown(
                        value.certainty(),
                        value.unallocated() ? "UNALLOCATED" : "ALLOCATED",
                        value.events(),
                        value.totals()))
            .toList(),
        dailyIncome(scope, month, coverage));
  }

  private List<DailyIncome> dailyIncome(Scope scope, YearMonth month, Coverage coverage) {
    var parameters = parameters(scope, month, coverage);
    parameters.put("days", month.lengthOfMonth());
    parameters.put("complete", coverage.status().equals("COMPLETE"));
    return jdbc.sql(
            """
            SELECT day.accounting_date,
              CASE WHEN count(e.id)=0 AND :complete THEN 0
                WHEN count(e.income)=count(e.id) THEN sum(e.income) END AS income,
              count(e.id) AS events,
              :complete AND coalesce(bool_and(e.complete AND e.preliminary IS NOT NULL)
                FILTER(WHERE e.id IS NOT NULL),true) AS complete
            FROM (SELECT CAST(:start AS date)+offset_day AS accounting_date
              FROM generate_series(0,:days-1) offset_day) day
            LEFT JOIN economics_financial_event e
              ON e.organization_id=:org AND e.account_id=:account AND e.current_revision
              AND e.component<>'PAYMENT' AND e.accounting_date=day.accounting_date
            GROUP BY day.accounting_date ORDER BY day.accounting_date
            """)
        .params(parameters)
        .query(
            (rs, index) ->
                new DailyIncome(
                    rs.getObject("accounting_date", LocalDate.class),
                    rs.getBigDecimal("income"),
                    rs.getLong("events"),
                    rs.getBoolean("complete")))
        .list();
  }

  @Bean
  public TableExportSource monthlyPnlExport() {
    return source("monthly-pnl", false);
  }

  @Bean
  public TableExportSource monthlyUnitEconomicsExport() {
    return source("monthly-unit-economics", true);
  }

  private TableExportSource source(String resource, boolean units) {
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
        authorization.require(scope, "finance.read");
        YearMonth month = requestedMonth(query);
        Coverage coverage = coverage(scope, month);
        var parameters = parameters(scope, month, coverage);
        if (!ids.isEmpty()) parameters.put("ids", ids);
        String sql = units ? unitProjection() : pnlProjection();
        sql =
            "SELECT * FROM ("
                + sql
                + ") monthly_rows"
                + (ids.isEmpty() ? "" : " WHERE canonical_id IN (:ids)")
                + " ORDER BY canonical_id";
        return new Projection(
            sql, parameters, units ? unitColumns() : pnlColumns(), coverage.status());
      }

      @Override
      public List<String> exportCells(List<Column> columns, List<String> cells) {
        if (!units) return cells;
        if (!columns.equals(unitColumns()) || cells.size() != columns.size()) {
          throw new BusinessException(
              "REPORT_LAYOUT_EXPIRED", 409, "Создайте новую выборку месячной удельной экономики");
        }
        var result = new ArrayList<>(cells);
        String quantity = cells.get(3);
        if (cells.get(1) == null || quantity == null || new BigDecimal(quantity).signum() <= 0) {
          return result;
        }
        BigDecimal denominator = new BigDecimal(quantity);
        for (int index = 0; index < 3; index++) {
          String amount = cells.get(index == 2 ? 8 : 4 + index);
          result.set(
              9 + index,
              amount == null
                  ? null
                  : EconomicCalculator.divide(new BigDecimal(amount), denominator).toPlainString());
        }
        return result;
      }
    };
  }

  private static YearMonth requestedMonth(TableExportSource.Query query) {
    if (!query.search().isEmpty() || !query.filters().keySet().equals(Set.of("month"))) {
      throw new BusinessException(
          "REPORT_MONTH_REQUIRED", 422, "Для месячного отчёта нужен только явный месяц YYYY-MM");
    }
    String requested = query.filters().get("month");
    if (requested == null
        || !requested.matches("[0-9]{4}-[0-9]{2}")
        || requested.startsWith("0000-")) throw invalidMonth();
    try {
      return YearMonth.parse(requested);
    } catch (DateTimeParseException invalid) {
      throw invalidMonth();
    }
  }

  private static BusinessException invalidMonth() {
    return new BusinessException("REPORT_MONTH_REQUIRED", 422, "Некорректный месяц отчёта");
  }

  private static Map<String, Object> parameters(Scope scope, YearMonth month, Coverage coverage) {
    var values = new HashMap<String, Object>();
    values.put("org", scope.organizationId());
    values.put("account", scope.requireAccount());
    values.put("start", month.atDay(1));
    values.put("end", month.plusMonths(1).atDay(1));
    values.put("month", month.toString());
    values.put("coverage", coverage.status());
    values.put("coverageReason", coverage.reason());
    values.put(
        "unallocatedId",
        UUID.nameUUIDFromBytes(
            (scope.accountId() + ":" + month + ":unallocated").getBytes(StandardCharsets.UTF_8)));
    return values;
  }

  private static String pnlProjection() {
    return """
    SELECT id AS canonical_id,jsonb_build_array(CAST(:month AS text),id::text,
      offer_id::text,accounting_date::text,component,source_id::text,
      source_revision::text,source_digest,
      %s,
      CASE WHEN offer_id IS NULL THEN 'UNALLOCATED' ELSE 'ALLOCATED' END,
      income::text,cost::text,expenses::text,tax::text,profit::text,
      %s,
      CAST(:coverage AS text),CAST(:coverageReason AS text)) AS cells
    FROM economics_financial_event WHERE organization_id=:org AND account_id=:account
      AND current_revision AND component<>'PAYMENT'
      AND accounting_date>=:start AND accounting_date<:end
    """
        .formatted(AccountingService.CERTAINTY_SQL, AccountingService.COMPLETENESS_SQL);
  }

  private static String unitProjection() {
    return """
    SELECT coalesce(offer_id,CAST(:unallocatedId AS uuid)) AS canonical_id,
      jsonb_build_array(CAST(:month AS text),offer_id::text,
      CASE WHEN offer_id IS NULL THEN 'UNALLOCATED' ELSE 'ALLOCATED' END,
      CASE WHEN count(*) FILTER(WHERE component='REVENUE' AND recognized_units IS NULL)=0
        THEN (sum(recognized_units) FILTER(WHERE component='REVENUE'))::text END,
      CASE WHEN count(income)=count(*) THEN sum(income)::text END,
      CASE WHEN count(cost)=count(*) THEN sum(cost)::text END,
      CASE WHEN count(expenses)=count(*) THEN sum(expenses)::text END,
      CASE WHEN count(tax)=count(*) THEN sum(tax)::text END,
      CASE WHEN count(profit)=count(*) THEN sum(profit)::text END,
      NULL::text,NULL::text,NULL::text,
      CASE WHEN count(*) FILTER(WHERE preliminary=false AND profit IS NULL)=0
        THEN coalesce(sum(profit) FILTER(WHERE preliminary=false),0)::text END,
      CASE WHEN count(*) FILTER(WHERE preliminary=true AND profit IS NULL)=0
        THEN coalesce(sum(profit) FILTER(WHERE preliminary=true),0)::text END,
      CASE WHEN count(*) FILTER(WHERE preliminary IS NULL AND profit IS NULL)=0
        THEN coalesce(sum(profit) FILTER(WHERE preliminary IS NULL),0)::text END,
      CAST(:coverage AS text),CAST(:coverageReason AS text)) AS cells
    FROM economics_financial_event WHERE organization_id=:org AND account_id=:account
      AND current_revision AND component<>'PAYMENT'
      AND accounting_date>=:start AND accounting_date<:end
    GROUP BY offer_id
    """;
  }

  private static List<TableExportSource.Column> pnlColumns() {
    return List.of(
        column("month", "Месяц", false),
        column("eventId", "Запись учёта", false),
        column("offerId", "Товар", false),
        column("date", "Дата признания", false),
        column("component", "Компонент", false),
        column("sourceId", "Источник", false),
        column("sourceRevision", "Редакция источника", false),
        column("sourceDigest", "Отпечаток источника", false),
        column("certainty", "Подтверждение суммы", false),
        column("allocation", "Распределение", false),
        column("income", "Доход", true),
        column("cost", "Себестоимость", true),
        column("expenses", "Расходы", true),
        column("tax", "Налог", true),
        column("profit", "Результат по включённым расходам", true),
        column("recognitionStatus", "Полнота основания расчёта", false),
        column("coverage", "Полнота периода", false),
        column("reason", "Причина неполноты", false));
  }

  private static List<TableExportSource.Column> unitColumns() {
    return List.of(
        column("month", "Месяц", false),
        column("offerId", "Товар", false),
        column("allocation", "Распределение", false),
        column("recognizedUnits", "Подтверждённые реализованные единицы", true),
        column("income", "Доход", true),
        column("cost", "Себестоимость", true),
        column("expenses", "Расходы", true),
        column("tax", "Налог", true),
        column("profit", "Результат по включённым расходам", true),
        column("unitIncome", "Доход на реализованную единицу", true),
        column("unitCost", "Себестоимость на реализованную единицу", true),
        column("unitProfit", "Результат на единицу по включённым расходам", true),
        column("confirmedProfit", "Подтверждённый результат по включённым расходам", true),
        column("preliminaryProfit", "Предварительный результат по включённым расходам", true),
        column("unknownProfit", "Результат, основание которого требует перерасчёта", true),
        column("coverage", "Полнота периода", false),
        column("reason", "Причина неполноты", false));
  }

  private static TableExportSource.Column column(String key, String title, boolean numeric) {
    return new TableExportSource.Column(key, title, numeric);
  }
}
