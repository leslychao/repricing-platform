package ru.oritas.repricer.app.files;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.app.RequestIdentity;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.StoredFileService;

@RestController
public final class ReportController {
  private final RequestIdentity identity;
  private final ScopeTransactionRunner transactions;
  private final SelectionService selections;
  private final ReportService reports;
  private final StoredFileService files;

  public ReportController(
      RequestIdentity identity,
      ScopeTransactionRunner transactions,
      SelectionService selections,
      ReportService reports,
      StoredFileService files) {
    this.identity = identity;
    this.transactions = transactions;
    this.selections = selections;
    this.reports = reports;
    this.files = files;
  }

  @PostMapping("/api/v1/selections")
  public SelectionService.Selection select(
      HttpServletRequest request, @RequestBody SelectionService.Request body) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> selections.create(scope, body));
  }

  @GetMapping("/api/v1/selections/{id}")
  public SelectionService.Selection selection(HttpServletRequest request, @PathVariable UUID id) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> selections.get(scope, id));
  }

  @PostMapping("/api/v1/reports")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ReportService.Accepted create(
      HttpServletRequest request, @RequestBody ReportService.Request body) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> reports.create(scope, body));
  }

  @GetMapping("/api/v1/reports")
  public Page<ReportService.Summary> list(
      HttpServletRequest request,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "date") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to) {
    var scope = identity.scope(request);
    return transactions.run(
        scope, () -> reports.list(scope, page, size, status, search, sort, direction, from, to));
  }

  @GetMapping("/api/v1/reports/{id}")
  public ReportService.Summary get(HttpServletRequest request, @PathVariable UUID id) {
    var scope = identity.scope(request);
    return transactions.run(scope, () -> reports.get(scope, id));
  }

  @GetMapping("/api/v1/reports/{id}/download")
  public ResponseEntity<StreamingResponseBody> download(
      HttpServletRequest request,
      @PathVariable UUID id,
      @RequestParam UUID organizationId,
      @RequestParam(required = false) UUID accountId) {
    var scope = identity.downloadScope(request, organizationId, accountId);
    var file = transactions.run(scope, () -> reports.download(scope, id));
    StreamingResponseBody body =
        output -> {
          // Authorize again on the async thread and during long downloads, never expose an S3 URL.
          transactions.run(scope, () -> reports.download(scope, id));
          try (InputStream input = files.open(file)) {
            byte[] buffer = new byte[65536];
            long sinceAuthorization = 0;
            long deadline = System.nanoTime() + java.time.Duration.ofMinutes(5).toNanos();
            int count;
            while ((count = input.read(buffer)) != -1) {
              if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
                throw new IOException("Download deadline exceeded");
              }
              if (sinceAuthorization >= 8 * 1024 * 1024) {
                transactions.run(scope, () -> reports.download(scope, id));
                sinceAuthorization = 0;
              }
              output.write(buffer, 0, count);
              sinceAuthorization += count;
            }
          }
        };
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Disposition", "attachment; filename=\"" + file.originalName() + "\"")
        .header("Content-Type", file.mediaType())
        .contentLength(file.byteCount())
        .body(body);
  }
}
