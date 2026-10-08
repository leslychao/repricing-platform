package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.app.read.StockReadService;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;
import ru.oritas.repricer.platform.TableExportSource;

class MarketplaceSourceLifecycleTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("source-test-only");
  private static final JsonCodec JSON = new JsonCodec();
  private static JdbcClient owner;
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner transactions;
  private static AuthorizationService authorization;
  private static NamedParameterJdbcTemplate categoryBatch;
  private Scope scope;
  private ConnectionService connections;
  private OutboundGateway gateway;
  private CapabilityService capabilities;
  private StoredFileService files;
  private JobRuntime jobs;
  private OutboxService outbox;
  private CatalogSyncService catalog;
  private SourceSnapshotPublisher snapshots;

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
            "ALTER TABLE marketplace_stock_stage ADD COLUMN test_transaction bigint DEFAULT"
                + " txid_current()")
        .update();
    var source =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_api", "api-test-only");
    jdbc = JdbcClient.create(source);
    categoryBatch = new NamedParameterJdbcTemplate(source);
    authorization = new AuthorizationService(jdbc);
    transactions =
        new ScopeTransactionRunner(jdbc, new DataSourceTransactionManager(source), authorization);
  }

  @BeforeEach
  void fixture() {
    scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    owner
        .sql(
            "INSERT INTO access_user(id,issuer,subject,display_name,email,email_verified) VALUES"
                + " (:id,'test',:sub,'Test','test@example.invalid',true)")
        .param("id", scope.subjectId())
        .param("sub", scope.subjectId().toString())
        .update();
    owner
        .sql("INSERT INTO access_organization(id,name) VALUES (:id,'Source test')")
        .param("id", scope.organizationId())
        .update();
    owner
        .sql(
            "INSERT INTO access_membership(id,organization_id,subject_id,role) VALUES"
                + " (gen_random_uuid(),:org,:subject,'OWNER')")
        .param("org", scope.organizationId())
        .param("subject", scope.subjectId())
        .update();
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_account(id,organization_id,marketplace,external_id,name,timezone)"
                + " VALUES (:id,:org,'OZON',:external,'Source account','Europe/Moscow')")
        .param("id", scope.accountId())
        .param("org", scope.organizationId())
        .param("external", scope.accountId().toString())
        .update();
    connections = mock(ConnectionService.class);
    when(connections.current(scope))
        .thenReturn(
            new ConnectionService.Credentials(
                "OZON", "fixture", "repricer/fixture", 1, "fixture", true, 1, "READY", "fixture"));
    gateway = mock(OutboundGateway.class);
    capabilities = mock(CapabilityService.class);
    files = mock(StoredFileService.class);
    snapshots = mock(SourceSnapshotPublisher.class);
    jobs = mock(JobRuntime.class);
    when(jobs.submit(any(), anyString(), anyString(), anyString()))
        .thenAnswer(
            call -> {
              Scope selected = call.getArgument(0);
              UUID id = UUID.randomUUID();
              jdbc.sql(
                      """
                      INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,business_key,payload,state,due_at)
                      VALUES (:id,:org,:account,:subject,:type,'fetch',:key,CAST(:payload AS jsonb),'READY',clock_timestamp())
                      """)
                  .param("id", id)
                  .param("org", selected.organizationId())
                  .param("account", selected.accountId())
                  .param("subject", selected.subjectId())
                  .param("type", call.getArgument(1, String.class))
                  .param("key", call.getArgument(2, String.class))
                  .param("payload", call.getArgument(3, String.class))
                  .update();
              return id;
            });
    outbox =
        new OutboxService(
            jdbc,
            JSON,
            jobs,
            new DefaultListableBeanFactory().getBeanProvider(OutboxRecipient.class));
    catalog =
        new CatalogSyncService(
            jdbc,
            transactions,
            authorization,
            connections,
            gateway,
            capabilities,
            files,
            jobs,
            JSON,
            new IdempotencyService(jdbc, JSON),
            new AuditService(jdbc),
            outbox,
            Clock.systemUTC(),
            snapshots,
            new CategoryService(jdbc, categoryBatch, authorization, outbox));
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void appliedRefreshWaitsForEarlierTraversalThenSchedulesOnceAndChecksCurrentAccess() {
    UUID old = run("PRICES", "PRICES", Instant.now().minusSeconds(60));
    Instant confirmed = Instant.now();
    JobContext context = context(UUID.randomUUID());
    assertFalse(catalog.requestAfterReadback(context, "PRICES", confirmed));
    assertEquals(1, runCount());
    owner
        .sql(
            "UPDATE marketplace_sync_run SET state='PUBLISHED',completed_at=clock_timestamp() WHERE"
                + " id=:id")
        .param("id", old)
        .update();
    assertTrue(catalog.requestAfterReadback(context, "PRICES", confirmed));
    assertTrue(catalog.requestAfterReadback(context, "PRICES", confirmed));
    assertEquals(2, runCount());
    assertTrue(
        owner
            .sql(
                "SELECT min(started_at)>=:confirmed FROM marketplace_sync_run WHERE"
                    + " account_id=:account AND id<>:old")
            .param("confirmed", Timestamp.from(confirmed))
            .param("account", scope.accountId())
            .param("old", old)
            .query(Boolean.class)
            .single());
    owner
        .sql("UPDATE access_membership SET active=false WHERE organization_id=:org")
        .param("org", scope.organizationId())
        .update();
    assertEquals(
        403,
        assertThrows(
                BusinessException.class,
                () -> catalog.requestAfterReadback(context, "PROMOTIONS", confirmed))
            .status());
    assertEquals(2, runCount());
  }

  @Test
  void aTerminalCatalogPageWithMissingDeclaredItemsCannotAdvanceOrArchiveExistingOffers()
      throws Exception {
    UUID baseline = run("CATALOG", "PUBLISH", Instant.now().minusSeconds(60));
    UUID original = offer(baseline, "old", "old-sku");
    UUID run = run("CATALOG", "CATALOG", Instant.now());
    sourcePage(
        run,
        0,
        VendorMethod.OZON_CATALOG,
        """
        {"result":{"items":[{"product_id":"101","offer_id":"sku-101"}],
          "total_items":2,"last_id":""}}
        """);
    assertEquals(
        "SOURCE_TOTAL_MISMATCH",
        assertThrows(
                BusinessException.class, () -> SourceJobSteps.executeStep(catalog, context(run)))
            .code());
    assertEquals(
        "SOURCE_TOTAL_MISMATCH",
        owner
            .sql("SELECT reason FROM marketplace_sync_run WHERE id=:id")
            .param("id", run)
            .query(String.class)
            .single());
    assertFalse(
        owner
            .sql("SELECT archived FROM marketplace_offer WHERE id=:id")
            .param("id", original)
            .query(Boolean.class)
            .single());
    assertEquals(
        baseline,
        owner
            .sql("SELECT publication_id FROM marketplace_offer WHERE id=:id")
            .param("id", original)
            .query(UUID.class)
            .single());
  }

  @Test
  void repeatedProductsAcrossPagesDoNotSatisfyTheDeclaredCatalogTotal() throws Exception {
    UUID run = run("CATALOG", "CATALOG", Instant.now());
    sourcePage(
        run,
        0,
        VendorMethod.OZON_CATALOG,
        """
        {"result":{"items":[{"product_id":"101","offer_id":"sku-101"}],
          "total_items":2,"last_id":"next"}}
        """);
    sourcePage(
        run,
        1,
        VendorMethod.OZON_CATALOG,
        """
        {"result":{"items":[{"product_id":"101","offer_id":"sku-101"}],
          "total_items":2,"last_id":""}}
        """);
    assertEquals("WAITING", SourceJobSteps.executeStep(catalog, context(run)).state());
    assertEquals(
        "SOURCE_TOTAL_MISMATCH",
        assertThrows(
                BusinessException.class, () -> SourceJobSteps.executeStep(catalog, context(run)))
            .code());
    assertEquals(
        "SOURCE_TOTAL_MISMATCH",
        owner
            .sql("SELECT reason FROM marketplace_sync_run WHERE id=:id")
            .param("id", run)
            .query(String.class)
            .single());
  }

  @Test
  void aCompleteEmptyPriceFeedCannotRepublishCopiedOldPricesAsFresh() throws Exception {
    UUID baseline = run("CATALOG", "PUBLISH", Instant.now().minusSeconds(60));
    UUID original = offer(baseline, "101", "sku-101");
    UUID job = catalog.request(scope, "PRICES", UUID.randomUUID());
    UUID run =
        owner
            .sql("SELECT id FROM marketplace_sync_run WHERE job_id=:job")
            .param("job", job)
            .query(UUID.class)
            .single();
    sourcePage(
        run, 0, VendorMethod.OZON_PRICES, "{\"items\":[],\"cursor\":\"\",\"total_items\":0}");
    sourcePage(
        run, 1, VendorMethod.OZON_PRICES, "{\"items\":[],\"cursor\":\"\",\"total_items\":0}");
    assertEquals("WAITING", SourceJobSteps.executeStep(catalog, context(run)).state());
    assertEquals("WAITING", SourceJobSteps.executeStep(catalog, context(run)).state());
    assertEquals(
        "INCOMPLETE_CATALOG",
        assertThrows(
                BusinessException.class, () -> SourceJobSteps.executeStep(catalog, context(run)))
            .code());
    assertEquals(
        baseline,
        owner
            .sql("SELECT publication_id FROM marketplace_offer WHERE id=:id")
            .param("id", original)
            .query(UUID.class)
            .single());
  }

  @Test
  void catalogAttributesAndPriceEvidencePublishTogetherAndRemainInImmutableObservation()
      throws Exception {
    UUID run = run("CATALOG", "ATTRIBUTES", Instant.now());
    owner
        .sql(
            """
            INSERT INTO marketplace_offer_stage(organization_id,account_id,run_id,external_id,
              sku,name,info_complete) VALUES (:org,:account,:run,'101','sku-101','Initial',true)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", run)
        .update();
    sourcePage(
        run,
        0,
        VendorMethod.OZON_ATTRIBUTES,
        """
        {"result":[{"id":101,"offer_id":"sku-101","sku":"9007199254740993",
          "name":"Measured item","height":10,"width":140,"depth":210,"dimension_unit":"mm",
          "weight":50,"weight_unit":"g","primary_image":"",
          "attributes":[{"id":85,"values":[{"value":"Brand"}]}]}],"total":"1","last_id":"last"}
        """);
    var fetch = context(run);
    assertEquals("canonicalization", catalog.execute(fetch).nextLane());
    doThrow(new AssertionError("Worker stopped after committed attribute staging"))
        .when(capabilities)
        .confirmRead(eq(scope), eq(VendorMethod.OZON_ATTRIBUTES), any());
    var parsing = SourceJobSteps.inLane(fetch, "canonicalization");
    assertThrows(AssertionError.class, () -> catalog.execute(parsing));
    doNothing().when(capabilities).confirmRead(eq(scope), eq(VendorMethod.OZON_ATTRIBUTES), any());
    assertEquals("fetch", catalog.execute(parsing).nextLane());
    verify(gateway, times(1))
        .executePage(
            eq(scope),
            eq(run),
            eq(0),
            eq(VendorMethod.OZON_ATTRIBUTES),
            anyString(),
            isNull(),
            any(),
            anyInt());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_offer WHERE account_id=:account")
            .param("account", scope.accountId())
            .query(Integer.class)
            .single());
    sourcePage(
        run,
        1,
        VendorMethod.OZON_PRICES,
        """
        {"items":[{"product_id":101,"offer_id":"sku-101","price":{"price":"123.45","vat":"0.2"},
          "commissions":{"sales_percent_fbo":15},"price_indexes":{"color_index":"GREEN",
            "ozon_index_data":{"price_index_value":"0.95"}}}],"cursor":"","total_items":1}
        """);
    assertEquals("WAITING", SourceJobSteps.executeStep(catalog, context(run)).state());
    sourcePage(
        run, 2, VendorMethod.OZON_PRICES, "{\"items\":[],\"cursor\":\"\",\"total_items\":0}");
    assertEquals("WAITING", SourceJobSteps.executeStep(catalog, context(run)).state());
    assertEquals("SUCCEEDED", SourceJobSteps.executeStep(catalog, context(run)).state());
    assertEquals(
        "9007199254740993",
        owner
            .sql("SELECT vendor_sku FROM marketplace_offer WHERE account_id=:account")
            .param("account", scope.accountId())
            .query(String.class)
            .single());
    assertEquals(
        "mm",
        owner
            .sql(
                "SELECT catalog_fields->>'dimension_unit' FROM marketplace_offer WHERE"
                    + " account_id=:account")
            .param("account", scope.accountId())
            .query(String.class)
            .single());
    assertEquals(
        "0.95",
        owner
            .sql(
                "SELECT supplier_price_data#>>'{price_indexes,ozon_index_data,price_index_value}'"
                    + " FROM marketplace_offer_observation WHERE source_run_id=:run")
            .param("run", run)
            .query(String.class)
            .single());
    assertEquals(
        1,
        owner
            .sql("SELECT count(*) FROM marketplace_offer_observation WHERE source_run_id=:run")
            .param("run", run)
            .query(Integer.class)
            .single());
    assertEquals("SUCCEEDED", SourceJobSteps.executeStep(catalog, context(run)).state());
    assertEquals(
        1,
        owner
            .sql("SELECT count(*) FROM marketplace_offer_observation WHERE source_run_id=:run")
            .param("run", run)
            .query(Integer.class)
            .single());
  }

  @Test
  void ozonActionsKeepEligibilityAndParticipationSeparateWithoutInventingScopeCompleteness()
      throws Exception {
    UUID baseline = run("CATALOG", "PUBLISH", Instant.now());
    offer(baseline, "101", "sku-101");
    UUID job = catalog.request(scope, "PROMOTIONS", UUID.randomUUID());
    UUID run =
        owner
            .sql("SELECT id FROM marketplace_sync_run WHERE job_id=:job")
            .param("job", job)
            .query(UUID.class)
            .single();
    sourcePage(
        run,
        0,
        VendorMethod.OZON_ACTIONS,
        """
        {"result":[{"id":321,"title":"Action","action_type":"DISCOUNT",
          "date_start":"2026-10-01T00:00:00Z","date_end":"2026-10-31T00:00:00Z",
          "auto_add_dates":["2026-10-10T00:00:00Z"]}]}
        """);
    sourcePage(
        run,
        1,
        VendorMethod.OZON_ACTION_CANDIDATES,
        """
        {"products":[{"id":101,"price":{"amount":"100","currency":"RUB"},
          "action_price":{"amount":"60","currency":"RUB"},
          "max_action_price":{"amount":"80","currency":"RUB"}}],"last_id":"","total":1}
        """);
    sourcePage(
        run,
        2,
        VendorMethod.OZON_ACTION_PRODUCTS,
        """
        {"products":[{"id":101,"price":{"amount":"100","currency":"RUB"},
          "action_price":{"amount":"55","currency":"RUB"},
          "max_action_price":{"amount":"70","currency":"RUB"},
          "add_mode":"SELLER","stock":7,"min_stock":3}],"last_id":"","total":1}
        """);
    var handler =
        new PromotionSyncHandler(
            jdbc,
            transactions,
            authorization,
            connections,
            gateway,
            capabilities,
            catalog,
            jobs,
            files,
            JSON,
            outbox,
            new AuditService(jdbc),
            Clock.systemUTC(),
            snapshots);
    for (int page = 0; page < 3; page++) {
      assertEquals("WAITING", SourceJobSteps.executeStep(handler, context(run)).state());
      assertEquals(
          0,
          owner
              .sql("SELECT count(*) FROM marketplace_promotion_offer WHERE account_id=:account")
              .param("account", scope.accountId())
              .query(Integer.class)
              .single());
    }
    assertEquals("SUCCEEDED", SourceJobSteps.executeStep(handler, context(run)).state());
    assertEquals(
        "MANUAL",
        owner
            .sql("SELECT status FROM marketplace_promotion_offer WHERE account_id=:account")
            .param("account", scope.accountId())
            .query(String.class)
            .single());
    assertEquals(
        "7",
        owner
            .sql(
                "SELECT participation_scope#>>'{participant,stock}' FROM"
                    + " marketplace_promotion_offer WHERE account_id=:account")
            .param("account", scope.accountId())
            .query(String.class)
            .single());
    assertFalse(
        owner
            .sql("SELECT complete FROM marketplace_promotion_offer WHERE account_id=:account")
            .param("account", scope.accountId())
            .query(Boolean.class)
            .single());
    assertEquals(
        "INCOMPLETE",
        owner
            .sql(
                "SELECT status FROM marketplace_source WHERE account_id=:account AND"
                    + " source_type='PROMOTIONS'")
            .param("account", scope.accountId())
            .query(String.class)
            .single());
  }

  @Test
  void ozonHistoryReadsMissingParentOnceAndNeverAddsShipmentsToOriginalDemand() throws Exception {
    UUID job =
        catalog.requestHistory(
            scope, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2), UUID.randomUUID());
    UUID run =
        owner
            .sql("SELECT id FROM marketplace_sync_run WHERE job_id=:job")
            .param("job", job)
            .query(UUID.class)
            .single();
    sourcePage(
        run,
        0,
        VendorMethod.OZON_FBS_ORDERS,
        """
        {"postings":[{"posting_number":"100-1-2","parent_posting_number":"100-1-1",
          "order_id":123,"order_number":"100-1","status":"delivered",
          "in_process_at":"2026-10-01T10:00:00Z","products":[{"offer_id":"sku","quantity":2}]}],
          "cursor":"final-cursor","has_next":false}
        """);
    sourcePage(
        run,
        1,
        VendorMethod.OZON_FBO_ORDERS,
        "{\"postings\":[],\"has_next\":false,\"cursor\":\"\"}");
    sourcePage(
        run,
        2,
        VendorMethod.OZON_FBS_ORDER,
        """
        {"result":{"posting_number":"100-1-1","parent_posting_number":"",
          "order_id":123,"order_number":"100-1","status":"cancelled",
          "in_process_at":"2026-09-30T10:00:00Z","products":[{"offer_id":"sku","quantity":2}]}}
        """);
    var handler =
        new HistorySyncHandler(
            jdbc,
            transactions,
            authorization,
            connections,
            catalog,
            gateway,
            capabilities,
            files,
            jobs,
            JSON,
            outbox,
            new AuditService(jdbc),
            Clock.systemUTC());
    for (int page = 0; page < 2; page++) {
      assertEquals("WAITING", SourceJobSteps.executeStep(handler, context(run)).state());
    }
    var fetch = context(run);
    assertEquals("canonicalization", handler.execute(fetch).nextLane());
    doThrow(new AssertionError("Worker stopped after committing the parent posting"))
        .when(capabilities)
        .confirmRead(eq(scope), eq(VendorMethod.OZON_FBS_ORDER), any());
    var parsing = SourceJobSteps.inLane(fetch, "canonicalization");
    assertThrows(AssertionError.class, () -> handler.execute(parsing));
    doNothing().when(capabilities).confirmRead(eq(scope), eq(VendorMethod.OZON_FBS_ORDER), any());
    assertEquals("fetch", handler.execute(parsing).nextLane());
    verify(gateway, times(1))
        .executePage(
            eq(scope),
            eq(run),
            eq(2),
            eq(VendorMethod.OZON_FBS_ORDER),
            anyString(),
            isNull(),
            any(),
            anyInt());
    assertEquals("BLOCKED", SourceJobSteps.executeStep(handler, context(run)).state());
    assertEquals(
        2,
        owner
            .sql("SELECT count(*) FROM marketplace_shipment_observation WHERE source_run_id=:run")
            .param("run", run)
            .query(Integer.class)
            .single());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_demand_observation WHERE account_id=:account")
            .param("account", scope.accountId())
            .query(Integer.class)
            .single());
    assertEquals(
        "OZON_ORIGINAL_ORDER_SCOPE_UNCONFIRMED",
        owner
            .sql(
                "SELECT reason FROM marketplace_source WHERE account_id=:account AND"
                    + " source_type='HISTORY'")
            .param("account", scope.accountId())
            .query(String.class)
            .single());
    assertEquals("BLOCKED", SourceJobSteps.executeStep(handler, context(run)).state());
    assertEquals(
        2,
        owner
            .sql("SELECT count(*) FROM marketplace_shipment_observation WHERE source_run_id=:run")
            .param("run", run)
            .query(Integer.class)
            .single());
  }

  @Test
  void fboAnalyticsRequestsStringSkusAndPreservesUnknownReservationCoverage() throws Exception {
    UUID catalogRun = run("CATALOG", "CATALOG", Instant.now());
    UUID offer = offer(catalogRun, "product", "123456789");
    UUID stockRun = run("STOCKS", "FBO", Instant.now());
    UUID rawId = raw();
    var raw =
        new StoredFileService.FileRecord(
            rawId,
            rawId.toString(),
            "version",
            "application/json",
            null,
            "STORED",
            0,
            "0".repeat(64),
            true,
            scope);
    var response = new OutboundGateway.Response(null, 200, raw, "identity", null, 1);
    when(gateway.executePage(
            eq(scope),
            eq(stockRun),
            eq(0),
            eq(VendorMethod.OZON_FBO_AVAILABLE),
            anyString(),
            isNull(),
            isNull(),
            eq(1)))
        .thenAnswer(
            call -> {
              assertEquals(
                  "/v1/analytics/stocks",
                  call.getArgument(3, VendorMethod.class).endpoint("123", null, null).getPath());
              var skus =
                  JsonParser.parseString(call.getArgument(4, String.class))
                      .getAsJsonObject()
                      .getAsJsonArray("skus");
              assertEquals(1, skus.size());
              assertTrue(skus.get(0).getAsJsonPrimitive().isString());
              assertEquals("123456789", skus.get(0).getAsString());
              return response;
            });
    String payload =
        """
        {"items":[{"sku":123456789,"warehouse_id":10,"warehouse_name":"FBO official",
          "available_stock_count":7,"valid_stock_count":200,"outbound_pending_delivery":3,
          "reserved_stock_count":4}]}
        """;
    when(gateway.open(response))
        .thenReturn(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
    assertEquals("WAITING", SourceJobSteps.executeStep(stockHandler(), context(stockRun)).state());
    assertTrue(
        owner
            .sql(
                """
                SELECT free_quantity=7 AND NOT complete AND obligations_covered IS NULL
                  AND name='FBO official' AND physical_quantity IS NULL
                FROM marketplace_stock_stage
                WHERE run_id=:run AND offer_id=:offer
                """)
            .param("run", stockRun)
            .param("offer", offer)
            .query(Boolean.class)
            .single());
  }

  @Test
  void oneThousandPhysicalPoolsPersistInTransactionsOfAtMostTwoHundredRows() throws Exception {
    UUID catalogRun = run("CATALOG", "CATALOG", Instant.now());
    owner
        .sql(
            "UPDATE marketplace_sync_run SET state='PUBLISHED',completed_at=clock_timestamp() WHERE"
                + " id=:id")
        .param("id", catalogRun)
        .update();
    UUID offer = offer(catalogRun, "product", "sku");
    UUID stockRun = run("STOCKS", "BASIC", Instant.now());
    UUID rawId = raw();
    var raw =
        new StoredFileService.FileRecord(
            rawId,
            rawId.toString(),
            "version",
            "application/json",
            null,
            "STORED",
            0,
            "0".repeat(64),
            true,
            scope);
    var response = new OutboundGateway.Response(null, 200, raw, "identity", null, 1);
    when(gateway.executePage(
            eq(scope),
            eq(stockRun),
            eq(0),
            eq(VendorMethod.OZON_STOCKS),
            anyString(),
            isNull(),
            isNull(),
            anyInt()))
        .thenReturn(response);
    String records =
        IntStream.range(0, 1000)
            .mapToObj(
                index ->
                    "{\"type\":\"fbs\",\"warehouse_id\":\"w"
                        + index
                        + "\",\"free_stock\":10,\"reserved\":2}")
            .collect(Collectors.joining(","));
    String payload =
        "{\"items\":[{\"product_id\":\"product\",\"stocks\":[" + records + "]}],\"cursor\":\"\"}";
    when(gateway.open(response))
        .thenReturn(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
    StockSyncHandler handler = stockHandler();
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context(stockRun)).state());
    assertEquals(
        1000,
        owner
            .sql(
                "SELECT count(*) FROM marketplace_stock_stage WHERE run_id=:run AND"
                    + " offer_id=:offer")
            .param("run", stockRun)
            .param("offer", offer)
            .query(Integer.class)
            .single());
    assertEquals(
        200,
        owner
            .sql(
                "SELECT max(n) FROM (SELECT count(*) n FROM marketplace_stock_stage WHERE"
                    + " run_id=:run GROUP BY test_transaction) sizes")
            .param("run", stockRun)
            .query(Integer.class)
            .single());
    assertEquals(
        5,
        owner
            .sql(
                "SELECT count(DISTINCT test_transaction) FROM marketplace_stock_stage WHERE"
                    + " run_id=:run")
            .param("run", stockRun)
            .query(Integer.class)
            .single());
  }

  @Test
  void explicitFbsStockPagesPublishPhysicalFreeAndReservedWithoutArithmeticInference()
      throws Exception {
    UUID catalogRun = run("CATALOG", "CATALOG", Instant.now());
    UUID offer = offer(catalogRun, "17", "sku");
    owner
        .sql("UPDATE marketplace_offer SET sku='sku',vendor_sku='999' WHERE id=:id")
        .param("id", offer)
        .update();
    UUID stockRun = run("STOCKS", "FBS", Instant.now());
    for (int page = 0; page < 3; page++) {
      UUID rawId = raw();
      var raw =
          new StoredFileService.FileRecord(
              rawId,
              rawId.toString(),
              "version",
              "application/json",
              null,
              "STORED",
              0,
              "0".repeat(64),
              true,
              scope);
      var response = new OutboundGateway.Response(null, 200, raw, "identity", null, 1);
      when(gateway.executePage(
              eq(scope),
              eq(stockRun),
              eq(page),
              eq(page == 2 ? VendorMethod.OZON_FBO_AVAILABLE : VendorMethod.OZON_FBS_STOCKS),
              anyString(),
              isNull(),
              page == 1 ? eq("next") : isNull(),
              anyInt()))
          .thenReturn(response);
      String body =
          page == 2
              ? "{\"items\":[]}"
              : """
              {"products":[{"offer_id":"sku","product_id":17,"sku":999,"warehouse_id":%d,
                "warehouse_name":"Warehouse %d","free_stock":8,"reserved":2,"present":13}],
                "has_next":%s,"cursor":"%s"}
              """
                  .formatted(700 + page, 700 + page, page == 0, "next");
      when(gateway.open(response))
          .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }
    var handler = stockHandler();
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context(stockRun)).state());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_stock_pool WHERE account_id=:id")
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context(stockRun)).state());
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context(stockRun)).state());
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context(stockRun)).state());
    assertEquals("SUCCEEDED", SourceJobSteps.executeStep(handler, context(stockRun)).state());
    assertEquals(
        2,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_stock_pool WHERE account_id=:account AND offer_id=:offer
                  AND complete AND physical_quantity=13 AND free_quantity=8 AND obligations_covered=2
                  AND name IN ('Warehouse 700','Warehouse 701')
                """)
            .param("account", scope.accountId())
            .param("offer", offer)
            .query(Integer.class)
            .single());
    assertEquals(
        2,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_stock_observation WHERE source_run_id=:run AND physical_quantity=13
                """)
            .param("run", stockRun)
            .query(Integer.class)
            .single());
    var reads = new MarketplaceReadService(jdbc, authorization, JSON, Clock.systemUTC());
    var directory = transactions.run(scope, () -> reads.warehouses(scope, 0, 50, ""));
    assertEquals(2, directory.total());
    assertTrue(
        directory.items().stream()
            .allMatch(
                warehouse ->
                    warehouse.model().equals("OZON_FBS")
                        && warehouse.models().equals(List.of("FBS"))
                        && warehouse.campaignId() == null
                        && warehouse.groupId() == null));
    assertEquals(
        2,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_warehouse w JOIN marketplace_raw_page raw
                  ON raw.run_id=w.discovered_run AND raw.file_id=w.raw_file_id
                WHERE w.discovered_run=:run AND w.external_id=(700+raw.page_number)::text
                  AND w.name='Warehouse '||w.external_id AND w.warehouse_model='OZON_FBS'
                """)
            .param("run", stockRun)
            .query(Integer.class)
            .single());
    assertEquals(
        Set.copyOf(directory.items().stream().map(MarketplaceReadService.Warehouse::id).toList()),
        Set.copyOf(warehouseLinks(reads).stream().map(WarehouseLink::warehouseId).toList()));
    var inventory =
        new StockReadService(
            jdbc,
            authorization,
            reads,
            new ResourceService(jdbc, reads, Clock.systemUTC(), outbox));
    UUID selectedWarehouse = directory.items().getFirst().id();
    var query =
        new TableExportSource.Query(
            "", Map.of("warehouseId", selectedWarehouse.toString()), "warehouse,asc", 0, 50);
    var stockPage = transactions.run(scope, () -> inventory.page(scope, query));
    assertEquals(1L, stockPage.total());
    var stock = stockPage.items().getFirst();
    assertEquals(directory.items().getFirst().name(), stock.warehouse());
    assertEquals(0, new BigDecimal("13").compareTo(stock.physical()));
    assertEquals(0, new BigDecimal("8").compareTo(stock.available()));
    transactions.run(
        scope,
        () -> {
          var projection = inventory.projection(scope, query, List.of());
          assertEquals(
              List.of(stock.id()),
              jdbc.sql(projection.sql())
                  .params(projection.parameters())
                  .query((row, index) -> row.getObject("canonical_id", UUID.class))
                  .list());
          return true;
        });
  }

  @Test
  void missingDetailedFbsCoverageKeepsAggregateEvidenceAndNeverPublishesSyntheticZero()
      throws Exception {
    UUID catalogRun = run("CATALOG", "CATALOG", Instant.now());
    offer(catalogRun, "17", "999");
    UUID stockRun = run("STOCKS", "BASIC", Instant.now());
    owner
        .sql(
            "INSERT INTO marketplace_source(id,organization_id,account_id,source_type)"
                + " VALUES(gen_random_uuid(),:org,:account,'STOCKS')")
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .update();
    sourcePage(
        stockRun,
        0,
        VendorMethod.OZON_STOCKS,
        """
        {"items":[{"product_id":17,"stocks":[{"type":"fbs","present":8,"reserved":2}]}],"cursor":""}
        """);
    sourcePage(
        stockRun,
        1,
        VendorMethod.OZON_FBS_STOCKS,
        "{\"products\":[],\"has_next\":false,\"cursor\":\"\"}");
    sourcePage(stockRun, 2, VendorMethod.OZON_FBO_AVAILABLE, "{\"items\":[]}");
    var handler = stockHandler();
    for (int attempt = 0; attempt < 4; attempt++) {
      assertEquals("WAITING", SourceJobSteps.executeStep(handler, context(stockRun)).state());
    }
    var outcome = SourceJobSteps.executeStep(handler, context(stockRun));
    assertEquals("BLOCKED", outcome.state());
    assertEquals("FBS_WAREHOUSE_COVERAGE_UNKNOWN", outcome.reason());
    assertEquals(
        "INCOMPLETE",
        owner
            .sql("SELECT state FROM marketplace_sync_run WHERE id=:id")
            .param("id", stockRun)
            .query(String.class)
            .single());
    assertEquals(
        "INCOMPLETE",
        owner
            .sql(
                "SELECT status FROM marketplace_source WHERE account_id=:id AND"
                    + " source_type='STOCKS'")
            .param("id", scope.accountId())
            .query(String.class)
            .single());
    assertEquals(
        1,
        owner
            .sql(
                "SELECT count(*) FROM marketplace_stock_stage WHERE run_id=:id AND"
                    + " warehouse_type='FBS_AGGREGATE'")
            .param("id", stockRun)
            .query(Integer.class)
            .single());
    assertEquals(
        3,
        owner
            .sql("SELECT count(*) FROM marketplace_raw_page WHERE run_id=:id AND parsed")
            .param("id", stockRun)
            .query(Integer.class)
            .single());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_stock_pool WHERE account_id=:id")
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_warehouse WHERE account_id=:id")
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
  }

  @Test
  void identicalSupplierWarehouseNumbersRemainSeparateAcrossOzonModelsAndAccounts()
      throws Exception {
    UUID catalogRun = run("CATALOG", "CATALOG", Instant.now());
    UUID offer = offer(catalogRun, "17", "999");
    UUID stockRun = run("STOCKS", "FBS", Instant.now());
    sourcePage(
        stockRun,
        0,
        VendorMethod.OZON_FBS_STOCKS,
        """
        {"products":[{"offer_id":"17","warehouse_id":700,"warehouse_name":"Seller warehouse",
          "free_stock":8,"reserved":2,"present":13}],"has_next":false,"cursor":""}
        """);
    sourcePage(
        stockRun,
        1,
        VendorMethod.OZON_FBO_AVAILABLE,
        """
        {"items":[{"sku":999,"warehouse_id":700,"warehouse_name":"Ozon warehouse",
          "available_stock_count":7}]}
        """);
    var handler = stockHandler();
    for (int attempt = 0; attempt < 3; attempt++) {
      assertEquals("WAITING", SourceJobSteps.executeStep(handler, context(stockRun)).state());
    }
    assertEquals("BLOCKED", SourceJobSteps.executeStep(handler, context(stockRun)).state());
    Scope originalScope = scope;
    scope = new Scope(scope.organizationId(), UUID.randomUUID(), scope.subjectId());
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_account(id,organization_id,marketplace,external_id,name,timezone)"
                + " VALUES(:id,:org,'OZON',:external,'Other account','Europe/Moscow')")
        .param("id", scope.accountId())
        .param("org", scope.organizationId())
        .param("external", scope.accountId().toString())
        .update();
    UUID foreignRun = run("STOCKS", "FBS", Instant.now());
    UUID foreignRaw = raw();
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_warehouse(id,organization_id,account_id,external_id,name,warehouse_model,models,discovered_run,raw_file_id,observed_at)"
                + " VALUES(gen_random_uuid(),:org,:account,'700','Foreign','OZON_FBS','[{\"placementType\":\"FBS\"}]',:run,:raw,clock_timestamp())")
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", foreignRun)
        .param("raw", foreignRaw)
        .update();
    scope = originalScope;
    var reads = new MarketplaceReadService(jdbc, authorization, JSON, Clock.systemUTC());
    var directory = transactions.run(scope, () -> reads.warehouses(scope, 0, 50, "700"));
    assertEquals(2, directory.total());
    assertEquals(
        Set.of("OZON_FBS", "OZON_FBO"),
        Set.copyOf(
            directory.items().stream().map(MarketplaceReadService.Warehouse::model).toList()));
    assertEquals(
        Set.of("Seller warehouse", "Ozon warehouse"),
        Set.copyOf(
            directory.items().stream().map(MarketplaceReadService.Warehouse::name).toList()));
    var links = warehouseLinks(reads);
    for (var warehouse : directory.items()) {
      UUID poolId =
          owner
              .sql(
                  "SELECT id FROM marketplace_stock_pool WHERE offer_id=:offer AND"
                      + " warehouse_type=:type")
              .param("offer", offer)
              .param("type", warehouse.model().equals("OZON_FBS") ? "FBS" : "FBO")
              .query(UUID.class)
              .single();
      assertTrue(links.contains(new WarehouseLink(offer, poolId, warehouse.id())));
    }
    assertEquals(2, links.size());
    assertEquals(
        2,
        owner
            .sql(
                "SELECT count(*) FROM marketplace_warehouse w JOIN marketplace_raw_page raw ON"
                    + " raw.run_id=w.discovered_run AND raw.file_id=w.raw_file_id WHERE"
                    + " w.discovered_run=:run AND ((w.warehouse_model='OZON_FBS' AND"
                    + " raw.page_number=0) OR (w.warehouse_model='OZON_FBO' AND"
                    + " raw.page_number=1))")
            .param("run", stockRun)
            .query(Integer.class)
            .single());
  }

  private List<WarehouseLink> warehouseLinks(MarketplaceReadService reads) {
    return transactions.run(
        scope,
        () -> {
          var projection = reads.warehousePoolsProjection(scope);
          return jdbc.sql(projection.sql())
              .params(projection.parameters())
              .query(
                  (row, index) ->
                      new WarehouseLink(
                          row.getObject("offer_id", UUID.class),
                          row.getObject("pool_id", UUID.class),
                          row.getObject("warehouse_id", UUID.class)))
              .list();
        });
  }

  private record WarehouseLink(UUID offerId, UUID poolId, UUID warehouseId) {}

  @Test
  void aggregateAndItsDetailedWarehousesNeverPublishAsIndependentPools() {
    UUID catalogRun = run("CATALOG", "CATALOG", Instant.now());
    UUID offer = offer(catalogRun, "product", "sku");
    UUID stockRun = run("STOCKS", "FBO", Instant.now());
    owner
        .sql("UPDATE marketplace_sync_run SET batch_after='zzzz' WHERE id=:id")
        .param("id", stockRun)
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_stock_stage(organization_id,account_id,run_id,external_id,offer_id,name,
              warehouse_type,free_quantity,obligations_covered,observed_at,complete)
            SELECT :org,:account,:run,external,:offer,'FBS','FBS',10,2,clock_timestamp(),true
            FROM (VALUES ('fbs:aggregate:product'),('fbs:w1:product')) ids(external)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", stockRun)
        .param("offer", offer)
        .update();
    BusinessException failure =
        assertThrows(
            BusinessException.class,
            () -> SourceJobSteps.executeStep(stockHandler(), context(stockRun)));
    assertEquals("STOCK_POOL_OVERLAP", failure.code());
    assertEquals(
        "INCOMPLETE",
        owner
            .sql("SELECT state FROM marketplace_sync_run WHERE id=:id")
            .param("id", stockRun)
            .query(String.class)
            .single());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_stock_pool WHERE account_id=:account")
            .param("account", scope.accountId())
            .query(Integer.class)
            .single());
  }

  @Test
  void financialPagesKeepIndependentComponentsAndRequireCoverageOfEveryCampaign() {
    owner
        .sql("UPDATE marketplace_account SET marketplace='YANDEX' WHERE id=:id")
        .param("id", scope.accountId())
        .update();
    UUID discovery = run("STOCKS", "COMPLETE", Instant.now());
    owner
        .sql("UPDATE marketplace_sync_run SET state='PUBLISHED' WHERE id=:id")
        .param("id", discovery)
        .update();
    addCampaign("123", discovery);
    owner
        .sql(
            """
            INSERT INTO marketplace_source(id,organization_id,account_id,source_type,status,publication_id)
            VALUES (:id,:org,:account,'STOCKS','READY',:run)
            """)
        .param("id", UUID.randomUUID())
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", discovery)
        .update();
    UUID rawId = raw();
    when(files.get(rawId))
        .thenReturn(
            new StoredFileService.FileRecord(
                rawId,
                rawId.toString(),
                "version",
                "application/zip",
                null,
                "STORED",
                0,
                "a".repeat(64),
                true,
                scope));
    UUID report = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO marketplace_report(id,organization_id,account_id,kind,date_from,date_until,
              state,generation_call_id,raw_file_id,generation_finished_at,row_count,financial_complete,campaign_id)
            VALUES (:id,:org,:account,'REALIZATION','2026-09-01','2026-09-30','PUBLISHED',:call,
              :raw,'2026-10-02T00:00:00Z',3,true,'123')
            """)
        .param("id", report)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("call", UUID.randomUUID())
        .param("raw", rawId)
        .update();
    var sale =
        JsonParser.parseString(
                """
                {"orderId":1,"yourSku":"sku","deliveryDate":"2026-09-20","deliveredCount":1,
                  "deliveredPriceSumWithVatAndDiscounts":80,"deliveredPriceSumWithVatAndNoDiscounts":100,
                  "deliveredDiscountSum":20}
                """)
            .getAsJsonObject();
    var compensation =
        JsonParser.parseString(
                """
                {"orderId":1,"yourSku":"sku","compensationDate":"2026-09-20","compensationAmount":100,
                  "decompensationDate":"2026-10-01","decompensationAmount":20}
                """)
            .getAsJsonObject();
    var first =
        RealizationReportCanonicalizer.canonicalize(
            report,
            1,
            "delivered",
            sale,
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 30),
            null,
            null,
            rawId);
    var next =
        RealizationReportCanonicalizer.canonicalize(
            report,
            2,
            "lost_items",
            compensation,
            LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 30),
            null,
            null,
            rawId);
    financialRow(report, 1, first);
    financialRow(report, 2, next);
    var sources = new FinancialSourceService(jdbc, authorization, JSON, files);
    transactions.run(
        scope,
        () -> {
          var page = sources.page(scope, report, 0, 2);
          assertEquals(
              List.of(1L, 2L),
              page.stream().map(FinancialSourceService.FinancialFact::rowNumber).toList());
          assertEquals("COMPENSATION", page.getLast().kind());
          var tail = sources.page(scope, report, 2, 2);
          assertEquals(1, tail.size());
          assertEquals("COMPENSATION_REVERSAL", tail.getFirst().kind());
          assertEquals(LocalDate.of(2026, 10, 1), tail.getFirst().accountingDate());
          assertTrue(sources.coverage(scope, YearMonth.of(2026, 9)).get("REVENUE"));
          assertFalse(sources.coverage(scope, YearMonth.of(2026, 9)).get("REFUND"));
          assertEquals(
              List.of(report),
              sources.publicationIds(scope, YearMonth.of(2026, 9), "REALIZATION", null, 10));
          return null;
        });
    addCampaign("456", discovery);
    assertFalse(
        transactions.run(
            scope, () -> sources.coverage(scope, YearMonth.of(2026, 9)).get("REVENUE")));
    Scope foreign = new Scope(scope.organizationId(), UUID.randomUUID(), scope.subjectId());
    assertEquals(
        404,
        assertThrows(
                BusinessException.class,
                () -> transactions.run(foreign, () -> sources.publication(foreign, report)))
            .status());
  }

  @Test
  void categoryTreePublishesConfirmedHierarchyAndRejectsPartialReplacement() throws Exception {
    UUID catalogRun = run("CATALOG", "PUBLISH", Instant.now().minusSeconds(10));
    UUID product = offer(catalogRun, "tree-product", "tree-sku");
    owner
        .sql("UPDATE marketplace_offer SET catalog_fields=CAST(:fields AS jsonb) WHERE id=:id")
        .param("fields", "{\"description_category_id\":20,\"type_id\":30}")
        .param("id", product)
        .update();
    var categories = new CategoryService(jdbc, categoryBatch, authorization, outbox);
    var handler =
        new CategorySyncHandler(
            jdbc,
            transactions,
            authorization,
            connections,
            gateway,
            capabilities,
            catalog,
            categories,
            jobs,
            files,
            JSON,
            outbox,
            Clock.systemUTC());
    var reads = new MarketplaceReadService(jdbc, authorization, JSON, Clock.systemUTC());
    UUID tree = run("CATEGORIES", "TREE", Instant.now());
    sourcePage(
        tree,
        0,
        VendorMethod.OZON_CATEGORIES,
        """
        {"result":[{"children":[{"children":[{"type_id":30,"type_name":"Кружки",
          "disabled":false,"children":[]}],"description_category_id":20,
          "category_name":"Посуда","disabled":false}],"description_category_id":10,
          "category_name":"Дом","disabled":false}]}
        """);
    assertEquals("canonicalization", handler.execute(categoryContext(tree, "fetch")).nextLane());
    assertEquals("SUCCEEDED", handler.execute(categoryContext(tree, "canonicalization")).state());
    var page = transactions.run(scope, () -> reads.categories(scope, 0, 50, "", null));
    assertEquals(3, page.total());
    var leaf =
        page.items().stream().filter(item -> item.kind().equals("TYPE")).findFirst().orElseThrow();
    assertEquals("20:30", leaf.externalId());
    var path = transactions.run(scope, () -> reads.categoryAncestors(scope, product));
    assertEquals(3, path.size());
    assertEquals(leaf.id(), path.getFirst());
    var names =
        transactions.run(
            scope, () -> reads.catalogNames(scope, Set.of(product), Set.of(leaf.id())));
    assertEquals("Кружки", names.categories().get(leaf.id()));
    assertEquals(Set.of(product), names.offers().keySet());
    assertEquals(Set.of(leaf.id()), names.categories().keySet());
    assertEquals(
        path.subList(1, 3),
        transactions.run(scope, () -> reads.categoryAncestry(scope, leaf.id())));
    handler.execute(categoryContext(tree, "canonicalization"));
    assertEquals(
        1L,
        transactions
            .run(scope, () -> reads.categories(scope, 0, 50, "Кружки", null))
            .items()
            .getFirst()
            .revision());

    UUID broken = run("CATEGORIES", "TREE", Instant.now().plusSeconds(1));
    sourcePage(
        broken,
        0,
        VendorMethod.OZON_CATEGORIES,
        """
        {"result":[{"description_category_id":10,"category_name":"Другой дом","disabled":false,
          "children":[{"description_category_id":20,"category_name":"Посуда","disabled":false},
            {"description_category_id":20,"category_name":"Конфликт","disabled":false}]}]}
        """);
    handler.execute(categoryContext(broken, "fetch"));
    handler.execute(categoryContext(broken, "canonicalization"));
    assertEquals(
        "INCOMPLETE",
        owner
            .sql("SELECT state FROM marketplace_sync_run WHERE id=:id")
            .param("id", broken)
            .query(String.class)
            .single());
    assertEquals(path, transactions.run(scope, () -> reads.categoryAncestors(scope, product)));
    assertEquals(
        "Дом",
        transactions
            .run(scope, () -> reads.categories(scope, 0, 50, "Дом", null))
            .items()
            .getFirst()
            .name());
    owner
        .sql(
            "UPDATE marketplace_category SET valid_until=clock_timestamp()-interval '1 second'"
                + " WHERE id=:id")
        .param("id", leaf.id())
        .update();
    assertEquals(
        "CATEGORY_UNKNOWN",
        assertThrows(
                BusinessException.class,
                () -> transactions.run(scope, () -> reads.categoryAncestors(scope, product)))
            .code());
    transactions.run(
        scope,
        () -> {
          var projection = reads.categoryPathsProjection(scope);
          assertEquals(
              1L,
              jdbc.sql(
                      "SELECT count(*) FROM ("
                          + projection.sql()
                          + ") category_path WHERE id=:id AND category_path IS NULL")
                  .params(projection.parameters())
                  .param("id", product)
                  .query(Long.class)
                  .single());
          return true;
        });
  }

  @Test
  void yandexCategoryBindingUsesOfficialMappingAndKeepsUnknownProductsUnknown() throws Exception {
    owner
        .sql("UPDATE marketplace_account SET marketplace='YANDEX' WHERE id=:id")
        .param("id", scope.accountId())
        .update();
    when(connections.current(scope))
        .thenReturn(
            new ConnectionService.Credentials(
                "YANDEX",
                "fixture",
                "repricer/fixture",
                1,
                "fixture",
                true,
                1,
                "READY",
                "fixture"));
    UUID catalogRun = run("CATALOG", "PUBLISH", Instant.now().minusSeconds(10));
    UUID known = offer(catalogRun, "mapped", "mapped");
    UUID unknown = offer(catalogRun, "unmapped", "unmapped");
    owner
        .sql("UPDATE marketplace_offer SET catalog_fields=CAST(:fields AS jsonb) WHERE id=:id")
        .param("fields", "{\"mapping\":{\"marketCategoryId\":20}}")
        .param("id", known)
        .update();
    var categories = new CategoryService(jdbc, categoryBatch, authorization, outbox);
    UUID tree = run("CATEGORIES", "TREE", Instant.now());
    sourcePage(
        tree,
        0,
        VendorMethod.YANDEX_CATEGORIES,
        """
        {"status":"OK","result":{"id":10,"name":"Дом","children":[{"id":20,"name":"Посуда","children":null}]}}
        """);
    var handler =
        new CategorySyncHandler(
            jdbc,
            transactions,
            authorization,
            connections,
            gateway,
            capabilities,
            catalog,
            categories,
            jobs,
            files,
            JSON,
            outbox,
            Clock.systemUTC());
    handler.execute(categoryContext(tree, "fetch"));
    handler.execute(categoryContext(tree, "canonicalization"));
    var reads = new MarketplaceReadService(jdbc, authorization, JSON, Clock.systemUTC());
    assertEquals(2, transactions.run(scope, () -> reads.categoryAncestors(scope, known)).size());
    assertEquals(
        "CATEGORY_UNKNOWN",
        assertThrows(
                BusinessException.class,
                () -> transactions.run(scope, () -> reads.categoryAncestors(scope, unknown)))
            .code());
  }

  @Test
  void categoryLaneRecoveryReusesRawAndPreservesCommittedParseBatches() throws Exception {
    UUID tree = run("CATEGORIES", "TREE", Instant.now());
    String content =
        "{\"result\":["
            + IntStream.rangeClosed(1, 250)
                .mapToObj(
                    id ->
                        "{\"description_category_id\":"
                            + id
                            + ",\"category_name\":\""
                            + "x".repeat(100)
                            + "\",\"children\":[]}")
                .collect(Collectors.joining(","))
            + "]}";
    sourcePage(tree, 0, VendorMethod.OZON_CATEGORIES, content);
    var categories = new CategoryService(jdbc, categoryBatch, authorization, outbox);
    var handler =
        new CategorySyncHandler(
            jdbc,
            transactions,
            authorization,
            connections,
            gateway,
            capabilities,
            catalog,
            categories,
            jobs,
            files,
            JSON,
            outbox,
            Clock.systemUTC());
    assertEquals("fetch", handler.execute(categoryContext(tree, "canonicalization")).nextLane());
    verify(gateway, never())
        .executePage(any(), any(), anyInt(), any(), anyString(), any(), any(), anyInt());
    assertEquals("canonicalization", handler.execute(categoryContext(tree, "fetch")).nextLane());
    verify(gateway, never()).open(any());
    assertEquals("canonicalization", handler.execute(categoryContext(tree, "fetch")).nextLane());
    verify(gateway, times(1))
        .executePage(any(), any(), anyInt(), any(), anyString(), any(), any(), anyInt());
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    when(gateway.open(any()))
        .thenAnswer(
            ignored ->
                new FilterInputStream(new ByteArrayInputStream(bytes)) {
                  private int remaining = 25000;

                  @Override
                  public int read(byte[] target, int offset, int length) throws IOException {
                    if (remaining == 0) {
                      throw new InterruptedIOException(
                          "Worker stopped after a committed category batch");
                    }
                    int count = super.read(target, offset, Math.min(length, remaining));
                    if (count > 0) {
                      remaining -= count;
                    }
                    return count;
                  }
                });
    assertThrows(
        InterruptedIOException.class,
        () -> handler.execute(categoryContext(tree, "canonicalization")));
    long staged =
        owner
            .sql("SELECT count(*) FROM marketplace_category_stage WHERE run_id=:id")
            .param("id", tree)
            .query(Long.class)
            .single();
    assertTrue(staged >= 100 && staged < 250);
    assertEquals(
        0L,
        owner
            .sql("SELECT count(*) FROM marketplace_category WHERE account_id=:account")
            .param("account", scope.accountId())
            .query(Long.class)
            .single());
    when(gateway.open(any())).thenAnswer(ignored -> new ByteArrayInputStream(bytes));
    assertEquals("SUCCEEDED", handler.execute(categoryContext(tree, "canonicalization")).state());
    assertEquals(
        250L,
        owner
            .sql("SELECT count(*) FROM marketplace_category WHERE account_id=:account AND current")
            .param("account", scope.accountId())
            .query(Long.class)
            .single());
    verify(gateway, times(1))
        .executePage(any(), any(), anyInt(), any(), anyString(), any(), any(), anyInt());
  }

  private JobContext categoryContext(UUID run, String lane) {
    return new JobContext(
        UUID.randomUUID(),
        scope,
        JSON.encode(new CatalogSyncService.SyncJob(run)),
        1,
        Instant.now().plusSeconds(120),
        0,
        lane);
  }

  private void financialRow(
      UUID report, long ordinal, List<FinancialSourceService.FinancialFact> facts) {
    owner
        .sql(
            """
            INSERT INTO marketplace_report_row(organization_id,account_id,report_id,sheet,row_number,
              data,content_hash,ordinal,canonical_fact,fact_start,fact_end)
            VALUES (:org,:account,:report,:sheet,1,'{}',:hash,:ordinal,CAST(:facts AS jsonb),:start,:end)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("report", report)
        .param("sheet", "sheet" + ordinal)
        .param("hash", "b".repeat(64))
        .param("ordinal", ordinal)
        .param("facts", JSON.encode(facts))
        .param("start", facts.getFirst().rowNumber())
        .param("end", facts.getLast().rowNumber())
        .update();
  }

  private void addCampaign(String id, UUID discovery) {
    owner
        .sql(
            """
            INSERT INTO marketplace_campaign(organization_id,account_id,external_id,placement_type,
              availability,discovered_run) VALUES (:org,:account,:id,'FBS','AVAILABLE',:run)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", id)
        .param("run", discovery)
        .update();
  }

  private StockSyncHandler stockHandler() {
    return new StockSyncHandler(
        jdbc,
        transactions,
        authorization,
        connections,
        catalog,
        gateway,
        capabilities,
        files,
        jobs,
        JSON,
        outbox,
        Clock.systemUTC(),
        snapshots);
  }

  private int runCount() {
    return owner
        .sql("SELECT count(*) FROM marketplace_sync_run WHERE account_id=:account")
        .param("account", scope.accountId())
        .query(Integer.class)
        .single();
  }

  private UUID run(String source, String phase, Instant started) {
    UUID id = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state,phase,started_at)
            VALUES (:id,:org,:account,:source,'FETCHING',:phase,:started)
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("source", source)
        .param("phase", phase)
        .param("started", Timestamp.from(started))
        .update();
    return id;
  }

  private UUID offer(UUID publication, String external, String vendorSku) {
    UUID id = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,vendor_sku,name,observed_at,publication_id)
            VALUES (:id,:org,:account,:external,:external,:sku,'Pool offer',clock_timestamp(),:publication)
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("external", external)
        .param("sku", vendorSku)
        .param("publication", publication)
        .update();
    return id;
  }

  private UUID raw() {
    UUID id = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,object_version,media_type,state)
            VALUES (:id,:org,:account,:subject,'RAW',:key,'version','application/json','STORED')
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("key", id.toString())
        .update();
    return id;
  }

  private void sourcePage(UUID run, int page, VendorMethod method, String content)
      throws Exception {
    UUID rawId = raw();
    var raw =
        new StoredFileService.FileRecord(
            rawId,
            rawId.toString(),
            "version",
            "application/json",
            null,
            "STORED",
            0,
            "0".repeat(64),
            true,
            scope);
    var response = new OutboundGateway.Response(null, 200, raw, "identity", null, 1);
    when(gateway.executePage(
            eq(scope), eq(run), eq(page), eq(method), anyString(), isNull(), any(), anyInt()))
        .thenReturn(response);
    when(gateway.open(argThat(value -> value != null && value.raw().id().equals(rawId))))
        .thenAnswer(ignored -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    when(files.get(rawId)).thenReturn(raw);
  }

  private JobContext context(UUID run) {
    return new JobContext(
        UUID.randomUUID(),
        scope,
        JSON.encode(new CatalogSyncService.SyncJob(run)),
        1,
        Instant.now().plusSeconds(120),
        0,
        "fetch");
  }
}
