package ru.oritas.repricer.platform;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One durable queue. Claiming global metadata cannot read cross-tenant payloads. */
@Component
public final class JobRuntime implements DisposableBean {
  private static final Logger log = LoggerFactory.getLogger(JobRuntime.class);
  private static final Map<String, Integer> LANES =
      Map.of("fetch", 4, "canonicalization", 2, "calculation", 2, "execution", 2, "service", 1);
  private final JdbcClient jdbc;
  private final ScopeExecutor scopes;
  private final Clock clock;
  private final UUID workerId = UUID.randomUUID();
  private final ObjectProvider<JobHandler> handlerProvider;
  private final ObjectProvider<OutboxService> outboxProvider;
  private final ObjectProvider<JobControlConnections> controlConnections;
  private final boolean worker;
  private final Map<String, ScheduledExecutorService> executors = new ConcurrentHashMap<>();
  private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
  private final Map<UUID, Lease> active = new ConcurrentHashMap<>();
  private final Map<Lease, Instant> attemptDeadlines = new ConcurrentHashMap<>();
  private final AtomicBoolean claiming = new AtomicBoolean();

  public JobRuntime(
      JdbcClient jdbc,
      ScopeExecutor scopes,
      Clock clock,
      ObjectProvider<JobHandler> handlers,
      ObjectProvider<OutboxService> outboxProvider,
      ObjectProvider<JobControlConnections> controlConnections,
      @Value("${repricer.mode:api}") String mode) {
    this.jdbc = jdbc;
    this.scopes = scopes;
    this.clock = clock;
    this.handlerProvider = handlers;
    this.outboxProvider = outboxProvider;
    this.controlConnections = controlConnections;
    this.worker = mode.equals("worker");
    if (worker) {
      LANES.forEach((lane, count) -> executors.put(lane, Executors.newScheduledThreadPool(count)));
      heartbeat.scheduleWithFixedDelay(this::extendLeases, 10, 10, TimeUnit.SECONDS);
    }
  }

  /** Must be invoked in the owner's business transaction. */
  public UUID submit(Scope scope, String type, String businessKey, String payload) {
    return schedule(scope, type, businessKey, payload, clock.instant());
  }

