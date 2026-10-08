package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AccessService;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.IdentityService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.economics.analytics.SalesPaceHandlers;
import ru.oritas.repricer.economics.analytics.SalesPaceService;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.ScopeExecutor;
import ru.oritas.repricer.platform.TableExportSource;

class MarketplaceHistoryIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("history-test-only");
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner transactions;
  private static MarketplaceHistoryService history;
  private static SalesPaceService pace;
  private static UUID organization;
  private static UUID admin;

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
    var authorization = new AuthorizationService(jdbc);
    transactions =
        new ScopeTransactionRunner(jdbc, new DataSourceTransactionManager(source), authorization);
    var json = new JsonCodec();
    var outbox =
        new OutboxService(
            jdbc,
            json,
            mock(JobRuntime.class),
            new DefaultListableBeanFactory().getBeanProvider(OutboxRecipient.class));
    var access =
        new AccessService(
            jdbc,
            transactions,
            authorization,
            new IdempotencyService(jdbc, json),
            new AuditService(jdbc),
            outbox,
            new IdentityService(jdbc));
    organization = access.initManagedUsers("https://history.invalid/realm", "admin", "test");
    admin = IdentityService.userId("https://history.invalid/realm", "admin");
    var reads = new MarketplaceReadService(jdbc, authorization, json, Clock.systemUTC());
    history = new MarketplaceHistoryService(jdbc, authorization, reads, Clock.systemUTC());
    pace =
        new SalesPaceService(
            history,
            reads,
            authorization,
            jdbc,
            new NamedParameterJdbcTemplate(source),
            json,
            Clock.systemUTC(),
            mock(JobRuntime.class),
            outbox,
            new ResourceService(jdbc, reads, Clock.systemUTC(), outbox));
  }

  @AfterAll
  static void close() {
    POSTGRES.stop();
  }

  @Test
  void backgroundPaceRefreshUsesDeclaredAccountPermissionWithoutHumanMembership() throws Exception {
    Fixture fixture = fixture();
    UUID job = transactions.run(fixture.scope(), () -> createPaceRefresh(fixture));
    try (var beans = new AnnotationConfigApplicationContext()) {
      beans.registerBean(SalesPaceService.class, () -> pace);
      beans.registerBean(JobRuntime.class, () -> mock(JobRuntime.class));
      beans.registerBean(ScopeExecutor.class, () -> transactions);
      beans.registerBean(Clock.class, Clock::systemUTC);
      beans.registerBean(JsonCodec.class, JsonCodec::new);
      beans.register(SalesPaceHandlers.class);
      beans.refresh();
      var handler = beans.getBean("paceRefreshHandler", JobHandler.class);
      var serviceScope = new Scope(organization, fixture.scope().accountId(), UUID.randomUUID());
      var outcome =
          handler.execute(
              new JobContext(
                  job, serviceScope, "{}", 1, Instant.now().plusSeconds(60), 0, "calculation"));
      assertEquals("SUCCEEDED", outcome.state());
    }
  }

  @Test
  void changedPriceInvalidatesPaceBeforeRefreshWithoutRewritingHistoricalSnapshot() {
    Fixture fixture = fixture();
    UUID placement = UUID.randomUUID();
    LocalDate today = LocalDate.now(ZoneId.of("Europe/Moscow"));
    transactions.run(
        fixture.scope(),
        () -> {
          jdbc.sql("UPDATE marketplace_offer SET seller_price=100 WHERE id=:offer")
              .param("offer", fixture.offer())
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_placement(id,organization_id,account_id,offer_id,external_id,
                    model,status,available,fields,publication_id,observed_at,valid_until)
                  SELECT :placement,organization_id,account_id,id,'shop','FBS','ACTIVE',true,'{}',
                    publication_id,clock_timestamp(),clock_timestamp()+interval '1 hour'
                  FROM marketplace_offer WHERE id=:offer
                  """)
              .param("placement", placement)
              .param("offer", fixture.offer())
              .update();
          var head = history.paceBasisProjection(fixture.scope());
          String regime =
              jdbc.sql("SELECT regime FROM (" + head.sql() + ") head")
                  .params(head.parameters())
                  .query(String.class)
                  .single();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_history_day(organization_id,account_id,placement_id,day,
                    ordered_units,orders_complete,stock_continuous,regime,sources_valid_until,raw_file_id)
                  SELECT :org,:account,:placement,day::date,5,true,true,:regime,
                    clock_timestamp()+interval '1 hour',:raw
                  FROM generate_series(CAST(:from AS date),CAST(:until AS date),interval '1 day') day
                  """)
              .param("org", fixture.scope().organizationId())
              .param("account", fixture.scope().accountId())
              .param("placement", placement)
              .param("regime", regime)
              .param("raw", fixture.raw())
              .param("from", today.minusDays(14))
              .param("until", today.minusDays(1))
              .update();
          refreshPace(fixture);
          var initial = pace.page(fixture.scope(), 0, 50, "", "id", "asc", "AVAILABLE");
          assertEquals(1, initial.total());
          assertEquals(0, new BigDecimal("35").compareTo(initial.items().getFirst().quantity()));
          UUID originalId = initial.items().getFirst().id();

          jdbc.sql("UPDATE marketplace_offer SET seller_price=120 WHERE id=:offer")
              .param("offer", fixture.offer())
              .update();
          var stale = pace.page(fixture.scope(), 0, 50, "", "id", "asc", "INCOMPLETE");
          assertEquals(1, stale.total());
          assertEquals("SOURCE_CHANGED", stale.items().getFirst().reason());
          assertNull(stale.items().getFirst().quantity());
          assertEquals(0, pace.page(fixture.scope(), 0, 50, "", "id", "asc", "AVAILABLE").total());
          var exported =
              pace.projection(
                  fixture.scope(),
                  new TableExportSource.Query("", Map.of(), "id,asc", 0, 50),
                  List.of());
          assertEquals(
              "SOURCE_CHANGED",
              jdbc.sql("SELECT cells->>4 FROM (" + exported.sql() + ") x")
                  .params(exported.parameters())
                  .query(String.class)
                  .single());
          assertEquals(
              "AVAILABLE",
              jdbc.sql("SELECT snapshot->>'status' FROM economics_sales_pace WHERE id=:id")
                  .param("id", originalId)
                  .query(String.class)
                  .single());

          refreshPace(fixture);
          assertEquals(0, pace.page(fixture.scope(), 0, 50, "", "id", "asc", "AVAILABLE").total());
          assertEquals(
              2,
              jdbc.sql("SELECT count(*) FROM economics_sales_pace").query(Integer.class).single());
        });
  }

  private static void refreshPace(Fixture fixture) {
    assertTrue(pace.prepareNext(fixture.scope(), createPaceRefresh(fixture)));
  }

  private static UUID createPaceRefresh(Fixture fixture) {
    UUID job = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,
              business_key,payload,state,due_at)
            VALUES (:id,:org,:account,:subject,'SALES_PACE_REFRESH','calculation',:key,'{}','READY',clock_timestamp())
            """)
        .param("id", job)
        .param("org", fixture.scope().organizationId())
        .param("account", fixture.scope().accountId())
        .param("subject", fixture.scope().subjectId())
        .param("key", job.toString())
        .update();
    jdbc.sql(
            "INSERT INTO economics_pace_refresh(organization_id,account_id,id) VALUES"
                + " (:org,:account,:id)")
        .param("org", fixture.scope().organizationId())
        .param("account", fixture.scope().accountId())
        .param("id", job)
        .update();
    return job;
  }

  @Test
  void paymentSortAndExportUseSameCaseInsensitiveSearchAndLocalDateBoundary() {
    Fixture fixture = fixture();
    UUID first = payment(fixture, "Transfer A", "20", "2026-01-31T21:30:00Z");
    UUID second = payment(fixture, "transfer B", "100", "2026-02-01T20:59:59Z");
    payment(fixture, "TRANSFER OUTSIDE", "500", "2026-02-01T21:00:00Z");
    var day = LocalDate.parse("2026-02-01");
    transactions.run(
        fixture.scope(),
        () -> {
          var page =
              history.payments(fixture.scope(), 0, 50, day, day, "TRANSFER", "amount", "asc");
          assertEquals(
              List.of(first, second),
              page.items().stream().map(MarketplaceHistoryService.Payment::id).toList());
          var query =
              new TableExportSource.Query(
                  "TRANSFER",
                  Map.of("from", day.toString(), "to", day.toString()),
                  "amount,asc",
                  0,
                  50);
          var export =
              history.historyProjection(
                  fixture.scope(),
                  MarketplaceHistoryService.HistoryTable.PAYMENTS,
                  query,
                  List.of());
          assertEquals(
              List.of(first, second),
              jdbc.sql(export.sql())
                  .params(export.parameters())
                  .query((row, index) -> row.getObject("canonical_id", UUID.class))
                  .list());
        });
  }

  @Test
  void unknownRefundRemainsNullAndLastInBothSortDirections() {
    Fixture fixture = fixture();
    UUID unknown = returned(fixture, "unknown", null);
    UUID known = returned(fixture, "known", "0");
    var day = LocalDate.parse("2026-02-01");
    for (String direction : List.of("asc", "desc")) {
      transactions.run(
          fixture.scope(),
          () -> {
            var page = history.returns(fixture.scope(), 0, 50, day, day, "", "amount", direction);
            assertEquals(
                List.of(known, unknown),
                page.items().stream().map(MarketplaceHistoryService.Return::id).toList());
            assertNull(page.items().getLast().refundedAmount());
            assertNull(page.items().getLast().physicalReceiptConfirmed());
            assertNull(page.items().getLast().stockConfirmed());
            var projection =
                history.historyProjection(
                    fixture.scope(),
                    MarketplaceHistoryService.HistoryTable.RETURNS,
                    new TableExportSource.Query(
                        "",
                        Map.of("from", day.toString(), "to", day.toString()),
                        "amount," + direction,
                        0,
                        50),
                    List.of());
            assertEquals(
                List.of(known, unknown),
                jdbc.sql(projection.sql())
                    .params(projection.parameters())
                    .query((row, index) -> row.getObject("canonical_id", UUID.class))
                    .list());
          });
    }
  }

  @Test
  void supplierFinancialFactCannotReferenceAnotherAccountRawFile() {
    Fixture own = fixture();
    Fixture foreign = fixture();
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            payment(
                new Fixture(own.scope(), own.offer(), foreign.raw()),
                "foreign",
                "10",
                "2026-02-01T12:00:00Z"));
  }

  private static Fixture fixture() {
    UUID account = UUID.randomUUID(),
        offer = UUID.randomUUID(),
        run = UUID.randomUUID(),
        raw = UUID.randomUUID();
    Scope scope = new Scope(organization, account, admin);
    transactions.run(
        new Scope(organization, null, admin),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'YANDEX',:external,'History test','Europe/Moscow')
                    """)
                .param("id", account)
                .param("org", organization)
                .param("external", account.toString())
                .update());
    transactions.run(
        scope,
        () -> {
          jdbc.sql(
                  """
                  INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
                  VALUES (:id,:org,:account,'CATALOG','PUBLISHED')
                  """)
              .param("id", run)
              .param("org", organization)
              .param("account", account)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,observed_at,publication_id)
                  VALUES (:id,:org,:account,'001','001','History offer',clock_timestamp(),:run)
                  """)
              .param("id", offer)
              .param("org", organization)
              .param("account", account)
              .param("run", run)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,media_type,state)
                  VALUES (:id,:org,:account,:subject,'RAW',:key,'application/json','READY')
                  """)
              .param("id", raw)
              .param("org", organization)
              .param("account", account)
              .param("subject", admin)
              .param("key", raw.toString())
              .update();
        });
    return new Fixture(scope, offer, raw);
  }

  private static UUID payment(Fixture fixture, String name, String amount, String date) {
    UUID id = UUID.randomUUID();
    transactions.run(
        fixture.scope(),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_payment(id,organization_id,account_id,external_id,occurred_at,amount,currency,
                      payment_scope,source_revision,content_hash,raw_file_id)
                    VALUES (:id,:org,:account,:name,:date,CAST(:amount AS numeric),'RUB','ACCOUNT','1',:hash,:raw)
                    """)
                .param("id", id)
                .param("org", organization)
                .param("account", fixture.scope().accountId())
                .param("name", name)
                .param("date", Timestamp.from(Instant.parse(date)))
                .param("amount", amount)
                .param("hash", "a".repeat(64))
                .param("raw", fixture.raw())
                .update());
    return id;
  }

  private static UUID returned(Fixture fixture, String line, String amount) {
    UUID id = UUID.randomUUID();
    transactions.run(
        fixture.scope(),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_return(id,organization_id,account_id,external_id,offer_id,line_id,
                      returned_quantity,returned_at,refunded_amount,source_revision,content_hash,raw_file_id)
                    VALUES (:id,:org,:account,:external,:offer,:line,1,'2026-02-01T12:00:00Z',
                      CAST(:amount AS numeric),'1',:hash,:raw)
                    """)
                .param("id", id)
                .param("org", organization)
                .param("account", fixture.scope().accountId())
                .param("external", id.toString())
                .param("offer", fixture.offer())
                .param("line", line)
                .param("amount", amount)
                .param("hash", "b".repeat(64))
                .param("raw", fixture.raw())
                .update());
    return id;
  }

  private record Fixture(Scope scope, UUID offer, UUID raw) {}
}
