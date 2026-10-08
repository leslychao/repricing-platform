package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AccessService;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.AutomationGrantService;
import ru.oritas.repricer.access.IdentityService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.automation.decision.CurrentEconomicsService;
import ru.oritas.repricer.automation.decision.DecisionContinuationService;
import ru.oritas.repricer.automation.decision.DecisionEngine;
import ru.oritas.repricer.automation.decision.DecisionFileRetentionOwner;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.automation.decision.DecisionValidityService;
import ru.oritas.repricer.automation.decision.QuantityComparisonService;
import ru.oritas.repricer.automation.execution.CommandStateMachine.State;
import ru.oritas.repricer.automation.execution.ExecutionHandler;
import ru.oritas.repricer.automation.execution.ExecutionService;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.automation.runtime.AutoService;
import ru.oritas.repricer.automation.runtime.RuntimeService;
import ru.oritas.repricer.automation.runtime.ScenarioService;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.economics.RiskLimitService;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.marketplace.CommercialGateway;
import ru.oritas.repricer.marketplace.CommercialGateway.CommercialEffect;
import ru.oritas.repricer.marketplace.CommercialGateway.EffectEvidence;
import ru.oritas.repricer.marketplace.CommercialGateway.EffectState;
import ru.oritas.repricer.marketplace.CommercialGateway.Operation;
import ru.oritas.repricer.marketplace.CommercialGateway.Outcome;
import ru.oritas.repricer.marketplace.CommercialGateway.PreparedCommercialCommand;
import ru.oritas.repricer.marketplace.CommercialGateway.Reconciliation;
import ru.oritas.repricer.marketplace.CommercialGateway.SendResult;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.ObjectStorage;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** Real PostgreSQL and worker leases; the HTTP peer deliberately loses its response. */
class ExecutionIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("test-container-only");
  private static final Clock CLOCK = Clock.systemUTC();
  private static final JsonCodec JSON = new JsonCodec();
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner transactions;
  private static AuthorizationService authorization;
  private static OutboxService outbox;
  private static AccessService access;
  private static UUID organization;
  private static UUID admin;
  private static UUID viewer;

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
    var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), "repricer_migrator", "migration-test-only");
    var database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    try (Liquibase liquibase =
        new Liquibase("db/changelog/master.xml", new ClassLoaderResourceAccessor(), database)) {
      liquibase.update(new liquibase.Contexts());
    }
    var source =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_worker", "worker-test-only");
    jdbc = JdbcClient.create(source);
    authorization = new AuthorizationService(jdbc);
    transactions =
        new ScopeTransactionRunner(jdbc, new DataSourceTransactionManager(source), authorization);
    var beans = new DefaultListableBeanFactory();
    outbox =
        new OutboxService(
            jdbc, JSON, mock(JobRuntime.class), beans.getBeanProvider(OutboxRecipient.class));
    access =
        new AccessService(
            jdbc,
            transactions,
            authorization,
            new IdempotencyService(jdbc, JSON),
            new AuditService(jdbc),
            outbox,
            new IdentityService(jdbc));
    admin = IdentityService.userId("https://execution.test.invalid/realm", "admin");
    viewer = IdentityService.userId("https://execution.test.invalid/realm", "test");
    organization = access.initManagedUsers("https://execution.test.invalid/realm", "admin", "test");
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void exhaustedSearchCannotBeApprovedOrCreateACommandDespiteAnAlreadySafeCandidate()
      throws Exception {
    var fixture = fixture();
    Scope scope = fixture.scope();
    UUID decisionId = UUID.randomUUID();
    UUID fileId = UUID.randomUUID();
    var context =
        new DecisionEngine.Context(
            settings().regularRule(),
            null,
            Set.of(PolicySettings.Operation.SET_BASE_PRICE),
            Set.of(),
            Set.of(fixture.offerId()),
            true,
            false,
            true,
            false,
            false,
            false,
            true,
            5,
            false,
            BigDecimal.ZERO,
            null);
    var candidates =
        List.of(
            approvalCandidate(fixture.offerId(), "100"),
            approvalCandidate(fixture.offerId(), "101"));
    var exhausted = new DecisionEngine().decide(context, candidates);
    assertEquals("INCOMPLETE_SEARCH", exhausted.status());
    assertEquals(1, exhausted.evaluations().size());
    assertTrue(exhausted.evaluations().getFirst().safe());
    assertTrue(exhausted.evaluations().getFirst().reachesTarget());
    assertNull(exhausted.selectedCandidate());
    Instant now = CLOCK.instant();
    var basis =
        new DecisionService.Basis(
            fixture.offerId(),
            null,
            0,
            null,
            0,
            1,
            0,
            0,
            0,
            now,
            now.plusSeconds(300),
            300,
            true,
            0,
            null,
            0,
            false,
            "ready-input",
            null,
            "");
    var snapshot = new DecisionService.Snapshot(basis, context, candidates, exhausted, null, null);
    String encoded = JSON.encode(snapshot);
    byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
    assertTrue(bytes.length < 64 * 1024);
    String key = "test/incomplete-decision/" + fileId;
    transactions.run(
        scope,
        () -> {
          jdbc.sql(
                  """
                  INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,
                    media_type,state,byte_count,sha256,eof_confirmed)
                  VALUES(:id,:org,:account,:subject,'DECISION',:key,'application/json','STORED',:bytes,:digest,true)
                  """)
              .param("id", fileId)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("subject", scope.subjectId())
              .param("key", key)
              .param("bytes", bytes.length)
              .param("digest", IdempotencyService.sha256(encoded))
              .update();
          jdbc.sql(
                  """
                  INSERT INTO automation_decision(organization_id,account_id,id,offer_id,policy_version,
                    assignment_revision,offer_revision,cost_revision,tax_revision,safety_revision,state,
                    reason,target_achieved,requires_confirmation,calculated_at,valid_until,maximum_age_seconds,
                    executable,snapshot_file_id,revision,author_id)
                  VALUES(:org,:account,:id,:offer,0,0,1,0,0,0,'CALCULATED','SEARCH_LIMIT',false,false,
                    :now,:until,300,false,:file,1,:author)
                  """)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("id", decisionId)
              .param("offer", fixture.offerId())
              .param("now", Timestamp.from(now))
              .param("until", Timestamp.from(basis.validUntil()))
              .param("file", fileId)
              .param("author", scope.subjectId())
              .update();
        });
    var storage = mock(ObjectStorage.class);
    when(storage.read(key, null))
        .thenAnswer(
            invocation ->
                new ResponseInputStream<>(
                    GetObjectResponse.builder().contentLength((long) bytes.length).build(),
                    AbortableInputStream.create(new ByteArrayInputStream(bytes))));
    var files = new StoredFileService(jdbc, transactions, storage, CLOCK);
    var jobs = mock(JobRuntime.class);
    var gateway = mock(CommercialGateway.class);
    var validity = mock(DecisionValidityService.class);
    var execution =
        new ExecutionService(
            jdbc, JSON, CLOCK, authorization, new AuditService(jdbc), outbox, files, jobs);
    var automatic =
        new AutoService(
            jdbc,
            mock(PolicyService.class),
            mock(AutomationGrantService.class),
            authorization,
            jobs,
            JSON,
            CLOCK,
            outbox,
            mock(MarketplaceReadService.class));
    var decisions =
        new DecisionService(
            jdbc,
            JSON,
            CLOCK,
            transactions,
            authorization,
            new AuditService(jdbc),
            outbox,
            files,
            gateway,
            execution,
            jobs,
            mock(ScenarioService.class),
            validity,
            mock(CurrentEconomicsService.class),
            automatic,
            mock(QuantityComparisonService.class),
            mock(DecisionContinuationService.class));
    var rejected =
        assertThrows(BusinessException.class, () -> decisions.approve(scope, decisionId, 1));
    assertEquals("DECISION_STALE", rejected.code());
    transactions.run(
        scope,
        () -> {
          assertEquals(
              0L,
              jdbc.sql("SELECT count(*) FROM automation_command WHERE decision_id=:id")
                  .param("id", decisionId)
                  .query(Long.class)
                  .single());
          jdbc.sql("SELECT state,revision,approved_at FROM automation_decision WHERE id=:id")
              .param("id", decisionId)
              .query(
                  (row, index) -> {
                    assertEquals("CALCULATED", row.getString(1));
                    assertEquals(1, row.getLong(2));
                    assertNull(row.getTimestamp(3));
                    return true;
                  })
              .single();
        });
    verifyNoInteractions(gateway, jobs, validity);
  }

  private static DecisionEngine.Candidate approvalCandidate(UUID offer, String amount) {
    BigDecimal price = new BigDecimal(amount);
    var economics =
        new DecisionEngine.EconomicOutcome(
            new EconomicCalculator.Input(
                EconomicCalculator.Outcome.KEPT_PURCHASE,
                price,
                new BigDecimal("50"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of()),
            new EconomicCalculator.Safety(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
            null);
    var effect =
        new DecisionEngine.Effect(
            offer,
            "COMMON",
            price,
            new BigDecimal("90"),
            price,
            new BigDecimal("90"),
            new BigDecimal("90"),
            BigDecimal.ONE,
            new BigDecimal("200"),
            new BigDecimal("100"),
            new BigDecimal("100"),
            List.of(economics),
            true,
            true,
            UUID.randomUUID(),
            price,
            new BigDecimal("90"));
    var step =
        new DecisionEngine.Step(
            PolicySettings.Operation.SET_BASE_PRICE,
            offer,
            null,
            price,
            true,
            true,
            BigDecimal.ZERO,
            price.subtract(new BigDecimal("90")));
    return new DecisionEngine.Candidate(
        UUID.randomUUID(), List.of(effect), Set.of(), List.of(step), false, true, Map.of());
  }

  @Test
  void reconciliationSubmissionRejectsStaleCommandRevisionBeforeCreatingWork() {
    Fixture fixture = fixture();
    var jobs = mock(JobRuntime.class);
    var execution =
        new ExecutionService(
            jdbc,
            JSON,
            CLOCK,
            authorization,
            new AuditService(jdbc),
            outbox,
            mock(StoredFileService.class),
            jobs);
    long previousRevision =
        transactions.run(
            fixture.scope(), () -> execution.get(fixture.scope(), fixture.commandId()).revision());
    assertTrue(admit(execution, fixture));
    UUID request = UUID.randomUUID();
    UUID job = UUID.randomUUID();
    when(jobs.submit(any(), anyString(), anyString(), anyString())).thenReturn(job);
    transactions.run(
        fixture.scope(),
        () -> {
          BusinessException stale =
              assertThrows(
                  BusinessException.class,
                  () ->
                      execution.submitReconcile(
                          fixture.scope(), request, fixture.commandId(), previousRevision));
          assertEquals("STALE_REVISION", stale.code());
          verifyNoInteractions(jobs);
          long revision = execution.get(fixture.scope(), fixture.commandId()).revision();
          assertEquals(
              job,
              execution.submitReconcile(fixture.scope(), request, fixture.commandId(), revision));
          verify(jobs)
              .submit(
                  fixture.scope(),
                  "AUTOMATION_RECONCILE",
                  request.toString(),
                  JSON.encode(new ExecutionService.ReconcileRequest(fixture.commandId())));
        });
    assertEquals(1, attemptCount(fixture));
  }

  @Test
  void onlyCompleteReadbackFixesTheImmutableConfirmationTime() {
    Fixture fixture = fixture();
    var execution = execution();
    assertTrue(admit(execution, fixture));
    transactions.run(
        fixture.scope(),
        () -> {
          assertEquals(
              State.UNKNOWN,
              execution.reconcile(
                  fixture.scope(), fixture.commandId(), unknown(fixture.offerId())));
          assertTrue(
              jdbc.sql("SELECT confirmed_at IS NULL FROM automation_command WHERE id=:id")
                  .param("id", fixture.commandId())
                  .query(Boolean.class)
                  .single());
          var command = execution.get(fixture.scope(), fixture.commandId());
          var evidence =
              new Reconciliation(
                  true,
                  true,
                  List.of(
                      new EffectEvidence(
                          fixture.offerId(),
                          "price",
                          EffectState.CONFIRMED,
                          command.journalFileId(),
                          "TEST_CANONICAL_CONFIRMATION")));
          assertEquals(
              State.APPLIED, execution.reconcile(fixture.scope(), fixture.commandId(), evidence));
          Timestamp confirmed =
              jdbc.sql("SELECT confirmed_at FROM automation_command WHERE id=:id")
                  .param("id", fixture.commandId())
                  .query(Timestamp.class)
                  .single();
          assertEquals(
              State.APPLIED, execution.reconcile(fixture.scope(), fixture.commandId(), evidence));
          assertEquals(
              confirmed,
              jdbc.sql("SELECT confirmed_at FROM automation_command WHERE id=:id")
                  .param("id", fixture.commandId())
                  .query(Timestamp.class)
                  .single());
        });
    assertEquals(1, attemptCount(fixture));
  }

  @Test
  void retentionReleasesOnlyOldSupersededUnapprovedPreviewsAndNeverUnknownEvidence() {
    Fixture fixture = fixture();
    assertTrue(admit(execution(), fixture));
    transactions.run(
        fixture.scope(),
        () -> execution().markUnknown(fixture.scope(), fixture.commandId(), "RESPONSE_LOST"));
    UUID eligible = UUID.randomUUID();
    UUID recent = UUID.randomUUID();
    UUID approved = UUID.randomUUID();
    var files = mock(StoredFileService.class);
    var owner = new DecisionFileRetentionOwner(jdbc, files, new AuditService(jdbc), outbox);
    transactions.run(
        fixture.scope(),
        () -> {
          var source = execution().get(fixture.scope(), fixture.commandId());
          for (UUID id : List.of(eligible, recent, approved)) {
            jdbc.sql(
                    """
                    INSERT INTO automation_decision(organization_id,account_id,id,offer_id,policy_version,
                      assignment_revision,offer_revision,cost_revision,tax_revision,safety_revision,state,
                      reason,target_achieved,requires_confirmation,calculated_at,valid_until,
                      maximum_age_seconds,executable,snapshot_file_id,revision,author_id,approved_at)
                    SELECT organization_id,account_id,:id,offer_id,0,0,1,0,0,0,'CALCULATED',
                      'TEST',true,false,clock_timestamp()-(:months*interval '1 month'),
                      clock_timestamp()-(:months*interval '1 month')+interval '5 minutes',
                      300,true,snapshot_file_id,1,author_id,
                      CASE WHEN :approved THEN clock_timestamp()-interval '13 months' ELSE NULL END
                    FROM automation_decision WHERE id=:source
                    """)
                .param("id", id)
                .param("months", id.equals(recent) ? 11 : 13)
                .param("approved", id.equals(approved))
                .param("source", source.decisionId())
                .update();
          }
          jdbc.sql(
                  """
                  INSERT INTO automation_economics_projection(organization_id,account_id,id,
                    offer_id,calculated_at,basis,result_status,result_reason)
                  SELECT organization_id,account_id,id,offer_id,calculated_at,'{}'::jsonb,
                    'CALCULATED','TEST' FROM automation_decision WHERE id=:id
                  """)
              .param("id", source.decisionId())
              .update();
        });
    assertEquals(
        1,
        transactions.runService(
            fixture.scope(),
            owner.permissions(),
            () -> owner.releaseEligible(fixture.scope(), 50)));
    verify(files).releaseOwner(fixture.scope(), "DECISION", eligible, 1);
    verify(files).releaseOwner(fixture.scope(), "DECISION_SEARCH", eligible, 1);
    assertEquals(
        List.of(eligible),
        transactions.run(
            fixture.scope(),
            () ->
                jdbc.sql("SELECT id FROM automation_decision WHERE evidence_expired_at IS NOT NULL")
                    .query(UUID.class)
                    .list()));
    assertEquals(
        0,
        transactions.runService(
            fixture.scope(),
            owner.permissions(),
            () -> owner.releaseEligible(fixture.scope(), 50)));
    assertEquals(
        State.UNKNOWN,
        transactions.run(
            fixture.scope(), () -> execution().get(fixture.scope(), fixture.commandId()).state()));
    assertEquals(1, attemptCount(fixture));
  }

  @Test
  void distinctTargetsAdmitConcurrentlyAfterHoldingTheSharedSourceBoundary() throws Exception {
    Fixture first = fixture();
    Fixture second = fixture(first.scope());
    CountDownLatch sharedBoundary = new CountDownLatch(2);
    var runtime = new RuntimeService(jdbc, authorization, new AuditService(jdbc), outbox, CLOCK);
    var results =
        race(
            () -> admitWithRuntime(first, runtime, sharedBoundary),
            () -> admitWithRuntime(second, runtime, sharedBoundary));
    assertEquals(List.of(true, true), results);
    assertEquals(1, attemptCount(first));
    assertEquals(1, attemptCount(second));
  }

  private static boolean admitWithRuntime(
      Fixture fixture, RuntimeService runtime, CountDownLatch sharedBoundary) {
    return transactions.run(
        fixture.scope(),
        () -> {
          authorization.requireLocked(fixture.scope(), Set.of("decision.approve", "finance.read"));
          jdbc.sql(
                  "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account"
                      + " FOR SHARE")
              .param("org", organization)
              .param("account", fixture.scope().accountId())
              .query(UUID.class)
              .single();
          sharedBoundary.countDown();
          try {
            assertTrue(sharedBoundary.await(3, TimeUnit.SECONDS));
          } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
          }
          runtime.observeStock(
              fixture.scope(),
              fixture.offerId(),
              fixture.offerId(),
              new BigDecimal("100"),
              true,
              new BigDecimal("99"),
              settings());
          runtime.admit(
              fixture.scope(),
              fixture.commandId(),
              fixture.offerId(),
              fixture.offerId(),
              new BigDecimal("99"),
              new BigDecimal("100"),
              settings());
          return admitInTransaction(execution(), fixture);
        });
  }

  @Test
  void twoWorkersAndLostHttpResponseLeaveOneAttemptAndRecoveryOnlyReads() throws Exception {
    Fixture fixture = fixture();
    AtomicInteger remoteWrites = new AtomicInteger();
    HttpServer peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    peer.createContext(
        "/price",
        exchange -> {
          try (var body = exchange.getRequestBody()) {
            assertEquals("{}", new String(body.readNBytes(2), StandardCharsets.UTF_8));
            remoteWrites.incrementAndGet();
          } finally {
            // The write has happened, but no HTTP response reaches the worker.
            exchange.close();
          }
        });
    peer.start();
    try {
      var first = execution();
      var second = execution();
      List<Boolean> admitted =
          race(
              () -> dispatch(first, fixture, peer.getAddress().getPort()),
              () -> dispatch(second, fixture, peer.getAddress().getPort()));
      assertEquals(1, admitted.stream().filter(Boolean::booleanValue).count());
      assertEquals(1, remoteWrites.get());
      assertEquals(1, attemptCount(fixture));
      assertEquals(
          State.UNKNOWN,
          transactions.run(
              fixture.scope(), () -> second.get(fixture.scope(), fixture.commandId()).state()));

      var gateway = mock(CommercialGateway.class);
      when(gateway.reconcile(any(), any(), any())).thenReturn(unknown(fixture.offerId()));
      var restarted = recoveryHandler(execution(), gateway);
      JobOutcome outcome = restarted.execute(context(fixture));
      assertEquals("WAITING", outcome.state());
      verify(gateway).reconcile(any(), any(), any());
      verify(gateway, never()).send(any(), any(), any());
      assertEquals(1, remoteWrites.get());
      assertEquals(1, attemptCount(fixture));
      assertThrows(
          BusinessException.class,
          () ->
              transactions.run(
                  fixture.scope(),
                  () ->
                      execution()
                          .cancel(fixture.scope(), fixture.commandId(), "CANCEL_AFTER_TIMEOUT")));
      assertEquals(
          1L,
          transactions.run(
              fixture.scope(),
              () ->
                  jdbc.sql("SELECT count(*) FROM automation_field_claim WHERE command_id=:id")
                      .param("id", fixture.commandId())
                      .query(Long.class)
                      .single()));
    } finally {
      peer.stop(0);
    }
  }

  @Test
  void crashAfterAdmissionBeforeTransportDoesNotResendOnRestart() throws Exception {
    Fixture fixture = fixture();
    assertTrue(admit(execution(), fixture));
    var gateway = mock(CommercialGateway.class);
    when(gateway.reconcile(any(), any(), any())).thenReturn(unknown(fixture.offerId()));
    assertEquals(
        "WAITING", recoveryHandler(execution(), gateway).execute(context(fixture)).state());
    verify(gateway, never()).send(any(), any(), any());
    assertEquals(1, attemptCount(fixture));
    assertEquals(
        State.UNKNOWN,
        transactions.run(
            fixture.scope(), () -> execution().get(fixture.scope(), fixture.commandId()).state()));
  }

  @Test
  void finalAuthorizationAndStockGuardBlockAdmissionWithoutAnAttempt() {
    Fixture fixture = fixture();
    access.grantAccountAccess(
        fixture.scope(), viewer, AccessService.Role.VIEWER, Set.of(), null, UUID.randomUUID());
    Scope restricted = new Scope(organization, fixture.scope().accountId(), viewer);
    BusinessException denied =
        assertThrows(
            BusinessException.class,
            () ->
                transactions.run(
                    restricted,
                    () -> {
                      authorization.requireLocked(
                          restricted, Set.of("decision.approve", "finance.read"));
                      return execution()
                          .admit(
                              restricted,
                              fixture.commandId(),
                              1,
                              1,
                              true,
                              true,
                              CLOCK.instant().plusSeconds(60),
                              60);
                    }));
    assertEquals(403, denied.status());
    var runtime = new RuntimeService(jdbc, authorization, new AuditService(jdbc), outbox, CLOCK);
    transactions.run(
        fixture.scope(),
        () ->
            runtime.observeStock(
                fixture.scope(),
                fixture.offerId(),
                fixture.offerId(),
                BigDecimal.ZERO,
                true,
                new BigDecimal("100"),
                settings()));
    BusinessException stock =
        assertThrows(
            BusinessException.class,
            () ->
                transactions.run(
                    fixture.scope(),
                    () -> {
                      authorization.requireLocked(
                          fixture.scope(), Set.of("decision.approve", "finance.read"));
                      runtime.admit(
                          fixture.scope(),
                          fixture.commandId(),
                          fixture.offerId(),
                          fixture.offerId(),
                          new BigDecimal("100"),
                          new BigDecimal("90"),
                          settings());
                      return execution()
                          .admit(
                              fixture.scope(),
                              fixture.commandId(),
                              1,
                              1,
                              true,
                              true,
                              CLOCK.instant().plusSeconds(60),
                              60);
                    }));
    assertEquals("RUNTIME_GUARD", stock.code());
    assertEquals(0, attemptCount(fixture));
    assertFalse(
        transactions.run(
            fixture.scope(),
            () ->
                execution()
                    .admit(
                        fixture.scope(),
                        fixture.commandId(),
                        1,
                        1,
                        true,
                        false,
                        CLOCK.instant().plusSeconds(60),
                        60)
                    .allowed()));
    assertEquals(0, attemptCount(fixture));
  }

  @Test
  void competingRiskAndStockReservationsDoNotOverspendAndUnknownDoesNotRelease() throws Exception {
    Fixture fixture = fixture();
    var risk =
        new RiskLimitService(jdbc, authorization, CLOCK, JSON, new AuditService(jdbc), outbox);
    var permission =
        new RiskLimitService.Permission(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            CLOCK.instant().minusSeconds(10),
            CLOCK.instant().plusSeconds(300),
            new BigDecimal("-10"),
            new BigDecimal("100"),
            BigDecimal.TEN,
            "confirmed-test-external-bound",
            "a".repeat(64),
            "ACTIVE",
            Set.of(Operation.SET_PRICE));
    transactions.run(fixture.scope(), () -> risk.grant(fixture.scope(), permission));
    List<Boolean> reserved =
        race(
            () -> reserveRisk(risk, fixture.scope(), permission),
            () -> reserveRisk(risk, fixture.scope(), permission));
    assertEquals(1, reserved.stream().filter(Boolean::booleanValue).count());
    transactions.run(fixture.scope(), () -> risk.revoke(fixture.scope(), permission.id(), 1));
    assertEquals(
        0,
        new BigDecimal("60")
            .compareTo(
                transactions.run(
                    fixture.scope(),
                    () -> risk.getUsage(fixture.scope(), permission.id()).occupied())));
    assertThrows(
        BusinessException.class,
        () ->
            transactions.run(
                fixture.scope(),
                () ->
                    risk.requireCurrent(
                        fixture.scope(),
                        permission.id(),
                        permission.runId(),
                        permission.scopeDigest(),
                        Operation.SET_PRICE)));

    UUID pool = stockPool(fixture);
    var resource =
        new ResourceService(
            jdbc, new MarketplaceReadService(jdbc, authorization, JSON, CLOCK), CLOCK, outbox);
    UUID firstCommand = UUID.randomUUID();
    UUID secondCommand = UUID.randomUUID();
    List<Boolean> stock =
        race(
            () -> reserveStock(resource, fixture.scope(), firstCommand, pool),
            () -> reserveStock(resource, fixture.scope(), secondCommand, pool));
    assertEquals(1, stock.stream().filter(Boolean::booleanValue).count());
    var inventory =
        transactions
            .run(
                fixture.scope(),
                () -> resource.inventory(fixture.scope(), List.of(fixture.offerId())))
            .get(fixture.offerId());
    assertEquals(1, inventory.poolCount());
    assertEquals(pool, inventory.pools().getFirst().poolId());
    assertEquals(
        0, new BigDecimal("3").compareTo(inventory.pools().getFirst().availableQuantity()));
    UUID winner = stock.getFirst() ? firstCommand : secondCommand;
    assertThrows(
        BusinessException.class,
        () ->
            transactions.run(
                fixture.scope(),
                () -> resource.releaseUnsent(fixture.scope(), winner, pool, false)));
    assertEquals(
        0,
        new BigDecimal("7")
            .compareTo(
                transactions.run(
                    fixture.scope(),
                    () ->
                        jdbc.sql(
                                "SELECT sum(quantity) FROM economics_resource_hold WHERE"
                                    + " pool_id=:pool AND state='HELD'")
                            .param("pool", pool)
                            .query(BigDecimal.class)
                            .single())));
  }

  @Test
  void provedNonSendReleasesOnlyWhenNoOtherCommandMayUseTheSameLiabilityPortion() {
    Fixture first = fixture();
    Fixture second = fixture(first.scope());
    var risk =
        new RiskLimitService(jdbc, authorization, CLOCK, JSON, new AuditService(jdbc), outbox);
    var permission =
        new RiskLimitService.Permission(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            CLOCK.instant().minusSeconds(10),
            CLOCK.instant().plusSeconds(300),
            new BigDecimal("-60"),
            new BigDecimal("6000"),
            new BigDecimal("100"),
            "confirmed-test-external-bound",
            "a".repeat(64),
            "ACTIVE",
            Set.of(Operation.SET_PRICE));
    UUID portion = UUID.randomUUID();
    transactions.run(
        first.scope(),
        () -> {
          risk.grant(first.scope(), permission);
          risk.reserveForCommand(
              first.scope(),
              permission.id(),
              portion,
              first.commandId(),
              new BigDecimal("6000"),
              permission.scopeDigest(),
              permission.runId(),
              Operation.SET_PRICE);
          risk.reserveForCommand(
              first.scope(),
              permission.id(),
              portion,
              second.commandId(),
              new BigDecimal("6000"),
              permission.scopeDigest(),
              permission.runId(),
              Operation.SET_PRICE);
          risk.releaseAbsentEffect(first.scope(), first.commandId());
          risk.releaseAbsentEffect(first.scope(), first.commandId());
        });
    assertEquals(
        0,
        new BigDecimal("6000")
            .compareTo(
                transactions.run(
                    first.scope(),
                    () -> risk.getUsage(first.scope(), permission.id()).occupied())));
    transactions.run(
        first.scope(), () -> risk.releaseAbsentEffect(first.scope(), second.commandId()));
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(
            transactions.run(
                first.scope(), () -> risk.getUsage(first.scope(), permission.id()).occupied())));
  }

  @Test
  void recognizingLossDoesNotChargeCoveredImmutableStepAgainOrClampActualLoss() {
    Fixture fixture = fixture();
    var risk =
        new RiskLimitService(jdbc, authorization, CLOCK, JSON, new AuditService(jdbc), outbox);
    var permission =
        new RiskLimitService.Permission(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            CLOCK.instant().minusSeconds(10),
            CLOCK.instant().plusSeconds(300),
            new BigDecimal("-60"),
            new BigDecimal("6000"),
            new BigDecimal("100"),
            "confirmed-test-external-bound",
            "a".repeat(64),
            "ACTIVE",
            Set.of(Operation.SET_PRICE));
    UUID obligation = UUID.randomUUID();
    transactions.run(
        fixture.scope(),
        () -> {
          risk.grant(fixture.scope(), permission);
          risk.reserve(
              fixture.scope(),
              permission.id(),
              obligation,
              new BigDecimal("6000"),
              permission.scopeDigest(),
              true,
              permission.runId(),
              Operation.SET_PRICE);
          risk.reconcile(
              fixture.scope(),
              permission.id(),
              obligation,
              new BigDecimal("2000"),
              new BigDecimal("3600"),
              1);
          risk.reserve(
              fixture.scope(),
              permission.id(),
              obligation,
              new BigDecimal("6000"),
              permission.scopeDigest(),
              true,
              permission.runId(),
              Operation.SET_PRICE);
        });
    assertEquals(
        0,
        new BigDecimal("5600")
            .compareTo(
                transactions.run(
                    fixture.scope(),
                    () -> risk.getUsage(fixture.scope(), permission.id()).occupied())));
    transactions.run(
        fixture.scope(),
        () ->
            risk.reconcile(
                fixture.scope(),
                permission.id(),
                obligation,
                new BigDecimal("6600"),
                BigDecimal.ZERO,
                2));
    assertEquals(
        0,
        new BigDecimal("6600")
            .compareTo(
                transactions.run(
                    fixture.scope(),
                    () -> risk.getUsage(fixture.scope(), permission.id()).occupied())));
    assertThrows(
        BusinessException.class,
        () ->
            transactions.run(
                fixture.scope(),
                () ->
                    risk.reserve(
                        fixture.scope(),
                        permission.id(),
                        obligation,
                        new BigDecimal("6100"),
                        permission.scopeDigest(),
                        true,
                        permission.runId(),
                        Operation.SET_PRICE)));
  }

  @Test
  void quantityReadViewsRetainUnknownHoldsAndRespectOfferPoolAndCurrentAccess() {
    Fixture first = fixture();
    Fixture second = fixture(first.scope());
    Fixture foreign = fixture();
    UUID firstPool = stockPool(first);
    UUID secondPool = stockPool(second);
    var resources =
        new ResourceService(
            jdbc, new MarketplaceReadService(jdbc, authorization, JSON, CLOCK), CLOCK, outbox);
    var grant =
        access.grantAccountAccess(
            first.scope(), viewer, AccessService.Role.VIEWER, Set.of(), null, UUID.randomUUID());
    Scope reader = new Scope(organization, first.scope().accountId(), viewer);
    transactions.run(
        first.scope(),
        () -> {
          resources.reserve(first.scope(), first.commandId(), firstPool, new BigDecimal("3"), 1);
          resources.reserve(first.scope(), second.commandId(), secondPool, new BigDecimal("2"), 1);
          return null;
        });
    assertThrows(
        BusinessException.class,
        () ->
            transactions.run(
                first.scope(),
                () -> {
                  resources.releaseUnsent(first.scope(), first.commandId(), firstPool, false);
                  return null;
                }));
    var holds =
        transactions.run(
            reader, () -> resources.reservations(reader, first.offerId(), firstPool, 0, 1));
    assertEquals(1, holds.total());
    assertEquals(first.commandId(), holds.items().getFirst().commandId());
    assertEquals("HELD", holds.items().getFirst().state());
    assertEquals(0, new BigDecimal("3").compareTo(holds.items().getFirst().quantity()));
    assertEquals(
        2,
        transactions.run(reader, () -> resources.reservations(reader, null, null, 0, 1)).total());
    assertEquals(
        1,
        transactions
            .run(reader, () -> resources.reservations(reader, null, null, 1, 1))
            .items()
            .size());
    assertEquals(
        0,
        transactions
            .run(reader, () -> resources.reservations(reader, first.offerId(), secondPool, 0, 50))
            .total());
    assertFalse(
        transactions.run(reader, () -> authorization.permissions(reader)).contains("finance.read"));

    UUID raw =
        transactions.run(
            first.scope(), () -> execution().get(first.scope(), first.commandId()).journalFileId());
    Instant observed = CLOCK.instant().minusSeconds(1);
    var commitment =
        new CommercialGateway.ResourceCommitment(
            firstPool, new BigDecimal("3"), 1, "allocation:read-view");
    var proof =
        new CommercialGateway.ObligationEvidence(
            first.commandId(),
            firstPool,
            "external-allocation",
            new BigDecimal("3"),
            new BigDecimal("2"),
            false,
            true,
            true,
            observed,
            1,
            raw);
    assertTrue(
        transactions.run(
            first.scope(),
            () ->
                resources.observe(
                    first.scope(),
                    first.commandId(),
                    commitment,
                    observed.minusSeconds(1),
                    proof)));
    var obligations =
        transactions.run(
            reader, () -> resources.obligations(reader, first.offerId(), firstPool, 0, 50));
    assertEquals(1, obligations.total());
    var obligation = obligations.items().getFirst();
    assertEquals("CONFIRMED", obligation.state());
    assertEquals(0, new BigDecimal("2").compareTo(obligation.remainingQuantity()));
    assertEquals(Long.valueOf(1), obligation.sourceRevision());
    assertEquals(Boolean.TRUE, obligation.admissionClosed());
    assertEquals(
        "CONVERTED",
        transactions
            .run(reader, () -> resources.reservations(reader, first.offerId(), firstPool, 0, 50))
            .items()
            .getFirst()
            .state());
    assertEquals(
        0,
        transactions
            .run(
                foreign.scope(),
                () -> resources.obligations(foreign.scope(), first.offerId(), firstPool, 0, 50))
            .total());
    assertEquals(
        0L,
        transactions.run(
            foreign.scope(),
            () ->
                jdbc.sql("SELECT count(*) FROM economics_resource_hold WHERE command_id=:command")
                    .param("command", first.commandId())
                    .query(Long.class)
                    .single()));
    assertThrows(
        BusinessException.class,
        () ->
            transactions.run(
                reader, () -> resources.reservations(reader, first.offerId(), firstPool, 0, 201)));
    access.revokeAccountAccess(first.scope(), viewer, grant.revision(), UUID.randomUUID());
    assertThrows(
        BusinessException.class,
        () ->
            transactions.run(
                reader, () -> resources.obligations(reader, first.offerId(), firstPool, 0, 50)));
  }

  @Test
  void exactExternalAllocationClosesOnlyWithFullProofAndReplaysWithoutFreeingUnknown() {
    Fixture fixture = fixture();
    UUID pool = stockPool(fixture);
    ResourceService resource =
        new ResourceService(
            jdbc, new MarketplaceReadService(jdbc, authorization, JSON, CLOCK), CLOCK, outbox);
    var commitment =
        new CommercialGateway.ResourceCommitment(pool, new BigDecimal("7"), 1, "campaign:exact");
    Instant observed =
        transactions.run(
            fixture.scope(),
            () ->
                jdbc.sql("SELECT observed_at FROM marketplace_stock_pool WHERE id=:id")
                    .param("id", pool)
                    .query((rs, row) -> rs.getTimestamp(1).toInstant())
                    .single());
    UUID raw =
        transactions.run(
            fixture.scope(),
            () -> execution().get(fixture.scope(), fixture.commandId()).journalFileId());
    Instant admitted = observed.minusSeconds(1);
    transactions.run(
        fixture.scope(),
        () ->
            resource.reserve(fixture.scope(), fixture.commandId(), pool, commitment.quantity(), 1));
    assertEquals(
        0,
        new BigDecimal("3")
            .compareTo(
                transactions.run(
                    fixture.scope(),
                    () -> resource.availability(fixture.scope(), pool).quantity())));
    var incomplete =
        new CommercialGateway.ObligationEvidence(
            fixture.commandId(),
            pool,
            "allocation",
            commitment.quantity(),
            BigDecimal.ZERO,
            false,
            false,
            true,
            observed,
            1,
            raw);
    assertFalse(
        transactions.run(
            fixture.scope(),
            () ->
                resource.observe(
                    fixture.scope(), fixture.commandId(), commitment, admitted, incomplete)));
    var open =
        new CommercialGateway.ObligationEvidence(
            fixture.commandId(),
            pool,
            "allocation",
            commitment.quantity(),
            BigDecimal.ZERO,
            false,
            true,
            false,
            observed,
            1,
            raw);
    assertTrue(
        transactions.run(
            fixture.scope(),
            () ->
                resource.observe(
                    fixture.scope(), fixture.commandId(), commitment, admitted, open)));
    assertFalse(
        transactions.run(
            fixture.scope(),
            () ->
                resource.observe(
                    fixture.scope(), fixture.commandId(), commitment, admitted, open)));
    assertEquals(
        0,
        new BigDecimal("3")
            .compareTo(
                transactions.run(
                    fixture.scope(),
                    () -> resource.availability(fixture.scope(), pool).quantity())));
    var partial =
        new CommercialGateway.ObligationEvidence(
            fixture.commandId(),
            pool,
            "allocation",
            commitment.quantity(),
            new BigDecimal("3"),
            false,
            true,
            true,
            observed,
            2,
            raw);
    assertTrue(
        transactions.run(
            fixture.scope(),
            () ->
                resource.observe(
                    fixture.scope(), fixture.commandId(), commitment, admitted, partial)));
    assertEquals(
        0,
        new BigDecimal("7")
            .compareTo(
                transactions.run(
                    fixture.scope(),
                    () -> resource.availability(fixture.scope(), pool).quantity())));
    var conflicting =
        new CommercialGateway.ObligationEvidence(
            fixture.commandId(),
            pool,
            "allocation",
            commitment.quantity(),
            BigDecimal.ZERO,
            false,
            true,
            true,
            observed,
            2,
            raw);
    assertThrows(
        BusinessException.class,
        () ->
            transactions.run(
                fixture.scope(),
                () ->
                    resource.observe(
                        fixture.scope(), fixture.commandId(), commitment, admitted, conflicting)));
    var closed =
        new CommercialGateway.ObligationEvidence(
            fixture.commandId(),
            pool,
            "allocation",
            commitment.quantity(),
            BigDecimal.ZERO,
            false,
            true,
            true,
            observed,
            3,
            raw);
    assertTrue(
        transactions.run(
            fixture.scope(),
            () ->
                resource.observe(
                    fixture.scope(), fixture.commandId(), commitment, admitted, closed)));
    assertFalse(
        transactions.run(
            fixture.scope(), () -> resource.pending(fixture.scope(), fixture.commandId(), pool)));
    assertFalse(
        transactions.run(
            fixture.scope(),
            () ->
                resource.observe(
                    fixture.scope(), fixture.commandId(), commitment, admitted, partial)));
    assertEquals(
        0,
        BigDecimal.TEN.compareTo(
            transactions.run(
                fixture.scope(), () -> resource.availability(fixture.scope(), pool).quantity())));
    assertEquals(
        3,
        transactions.run(
            fixture.scope(),
            () ->
                jdbc.sql(
                        "SELECT count(*) FROM economics_resource_evidence WHERE"
                            + " command_id=:command")
                    .param("command", fixture.commandId())
                    .query(Integer.class)
                    .single()));
  }

  @Test
  void reclaimedLeaseFencesOldWorkerBeforeSendAdmission() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch resumeOld = new CountDownLatch(1);
    CountDownLatch oldFinished = new CountDownLatch(1);
    CountDownLatch replacementFinished = new CountDownLatch(1);
    AtomicReference<JobContext> original = new AtomicReference<>();
    AtomicReference<JobContext> replacement = new AtomicReference<>();
    AtomicReference<String> rejected = new AtomicReference<>();
    AtomicReference<Throwable> unexpected = new AtomicReference<>();
    AtomicReference<JobRuntime> oldRuntime = new AtomicReference<>();
    AtomicReference<JobRuntime> newRuntime = new AtomicReference<>();
    AtomicInteger sends = new AtomicInteger();
    JobHandler first =
        new JobHandler() {
          public String type() {
            return "FENCED_TEST";
          }

          public String lane() {
            return "execution";
          }

          public JobOutcome execute(JobContext context) throws InterruptedException {
            original.set(context);
            entered.countDown();
            try {
              if (!resumeOld.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Replacement worker did not complete");
              }
              transactions.run(
                  context.scope(),
                  () -> {
                    oldRuntime.get().requireOwnership(context);
                    if (admitInTransaction(execution(), fixture)) {
                      sends.incrementAndGet();
                    }
                  });
            } catch (BusinessException exception) {
              rejected.set(exception.code());
            } catch (RuntimeException exception) {
              unexpected.set(exception);
            } finally {
              oldFinished.countDown();
            }
            return JobOutcome.succeeded("{}");
          }
        };
    JobHandler second =
        new JobHandler() {
          public String type() {
            return "FENCED_TEST";
          }

          public String lane() {
            return "execution";
          }

          public JobOutcome execute(JobContext context) {
            replacement.set(context);
            try {
              boolean admitted =
                  transactions.run(
                      context.scope(),
                      () -> {
                        newRuntime.get().requireOwnership(context);
                        return admitInTransaction(execution(), fixture);
                      });
              if (admitted) {
                sends.incrementAndGet();
              }
            } catch (RuntimeException exception) {
              unexpected.set(exception);
            } finally {
              replacementFinished.countDown();
            }
            return JobOutcome.succeeded("{}");
          }
        };
    oldRuntime.set(worker(first));
    newRuntime.set(worker(second));
    try {
      UUID jobId =
          transactions.run(
              fixture.scope(),
              () ->
                  oldRuntime
                      .get()
                      .submit(fixture.scope(), "FENCED_TEST", UUID.randomUUID().toString(), "{}"));
      oldRuntime.get().claim();
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      // A database-clock expiry models a dead process without waiting for the production lease TTL.
      try (Connection connection =
              DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          var statement =
              connection.prepareStatement(
                  "UPDATE platform_job SET lease_until=clock_timestamp()-interval '1 second' WHERE"
                      + " id=?")) {
        statement.setObject(1, jobId);
        assertEquals(1, statement.executeUpdate());
      }
      newRuntime.get().claim();
      assertTrue(replacementFinished.await(5, TimeUnit.SECONDS));
      resumeOld.countDown();
      assertTrue(oldFinished.await(5, TimeUnit.SECONDS));
      if (unexpected.get() != null) {
        throw new AssertionError("Worker failed unexpectedly", unexpected.get());
      }
      assertEquals("LEASE_EXPIRED", rejected.get());
      assertTrue(replacement.get().fence() > original.get().fence());
      assertEquals(1, sends.get());
      assertEquals(1, attemptCount(fixture));
    } finally {
      resumeOld.countDown();
      oldRuntime.get().destroy();
      newRuntime.get().destroy();
    }
  }

  private static JobRuntime worker(JobHandler handler) {
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("handler", handler);
    beans.registerSingleton("outbox", outbox);
    var control = mock(ru.oritas.repricer.platform.JobControlConnections.class);
    when(control.jdbc()).thenReturn(jdbc);
    beans.registerSingleton("control", control);
    return new JobRuntime(
        jdbc,
        transactions,
        CLOCK,
        beans.getBeanProvider(JobHandler.class),
        beans.getBeanProvider(OutboxService.class),
        beans.getBeanProvider(ru.oritas.repricer.platform.JobControlConnections.class),
        "worker");
  }

  private static boolean reserveRisk(
      RiskLimitService risks, Scope scope, RiskLimitService.Permission permission) {
    try {
      transactions.run(
          scope,
          () ->
              risks.reserve(
                  scope,
                  permission.id(),
                  UUID.randomUUID(),
                  new BigDecimal("60"),
                  permission.scopeDigest(),
                  true,
                  permission.runId(),
                  Operation.SET_PRICE));
      return true;
    } catch (BusinessException failure) {
      assertEquals("LOSS_BUDGET_EXCEEDED", failure.code());
      return false;
    }
  }

  private static boolean reserveStock(
      ResourceService resource, Scope scope, UUID command, UUID pool) {
    try {
      transactions.run(scope, () -> resource.reserve(scope, command, pool, new BigDecimal("7"), 1));
      return true;
    } catch (BusinessException failure) {
      assertEquals("INSUFFICIENT_STOCK", failure.code());
      return false;
    }
  }

  private static boolean dispatch(ExecutionService execution, Fixture fixture, int port) {
    if (!admit(execution, fixture)) {
      return false;
    }
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5000);
      socket
          .getOutputStream()
          .write(
              ("POST /price HTTP/1.1\r\nHost: localhost\r\n"
                      + "Content-Length: 2\r\nConnection: close\r\n\r\n{}")
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      assertEquals(-1, socket.getInputStream().read());
    } catch (IOException failure) {
      throw new AssertionError("Fault injection must close an established HTTP request", failure);
    }
    transactions.run(
        fixture.scope(),
        () ->
            execution.recordSend(
                fixture.scope(),
                fixture.commandId(),
                new SendResult(Outcome.UNKNOWN, null, "RESPONSE_LOST")));
    return true;
  }

  private static boolean admit(ExecutionService execution, Fixture fixture) {
    return transactions.run(fixture.scope(), () -> admitInTransaction(execution, fixture));
  }

  private static boolean admitInTransaction(ExecutionService execution, Fixture fixture) {
    authorization.requireLocked(fixture.scope(), Set.of("decision.approve", "finance.read"));
    return execution
        .admit(
            fixture.scope(),
            fixture.commandId(),
            1,
            1,
            true,
            true,
            CLOCK.instant().plusSeconds(60),
            60)
        .allowed();
  }

  private static ExecutionHandler recoveryHandler(
      ExecutionService execution, CommercialGateway gateway) {
    return new ExecutionHandler(
        execution,
        mock(DecisionService.class),
        gateway,
        transactions,
        mock(JobRuntime.class),
        JSON,
        CLOCK,
        mock(RuntimeService.class),
        mock(MarketplaceReadService.class),
        mock(ScenarioService.class),
        mock(RiskLimitService.class),
        mock(AutoService.class),
        mock(ResourceService.class));
  }

  private static ExecutionService execution() {
    return new ExecutionService(
        jdbc,
        JSON,
        CLOCK,
        authorization,
        new AuditService(jdbc),
        outbox,
        mock(StoredFileService.class),
        mock(JobRuntime.class));
  }

  private static Reconciliation unknown(UUID offer) {
    return new Reconciliation(
        true,
        false,
        List.of(new EffectEvidence(offer, "price", EffectState.UNKNOWN, null, "READ_PENDING")));
  }

  private static JobContext context(Fixture fixture) {
    return new JobContext(
        UUID.randomUUID(),
        fixture.scope(),
        JSON.encode(new DecisionService.ExecutionJob(fixture.commandId())),
        2,
        CLOCK.instant().plusSeconds(30),
        1,
        "execution");
  }

  private static int attemptCount(Fixture fixture) {
    return transactions.run(
        fixture.scope(),
        () ->
            jdbc.sql("SELECT count(*) FROM automation_send_attempt WHERE command_id=:id")
                .param("id", fixture.commandId())
                .query(Integer.class)
                .single());
  }

  private static List<Boolean> race(Supplier<Boolean> first, Supplier<Boolean> second)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var one =
          executor.submit(
              () -> {
                start.await();
                return first.get();
              });
      var two =
          executor.submit(
              () -> {
                start.await();
                return second.get();
              });
      start.countDown();
      return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
    }
  }

  private record Fixture(Scope scope, UUID offerId, UUID commandId) {}

  private static Fixture fixture() {
    UUID account = UUID.randomUUID();
    Scope scope = new Scope(organization, account, admin);
    transactions.run(
        new Scope(organization, null, admin),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'Execution test','Europe/Moscow')
                    """)
                .param("id", account)
                .param("org", organization)
                .param("external", account.toString())
                .update());
    return fixture(scope);
  }

  private static Fixture fixture(Scope scope) {
    UUID account = scope.requireAccount();
    UUID offer = UUID.randomUUID();
    UUID decision = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    return transactions.run(
        scope,
        () -> {
          UUID publication = UUID.randomUUID();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
                  VALUES (:id,:org,:account,'CATALOG','PUBLISHED')
                  """)
              .param("id", publication)
              .param("org", organization)
              .param("account", account)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
                    observed_at,publication_id)
                  VALUES (:id,:org,:account,:external,:external,'Test offer',clock_timestamp(),:publication)
                  """)
              .param("id", offer)
              .param("org", organization)
              .param("account", account)
              .param("external", offer.toString())
              .param("publication", publication)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,
                    media_type,state,byte_count,sha256,eof_confirmed)
                  VALUES (:id,:org,:account,:subject,'SEND_JOURNAL',:key,'application/json','STORED',2,
                    :digest,true)
                  """)
              .param("id", file)
              .param("org", organization)
              .param("account", account)
              .param("subject", admin)
              .param("key", "test/" + file)
              .param("digest", "a".repeat(64))
              .update();
          jdbc.sql(
                  """
                  INSERT INTO automation_decision(organization_id,account_id,id,offer_id,policy_version,
                    assignment_revision,offer_revision,cost_revision,tax_revision,safety_revision,state,
                    reason,target_achieved,requires_confirmation,calculated_at,valid_until,maximum_age_seconds,
                    executable,snapshot_file_id,revision,author_id)
                  VALUES (:org,:account,:id,:offer,0,0,1,0,0,0,'APPROVED','TEST',true,false,
                    clock_timestamp(),clock_timestamp()+interval '5 minutes',300,true,:file,1,:author)
                  """)
              .param("org", organization)
              .param("account", account)
              .param("id", decision)
              .param("offer", offer)
              .param("file", file)
              .param("author", admin)
              .update();
          Instant now = CLOCK.instant();
          var prepared =
              new PreparedCommercialCommand(
                  UUID.randomUUID(),
                  1,
                  now.plusSeconds(300),
                  "SET_PRICE",
                  "http://fault-peer.invalid/price",
                  "{}",
                  "b".repeat(64),
                  1,
                  List.of(new CommercialEffect(offer, "price", new BigDecimal("100"), null)));
          var execution = execution();
          var command = execution.prepare(scope, decision, 0, prepared, now, now.plusSeconds(300));
          execution.attachJournal(scope, command.id(), file);
          return new Fixture(scope, offer, command.id());
        });
  }

  private static UUID stockPool(Fixture fixture) {
    UUID pool = UUID.randomUUID();
    transactions.run(
        fixture.scope(),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_stock_pool(id,organization_id,account_id,external_id,offer_id,
                      name,warehouse_type,free_quantity,obligations_covered,revision,observed_at,valid_until,complete)
                    VALUES (:id,:org,:account,:external,:offer,'Test stock','FBO',10,0,1,
                      clock_timestamp(),:until,true)
                    """)
                .param("id", pool)
                .param("org", organization)
                .param("account", fixture.scope().accountId())
                .param("external", pool.toString())
                .param("offer", fixture.offerId())
                .param("until", Timestamp.from(CLOCK.instant().plusSeconds(300)))
                .update());
    return pool;
  }

  private static PolicySettings settings() {
    var rule =
        new PolicySettings.Rule(
            PolicySettings.Strategy.HOLD_PRICE,
            PolicySettings.PriceKind.BASE_SELLER,
            new BigDecimal("100"),
            new BigDecimal("99"),
            new BigDecimal("101"),
            null,
            PolicySettings.Unreachable.KEEP_SAFE,
            null,
            null,
            PolicySettings.PromoMode.PRESERVE,
            Set.of(),
            Set.of());
    return new PolicySettings(
        rule,
        null,
        Set.of(PolicySettings.Operation.SET_BASE_PRICE),
        new PolicySettings.StockProtection(true, new BigDecimal("5"), BigDecimal.TEN, null),
        new PolicySettings.Stability(
            BigDecimal.ONE,
            new BigDecimal("100"),
            new BigDecimal("1000"),
            3600,
            10,
            0,
            60,
            PolicySettings.WriterMode.DECLARED_EXCLUSIVE_WRITER,
            3),
        List.of(),
        false);
  }
}