  /** Internal recovery metadata; transport controllers must never expose a job payload. */
  public Optional<FailedJob> failure(Scope scope, UUID id) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Job state must be read in its owner's transaction");
    }
    return jdbc.sql(
            """
            SELECT job_type,payload::text,state,updated_at FROM platform_job
            WHERE id=:id AND organization_id=:org AND account_id IS NOT DISTINCT FROM :account
              AND state IN ('BLOCKED','DEAD')
            """)
        .param("id", id)
        .param("org", scope.requireOrganization())
        .param("account", scope.accountId())
        .query(
            (row, index) ->
                new FailedJob(
                    row.getString(1),
                    row.getString(2),
                    row.getString(3),
                    row.getTimestamp(4).toInstant()))
        .optional();
  }

  public record FailedJob(String type, String payload, String state, Instant updatedAt) {}

  /** Reads terminality in the caller's current authorized business transaction. */
  public boolean terminal(Scope scope, UUID id) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Job state must be read in its owner's transaction");
    }
    return jdbc.sql(
            """
            SELECT state IN ('SUCCEEDED','DEAD','BLOCKED','CANCELLED') FROM platform_job
            WHERE id=:id AND organization_id=:org AND account_id IS NOT DISTINCT FROM :account
            """)
        .param("id", id)
        .param("org", scope.requireOrganization())
        .param("account", scope.accountId())
        .query(Boolean.class)
        .optional()
        .orElseThrow(
            () -> new BusinessException("OPERATION_NOT_FOUND", 404, "Операция недоступна"));
  }

  /** Reads a bounded set of job states under the caller's authorized scope. */
  public Map<UUID, String> statuses(Scope scope, Set<UUID> ids) {
    if (!TransactionSynchronizationManager.isActualTransactionActive() || ids.size() > 200) {
      throw new IllegalArgumentException("Job states require a transaction and at most 200 ids");
    }
    if (ids.isEmpty()) {
      return Map.of();
    }
    return jdbc
        .sql(
            """
            SELECT id,state FROM platform_job WHERE organization_id=:org
              AND account_id IS NOT DISTINCT FROM :account AND id IN (:ids)
            """)
        .param("org", scope.requireOrganization())
        .param("account", scope.accountId())
        .param("ids", ids)
        .query((row, index) -> Map.entry(row.getObject(1, UUID.class), row.getString(2)))
        .list()
        .stream()
        .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public UUID schedule(
      Scope scope, String type, String businessKey, String payload, Instant dueAt) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("A job must be saved in its owner's transaction");
    }
    JobHandler handler = handlers().get(type);
    if (handler == null) {
      throw new IllegalArgumentException("Unknown job type: " + type);
    }
    UUID id = UUID.randomUUID();
    return jdbc.sql(
            """
            INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,
              business_key,payload,state,due_at)
            VALUES (:id,:org,:account,:subject,:type,:lane,:key,CAST(:payload AS jsonb),'READY',:due)
            ON CONFLICT(scope_key,job_type,business_key) DO UPDATE
              SET business_key=EXCLUDED.business_key
              WHERE platform_job.deletion_after IS NULL
            RETURNING id
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("type", type)
        .param("lane", handler.lane())
        .param("key", businessKey)
        .param("payload", payload)
        .param("due", Timestamp.from(dueAt))
        .query(UUID.class)
        .optional()
        .orElseThrow(
            () -> new BusinessException("OPERATION_EXPIRED", 410, "Срок хранения задания истёк"));
  }

  @Scheduled(fixedDelay = 1000)
  public void claim() {
    if (!worker || !claiming.compareAndSet(false, true)) {
      return;
    }
    try {
      for (var lane : LANES.entrySet()) {
        long running =
            active.values().stream().filter(lease -> lease.lane().equals(lane.getKey())).count();
        for (long slot = running; slot < lane.getValue(); slot++) {
          var candidate =
              controlConnections
                  .getObject()
                  .jdbc()
                  .sql("SELECT * FROM repricer_claim_job(:worker,:lane,:slots)")
                  .param("worker", workerId)
                  .param("lane", lane.getKey())
                  .param("slots", lane.getValue())
                  .query(
                      (row, index) ->
                          new Lease(
                              row.getObject("id", UUID.class),
                              new Scope(
                                  row.getObject("organization_id", UUID.class),
                                  row.getObject("account_id", UUID.class),
                                  row.getObject("subject_id", UUID.class)),
                              row.getString("job_type"),
                              lane.getKey(),
                              row.getLong("fence")))
                  .optional();
          if (candidate.isEmpty()) {
            break;
          }
          Lease lease = candidate.orElseThrow();
          active.put(lease.id(), lease);
          executors.get(lane.getKey()).execute(() -> execute(lease));
        }
      }
    } catch (RuntimeException exception) {
      log.warn("Job claim failed: {}", exception.getClass().getSimpleName());
    } finally {
      claiming.set(false);
    }
  }

  private void execute(Lease lease) {
    JobHandler handler = handlers().get(lease.type());
    Set<String> permissions = Set.of("platform.job.manage");
    Duration maximum = handler == null ? Duration.ofSeconds(120) : handler.maximumAttemptDuration();
    if (maximum.isNegative() || maximum.isZero() || maximum.compareTo(Duration.ofMinutes(30)) > 0) {
      active.remove(lease.id(), lease);
      throw new IllegalStateException("Invalid job attempt duration");
    }
    Instant deadline = clock.instant().plus(maximum);
    attemptDeadlines.put(lease, deadline);
    Thread executingThread = Thread.currentThread();
    var cancellation =
        heartbeat.schedule(executingThread::interrupt, maximum.toMillis(), TimeUnit.MILLISECONDS);
    try {
      JobContext context =
          scopes.execute(
              lease.scope(),
              permissions,
              () ->
                  jdbc.sql(
                          """
                          SELECT payload::text,attempt FROM platform_job WHERE id=:id AND fence=:fence
                            AND lease_owner=:owner AND state='RUNNING'
                          """)
                      .param("id", lease.id())
                      .param("fence", lease.fence())
                      .param("owner", workerId)
                      .query(
                          (row, index) ->
                              new JobContext(
                                  lease.id(),
                                  lease.scope(),
                                  row.getString(1),
                                  lease.fence(),
                                  deadline,
                                  row.getInt(2),
                                  lease.lane()))
                      .single());
      if (context.attempt() >= 7) {
        finish(
            lease,
            permissions,
            new JobOutcome("DEAD", null, "ATTEMPTS_EXHAUSTED", null, null),
            false);
        return;
      }
      JobOutcome outcome =
          handler == null ? JobOutcome.blocked("UNKNOWN_HANDLER") : handler.execute(context);
      finish(lease, permissions, outcome, false);
    } catch (BusinessException exception) {
      finish(lease, permissions, JobOutcome.blocked(exception.code()), false);
    } catch (InterruptedException exception) {
      try {
        finish(
            lease,
            permissions,
            JobOutcome.waiting("ATTEMPT_INTERRUPTED", clock.instant().plusSeconds(10)),
            true);
      } finally {
        Thread.currentThread().interrupt();
      }
    } catch (Exception exception) {
      log.warn("Job {} failed: {}", lease.id(), exception.getClass().getSimpleName());
      int delay = ThreadLocalRandom.current().nextInt(5, 16);
      finish(
          lease,
          permissions,
          JobOutcome.waiting("TEMPORARY_FAILURE", clock.instant().plusSeconds(delay)),
          true);
    } finally {
      cancellation.cancel(false);
      Thread.interrupted();
      active.remove(lease.id(), lease);
      attemptDeadlines.remove(lease);
    }
  }

  private void finish(Lease lease, Set<String> permissions, JobOutcome outcome, boolean retry) {
    if (outcome.nextLane() != null && !LANES.containsKey(outcome.nextLane())) {
      throw new IllegalArgumentException("Unknown target job lane");
    }
    scopes.execute(
        lease.scope(),
        permissions,
        () -> {
          int changed =
              jdbc.sql(
                      """
                      UPDATE platform_job SET
                        state=CASE WHEN :retry AND (attempt>=6 OR first_failure_at<clock_timestamp()-interval '30 minutes')
                          THEN 'DEAD' ELSE :state END,
                        attempt=attempt+CASE WHEN :retry THEN 1 ELSE 0 END,
                        first_failure_at=CASE WHEN :retry THEN COALESCE(first_failure_at,clock_timestamp())
                          ELSE first_failure_at END,
                        result=CAST(:result AS jsonb),reason=:reason,lane=COALESCE(:nextLane,lane),
                        due_at=CASE WHEN :retry THEN clock_timestamp()
                          +(LEAST(300,5*power(2,attempt))*(0.5+random()))*interval '1 second'
                          ELSE COALESCE(:due,clock_timestamp()) END,lease_owner=NULL,lease_until=NULL,
                        updated_at=clock_timestamp()
                      WHERE id=:id AND fence=:fence AND lease_owner=:owner AND lease_until>clock_timestamp()
                        AND state='RUNNING' AND lane=:heldLane
                      """)
                  .param("retry", retry)
                  .param("state", outcome.state())
                  .param("result", outcome.result())
                  .param("reason", outcome.reason())
                  .param("nextLane", outcome.nextLane())
                  .param("heldLane", lease.lane())
                  .param("due", outcome.dueAt() == null ? null : Timestamp.from(outcome.dueAt()))
                  .param("id", lease.id())
                  .param("fence", lease.fence())
                  .param("owner", workerId)
                  .update();
          if (changed == 1
              && lease.scope().organizationId() != null
              && !lease.type().equals("OUTBOX_DELIVERY")) {
            outboxProvider
                .getObject()
                .emit(
                    lease.scope(),
                    lease.id() + ":" + lease.fence(),
                    "operation.changed",
                    new OutboxService.EntityChange("operations", lease.id(), lease.fence()));
          }
          return changed;
        });
  }

  private void extendLeases() {
    for (Lease lease : active.values()) {
      Instant deadline = attemptDeadlines.get(lease);
      if (deadline == null || !deadline.isAfter(clock.instant())) {
        continue;
      }
      try {
        controlConnections
            .getObject()
            .jdbc()
            .sql("SELECT repricer_renew_job_lease(:id,:fence,:owner)")
            .param("id", lease.id())
            .param("fence", lease.fence())
            .param("owner", workerId)
            .query(Boolean.class)
            .single();
      } catch (RuntimeException exception) {
        log.warn("Lease renewal failed for {}", lease.id());
      }
    }
  }

  @Override
  public void destroy() {
    heartbeat.shutdownNow();
    executors.values().forEach(ScheduledExecutorService::shutdownNow);
  }

  public void requireOwnership(JobContext context) {
    boolean owned =
        jdbc.sql(
                """
                SELECT id FROM platform_job WHERE id=:id AND fence=:fence
                  AND lease_owner=:worker AND lease_until>clock_timestamp() AND state='RUNNING'
                  AND lane=:lane FOR SHARE
                """)
            .param("id", context.id())
            .param("fence", context.fence())
            .param("worker", workerId)
            .param("lane", context.lane())
            .query(UUID.class)
            .optional()
            .isPresent();
    if (!owned || clock.instant().isAfter(context.deadline())) {
      throw new BusinessException("LEASE_EXPIRED", 409, "Задание передано другому обработчику");
    }
  }

  private Map<String, JobHandler> handlers() {
    return handlerProvider.stream()
        .collect(Collectors.toUnmodifiableMap(JobHandler::type, Function.identity()));
  }

  private record Lease(UUID id, Scope scope, String type, String lane, long fence) {}
}
