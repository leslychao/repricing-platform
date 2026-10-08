package ru.oritas.repricer.app.files;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OperationService;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** Terminal job state and this durable notification commit together in JobRuntime. */
@Component
public final class FilesOperationFailureRecipient implements OutboxRecipient {
  private final OperationService operations;
  private final ImportService imports;
  private final ReportService reports;
  private final JsonCodec json;

  public FilesOperationFailureRecipient(OperationService operations, ImportService imports,
      ReportService reports, JsonCodec json) {
    this.operations = operations;
    this.imports = imports;
    this.reports = reports;
    this.json = json;
  }

  @Override public String name() { return "FILE_OPERATION_FAILURE"; }
  @Override public boolean accepts(String type) { return type.equals("operation.changed"); }
  @Override public Set<String> servicePermissions() { return Set.of("app.files.terminal"); }

  @Override public void receive(Scope scope, UUID eventId, String type, String payload) {
    var change = json.decode(payload, OutboxService.EntityChange.class);
    var operation = operations.get(change.entityId());
    if (!Set.of("DEAD", "BLOCKED").contains(operation.status())) {
      return;
    }
    switch (operation.name()) {
      case "REPORT_EXPORT" -> reports.onTerminalFailure(scope, operation.id(), operation.message());
      case "IMPORT_VALIDATE", "IMPORT_APPLY" -> imports.onTerminalFailure(scope, operation.id(), operation.message());
      default -> { /* Other owners handle their own terminal business states. */ }
    }
  }
}
