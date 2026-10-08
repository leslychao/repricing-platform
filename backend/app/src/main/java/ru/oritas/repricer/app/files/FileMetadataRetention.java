package ru.oritas.repricer.app.files;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

/** Releases finished consumers; physical S3 deletion remains with the platform file owner. */
@Component
public final class FileMetadataRetention implements JobHandler {
  private static final Logger log = LoggerFactory.getLogger(FileMetadataRetention.class);
  private static final Set<String> AUTHORITY = Set.of("app.files.retention");
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner scopes;
  private final StoredFileService files;
  private final ImportService imports;
  private final ObjectProvider<JobRuntime> jobs;
  private final Clock clock;
  private final boolean worker;

  public FileMetadataRetention(
      JdbcClient jdbc,
      ScopeTransactionRunner scopes,
      StoredFileService files,
      ImportService imports,
      ObjectProvider<JobRuntime> jobs,
      Clock clock,
      @Value("${repricer.mode:api}") String mode) {
    this.jdbc = jdbc;
    this.scopes = scopes;
    this.files = files;
    this.imports = imports;
    this.jobs = jobs;
    this.clock = clock;
    this.worker = mode.equals("worker");
  }

  @Override
  public String type() {
    return "FILE_METADATA_RETENTION";
  }

  @Scheduled(fixedDelay = 60000)
  public void schedule() {
    if (!worker) {
      return;
    }
    try {
      var candidates =
          jdbc.sql("SELECT * FROM repricer_file_metadata_retention_scopes()")
              .query(
                  (row, index) ->
                      new Scope(
                          row.getObject(1, UUID.class),
                          row.getObject(2, UUID.class),
                          row.getObject(3, UUID.class)))
              .list();
      String hour = clock.instant().truncatedTo(ChronoUnit.HOURS).toString();
      for (Scope scope : candidates) {
        scopes.runService(
            scope, AUTHORITY, () -> jobs.getObject().submit(scope, type(), hour, "{}"));
      }
    } catch (RuntimeException failure) {
      log.warn("File metadata retention scheduling failed: {}", failure.getClass().getSimpleName());
    }
  }

  @Override
  public JobOutcome execute(JobContext context) {
    int changed =
        scopes.runService(
            context.scope(),
            AUTHORITY,
            () -> {
              jobs.getObject().requireOwnership(context);
              return releaseReports(context.scope())
                  + discardSelections()
                  + imports.cleanupTemporary(context.scope());
            });
    return changed == 0
        ? JobOutcome.succeeded("{}")
        : JobOutcome.waiting("FILE_METADATA_RETENTION_CONTINUE", clock.instant().plusSeconds(1));
  }

  private int releaseReports(Scope scope) {
    record Expired(UUID id, UUID fileId) {}
    List<Expired> expired =
        jdbc.sql(
                """
                SELECT r.id,r.file_id FROM app_report r
                WHERE r.state='READY' AND r.expires_at<=clock_timestamp()
                  AND EXISTS(SELECT 1 FROM platform_file_reference f WHERE f.file_id=r.file_id
                    AND f.owner_type='REPORT' AND f.owner_id=r.id)
                ORDER BY r.expires_at,r.id LIMIT 100 FOR UPDATE OF r SKIP LOCKED
                """)
            .query(
                (row, index) ->
                    new Expired(row.getObject(1, UUID.class), row.getObject(2, UUID.class)))
            .list();
    for (Expired report : expired) {
      files.release(scope, report.fileId(), "REPORT", report.id());
    }
    return expired.size();
  }

  private int discardSelections() {
    int marked =
        jdbc.sql(
                """
                WITH candidates AS MATERIALIZED (
                  SELECT s.id FROM app_selection s WHERE s.expires_at<=clock_timestamp()
                    AND s.rows_deletion_after IS NULL
                    AND NOT EXISTS(SELECT 1 FROM app_selection_reference r WHERE r.selection_id=s.id)
                  ORDER BY s.expires_at,s.id LIMIT 500 FOR UPDATE OF s SKIP LOCKED)
                UPDATE app_selection s SET rows_deletion_after=clock_timestamp()+interval '24 hours'
                FROM candidates c WHERE s.id=c.id
                  AND NOT EXISTS(SELECT 1 FROM app_selection_reference r WHERE r.selection_id=s.id)
                """)
            .update();
    var candidate =
        jdbc.sql(
                """
                SELECT s.id FROM app_selection s WHERE s.rows_deletion_after<=clock_timestamp()
                  AND NOT EXISTS(SELECT 1 FROM app_selection_reference r WHERE r.selection_id=s.id)
                  AND EXISTS(SELECT 1 FROM app_selection_row r WHERE r.selection_id=s.id)
                ORDER BY s.rows_deletion_after,s.id LIMIT 1 FOR UPDATE OF s SKIP LOCKED
                """)
            .query(UUID.class)
            .optional();
    if (candidate.isEmpty()) {
      return marked;
    }
    return marked
        + jdbc.sql(
                """
                DELETE FROM app_selection_row WHERE selection_id=:id AND ordinal IN (
                  SELECT ordinal FROM app_selection_row WHERE selection_id=:id ORDER BY ordinal LIMIT 500)
                """)
            .param("id", candidate.orElseThrow())
            .update();
  }
}
