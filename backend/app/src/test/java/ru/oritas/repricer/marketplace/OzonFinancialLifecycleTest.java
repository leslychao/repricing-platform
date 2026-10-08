package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

class OzonFinancialLifecycleTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("finance-test-only");
  private static final JsonCodec JSON = new JsonCodec();
  private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static JdbcClient owner;
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner transactions;
  private static AuthorizationService authorization;
  private Scope scope;
  private StoredFileService files;
  private OutboundGateway gateway;
  private FinancialSourceService financial;
  private OzonFinancialSync handler;
  private OutboxService outbox;

  @BeforeAll
  static void prepare() throws Exception {
    POSTGRES.start();
    owner =
        JdbcClient.create(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "finance-test-only"));
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
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_api", "api-test-only");
    jdbc = JdbcClient.create(source);
    authorization = new AuthorizationService(jdbc);
    transactions =
        new ScopeTransactionRunner(jdbc, new DataSourceTransactionManager(source), authorization);
  }

  @BeforeEach
  void fixture() {
    scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    owner
        .sql(
            """
            INSERT INTO access_user(id,issuer,subject,display_name,email,email_verified)
            VALUES(:id,'test',:sub,'Test','test@example.invalid',true)
            """)
        .param("id", scope.subjectId())
        .param("sub", scope.subjectId().toString())
        .update();
    owner
        .sql("INSERT INTO access_organization(id,name) VALUES(:id,'Ozon finance')")
        .param("id", scope.organizationId())
        .update();
    owner
        .sql(
            """
            INSERT INTO access_membership(id,organization_id,subject_id,role)
            VALUES(gen_random_uuid(),:org,:subject,'OWNER')
            """)
        .param("org", scope.organizationId())
        .param("subject", scope.subjectId())
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
            VALUES(:id,:org,'OZON',:external,'Ozon finance','Europe/Moscow')
            """)
        .param("id", scope.accountId())
        .param("org", scope.organizationId())
        .param("external", scope.accountId().toString())
        .update();
    files = mock(StoredFileService.class);
    gateway = mock(OutboundGateway.class);
    var connections = mock(ConnectionService.class);
    when(connections.current(scope))
        .thenReturn(
            new ConnectionService.Credentials(
                "OZON", "100", "repricer/fixture", 1, "100", true, 1, "ACTIVE", "test"));
    var jobs = mock(JobRuntime.class);
    outbox =
        new OutboxService(
            jdbc,
            JSON,
            jobs,
            new DefaultListableBeanFactory().getBeanProvider(OutboxRecipient.class));
    financial = new FinancialSourceService(jdbc, authorization, JSON, files);
    handler =
        new OzonFinancialSync(
            jdbc,
            transactions,
            authorization,
            connections,
            mock(CatalogSyncService.class),
            gateway,
            mock(CapabilityService.class),
            files,
            financial,
            jobs,
            new IdempotencyService(jdbc, JSON),
            JSON,
            outbox,
            new AuditService(jdbc),
            CLOCK);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void correctionReplacesOnlyOneIdentifiedAccrualAndReplayDoesNotCreateAnotherEdition() {
    UUID firstRun = run("SERVICES", "ACCRUAL", NOW.minusSeconds(30));
    UUID raw = raw();
    UUID first = publish(firstRun, NOW.minusSeconds(30), raw, accrual(10, "1000"));
    UUID other = publish(firstRun, NOW.minusSeconds(30), raw, accrual(11, "500"));
    assertEquals(first, publish(firstRun, NOW.minusSeconds(30), raw, accrual(10, "1000")));
    UUID secondRun = run("SERVICES", "ACCRUAL", NOW.minusSeconds(10));
    UUID corrected = publish(secondRun, NOW.minusSeconds(10), raw, accrual(10, "1200"));
    assertEquals(3, count("marketplace_financial_observation"));
    transactions.run(
        scope,
        () -> {
          var original = financial.page(scope, first, 0, 10).getFirst();
          var current = financial.page(scope, corrected, 0, 10).getFirst();
          assertEquals(new BigDecimal("1000"), original.settlement().sellerRevenue());
          assertEquals(new BigDecimal("1200"), current.settlement().sellerRevenue());
          assertNull(current.settlement().units());
          assertNull(current.settlement().taxBase());
          assertNull(current.settlement().originalOrderLineId());
          assertEquals("OZON_ACCRUAL:10", financial.publication(scope, corrected).cohortKey());
          var publications =
              financial.publicationIds(scope, YearMonth.of(2026, 9), "ACCRUALS", null, 100);
          assertEquals(2, publications.size());
          assertTrue(publications.containsAll(List.of(other, corrected)));
          var coverage = financial.coverageEvidence(scope, YearMonth.of(2026, 9));
          assertFalse(coverage.completeByComponent().get("REVENUE"));
          assertEquals(2, coverage.publicationIds().size());
          assertThrows(
              BusinessException.class,
              () ->
                  financial.publishOzon(
                      scope, secondRun, NOW.minusSeconds(10), raw, accrual(10, "1300"), outbox));
        });
    Scope foreign = new Scope(scope.organizationId(), UUID.randomUUID(), scope.subjectId());
    assertThrows(
        BusinessException.class,
        () -> transactions.run(foreign, () -> financial.publication(foreign, corrected)));
  }

  @Test
  void parsedPagePublishesInBoundedTransactionsButNeverClaimsDayCoverage() throws Exception {
    UUID run = run("SERVICES", "ACCRUAL", NOW.minusSeconds(30));
    String content =
        "{\"accruals\":["
            + IntStream.range(1, 26)
                .mapToObj(value -> accrualJson(value, "1000"))
                .collect(Collectors.joining(","))
            + "],\"last_id\":\"\"}";
    page(run, content);
    var context = context(run);
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context).state());
    assertEquals(0, count("marketplace_financial_observation"));
    assertEquals(25, count("marketplace_history_stage"));
    assertEquals(
        25L,
        owner
            .sql("SELECT parsed_count FROM marketplace_sync_run WHERE id=:run")
            .param("run", run)
            .query(Long.class)
            .single());
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context).state());
    assertEquals(10, count("marketplace_financial_observation"));
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context).state());
    assertEquals(20, count("marketplace_financial_observation"));
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context).state());
    assertEquals(25, count("marketplace_financial_observation"));
    assertEquals("SUCCEEDED", SourceJobSteps.executeStep(handler, context).state());
    assertEquals(
        "INCOMPLETE",
        owner
            .sql("SELECT status FROM marketplace_source WHERE account_id=:account")
            .param("account", scope.accountId())
            .query(String.class)
            .single());
  }

  @Test
  void truncatedPageCannotPublishEvenItsValidPrefix() throws Exception {
    UUID run = run("SERVICES", "ACCRUAL", NOW.minusSeconds(30));
    page(
        run,
        "{\"accruals\":["
            + IntStream.range(1, 12)
                .mapToObj(value -> accrualJson(value, "1000"))
                .collect(Collectors.joining(",")));
    assertEquals("BLOCKED", SourceJobSteps.executeStep(handler, context(run)).state());
    assertEquals(0, count("marketplace_financial_observation"));
    assertEquals(10, count("marketplace_history_stage"));
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_raw_page WHERE run_id=:run AND parsed")
            .param("run", run)
            .query(Integer.class)
            .single());
  }

  private UUID publish(
      UUID run, Instant observed, UUID raw, OzonFinancialCanonicalizer.Observation observation) {
    return transactions.run(
        scope, () -> financial.publishOzon(scope, run, observed, raw, observation, outbox));
  }

  private static OzonFinancialCanonicalizer.Observation accrual(int id, String amount) {
    return OzonFinancialCanonicalizer.accrual(
        JsonParser.parseString(accrualJson(id, amount)).getAsJsonObject(),
        LocalDate.of(2026, 9, 12));
  }

  private static String accrualJson(int id, String amount) {
    return """
    {"accrual_id":%d,"date":"2026-09-12","posting":{"products":[{"sku":123,
      "commission":{"sale_amount":{"amount":"%s","currency":"RUB"},
      "commission":{"amount":"-199","currency":"RUB"}}}]}}
    """
        .formatted(id, amount);
  }

  private UUID run(String kind, String phase, Instant started) {
    UUID id = UUID.randomUUID();
    UUID job = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,
              business_key,payload,state,due_at) VALUES(:id,:org,:account,:subject,'OZON_FINANCIAL_SYNC',
              'fetch',:key,'{}','READY',clock_timestamp())
            """)
        .param("id", job)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("key", id.toString())
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_sync_run(id,organization_id,account_id,job_id,source_type,state,phase,
              range_from,range_until,range_cursor,started_at)
            VALUES(:id,:org,:account,:job,:kind,'FETCHING',:phase,'2026-09-12','2026-09-12','2026-09-12',:started)
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("job", job)
        .param("kind", kind)
        .param("phase", phase)
        .param("started", Timestamp.from(started))
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_source(id,organization_id,account_id,source_type,status)
            VALUES(gen_random_uuid(),:org,:account,:kind,'SYNCING')
            ON CONFLICT(organization_id,account_id,source_type) DO NOTHING
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("kind", kind)
        .update();
    return id;
  }

  private UUID raw() {
    UUID id = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,object_version,media_type,state)
            VALUES(:id,:org,:account,:subject,'RAW',:key,'version','application/json','STORED')
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("key", id.toString())
        .update();
    return id;
  }

  private void page(UUID run, String content) throws Exception {
    UUID id = raw();
    var file =
        new StoredFileService.FileRecord(
            id,
            id.toString(),
            "version",
            "application/json",
            null,
            "STORED",
            0,
            "0".repeat(64),
            true,
            scope);
    var response = new OutboundGateway.Response(null, 200, file, "identity", null, 1);
    when(gateway.executePage(
            eq(scope),
            eq(run),
            eq(0),
            eq(VendorMethod.OZON_ACCRUAL_DAY),
            anyString(),
            isNull(),
            isNull(),
            anyInt()))
        .thenReturn(response);
    when(gateway.open(response))
        .thenAnswer(ignored -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    when(files.get(id)).thenReturn(file);
  }

  private JobContext context(UUID run) {
    UUID job =
        owner
            .sql("SELECT job_id FROM marketplace_sync_run WHERE id=:run")
            .param("run", run)
            .query(UUID.class)
            .single();
    return new JobContext(
        job,
        scope,
        JSON.encode(new OzonFinancialSync.Work(run)),
        1,
        NOW.plusSeconds(120),
        0,
        "fetch");
  }

  private int count(String table) {
    return owner
        .sql("SELECT count(*) FROM " + table + " WHERE account_id=:account")
        .param("account", scope.accountId())
        .query(Integer.class)
        .single();
  }
}
