package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.HistoryQuery;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobControlConnections;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OperationService;
import ru.oritas.repricer.platform.OutboxDeliveryHandler;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

class RuntimeMetadataRetentionIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("metadata-test-only");
  private static final Set<String> AUTHORITY = Set.of("platform.outbox.manage");
  private static JdbcClient jdbc;
  private static JdbcClient owner;
  private static JdbcClient worker;
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
    owner
        .sql(
            """
            CREATE TABLE test_job_reference(organization_id uuid,account_id uuid,job_id uuid PRIMARY KEY,
              FOREIGN KEY(organization_id,account_id,job_id)
              REFERENCES platform_job(organization_id,account_id,id))
            """)
        .update();
    owner.sql("GRANT SELECT ON test_job_reference TO repricer_migrator").update();
    var source =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_api", "api-test-only");
    jdbc = JdbcClient.create(source);
    worker =
        JdbcClient.create(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "repricer_worker", "worker-test-only"));
    scopes =
        new ScopeTransactionRunner(
            jdbc, new DataSourceTransactionManager(source), new AuthorizationService(jdbc));
  }

  @BeforeEach
  void clearTechnicalFixtures() {
    owner.sql("DELETE FROM test_job_reference").update();
    owner.sql("DELETE FROM platform_job").update();
    owner.sql("DELETE FROM platform_outbox").update();
    owner.sql("DELETE FROM platform_request").update();
    owner.sql("UPDATE platform_outbox_pressure SET pending_bytes=0,paused=false").update();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void cleanupMarksThenRechecksAllIncomingForeignKeysAndNeverRemovesUnresolvedWork() {
    Scope scope = scope();
    UUID free = job(scope, "SUCCEEDED");
    UUID retained = job(scope, "SUCCEEDED");
    job(scope, "DEAD");
    job(scope, "BLOCKED");
    job(scope, "RUNNING");
    assertEquals(2, clean());
    assertEquals(5, count("platform_job"));
    assertEquals(0, clean());
    OperationService operations = new OperationService(jdbc);
    HistoryQuery query = new HistoryQuery("", "SUCCEEDED", null, null, "id", "asc", 0, 50);
    assertEquals(
        410,
        assertThrows(
                BusinessException.class,
                () -> scopes.runService(scope, AUTHORITY, () -> operations.get(free)))
            .status());
    assertEquals(
        0,
        scopes.runService(scope, AUTHORITY, () -> operations.list(query, ZoneOffset.UTC).total()));
    assertEquals(
        0,
        scopes.runService(
            scope,
            AUTHORITY,
            () -> {
              var projection = operations.projection(query, ZoneOffset.UTC, List.of());
              return jdbc.sql("SELECT count(*) FROM (" + projection.sql() + ") visible")
                  .params(projection.parameters())
                  .query(Integer.class)
                  .single();
            }));
    JobHandler handler = mock(JobHandler.class);
    when(handler.type()).thenReturn("FILE_RETENTION");
    when(handler.lane()).thenReturn("service");
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("retentionHandler", handler);
    var runtime =
        new JobRuntime(
            jdbc,
            scopes,
            Clock.systemUTC(),
            beans.getBeanProvider(JobHandler.class),
            beans.getBeanProvider(OutboxService.class),
            beans.getBeanProvider(JobControlConnections.class),
            "api");
    try {
      assertEquals(
          410,
          assertThrows(
                  BusinessException.class,
                  () ->
                      scopes.runService(
                          scope,
                          AUTHORITY,
                          () -> runtime.submit(scope, "FILE_RETENTION", free.toString(), "{}")))
              .status());
    } finally {
      runtime.destroy();
    }
    owner
        .sql("INSERT INTO test_job_reference VALUES (:org,:account,:id)")
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", retained)
        .update();
    owner
        .sql(
            "UPDATE platform_job SET deletion_after=clock_timestamp()-interval '1 second' WHERE"
                + " deletion_after IS NOT NULL")
        .update();
    assertEquals(2, clean());
    assertEquals(4, count("platform_job"));
    assertFalse(
        owner
            .sql("SELECT EXISTS(SELECT 1 FROM platform_job WHERE id=:id)")
            .param("id", free)
            .query(Boolean.class)
            .single());
    assertTrue(
        owner
            .sql("SELECT EXISTS(SELECT 1 FROM platform_job WHERE id=:id)")
            .param("id", retained)
            .query(Boolean.class)
            .single());
    assertThrows(
        DataAccessException.class,
        () ->
            jdbc.sql("SELECT repricer_cleanup_platform_metadata()").query(Integer.class).single());
  }

  @Test
  void deliveredEventsWaitThirtyDaysAndTwentyFourHoursWhilePendingDeliveryJobsRetainReceipts() {
    Scope scope = scope();
    UUID removable = envelope(scope, true);
    UUID pending = envelope(scope, false);
    UUID stillRunning = envelope(scope, true);
    UUID delivery = job(scope, "RUNNING");
    owner
        .sql("UPDATE platform_job SET job_type='OUTBOX_DELIVERY',business_key=:key WHERE id=:id")
        .param("key", stillRunning.toString())
        .param("id", delivery)
        .update();
    clean();
    assertEquals(3, count("platform_outbox"));
    assertEquals(
        1,
        owner
            .sql("SELECT count(*) FROM platform_outbox WHERE deletion_after IS NOT NULL")
            .query(Integer.class)
            .single());
    clean();
    assertEquals(3, count("platform_outbox"));
    owner
        .sql(
            "UPDATE platform_outbox SET deletion_after=clock_timestamp()-interval '1 second' WHERE"
                + " id=:id")
        .param("id", removable)
        .update();
    clean();
    assertEquals(2, count("platform_outbox"));
    assertTrue(
        owner
            .sql("SELECT EXISTS(SELECT 1 FROM platform_outbox WHERE id=:id)")
            .param("id", pending)
            .query(Boolean.class)
            .single());
  }

  @Test
  void oneCleanupExaminesAtMostFiveHundredRows() {
    Scope scope = scope();
    owner
        .sql(
            """
            INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,business_key,
              payload,state,due_at,updated_at)
            SELECT gen_random_uuid(),:org,:account,:subject,'FILE_RETENTION','service',i::text,'{}',
              'SUCCEEDED',clock_timestamp()-interval '100 days',clock_timestamp()-interval '100 days'
            FROM generate_series(1,600) i
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .update();
    assertEquals(500, clean());
    assertEquals(
        500,
        owner
            .sql("SELECT count(*) FROM platform_job WHERE deletion_after IS NOT NULL")
            .query(Integer.class)
            .single());
    assertEquals(100, clean());
    assertEquals(600, count("platform_job"));
  }

  @Test
  void pressureSharesRollbackDeduplicationAndDeliveryAndUsesHighLowHysteresis() {
    Scope scope = scope();
    var json = new JsonCodec();
    JobRuntime jobs = mock(JobRuntime.class);
    OutboxRecipient recipient = mock(OutboxRecipient.class);
    when(recipient.name()).thenReturn("test-pressure");
    when(recipient.accepts("test.changed")).thenReturn(true);
    when(recipient.servicePermissions()).thenReturn(AUTHORITY);
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("testRecipient", recipient);
    var recipients = beans.getBeanProvider(OutboxRecipient.class);
    var outbox = new OutboxService(jdbc, json, jobs, recipients);
    assertThrows(
        IllegalStateException.class,
        () ->
            scopes.runService(
                scope,
                AUTHORITY,
                () -> {
                  outbox.emit(scope, "rollback", "test.changed", Map.of("value", "abc"));
                  throw new IllegalStateException("producer rollback");
                }));
    assertEquals(0, count("platform_outbox"));
    assertEquals(0, pendingBytes());
    scopes.runService(
        scope,
        AUTHORITY,
        () -> {
          outbox.emit(scope, "stable", "test.changed", Map.of("value", "abc"));
          outbox.emit(scope, "stable", "test.changed", Map.of("value", "abc"));
          return true;
        });
    assertEquals(1, count("platform_outbox"));
    long bytes =
        owner
            .sql("SELECT octet_length(payload::text) FROM platform_outbox")
            .query(Long.class)
            .single();
    assertEquals(bytes, pendingBytes());
    UUID deliveryId = owner.sql("SELECT id FROM platform_outbox").query(UUID.class).single();
    AtomicBoolean fail = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              jdbc.sql(
                      """
                      INSERT INTO platform_request(organization_id,account_id,subject_id,operation,request_id,body_hash,result)
                      VALUES (:org,:account,:subject,'test-delivery',:id,:hash,'{}')
                      """)
                  .param("org", scope.organizationId())
                  .param("account", scope.accountId())
                  .param("subject", scope.subjectId())
                  .param("id", deliveryId)
                  .param("hash", "0".repeat(64))
                  .update();
              if (fail.get()) {
                throw new IllegalStateException("consumer rollback");
              }
              return null;
            })
        .when(recipient)
        .receive(
            ArgumentMatchers.eq(scope),
            ArgumentMatchers.any(UUID.class),
            ArgumentMatchers.eq("test.changed"),
            ArgumentMatchers.anyString());
    var handler = new OutboxDeliveryHandler(jdbc, json, scopes, jobs, recipients);
    var context =
        new JobContext(
            UUID.randomUUID(),
            scope,
            json.encode(new OutboxService.Delivery(deliveryId)),
            1,
            Instant.now().plusSeconds(60),
            0,
            "service");
    assertThrows(IllegalStateException.class, () -> handler.execute(context));
    assertEquals(0, count("platform_request"));
    assertEquals(bytes, pendingBytes());
    fail.set(false);
    handler.execute(context);
    handler.execute(context);
    assertEquals(1, count("platform_request"));
    assertEquals(0, pendingBytes());
    owner
        .sql(
            "UPDATE platform_outbox SET deletion_after=clock_timestamp()+interval '24 hours' WHERE"
                + " id=:id")
        .param("id", deliveryId)
        .update();
    assertEquals(
        "EVENT_EXPIRED",
        assertThrows(BusinessException.class, () -> handler.execute(context)).code());
    assertEquals(
        "EVENT_EXPIRED",
        assertThrows(
                BusinessException.class,
                () ->
                    scopes.runService(
                        scope,
                        AUTHORITY,
                        () -> outbox.emit(scope, "stable", "test.changed", Map.of("value", "abc"))))
            .code());
    assertEquals(0, pendingBytes());

    owner.sql("UPDATE platform_outbox_pressure SET pending_bytes=1073741823,paused=false").update();
    UUID high =
        scopes.runService(
            scope,
            AUTHORITY,
            () -> {
              outbox.emit(scope, "high", "test.changed", Map.of("value", "abc"));
              return jdbc.sql("SELECT id FROM platform_outbox WHERE delivered_at IS NULL")
                  .query(UUID.class)
                  .single();
            });
    assertFalse(outbox.heavyAllowed());
    owner.sql("UPDATE platform_outbox_pressure SET pending_bytes=700000000").update();
    assertFalse(outbox.heavyAllowed());
    long highBytes =
        owner
            .sql("SELECT pressure_bytes FROM platform_outbox WHERE id=:id")
            .param("id", high)
            .query(Long.class)
            .single();
    owner
        .sql("UPDATE platform_outbox_pressure SET pending_bytes=:bytes")
        .param("bytes", 536870912L + highBytes)
        .update();
    scopes.runService(
        scope,
        AUTHORITY,
        () -> {
          jdbc.sql("UPDATE platform_outbox SET delivered_at=clock_timestamp() WHERE id=:id")
              .param("id", high)
              .update();
          jdbc.sql("SELECT repricer_outbox_pressure(:id)")
              .param("id", high)
              .query((row, index) -> true)
              .single();
          return true;
        });
    assertEquals(536870912L, pendingBytes());
    assertTrue(outbox.heavyAllowed());
  }

  private static Scope scope() {
    return new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }

  private static int clean() {
    return worker.sql("SELECT repricer_cleanup_platform_metadata()").query(Integer.class).single();
  }

  private static int count(String table) {
    return owner.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
  }

  private static long pendingBytes() {
    return owner
        .sql("SELECT pending_bytes FROM platform_outbox_pressure")
        .query(Long.class)
        .single();
  }

  private static UUID job(Scope scope, String state) {
    UUID id = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,business_key,
              payload,state,due_at,updated_at)
            VALUES (:id,:org,:account,:subject,'FILE_RETENTION','service',:key,'{}',:state,
              clock_timestamp()-interval '100 days',clock_timestamp()-interval '100 days')
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("key", id.toString())
        .param("state", state)
        .update();
    return id;
  }

  private static UUID envelope(Scope scope, boolean delivered) {
    UUID id = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO platform_outbox(id,organization_id,account_id,subject_id,producer_event_id,
              recipient,event_type,payload,delivered_at)
            VALUES (:id,:org,:account,:subject,:event,'fixture','test','{}',
              CASE WHEN :delivered THEN clock_timestamp()-interval '40 days' ELSE NULL END)
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("event", UUID.randomUUID())
        .param("delivered", delivered)
        .update();
    return id;
  }
}
