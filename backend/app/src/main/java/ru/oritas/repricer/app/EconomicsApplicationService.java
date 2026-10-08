package ru.oritas.repricer.app;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.economics.AccountingInputService;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.SafetyEnvelopeService;
import ru.oritas.repricer.economics.accounting.MonthlyReportService;
import ru.oritas.repricer.economics.analytics.SalesPaceService;
import ru.oritas.repricer.marketplace.MarketplaceHistoryService;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.OperationResult;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;

@Service
public final class EconomicsApplicationService {
  public record CostRevision(
      String id,
      UUID offerId,
      long revision,
      LocalDate validFrom,
      LocalDate validTo,
      BigDecimal amount,
      BigDecimal extraExpense,
      UUID actor,
      Instant createdAt) {}

  public record FinanceRecord(
      UUID id,
      LocalDate occurredAt,
      String name,
      String category,
      String status,
      UUID offerId,
      UUID source,
      long revision,
      BigDecimal income,
      BigDecimal cost,
      BigDecimal expenses,
      BigDecimal tax,
      BigDecimal profit,
      boolean complete,
      String certainty) {}

  public record SummaryLine(String id, String name, BigDecimal amount) {}

  public record SummarySection(String id, String name, long events, List<SummaryLine> lines) {}

  public record Summary(
      String period,
      String status,
      String completeness,
      BigDecimal revenue,
      BigDecimal costs,
      BigDecimal result,
      List<SummaryLine> lines,
      List<SummarySection> sections,
      List<MonthlyReportService.DailyIncome> daily,
      List<String> unknownReasons) {}

  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final IdempotencyService requests;
  private final EconomicsService economics;
  private final AccountingInputService inputs;
  private final SafetyEnvelopeService safety;
  private final MarketplaceHistoryService history;
  private final SalesPaceService pace;
  private final MonthlyReportService monthly;

  public EconomicsApplicationService(
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      IdempotencyService requests,
      EconomicsService economics,
      AccountingInputService inputs,
      SafetyEnvelopeService safety,
      MarketplaceHistoryService history,
      SalesPaceService pace,
      MonthlyReportService monthly) {
    this.transactions = transactions;
    this.authorization = authorization;
    this.requests = requests;
    this.economics = economics;
    this.inputs = inputs;
    this.safety = safety;
    this.history = history;
    this.pace = pace;
    this.monthly = monthly;
  }

  public Page<MarketplaceHistoryService.Payment> payments(
      Scope scope,
      int page,
      int size,
      LocalDate from,
      LocalDate until,
      String search,
      String sort,
      String direction) {
    return transactions.run(
        scope, () -> history.payments(scope, page, size, from, until, search, sort, direction));
  }

  public Page<MarketplaceHistoryService.Return> returns(
      Scope scope,
      int page,
      int size,
      LocalDate from,
      LocalDate until,
      String search,
      String sort,
      String direction) {
    return transactions.run(
        scope, () -> history.returns(scope, page, size, from, until, search, sort, direction));
  }

  public Page<SalesPaceService.Result> pace(
      Scope scope,
      int page,
      int size,
      String search,
      String sort,
      String direction,
      String status) {
    return transactions.run(
        scope, () -> pace.page(scope, page, size, search, sort, direction, status));
  }

