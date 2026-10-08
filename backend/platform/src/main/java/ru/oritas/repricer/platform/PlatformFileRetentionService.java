package ru.oritas.repricer.platform;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Physical object deletion follows an owner release, an age gate and a durable 24-hour mark. */
@Service
public final class PlatformFileRetentionService implements JobHandler {
  private static final Logger log = LoggerFactory.getLogger(PlatformFileRetentionService.class);
  private static final Set<String> AUTHORITY = Set.of("platform.file.manage");
  private final JdbcClient jdbc;
  private final ScopeExecutor scopes;
  private final ObjectStorage storage;
  private final ObjectProvider<JobRuntime> runtime;
  private final List<FileRetentionOwner> owners;
  private final Clock clock;
  private final boolean worker;

  public PlatformFileRetentionService(
      JdbcClient jdbc,
      ScopeExecutor scopes,
      ObjectStorage storage,
      ObjectProvider<JobRuntime> runtime,
      List<FileRetentionOwner> owners,
      Clock clock,
      @Value("${repricer.mode:api}") String mode) {
    this.jdbc = jdbc;
    this.scopes = scopes;
    this.storage = storage;
    this.runtime = runtime;
    this.owners = List.copyOf(owners);
    this.clock = clock;
    this.worker = mode.equals("worker");
  }

  @Override
  public String type() {
    return "FILE_RETENTION";
  }

  @Scheduled(fixedDelay = 60000)
  public void schedule() {
    if (!worker) {
      return;
    }
    try {
      List<Scope> candidates =
          jdbc.sql("SELECT * FROM repricer_file_retention_scopes()")
              .query(
                  (row, index) ->
                      new Scope(
                          row.getObject(1, UUID.class),
                          row.getObject(2, UUID.class),
                          row.getObject(3, UUID.class)))
              .list();
      String hour = clock.instant().truncatedTo(ChronoUnit.HOURS).toString();
      for (Scope scope : candidates) {
        scopes.execute(
            scope, AUTHORITY, () -> runtime.getObject().submit(scope, type(), hour, "{}"));
      }
    } catch (RuntimeException failure) {
      log.warn("File retention scheduling failed: {}", failure.getClass().getSimpleName());
    }
  }

  @Override
  public JobOutcome execute(JobContext context) {
    for (FileRetentionOwner owner : owners) {
      scopes.execute(
          context.scope(),
          owner.permissions(),
          () -> {
            runtime.getObject().requireOwnership(context);
            int released = owner.releaseEligible(context.scope(), 50);
            runtime.getObject().requireOwnership(context);
            return released;
          });
    }
    scopes.execute(
        context.scope(),
        AUTHORITY,
        () -> {
          runtime.getObject().requireOwnership(context);
          markEligible(context.scope());
          jdbc.sql(
                  """
                  DELETE FROM platform_file_read_pin WHERE id IN (
                    SELECT id FROM platform_file_read_pin WHERE lease_until<clock_timestamp()
                    ORDER BY lease_until,id LIMIT 500)
                  """)
              .update();
          return true;
        });
    List<UUID> ids =
        scopes.execute(
            context.scope(),
            AUTHORITY,
            () ->
                jdbc.sql(
                        """
                        SELECT id FROM platform_file WHERE state='DELETING' AND deletion_after<=clock_timestamp()
                        ORDER BY deletion_after,id LIMIT 100
                        """)
                    .query(UUID.class)
                    .list());
    for (UUID id : ids) {
      if (!clock.instant().isBefore(context.deadline().minusSeconds(5))) {
        return JobOutcome.waiting("FILE_RETENTION_CONTINUE", clock.instant().plusSeconds(10));
      }
      scopes.execute(
          context.scope(),
          AUTHORITY,
          () -> {
            runtime.getObject().requireOwnership(context);
            return true;
          });
      deleteMarked(context.scope(), id);
    }
    return JobOutcome.succeeded("{}");
  }

