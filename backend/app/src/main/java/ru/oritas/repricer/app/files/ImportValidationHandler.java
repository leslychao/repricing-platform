package ru.oritas.repricer.app.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.FileWorkGate;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.StoredFileService;
import ru.oritas.repricer.platform.TableFormatException;
import ru.oritas.repricer.platform.TabularImportTarget;
import ru.oritas.repricer.platform.TabularReader;
import ru.oritas.repricer.platform.XlsxTableReader;

@Component
public final class ImportValidationHandler implements JobHandler {
  private final ImportService imports;
  private final ScopeTransactionRunner transactions;
  private final StoredFileService files;
  private final FileWorkGate gate;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batches;
  private final Clock clock;

  public ImportValidationHandler(
      ImportService imports,
      ScopeTransactionRunner transactions,
      StoredFileService files,
      FileWorkGate gate,
      JobRuntime jobs,
      JsonCodec json,
      JdbcClient jdbc,
      NamedParameterJdbcTemplate batches,
      Clock clock) {
    this.imports = imports;
    this.transactions = transactions;
    this.files = files;
    this.gate = gate;
    this.jobs = jobs;
    this.json = json;
    this.jdbc = jdbc;
    this.batches = batches;
    this.clock = clock;
  }

  @Override
  public String type() {
    return "IMPORT_VALIDATE";
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
    UUID id = json.decode(context.payload(), ImportService.Work.class).importId();
    try {
      return validate(context, id);
    } catch (TableFormatException failure) {
      imports.failWork(context, id, "DRAFT", "INVALID", "INVALID_FILE_FORMAT");
      return JobOutcome.succeeded(json.encode(new ValidationResult(id, "INVALID")));
    } catch (BusinessException failure) {
      if (Set.of("LEASE_EXPIRED", "FILE_LEASE_EXPIRED").contains(failure.code())) {
        throw failure;
      }
      String state = "INVALID";
      if (failure.code().equals("STALE_REVISION")) {
        state = "STALE";
      } else if (failure.status() == 403) {
        state = "FAILED";
      }
      imports.failWork(context, id, "DRAFT", state, failure.code());
      return JobOutcome.blocked(failure.code());
    } catch (IOException | RuntimeException failure) {
      if (context.attempt() >= 5) {
        imports.failWork(context, id, "DRAFT", "FAILED", "IMPORT_PROCESSING_FAILED");
        return JobOutcome.blocked("IMPORT_PROCESSING_FAILED");
      }
      throw failure;
    }
  }

  private JobOutcome validate(JobContext context, UUID id) throws IOException {
    ImportService.Header header =
        transactions.run(
            context.scope(),
            () -> {
              var loaded = imports.load(id, false);
              imports.requireWrite(context.scope(), imports.target(loaded.kind()));
              jobs.requireOwnership(context);
              return loaded;
            });
    if (!header.status().equals("DRAFT") || !context.id().equals(header.validationJobId())) {
      return JobOutcome.succeeded(json.encode(new ValidationResult(id, header.status())));
    }
    var acquired = gate.tryAcquire(context.deadline());
    if (acquired.isEmpty()) {
      return JobOutcome.waiting("FILE_SLOTS_BUSY", clock.instant().plusSeconds(5));
    }
    TabularImportTarget target = imports.target(header.kind());
    try (FileWorkGate.Permit permit = acquired.orElseThrow()) {
      if (!header.parsed()) {
        parse(context, header, target, permit);
      }
      while (true) {
        permit.requireValid();
        boolean done = transactions.run(context.scope(), () -> stageNext(context, id, target));
        if (done) {
          break;
        }
      }
      return transactions.run(
          context.scope(),
          () -> {
            imports.requireWrite(context.scope(), target);
            jobs.requireOwnership(context);
            permit.confirm();
            target.lockForPublication(context.scope(), header.validationId());
            var current = imports.load(id, true);
            if (!current.status().equals("DRAFT")
                || !context.id().equals(current.validationJobId())) {
              return JobOutcome.succeeded(json.encode(new ValidationResult(id, current.status())));
            }
            if (current.errorRows() == 0) {
              target.seal(context.scope(), current.validationId());
            }
            jobs.requireOwnership(context);
            permit.confirm();
            String state = current.errorRows() == 0 ? "VALIDATED" : "INVALID";
            imports.transition(context.scope(), current, state, null);
            return JobOutcome.succeeded(json.encode(new ValidationResult(id, state)));
          });
    }
  }

