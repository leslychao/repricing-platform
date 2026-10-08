package ru.oritas.repricer.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.SafetyEnvelopeService;
import ru.oritas.repricer.economics.analytics.SalesPaceService;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.marketplace.MarketplaceHistoryService;
import ru.oritas.repricer.platform.OperationResult;
import ru.oritas.repricer.platform.Page;

@RestController
public final class EconomicsController {
  private final RequestIdentity identity;
  private final EconomicsApplicationService economics;

  public EconomicsController(RequestIdentity identity, EconomicsApplicationService economics) {
    this.identity = identity;
    this.economics = economics;
  }

  @GetMapping({"/api/v1/cost-revisions", "/api/v1/seller-costs/revisions"})
  public Page<EconomicsApplicationService.CostRevision> costs(HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search, @RequestParam(required = false) UUID offerId,
      @RequestParam(defaultValue = "id") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
    return economics.costs(identity.scope(request), page, size, search, offerId, sort, direction,
        from, to);
  }

  @GetMapping("/api/v1/economics/accruals")
  public Page<EconomicsApplicationService.FinanceRecord> accruals(HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
      @RequestParam LocalDate from, @RequestParam LocalDate to,
      @RequestParam(required = false) UUID offerId,
      @RequestParam(defaultValue = "") String component,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "date") String sort,
      @RequestParam(defaultValue = "desc") String direction) {
    return economics.accruals(identity.scope(request), page, size, from, to.plusDays(1),
        offerId, component, search, status, sort, direction);
  }

  @GetMapping("/api/v1/economics/summary")
  public EconomicsApplicationService.Summary summary(HttpServletRequest request,
      @RequestParam String period) {
    return economics.summary(identity.scope(request), YearMonth.parse(period));
  }

  @GetMapping("/api/v1/economics/payments")
  public Page<MarketplaceHistoryService.Payment> payments(HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
      @RequestParam LocalDate from, @RequestParam LocalDate to,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "date") String sort,
      @RequestParam(defaultValue = "desc") String direction) {
    return economics.payments(identity.scope(request), page, size, from, to, search, sort, direction);
  }

  @GetMapping("/api/v1/economics/returns")
  public Page<MarketplaceHistoryService.Return> returns(HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
      @RequestParam LocalDate from, @RequestParam LocalDate to,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "date") String sort,
      @RequestParam(defaultValue = "desc") String direction) {
    return economics.returns(identity.scope(request), page, size, from, to, search, sort, direction);
  }

  @GetMapping("/api/v1/economics/sales-pace")
  public Page<SalesPaceService.Result> pace(HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "id") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam(defaultValue = "") String status) {
    return economics.pace(identity.scope(request), page, size, search, sort, direction, status);
  }

  @PostMapping("/api/v1/economics/sales-pace/refresh")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult refreshPace(HttpServletRequest request,
      @Valid @RequestBody RefreshRequest input) {
    return economics.refreshPace(identity.scope(request), input.clientRequestId());
  }

  @GetMapping("/api/v1/cost-revisions/current/{offerId}")
  public EconomicsService.CostView cost(HttpServletRequest request, @PathVariable UUID offerId) {
    return economics.cost(identity.scope(request), offerId);
  }

  @GetMapping("/api/v1/tax-profile/revisions/current")
  public EconomicsService.TaxView tax(HttpServletRequest request) {
    return economics.tax(identity.scope(request));
  }

  @PostMapping({"/api/v1/cost-revisions", "/api/v1/seller-costs/revisions"})
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult cost(HttpServletRequest request, @Valid @RequestBody CostRequest input) {
    return economics.cost(identity.scope(request), input.clientRequestId(),
        new EconomicsService.CostInput(input.offerId(), input.amount(), input.extraExpense(),
            input.validFrom(), input.validTo(), input.expectedRevision()));
  }

  @PostMapping("/api/v1/tax-profile/revisions")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult tax(HttpServletRequest request, @Valid @RequestBody TaxRequest input) {
    return economics.tax(identity.scope(request), input.clientRequestId(),
        new EconomicsService.TaxInput(input.rate(), input.validFrom(), input.validTo(),
            input.expectedRevision()));
  }

  @GetMapping("/api/v1/safety-envelopes/revisions")
  public SafetyEnvelopeService.Envelope safety(HttpServletRequest request) {
    return economics.safety(identity.scope(request));
  }

  @GetMapping("/api/v1/offers/{offerId}/price-bounds")
  public SafetyEnvelopeService.OfferBounds offerBounds(HttpServletRequest request,
      @PathVariable UUID offerId) {
    return economics.offerBounds(identity.scope(request), offerId);
  }

  @PostMapping("/api/v1/offers/{offerId}/price-bounds")
  public SafetyEnvelopeService.OfferBounds offerBounds(HttpServletRequest request,
      @PathVariable UUID offerId, @Valid @RequestBody OfferBoundsRequest input) {
    return economics.offerBounds(identity.scope(request), input.clientRequestId(),
        new SafetyEnvelopeService.OfferBounds(offerId, input.expectedRevision(), input.enabled(),
            input.minimumPrice(), input.maximumPrice()));
  }

  @PostMapping("/api/v1/safety-envelopes/revisions")
  public SafetyEnvelopeService.Envelope safety(HttpServletRequest request,
      @Valid @RequestBody SafetyRequest input) {
    return economics.safety(identity.scope(request), input.clientRequestId(),
        new SafetyEnvelopeService.Envelope(input.expectedRevision(), input.minimumPrice(),
            input.maximumPrice(), input.minimumMargin(), input.minimumProfit(),
            input.maximumOutcomeLoss()));
  }

  public record CostRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
      @NotNull UUID offerId, @NotNull BigDecimal amount, @NotNull BigDecimal extraExpense,
      @NotNull LocalDate validFrom, LocalDate validTo) {}

  public record RefreshRequest(@NotNull UUID clientRequestId) {}

  public record OfferBoundsRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
      boolean enabled, BigDecimal minimumPrice, BigDecimal maximumPrice) {}

  public record TaxRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
      @NotNull BigDecimal rate, @NotNull LocalDate validFrom, LocalDate validTo) {}

  public record SafetyRequest(@NotNull UUID clientRequestId, @Min(0) long expectedRevision,
      @NotNull BigDecimal minimumPrice, @NotNull BigDecimal maximumPrice,
      @NotNull BigDecimal minimumMargin, BigDecimal minimumProfit,
      @NotNull Map<EconomicCalculator.Outcome, BigDecimal> maximumOutcomeLoss) {}
}
