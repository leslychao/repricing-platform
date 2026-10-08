package ru.oritas.repricer.app.files;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.app.RequestIdentity;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.CsvTableWriter;
import ru.oritas.repricer.platform.FileWorkGate;
import ru.oritas.repricer.platform.Page;

@RestController
public final class ImportController {
  private final RequestIdentity identity;
  private final ScopeTransactionRunner transactions;
  private final ImportService imports;

  public ImportController(
      RequestIdentity identity, ScopeTransactionRunner transactions, ImportService imports) {
    this.identity = identity;
    this.transactions = transactions;
    this.imports = imports;
  }

  @PostMapping(value = "/api/v1/imports", consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ImportService.Accepted upload(
      HttpServletRequest request,
      @RequestParam MultipartFile file,
      @RequestParam String kind,
      @RequestParam UUID clientRequestId,
      @RequestParam long expectedRevision,
      @RequestParam(defaultValue = "SEMICOLON") String delimiter,
      @RequestParam(defaultValue = "DOT") String decimalSeparator,
      @RequestParam(required = false) String sheetName)
      throws IOException {
    if (expectedRevision != 0 || file.isEmpty() || file.getSize() > 100L * 1024 * 1024) {
      throw new BusinessException("INVALID_IMPORT", 422, "Нужен непустой файл размером до 100 MiB");
    }
    if (!(request.getAttribute(UploadAdmissionFilter.PERMIT_ATTRIBUTE)
        instanceof FileWorkGate.Permit permit)) {
      throw new IllegalStateException("Upload admission is required before multipart resolution");
    }
    String name = file.getOriginalFilename();
    String suffix = name == null ? "" : name.toLowerCase(Locale.ROOT);
    String format = suffix.endsWith(".csv") ? "CSV" : suffix.endsWith(".xlsx") ? "XLSX" : "";
    var options = new ImportService.Options(format, delimiter, decimalSeparator, sheetName);
    try (var input = file.getInputStream()) {
      return imports.upload(
          identity.scope(request), kind, name, options, clientRequestId, input, permit);
    }
  }

  @GetMapping("/api/v1/imports")
  public Page<ImportService.Summary> list(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String kind,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "date") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    var scope = identity.scope(request);
    return transactions.run(
        scope,
        () -> imports.list(scope, page, size, status, kind, search, sort, direction, from, to));
  }

  @GetMapping("/api/v1/imports/{id}")
  public ImportService.Detail detail(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> imports.detail(scope, id, page, size));
  }

  @PostMapping("/api/v1/imports/{id}/apply")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ImportService.Accepted apply(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestBody ImportService.Mutation input) {
    return imports.apply(identity.scope(request), id, input);
  }

  @PostMapping("/api/v1/imports/{id}/revalidate")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ImportService.Accepted revalidate(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestBody ImportService.Revalidation input) {
    return imports.revalidate(identity.scope(request), id, input);
  }

  @PostMapping("/api/v1/imports/{id}/cancel")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void cancel(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestBody ImportService.Mutation input) {
    imports.cancel(identity.scope(request), id, input.clientRequestId(), input.expectedRevision());
  }

  @GetMapping("/api/v1/imports/templates/{kind}")
  public ResponseEntity<StreamingResponseBody> template(
      HttpServletRequest request,
      @PathVariable String kind,
      @RequestParam UUID organizationId,
      @RequestParam UUID accountId) {
    var scope = identity.downloadScope(request, organizationId, accountId);
    List<String> headers = transactions.run(scope, () -> imports.template(scope, kind));
    StreamingResponseBody body =
        output -> {
          var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
          new CsvTableWriter(writer, headers);
          writer.flush();
        };
    return ResponseEntity.ok()
        .header("Content-Type", "text/csv;charset=UTF-8")
        .header("Content-Disposition", "attachment; filename=import-template.csv")
        .body(body);
  }

  @GetMapping("/api/v1/imports/{id}/errors")
  public ResponseEntity<StreamingResponseBody> errors(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam UUID organizationId,
      @RequestParam UUID accountId) {
    var scope = identity.downloadScope(request, organizationId, accountId);
    List<ImportService.RowResult> first =
        transactions.run(scope, () -> imports.errors(scope, id, -1));
    StreamingResponseBody body =
        output -> {
          var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
          var csv = new CsvTableWriter(writer, List.of("row", "status", "message"));
          List<ImportService.RowResult> rows = first;
          while (!rows.isEmpty()) {
            for (var row : rows) {
              csv.row(
                  List.of(
                      CsvTableWriter.Cell.text(Integer.toString(row.row())),
                      CsvTableWriter.Cell.text(row.status()),
                      CsvTableWriter.Cell.text(row.message())));
            }
            writer.flush();
            int after = rows.getLast().row();
            rows = transactions.run(scope, () -> imports.errors(scope, id, after));
          }
        };
    return ResponseEntity.ok()
        .header("Content-Type", "text/csv;charset=UTF-8")
        .header("Content-Disposition", "attachment; filename=import-errors.csv")
        .body(body);
  }
}
