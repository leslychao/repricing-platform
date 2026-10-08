package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessException;
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
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

class SourceStageRetentionTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("stage-test-only");
  private static final List<String> STAGES =
      List.of("offer", "promotion", "placement", "stock", "history", "category");
  private static JdbcClient owner;
  private static JdbcClient jdbc;
  private static JdbcClient api;
  private static ScopeTransactionRunner transactions;
  private static AuthorizationService authorization;
  private Scope scope;
  private JobRuntime jobs;
  private SourceStageRetention retention;

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
    api =
        JdbcClient.create(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_api", "api-test-only"));
    authorization = new AuthorizationService(jdbc);
    transactions =
        new ScopeTransactionRunner(jdbc, new DataSourceTransactionManager(source), authorization);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void fixture() {
    scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    owner
        .sql("INSERT INTO access_organization(id,name) VALUES (:id,'Retention test')")
        .param("id", scope.organizationId())
        .update();
    owner
        .sql(
            "INSERT INTO access_user(id,issuer,subject,display_name,email,email_verified)"
                + " VALUES (:id,'test',:subject,'Test','test@example.invalid',true)")
        .param("id", scope.subjectId())
        .param("subject", scope.subjectId().toString())
        .update();
    owner
        .sql(
            "INSERT INTO access_membership(id,organization_id,subject_id,role)"
                + " VALUES(gen_random_uuid(),:org,:subject,'OWNER')")
        .param("org", scope.organizationId())
        .param("subject", scope.subjectId())
        .update();
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_account(id,organization_id,marketplace,external_id,name,timezone)"
                + " VALUES(:id,:org,'OZON',:external,'Retention','Europe/Moscow')")
        .param("id", scope.accountId())
        .param("org", scope.organizationId())
        .param("external", scope.accountId().toString())
        .update();
    jobs = mock(JobRuntime.class);
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("jobs", jobs);
    retention =
        new SourceStageRetention(
            jdbc,
            transactions,
            beans.getBeanProvider(JobRuntime.class),
            Clock.systemUTC(),
            "worker");
  }

  @Test
  void marksThenDeletesOnlyTemporaryRowsPreservingRawCanonicalAndUnknownEvidence() {
    UUID run = run("PUBLISHED", "SUCCEEDED", 8);
    UUID raw = raw();
    UUID offer = offer(run);
    allStages(run, raw, offer);
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_raw_page(organization_id,account_id,run_id,page_number,file_id)"
                + " VALUES(:org,:account,:run,0,:raw)")
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", run)
        .param("raw", raw)
        .update();
    owner
        .sql(
            "INSERT INTO"
                + " platform_file_reference(organization_id,account_id,file_id,owner_type,owner_id)"
                + " VALUES(:org,:account,:raw,'sync-run',:run)")
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("raw", raw)
        .param("run", run)
        .update();
    UUID unknown = UUID.randomUUID();
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_external_call(id,organization_id,account_id,method,semantic_kind,"
                + "connection_revision,outcome,raw_file_id)"
                + " VALUES(:id,:org,:account,'OZON_SET_PRICE','WRITE',1,'UNKNOWN',:raw)")
        .param("id", unknown)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("raw", raw)
        .update();

    assertEquals("WAITING", clean());
    assertTrue(marked(run));
    assertEquals(6, count(run));
    assertEquals("SUCCEEDED", clean());
    assertEquals(6, count(run));
    assertEquals(
        410,
        assertThrows(
                BusinessException.class, () -> transactions.run(scope, () -> catalog().run(run)))
            .status());
    mature(run);
    assertEquals("WAITING", clean());
    assertEquals(0, count(run));
    assertEquals("SUCCEEDED", clean());
    assertEquals(1, rows("marketplace_raw_page", "run_id", run));
    assertEquals(1, rows("platform_file", "id", raw));
    assertEquals(1, rows("platform_file_reference", "file_id", raw));
    assertEquals(1, rows("marketplace_offer", "id", offer));
    assertEquals(
        "UNKNOWN",
        owner
            .sql("SELECT outcome FROM marketplace_external_call WHERE id=:id")
            .param("id", unknown)
            .query(String.class)
            .single());
  }

  @Test
  void eligibleAgeRequiresBothTerminalOwnersAndResumedWorkIsRechecked() {
    UUID activeRun = run("PARSING", "SUCCEEDED", 20);
    UUID activeJob = run("INCOMPLETE", "RUNNING", 20);
    UUID fresh = run("FAILED", "DEAD", 6);
    UUID resumed = run("INCOMPLETE", "BLOCKED", 8);
    for (UUID id : List.of(activeRun, activeJob, fresh, resumed)) {
      offerStages(id, 1);
    }
    assertEquals("WAITING", clean());
    assertTrue(marked(resumed));
    assertFalse(marked(activeRun));
    assertFalse(marked(activeJob));
    assertFalse(marked(fresh));
    mature(resumed);
    owner
        .sql(
            "UPDATE platform_job SET state='RUNNING' WHERE id=(SELECT job_id FROM"
                + " marketplace_sync_run WHERE id=:run)")
        .param("run", resumed)
        .update();
    assertEquals("SUCCEEDED", clean());
    assertEquals(1, count(resumed));
    assertEquals(
        410,
        assertThrows(
                BusinessException.class,
                () -> transactions.run(scope, () -> catalog().run(resumed)))
            .status());
    owner
        .sql(
            "UPDATE platform_job SET state='BLOCKED',updated_at=clock_timestamp() WHERE id=(SELECT"
                + " job_id FROM marketplace_sync_run WHERE id=:run)")
        .param("run", resumed)
        .update();
    assertEquals("SUCCEEDED", clean());
    assertEquals(1, count(resumed));
  }

  @Test
  void oneTransactionDeletesAtMostFiveHundredAcrossAllStageTablesAndRollsBackLostLease() {
    UUID run = run("PUBLISHED", "SUCCEEDED", 10);
    offerStages(run, 300);
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_promotion_stage(organization_id,account_id,run_id,external_id,sku,data)"
                + " SELECT :org,:account,:run,n::text,'sku','{}' FROM generate_series(1,207) n")
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", run)
        .update();
    assertEquals("WAITING", clean());
    mature(run);
    AtomicInteger fences = new AtomicInteger();
    doAnswer(
            invocation -> {
              if (fences.incrementAndGet() == 2) {
                throw new BusinessException("JOB_LEASE_LOST", 409, "Lease lost");
              }
              return null;
            })
        .when(jobs)
        .requireOwnership(any());
    assertThrows(BusinessException.class, this::clean);
    assertEquals(507, count(run));
    assertEquals("WAITING", clean());
    assertEquals(7, count(run));
    assertEquals("WAITING", clean());
    assertEquals(0, count(run));
    assertEquals("SUCCEEDED", clean());
  }

  @Test
  void scopeChooserExposesOnlyWorkerMetadataAndScopedForeignKeysRejectOtherAccounts() {
    UUID run = run("INCOMPLETE", "DEAD", 8);
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_category_stage(organization_id,account_id,run_id,sequence,external_id,kind,name,disabled)"
                + " VALUES(:org,:account,:run,1,'only-category','CATEGORY','Category',false)")
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", run)
        .update();
    owner
        .sql("UPDATE marketplace_sync_run SET completed_at=NULL WHERE id=:id")
        .param("id", run)
        .update();
    assertTrue(
        jdbc.sql(
                "SELECT EXISTS(SELECT 1 FROM repricer_source_stage_retention_scopes() WHERE"
                    + " account_id=:id)")
            .param("id", scope.accountId())
            .query(Boolean.class)
            .single());
    assertThrows(
        DataAccessException.class,
        () ->
            api.sql("SELECT * FROM repricer_source_stage_retention_scopes()").query().listOfRows());
    assertThrows(
        DataAccessException.class,
        () ->
            owner
                .sql(
                    "INSERT INTO"
                        + " marketplace_offer_stage(organization_id,account_id,run_id,external_id,sku,name)"
                        + " VALUES(:org,:account,:run,'wrong','wrong','Wrong scope')")
                .param("org", scope.organizationId())
                .param("account", UUID.randomUUID())
                .param("run", run)
                .update());
    Scope actual = scope;
    scope = new Scope(scope.organizationId(), UUID.randomUUID(), scope.subjectId());
    assertEquals("SUCCEEDED", clean());
    assertFalse(marked(run));
    scope = actual;
    assertEquals("WAITING", clean());
    assertFalse(
        jdbc.sql(
                "SELECT EXISTS(SELECT 1 FROM repricer_source_stage_retention_scopes() WHERE"
                    + " account_id=:id)")
            .param("id", scope.accountId())
            .query(Boolean.class)
            .single());
  }

  private String clean() {
    return retention
        .execute(
            new JobContext(
                UUID.randomUUID(), scope, "{}", 1, Instant.now().plusSeconds(60), 0, "service"))
        .state();
  }

  private CatalogSyncService catalog() {
    return new CatalogSyncService(
        jdbc,
        transactions,
        authorization,
        mock(ConnectionService.class),
        mock(OutboundGateway.class),
        mock(CapabilityService.class),
        mock(StoredFileService.class),
        jobs,
        new JsonCodec(),
        mock(IdempotencyService.class),
        mock(AuditService.class),
        mock(OutboxService.class),
        Clock.systemUTC(),
        mock(SourceSnapshotPublisher.class),
        mock(CategoryService.class));
  }

  private UUID run(String state, String jobState, int ageDays) {
    UUID id = UUID.randomUUID();
    UUID job = UUID.randomUUID();
    owner
        .sql(
            "INSERT INTO"
                + " platform_job(id,organization_id,account_id,subject_id,job_type,lane,business_key,payload,state,due_at,updated_at)"
                + " VALUES(:id,:org,:account,:subject,'CATALOG_SYNC','fetch',:key,'{}',:state,"
                + "clock_timestamp(),clock_timestamp()-make_interval(days=>:days))")
        .param("id", job)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("key", job.toString())
        .param("state", jobState)
        .param("days", ageDays)
        .update();
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_sync_run(id,organization_id,account_id,job_id,source_type,state,started_at,completed_at)"
                + " VALUES(:id,:org,:account,:job,'CATALOG',:state,clock_timestamp()-interval '30"
                + " days',clock_timestamp()-make_interval(days=>:days))")
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("job", job)
        .param("state", state)
        .param("days", ageDays)
        .update();
    return id;
  }

  private void offerStages(UUID run, int count) {
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_offer_stage(organization_id,account_id,run_id,external_id,sku,name)"
                + " SELECT :org,:account,:run,n::text,n::text,'Candidate' FROM"
                + " generate_series(1,:count) n")
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", run)
        .param("count", count)
        .update();
  }

  private void allStages(UUID run, UUID raw, UUID offer) {
    offerStages(run, 1);
    for (String sql :
        List.of(
            "INSERT INTO"
                + " marketplace_promotion_stage(organization_id,account_id,run_id,external_id,sku,data)"
                + " VALUES(:org,:account,:run,'promo','sku','{}')",
            "INSERT INTO"
                + " marketplace_placement_stage(organization_id,account_id,run_id,campaign_id,offer_id,status,available,fields)"
                + " VALUES(:org,:account,:run,'campaign',:offer,'PUBLISHED',true,'{}')",
            "INSERT INTO"
                + " marketplace_stock_stage(organization_id,account_id,run_id,external_id,offer_id,name,warehouse_type,observed_at,complete)"
                + " VALUES(:org,:account,:run,'pool',:offer,'Pool','FBS',clock_timestamp(),false)",
            "INSERT INTO"
                + " marketplace_history_stage(organization_id,account_id,run_id,source_kind,external_id,data,raw_file_id)"
                + " VALUES(:org,:account,:run,'ORDER','order','{}',:raw)",
            "INSERT INTO"
                + " marketplace_category_stage(organization_id,account_id,run_id,sequence,external_id,kind,name,disabled)"
                + " VALUES(:org,:account,:run,1,'category','CATEGORY','Category',false)")) {
      owner
          .sql(sql)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("run", run)
          .param("raw", raw)
          .param("offer", offer)
          .update();
    }
  }

  private UUID raw() {
    UUID id = UUID.randomUUID();
    owner
        .sql(
            "INSERT INTO"
                + " platform_file(id,organization_id,account_id,subject_id,kind,object_key,object_version,media_type,state)"
                + " VALUES(:id,:org,:account,:subject,'RAW',:key,'version','application/json','STORED')")
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("key", id.toString())
        .update();
    return id;
  }

  private UUID offer(UUID run) {
    UUID id = UUID.randomUUID();
    owner
        .sql(
            "INSERT INTO"
                + " marketplace_offer(id,organization_id,account_id,external_id,sku,name,observed_at,publication_id)"
                + " VALUES(:id,:org,:account,:external,:external,'Published',clock_timestamp(),:run)")
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("external", id.toString())
        .param("run", run)
        .update();
    return id;
  }

  private boolean marked(UUID run) {
    return owner
        .sql("SELECT stage_deletion_after IS NOT NULL FROM marketplace_sync_run WHERE id=:id")
        .param("id", run)
        .query(Boolean.class)
        .single();
  }

  private void mature(UUID run) {
    owner
        .sql(
            "UPDATE marketplace_sync_run SET stage_deletion_after=clock_timestamp()-interval '1"
                + " second' WHERE id=:id")
        .param("id", run)
        .update();
  }

  private int count(UUID run) {
    int count = 0;
    for (String stage : STAGES) {
      count += rows("marketplace_" + stage + "_stage", "run_id", run);
    }
    return count;
  }

  private static int rows(String table, String key, UUID id) {
    return owner
        .sql("SELECT count(*) FROM " + table + " WHERE " + key + "=:id")
        .param("id", id)
        .query(Integer.class)
        .single();
  }
}
