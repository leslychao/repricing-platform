package ru.oritas.repricer.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.app.read.OfferReadService;
import ru.oritas.repricer.marketplace.CatalogSyncService;
import ru.oritas.repricer.marketplace.ConnectionService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.marketplace.ReportSyncService;
import ru.oritas.repricer.platform.OperationResult;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;

/** HTTP transport selects the exact scope; business owners perform every access check. */
@RestController
public final class MarketplaceController {
  private final RequestIdentity identity;
  private final ScopeTransactionRunner transactions;
  private final MarketplaceReadService reads;
  private final ConnectionService connections;
  private final CatalogSyncService synchronization;
  private final ReportSyncService reports;
  private final OfferReadService offerReads;

  public MarketplaceController(
      RequestIdentity identity,
      ScopeTransactionRunner transactions,
      MarketplaceReadService reads,
      ConnectionService connections,
      CatalogSyncService synchronization,
      ReportSyncService reports,
      OfferReadService offerReads) {
    this.identity = identity;
    this.transactions = transactions;
    this.reads = reads;
    this.connections = connections;
    this.synchronization = synchronization;
    this.reports = reports;
    this.offerReads = offerReads;
  }

  @GetMapping("/api/v1/marketplace-accounts")
  public Page<MarketplaceReadService.Account> accounts(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    Scope scope = organization(request);
    return transactions.run(scope, () -> reads.accounts(scope, page, size));
  }

  @PostMapping("/api/v1/marketplace-accounts")
  @ResponseStatus(HttpStatus.CREATED)
  public MarketplaceReadService.Account create(
      HttpServletRequest request, @Valid @RequestBody AccountRequest input) {
    return connections.createAccount(
        organization(request),
        input.marketplace(),
        input.externalId(),
        input.name(),
        input.timezone(),
        input.clientRequestId());
  }

  @GetMapping("/api/v1/marketplace-accounts/{id}/connections")
  public ConnectionService.ConnectionView connection(
      HttpServletRequest request, @PathVariable UUID id) {
    Scope scope = account(request, id);
    return transactions.run(scope, () -> connections.view(scope));
  }

  @PutMapping("/api/v1/marketplace-accounts/{id}/connections")
  public ConnectionService.ConnectionView connection(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody ConnectionRequest input) {
    Scope scope = account(request, id);
    var account = transactions.run(scope, () -> reads.account(scope));
    return connections.rotate(
        scope,
        input.secret(),
        account.externalId(),
        input.readOnly(),
        input.expectedRevision(),
        input.clientRequestId());
  }

  @PostMapping("/api/v1/marketplace-accounts/{id}/connections/check")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult check(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody Mutation input) {
    return accepted(connections.check(account(request, id), input.clientRequestId()));
  }

  @PostMapping("/api/v1/marketplace-accounts/{id}/connections/disconnect")
  public ConnectionService.ConnectionView disconnect(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody Mutation input) {
    return connections.disconnect(
        account(request, id), input.expectedRevision(), input.clientRequestId());
  }

  @GetMapping("/api/v1/marketplace-accounts/{id}/sources")
  public Page<MarketplaceReadService.SourceStatus> sources(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    Scope scope = account(request, id);
    return transactions.run(scope, () -> reads.sources(scope, page, size));
  }

  @GetMapping("/api/v1/marketplace-accounts/{id}/capabilities")
  public List<MarketplaceReadService.Capability> capabilities(
      HttpServletRequest request, @PathVariable UUID id) {
    Scope scope = account(request, id);
    return transactions.run(scope, () -> reads.capabilities(scope));
  }

