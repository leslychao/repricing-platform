package ru.oritas.repricer.marketplace;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OperationService;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** Queue exhaustion closes source work atomically with the durable delivery receipt. */
@Component
public final class MarketplaceOperationFailureRecipient implements OutboxRecipient {
  private final OperationService operations;
  private final CatalogSyncService catalog;
  private final ReportSyncService reports;
  private final JsonCodec json;

  public MarketplaceOperationFailureRecipient(
      OperationService operations,
      CatalogSyncService catalog,
      ReportSyncService reports,
      JsonCodec json) {
    this.operations = operations;
    this.catalog = catalog;
    this.reports = reports;
    this.json = json;
  }

  @Override
  public String name() {
    return "MARKETPLACE_OPERATION_FAILURE";
  }

  @Override
  public boolean accepts(String type) {
    return type.equals("operation.changed");
  }

  @Override
  public Set<String> servicePermissions() {
    return Set.of("marketplace.sync.record");
  }

  @Override
  public void receive(Scope scope, UUID event, String type, String payload) {
    var change = json.decode(payload, OutboxService.EntityChange.class);
    var operation = operations.get(change.entityId());
    if (!Set.of("DEAD", "BLOCKED", "CANCELLED").contains(operation.status())) {
      return;
    }
    String reason = operation.message();
    if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{0,127}")) {
      reason = "SOURCE_WORK_FAILED";
    }
    if (operation.name().equals("SUPPLIER_REPORT")) {
      reports.onTerminalFailure(scope, operation.id(), reason);
    } else if (Set.of("CATALOG_SYNC", "STOCK_SYNC", "PROMOTION_SYNC", "HISTORY_SYNC")
        .contains(operation.name())) {
      catalog.onTerminalFailure(scope, operation.id(), reason);
    }
  }
}
