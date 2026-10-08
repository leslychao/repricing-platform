package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AccessService;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.AutomationGrantService;
import ru.oritas.repricer.access.IdentityService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.app.read.OfferReadService;
import ru.oritas.repricer.app.read.StockReadService;
import ru.oritas.repricer.automation.decision.CurrentEconomicsService;
import ru.oritas.repricer.automation.decision.DecisionAuthorization;
import ru.oritas.repricer.automation.decision.DecisionContextAssembler;
import ru.oritas.repricer.automation.decision.DecisionContinuationService;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.automation.decision.DecisionValidityService;
import ru.oritas.repricer.automation.decision.QuantityComparisonService;
import ru.oritas.repricer.automation.execution.ExecutionService;
import ru.oritas.repricer.automation.policy.AssignmentBatchService;
import ru.oritas.repricer.automation.policy.AssignmentLifecycleService;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.automation.runtime.AutoService;
import ru.oritas.repricer.automation.runtime.RuntimeRules.ScenarioState;
import ru.oritas.repricer.automation.runtime.ScenarioService;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.economics.RiskLimitService;
import ru.oritas.repricer.economics.accounting.AccountingBasisService;
import ru.oritas.repricer.marketplace.CommercialGateway;
import ru.oritas.repricer.marketplace.MarketplaceCompetitorService;
import ru.oritas.repricer.marketplace.MarketplaceHistoryService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobControlConnections;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.ScopeExecutor;
import ru.oritas.repricer.platform.StoredFileService;
import ru.oritas.repricer.platform.TableExportSource;

class AssignmentBatchDatabaseTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("test-container-only");
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner transactions;
  private static PolicyService policies;
  private static AssignmentLifecycleService assignmentLifecycle;
  private static DecisionService decisions;
  private static AssignmentBatchService batches;
  private static JobRuntime jobs;
  private static AccessService access;
  private static OfferReadService offerReads;
  private static StockReadService stockReads;
  private static EconomicsService economics;
  private static QuantityComparisonService comparisons;
  private static MarketplaceCompetitorService competitors;
  private static CompetitorApplicationService competitorCommands;
  private static UUID company;
  private static UUID admin;
  private UUID account;

  @BeforeAll
  static void prepare() throws Exception {
    POSTGRES.start();
    try (Connection connection =
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
    Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), "repricer_migrator", "migration-test-only");
    var database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    try (Liquibase liquibase =
        new Liquibase("db/changelog/master.xml", new ClassLoaderResourceAccessor(), database)) {
      liquibase.update(new liquibase.Contexts());
    }
    var dataSource =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_api", "api-test-only");
    jdbc = JdbcClient.create(dataSource);
    var authorization = new AuthorizationService(jdbc);
    transactions =
        new ScopeTransactionRunner(
            jdbc, new DataSourceTransactionManager(dataSource), authorization);
    var json = new JsonCodec();
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "batchHandler",
        new JobHandler() {
          @Override
          public String type() {
            return "ASSIGNMENT_BATCH";
          }

          @Override
          public JobOutcome execute(JobContext context) {
            throw new IllegalStateException("The test drives captured transactions explicitly");
          }
        });
    jobs =
        new JobRuntime(
            jdbc,
            transactions,
            Clock.systemUTC(),
            beans.getBeanProvider(JobHandler.class),
            beans.getBeanProvider(OutboxService.class),
            beans.getBeanProvider(JobControlConnections.class),
            "api");
    var outbox = new OutboxService(jdbc, json, jobs, beans.getBeanProvider(OutboxRecipient.class));
    comparisons =
        new QuantityComparisonService(jdbc, authorization, json, Clock.systemUTC(), outbox);
    beans.registerSingleton("outbox", outbox);
    access =
        new AccessService(
            jdbc,
            transactions,
            authorization,
            new IdempotencyService(jdbc, json),
            new AuditService(jdbc),
            outbox,
            new IdentityService(jdbc));
    company = access.initManagedUsers("https://batch.invalid/realm", "admin", "test");
    admin = IdentityService.userId("https://batch.invalid/realm", "admin");
    var marketplace = new MarketplaceReadService(jdbc, authorization, json, Clock.systemUTC());
    policies =
        new PolicyService(jdbc, json, authorization, new AuditService(jdbc), outbox, marketplace);
    assignmentLifecycle =
        new AssignmentLifecycleService(
            policies,
            new ScenarioService(
                jdbc,
                json,
                Clock.systemUTC(),
                authorization,
                new AuditService(jdbc),
                outbox,
                policies,
                jobs,
                mock(RiskLimitService.class),
                mock(MarketplaceHistoryService.class),
                marketplace,
                mock(StoredFileService.class),
                new ResourceService(jdbc, marketplace, Clock.systemUTC(), outbox)));
    competitors =
        new MarketplaceCompetitorService(
            jdbc, authorization, new AuditService(jdbc), outbox, Clock.systemUTC());
    competitorCommands =
        new CompetitorApplicationService(
            transactions,
            authorization,
            new IdempotencyService(jdbc, json),
            competitors,
            marketplace);
    var validity =
        new DecisionValidityService(
            jdbc, json, Clock.systemUTC(), marketplace, competitors, policies);
    var current = new CurrentEconomicsService(jdbc, json, authorization, validity);
    var files = mock(StoredFileService.class);
    var execution =
        new ExecutionService(
            jdbc,
            json,
            Clock.systemUTC(),
            authorization,
            new AuditService(jdbc),
            outbox,
            files,
            jobs);
    decisions =
        new DecisionService(
            jdbc,
            json,
            Clock.systemUTC(),
            mock(ScopeExecutor.class),
            authorization,
            new AuditService(jdbc),
            outbox,
            files,
            mock(CommercialGateway.class),
            execution,
            jobs,
            mock(ScenarioService.class),
            validity,
            current,
            mock(AutoService.class),
            mock(QuantityComparisonService.class),
            mock(DecisionContinuationService.class));
    economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    offerReads =
        new OfferReadService(
            jdbc,
            authorization,
            marketplace,
            economics,
            policies,
            decisions,
            execution,
            current,
            new ResourceService(jdbc, marketplace, Clock.systemUTC(), outbox),
            json);
    stockReads =
        new StockReadService(
            jdbc,
            authorization,
            marketplace,
            new ResourceService(jdbc, marketplace, Clock.systemUTC(), outbox));
    batches =
        new AssignmentBatchService(
            jdbc,
            new NamedParameterJdbcTemplate(dataSource),
            policies,
            mock(DecisionService.class),
            authorization,
            json,
            jobs,
            outbox,
            new MarketplaceReadService(jdbc, authorization, json, Clock.systemUTC()));
  }

  @BeforeEach
  void createIsolatedAccount() {
    account = UUID.randomUUID();
    transactions.run(
        new Scope(company, null, admin),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'Batch test','Europe/Moscow')
                    """)
                .param("id", account)
                .param("org", company)
                .param("external", account.toString())
                .update());
  }

  @AfterAll
  static void stop() {
    if (jobs != null) {
      jobs.destroy();
    }
    POSTGRES.stop();
  }

  @Test
  void competitorHttpCommandsPreserveObservationHistoryAndRecheckAccessOnReplay() throws Exception {
    Scope owner = new Scope(company, account, admin);
    UUID offer = UUID.randomUUID();
    UUID publication = UUID.randomUUID();
    transactions.run(
        owner,
        () -> {
          jdbc.sql(
                  """
                  INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
                  VALUES (:id,:org,:account,'CATALOG','PUBLISHED')
                  """)
              .param("id", publication)
              .param("org", company)
              .param("account", account)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
                    category_path,observed_at,publication_id)
                  VALUES (:id,:org,:account,:sku,:sku,'Observed item','{}'::uuid[],clock_timestamp(),:publication)
                  """)
              .param("id", offer)
              .param("org", company)
              .param("account", account)
              .param("sku", offer.toString())
              .param("publication", publication)
              .update();
        });
    UUID actor = IdentityService.userId("https://batch.invalid/realm", "test");
    var grant =
        access.grantAccountAccess(
            owner, actor, AccessService.Role.MANAGER, Set.of(), null, UUID.randomUUID());
    Scope actorScope = new Scope(company, account, actor);
    var identity = mock(RequestIdentity.class);
    when(identity.scope(any())).thenReturn(actorScope);
    var json = new JsonCodec();
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new CompetitorController(identity, transactions, competitors, competitorCommands))
            .setControllerAdvice(new ApiErrors())
            .setMessageConverters(new RequestJsonConverter(json))
            .build();
    Instant observed =
        Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    var input =
        new CompetitorController.ObservationRequest(
            UUID.randomUUID(),
            0L,
            offer,
            UUID.randomUUID(),
            "Seller",
            "new;single;RU;regular",
            observed,
            true,
            new BigDecimal("120.01"),
            BigDecimal.ZERO,
            false);
    String body = json.encode(input);
    String first =
        mvc.perform(
                post("/api/v1/competitor-observations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertEquals(
        first,
        mvc.perform(
                post("/api/v1/competitor-observations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
    var saved = json.decode(first, MarketplaceCompetitorService.Observation.class);
    assertEquals("Observed item", saved.offerName());
    assertEquals(observed, saved.observedAt());
    mvc.perform(
            get("/api/v1/competitor-observations")
                .param("offerId", offer.toString())
                .param("search", "Seller")
                .param("sort", "date")
                .param("size", "1"))
        .andExpect(status().isOk());
    String sources =
        mvc.perform(get("/api/v1/competitor-observations/sources").param("search", "Seller"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(sources.contains(input.sourceId().toString()));
    var correction =
        new CompetitorController.ObservationRequest(
            UUID.randomUUID(),
            1L,
            offer,
            input.sourceId(),
            "Seller",
            input.segment(),
            observed,
            false,
            null,
            null,
            false);
    String corrected =
        mvc.perform(
                put("/api/v1/competitor-observations/" + saved.id())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.encode(correction)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var current = json.decode(corrected, MarketplaceCompetitorService.Observation.class);
    assertEquals(2, current.revision());
    assertEquals(observed, current.observedAt());
    assertNull(current.price());
    assertEquals(false, current.inStock());
    var revoke = new CompetitorController.Mutation(UUID.randomUUID(), 2L);
    String revoked =
        mvc.perform(
                post("/api/v1/competitor-observations/" + saved.id() + "/revoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.encode(revoke)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertTrue(json.decode(revoked, MarketplaceCompetitorService.Observation.class).revoked());
    var page =
        transactions.run(
            actorScope,
            () ->
                competitors.page(
                    actorScope,
                    new TableExportSource.Query(
                        "", Map.of("status", "REVOKED"), "date,desc", 0, 50)));
    assertEquals(1, page.total());
    assertEquals(3, page.items().getFirst().revision());
    assertEquals(
        3,
        transactions.run(
            owner,
            () ->
                jdbc.sql("SELECT count(*) FROM marketplace_competitor_observation WHERE id=:id")
                    .param("id", saved.id())
                    .query(Integer.class)
                    .single()));
    access.revokeAccountAccess(owner, actor, grant.revision(), UUID.randomUUID());
    mvc.perform(
            post("/api/v1/competitor-observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
  }

  @Test
  void sweepReadIsPagedScopedAndRechecksRevokedAccessWithoutRevealingFinancialInputs() {
    Scope scope = new Scope(company, account, admin);
    var authorization = new AuthorizationService(jdbc);
    var automatic = new AutoService(jdbc, policies, mock(AutomationGrantService.class), authorization,
        jobs, new JsonCodec(), Clock.systemUTC(), mock(OutboxService.class),
        new MarketplaceReadService(jdbc, authorization, new JsonCodec(), Clock.systemUTC()));
    assertEquals(0L, transactions.run(scope, () -> automatic.sweeps(scope, 0, 50)).total());
    transactions.run(scope, () -> jdbc.sql("""
            INSERT INTO automation_sweep(organization_id,account_id,cycle,cycle_started_at)
            VALUES (:org,:account,7,clock_timestamp())
            """)
        .param("org", company).param("account", account).update());
    UUID viewer = IdentityService.userId("https://batch.invalid/realm", "test");
    var grant = access.grantAccountAccess(scope, viewer, AccessService.Role.VIEWER, Set.of(), null,
        UUID.randomUUID());
    Scope viewerScope = new Scope(company, account, viewer);
    var page = transactions.run(viewerScope, () -> automatic.sweeps(viewerScope, 0, 1));
    assertEquals(1L, page.total());
    assertEquals(7L, page.items().getFirst().cycle());
    assertEquals(6L, page.items().getFirst().completedCycles());
    assertEquals(account, page.items().getFirst().accountId());
    assertEquals(Set.of("accountId", "cycle", "cycleStartedAt", "completedCycles"),
        JsonParser.parseString(new JsonCodec().encode(page.items().getFirst()))
            .getAsJsonObject().keySet());
    assertTrue(transactions.run(viewerScope, () -> automatic.sweeps(viewerScope, 1, 1)).items().isEmpty());
    assertThrows(BusinessException.class,
        () -> transactions.run(viewerScope, () -> automatic.sweeps(viewerScope, 0, 201)));
    Scope foreign = new Scope(UUID.randomUUID(), account, viewer);
    assertEquals(403, assertThrows(BusinessException.class,
        () -> transactions.run(foreign, () -> automatic.sweeps(foreign, 0, 50))).status());
    access.revokeAccountAccess(scope, viewer, grant.revision(), UUID.randomUUID());
    assertEquals(403, assertThrows(BusinessException.class,
        () -> transactions.run(viewerScope, () -> automatic.sweeps(viewerScope, 0, 50))).status());
  }

  @Test
  void admissionBlocksANewMoreSpecificAssignmentFromAnotherPolicy() throws Exception {
    Scope scope = new Scope(company, account, admin);
    Scope organization = new Scope(company, null, admin);
    UUID offer = UUID.randomUUID();
    transactions.run(
        scope,
        () -> {
          UUID run = UUID.randomUUID();
          jdbc.sql("""
              INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
              VALUES (:id,:org,:account,'CATALOG','PUBLISHED')
              """)
              .param("id", run).param("org", company).param("account", account).update();
          jdbc.sql("""
              INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
                category_path,publication_id,observed_at)
              VALUES (:id,:org,:account,:external,:external,'Concurrent item','{}'::uuid[],:run,now())
              """)
              .param("id", offer).param("org", company).param("account", account)
              .param("external", offer.toString()).param("run", run).update();
          return true;
        });
    var published = transactions.run(
        organization,
        () -> {
          var parentDraft = policies.create(organization,
              new PolicyService.PolicyInput("Admission parent", "", settings(), 0));
          var childDraft = policies.create(organization,
              new PolicyService.PolicyInput("Admission child", "", settings(), 0));
          return List.of(
              policies.publish(organization, parentDraft.id(), parentDraft.revision()),
              policies.publish(organization, childDraft.id(), childDraft.revision()));
        });
    var parent = transactions.run(scope, () -> policies.assign(scope,
        new PolicyService.AssignmentInput(published.getFirst().id(), "ACCOUNT", null,
            "PREVIEW", true, 0)));
    var childInput = new PolicyService.AssignmentInput(published.getLast().id(), "OFFER", offer,
        "PREVIEW", true, 0);
    var resolved = new CountDownLatch(1);
    var finishAdmission = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var admission = executor.submit(() -> transactions.run(scope, () -> {
        assertEquals(parent.id(), policies.resolveForOffer(scope, offer).assignmentId());
        resolved.countDown();
        try {
          assertTrue(finishAdmission.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Admission interrupted", interrupted);
        }
        assertEquals(parent.id(), policies.resolveForOffer(scope, offer).assignmentId());
        return true;
      }));
      try {
        assertTrue(resolved.await(5, TimeUnit.SECONDS));
        assertThrows(CannotAcquireLockException.class, () -> transactions.run(scope, () -> {
          jdbc.sql("SET LOCAL lock_timeout='200ms'").update();
          return policies.assign(scope, childInput);
        }));
      } finally {
        finishAdmission.countDown();
      }
      assertTrue(admission.get(5, TimeUnit.SECONDS));
    }
    var child = transactions.run(scope, () -> policies.assign(scope, childInput));
    assertEquals(child.id(),
        transactions.run(scope, () -> policies.resolveForOffer(scope, offer)).assignmentId());
  }

  @Test
  void detachPreviewsInheritanceRejectsChangedParentAndPreservesHistoricalBinding() {
    Scope scope = new Scope(company, account, admin);
    Scope organization = new Scope(company, null, admin);
    UUID offer = UUID.randomUUID();
    transactions.run(
        scope,
        () -> {
          UUID run = UUID.randomUUID();
          jdbc.sql(
                  "INSERT INTO"
                      + " marketplace_sync_run(id,organization_id,account_id,source_type,state)"
                      + " VALUES (:id,:org,:account,'CATALOG','PUBLISHED')")
              .param("id", run)
              .param("org", company)
              .param("account", account)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,category_path,publication_id,observed_at)
                  VALUES (:id,:org,:account,:external,:external,'Detached item','{}'::uuid[],:run,now())
                  """)
              .param("id", offer)
              .param("org", company)
              .param("account", account)
              .param("external", offer.toString())
              .param("run", run)
              .update();
          return true;
        });
    var policy =
        transactions.run(
            organization,
            () -> {
              var draft =
                  policies.create(
                      organization,
                      new PolicyService.PolicyInput("Inheritance policy", "", settings(), 0));
              return policies.publish(organization, draft.id(), draft.revision());
            });
    var parent =
        transactions.run(
            scope,
            () ->
                policies.assign(
                    scope,
                    new PolicyService.AssignmentInput(
                        policy.id(), "ACCOUNT", null, "PREVIEW", true, 0)));
    var child =
        transactions.run(
            scope,
            () ->
                policies.assign(
                    scope,
                    new PolicyService.AssignmentInput(
                        policy.id(), "OFFER", offer, "PREVIEW", true, 0)));
    var review =
        transactions.run(
            scope, () -> assignmentLifecycle.review(scope, child.id(), child.revision()));
    assertEquals(parent.id(), review.inherited().assignmentId());
    transactions.run(
        scope, () -> policies.pauseAssignment(scope, parent.id(), parent.revision(), true));
    assertEquals(
        "ASSIGNMENT_INHERITANCE_CHANGED",
        assertThrows(
                BusinessException.class,
                () ->
                    transactions.run(
                        scope,
                        () ->
                            assignmentLifecycle.detach(
                                scope, child.id(), child.revision(), review.digest())))
            .code());
    var updated =
        transactions.run(
            scope, () -> assignmentLifecycle.review(scope, child.id(), child.revision()));
    assertEquals("PAUSED", updated.inherited().status());
    var detached =
        transactions.run(
            scope,
            () ->
                assignmentLifecycle.detach(scope, child.id(), child.revision(), updated.digest()));
    assertTrue(detached.detached());
    var effective = transactions.run(scope, () -> policies.resolveForOffer(scope, offer));
    assertEquals(parent.id(), effective.assignmentId());
    assertFalse(effective.active());
    assertEquals(
        1,
        transactions.run(scope, () -> policies.assignmentPage(scope, 0, 50, null, "", "")).total());

    transactions.run(
        scope,
        () ->
            jdbc.sql("UPDATE marketplace_offer SET category_path=NULL WHERE id=:id")
                .param("id", offer)
                .update());
    assertEquals(
        "CATEGORY_UNKNOWN",
        assertThrows(
                BusinessException.class,
                () -> transactions.run(scope, () -> policies.resolveForOffer(scope, offer)))
            .code());
    transactions.run(
        scope,
        () -> {
          var projection = policies.effectiveAssignmentProjection(scope);
          assertEquals(
              0L,
              jdbc.sql(
                      "SELECT count(*) FROM (" + projection.sql() + ") selected WHERE offer_id=:id")
                  .params(projection.parameters())
                  .param("id", offer)
                  .query(Long.class)
                  .single());
          return true;
        });
    var reattached =
        transactions.run(
            scope,
            () ->
                policies.assign(
                    scope,
                    new PolicyService.AssignmentInput(
                        policy.id(), "OFFER", offer, "MANUAL", true, 0)));
    assertEquals(child.id(), reattached.id());
    assertEquals(detached.revision() + 1, reattached.revision());
    assertFalse(reattached.detached());
    assertEquals(
        reattached.id(),
        transactions.run(scope, () -> policies.resolveForOffer(scope, offer)).assignmentId());
    transactions.run(
        scope,
        () -> {
          var projection = policies.effectiveAssignmentProjection(scope);
          assertEquals(
              reattached.id(),
              jdbc.sql(
                      "SELECT assignment_id FROM ("
                          + projection.sql()
                          + ") selected WHERE offer_id=:id")
                  .params(projection.parameters())
                  .param("id", offer)
                  .query(UUID.class)
                  .single());
          return true;
        });
    transactions.run(
        scope,
        () -> {
          UUID scenarioJob =
              jobs.submit(scope, "SCENARIO_CREATE", UUID.randomUUID().toString(), "{}");
          jdbc.sql(
                  """
                  INSERT INTO automation_scenario(organization_id,account_id,id,assignment_id,assignment_revision,
                    policy_id,policy_version,state,starts_at,ends_at,accepted_quantity,scope_digest,settings,reason,revision,author_id)
                  VALUES (:org,:account,:id,:assignment,:revision,:policy,1,'READY',clock_timestamp(),
                    clock_timestamp()+interval '1 hour',0,:digest,CAST(:settings AS jsonb),'READY',1,:author)
                  """)
              .param("org", company)
              .param("account", account)
              .param("id", scenarioJob)
              .param("assignment", reattached.id())
              .param("revision", reattached.revision())
              .param("policy", policy.id())
              .param("digest", "0".repeat(64))
              .param("settings", new JsonCodec().encode(settings()))
              .param("author", admin)
              .update();
          return true;
        });
    assertEquals(
        "ASSIGNMENT_HAS_ACTIVE_SCENARIO",
        assertThrows(
                BusinessException.class,
                () ->
                    transactions.run(
                        scope,
                        () ->
                            assignmentLifecycle.review(
                                scope, reattached.id(), reattached.revision())))
            .code());
  }

  @Test
  void materializesBeforeApplyingAndPreservesConcurrentRowWithExplicitOutcome() {
    Scope scope = new Scope(company, account, admin);
    UUID publication = UUID.randomUUID();
    List<UUID> offers =
        transactions.run(
            scope,
            () -> {
              jdbc.sql(
                      """
                      INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
                      VALUES (:id,:org,:account,'CATALOG','PUBLISHED')
                      """)
                  .param("id", publication)
                  .param("org", company)
                  .param("account", account)
                  .update();
              return jdbc
                  .sql(
                      """
                      INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
                        category_path,observed_at,publication_id)
                      SELECT md5(i::text)::uuid,:org,:account,i::text,i::text,'Batch item '||i,
                        '{}'::uuid[],clock_timestamp(),:publication FROM generate_series(1,60) i RETURNING id
                      """)
                  .param("org", company)
                  .param("account", account)
                  .param("publication", publication)
                  .query(UUID.class)
                  .list()
                  .stream()
                  .sorted()
                  .toList();
            });
    PolicyService.Policy policy =
        transactions.run(
            new Scope(company, null, admin),
            () -> {
              var draft =
                  policies.create(
                      new Scope(company, null, admin),
                      new PolicyService.PolicyInput("Batch policy", "", settings(), 0));
              return policies.publish(
                  new Scope(company, null, admin), draft.id(), draft.revision());
            });
    UUID requestId = UUID.randomUUID();
    UUID selectionId = UUID.randomUUID();
    UUID batchId =
        transactions.run(
            scope,
            () ->
                batches.beginBulk(
                    scope, requestId, selectionId, offers.size(), policy.id(), "PREVIEW"));
    assertEquals(
        batchId,
        transactions.run(
            scope,
            () ->
                batches.beginBulk(
                    scope, requestId, selectionId, offers.size(), policy.id(), "PREVIEW")));
    assertThrows(
        BusinessException.class,
        () ->
            transactions.run(scope, () -> batches.applyNext(scope, batches.lock(scope, batchId))));
    transactions.run(scope, () -> batches.capture(scope, batches.lock(scope, batchId), offers));
    assertEquals(
        0,
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT count(*) FROM automation_assignment")
                    .query(Integer.class)
                    .single()));
    UUID concurrentlyChanged = offers.getFirst();
    transactions.run(
        scope,
        () ->
            policies.assign(
                scope,
                new PolicyService.AssignmentInput(
                    policy.id(), "OFFER", concurrentlyChanged, "MANUAL", true, 0)));
    assertFalse(
        transactions.run(scope, () -> batches.applyNext(scope, batches.lock(scope, batchId))));
    assertTrue(
        transactions.run(scope, () -> batches.applyNext(scope, batches.lock(scope, batchId))));
    var result = transactions.run(scope, () -> batches.get(scope, batchId));
    assertEquals(59, result.applied());
    assertEquals(1, result.failed());
    assertEquals("COMPLETED", result.state());
    assertEquals(
        60L, transactions.run(scope, () -> policies.get(scope, policy.id())).assignmentsCount());
    assertEquals(
        List.of("OFFER"),
        transactions.run(scope, () -> policies.get(scope, policy.id())).assignmentScopes());
    Scope organizationScope = new Scope(company, null, admin);
    assertNull(
        transactions
            .run(organizationScope, () -> policies.get(organizationScope, policy.id()))
            .assignmentsCount());
    assertEquals(
        60L,
        transactions
            .run(
                scope,
                () ->
                    policies.page(
                        scope,
                        new TableExportSource.Query(
                            "Batch policy",
                            Map.of("scopes", "OFFER", "hasAssignments", "YES"),
                            "assignments,desc",
                            0,
                            50)))
            .items()
            .getFirst()
            .assignmentsCount());
    var rows = transactions.run(scope, () -> batches.rows(scope, batchId, 0, 100));
    assertEquals(
        "STALE",
        rows.items().stream()
            .filter(row -> row.offerId().equals(concurrentlyChanged))
            .findFirst()
            .orElseThrow()
            .state());
    assertEquals(
        1,
        transactions
            .run(
                scope, () -> policies.captureOffers(scope, List.of(concurrentlyChanged)).getFirst())
            .directRevision());
    assertEquals(
        "MANUAL",
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT mode FROM automation_assignment WHERE target_id=:id")
                    .param("id", concurrentlyChanged)
                    .query(String.class)
                    .single()));
    transactions.run(scope, () -> batches.applyNext(scope, batches.lock(scope, batchId)));
    assertEquals(result, transactions.run(scope, () -> batches.get(scope, batchId)));
    Scope denied =
        new Scope(company, account, IdentityService.userId("https://batch.invalid/realm", "test"));
    assertEquals(
        403,
        assertThrows(
                BusinessException.class,
                () -> transactions.run(denied, () -> batches.lock(denied, batchId)))
            .status());
  }

  private static PolicySettings settings() {
    return new PolicySettings(
        new PolicySettings.Rule(
            PolicySettings.Strategy.HOLD_PRICE,
            PolicySettings.PriceKind.BASE_SELLER,
            new BigDecimal("100"),
            new BigDecimal("90"),
            new BigDecimal("110"),
            null,
            PolicySettings.Unreachable.KEEP_SAFE,
            null,
            null,
            PolicySettings.PromoMode.PRESERVE,
            Set.of(),
            Set.of()),
        null,
        Set.of(PolicySettings.Operation.SET_BASE_PRICE),
        new PolicySettings.StockProtection(false, BigDecimal.ZERO, BigDecimal.ZERO, null),
        new PolicySettings.Stability(
            BigDecimal.ONE,
            new BigDecimal("100"),
            new BigDecimal("500"),
            3600,
            5,
            0,
            300,
            PolicySettings.WriterMode.REQUIRE_CONDITIONAL,
            null),
        List.of(),
        false);
  }

  @Test
  void scenarioHistoryResponseNeverSerializesFrozenFinancialPolicySettings() {
    var now = Instant.parse("2026-10-08T10:00:00Z");
    var source =
        new ScenarioService.Run(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            2,
            ScenarioState.RUNNING,
            now,
            now.plusSeconds(60),
            new BigDecimal("10"),
            BigDecimal.ONE,
            BigDecimal.ZERO,
            "scope",
            settings(),
            "RUNNING",
            3,
            null);
    var json = new JsonCodec();
    assertTrue(json.encode(source).contains("\"settings\""));
    var response = AutomationApplicationService.temporaryRunView(source);
    assertEquals(source.id(), response.id());
    assertEquals(source.policyVersion(), response.policyVersion());
    String serialized = json.encode(response);
    assertFalse(serialized.contains("\"settings\""));
    assertFalse(serialized.contains("\"regularRule\""));
    assertFalse(serialized.contains("\"target\""));
  }

  @Test
  void previewMaterializesEveryConfirmedPriceTargetBeforeQueuingCalculations() {
    Scope scope = new Scope(company, account, admin);
    UUID offerId = UUID.randomUUID();
    UUID firstTarget = UUID.randomUUID();
    UUID secondTarget = UUID.randomUUID();
    UUID publication = UUID.randomUUID();
    UUID raw = UUID.randomUUID();
    transactions.run(
        scope,
        () -> {
          jdbc.sql(
                  """
                  INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
                  VALUES (:id,:org,:account,'CATALOG','PUBLISHED')
                  """)
              .param("id", publication)
              .param("org", company)
              .param("account", account)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
                    category_path,observed_at,publication_id)
                  VALUES (:id,:org,:account,:external,:external,'Two targets','{}'::uuid[],clock_timestamp(),:publication)
                  """)
              .param("id", offerId)
              .param("org", company)
              .param("account", account)
              .param("external", offerId.toString())
              .param("publication", publication)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,
                    media_type,state) VALUES (:id,:org,:account,:author,'RAW',:key,'application/json','READY')
                  """)
              .param("id", raw)
              .param("org", company)
              .param("account", account)
              .param("author", admin)
              .param("key", raw.toString())
              .update();
          // This fixture tests the published target identity contract, not pricing calculations.
          String source =
              new JsonCodec()
                  .encode(
                      Map.of(
                          "complete",
                          true,
                          "validUntil",
                          Instant.now().plusSeconds(300),
                          "targetIds",
                          List.of(firstTarget, secondTarget)));
          jdbc.sql(
                  """
                  INSERT INTO marketplace_commercial_state(organization_id,account_id,offer_id,revision,
                    snapshot,current,raw_file_id) VALUES (:org,:account,:offer,1,CAST(:source AS jsonb),true,:raw)
                  """)
              .param("org", company)
              .param("account", account)
              .param("offer", offerId)
              .param("source", source)
              .param("raw", raw)
              .update();
        });
    PolicyService.Policy policy =
        transactions.run(
            new Scope(company, null, admin),
            () -> {
              var draft =
                  policies.create(
                      new Scope(company, null, admin),
                      new PolicyService.PolicyInput("Preview policy", "", settings(), 0));
              return policies.publish(
                  new Scope(company, null, admin), draft.id(), draft.revision());
            });
    var assignment =
        transactions.run(
            scope,
            () ->
                policies.assign(
                    scope,
                    new PolicyService.AssignmentInput(
                        policy.id(), "OFFER", offerId, "PREVIEW", true, 0)));
    UUID id =
        transactions.run(
            scope,
            () ->
                batches.beginPreview(
                    scope, UUID.randomUUID(), assignment.id(), assignment.revision()));
    transactions.run(
        scope,
        () -> {
          var batch = batches.lock(scope, id);
          assertEquals(List.of(offerId), batches.nextPreviewOffers(scope, batch));
          batches.capture(scope, batch, List.of(offerId));
          assertEquals("CAPTURING", batches.get(scope, id).state());
        });
    transactions.run(
        scope,
        () -> {
          var batch = batches.lock(scope, id);
          assertTrue(batches.nextPreviewOffers(scope, batch).isEmpty());
          batches.capture(scope, batch, List.of());
        });
    var rows = transactions.run(scope, () -> batches.rows(scope, id, 0, 50));
    assertEquals(2, rows.total());
    assertEquals(
        Set.of(firstTarget, secondTarget),
        rows.items().stream()
            .map(AssignmentBatchService.Row::targetId)
            .collect(java.util.stream.Collectors.toSet()));
    assertTrue(rows.items().stream().allMatch(row -> row.state().equals("PENDING")));
  }

  @Test
  void composedOffersSortAndExportTheSameWholeSelectionWithoutExposingFinancialData() {
    Scope scope = new Scope(company, account, admin);
    List<UUID> offers =
        transactions.run(
            scope,
            () -> {
              UUID publication = UUID.randomUUID();
              jdbc.sql(
                      """
                      INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
                      VALUES (:id,:org,:account,'CATALOG','PUBLISHED')
                      """)
                  .param("id", publication)
                  .param("org", company)
                  .param("account", account)
                  .update();
              return jdbc.sql(
                      """
                      INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
                        category_path,observed_at,publication_id)
                      SELECT md5(('offer-composition-'||i))::uuid,:org,:account,i::text,i::text,'Item '||i,
                        '{}'::uuid[],clock_timestamp(),:publication FROM generate_series(1,3) i
                      RETURNING id
                      """)
                  .param("org", company)
                  .param("account", account)
                  .param("publication", publication)
                  .query(UUID.class)
                  .list();
            });
    transactions.run(
        scope,
        () ->
            economics.publishCost(
                scope,
                new EconomicsService.CostInput(
                    offers.get(0),
                    new BigDecimal("100"),
                    BigDecimal.ZERO,
                    LocalDate.of(2020, 1, 1),
                    null,
                    0)));
    transactions.run(
        scope,
        () ->
            economics.publishCost(
                scope,
                new EconomicsService.CostInput(
                    offers.get(1),
                    new BigDecimal("200"),
                    BigDecimal.ZERO,
                    LocalDate.of(2020, 1, 1),
                    null,
                    0)));
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_stock_pool(id,organization_id,account_id,external_id,offer_id,
                      name,warehouse_type,free_quantity,obligations_covered,observed_at,valid_until,complete)
                    VALUES (:id,:org,:account,'warehouse-1',:offer,'Warehouse 1','FBO',7,2,
                      clock_timestamp(),clock_timestamp()+interval '1 hour',true)
                    """)
                .param("id", UUID.randomUUID())
                .param("org", company)
                .param("account", account)
                .param("offer", offers.get(1))
                .update());
    var stocks =
        transactions.run(
            scope,
            () ->
                stockReads.page(
                    scope,
                    new TableExportSource.Query(
                        "Item", Map.of("availableMin", "6"), "available,desc", 0, 50)));
    assertEquals(1, stocks.total());
    assertNull(stocks.items().getFirst().physical());
    assertEquals(0, new BigDecimal("7").compareTo(stocks.items().getFirst().available()));
    var query = new TableExportSource.Query("Item", Map.of("costMin", "150"), "cost,desc", 0, 1);
    var page = transactions.run(scope, () -> offerReads.page(scope, query));
    assertEquals(1, page.total());
    assertEquals(offers.get(1), page.items().getFirst().id());
    assertEquals(0, new BigDecimal("200").compareTo(page.items().getFirst().cost()));
    assertNull(page.items().getFirst().margin());
    assertEquals(0, new BigDecimal("7").compareTo(page.items().getFirst().stock()));
    transactions.run(
        scope,
        () -> {
          var projection = offerReads.projection(scope, query, List.of());
          var captured =
              jdbc.sql(projection.sql())
                  .params(projection.parameters())
                  .query((row, index) -> row.getObject("canonical_id", UUID.class))
                  .list();
          assertEquals(List.of(offers.get(1)), captured);
          return null;
        });
    UUID viewer = IdentityService.userId("https://batch.invalid/realm", "test");
    access.grantAccountAccess(
        scope, viewer, AccessService.Role.VIEWER, Set.of(), null, UUID.randomUUID());
    Scope viewerScope = new Scope(company, account, viewer);
    var visible = transactions.run(viewerScope, () -> offerReads.get(viewerScope, offers.get(1)));
    assertNull(visible.cost());
    assertFalse(new JsonCodec().encode(visible).contains("\"cost\""));
    assertEquals(
        403,
        assertThrows(
                BusinessException.class,
                () -> transactions.run(viewerScope, () -> offerReads.page(viewerScope, query)))
            .status());
    access.grantAccountAccess(
        scope, viewer, AccessService.Role.MANAGER, Set.of(), 1L, UUID.randomUUID());
    assertEquals(
        403,
        assertThrows(
                BusinessException.class,
                () ->
                    transactions.run(
                        viewerScope,
                        () ->
                            policies.assign(
                                viewerScope,
                                new PolicyService.AssignmentInput(
                                    UUID.randomUUID(), "ACCOUNT", null, "PREVIEW", true, 0))))
            .status());
    access.grantAccountAccess(
        scope, viewer, AccessService.Role.OPERATOR, Set.of(), 2L, UUID.randomUUID());
    var effective =
        new DecisionContextAssembler.PreviewRequest(offers.getFirst(), null, null, false);
    transactions.run(viewerScope, () -> decisions.requirePreviewAuthority(viewerScope, effective));
    var arbitrary =
        new DecisionContextAssembler.PreviewRequest(
            offers.getFirst(),
            null,
            null,
            false,
            new DecisionContextAssembler.FixedTarget(
                PolicySettings.PriceKind.BASE_SELLER, new BigDecimal("50")));
    assertEquals(
        403,
        assertThrows(
                BusinessException.class,
                () ->
                    transactions.runService(
                        viewerScope,
                        DecisionAuthorization.INTERNAL_INPUTS,
                        () -> {
                          decisions.requirePreviewAuthority(viewerScope, arbitrary);
                          return true;
                        }))
            .status());
    UUID snapshot = UUID.randomUUID();
    UUID effectiveDecision = UUID.randomUUID();
    UUID arbitraryDecision = UUID.randomUUID();
    transactions.run(
        scope,
        () -> {
          jdbc.sql(
                  """
                  INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,
                    media_type,state) VALUES (:id,:org,:account,:author,'DECISION',:key,'application/json','READY')
                  """)
              .param("id", snapshot)
              .param("org", company)
              .param("account", account)
              .param("author", admin)
              .param("key", snapshot.toString())
              .update();
          for (UUID decision : List.of(effectiveDecision, arbitraryDecision)) {
            jdbc.sql(
                    """
                    INSERT INTO automation_decision(organization_id,account_id,id,offer_id,
                      policy_version,assignment_revision,offer_revision,cost_revision,tax_revision,
                      safety_revision,state,reason,target_achieved,requires_confirmation,
                      calculated_at,valid_until,maximum_age_seconds,executable,snapshot_file_id,
                      revision,author_id,effective_assignment)
                    VALUES (:org,:account,:id,:offer,0,0,1,0,0,0,'CALCULATED','AUTHORITY_FIXTURE',false,true,
                      clock_timestamp(),clock_timestamp()+interval '60 seconds',60,false,:snapshot,1,
                      :author,:effective)
                    """)
                .param("org", company)
                .param("account", account)
                .param("id", decision)
                .param("offer", offers.getFirst())
                .param("snapshot", snapshot)
                .param("author", admin)
                .param("effective", decision.equals(effectiveDecision))
                .update();
          }
        });
    transactions.runService(
        viewerScope,
        DecisionAuthorization.INTERNAL_INPUTS,
        () -> {
          decisions.requireApprovalAuthority(viewerScope, effectiveDecision);
          return true;
        });
    assertEquals(
        403,
        assertThrows(
                BusinessException.class,
                () ->
                    transactions.runService(
                        viewerScope,
                        DecisionAuthorization.INTERNAL_INPUTS,
                        () -> {
                          decisions.requireApprovalAuthority(viewerScope, arbitraryDecision);
                          return true;
                        }))
            .status());
    access.grantAccountAccess(
        scope, viewer, AccessService.Role.VIEWER, Set.of(), 3L, UUID.randomUUID());
    assertEquals(
        403,
        assertThrows(
                BusinessException.class,
                () ->
                    transactions.runService(
                        viewerScope,
                        DecisionAuthorization.INTERNAL_INPUTS,
                        () -> {
                          decisions.requireApprovalAuthority(viewerScope, effectiveDecision);
                          return true;
                        }))
            .status());
    access.grantAccountAccess(
        scope, viewer, AccessService.Role.VIEWER, Set.of("finance.read"), 4L, UUID.randomUUID());
    assertEquals(
        0,
        transactions
            .run(viewerScope, () -> comparisons.page(viewerScope, effectiveDecision, 0, 50))
            .total());
    assertEquals(
        403,
        assertThrows(
                BusinessException.class,
                () ->
                    transactions.run(
                        viewerScope,
                        () -> comparisons.create(viewerScope, effectiveDecision, null, 100, null)))
            .status());
    access.grantAccountAccess(
        scope, viewer, AccessService.Role.VIEWER, Set.of(), 5L, UUID.randomUUID());
    assertEquals(
        403,
        assertThrows(
                BusinessException.class,
                () ->
                    transactions.run(
                        viewerScope, () -> comparisons.page(viewerScope, effectiveDecision, 0, 50)))
            .status());
    var next =
        transactions.run(
            scope,
            () ->
                offerReads.page(
                    scope, new TableExportSource.Query("Item", Map.of(), "cost,desc", 1, 1)));
    assertEquals(offers.get(0), next.items().getFirst().id());
  }
}
