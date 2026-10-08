package ru.oritas.repricer.app.files;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.CsvTableWriter;
import ru.oritas.repricer.platform.FileWorkGate;
import ru.oritas.repricer.platform.GeneratedFile;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.StoredFileService;
import ru.oritas.repricer.platform.XlsxTableWriter;

@Component
public final class ReportExportHandler implements JobHandler {
  private final ReportService reports;
  private final SelectionService selections;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final StoredFileService files;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final JdbcClient jdbc;
  private final FileWorkGate gate;
  private final Clock clock;

  public ReportExportHandler(
      ReportService reports,
      SelectionService selections,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      StoredFileService files,
      JobRuntime jobs,
      JsonCodec json,
      JdbcClient jdbc,
      FileWorkGate gate,
      Clock clock) {
    this.reports = reports;
    this.selections = selections;
    this.transactions = transactions;
    this.authorization = authorization;
    this.files = files;
    this.jobs = jobs;
    this.json = json;
    this.jdbc = jdbc;
    this.gate = gate;
    this.clock = clock;
  }

  @Override
  public String type() {
    return "REPORT_EXPORT";
  }

  @Override
  public String lane() {
    return "canonicalization";
  }

  @Override
  public Duration maximumAttemptDuration() {
    return Duration.ofMinutes(30);
  }

  @Override
  public JobOutcome execute(JobContext context) throws IOException {
    UUID id = json.decode(context.payload(), ReportService.Work.class).reportId();
    var acquired = gate.tryAcquire(context.deadline());
    if (acquired.isEmpty()) {
      return JobOutcome.waiting("FILE_SLOTS_BUSY", clock.instant().plusSeconds(5));
    }
    try (FileWorkGate.Permit permit = acquired.orElseThrow()) {
      ReportService.Header header =
          transactions.run(
              context.scope(),
              () -> {
                authorization.requireLocked(context.scope(), Set.of("export"));
                reports.requireRead(context.scope(), id);
                jobs.requireOwnership(context);
                var current = reports.load(id, true);
                if (current.state().equals("PENDING")) {
                  jdbc.sql(
                          "UPDATE app_report SET state='PREPARING',revision=revision+1 WHERE"
                              + " id=:id")
                      .param("id", id)
                      .update();
                  reports.changed(context.scope(), id, current.revision() + 1);
                }
                return current;
              });
      if (Set.of("READY", "FAILED").contains(header.state())) {
        return JobOutcome.succeeded(json.encode(new Result(id, header.state())));
      }
      SelectionService.Snapshot snapshot =
          transactions.run(
              context.scope(),
              () -> selections.snapshot(context.scope(), header.selectionId(), false));
      String media =
          header.format().equals("CSV")
              ? "text/csv"
              : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
      String name = "repricer-" + id + "." + header.format().toLowerCase(java.util.Locale.ROOT);
      StoredFileService.Recovery recovery =
          header.preparedFileId() == null
              ? null
              : files.recover(context.scope(), header.preparedFileId());
      if (recovery != null && recovery.retryAt() != null) {
        return JobOutcome.waiting("PREVIOUS_FILE_ATTEMPT_ACTIVE", recovery.retryAt());
      }
      StoredFileService.FileRecord file;
      if (recovery != null && Set.of("STORED", "READY").contains(recovery.file().state())) {
        file = recovery.file();
      } else {
        UUID prepared =
            recovery != null && recovery.file().state().equals("UPLOADING")
                ? recovery.file().id()
                : files.prepare(context.scope(), "REPORT", media, name);
        transactions.run(
            context.scope(),
            () -> {
              authorization.requireLocked(context.scope(), Set.of("export"));
              reports.requireRead(context.scope(), id);
              jobs.requireOwnership(context);
              jdbc.sql(
                      "UPDATE app_report SET prepared_file_id=:file WHERE id=:id AND"
                          + " state='PREPARING'")
                  .param("file", prepared)
                  .param("id", id)
                  .update();
            });
        file =
            GeneratedFile.storePrepared(
                files,
                context.scope(),
                prepared,
                512L * 1024 * 1024,
                context.deadline(),
                output -> write(context, snapshot, header.format(), output, permit));
      }
      return transactions.run(
          context.scope(),
          () -> {
            authorization.requireLocked(context.scope(), Set.of("export"));
            reports.requireRead(context.scope(), id);
            jobs.requireOwnership(context);
            permit.confirm();
            reports.publish(context.scope(), id, file);
            return JobOutcome.succeeded(json.encode(new Result(id, "READY")));
          });
    } catch (BusinessException exception) {
      if (Set.of("LEASE_EXPIRED", "FILE_LEASE_EXPIRED", "ACCESS_DENIED", "MEMBERSHIP_REQUIRED")
          .contains(exception.code())) {
        throw exception;
      }
      transactions.run(
          context.scope(),
          () -> {
            jobs.requireOwnership(context);
            var current = reports.load(id, true);
            jdbc.sql(
                    "UPDATE app_report SET state='FAILED',reason=:reason,revision=revision+1 WHERE"
                        + " id=:id")
                .param("reason", exception.code())
                .param("id", id)
                .update();
            reports.changed(context.scope(), id, current.revision() + 1);
            selections.release(context.scope(), current.selectionId(), "REPORT", id);
          });
      return JobOutcome.blocked(exception.code());
    }
  }

  private void write(
      JobContext context,
      SelectionService.Snapshot snapshot,
      String format,
      OutputStream output,
      FileWorkGate.Permit permit)
      throws IOException {
    var headers = snapshot.columns().stream().map(column -> column.title()).toList();
    if (format.equals("XLSX")) {
      try (var writer = new XlsxTableWriter(output, headers)) {
        writeRows(context, snapshot, permit, writer::row);
      }
    } else {
      var destination = new OutputStreamWriter(output, StandardCharsets.UTF_8);
      var writer = new CsvTableWriter(destination, headers);
      writeRows(context, snapshot, permit, writer::row);
      destination.flush();
    }
  }

  private void writeRows(
      JobContext context,
      SelectionService.Snapshot snapshot,
      FileWorkGate.Permit permit,
      RowSink writer)
      throws IOException {
    var source = selections.source(snapshot.resource());
    long after = 0;
    while (after < snapshot.total()) {
      permit.requireValid();
      long cursor = after;
      List<SelectionService.Row> rows =
          transactions.run(
              context.scope(),
              () -> {
                authorization.require(context.scope(), "export");
                jobs.requireOwnership(context);
                return selections.rows(context.scope(), snapshot.id(), cursor);
              });
      if (rows.isEmpty()) {
        throw new IOException("Immutable export snapshot is incomplete");
      }
      for (SelectionService.Row row : rows) {
        if (row.ordinal() != after + 1 || row.cells().size() != snapshot.columns().size()) {
          throw new IOException("Immutable export snapshot is inconsistent");
        }
        List<String> values = source.exportCells(snapshot.columns(), row.cells());
        if (values.size() != snapshot.columns().size()) {
          throw new IOException("Export calculation changed the captured row layout");
        }
        List<CsvTableWriter.Cell> cells = new ArrayList<>(values.size());
        for (int column = 0; column < values.size(); column++) {
          cells.add(
              new CsvTableWriter.Cell(
                  values.get(column), snapshot.columns().get(column).numeric()));
        }
        writer.write(cells);
        after = row.ordinal();
      }
    }
  }

  @FunctionalInterface
  private interface RowSink {
    void write(List<CsvTableWriter.Cell> row) throws IOException;
  }

  private record Result(UUID reportId, String status) {}
}
