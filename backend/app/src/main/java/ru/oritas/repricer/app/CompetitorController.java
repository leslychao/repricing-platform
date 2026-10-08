package ru.oritas.repricer.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
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
import ru.oritas.repricer.marketplace.MarketplaceCompetitorService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.TableExportSource;

@RestController
public final class CompetitorController {
  private final RequestIdentity identity;
  private final ScopeTransactionRunner transactions;
  private final MarketplaceCompetitorService observations;
  private final CompetitorApplicationService commands;

  public CompetitorController(
      RequestIdentity identity,
      ScopeTransactionRunner transactions,
      MarketplaceCompetitorService observations,
      CompetitorApplicationService commands) {
    this.identity = identity;
    this.transactions = transactions;
    this.observations = observations;
    this.commands = commands;
  }

  @GetMapping("/api/v1/competitor-observations")
  public Page<MarketplaceCompetitorService.Observation> page(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "observedAt") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam Map<String, String> parameters) {
    var scope = identity.scope(request);
    var filters = new LinkedHashMap<>(parameters);
    List.of("page", "size", "search", "sort", "direction").forEach(filters::remove);
    var query = new TableExportSource.Query(search, filters, sort + "," + direction, page, size);
    return transactions.run(scope, () -> observations.page(scope, query));
  }

  @GetMapping("/api/v1/competitor-observations/sources")
  public Page<MarketplaceCompetitorService.Source> sources(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> observations.sources(scope, search, page, size));
  }

  @PostMapping("/api/v1/competitor-observations")
  @ResponseStatus(HttpStatus.CREATED)
  public MarketplaceCompetitorService.Observation create(
      HttpServletRequest request, @Valid @RequestBody ObservationRequest input) {
    return commands.create(
        identity.scope(request),
        input.observation(),
        input.expectedRevision(),
        input.clientRequestId());
  }

  @PutMapping("/api/v1/competitor-observations/{id}")
  public MarketplaceCompetitorService.Observation correct(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody ObservationRequest input) {
    return commands.correct(
        identity.scope(request),
        id,
        input.observation(),
        input.expectedRevision(),
        input.clientRequestId());
  }

  @PostMapping("/api/v1/competitor-observations/{id}/revoke")
  public MarketplaceCompetitorService.Observation revoke(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody Mutation input) {
    return commands.revoke(
        identity.scope(request), id, input.expectedRevision(), input.clientRequestId());
  }

  public record Mutation(@NotNull UUID clientRequestId, @NotNull @Min(1) Long expectedRevision) {}

  public record ObservationRequest(
      @NotNull UUID clientRequestId,
      @NotNull @Min(0) Long expectedRevision,
      @NotNull UUID offerId,
      @NotNull UUID sourceId,
      @NotBlank @Size(max = 200) String sellerName,
      @NotBlank @Size(max = 1000) String segment,
      @NotNull Instant observedAt,
      Boolean inStock,
      BigDecimal price,
      BigDecimal delivery,
      @NotNull Boolean ownOffer) {
    MarketplaceCompetitorService.Input observation() {
      return new MarketplaceCompetitorService.Input(
          offerId, sourceId, sellerName, segment, observedAt, inStock, price, delivery, ownOffer);
    }
  }
}