  private void parse(
      JobContext context,
      ImportService.Header header,
      TabularImportTarget target,
      FileWorkGate.Permit permit)
      throws IOException {
    var validator =
        new ImportTableValidator(
            target.columns(), header.options().decimalSeparator().equals("COMMA"));
    List<ImportTableValidator.Checked> pending = new ArrayList<>(500);
    TabularReader.RowConsumer consumer =
        row -> {
          permit.requireValid();
          var checked = validator.accept(row);
          if (checked != null) {
            pending.add(checked);
          }
          if (pending.size() == 500) {
            save(context, header.id(), pending);
            pending.clear();
          }
        };
    var file = transactions.run(context.scope(), () -> files.get(header.fileId()));
    if (header.options().format().equals("CSV")) {
      try (InputStream input = files.open(file)) {
        new TabularReader()
            .csv(input, header.options().delimiter().equals("COMMA") ? ',' : ';', consumer);
      }
    } else {
      Path scratch = Files.createTempDirectory("repricer-import-" + context.id() + "-");
      Path source = scratch.resolve("input.xlsx");
      try {
        try (InputStream input = files.open(file);
            var output = Files.newOutputStream(source)) {
          byte[] buffer = new byte[65536];
          long copied = 0;
          int count;
          while ((count = input.read(buffer)) != -1) {
            permit.requireValid();
            copied += count;
            if (copied > 100L * 1024 * 1024) {
              throw new TableFormatException("Workbook exceeds the upload limit");
            }
            output.write(buffer, 0, count);
          }
        }
        new XlsxTableReader().read(source, header.options().sheetName(), scratch, consumer);
      } finally {
        Files.deleteIfExists(source);
        Files.deleteIfExists(scratch);
      }
    }
    if (!pending.isEmpty()) {
      save(context, header.id(), pending);
    }
    transactions.run(
        context.scope(),
        () -> {
          imports.requireWrite(context.scope(), target);
          jobs.requireOwnership(context);
          permit.confirm();
          jdbc.sql(
                  """
                  UPDATE app_import SET parsed=true,
                    total_rows=(SELECT count(*) FROM app_import_row WHERE import_id=:id AND row_number>0),
                    valid_rows=(SELECT count(*) FROM app_import_row WHERE import_id=:id AND valid),
                    error_rows=(SELECT count(*) FROM app_import_row WHERE import_id=:id AND NOT valid)
                  WHERE id=:id AND state='DRAFT'
                  """)
              .param("id", header.id())
              .update();
          return true;
        });
  }

  private void save(JobContext context, UUID id, List<ImportTableValidator.Checked> rows) {
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          var header = imports.load(id, false);
          imports.requireWrite(context.scope(), imports.target(header.kind()));
          header = imports.load(id, true);
          if (!header.status().equals("DRAFT") || !context.id().equals(header.validationJobId())) {
            throw new BusinessException("IMPORT_CANCELLED", 409, "Проверка отменена");
          }
          MapSqlParameterSource[] parameters =
              rows.stream()
                  .map(
                      checked ->
                          new MapSqlParameterSource()
                              .addValue("org", context.scope().organizationId())
                              .addValue("account", context.scope().accountId())
                              .addValue("id", id)
                              .addValue("row", checked.row().number())
                              .addValue("values", json.encode(checked.row()))
                              .addValue("errors", json.encode(checked.errors()))
                              .addValue("valid", checked.errors().isEmpty()))
                  .toArray(MapSqlParameterSource[]::new);
          batches.batchUpdate(
              """
              INSERT INTO app_import_row(organization_id,account_id,import_id,row_number,values,errors,valid)
              VALUES (:org,:account,:id,:row,CAST(:values AS jsonb),CAST(:errors AS jsonb),:valid)
              ON CONFLICT(import_id,row_number) DO NOTHING
              """,
              parameters);
          return true;
        });
  }

  private boolean stageNext(JobContext context, UUID id, TabularImportTarget target) {
    imports.requireWrite(context.scope(), target);
    jobs.requireOwnership(context);
    var header = imports.load(id, false);
    if (!header.status().equals("DRAFT") || !context.id().equals(header.validationJobId())) {
      throw new BusinessException("IMPORT_CANCELLED", 409, "Проверка отменена");
    }
    if (header.errorRows() != 0) {
      return true;
    }
    target.begin(context.scope(), header.validationId());
    List<TabularImportTarget.InputRow> rows =
        jdbc.sql(
                """
                SELECT values::text FROM app_import_row WHERE import_id=:id AND row_number>:after AND valid
                ORDER BY row_number LIMIT 500
                """)
            .param("id", id)
            .param("after", header.stagedThrough())
            .query(
                (row, index) -> json.decode(row.getString(1), TabularImportTarget.InputRow.class))
            .list();
    if (rows.isEmpty()) {
      return true;
    }
    target.stage(context.scope(), header.validationId(), rows);
    jdbc.sql("UPDATE app_import SET staged_through=:row WHERE id=:id AND state='DRAFT'")
        .param("row", rows.getLast().number())
        .param("id", id)
        .update();
    return false;
  }

  private record ValidationResult(UUID importId, String status) {}
}
