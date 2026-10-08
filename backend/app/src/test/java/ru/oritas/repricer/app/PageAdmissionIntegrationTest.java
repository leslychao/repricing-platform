package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.Scope;

class PageAdmissionIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("page-test-only");
  private static final Set<String> AUTHORITY = Set.of("marketplace.transport.record");
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner scopes;
  private static final java.util.List<Fixture> FIXTURES = new ArrayList<>();

  @BeforeAll
  static void prepare() throws Exception {
    POSTGRES.start();
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE ROLE repricer_migrator LOGIN BYPASSRLS PASSWORD 'migration-test-only'");
      statement.execute("CREATE ROLE repricer_api LOGIN NOBYPASSRLS PASSWORD 'api-test-only'");
      statement.execute(
          "CREATE ROLE repricer_worker LOGIN NOBYPASSRLS PASSWORD 'worker-test-only'");
      statement.execute("GRANT ALL ON SCHEMA public TO repricer_migrator");
    }
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
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_api", "api-test-only");
    jdbc = JdbcClient.create(source);
    scopes =
        new ScopeTransactionRunner(
            jdbc, new DataSourceTransactionManager(source), new AuthorizationService(jdbc));
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @AfterEach
  void finishFixtureRuns() {
    for (Fixture fixture : FIXTURES) {
      scopes.runService(
          fixture.scope(),
          AUTHORITY,
          () ->
              jdbc.sql("UPDATE marketplace_sync_run SET state='INCOMPLETE' WHERE id=:id")
                  .param("id", fixture.run())
                  .update());
    }
    FIXTURES.clear();
  }

  @Test
  void fortyMiBReservationAndHysteresisReleaseParsedPagesBeforePublication() {
    Fixture fixture = fixture();
    for (int page = 0; page < 6; page++) {
      assertNotNull(admit(fixture, page, true));
    }
    assertNull(admit(fixture, 6, true));
    parsed(fixture, 0);
    parsed(fixture, 1);
    assertNull(admit(fixture, 6, true)); // 160 MiB is below HIGH but above LOW.
    parsed(fixture, 2);
    assertNotNull(admit(fixture, 6, true));
    assertEquals(
        "FETCHING",
        scopes.runService(
            fixture.scope(),
            AUTHORITY,
            () ->
                jdbc.sql("SELECT state FROM marketplace_sync_run WHERE id=:id")
                    .param("id", fixture.run())
                    .query(String.class)
                    .single()));
  }

  @Test
  void sixteenSmallPagesStopAndExistingPageResumesWhileHeavyWorkIsPaused() {
    Fixture fixture = fixture();
    UUID first = null;
    for (int page = 0; page < 16; page++) {
      UUID admitted = admit(fixture, page, true);
      assertNotNull(admitted);
      if (first == null) {
        first = admitted;
      }
      scopes.runService(
          fixture.scope(),
          AUTHORITY,
          () ->
              jdbc.sql("UPDATE marketplace_page_queue SET byte_count=1 WHERE id=:id")
                  .param("id", admitted)
                  .update());
    }
    assertNull(admit(fixture, 16, true));
    assertEquals(first, admit(fixture, 0, false));
    for (int page = 0; page < 8; page++) {
      parsed(fixture, page);
    }
    assertNull(admit(fixture, 16, true)); // LOW requires strictly fewer than eight pages.
    parsed(fixture, 8);
    assertNull(admit(fixture, 16, false));
    assertNotNull(admit(fixture, 16, true));
  }

  @Test
  void concurrentReservationsCannotOvershootAccountUpperBound() throws Exception {
    Fixture fixture = fixture();
    for (int page = 0; page < 5; page++) {
      assertNotNull(admit(fixture, page, true));
    }
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var tasks = new ArrayList<java.util.concurrent.Future<UUID>>();
      for (int page = 5; page < 7; page++) {
        int number = page;
        tasks.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return admit(fixture, number, true);
                }));
      }
      ready.await();
      start.countDown();
      int admitted = 0;
      for (var task : tasks) {
        if (task.get() != null) {
          admitted++;
        }
      }
      assertEquals(1, admitted);
    }
  }

  @Test
  void admissionFunctionCannotAddressAnotherTenantOrExposeGlobalPressure() {
    Fixture fixture = fixture();
    assertThrows(
        DataAccessException.class,
        () ->
            scopes.runService(
                fixture.scope(),
                AUTHORITY,
                () ->
                    jdbc.sql("SELECT * FROM repricer_admit_page(:id,:org,:account,:run,0,true)")
                        .param("id", UUID.randomUUID())
                        .param("org", fixture.scope().organizationId())
                        .param("account", UUID.randomUUID())
                        .param("run", fixture.run())
                        .query((row, index) -> row.getObject(1, UUID.class))
                        .single()));
    assertThrows(
        DataAccessException.class,
        () -> jdbc.sql("SELECT * FROM marketplace_page_pressure").query().listOfRows());
  }

  @Test
  void globalFourGiBBoundaryAndTwoGiBLowWatermarkApplyAcrossCompanies() {
    var admitted = new ArrayList<Fixture>();
    for (int index = 0; index < 102; index++) {
      Fixture fixture = fixture();
      assertNotNull(admit(fixture, 0, true));
      admitted.add(fixture);
    }
    Fixture next = fixture();
    assertNull(admit(next, 0, true));
    for (int index = 0; index < 49; index++) {
      Fixture done = admitted.get(index);
      scopes.runService(
          done.scope(),
          AUTHORITY,
          () ->
              jdbc.sql(
                      "UPDATE marketplace_page_queue SET closed_at=clock_timestamp() WHERE"
                          + " run_id=:run")
                  .param("run", done.run())
                  .update());
    }
    assertNull(admit(next, 0, true));
    for (int index = 49; index < 51; index++) {
      Fixture done = admitted.get(index);
      scopes.runService(
          done.scope(),
          AUTHORITY,
          () ->
              jdbc.sql(
                      "UPDATE marketplace_page_queue SET closed_at=clock_timestamp() WHERE"
                          + " run_id=:run")
                  .param("run", done.run())
                  .update());
    }
    assertNotNull(admit(next, 0, true));
  }

  private static UUID admit(Fixture fixture, int page, boolean heavyAllowed) {
    return scopes
        .runService(
            fixture.scope(),
            AUTHORITY,
            () ->
                jdbc.sql(
                        """
                        SELECT admission_id FROM repricer_admit_page(:id,:org,:account,:run,:page,:allowed)
                        """)
                    .param("id", UUID.randomUUID())
                    .param("org", fixture.scope().organizationId())
                    .param("account", fixture.scope().accountId())
                    .param("run", fixture.run())
                    .param("page", page)
                    .param("allowed", heavyAllowed)
                    .query((row, index) -> new Admission(row.getObject(1, UUID.class)))
                    .single())
        .id();
  }

  private static void parsed(Fixture fixture, int page) {
    scopes.runService(
        fixture.scope(),
        AUTHORITY,
        () -> {
          UUID file = UUID.randomUUID();
          jdbc.sql(
                  """
                  INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,
                    object_version,media_type,state,byte_count,sha256,eof_confirmed)
                  VALUES (:id,:org,:account,:subject,'RAW',:key,'immutable','application/json',
                    'STORED',0,:digest,true)
                  """)
              .param("id", file)
              .param("org", fixture.scope().organizationId())
              .param("account", fixture.scope().accountId())
              .param("subject", fixture.scope().subjectId())
              .param("key", file.toString())
              .param("digest", "a".repeat(64))
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_raw_page(organization_id,account_id,run_id,page_number,
                    file_id,method,parsed) VALUES (:org,:account,:run,:page,:file,'YANDEX_CATALOG',true)
                  """)
              .param("org", fixture.scope().organizationId())
              .param("account", fixture.scope().accountId())
              .param("run", fixture.run())
              .param("page", page)
              .param("file", file)
              .update();
          return true;
        });
  }

  private static Fixture fixture() {
    Scope scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    UUID run = UUID.randomUUID();
    Fixture fixture =
        scopes.runService(
            scope,
            AUTHORITY,
            () -> {
              jdbc.sql("INSERT INTO access_organization(id,name) VALUES (:id,'Page test')")
                  .param("id", scope.organizationId())
                  .update();
              jdbc.sql(
                      """
                      INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                      VALUES (:id,:org,'YANDEX',:external,'Page test','Europe/Moscow')
                      """)
                  .param("id", scope.accountId())
                  .param("org", scope.organizationId())
                  .param("external", scope.accountId().toString())
                  .update();
              jdbc.sql(
                      """
                      INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state,phase)
                      VALUES (:id,:org,:account,'CATALOG','FETCHING','CATALOG')
                      """)
                  .param("id", run)
                  .param("org", scope.organizationId())
                  .param("account", scope.accountId())
                  .update();
              return new Fixture(scope, run);
            });
    FIXTURES.add(fixture);
    return fixture;
  }

  private record Fixture(Scope scope, UUID run) {}

  private record Admission(UUID id) {}
}