  @PostMapping("/api/v1/marketplace-accounts/{id}/sync")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult sync(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "ALL") String source,
      @Valid @RequestBody Mutation input) {
    return accepted(synchronization.request(account(request, id), source, input.clientRequestId()));
  }

  @PostMapping("/api/v1/marketplace-accounts/{id}/history-sync")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult history(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody HistoryRequest input) {
    return accepted(
        synchronization.requestHistory(
            account(request, id), input.from(), input.until(), input.clientRequestId()));
  }

  @PostMapping("/api/v1/marketplace-accounts/{id}/report-sync")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public OperationResult report(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody ReportRequest input) {
    return accepted(
        reports.request(
            account(request, id),
            input.kind(),
            input.from(),
            input.until(),
            input.campaignId(),
            input.clientRequestId()));
  }

  @GetMapping("/api/v1/marketplace-accounts/{id}/campaigns")
  public Page<ReportSyncService.Campaign> campaigns(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    Scope scope = account(request, id);
    return transactions.run(scope, () -> reports.campaigns(scope, page, size));
  }

  @GetMapping("/api/v1/marketplace-accounts/{id}/sync-runs")
  public Page<MarketplaceReadService.SyncRun> runs(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    Scope scope = account(request, id);
    return transactions.run(scope, () -> reads.syncRuns(scope, page, size));
  }

  @GetMapping("/api/v1/offers")
  public Page<OfferReadService.Offer> offers(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "id") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam Map<String, String> parameters) {
    Scope scope = identity.scope(request);
    var filters = new LinkedHashMap<>(parameters);
    List.of("page", "size", "search", "sort", "direction").forEach(filters::remove);
    var query = new TableExportSource.Query(search, filters, sort + "," + direction, page, size);
    return transactions.run(scope, () -> offerReads.page(scope, query));
  }

  @GetMapping("/api/v1/offers/{id}")
  public OfferReadService.Offer offer(HttpServletRequest request, @PathVariable UUID id) {
    Scope scope = identity.scope(request);
    return transactions.run(scope, () -> offerReads.get(scope, id));
  }

  @GetMapping("/api/v1/offers/{id}/placements")
  public Page<MarketplaceReadService.Placement> placements(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    Scope scope = identity.scope(request);
    return transactions.run(scope, () -> reads.placements(scope, id, page, size));
  }

  @GetMapping("/api/v1/offers/{id}/price-indices")
  public MarketplaceReadService.SupplierPriceIndices priceIndices(
      HttpServletRequest request, @PathVariable UUID id) {
    Scope scope = identity.scope(request);
    return transactions.run(scope, () -> reads.supplierPriceIndices(scope, id));
  }

  @GetMapping("/api/v1/promotions")
  public Page<MarketplaceReadService.Promotion> promotions(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search) {
    Scope scope = identity.scope(request);
    return transactions.run(scope, () -> reads.promotions(scope, page, size, search));
  }

  @GetMapping("/api/v1/categories")
  public Page<MarketplaceReadService.Category> categories(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(required = false) UUID parentId) {
    Scope scope = identity.scope(request);
    return transactions.run(scope, () -> reads.categories(scope, page, size, search, parentId));
  }

  @GetMapping("/api/v1/warehouses")
  public Page<MarketplaceReadService.Warehouse> warehouses(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search) {
    Scope scope = identity.scope(request);
    return transactions.run(scope, () -> reads.warehouses(scope, page, size, search));
  }

  private Scope organization(HttpServletRequest request) {
    Scope supplied = identity.scope(request);
    return new Scope(supplied.requireOrganization(), null, supplied.subjectId());
  }

  private Scope account(HttpServletRequest request, UUID id) {
    Scope supplied = identity.scope(request);
    return new Scope(supplied.requireOrganization(), id, supplied.subjectId());
  }

  private static OperationResult accepted(UUID job) {
    return new OperationResult(job, "QUEUED");
  }

  public record AccountRequest(
      @NotNull UUID clientRequestId,
      @Min(0) long expectedRevision,
      @NotBlank @Size(max = 160) String name,
      @NotBlank String marketplace,
      @NotBlank @Size(max = 100) String externalId,
      @NotBlank @Size(max = 80) String timezone) {}

  public record ConnectionRequest(
      @NotNull UUID clientRequestId,
      @Min(0) long expectedRevision,
      @NotBlank @Size(max = 4096) String secret,
      @NotNull Boolean readOnly) {}

  public record Mutation(@NotNull UUID clientRequestId, @Min(0) long expectedRevision) {}

  public record HistoryRequest(
      @NotNull UUID clientRequestId, @NotNull LocalDate from, @NotNull LocalDate until) {}

  public record ReportRequest(
      @NotNull UUID clientRequestId,
      @NotBlank String kind,
      @NotNull LocalDate from,
      @NotNull LocalDate until,
      @Size(max = 20) String campaignId) {}
}
