package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.app.preferences.ViewStateService;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.accounting.AccountingBasisService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobControlConnections;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/**
 * Explicit long-running acceptance of financial reads and personal views at the specified data
 * size. Run with {@code -Dtest=ReadLoadAcceptance}; this does not certify HTTP, workers or host
 * sizing.
 */
class ReadLoadAcceptance {
  private static final int USERS = 20;
  private static final int REQUESTS_PER_USER = 900;
  private static final LocalDate START = LocalDate.of(2025, 10, 1);
  private static final LocalDate END = LocalDate.of(2026, 10, 1);

  private record Account(UUID organization, UUID id) {}

  @Test
  @Timeout(value = 50, unit = TimeUnit.MINUTES)
  void financialPagesAndPersonalViewsSustainTenRequestsPerSecondForThirtyMinutes(
      TestReporter reporter) throws Exception {
    try (var postgres =
        new PostgreSQLContainer(
                DockerImageName.parse(
                        "postgres:17.5-alpine@sha256:6567bca8d7bc8c82c5922425a0baee57be8402df92bae5eacad5f01ae9544daa")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("repricer")
            .withUsername("postgres")
            .withPassword("test-container-only")) {
      postgres.start();
      migrate(postgres);
      var fixture =
          JdbcClient.create(
              new DriverManagerDataSource(
                  postgres.getJdbcUrl(), "repricer_migrator", "migration-test-only"));
      List<Scope> users = seed(fixture);
      assertEquals(
          50_000L,
          fixture.sql("SELECT count(*) FROM marketplace_offer").query(Long.class).single());
      assertEquals(
          5_000_000L,
          fixture.sql("SELECT count(*) FROM economics_financial_event").query(Long.class).single());
      fixture.sql("ANALYZE").update();

      var configuration = new HikariConfig();
      configuration.setJdbcUrl(postgres.getJdbcUrl());
      configuration.setUsername("repricer_api");
      configuration.setPassword("api-test-only");
      configuration.setMaximumPoolSize(10);
      configuration.setMinimumIdle(1);
      configuration.setConnectionTimeout(3000);
      configuration.setValidationTimeout(2000);
      try (var dataSource = new HikariDataSource(configuration)) {
        var jdbc = JdbcClient.create(dataSource);
        var authorization = new AuthorizationService(jdbc);
        var scopes =
            new ScopeTransactionRunner(
                jdbc, new DataSourceTransactionManager(dataSource), authorization);
        var beans = new DefaultListableBeanFactory();
        var json = new JsonCodec();
        var clock = Clock.systemUTC();
        var jobs =
            new JobRuntime(
                jdbc,
                scopes,
                clock,
                beans.getBeanProvider(JobHandler.class),
                beans.getBeanProvider(OutboxService.class),
                beans.getBeanProvider(JobControlConnections.class),
                "api");
        try {
          var outbox =
              new OutboxService(jdbc, json, jobs, beans.getBeanProvider(OutboxRecipient.class));
          var economics =
              new EconomicsService(
                  jdbc,
                  authorization,
                  new AuditService(jdbc),
                  outbox,
                  new AccountingBasisService(jdbc, outbox));
          var views =
              new ViewStateService(
                  jdbc,
                  json,
                  authorization,
                  new IdempotencyService(jdbc, json),
                  new MarketplaceReadService(jdbc, authorization, json, clock),
                  clock);
          runLoad(users, scopes, economics, views, reporter);
        } finally {
          jobs.destroy();
        }
      }
    }
  }

  private static void runLoad(
      List<Scope> users,
      ScopeTransactionRunner scopes,
      EconomicsService economics,
      ViewStateService views,
      TestReporter reporter)
      throws Exception {
    int requests = USERS * REQUESTS_PER_USER;
    long[] gridTimes = new long[requests];
    long[] viewReadTimes = new long[requests];
    long[] viewSaveTimes = new long[requests];
    long[] firstGridTimes = new long[USERS];
    long[] firstViewTimes = new long[USERS];
    var gridCount = new AtomicInteger();
    var viewReadCount = new AtomicInteger();
    var viewSaveCount = new AtomicInteger();
    var invariantFailure = new AtomicReference<AssertionError>();
    var errors = new ConcurrentLinkedQueue<String>();
    long started = System.nanoTime();
    long workloadDeadline = started + TimeUnit.SECONDS.toNanos(1805);
    try (var executor = Executors.newFixedThreadPool(USERS)) {
      var futures = new ArrayList<Future<?>>(USERS);
      for (int index = 0; index < USERS; index++) {
        int userIndex = index;
        Scope user = users.get(index);
        futures.add(
            executor.submit(
                () -> {
                  ViewStateService.SavedView previous = null;
                  for (int request = 0; request < REQUESTS_PER_USER; request++) {
                    if (invariantFailure.get() != null) {
                      return;
                    }
                    long due =
                        started
                            + TimeUnit.MILLISECONDS.toNanos(userIndex * 100L)
                            + TimeUnit.SECONDS.toNanos(request * 2L);
                    long waiting = due - System.nanoTime();
                    if (waiting > 0) {
                      try {
                        TimeUnit.NANOSECONDS.sleep(waiting);
                      } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Load interrupted", interrupted);
                      }
                    }
                    boolean view = request % 5 == 4;
                    boolean viewRead = view && (previous == null || request % 10 == 4);
                    try {
                      if (view) {
                        if (viewRead) {
                          previous = scopes.run(user, () -> views.get(user, "finance"));
                        } else {
                          var input =
                              new ViewStateService.SaveRequest(
                                  UUID.randomUUID(),
                                  previous.revision(),
                                  previous.resetGeneration(),
                                  previous.state());
                          previous = scopes.run(user, () -> views.save(user, "finance", input));
                        }
                      } else {
                        boolean annual = request % 8 == 0;
                        int page = request % 20;
                        var rows =
                            scopes.run(
                                user,
                                () ->
                                    economics.financialPage(
                                        user,
                                        page,
                                        50,
                                        annual ? START : END.minusMonths(1),
                                        END,
                                        null,
                                        "",
                                        "",
                                        "",
                                        "date",
                                        "desc"));
                        assertEquals(annual ? 500_000L : 41_070L, rows.total());
                        assertEquals(50, rows.items().size());
                        assertTrue(
                            rows.items().stream()
                                .allMatch(
                                    row ->
                                        row.complete()
                                            && row.offerId()
                                                .toString()
                                                .startsWith(
                                                    user.accountId().toString().substring(0, 24))
                                            && !row.accountingDate().isBefore(START)
                                            && row.accountingDate().isBefore(END)));
                      }
                    } catch (AssertionError failure) {
                      // Wrong data or a scope violation invalidates acceptance even once.
                      invariantFailure.compareAndSet(null, failure);
                      return;
                    } catch (RuntimeException failure) {
                      // Count every failure, but retain bounded diagnostic text instead of all
                      // stacks.
                      errors.add(failure.getClass().getSimpleName());
                    } finally {
                      long elapsed = System.nanoTime() - due;
                      if (request == 0) {
                        firstGridTimes[userIndex] = elapsed;
                      }
                      if (request == 4) {
                        firstViewTimes[userIndex] = elapsed;
                      }
                      if (viewRead) {
                        viewReadTimes[viewReadCount.getAndIncrement()] = elapsed;
                      } else if (view) {
                        viewSaveTimes[viewSaveCount.getAndIncrement()] = elapsed;
                      } else {
                        gridTimes[gridCount.getAndIncrement()] = elapsed;
                      }
                    }
                  }
                }));
      }
      try {
        for (var future : futures) {
          future.get(Math.max(1, workloadDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        }
      } finally {
        executor.shutdownNow();
      }
    }
    AssertionError invalidResult = invariantFailure.get();
    if (invalidResult != null) {
      throw invalidResult;
    }
    long duration = System.nanoTime() - started;
    long gridP95 = percentile95(gridTimes, gridCount.get());
    long viewReadP95 = percentile95(viewReadTimes, viewReadCount.get());
    long viewSaveP95 = percentile95(viewSaveTimes, viewSaveCount.get());
    long firstGridMaximum = Arrays.stream(firstGridTimes).max().orElseThrow();
    long firstViewMaximum = Arrays.stream(firstViewTimes).max().orElseThrow();
    reporter.publishEntry(
        Map.ofEntries(
            Map.entry("offers", "50000"),
            Map.entry("financialRows", "5000000"),
            Map.entry("users", "20"),
            Map.entry("requests", Integer.toString(requests)),
            Map.entry("offeredRequestsPerSecond", "10"),
            Map.entry(
                "completedRequestsPerSecond",
                String.format(Locale.ROOT, "%.4f", requests * 1_000_000_000d / duration)),
            Map.entry("durationMillis", Long.toString(TimeUnit.NANOSECONDS.toMillis(duration))),
            Map.entry("gridP95Millis", Long.toString(TimeUnit.NANOSECONDS.toMillis(gridP95))),
            Map.entry(
                "viewReadP95Millis", Long.toString(TimeUnit.NANOSECONDS.toMillis(viewReadP95))),
            Map.entry(
                "viewSaveP95Millis", Long.toString(TimeUnit.NANOSECONDS.toMillis(viewSaveP95))),
            Map.entry("errors", Integer.toString(errors.size())),
            Map.entry(
                "firstGridMaximumMillis",
                Long.toString(TimeUnit.NANOSECONDS.toMillis(firstGridMaximum))),
            Map.entry(
                "firstViewMaximumMillis",
                Long.toString(TimeUnit.NANOSECONDS.toMillis(firstViewMaximum)))));
    assertEquals(requests, gridCount.get() + viewReadCount.get() + viewSaveCount.get());
    assertTrue(
        duration <= TimeUnit.SECONDS.toNanos(1805), "Workload did not drain within 5 seconds");
    assertTrue(
        errors.size() < requests / 100,
        () -> "Errors: " + errors.size() + ", first: " + errors.stream().limit(5).toList());
    assertTrue(gridP95 <= TimeUnit.SECONDS.toNanos(2), "Financial page p95 exceeds 2 seconds");
    assertTrue(
        firstGridMaximum <= TimeUnit.SECONDS.toNanos(2),
        "Initial financial page exceeds 2 seconds");
    assertTrue(viewReadP95 <= TimeUnit.MILLISECONDS.toNanos(500), "View read p95 exceeds 500 ms");
    assertTrue(viewSaveP95 <= TimeUnit.MILLISECONDS.toNanos(500), "View save p95 exceeds 500 ms");
    assertTrue(
        firstViewMaximum <= TimeUnit.MILLISECONDS.toNanos(500), "Initial view exceeds 500 ms");
  }

  private static long percentile95(long[] values, int count) {
    Arrays.sort(values, 0, count);
    return values[(count * 95 + 99) / 100 - 1];
  }

  private static void migrate(PostgreSQLContainer postgres) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
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
                        postgres.getJdbcUrl(), "repricer_migrator", "migration-test-only")));
    try (var liquibase =
        new Liquibase("db/changelog/master.xml", new ClassLoaderResourceAccessor(), database)) {
      liquibase.update(new Contexts());
    }
  }

  private static List<Scope> seed(JdbcClient fixture) {
    List<UUID> organizations = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    var accounts = new ArrayList<Account>(10);
    for (int organization = 0; organization < organizations.size(); organization++) {
      UUID org = organizations.get(organization);
      fixture
          .sql("INSERT INTO access_organization(id,name) VALUES (:id,:name)")
          .param("id", org)
          .param("name", "Load company " + organization)
          .update();
      for (int index = 0; index < (organization == 0 ? 4 : 3); index++) {
        var account = new Account(org, UUID.randomUUID());
        accounts.add(account);
        fixture
            .sql(
                """
                INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                VALUES (:id,:org,'YANDEX',:external,'Load account','Europe/Moscow')
                """)
            .param("id", account.id())
            .param("org", org)
            .param("external", Integer.toString(8_800_000 + accounts.size()))
            .update();
        seedAccount(fixture, account);
      }
    }
    var users = new ArrayList<Scope>(USERS);
    for (int index = 0; index < USERS; index++) {
      int orgIndex;
      int offset;
      if (index < 14) {
        orgIndex = 0;
        offset = 0;
      } else if (index < 17) {
        orgIndex = 1;
        offset = 4;
      } else {
        orgIndex = 2;
        offset = 7;
      }
      int count = orgIndex == 0 ? 4 : 3;
      Account account = accounts.get(offset + index % count);
      UUID subject = UUID.randomUUID();
      fixture
          .sql(
              """
              INSERT INTO access_user(id,issuer,subject,display_name,email)
              VALUES (:id,'https://load.invalid',:subject,'Load user',:email)
              """)
          .param("id", subject)
          .param("subject", subject.toString())
          .param("email", "load" + index + "@example.invalid")
          .update();
      boolean owner = index == 0 || index == 14 || index == 17;
      fixture
          .sql(
              """
              INSERT INTO access_membership(id,organization_id,subject_id,role)
              VALUES (:id,:org,:subject,:role)
              """)
          .param("id", UUID.randomUUID())
          .param("org", account.organization())
          .param("subject", subject)
          .param("role", owner ? "OWNER" : "MEMBER")
          .update();
      fixture
          .sql(
              """
              INSERT INTO access_account_permission(id,organization_id,account_id,subject_id,role,permissions)
              VALUES (:id,:org,:account,:subject,'ADMIN',ARRAY['finance.read'])
              """)
          .param("id", UUID.randomUUID())
          .param("org", account.organization())
          .param("account", account.id())
          .param("subject", subject)
          .update();
      users.add(new Scope(account.organization(), account.id(), subject));
    }
    return List.copyOf(users);
  }

  private static void seedAccount(JdbcClient fixture, Account account) {
    UUID run = UUID.randomUUID();
    var parameters = Map.of("org", account.organization(), "account", account.id(), "run", run);
    fixture
        .sql(
            """
            INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
            VALUES (:run,:org,:account,'CATALOG','PUBLISHED')
            """)
        .params(parameters)
        .update();
    fixture
        .sql(
            """
            INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
              observed_at,publication_id)
            SELECT (left(:account::text,24)||lpad(to_hex(n),12,'0'))::uuid,:org,:account,n::text,n::text,
              'Load offer '||n,clock_timestamp(),:run FROM generate_series(1,5000) n
            """)
        .params(parameters)
        .update();
    fixture
        .sql(
            """
            INSERT INTO economics_recognition_guard(organization_id,account_id,source_id,component)
            SELECT :org,:account,md5(:account::text||':event:'||n)::uuid,'REVENUE'
            FROM generate_series(1,500000) n
            """)
        .params(parameters)
        .update();
    fixture
        .sql(
            """
            INSERT INTO economics_financial_event(organization_id,account_id,id,source_id,component,
              source_revision,source_digest,offer_id,accounting_date,income,cost,expenses,tax,profit,
              complete,current_revision,preliminary)
            SELECT :org,:account,md5(:account::text||':event:'||n)::uuid,
              md5(:account::text||':event:'||n)::uuid,'REVENUE',1,'load-evidence',
              (left(:account::text,24)||lpad(to_hex((n-1)%5000+1),12,'0'))::uuid,
              DATE '2025-10-01'+((n-1)%365),1000,100,50,60,790,true,true,false
            FROM generate_series(1,500000) n
            """)
        .params(parameters)
        .update();
  }
}
