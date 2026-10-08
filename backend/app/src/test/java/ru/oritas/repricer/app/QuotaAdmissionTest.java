package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

class QuotaAdmissionTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("quota-test-only");
  private static final String SUBJECT = "a".repeat(64);
  private static JdbcClient owner;

  @BeforeAll
  static void prepare() throws Exception {
    POSTGRES.start();
    try (Connection connection = ownerConnection();
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE ROLE repricer_migrator LOGIN BYPASSRLS PASSWORD 'migration-test-only'");
      statement.execute("CREATE ROLE repricer_api LOGIN NOBYPASSRLS PASSWORD 'api-test-only'");
      statement.execute(
          "CREATE ROLE repricer_worker LOGIN NOBYPASSRLS PASSWORD 'worker-test-only'");
      statement.execute("GRANT ALL ON SCHEMA public TO repricer_migrator");
    }
    Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), "repricer_migrator", "migration-test-only");
    var database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    try (var liquibase =
        new Liquibase("db/changelog/master.xml", new ClassLoaderResourceAccessor(), database)) {
      liquibase.update(new liquibase.Contexts());
    }
    owner =
        JdbcClient.create(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }

  @AfterAll
  static void close() {
    POSTGRES.stop();
  }

  @Test
  void privateQuotaMetadataCannotBeReadByEitherRuntimeRole() throws Exception {
    for (String role : new String[] {"repricer_api", "repricer_worker"}) {
      try (Connection connection =
              DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(),
                  role,
                  role.equals("repricer_api") ? "api-test-only" : "worker-test-only");
          var statement = connection.createStatement()) {
        for (String table :
            new String[] {
              "marketplace_quota_bucket",
              "marketplace_quota_waiter",
              "marketplace_quota_turn",
              "marketplace_quota_grant",
              "marketplace_quota_resource",
              "marketplace_quota_report"
            }) {
          assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM " + table));
        }
      }
    }
  }

  @Test
  void concurrencyAndAdmissionIdentityAreSeparateFromRateConsumption() throws Exception {
    String group = "concurrency";
    UUID org = UUID.randomUUID(), account = UUID.randomUUID(), grant = UUID.randomUUID();
    assertNull(reserve(group, org, account, "SYNC", grant, SUBJECT));
    assertNull(reserve(group, org, account, "SYNC", grant, SUBJECT));
    assertEquals(
        1L,
        owner
            .sql("SELECT count(*) FROM marketplace_quota_grant WHERE method_group=:group")
            .param("group", group)
            .query(Long.class)
            .single());
    makeWindowDue(group);
    assertNotNull(reserve(group, org, account, "SYNC", UUID.randomUUID(), SUBJECT));
    release(grant);
    assertNull(reserve(group, org, account, "SYNC", UUID.randomUUID(), SUBJECT));
    assertNull(
        reserve(
            group,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "SYNC",
            UUID.randomUUID(),
            "b".repeat(64)));
  }

  @Test
  void tenthEligibleGrantBelongsToContinuouslyReadySynchronization() throws Exception {
    String group = "sync-share";
    UUID org = UUID.randomUUID(), account = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    assertNull(reserve(group, org, account, "RECONCILE", first, SUBJECT));
    assertNotNull(reserve(group, org, account, "SYNC", UUID.randomUUID(), SUBJECT));
    release(first);
    for (int count = 1; count < 9; count++) {
      makeWindowDue(group);
      UUID grant = UUID.randomUUID();
      assertNull(reserve(group, org, account, "RECONCILE", grant, SUBJECT));
      release(grant);
    }
    makeWindowDue(group);
    assertNotNull(reserve(group, org, account, "RECONCILE", UUID.randomUUID(), SUBJECT));
    assertNull(reserve(group, org, account, "SYNC", UUID.randomUUID(), SUBJECT));
  }

  @Test
  void companyIsSelectedBeforeItsNumberOfAccounts() throws Exception {
    String group = "company-first";
    UUID orgA = UUID.randomUUID(), orgB = UUID.randomUUID();
    UUID a1 = UUID.randomUUID(),
        a2 = UUID.randomUUID(),
        b1 = UUID.randomUUID(),
        first = UUID.randomUUID();
    assertNull(reserve(group, orgA, a1, "SYNC", first, SUBJECT));
    assertNotNull(reserve(group, orgA, a2, "SYNC", UUID.randomUUID(), SUBJECT));
    assertNotNull(reserve(group, orgB, b1, "SYNC", UUID.randomUUID(), SUBJECT));
    release(first);
    makeWindowDue(group);
    assertNotNull(reserve(group, orgA, a2, "SYNC", UUID.randomUUID(), SUBJECT));
    UUID other = UUID.randomUUID();
    assertNull(reserve(group, orgB, b1, "SYNC", other, SUBJECT));
    release(other);
    makeWindowDue(group);
    assertNotNull(reserve(group, orgA, a1, "SYNC", UUID.randomUUID(), SUBJECT));
    assertNull(reserve(group, orgA, a2, "SYNC", UUID.randomUUID(), SUBJECT));
  }

  @Test
  void twoWorkersCannotBothConsumeTheSameExternalAdmission() throws Exception {
    String group = "concurrent-workers";
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return reserve(
                    group,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "SYNC",
                    UUID.randomUUID(),
                    SUBJECT);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return reserve(
                    group,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "SYNC",
                    UUID.randomUUID(),
                    SUBJECT);
              });
      start.countDown();
      assertEquals(1, (first.get() == null ? 1 : 0) + (second.get() == null ? 1 : 0));
    }
  }

  private static Timestamp reserve(
      String group, UUID org, UUID account, String purpose, UUID id, String subject)
      throws SQLException {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "repricer_worker", "worker-test-only");
        var statement =
            connection.prepareStatement(
                "SELECT repricer_reserve_quota(?,?,?,?,CAST(? AS"
                    + " text[]),?,1000,'test-resource',1)")) {
      statement.setObject(1, id);
      statement.setObject(2, org);
      statement.setObject(3, account);
      statement.setString(4, purpose);
      statement.setString(5, "{" + subject + "}");
      statement.setString(6, group);
      try (var result = statement.executeQuery()) {
        result.next();
        return result.getTimestamp(1);
      }
    }
  }

  @Test
  void reportSlotSurvivesHttpCompletionUntilConfirmedGenerationEnd() throws Exception {
    String group = "YANDEX:report-generation";
    String subject = "c".repeat(64);
    UUID first = UUID.randomUUID();
    assertNull(reserve(group, UUID.randomUUID(), UUID.randomUUID(), "SYNC", first, subject));
    release(first);
    makeWindowDue(group);
    UUID org = UUID.randomUUID(), account = UUID.randomUUID();
    assertNotNull(reserve(group, org, account, "SYNC", UUID.randomUUID(), subject));
    owner
        .sql("SELECT repricer_finish_report_quota(:id)")
        .param("id", first)
        .query((row, index) -> true)
        .single();
    assertNull(reserve(group, org, account, "SYNC", UUID.randomUUID(), subject));
  }

  @Test
  void lateResourceObservationNeverReturnsSpentCapacityOrOpensAnEarlierWindow() throws Exception {
    String group = "resource-window";
    String subject = "d".repeat(64);
    UUID org = UUID.randomUUID(), account = UUID.randomUUID(), first = UUID.randomUUID();
    assertNull(reserve(group, org, account, "SYNC", first, subject));
    release(first);
    owner
        .sql(
            """
            SELECT repricer_observe_resource(CAST(:subjects AS text[]),:group,'test-resource',
              clock_timestamp()-interval '1 minute',date_trunc('hour',clock_timestamp())+interval '1 hour',100,11)
            """)
        .param("subjects", "{" + subject + "}")
        .param("group", group)
        .query((row, index) -> true)
        .single();
    makeWindowDue(group);
    UUID second = UUID.randomUUID();
    assertNull(reserve(group, org, account, "SYNC", second, subject));
    release(second);
    makeWindowDue(group);
    owner
        .sql(
            """
            SELECT repricer_observe_resource(CAST(:subjects AS text[]),:group,'test-resource',
              clock_timestamp()-interval '2 minutes',date_trunc('hour',clock_timestamp())+interval '1 hour',200,99)
            """)
        .param("subjects", "{" + subject + "}")
        .param("group", group)
        .query((row, index) -> true)
        .single();
    assertNotNull(reserve(group, org, account, "SYNC", UUID.randomUUID(), subject));
    assertEquals(
        10L,
        owner
            .sql("SELECT remaining FROM marketplace_quota_resource WHERE subject_hash=:subject")
            .param("subject", subject)
            .query(Long.class)
            .single());
    assertEquals(
        100L,
        owner
            .sql(
                "SELECT confirmed_limit FROM marketplace_quota_resource WHERE"
                    + " subject_hash=:subject")
            .param("subject", subject)
            .query(Long.class)
            .single());
  }

  private static void release(UUID id) {
    owner
        .sql("SELECT repricer_release_quota(:id)")
        .param("id", id)
        .query((row, index) -> true)
        .single();
  }

  @Test
  void retentionKeepsUnknownReportSlotsAndDeletesNoMoreThanFiveHundredRows() {
    UUID unknown = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO marketplace_quota_grant(id,subjects,method_group,created_at,expires_at,released_at)
            VALUES (:id,ARRAY[:subject],'retention-report',clock_timestamp()-interval '3 days',
              clock_timestamp()-interval '2 days',clock_timestamp()-interval '2 days')
            """)
        .param("id", unknown)
        .param("subject", SUBJECT)
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_quota_report(id,subjects,created_at)
            VALUES (:id,ARRAY[:subject],clock_timestamp()-interval '3 days')
            """)
        .param("id", unknown)
        .param("subject", SUBJECT)
        .update();
    owner.sql("SELECT repricer_cleanup_quota()").query(Integer.class).single();
    assertEquals(
        1,
        owner
            .sql("SELECT count(*) FROM marketplace_quota_grant WHERE id=:id")
            .param("id", unknown)
            .query(Integer.class)
            .single());
    owner
        .sql(
            """
            INSERT INTO marketplace_quota_grant(id,subjects,method_group,created_at,expires_at,released_at)
            SELECT gen_random_uuid(),ARRAY[:subject],'retention-bulk',clock_timestamp()-interval '3 days',
              clock_timestamp()-interval '2 days',clock_timestamp()-interval '2 days'
            FROM generate_series(1,600)
            """)
        .param("subject", SUBJECT)
        .update();
    assertEquals(500, owner.sql("SELECT repricer_cleanup_quota()").query(Integer.class).single());
    assertEquals(
        100,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_quota_grant WHERE method_group='retention-bulk'
                """)
            .query(Integer.class)
            .single());
    assertEquals(
        1,
        owner
            .sql("SELECT count(*) FROM marketplace_quota_report WHERE id=:id")
            .param("id", unknown)
            .query(Integer.class)
            .single());
  }

  private static void makeWindowDue(String group) {
    owner
        .sql(
            "UPDATE marketplace_quota_bucket SET next_allowed_at=clock_timestamp()-interval '1"
                + " second' WHERE method_group=:group")
        .param("group", group)
        .update();
  }

  private static Connection ownerConnection() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
