package ru.oritas.repricer.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.app.preferences.ViewStateService;
import ru.oritas.repricer.app.realtime.NotificationService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.HistoryQuery;
import ru.oritas.repricer.platform.OperationService;
import ru.oritas.repricer.platform.Page;

@RestController
public final class WorkspaceController {
  private final RequestIdentity identity;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ViewStateService views;
  private final OperationService operations;
  private final AuditService audit;
  private final NotificationService notifications;
  private final MarketplaceReadService marketplace;

  public WorkspaceController(
      RequestIdentity identity,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ViewStateService views,
      OperationService operations,
      AuditService audit,
      NotificationService notifications,
      MarketplaceReadService marketplace) {
    this.identity = identity;
    this.transactions = transactions;
    this.authorization = authorization;
    this.views = views;
    this.operations = operations;
    this.audit = audit;
    this.notifications = notifications;
    this.marketplace = marketplace;
  }

  @GetMapping("/api/v1/me/view-states/{key}")
  public ViewStateService.SavedView view(HttpServletRequest request, @PathVariable String key) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> views.get(scope, key));
  }

  @PutMapping("/api/v1/me/view-states/{key}")
  public ViewStateService.SavedView saveView(
      HttpServletRequest request,
      @PathVariable String key,
      @Valid @RequestBody ViewStateService.SaveRequest input) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> views.save(scope, key, input));
  }

  @PostMapping("/api/v1/me/view-states/{key}/reset")
  public ViewStateService.SavedView resetView(
      HttpServletRequest request,
      @PathVariable String key,
      @Valid @RequestBody ViewStateService.RevisionRequest input) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> views.reset(scope, key, input));
  }

  @GetMapping("/api/v1/operations")
  public Page<OperationService.Operation> operations(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "createdAt") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    var scope = identity.scope(request);
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "operation.read");
          return operations.list(
              new HistoryQuery(search, status, from, to, sort, direction, page, size),
              scope.accountId() == null ? ZoneOffset.UTC : marketplace.zoneId(scope));
        });
  }

  @GetMapping("/api/v1/operations/{id}")
  public OperationService.Operation operation(HttpServletRequest request, @PathVariable UUID id) {
    var scope = identity.scope(request);
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "operation.read");
          return operations.get(id);
        });
  }

  @GetMapping("/api/v1/audit")
  public Page<AuditService.Entry> audit(
      HttpServletRequest request,
      @RequestParam(required = false) UUID offerId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "createdAt") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    var scope = identity.scope(request);
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "audit.read");
          return audit.list(
              scope,
              new AuditService.Query(
                  new HistoryQuery(search, status, from, to, sort, direction, page, size), offerId),
              scope.accountId() == null ? ZoneOffset.UTC : marketplace.zoneId(scope));
        });
  }

  @GetMapping("/api/v1/notifications")
  public Page<NotificationService.Notification> notifications(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> notifications.list(scope, page, size));
  }

  @PostMapping("/api/v1/notifications/{id}/read")
  public NotificationService.Receipt read(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody AccessController.RevisionRequest input) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> notifications.read(scope, id, input.clientRequestId()));
  }

  @PostMapping("/api/v1/notifications/read-all")
  public NotificationService.Receipt readAll(
      HttpServletRequest request, @Valid @RequestBody AccessController.RevisionRequest input) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> notifications.readAll(scope, input.clientRequestId()));
  }
}
