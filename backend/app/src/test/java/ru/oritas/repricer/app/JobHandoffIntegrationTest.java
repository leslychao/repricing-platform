package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobControlConnections;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.ScopeExecutor;

/** Queue claims, lane capacity, transactions and lease fencing use PostgreSQL and real workers. */
class JobHandoffIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("handoff-test-only");
  private static final Set<String> AUTHORITY = Set.of("platform.job.manage");
  private static final String TYPE = "HANDOFF_TEST";
  private static JdbcClient owner;
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner scopes;

  @BeforeAll
  static void prepare() throws Exception {
    POSTGRES.start();
    owner =
        JdbcClient.create(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    owner
        .sql("CREATE ROLE repricer_migrator LOGIN BYPASSRLS PASSWORD 'migration-test-only'")
        .update();
    owner.sql("CREATE ROLE repricer_api LOGIN NOBYPASSRLS PASSWORD 'api-test-only'").update();
    owner.sql("CREATE ROLE repricer_worker LOGIN NOBYPASSRLS PASSWORD 'worker-test-only'").update();
    owner.sql("GRANT ALL ON SCHEMA public TO repricer_migrator").update();
    var database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(
                new JdbcConnection(
                    DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), "repricer_migrator", "migration-test-only")));
    try (var liquibase =
        new Liquibase("db/changelog/master.xml", new ClassLoaderResourceAccessor(), database)) {
      liquibase.update(new liquibase.Contexts());
    }
    var source =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_worker", "worker-test-only");
    jdbc = JdbcClient.create(source);
    scopes =
        new ScopeTransactionRunner(
            jdbc, new DataSourceTransactionManager(source), new AuthorizationService(jdbc));
    assertEquals("repricer_worker", jdbc.sql("SELECT current_user").query(String.class).single());
    assertFalse(
        jdbc.sql("SELECT rolbypassrls FROM pg_roles WHERE rolname=current_user")
            .query(Boolean.class)
            .single());
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void handoffPreservesOneJobAndCheckpointAndHonorsDueTimeAndGlobalCanonicalCapacity()
      throws Exception {
    Scope scope = scope();
    Instant due = Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.MICROS);
    BlockingQueue<Invocation> fetched = new LinkedBlockingQueue<>();
    BlockingQueue<Invocation> canonical = new LinkedBlockingQueue<>();
    Semaphore release = new Semaphore(0);
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    Action action =
        (runtime, context) -> {
          checkpoint(runtime, context);
          if (context.lane().equals("fetch")) {
            fetched.add(new Invocation(runtime, context));
            return JobOutcome.handoff("canonicalization", due);
          }
          int concurrent = running.incrementAndGet();
          maximum.accumulateAndGet(concurrent, Math::max);
          canonical.add(new Invocation(runtime, context));
          try {
            if (!release.tryAcquire(20, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Canonical work was not released");
            }
            return JobOutcome.succeeded("{\"published\":true}");
          } finally {
            running.decrementAndGet();
          }
        };
    try (Worker first = worker(action);
        Worker second = worker(action)) {
      List<UUID> ids = new ArrayList<>();
      for (int index = 0; index < 3; index++) {
        String payload = "{\"source\":\"immutable-" + index + "\"}";
        ids.add(
            scopes.runService(
                scope,
                AUTHORITY,
                () -> first.runtime().submit(scope, TYPE, UUID.randomUUID().toString(), payload)));
      }
      assertEquals(0L, jdbc.sql("SELECT count(*) FROM platform_job").query(Long.class).single());
      first.runtime().claim();
      List<Invocation> fetches = List.of(take(fetched), take(fetched), take(fetched));
      await(() -> states(scope).stream().allMatch(state -> state.state().equals("WAITING")));
      assertTrue(Instant.now().isBefore(due), "Fixture must reach the not-yet-due boundary");
      for (JobState state : states(scope)) {
        assertEquals("canonicalization", state.lane());
        assertEquals(due, state.due());
        assertEquals(0, state.attempt());
        assertEquals(1, state.fence());
        assertEquals("42", state.checkpoint());
      }
      first.runtime().claim();
      second.runtime().claim();
      assertTrue(canonical.isEmpty(), "Future handoff must not be claimed early");
      assertTrue(states(scope).stream().allMatch(state -> state.state().equals("WAITING")));
      await(() -> !Instant.now().isBefore(due));
      first.runtime().claim();
      Invocation firstCanonical = take(canonical);
      Invocation secondCanonical = take(canonical);
      second.runtime().claim();
      assertTrue(canonical.isEmpty(), "A second worker must not create a third canonical slot");
      assertEquals(
          2, states(scope).stream().filter(state -> state.state().equals("RUNNING")).count());
      for (Invocation invocation : List.of(firstCanonical, secondCanonical)) {
        Invocation fetchedInput =
            fetches.stream()
                .filter(value -> value.context().id().equals(invocation.context().id()))
                .findFirst()
                .orElseThrow();
        assertEquals(fetchedInput.context().payload(), invocation.context().payload());
        assertTrue(invocation.context().fence() > fetchedInput.context().fence());
        assertEquals(0, invocation.context().attempt());
        assertLost(fetchedInput);
      }
      release.release();
      await(
          () ->
              states(scope).stream().filter(state -> state.state().equals("SUCCEEDED")).count()
                  == 1);
      second.runtime().claim();
      Invocation thirdCanonical = take(canonical);
      assertFalse(
          Set.of(firstCanonical.context().id(), secondCanonical.context().id())
              .contains(thirdCanonical.context().id()));
      assertTrue(ids.contains(thirdCanonical.context().id()));
      assertEquals(
          2, states(scope).stream().filter(state -> state.state().equals("RUNNING")).count());
      release.release(2);
      await(() -> states(scope).stream().allMatch(state -> state.state().equals("SUCCEEDED")));
      assertEquals(2, maximum.get());
      assertEquals(3, states(scope).size());
      for (JobState state : states(scope)) {
        assertEquals(0, state.attempt(), "Lane handoffs are not failed attempts");
        assertEquals(2, state.fence());
        assertEquals("42", state.checkpoint());
      }
    } finally {
      release.release(3);
    }
  }

  @Test
  void expiredFetchCannotOverwriteReclaimedCanonicalLaneOrCheckpointWithItsLateHandoff()
      throws Exception {
    Scope scope = scope();
    BlockingQueue<Invocation> oldFetch = new LinkedBlockingQueue<>();
    BlockingQueue<Invocation> replacements = new LinkedBlockingQueue<>();
    CountDownLatch returnOld = new CountDownLatch(1);
    CountDownLatch finishCanonical = new CountDownLatch(1);
    CountDownLatch lateFinishCommitted = new CountDownLatch(1);
    Action oldAction =
        (runtime, context) -> {
          checkpoint(runtime, context);
          oldFetch.add(new Invocation(runtime, context));
          if (!returnOld.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Old worker was not released");
          }
          return JobOutcome.handoff("fetch", Instant.now().plusSeconds(3600));
        };
    Action newAction =
        (runtime, context) -> {
          checkpoint(runtime, context);
          replacements.add(new Invocation(runtime, context));
          if (context.lane().equals("fetch")) {
            return JobOutcome.handoff("canonicalization", Instant.now().plusSeconds(1));
          }
          if (!finishCanonical.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Replacement worker was not released");
          }
          return JobOutcome.succeeded("{\"published\":true}");
        };
    try (Worker first = worker(oldAction);
        Worker second = worker(newAction)) {
      UUID id =
          scopes.runService(
              scope,
              AUTHORITY,
              () ->
                  first
                      .runtime()
                      .submit(
                          scope, TYPE, UUID.randomUUID().toString(), "{\"source\":\"retained\"}"));
      first.runtime().claim();
      Invocation original = take(oldFetch);
      // Expire the real database lease to model process loss without waiting for its 60-second TTL.
      owner
          .sql(
              "UPDATE platform_job SET lease_until=clock_timestamp()-interval '1 second' WHERE"
                  + " id=:id")
          .param("id", id)
          .update();
      assertLost(original);
      second.runtime().claim();
      Invocation replacementFetch = take(replacements);
      await(() -> states(scope).getFirst().state().equals("WAITING"));
      assertTrue(replacementFetch.context().fence() > original.context().fence());
      await(() -> !Instant.now().isBefore(states(scope).getFirst().due()));
      second.runtime().claim();
      Invocation replacementCanonical = take(replacements);
      assertEquals("canonicalization", replacementCanonical.context().lane());
      assertEquals(id, replacementCanonical.context().id());
      assertEquals(original.context().payload(), replacementCanonical.context().payload());
      assertTrue(replacementCanonical.context().fence() > replacementFetch.context().fence());
      assertEquals(
          1, replacementCanonical.context().attempt(), "Only the lost lease consumes an attempt");
      assertLost(original);
      assertLost(replacementFetch);
      var wrongLane =
          new JobContext(
              id,
              scope,
              replacementCanonical.context().payload(),
              replacementCanonical.context().fence(),
              replacementCanonical.context().deadline(),
              replacementCanonical.context().attempt(),
              "fetch");
      assertLost(new Invocation(second.runtime(), wrongLane));
      JobState held = states(scope).getFirst();
      first.finishObserver().set(lateFinishCommitted);
      returnOld.countDown();
      assertTrue(
          lateFinishCommitted.await(5, TimeUnit.SECONDS), "Late finish transaction did not finish");
      assertEquals(held, states(scope).getFirst(), "The stale handoff must update no job fields");
      scopes.runService(
          scope,
          AUTHORITY,
          () -> {
            second.runtime().requireOwnership(replacementCanonical.context());
            return true;
          });
      finishCanonical.countDown();
      await(() -> states(scope).getFirst().state().equals("SUCCEEDED"));
      assertEquals("canonicalization", states(scope).getFirst().lane());
      assertEquals(1, states(scope).getFirst().attempt());
      assertEquals(1, states(scope).size());
    } finally {
      returnOld.countDown();
      finishCanonical.countDown();
    }
  }

  @Test
  void olderFinallyCannotRemoveTheNewLeaseOrItsHeartbeatAfterSameWorkerReclaim() throws Exception {
    Scope scope = scope();
    BlockingQueue<Invocation> phases = new LinkedBlockingQueue<>();
    CountDownLatch finishFetch = new CountDownLatch(1);
    CountDownLatch finishCanonical = new CountDownLatch(1);
    CommitGate gate = new CommitGate(new CountDownLatch(1), new CountDownLatch(1));
    AtomicReference<Thread> fetchThread = new AtomicReference<>();
    Action action =
        (runtime, context) -> {
          checkpoint(runtime, context);
          phases.add(new Invocation(runtime, context));
          if (context.lane().equals("fetch")) {
            fetchThread.set(Thread.currentThread());
            if (!finishFetch.await(20, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Fetch was not released");
            }
            return JobOutcome.handoff("canonicalization", Instant.now());
          }
          if (!finishCanonical.await(25, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Canonical work was not released");
          }
          return JobOutcome.succeeded("{}");
        };
    try (Worker worker = worker(action)) {
      UUID id =
          scopes.runService(
              scope,
              AUTHORITY,
              () -> worker.runtime().submit(scope, TYPE, UUID.randomUUID().toString(), "{}"));
      worker.runtime().claim();
      Invocation original = take(phases);
      worker.commitGate().set(gate);
      finishFetch.countDown();
      assertTrue(gate.entered().await(5, TimeUnit.SECONDS), "Fetch finish did not commit");
      assertEquals("WAITING", states(scope).getFirst().state());
      worker.runtime().claim();
      Invocation current = take(phases);
      assertEquals("canonicalization", current.context().lane());
      assertTrue(current.context().fence() > original.context().fence());
      gate.release().countDown();
      // No more fetch tasks exist: reaching getTask proves execute.finally has actually returned.
      await(
          () ->
              Arrays.stream(fetchThread.get().getStackTrace())
                  .anyMatch(
                      frame ->
                          frame.getClassName().equals("java.util.concurrent.ThreadPoolExecutor")
                              && frame.getMethodName().equals("getTask")));
      owner
          .sql(
              "UPDATE platform_job SET lease_until=clock_timestamp()+interval '15 seconds' WHERE"
                  + " id=:id")
          .param("id", id)
          .update();
      await(
          Duration.ofSeconds(12),
          () ->
              owner
                  .sql(
                      """
                      SELECT lease_until>clock_timestamp()+interval '40 seconds' FROM platform_job
                      WHERE id=:id AND state='RUNNING' AND lane='canonicalization'
                      """)
                  .param("id", id)
                  .query(Boolean.class)
                  .single());
      scopes.runService(
          scope,
          AUTHORITY,
          () -> {
            worker.runtime().requireOwnership(current.context());
            return true;
          });
      assertLost(original);
      finishCanonical.countDown();
      await(() -> states(scope).getFirst().state().equals("SUCCEEDED"));
      assertEquals(2, states(scope).getFirst().fence());
      assertEquals(0, states(scope).getFirst().attempt());
    } finally {
      finishFetch.countDown();
      finishCanonical.countDown();
      gate.release().countDown();
    }
  }

  private static void checkpoint(JobRuntime runtime, JobContext context) {
    scopes.runService(
        context.scope(),
        AUTHORITY,
        () -> {
          runtime.requireOwnership(context);
          if (context.lane().equals("fetch")) {
            jdbc.sql("UPDATE platform_job SET checkpoint='{\"offset\":42}'::jsonb WHERE id=:id")
                .param("id", context.id())
                .update();
          } else {
            assertEquals(
                "42",
                jdbc.sql("SELECT checkpoint->>'offset' FROM platform_job WHERE id=:id")
                    .param("id", context.id())
                    .query(String.class)
                    .single());
          }
          return true;
        });
  }

  private static Scope scope() {
    UUID organization = UUID.randomUUID();
    UUID subject = UUID.randomUUID();
    owner
        .sql("INSERT INTO access_organization(id,name) VALUES (:id,'Handoff test')")
        .param("id", organization)
        .update();
    owner
        .sql(
            "INSERT INTO access_user(id,issuer,subject,display_name,email) VALUES"
                + " (:id,'https://handoff.invalid',:subject,'Test','test@example.invalid')")
        .param("id", subject)
        .param("subject", subject.toString())
        .update();
    return new Scope(organization, null, subject);
  }

  private static List<JobState> states(Scope scope) {
    return scopes.runService(
        scope,
        AUTHORITY,
        () ->
            jdbc.sql(
                    """
                    SELECT lane,state,attempt,fence,due_at,payload::text,checkpoint->>'offset' FROM platform_job
                    WHERE organization_id=:organization ORDER BY id
                    """)
                .param("organization", scope.organizationId())
                .query(
                    (row, index) ->
                        new JobState(
                            row.getString(1),
                            row.getString(2),
                            row.getInt(3),
                            row.getLong(4),
                            row.getTimestamp(5).toInstant(),
                            row.getString(6),
                            row.getString(7)))
                .list());
  }

  private static Invocation take(BlockingQueue<Invocation> queue) throws InterruptedException {
    Invocation invocation = queue.poll(5, TimeUnit.SECONDS);
    assertNotNull(invocation, "Worker did not enter the expected phase");
    return invocation;
  }

  private static void assertLost(Invocation invocation) {
    assertEquals(
        "LEASE_EXPIRED",
        assertThrows(
                BusinessException.class,
                () ->
                    scopes.runService(
                        invocation.context().scope(),
                        AUTHORITY,
                        () -> {
                          invocation.runtime().requireOwnership(invocation.context());
                          return true;
                        }))
            .code());
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    await(Duration.ofSeconds(8), condition);
  }

  private static void await(Duration timeout, BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      assertTrue(System.nanoTime() < deadline, "Expected durable job state was not reached");
      Thread.sleep(20);
    }
  }

  private static Worker worker(Action action) {
    var beans = new DefaultListableBeanFactory();
    var reference = new AtomicReference<JobRuntime>();
    var observer = new AtomicReference<CountDownLatch>();
    var commitGate = new AtomicReference<CommitGate>();
    beans.registerSingleton(
        "handler",
        new JobHandler() {
          @Override
          public String type() {
            return TYPE;
          }

          @Override
          public String lane() {
            return "fetch";
          }

          @Override
          public JobOutcome execute(JobContext context) throws Exception {
            return action.execute(reference.get(), context);
          }
        });
    beans.registerSingleton("outbox", mock(OutboxService.class));
    var control =
        new JobControlConnections(POSTGRES.getJdbcUrl(), "repricer_worker", "worker-test-only");
    beans.registerSingleton("control", control);
    // Observe a committed late finish without replacing its transaction, SQL or ownership checks.
    ScopeExecutor observedScopes =
        new ScopeExecutor() {
          @Override
          public <T> T execute(Scope scope, Set<String> permissions, Supplier<T> operation) {
            T result = scopes.execute(scope, permissions, operation);
            CountDownLatch complete = observer.getAndSet(null);
            if (complete != null) {
              complete.countDown();
            }
            CommitGate gate = commitGate.getAndSet(null);
            if (gate != null) {
              gate.entered().countDown();
              try {
                if (!gate.release().await(15, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Committed finish was not released");
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Committed finish was interrupted", interrupted);
              }
            }
            return result;
          }
        };
    var runtime =
        new JobRuntime(
            jdbc,
            observedScopes,
            Clock.systemUTC(),
            beans.getBeanProvider(JobHandler.class),
            beans.getBeanProvider(OutboxService.class),
            beans.getBeanProvider(JobControlConnections.class),
            "worker");
    reference.set(runtime);
    return new Worker(runtime, control, observer, commitGate);
  }

  @FunctionalInterface
  private interface Action {
    JobOutcome execute(JobRuntime runtime, JobContext context) throws Exception;
  }

  private record Invocation(JobRuntime runtime, JobContext context) {}

  private record CommitGate(CountDownLatch entered, CountDownLatch release) {}

  private record JobState(
      String lane,
      String state,
      int attempt,
      long fence,
      Instant due,
      String payload,
      String checkpoint) {}

  private record Worker(
      JobRuntime runtime,
      JobControlConnections control,
      AtomicReference<CountDownLatch> finishObserver,
      AtomicReference<CommitGate> commitGate)
      implements AutoCloseable {
    @Override
    public void close() {
      runtime.destroy();
      control.close();
    }
  }
}