  public OperationResult refreshPace(Scope scope, UUID requestId) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("finance.read", "decision.preview"));
          return requests.execute(
              scope,
              "sales-pace.refresh",
              requestId,
              "refresh",
              OperationResult.class,
              () -> new OperationResult(pace.submitRefresh(scope, requestId), "PENDING"));
        });
  }

  public EconomicsService.CostView cost(Scope scope, UUID offerId) {
    return transactions.run(scope, () -> economics.getCost(scope, offerId));
  }

  public SafetyEnvelopeService.OfferBounds offerBounds(Scope scope, UUID offerId) {
    return transactions.run(scope, () -> safety.offerBounds(scope, offerId));
  }

  public SafetyEnvelopeService.OfferBounds offerBounds(
      Scope scope, UUID requestId, SafetyEnvelopeService.OfferBounds input) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("finance.write"));
          return requests.execute(
              scope,
              "offer.bounds:" + input.offerId(),
              requestId,
              input,
              SafetyEnvelopeService.OfferBounds.class,
              () -> safety.publishOfferBounds(scope, input));
        });
  }

  public Page<CostRevision> costs(
      Scope scope,
      int page,
      int size,
      String search,
      UUID offerId,
      String sort,
      String direction,
      LocalDate from,
      LocalDate to) {
    return transactions.run(
        scope,
        () -> {
          var source =
              economics.costHistory(scope, offerId, page, size, search, sort, direction, from, to);
          return new Page<>(
              source.items().stream()
                  .map(
                      row ->
                          new CostRevision(
                              row.id().toString(),
                              row.offerId(),
                              row.revision(),
                              row.validFrom(),
                              row.validUntil(),
                              row.amount(),
                              row.extraExpense(),
                              row.authorId(),
                              row.recordedAt()))
                  .toList(),
              source.total(),
              source.page(),
              source.size());
        });
  }

  public Page<FinanceRecord> accruals(
      Scope scope,
      int page,
      int size,
      LocalDate from,
      LocalDate until,
      UUID offerId,
      String component,
      String search,
      String status,
      String sort,
      String direction) {
    return transactions.run(
        scope,
        () -> {
          var source =
              economics.financialPage(
                  scope, page, size, from, until, offerId, component, search, status, sort,
                  direction);
          return new Page<>(
              source.items().stream()
                  .map(
                      row ->
                          new FinanceRecord(
                              row.id(),
                              row.accountingDate(),
                              row.component(),
                              row.component(),
                              row.status(),
                              row.offerId(),
                              row.sourceId(),
                              row.sourceRevision(),
                              row.income(),
                              row.cost(),
                              row.expenses(),
                              row.tax(),
                              row.profit(),
                              row.complete(),
                              row.certainty()))
                  .toList(),
              source.total(),
              source.page(),
              source.size());
        });
  }

  public Summary summary(Scope scope, YearMonth period) {
    return transactions.run(
        scope,
        () -> {
          var result = monthly.summary(scope, period);
          boolean complete = result.coverage().status().equals("COMPLETE");
          var total = result.totals();
          var sections =
              result.breakdown().stream()
                  .map(
                      part ->
                          new SummarySection(
                              part.certainty() + ":" + part.allocation(),
                              (switch (part.certainty()) {
                                    case "CONFIRMED" -> "Подтверждённые";
                                    case "PRELIMINARY" -> "Предварительные";
                                    default -> "Основание требует перерасчёта";
                                  })
                                  + (part.allocation().equals("UNALLOCATED")
                                      ? " · не распределены по товарам"
                                      : " · отнесены к товарам"),
                              part.events(),
                              summaryLines(part.totals())))
                  .toList();
          return new Summary(
              period.toString(),
              result.coverage().status(),
              complete ? "Покрытие подтверждено" : "Неполный период",
              total.income(),
              total.cost(),
              total.profit(),
              summaryLines(total),
              sections,
              result.daily(),
              complete
                  ? List.of()
                  : List.of(
                      "Результат по включённым расходам; неизвестные составляющие не заменены"
                          + " нулём.",
                      coverageReason(result.coverage().reason())));
        });
  }

  private static List<SummaryLine> summaryLines(MonthlyReportService.Totals value) {
    return List.of(
        new SummaryLine("income", "Доход", value.income()),
        new SummaryLine("cost", "Себестоимость", value.cost()),
        new SummaryLine("expenses", "Расходы", value.expenses()),
        new SummaryLine("tax", "Налог", value.tax()),
        new SummaryLine("profit", "Результат по включённым расходам", value.profit()));
  }

  private static String coverageReason(String reason) {
    if (reason.startsWith("MISSING_COMPONENTS:")) {
      return "Не подтверждено покрытие всех источников дохода, возвратов, услуг и компенсаций.";
    }
    return switch (reason) {
      case "SOURCE_RECOGNITION_PENDING" ->
          "Новая редакция источника ещё проходит финансовое признание.";
      case "ACCOUNTING_RECALCULATION_PENDING" ->
          "Учёт пересчитывается по исправленным объявлениям стоимости и налога.";
      case "RECOGNITION_BASIS_INCOMPLETE" ->
          "Для части событий не подтверждены обязательные основания расчёта.";
      case "SERVICE_COVERAGE_INCOMPLETE" ->
          "Известные услуги учтены; покрытие всех услуг для части продаж не подтверждено.";
      case "PRELIMINARY_AMOUNTS" -> "Результат содержит предварительные суммы из источника.";
      default -> reason;
    };
  }

  public EconomicsService.TaxView tax(Scope scope) {
    return transactions.run(scope, () -> economics.getTax(scope));
  }

  public OperationResult cost(Scope scope, UUID requestId, EconomicsService.CostInput input) {
    return submit(scope, new AccountingInputService.InputSet(requestId, List.of(input), null));
  }

  public OperationResult tax(Scope scope, UUID requestId, EconomicsService.TaxInput input) {
    return submit(scope, new AccountingInputService.InputSet(requestId, List.of(), input));
  }

  private OperationResult submit(Scope scope, AccountingInputService.InputSet input) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("finance.write"));
          return requests.execute(
              scope,
              "economics.input",
              input.id(),
              input,
              OperationResult.class,
              () -> new OperationResult(inputs.submit(scope, input), "PENDING"));
        });
  }

  public SafetyEnvelopeService.Envelope safety(Scope scope) {
    return transactions.run(scope, () -> safety.get(scope));
  }

  public SafetyEnvelopeService.Envelope safety(
      Scope scope, UUID requestId, SafetyEnvelopeService.Envelope input) {
    return transactions.run(
        scope,
        () -> {
          authorization.requireLocked(scope, Set.of("finance.write"));
          return requests.execute(
              scope,
              "safety.publish",
              requestId,
              input,
              SafetyEnvelopeService.Envelope.class,
              () -> safety.publish(scope, input));
        });
  }
}