  /** Called inside the scoped owner transaction; locks are shared with retain/open/release. */
  public int markEligible(Scope scope) {
    scope.requireOrganization();
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Retention marking requires a scoped transaction");
    }
    List<UUID> ids =
        jdbc.sql(
                """
                SELECT f.id FROM platform_file f
                WHERE ((f.state IN ('STORED','READY') AND f.expires_at<=clock_timestamp())
                  OR (f.state IN ('UPLOADING','FAILED') AND NOT f.eof_confirmed
                    AND f.created_at<clock_timestamp()-interval '24 hours'
                    AND COALESCE(f.write_deadline,f.created_at+interval '30 minutes')
                      <clock_timestamp()-interval '120 seconds'))
                  AND NOT EXISTS (SELECT 1 FROM platform_file_reference r WHERE r.file_id=f.id)
                  AND NOT EXISTS (SELECT 1 FROM platform_file_read_pin p
                    WHERE p.file_id=f.id AND p.lease_until>clock_timestamp())
                ORDER BY f.created_at,f.id LIMIT 500 FOR UPDATE OF f SKIP LOCKED
                """)
            .query(UUID.class)
            .list();
    if (ids.isEmpty()) {
      return 0;
    }
    // This second statement observes references committed while the first acquired its locks.
    return jdbc.sql(
            """
            UPDATE platform_file f SET state='DELETING',deletion_after=clock_timestamp()+interval '24 hours'
            WHERE f.id IN (:ids) AND f.state IN ('STORED','READY','UPLOADING','FAILED')
              AND NOT EXISTS (SELECT 1 FROM platform_file_reference r WHERE r.file_id=f.id)
              AND NOT EXISTS (SELECT 1 FROM platform_file_read_pin p
                WHERE p.file_id=f.id AND p.lease_until>clock_timestamp())
            """)
        .param("ids", ids)
        .update();
  }

  /** Rechecks the durable mark, then deletes only the immutable version outside the transaction. */
  public boolean deleteMarked(Scope scope, UUID id) {
    Pending pending =
        scopes.execute(
            scope,
            AUTHORITY,
            () -> {
              var candidate =
                  jdbc.sql(
                          """
                          SELECT object_key,object_version,upload_id FROM platform_file
                          WHERE id=:id AND state='DELETING' AND deletion_after<=clock_timestamp() FOR UPDATE
                          """)
                      .param("id", id)
                      .query(
                          (row, index) ->
                              new Pending(row.getString(1), row.getString(2), row.getString(3)))
                      .optional();
              if (candidate.isEmpty()) {
                return null;
              }
              boolean used =
                  jdbc.sql(
                          """
                          SELECT EXISTS(SELECT 1 FROM platform_file_reference WHERE file_id=:id)
                            OR EXISTS(SELECT 1 FROM platform_file_read_pin WHERE file_id=:id
                              AND lease_until>clock_timestamp())
                          """)
                      .param("id", id)
                      .query(Boolean.class)
                      .single();
              return used ? null : candidate.orElseThrow();
            });
    if (pending == null) {
      return false;
    }
    if (pending.uploadId() != null) {
      storage.abort(pending.key(), pending.uploadId());
    }
    if (pending.version() != null) {
      storage.delete(pending.key(), pending.version());
    }
    return scopes.execute(
        scope,
        AUTHORITY,
        () -> {
          int changed =
              jdbc.sql(
                      """
                      UPDATE platform_file SET state='DELETED' WHERE id=:id AND state='DELETING'
                        AND deletion_after<=clock_timestamp()
                      """)
                  .param("id", id)
                  .update();
          jdbc.sql(
                  """
                  DELETE FROM platform_file_part WHERE file_id=:id AND part_number IN (
                    SELECT part_number FROM platform_file_part WHERE file_id=:id ORDER BY part_number LIMIT 500)
                  """)
              .param("id", id)
              .update();
          return changed == 1;
        });
  }

  private record Pending(String key, String version, String uploadId) {}
}
