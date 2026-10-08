package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import jakarta.mail.internet.MimeMessage;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AccessQuery;
import ru.oritas.repricer.access.AccessService;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.IdentityService;
import ru.oritas.repricer.access.InvitationMailHandler;
import ru.oritas.repricer.access.InvitationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.app.realtime.RealtimeEventGateway;
import ru.oritas.repricer.app.realtime.RealtimeSocket;
import ru.oritas.repricer.automation.decision.CurrentEconomicsService;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.automation.decision.DecisionValidityService;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.economics.AccountingImportService;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.accounting.AccountingBasisService;
import ru.oritas.repricer.marketplace.MarketplaceCompetitorService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.FileWorkGate;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;
import ru.oritas.repricer.platform.VaultSecretStore;

class DatabaseBoundaryTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("test-container-only");
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner transactions;
  private static AuthorizationService authorization;
  private static AccessService access;
  private static UUID company;
  private static UUID admin;
  private static UUID test;
  private static OutboxService outbox;
  private static DriverManagerDataSource dataSource;

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
    migrate();
    dataSource =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_api", "api-test-only");
    jdbc = JdbcClient.create(dataSource);
    authorization = new AuthorizationService(jdbc);
    transactions =
        new ScopeTransactionRunner(
            jdbc, new DataSourceTransactionManager(dataSource), authorization);
    JsonCodec json = new JsonCodec();
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("events", new RealtimeEventGateway(jdbc, json, authorization));
    outbox =
        new OutboxService(
            jdbc, json, mock(JobRuntime.class), beans.getBeanProvider(OutboxRecipient.class));
    access =
        new AccessService(
            jdbc,
            transactions,
            authorization,
            new IdempotencyService(jdbc, json),
            new AuditService(jdbc),
            outbox,
            new IdentityService(jdbc));
    admin = IdentityService.userId("https://test.invalid/realm", "admin");
    test = IdentityService.userId("https://test.invalid/realm", "test");
    company = access.initManagedUsers("https://test.invalid/realm", "admin", "test");
  }

  private static void migrate() throws Exception {
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
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void acceptedInvitationReplayCannotBypassSubsequentMembershipRevocation() {
    String issuer = "https://test.invalid/realm";
    String subject = UUID.randomUUID().toString();
    UUID recipient = IdentityService.userId(issuer, subject);
    String email = subject + "@example.invalid";
    Scope self = new Scope(null, null, recipient);
    transactions.run(
        self,
        () ->
            new IdentityService(jdbc)
                .confirm(self, issuer, subject, "Invitee", email, true, Set.of()));
    var secrets = mock(VaultSecretStore.class);
    String token = "a".repeat(43);
    when(secrets.putIfAbsent(anyString(), anyMap())).thenReturn(Map.of("token", token));
    var json = new JsonCodec();
    var invitations =
        new InvitationService(
            jdbc,
            transactions,
            authorization,
            access,
            new IdempotencyService(jdbc, json),
            mock(JobRuntime.class),
            json,
            secrets,
            Clock.systemUTC());
    UUID invitedCompany =
        access.createOrganization(admin, UUID.randomUUID(), "Invitation replay").id();
    Scope owner = new Scope(invitedCompany, null, admin);
    invitations.create(owner, email, AccessService.Role.MEMBER, Set.of(), UUID.randomUUID());
    UUID requestId = UUID.randomUUID();
    var accepted = invitations.accept(recipient, token, requestId);
    assertEquals(invitedCompany, accepted.organizationId());
    assertEquals(accepted, invitations.accept(recipient, token, requestId));
    long revision =
        transactions.run(
            owner,
            () ->
                jdbc.sql("SELECT revision FROM access_membership WHERE subject_id=:subject")
                    .param("subject", recipient)
                    .query(Long.class)
                    .single());
    access.revokeMembership(owner, recipient, revision, UUID.randomUUID());
    BusinessException rejected =
        assertThrows(
            BusinessException.class, () -> invitations.accept(recipient, token, requestId));
    assertEquals(403, rejected.status());
  }

  @Test
  void invitationsReachMailpitAndLostSmtpConfirmationNeverCausesAnotherSend() throws Exception {
    try (var mailbox =
        new GenericContainer<>(
                "axllent/mailpit:v1.27.8@sha256:6abc8e633df15eaf785cfcf38bae48e66f64beecdc03121e249d0f9ec15f0707")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/livez").forPort(8025))) {
      mailbox.start();
      var secrets = mock(VaultSecretStore.class);
      String confirmedToken = "b".repeat(43);
      String uncertainToken = "c".repeat(43);
      when(secrets.putIfAbsent(anyString(), anyMap()))
          .thenReturn(Map.of("token", confirmedToken))
          .thenReturn(Map.of("token", uncertainToken));
      var runtime = mock(JobRuntime.class);
      var json = new JsonCodec();
      var invitations =
          new InvitationService(
              jdbc,
              transactions,
              authorization,
              access,
              new IdempotencyService(jdbc, json),
              runtime,
              json,
              secrets,
              Clock.systemUTC());
      Scope owner = new Scope(company, null, admin);
      var confirmed =
          invitations.create(
              owner,
              "mail@example.invalid",
              AccessService.Role.MEMBER,
              Set.of(),
              UUID.randomUUID());
      var uncertain =
          invitations.create(
              owner,
              "lost@example.invalid",
              AccessService.Role.MEMBER,
              Set.of(),
              UUID.randomUUID());
      when(secrets.read(anyString(), anyLong()))
          .thenReturn(Map.of("token", confirmedToken))
          .thenReturn(Map.of("token", uncertainToken));
      var sender = spy(new JavaMailSenderImpl());
      sender.setHost(mailbox.getHost());
      sender.setPort(mailbox.getMappedPort(1025));
      sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "5000");
      sender.getJavaMailProperties().setProperty("mail.smtp.timeout", "5000");
      sender.getJavaMailProperties().setProperty("mail.smtp.writetimeout", "5000");
      var handler =
          new InvitationMailHandler(
              jdbc,
              transactions,
              runtime,
              json,
              secrets,
              sender,
              Clock.systemUTC(),
              URI.create("https://repricer.example.invalid"),
              "invite@example.invalid");
      var confirmedJob =
          new JobContext(
              UUID.randomUUID(),
              owner,
              json.encode(new InvitationService.MailJob(confirmed.id())),
              1,
              Instant.now().plusSeconds(30),
              0,
              "service");
      assertEquals("SUCCEEDED", handler.execute(confirmedJob).state());
      assertEquals("SUCCEEDED", handler.execute(confirmedJob).state());
      doAnswer(
              invocation -> {
                invocation.callRealMethod();
                throw new MailSendException("Synthetic lost SMTP acknowledgement");
              })
          .when(sender)
          .send(any(MimeMessage.class));
      var uncertainJob =
          new JobContext(
              UUID.randomUUID(),
              owner,
              json.encode(new InvitationService.MailJob(uncertain.id())),
              1,
              Instant.now().plusSeconds(30),
              0,
              "service");
      assertEquals("MAIL_OUTCOME_UNKNOWN", handler.execute(uncertainJob).reason());
      assertEquals("MAIL_OUTCOME_UNKNOWN", handler.execute(uncertainJob).reason());
      assertEquals(
          "UNKNOWN",
          transactions.run(
              owner,
              () ->
                  jdbc.sql("SELECT mail_state FROM access_invitation WHERE id=:id")
                      .param("id", uncertain.id())
                      .query(String.class)
                      .single()));
      URI messages =
          URI.create(
              "http://"
                  + mailbox.getHost()
                  + ":"
                  + mailbox.getMappedPort(8025)
                  + "/api/v1/messages?limit=2");
      try (var client = HttpClient.newHttpClient()) {
        var response =
            client.send(
                HttpRequest.newBuilder(messages).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        try (var input = response.body()) {
          byte[] body = input.readNBytes(16385);
          assertTrue(body.length <= 16384);
          var data = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
          assertEquals(2, data.getAsJsonObject().get("total").getAsInt());
        }
      }
    }
  }

  @Test
  void abandonedAttemptsAreBoundedAndExhaustedWorkDoesNotExecuteAgain() throws Exception {
    Scope scope = new Scope(company, null, admin);
    UUID jobId = UUID.randomUUID();
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO platform_job(id,organization_id,subject_id,job_type,lane,business_key,
                      payload,state,due_at,lease_owner,lease_until)
                    VALUES (:id,:org,:actor,'CRASH_BOUNDARY','execution',:key,'{}','RUNNING',
                      clock_timestamp()-interval '1 hour',:worker,clock_timestamp()-interval '1 second')
                    """)
                .param("id", jobId)
                .param("org", company)
                .param("actor", admin)
                .param("key", jobId.toString())
                .param("worker", UUID.randomUUID())
                .update());
    var workerData =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_worker", "worker-test-only");
    var workerJdbc = JdbcClient.create(workerData);
    var workerScopes =
        new ScopeTransactionRunner(
            workerJdbc,
            new DataSourceTransactionManager(workerData),
            new AuthorizationService(workerJdbc));
    for (int attempt = 1; attempt <= 7; attempt++) {
      assertEquals(
          jobId,
          workerJdbc
              .sql("SELECT id FROM repricer_claim_job(:worker,'execution',2)")
              .param("worker", UUID.randomUUID())
              .query(UUID.class)
              .single());
      int actual =
          workerScopes.runService(
              scope,
              Set.of("platform.job.manage"),
              () ->
                  workerJdbc
                      .sql("SELECT attempt FROM platform_job WHERE id=:id")
                      .param("id", jobId)
                      .query(Integer.class)
                      .single());
      assertEquals(attempt, actual);
      workerScopes.runService(
          scope,
          Set.of("platform.job.manage"),
          () ->
              workerJdbc
                  .sql(
                      """
                      UPDATE platform_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=:id
                      """)
                  .param("id", jobId)
                  .update());
    }
    var called = new java.util.concurrent.atomic.AtomicInteger();
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "handler",
        new ru.oritas.repricer.platform.JobHandler() {
          @Override
          public String type() {
            return "CRASH_BOUNDARY";
          }

          @Override
          public String lane() {
            return "execution";
          }

          @Override
          public ru.oritas.repricer.platform.JobOutcome execute(
              ru.oritas.repricer.platform.JobContext context) {
            called.incrementAndGet();
            return ru.oritas.repricer.platform.JobOutcome.succeeded("{}");
          }
        });
    beans.registerSingleton("outbox", mock(OutboxService.class));
    var control = mock(ru.oritas.repricer.platform.JobControlConnections.class);
    when(control.jdbc()).thenReturn(workerJdbc);
    beans.registerSingleton("control", control);
    var runtime =
        new JobRuntime(
            workerJdbc,
            workerScopes,
            Clock.systemUTC(),
            beans.getBeanProvider(ru.oritas.repricer.platform.JobHandler.class),
            beans.getBeanProvider(OutboxService.class),
            beans.getBeanProvider(ru.oritas.repricer.platform.JobControlConnections.class),
            "worker");
    try {
      runtime.claim();
      long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
      String state;
      do {
        state =
            transactions.run(
                scope,
                () ->
                    jdbc.sql("SELECT state FROM platform_job WHERE id=:id")
                        .param("id", jobId)
                        .query(String.class)
                        .single());
        if (state.equals("DEAD")) {
          break;
        }
        Thread.sleep(20);
      } while (System.nanoTime() < deadline);
      assertEquals("DEAD", state);
      assertEquals(0, called.get());
      assertTrue(
          workerScopes.runService(
              scope, Set.of("platform.job.manage"), () -> runtime.terminal(scope, jobId)));
    } finally {
      runtime.destroy();
    }
  }

  @Test
  void leaseRenewalRetainsReservedConnectionsWhenTheBusinessPoolIsExhausted() throws Exception {
    Scope scope = new Scope(company, null, admin);
    var configuration = new com.zaxxer.hikari.HikariConfig();
    configuration.setJdbcUrl(POSTGRES.getJdbcUrl());
    configuration.setUsername("repricer_worker");
    configuration.setPassword("worker-test-only");
    configuration.setMaximumPoolSize(1);
    configuration.setMinimumIdle(1);
    configuration.setConnectionTimeout(1000);
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var businessPool = new com.zaxxer.hikari.HikariDataSource(configuration);
        var control =
            new ru.oritas.repricer.platform.JobControlConnections(
                POSTGRES.getJdbcUrl(), "repricer_worker", "worker-test-only")) {
      var businessJdbc = JdbcClient.create(businessPool);
      var businessScopes =
          new ScopeTransactionRunner(
              businessJdbc,
              new DataSourceTransactionManager(businessPool),
              new AuthorizationService(businessJdbc));
      var beans = new DefaultListableBeanFactory();
      beans.registerSingleton("control", control);
      beans.registerSingleton("outbox", mock(OutboxService.class));
      beans.registerSingleton(
          "handler",
          new ru.oritas.repricer.platform.JobHandler() {
            @Override
            public String type() {
              return "POOL_ISOLATION";
            }

            @Override
            public String lane() {
              return "execution";
            }

            @Override
            public ru.oritas.repricer.platform.JobOutcome execute(
                ru.oritas.repricer.platform.JobContext context) throws InterruptedException {
              entered.countDown();
              if (!release.await(25, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test did not release the running operation");
              }
              return ru.oritas.repricer.platform.JobOutcome.succeeded("{}");
            }
          });
      var runtime =
          new JobRuntime(
              businessJdbc,
              businessScopes,
              Clock.systemUTC(),
              beans.getBeanProvider(ru.oritas.repricer.platform.JobHandler.class),
              beans.getBeanProvider(OutboxService.class),
              beans.getBeanProvider(ru.oritas.repricer.platform.JobControlConnections.class),
              "worker");
      UUID job =
          businessScopes.runService(
              scope,
              Set.of("platform.job.manage"),
              () -> runtime.submit(scope, "POOL_ISOLATION", UUID.randomUUID().toString(), "{}"));
      try {
        runtime.claim();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        """
                        UPDATE platform_job SET lease_until=clock_timestamp()+interval '15 seconds' WHERE id=:id
                        """)
                    .param("id", job)
                    .update());
        try (Connection occupied = businessPool.getConnection()) {
          assertTrue(occupied.isValid(1));
          long deadline = System.nanoTime() + java.time.Duration.ofSeconds(13).toNanos();
          boolean renewed = false;
          while (!renewed && System.nanoTime() < deadline) {
            renewed =
                transactions.run(
                    scope,
                    () ->
                        jdbc.sql(
                                """
                                SELECT lease_until>clock_timestamp()+interval '40 seconds' FROM platform_job WHERE id=:id
                                """)
                            .param("id", job)
                            .query(Boolean.class)
                            .single());
            if (!renewed) {
              Thread.sleep(100);
            }
          }
          assertTrue(renewed, "Heartbeat must not wait for the occupied business connection");
        }
        assertFalse(
            control
                .jdbc()
                .sql("SELECT repricer_renew_job_lease(:id,0,:owner)")
                .param("id", job)
                .param("owner", UUID.randomUUID())
                .query(Boolean.class)
                .single());
        assertThrows(
            org.springframework.dao.DataAccessException.class,
            () ->
                jdbc.sql("SELECT repricer_renew_job_lease(:id,0,:owner)")
                    .param("id", job)
                    .param("owner", UUID.randomUUID())
                    .query(Boolean.class)
                    .single());
        release.countDown();
        long finishDeadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        String state;
        do {
          state =
              transactions.run(
                  scope,
                  () ->
                      jdbc.sql("SELECT state FROM platform_job WHERE id=:id")
                          .param("id", job)
                          .query(String.class)
                          .single());
          if (!state.equals("SUCCEEDED")) {
            Thread.sleep(20);
          }
        } while (!state.equals("SUCCEEDED") && System.nanoTime() < finishDeadline);
        assertEquals("SUCCEEDED", state);
      } finally {
        release.countDown();
        runtime.destroy();
      }
    }
  }

  @Test
  void everyApplicationTableDeclaresItsContractAndIsolatesRuntimeAccess() {
    List<String> violations =
        jdbc.sql(
                """
                WITH application_tables AS (
                  SELECT c.oid,c.relname,c.relkind,c.reloptions,c.relrowsecurity,c.relforcerowsecurity,
                    obj_description(c.oid,'pg_class') contract
                  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                  WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m')
                    AND c.relname NOT IN ('databasechangelog','databasechangeloglock'))
                SELECT relname||': missing contract' FROM application_tables
                  WHERE contract IS NULL OR NOT (contract LIKE '%owner=%' AND contract LIKE '%scope=%'
                    AND contract LIKE '%grain=%' AND contract LIKE '%key=%'
                    AND contract LIKE '%repeat=%' AND contract LIKE '%history=%')
                UNION ALL
                SELECT relname||': runtime access without forced RLS' FROM application_tables
                  WHERE (has_table_privilege('repricer_api',oid,'SELECT,INSERT,UPDATE,DELETE')
                    OR has_table_privilege('repricer_worker',oid,'SELECT,INSERT,UPDATE,DELETE'))
                    AND NOT ((relrowsecurity AND relforcerowsecurity)
                      OR (relkind='v' AND COALESCE(reloptions @> ARRAY['security_invoker=true'],false)))
                UNION ALL
                SELECT relname||': missing primary key' FROM application_tables t
                  WHERE t.relkind IN ('r','p') AND NOT EXISTS(SELECT 1 FROM pg_constraint k
                    WHERE k.conrelid=t.oid AND k.contype='p')
                UNION ALL
                SELECT relname||': runtime TRUNCATE privilege' FROM application_tables
                  WHERE has_table_privilege('repricer_api',oid,'TRUNCATE')
                    OR has_table_privilege('repricer_worker',oid,'TRUNCATE')
                ORDER BY 1
                """)
            .query(String.class)
            .list();
    assertEquals(List.of(), violations);
  }

  @Test
  void workingRolesCannotBypassRlsOrMutateImmutableHistory() {
    assertFalse(
        jdbc.sql("SELECT rolbypassrls OR rolsuper FROM pg_roles WHERE rolname=current_user")
            .query(Boolean.class)
            .single());
    assertEquals(0L, jdbc.sql("SELECT count(*) FROM access_membership").query(Long.class).single());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.sql("TRUNCATE access_membership CASCADE").update());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.sql("UPDATE access_audit SET details='changed'").update());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.sql("UPDATE marketplace_profile SET status='AVAILABLE'").update());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.sql("UPDATE marketplace_profile SET reason='changed'").update());
    assertEquals(
        0L,
        jdbc.sql(
                """
                SELECT count(*) FROM (VALUES ('repricer_api'),('repricer_worker')) roles(name)
                WHERE has_any_column_privilege(name,'marketplace_profile','UPDATE')
                """)
            .query(Long.class)
            .single());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.sql("CREATE TABLE forbidden_table(id integer)").update());
  }

  @Test
  void rollbackClearsScopeAndNextTransactionCannotSeePreviousCompany() {
    Scope own = new Scope(company, null, admin);
    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.run(
                own,
                () -> {
                  assertEquals(
                      2L,
                      jdbc.sql("SELECT count(*) FROM access_membership WHERE organization_id=:org")
                          .param("org", company)
                          .query(Long.class)
                          .single());
                  throw new IllegalStateException("force rollback");
                }));
    assertEquals(0L, jdbc.sql("SELECT count(*) FROM access_membership").query(Long.class).single());
    Scope stranger = new Scope(company, null, UUID.randomUUID());
    assertThrows(BusinessException.class, () -> transactions.run(stranger, () -> true));
    assertTrue(transactions.run(own, () -> authorization.hasPermission(own, "membership.manage")));
  }

  @Test
  void membershipRevocationDoesNotReactivateOldAccountGrant() {
    UUID account = UUID.randomUUID();
    Scope ownerCompany = new Scope(company, null, admin);
    transactions.run(
        ownerCompany,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'Test account','Europe/Moscow')
                    """)
                .param("id", account)
                .param("org", company)
                .param("external", account.toString())
                .update());
    Scope ownerAccount = new Scope(company, account, admin);
    access.grantAccountAccess(
        ownerAccount, test, AccessService.Role.VIEWER, Set.of(), null, UUID.randomUUID());
    Scope viewer = new Scope(company, account, test);
    assertTrue(transactions.run(viewer, () -> authorization.hasPermission(viewer, "catalog.read")));
    AccessService.Member member =
        access.members(ownerCompany, new AccessQuery("", "", "", "asc", 0, 50)).items().stream()
            .filter(item -> item.userId().equals(test))
            .findFirst()
            .orElseThrow();
    var revoked = access.revokeMembership(ownerCompany, test, member.revision(), UUID.randomUUID());
    assertThrows(BusinessException.class, () -> transactions.run(viewer, () -> true));
    access.reactivateMembership(ownerCompany, test, revoked.revision(), UUID.randomUUID());
    assertThrows(BusinessException.class, () -> transactions.run(viewer, () -> true));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2})
  void recognitionUpgradeSchedulesOneExistingRebuildAndPreservesRetainedPublications(
      int retainedVersion) throws Exception {
    try (var connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE DATABASE recognition_upgrade_" + retainedVersion + " OWNER repricer_migrator");
    }
    String url =
        "jdbc:postgresql://"
            + POSTGRES.getHost()
            + ":"
            + POSTGRES.getMappedPort(5432)
            + "/recognition_upgrade_"
            + retainedVersion;
    var database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(
                new JdbcConnection(
                    DriverManager.getConnection(url, "repricer_migrator", "migration-test-only")));
    try (var liquibase =
        new Liquibase("db/changelog/master.xml", new ClassLoaderResourceAccessor(), database)) {
      var pending = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
      int beforeUpgrade = 0;
      while (beforeUpgrade < pending.size()
          && !pending
              .get(beforeUpgrade)
              .getId()
              .equals("recognition-upgrade-" + (retainedVersion + 1))) {
        beforeUpgrade++;
      }
      assertTrue(beforeUpgrade > 0 && beforeUpgrade < pending.size());
      liquibase.update(beforeUpgrade, new Contexts(), new LabelExpression());
      var upgrade =
          JdbcClient.create(
              new DriverManagerDataSource(url, "repricer_migrator", "migration-test-only"));
      UUID org = UUID.randomUUID(), account = UUID.randomUUID(), author = UUID.randomUUID();
      UUID raw = UUID.randomUUID();
      upgrade
          .sql("INSERT INTO access_organization(id,name) VALUES (:id,'Retained company')")
          .param("id", org)
          .update();
      upgrade
          .sql(
              """
              INSERT INTO access_user(id,issuer,subject,display_name,email)
              VALUES (:id,'https://migration.invalid','author','Retained author','author@migration.invalid')
              """)
          .param("id", author)
          .update();
      upgrade
          .sql(
              """
              INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
              VALUES (:id,:org,'OZON','123','Retained account','Europe/Moscow')
              """)
          .param("id", account)
          .param("org", org)
          .update();
      upgrade
          .sql(
              "INSERT INTO economics_accounting_basis(organization_id,account_id,revision) VALUES"
                  + " (:org,:account,7)")
          .param("org", org)
          .param("account", account)
          .update();
      upgrade
          .sql(
              """
              INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,media_type,state)
              VALUES (:id,:org,:account,:author,'RAW','retained-source','application/json','READY')
              """)
          .param("id", raw)
          .param("org", org)
          .param("account", account)
          .param("author", author)
          .update();
      for (int index = 0; index < 2; index++) {
        upgrade
            .sql(
                """
                INSERT INTO economics_ledger_publication(organization_id,account_id,id,cohort_key,
                  generated_at,source_digest,from_day,until_day,expected_rows,processed_rows,state,
                  raw_file_id,source_publication_id,accounting_revision,recognition_complete,
                  recognition_version)
                VALUES (:org,:account,:id,:cohort,'2026-10-01T00:00:00Z','retained-digest',
                  '2026-09-01','2026-10-01',0,0,'PUBLISHED',:raw,:source,7,true,:version)
                """)
            .param("org", org)
            .param("account", account)
            .param("id", UUID.randomUUID())
            .param("cohort", "retained:" + index)
            .param("raw", raw)
            .param("source", UUID.randomUUID())
            .param("version", retainedVersion)
            .update();
      }
      liquibase.update(new Contexts());
      assertEquals(
          1,
          upgrade
              .sql(
                  "SELECT count(*) FROM platform_job WHERE job_type='FINANCIAL_REBUILD'"
                      + " AND business_key='7:recognition:3'")
              .query(Integer.class)
              .single());
      assertEquals(
          author,
          upgrade
              .sql("SELECT subject_id FROM platform_job" + " WHERE business_key='7:recognition:3'")
              .query(UUID.class)
              .single());
      assertEquals(
          "7",
          upgrade
              .sql(
                  "SELECT payload->>'revision' FROM platform_job"
                      + " WHERE business_key='7:recognition:3'")
              .query(String.class)
              .single());
      assertEquals(
          2,
          upgrade
              .sql(
                  """
                  SELECT count(*) FROM economics_ledger_publication WHERE recognition_version=:version
                    AND source_digest='retained-digest' AND state='PUBLISHED'
                  """)
              .param("version", retainedVersion)
              .query(Integer.class)
              .single());
      liquibase.update(new Contexts());
      assertEquals(
          3 - retainedVersion,
          upgrade.sql("SELECT count(*) FROM platform_job").query(Integer.class).single());
      assertEquals(
          1,
          upgrade
              .sql("SELECT count(*) FROM platform_file WHERE id=:id")
              .param("id", raw)
              .query(Integer.class)
              .single());
    }
  }

  @Test
  void secondMigrationPreservesPublishedData() throws Exception {
    long before =
        transactions.run(
            new Scope(company, null, admin),
            () -> jdbc.sql("SELECT count(*) FROM access_audit").query(Long.class).single());
    migrate();
    long after =
        transactions.run(
            new Scope(company, null, admin),
            () -> jdbc.sql("SELECT count(*) FROM access_audit").query(Long.class).single());
    assertEquals(before, after);
  }

  @Test
  void calculationPageKeepsUnknownValuesAndBatchValidityRejectsChangedInputs() {
    UUID account = UUID.randomUUID();
    UUID offer = UUID.randomUUID();
    UUID publication = UUID.randomUUID();
    UUID policy = UUID.randomUUID();
    UUID assignment = UUID.randomUUID();
    UUID decision = UUID.randomUUID();
    Scope scope = new Scope(company, account, admin);
    Instant now = Instant.parse("2026-10-08T00:00:00Z");
    var marketplace =
        new MarketplaceReadService(
            jdbc, authorization, new JsonCodec(), Clock.fixed(now, ZoneOffset.UTC));
    var validity =
        new DecisionValidityService(
            jdbc,
            new JsonCodec(),
            Clock.fixed(now, ZoneOffset.UTC),
            marketplace,
            new MarketplaceCompetitorService(
                jdbc,
                authorization,
                new AuditService(jdbc),
                outbox,
                Clock.fixed(now, ZoneOffset.UTC)),
            new PolicyService(
                jdbc, new JsonCodec(), authorization, new AuditService(jdbc), outbox, marketplace));
    var calculations = new CurrentEconomicsService(jdbc, new JsonCodec(), authorization, validity);
    transactions.run(
        new Scope(company, null, admin),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'Calculation test','Europe/Moscow')
                    """)
                .param("id", account)
                .param("org", company)
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
              .param("id", publication)
              .param("org", company)
              .param("account", account)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
                    observed_at,publication_id)
                  VALUES (:id,:org,:account,'000456','000456','Unit result',clock_timestamp(),:publication)
                  """)
              .param("id", offer)
              .param("org", company)
              .param("account", account)
              .param("publication", publication)
              .update();
          var page = calculations.page(scope, 0, 50, "000456", "", "name", "asc", null, null, null);
          assertEquals(1, page.total());
          assertEquals("NOT_CALCULATED", page.items().getFirst().status());
          assertNull(page.items().getFirst().profit());
          assertFalse(page.items().getFirst().current());
          var export =
              calculations.projection(
                  scope,
                  new TableExportSource.Query("000456", Map.of(), "name,asc", 0, 50),
                  List.of());
          assertEquals(
              List.of(offer),
              jdbc.sql(export.sql())
                  .params(export.parameters())
                  .query((row, index) -> row.getObject("canonical_id", UUID.class))
                  .list());

          // Only source-head metadata is relevant here; this test never executes the policy.
          jdbc.sql(
                  """
                  INSERT INTO automation_policy(organization_id,id,name,description,status,version,
                    revision,draft,author_id) VALUES (:org,:id,'Head comparison','','ACTIVE',1,1,'{}',:author)
                  """)
              .param("org", company)
              .param("id", policy)
              .param("author", admin)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO automation_assignment(organization_id,account_id,id,policy_id,scope_kind,
                    target_id,mode,enabled,paused,revision)
                  VALUES (:org,:account,:id,:policy,'OFFER',:offer,'MANUAL',true,false,1)
                  """)
              .param("org", company)
              .param("account", account)
              .param("id", assignment)
              .param("policy", policy)
              .param("offer", offer)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO economics_cost_head(organization_id,account_id,offer_id,revision)
                  VALUES (:org,:account,:offer,1)
                  """)
              .param("org", company)
              .param("account", account)
              .param("offer", offer)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO economics_tax_head(organization_id,account_id,revision)
                  VALUES (:org,:account,1)
                  """)
              .param("org", company)
              .param("account", account)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO economics_safety_envelope(organization_id,account_id,revision,settings,
                    current_revision,author_id) VALUES (:org,:account,1,'{}',true,:author)
                  """)
              .param("org", company)
              .param("account", account)
              .param("author", admin)
              .update();
          UUID rawFile = UUID.randomUUID();
          jdbc.sql(
                  """
                  INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,
                    media_type,state) VALUES (:id,:org,:account,:author,'RAW',:key,'application/json','READY')
                  """)
              .param("id", rawFile)
              .param("org", company)
              .param("account", account)
              .param("author", admin)
              .param("key", rawFile.toString())
              .update();
          String source =
              new JsonCodec()
                  .encode(
                      Map.of(
                          "complete",
                          true,
                          "validUntil",
                          now.plusSeconds(60).toString(),
                          "price",
                          "100"));
          jdbc.sql(
                  """
                  INSERT INTO marketplace_commercial_state(organization_id,account_id,offer_id,revision,
                    snapshot,current,raw_file_id) VALUES (:org,:account,:offer,1,CAST(:source AS jsonb),true,:raw)
                  """)
              .param("org", company)
              .param("account", account)
              .param("offer", offer)
              .param("source", source)
              .param("raw", rawFile)
              .update();
          UUID placement = UUID.randomUUID();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_placement(id,organization_id,account_id,offer_id,external_id,
                    model,status,available,fields,publication_id,observed_at,valid_until)
                  VALUES (:id,:org,:account,:offer,'test-placement','FBS','ACTIVE',true,'{}',:publication,
                    :observed,:until)
                  """)
              .param("id", placement)
              .param("org", company)
              .param("account", account)
              .param("offer", offer)
              .param("publication", publication)
              .param("observed", java.sql.Timestamp.from(now.minusSeconds(5)))
              .param("until", java.sql.Timestamp.from(now.plusSeconds(60)))
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_economic_terms(organization_id,account_id,offer_id,placement_id,
                    revision,terms,current,raw_file_id)
                  VALUES (:org,:account,:offer,:placement,1,CAST(:source AS jsonb),true,:raw)
                  """)
              .param("org", company)
              .param("account", account)
              .param("offer", offer)
              .param("placement", placement)
              .param("source", source)
              .param("raw", rawFile)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_stock_pool(id,organization_id,account_id,external_id,offer_id,
                    name,warehouse_type,free_quantity,obligations_covered,observed_at,valid_until,complete)
                  VALUES (:id,:org,:account,'test-pool',:offer,'Pool','FBS',20,0,:observed,:until,true)
                  """)
              .param("id", UUID.randomUUID())
              .param("org", company)
              .param("account", account)
              .param("offer", offer)
              .param("observed", java.sql.Timestamp.from(now.minusSeconds(5)))
              .param("until", java.sql.Timestamp.from(now.plusSeconds(60)))
              .update();
          var basis =
              new DecisionService.Basis(
                  offer,
                  policy,
                  1,
                  assignment,
                  1,
                  1,
                  1,
                  1,
                  1,
                  now.minusSeconds(5),
                  now.plusSeconds(60),
                  300,
                  true,
                  0,
                  null,
                  0,
                  false,
                  marketplace.decisionFingerprint(scope, offer),
                  null,
                  "");
          assertEquals(Set.of(decision), validity.current(scope, Map.of(decision, basis)));
          jdbc.sql(
                  """
                  UPDATE marketplace_commercial_state SET current=false
                  WHERE offer_id=:offer AND current
                  """)
              .param("offer", offer)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_commercial_state(organization_id,account_id,offer_id,revision,
                    snapshot,current,raw_file_id)
                  VALUES (:org,:account,:offer,2,jsonb_set(CAST(:source AS jsonb),'{price}','"101"'),true,:raw)
                  """)
              .param("org", company)
              .param("account", account)
              .param("offer", offer)
              .param("source", source)
              .param("raw", rawFile)
              .update();
          assertTrue(validity.current(scope, Map.of(decision, basis)).isEmpty());
          jdbc.sql(
                  """
                  UPDATE marketplace_commercial_state SET current=false
                  WHERE offer_id=:offer AND current
                  """)
              .param("offer", offer)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_commercial_state(organization_id,account_id,offer_id,revision,
                    snapshot,current,raw_file_id)
                  VALUES (:org,:account,:offer,3,CAST(:source AS jsonb),true,:raw)
                  """)
              .param("org", company)
              .param("account", account)
              .param("offer", offer)
              .param("source", source)
              .param("raw", rawFile)
              .update();
          assertEquals(Set.of(decision), validity.current(scope, Map.of(decision, basis)));
          jdbc.sql(
                  """
                  UPDATE economics_cost_head SET revision=2
                  WHERE organization_id=:org AND account_id=:account AND offer_id=:offer
                  """)
              .param("org", company)
              .param("account", account)
              .param("offer", offer)
              .update();
          assertTrue(validity.current(scope, Map.of(decision, basis)).isEmpty());
          return true;
        });
    Scope foreign = new Scope(UUID.randomUUID(), account, admin);
    assertThrows(
        BusinessException.class,
        () ->
            transactions.run(
                foreign,
                () -> calculations.page(foreign, 0, 50, "", "", "id", "asc", null, null, null)));
  }

  @Test
  void importPublishesWholeOverlayPreservesFutureIntervalsAndRejectsStaleCommit() {
    UUID account = UUID.randomUUID();
    UUID offer = UUID.randomUUID();
    UUID publication = UUID.randomUUID();
    Scope scope = new Scope(company, account, admin);
    transactions.run(
        new Scope(company, null, admin),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'Import test','Europe/Moscow')
                    """)
                .param("id", account)
                .param("org", company)
                .param("external", account.toString())
                .update());
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
                    VALUES (:id,:org,:account,'CATALOG','PUBLISHED')
                    """)
                .param("id", publication)
                .param("org", company)
                .param("account", account)
                .update());
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,observed_at,publication_id)
                    VALUES (:id,:org,:account,'000123','000123','Exact input',clock_timestamp(),:publication)
                    """)
                .param("id", offer)
                .param("org", company)
                .param("account", account)
                .param("publication", publication)
                .update());
    var accountingBasis = new AccountingBasisService(jdbc, outbox);
    var economics =
        new EconomicsService(jdbc, authorization, new AuditService(jdbc), outbox, accountingBasis);
    var imports =
        new AccountingImportService(
            jdbc,
            new NamedParameterJdbcTemplate(dataSource),
            authorization,
            new AuditService(jdbc),
            outbox,
            accountingBasis);
    transactions.run(
        scope,
        () ->
            economics.publishCost(
                scope,
                new EconomicsService.CostInput(
                    offer,
                    new BigDecimal("500"),
                    new BigDecimal("10"),
                    LocalDate.parse("2026-01-01"),
                    null,
                    0)));
    transactions.run(
        scope,
        () ->
            economics.publishCost(
                scope,
                new EconomicsService.CostInput(
                    offer,
                    new BigDecimal("550"),
                    new BigDecimal("10"),
                    LocalDate.parse("2026-07-01"),
                    null,
                    1)));
    UUID set = UUID.randomUUID();
    transactions.run(
        scope,
        () -> {
          imports.begin(scope, set, AccountingImportService.Kind.COSTS);
          imports.stageCosts(
              scope,
              set,
              List.of(
                  new AccountingImportService.StagedCost(
                      offer,
                      LocalDate.parse("2026-02-15"),
                      LocalDate.parse("2026-02-20"),
                      new BigDecimal("520"),
                      null,
                      2)));
          return imports.seal(scope, set);
        });
    assertFalse(transactions.run(scope, () -> imports.prepareNext(scope, set)));
    assertTrue(transactions.run(scope, () -> imports.prepareNext(scope, set)));
    assertEquals(2, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
    transactions.run(scope, () -> imports.commit(scope, set));
    var result = transactions.run(scope, () -> economics.getCost(scope, offer));
    assertEquals(3, result.revision());
    assertEquals(4, result.intervals().size());
    assertEquals(0, result.intervals().get(1).amount().compareTo(new BigDecimal("520")));
    assertEquals(0, result.intervals().get(2).amount().compareTo(new BigDecimal("500")));
    assertEquals(0, result.intervals().get(3).amount().compareTo(new BigDecimal("550")));
    assertTrue(
        result.intervals().stream()
            .allMatch(interval -> interval.extraExpense().compareTo(BigDecimal.TEN) == 0));
    transactions.run(scope, () -> imports.commit(scope, set));
    assertEquals(3, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());

    UUID stale = UUID.randomUUID();
    transactions.run(
        scope,
        () -> {
          imports.begin(scope, stale, AccountingImportService.Kind.COSTS);
          imports.stageCosts(
              scope,
              stale,
              List.of(
                  new AccountingImportService.StagedCost(
                      offer,
                      LocalDate.parse("2026-03-01"),
                      LocalDate.parse("2026-03-02"),
                      new BigDecimal("530"),
                      null,
                      3)));
          return imports.seal(scope, stale);
        });
    transactions.run(scope, () -> imports.prepareNext(scope, stale));
    transactions.run(scope, () -> imports.prepareNext(scope, stale));
    transactions.run(
        scope,
        () ->
            economics.publishCost(
                scope,
                new EconomicsService.CostInput(
                    offer,
                    new BigDecimal("550"),
                    new BigDecimal("12"),
                    LocalDate.parse("2026-07-01"),
                    null,
                    3)));
    assertThrows(
        BusinessException.class, () -> transactions.run(scope, () -> imports.commit(scope, stale)));
    assertEquals(4, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
  }

  @Test
  void twoFileWorksAcrossInstancesLeaveThirdWaiting() {
    var firstInstance = new FileWorkGate(jdbc, Clock.systemUTC());
    var secondInstance = new FileWorkGate(jdbc, Clock.systemUTC());
    var deadline = java.time.Instant.now().plusSeconds(60);
    try (var first = firstInstance.tryAcquire(deadline).orElseThrow();
        var second = secondInstance.tryAcquire(deadline).orElseThrow()) {
      assertTrue(secondInstance.tryAcquire(deadline).isEmpty());
      first.confirm();
      second.confirm();
    }
    try (var available = secondInstance.tryAcquire(deadline).orElseThrow()) {
      available.confirm();
    }
  }

  @Test
  void serverSelectionCapturesAllRowsOnceAndCannotLeakToAnotherAccount() {
    UUID account = UUID.randomUUID();
    Scope scope = new Scope(company, account, admin);
    transactions.run(
        new Scope(company, null, admin),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'Selection test','Europe/Moscow')
                    """)
                .param("id", account)
                .param("org", company)
                .param("external", account.toString())
                .update());
    var registry = new DefaultListableBeanFactory();
    registry.registerSingleton(
        "source",
        new TableExportSource() {
          @Override
          public String resource() {
            return "fixture";
          }

          @Override
          public Set<String> permissions() {
            return Set.of("catalog.read");
          }

          @Override
          public Projection projection(Scope selected, Query query, List<UUID> ids) {
            return new Projection(
                """
                SELECT md5(i::text)::uuid AS canonical_id,
                  jsonb_build_array(('000'||i)::text,:label) AS cells,
                  '["finance.read"]'::jsonb AS permissions
                FROM generate_series(1,3) i ORDER BY i
                """,
                Map.of("label", query.search()),
                List.of(new Column("sku", "Артикул", false), new Column("name", "Имя", false)),
                "AS_PUBLISHED",
                true);
          }
        });
    var service =
        new ru.oritas.repricer.app.files.SelectionService(
            jdbc,
            authorization,
            new IdempotencyService(jdbc, new JsonCodec()),
            registry.getBeanProvider(TableExportSource.class),
            new JsonCodec(),
            Clock.systemUTC(),
            outbox);
    var request =
        new ru.oritas.repricer.app.files.SelectionService.Request(
            UUID.randomUUID(),
            0,
            "fixture",
            new TableExportSource.Query("Original", Map.of(), "id,asc", 2, 1),
            null);
    var selection = transactions.run(scope, () -> service.create(scope, request));
    assertEquals(3, selection.total());
    assertEquals(selection, transactions.run(scope, () -> service.create(scope, request)));
    assertEquals(
        List.of("0001", "Original"),
        transactions.run(scope, () -> service.rows(scope, selection.id(), 0).getFirst().cells()));
    var grant =
        access.grantAccountAccess(
            scope,
            test,
            AccessService.Role.MANAGER,
            Set.of("finance.read"),
            null,
            UUID.randomUUID());
    Scope operator = new Scope(company, account, test);
    assertEquals(
        3, transactions.run(operator, () -> service.get(operator, selection.id())).total());
    access.grantAccountAccess(
        scope, test, AccessService.Role.MANAGER, Set.of(), grant.revision(), UUID.randomUUID());
    assertThrows(
        BusinessException.class,
        () -> transactions.run(operator, () -> service.get(operator, selection.id())));
    assertThrows(
        BusinessException.class,
        () ->
            transactions.run(
                scope,
                () ->
                    service.create(
                        scope,
                        new ru.oritas.repricer.app.files.SelectionService.Request(
                            request.clientRequestId(),
                            0,
                            "fixture",
                            new TableExportSource.Query("Changed", Map.of(), "id,asc", 0, 1),
                            null))));
    Scope foreign = new Scope(company, UUID.randomUUID(), admin);
    assertThrows(
        BusinessException.class,
        () -> transactions.run(foreign, () -> service.get(foreign, selection.id())));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            transactions.run(
                scope,
                () ->
                    jdbc.sql("UPDATE app_selection_row SET cells='[]' WHERE selection_id=:id")
                        .param("id", selection.id())
                        .update()));
  }

  @Test
  void organizationAccessSelectionPreservesQueryAndRejectsAccountScopeLinks() {
    UUID organization = access.createOrganization(admin, UUID.randomUUID(), "Export access").id();
    Scope scope = new Scope(organization, null, admin);
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO access_membership(id,organization_id,subject_id,role)
                    VALUES (:id,:org,:user,'MEMBER')
                    """)
                .param("id", UUID.randomUUID())
                .param("org", organization)
                .param("user", test)
                .update());
    var page = access.members(scope, new AccessQuery("", "ACTIVE", "role", "asc", 0, 1));
    assertEquals(2, page.total());
    assertEquals("MEMBER", page.items().getFirst().role());
    assertEquals(0, access.members(scope, new AccessQuery("%", "", "name", "asc", 0, 50)).total());
    var registry = new DefaultListableBeanFactory();
    registry.registerSingleton(
        "members",
        new TableExportSource() {
          @Override
          public String resource() {
            return "memberships";
          }

          @Override
          public Set<String> permissions() {
            return Set.of("membership.read");
          }

          @Override
          public Projection projection(Scope selected, Query query, List<UUID> ids) {
            return access.memberProjection(selected, AccessQuery.from(query), ids);
          }
        });
    var selections =
        new ru.oritas.repricer.app.files.SelectionService(
            jdbc,
            authorization,
            new IdempotencyService(jdbc, new JsonCodec()),
            registry.getBeanProvider(TableExportSource.class),
            new JsonCodec(),
            Clock.systemUTC(),
            outbox);
    var snapshot =
        transactions.run(
            scope,
            () ->
                selections.create(
                    scope,
                    new ru.oritas.repricer.app.files.SelectionService.Request(
                        UUID.randomUUID(),
                        0,
                        "memberships",
                        new TableExportSource.Query(
                            "", Map.of("status", "ACTIVE"), "role,asc", 0, 1),
                        List.of())));
    assertEquals(2, snapshot.total());
    assertEquals(
        "MEMBER",
        transactions
            .run(scope, () -> selections.rows(scope, snapshot.id(), 0))
            .getFirst()
            .cells()
            .get(2));
    UUID account = UUID.randomUUID();
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'Account query','UTC')
                    """)
                .param("id", account)
                .param("org", organization)
                .param("external", account.toString())
                .update());
    Scope accountScope = new Scope(organization, account, admin);
    var grant =
        access.grantAccountAccess(
            accountScope, test, AccessService.Role.VIEWER, Set.of(), null, UUID.randomUUID());
    var accountPage =
        access.accountAccess(
            accountScope, new AccessQuery(grant.email(), "ACTIVE", "name", "asc", 0, 50));
    assertEquals(1, accountPage.total());
    assertEquals(grant.displayName(), accountPage.items().getFirst().displayName());
    assertThrows(
        BusinessException.class,
        () -> transactions.run(accountScope, () -> selections.get(accountScope, snapshot.id())));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            transactions.run(
                accountScope,
                () ->
                    jdbc.sql(
                            """
                            INSERT INTO app_selection_row(organization_id,account_id,selection_id,ordinal,canonical_id,cells)
                            VALUES (:org,:account,:selection,3,:canonical,'[]')
                            """)
                        .param("org", organization)
                        .param("account", account)
                        .param("selection", snapshot.id())
                        .param("canonical", UUID.randomUUID())
                        .update()));
  }

  @Test
  void grantingAccessAgainDoesNotReviveAnOldAutoDelegation() {
    UUID account = UUID.randomUUID();
    transactions.run(
        new Scope(company, null, admin),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'AUTO test','Europe/Moscow')
                    """)
                .param("id", account)
                .param("org", company)
                .param("external", account.toString())
                .update());
    Scope owner = new Scope(company, account, admin);
    var issued =
        access.grantAccountAccess(
            owner,
            test,
            AccessService.Role.MANAGER,
            Set.of("finance.read"),
            null,
            UUID.randomUUID());
    Scope operator = new Scope(company, account, test);
    var service =
        new ru.oritas.repricer.access.AutomationGrantService(
            jdbc, authorization, new AuditService(jdbc), new JsonCodec(), outbox);
    UUID assignment = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    var grant =
        transactions.run(
            operator,
            () ->
                service.grant(
                    operator,
                    assignment,
                    1,
                    1,
                    "f".repeat(64),
                    Set.of("SET_BASE_PRICE"),
                    Set.of(target),
                    0));
    transactions.run(owner, () -> service.requireCurrent(owner, grant.id(), grant.binding()));
    var revoked = access.revokeAccountAccess(owner, test, issued.revision(), UUID.randomUUID());
    access.grantAccountAccess(
        owner,
        test,
        AccessService.Role.MANAGER,
        Set.of("finance.read"),
        revoked.revision(),
        UUID.randomUUID());
    assertEquals(
        "AUTO_AUTHORITY_STALE",
        assertThrows(
                BusinessException.class,
                () ->
                    transactions.run(
                        owner, () -> service.requireCurrent(owner, grant.id(), grant.binding())))
            .code());
  }

  @Test
  void historyFilteringUsesCabinetDateAndExportsTheSameWholeSelection() {
    UUID account = UUID.randomUUID();
    Scope scope = new Scope(company, account, admin);
    transactions.run(
        new Scope(company, null, admin),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'History test','Europe/Saratov')
                    """)
                .param("id", account)
                .param("org", company)
                .param("external", account.toString())
                .update());
    transactions.run(
        scope,
        () -> {
          for (String date :
              List.of("2026-01-01T19:30:00Z", "2026-01-01T20:30:00Z", "2026-01-02T10:30:00Z")) {
            UUID id = UUID.randomUUID();
            jdbc.sql(
                    """
                    INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,
                      business_key,payload,state,due_at,created_at)
                    VALUES (:id,:org,:account,:actor,'REPORT_EXPORT','canonicalization',:key,'{}',
                      'WAITING',clock_timestamp(),:at)
                    """)
                .param("id", id)
                .param("org", company)
                .param("account", account)
                .param("actor", admin)
                .param("key", id.toString())
                .param("at", java.sql.Timestamp.from(Instant.parse(date)))
                .update();
            jdbc.sql(
                    """
                    INSERT INTO access_audit(id,organization_id,account_id,subject_id,action,details,occurred_at)
                    VALUES (:id,:org,:account,:actor,'REPORT_TEST','safe details',:at)
                    """)
                .param("id", UUID.randomUUID())
                .param("org", company)
                .param("account", account)
                .param("actor", admin)
                .param("at", java.sql.Timestamp.from(Instant.parse(date)))
                .update();
          }
          var query =
              new ru.oritas.repricer.platform.HistoryQuery(
                  "REPORT",
                  "WAITING",
                  LocalDate.of(2026, 1, 2),
                  LocalDate.of(2026, 1, 2),
                  "createdAt",
                  "asc",
                  0,
                  1);
          var zone = java.time.ZoneId.of("Europe/Saratov");
          var operations = new ru.oritas.repricer.platform.OperationService(jdbc);
          var page = operations.list(query, zone);
          assertEquals(2, page.total());
          assertEquals(1, page.items().size());
          assertEquals(Instant.parse("2026-01-01T20:30:00Z"), page.items().getFirst().createdAt());
          var export = operations.projection(query, zone, List.of());
          assertEquals(
              2,
              jdbc.sql(export.sql())
                  .params(export.parameters())
                  .query((row, index) -> row.getObject("canonical_id", UUID.class))
                  .list()
                  .size());
          var auditQuery =
              new AuditService.Query(
                  new ru.oritas.repricer.platform.HistoryQuery(
                      "safe details",
                      "REPORT_TEST",
                      query.from(),
                      query.to(),
                      query.sort(),
                      query.direction(),
                      0,
                      1),
                  null);
          var audit = new AuditService(jdbc);
          assertEquals(2, audit.list(scope, auditQuery, zone).total());
          var auditExport = audit.projection(scope, auditQuery, zone, List.of());
          assertEquals(
              2,
              jdbc.sql(auditExport.sql())
                  .params(auditExport.parameters())
                  .query((row, index) -> row.getObject("canonical_id", UUID.class))
                  .list()
                  .size());
          assertEquals(
              0,
              operations
                  .list(
                      new ru.oritas.repricer.platform.HistoryQuery(
                          "%", "", null, null, "id", "asc", 0, 50),
                      zone)
                  .total());
        });
  }

  @Test
  void productAuditAndExportUseTheSameExactSubjectInsteadOfAccountHistoryOrFreeText() {
    UUID account = UUID.randomUUID();
    UUID offer = UUID.randomUUID();
    UUID otherOffer = UUID.randomUUID();
    UUID publication = UUID.randomUUID();
    Scope organization = new Scope(company, null, admin);
    transactions.run(
        organization,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'Audit subjects','Europe/Moscow')
                    """)
                .param("id", account)
                .param("org", company)
                .param("external", account.toString())
                .update());
    Scope scope = new Scope(company, account, admin);
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
          for (UUID id : List.of(offer, otherOffer)) {
            jdbc.sql(
                    """
                    INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,observed_at,publication_id)
                    VALUES (:id,:org,:account,:external,:external,'Audit offer',clock_timestamp(),:publication)
                    """)
                .param("id", id)
                .param("org", company)
                .param("account", account)
                .param("external", id.toString())
                .param("publication", publication)
                .update();
          }
          var audit = new AuditService(jdbc);
          UUID command = UUID.randomUUID();
          audit.recordForOffer(scope, "COMMAND_TEST", command, offer, "revision=1");
          audit.recordForOffer(
              scope, "OTHER_PRODUCT", UUID.randomUUID(), otherOffer, offer.toString());
          audit.record(scope, "ACCOUNT_EVENT", account, offer.toString());
          var query =
              AuditService.Query.from(
                  new TableExportSource.Query(
                      "", Map.of("offerId", offer.toString()), "createdAt,desc", 0, 1));
          var page = audit.list(scope, query, ZoneOffset.UTC);
          assertEquals(1, page.total());
          assertEquals(command, page.items().getFirst().targetId());
          var exported = audit.projection(scope, query, ZoneOffset.UTC, List.of());
          assertEquals(
              List.of(page.items().getFirst().id()),
              jdbc.sql(exported.sql())
                  .params(exported.parameters())
                  .query((row, index) -> row.getObject("canonical_id", UUID.class))
                  .list());
        });
  }

  @Test
  void realtimeSharesTransportAcrossScopesWithoutMissingChangesOrSurvivingRevocation()
      throws Exception {
    UUID account = UUID.randomUUID();
    UUID foreignAccount = UUID.randomUUID();
    Scope organization = new Scope(company, null, admin);
    for (UUID id : List.of(account, foreignAccount)) {
      transactions.run(
          organization,
          () ->
              jdbc.sql(
                      """
                      INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                      VALUES (:id,:org,'OZON',:external,'Realtime test','Europe/Moscow')
                      """)
                  .param("id", id)
                  .param("org", company)
                  .param("external", id.toString())
                  .update());
    }
    Scope owner = new Scope(company, account, admin);
    var grant =
        access.grantAccountAccess(
            owner, test, AccessService.Role.VIEWER, Set.of(), null, UUID.randomUUID());
    JsonCodec json = new JsonCodec();
    var gateway = new RealtimeEventGateway(jdbc, json, authorization);
    Instant now = Instant.now();
    var socket = new RealtimeSocket(transactions, gateway, json, Clock.fixed(now, ZoneOffset.UTC));
    var jwt =
        Jwt.withTokenValue("integration-session")
            .header("alg", "RS256")
            .issuer("https://test.invalid/realm")
            .subject("test")
            .issuedAt(now.minusSeconds(1))
            .expiresAt(now.plusSeconds(60))
            .build();
    WebSocketSession session = mock(WebSocketSession.class);
    List<String> messages = new ArrayList<>();
    List<CloseStatus> closed = new ArrayList<>();
    when(session.getId()).thenReturn(UUID.randomUUID().toString());
    when(session.getPrincipal()).thenReturn(new JwtAuthenticationToken(jwt));
    when(session.isOpen()).thenAnswer(invocation -> closed.isEmpty());
    doAnswer(
            invocation -> {
              Object message = invocation.getArgument(0);
              if (message instanceof TextMessage text) {
                messages.add(text.getPayload());
              }
              return null;
            })
        .when(session)
        .sendMessage(any());
    doAnswer(
            invocation -> {
              closed.add(invocation.getArgument(0, CloseStatus.class));
              return null;
            })
        .when(session)
        .close(any(CloseStatus.class));
    socket.afterConnectionEstablished(session);
    socket.handleMessage(
        session,
        new TextMessage(
            json.encode(
                Map.of(
                    "type",
                    "subscribe",
                    "subscriptionId",
                    "organization",
                    "organizationId",
                    company,
                    "resources",
                    Set.of("organizations")))));
    socket.handleMessage(
        session,
        new TextMessage(
            json.encode(
                Map.of(
                    "type",
                    "subscribe",
                    "subscriptionId",
                    "account",
                    "organizationId",
                    company,
                    "accountId",
                    account,
                    "resources",
                    Set.of("offers")))));
    assertTrue(closed.isEmpty());
    assertEquals(2, messages.size());
    assertTrue(messages.stream().allMatch(message -> message.contains("subscribed")));
    UUID organizationChange = UUID.randomUUID();
    UUID accountChange = UUID.randomUUID();
    UUID foreignChange = UUID.randomUUID();
    transactions.run(
        organization,
        () ->
            gateway.receive(
                organization,
                UUID.randomUUID(),
                "ORGANIZATION_UPDATED",
                json.encode(
                    new OutboxService.EntityChange("organizations", organizationChange, 1))));
    transactions.run(
        owner,
        () ->
            gateway.receive(
                owner,
                UUID.randomUUID(),
                "OFFER_UPDATED",
                json.encode(new OutboxService.EntityChange("offers", accountChange, 1))));
    Scope foreign = new Scope(company, foreignAccount, admin);
    transactions.run(
        foreign,
        () ->
            gateway.receive(
                foreign,
                UUID.randomUUID(),
                "OFFER_UPDATED",
                json.encode(new OutboxService.EntityChange("offers", foreignChange, 1))));
    socket.deliver();
    assertEquals(4, messages.size());
    assertTrue(
        messages.stream().anyMatch(message -> message.contains(organizationChange.toString())));
    assertTrue(messages.stream().anyMatch(message -> message.contains(accountChange.toString())));
    assertTrue(messages.stream().noneMatch(message -> message.contains(foreignChange.toString())));
    socket.deliver();
    assertEquals(4, messages.size());
    access.revokeAccountAccess(owner, test, grant.revision(), UUID.randomUUID());
    socket.deliver();
    assertEquals(List.of(4003), closed.stream().map(CloseStatus::getCode).toList());
    assertEquals(4, messages.size());
  }
}
