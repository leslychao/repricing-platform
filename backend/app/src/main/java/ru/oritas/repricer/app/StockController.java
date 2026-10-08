package ru.oritas.repricer.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.app.read.StockReadService;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.TableExportSource;

@RestController
public final class StockController {
  private final RequestIdentity identity;
  private final ScopeTransactionRunner transactions;
  private final StockReadService stocks;
  private final ResourceService resources;

  public StockController(
      RequestIdentity identity,
      ScopeTransactionRunner transactions,
      StockReadService stocks,
      ResourceService resources) {
    this.identity = identity;
    this.transactions = transactions;
    this.stocks = stocks;
    this.resources = resources;
  }

  @GetMapping("/api/v1/reservations")
  public Page<ResourceService.ReservationView> reservations(
      HttpServletRequest request,
      @RequestParam(required = false) UUID offerId,
      @RequestParam(required = false) UUID poolId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    var scope = identity.scope(request);
    return transactions.run(
        scope, () -> resources.reservations(scope, offerId, poolId, page, size));
  }

  @GetMapping("/api/v1/obligations")
  public Page<ResourceService.ObligationView> obligations(
      HttpServletRequest request,
      @RequestParam(required = false) UUID offerId,
      @RequestParam(required = false) UUID poolId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> resources.obligations(scope, offerId, poolId, page, size));
  }

  @GetMapping("/api/v1/stock-pools")
  public Page<StockReadService.Stock> stocks(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "id") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam Map<String, String> parameters) {
    var scope = identity.scope(request);
    var filters = new LinkedHashMap<>(parameters);
    List.of("page", "size", "search", "sort", "direction").forEach(filters::remove);
    var query = new TableExportSource.Query(search, filters, sort + "," + direction, page, size);
    return transactions.run(scope, () -> stocks.page(scope, query));
  }
}
