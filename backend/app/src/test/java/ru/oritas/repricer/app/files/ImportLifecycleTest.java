package ru.oritas.repricer.app.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import ru.oritas.repricer.economics.AccountingImportService;
import ru.oritas.repricer.economics.AccountingImportTargets;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.accounting.AccountingBasisService;
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
import ru.oritas.repricer.platform.StoredFileService;
import ru.oritas.repricer.platform.TableExportSource;
import ru.oritas.repricer.platform.TabularImportTarget;

class ImportLifecycleTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("imports-test-only");
  private static final JsonCodec JSON = new JsonCodec();
  private static final Clock CLOCK = Clock.systemUTC();
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner transactions;
  private static AccessService access;
  private static ImportService imports;
  private static ImportValidationHandler validation;
  private static ImportApplyHandler apply;
  private static EconomicsService economics;
  private static StoredFileService files;
  private static AnnotationConfigApplicationContext targets;
  private static UUID company;
  private static UUID admin;
  private static UUID reader;

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
    var jobs = mock(JobRuntime.class);
    when(jobs.submit(any(), anyString(), anyString(), anyString()))
        .thenAnswer(
            call ->
                insertJob(
                    call.getArgument(0),
                    UUID.randomUUID(),
                    call.getArgument(1),
                    call.getArgument(2),
                    call.getArgument(3)));
    targets = new AnnotationConfigApplicationContext();
    var outbox =
        new OutboxService(
            jdbc,
            JSON,
            jobs,
            new DefaultListableBeanFactory().getBeanProvider(OutboxRecipient.class));
    var audit = new AuditService(jdbc);
    var requests = new IdempotencyService(jdbc, JSON);
    access =
        new AccessService(
            jdbc, transactions, authorization, requests, audit, outbox, new IdentityService(jdbc));
    company = access.initManagedUsers("https://imports.invalid/realm", "admin", "test");
    admin = IdentityService.userId("https://imports.invalid/realm", "admin");
    reader = IdentityService.userId("https://imports.invalid/realm", "test");
    var market = new MarketplaceReadService(jdbc, authorization, JSON, CLOCK);
    var accounting =
        new AccountingImportService(
            jdbc,
            new NamedParameterJdbcTemplate(source),
            authorization,
            audit,
            outbox,
            new AccountingBasisService(jdbc, outbox));
    targets.registerBean(AccountingImportService.class, () -> accounting);
    targets.registerBean(MarketplaceReadService.class, () -> market);
    targets.register(AccountingImportTargets.class);
    targets.refresh();
    files = mock(StoredFileService.class);
    imports =
        new ImportService(
            jdbc,
            transactions,
            authorization,
            requests,
            jobs,
            files,
            JSON,
            outbox,
            audit,
            targets.getBeanProvider(TabularImportTarget.class),
            CLOCK,
            market);
    var gate = new FileWorkGate(jdbc, CLOCK);
    validation =
        new ImportValidationHandler(
            imports,
            transactions,
            files,
            gate,
            jobs,
            JSON,
            jdbc,
            new NamedParameterJdbcTemplate(source),
            CLOCK);
    apply = new ImportApplyHandler(imports, transactions, jobs, JSON, audit, gate, CLOCK);
    economics =
        new EconomicsService(
            jdbc, authorization, audit, outbox, new AccountingBasisService(jdbc, outbox));
  }

  @AfterAll
  static void stop() {
    if (targets != null) {
      targets.close();
    }
    POSTGRES.stop();
  }

  @Test
  void staleApplyClosesEditionAndRevalidationUsesSameRowsWithoutInventingFreshness()
      throws Exception {
    Scope scope = account();
    UUID offer = offer(scope);
    publishCost(scope, offer, "100", 0);
    UUID id = parsed(scope, "cost.csv", Instant.parse("2026-02-01T10:00:00Z"));
    var initial = header(scope, id);
    assertEquals(
        "SUCCEEDED", validation.execute(context(scope, id, initial.validationJobId())).state());
    var validated = header(scope, id);
    publishCost(scope, offer, "110", 1);
    var first = imports.apply(scope, id, mutation(validated.revision()));
    assertEquals("BLOCKED", apply.execute(context(scope, id, first.operationId())).state());
    var stale = header(scope, id);
    assertEquals("STALE", stale.status());
    assertEquals("STALE_REVISION", stale.reason());
    assertEquals(2, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
    String originalRows =
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT values::text FROM app_import_row WHERE import_id=:id")
                    .param("id", id)
                    .query(String.class)
                    .single());
    UUID request = UUID.randomUUID();
    var revalidation = new ImportService.Revalidation(request, stale.revision());
    var next = imports.revalidate(scope, id, revalidation);
    assertEquals(next, imports.revalidate(scope, id, revalidation));
    var second = header(scope, id);
    assertNotEquals(initial.validationId(), second.validationId());
    assertEquals(2, second.validationEdition());
    validation.execute(context(scope, id, next.operationId()));
    var ready = header(scope, id);
    var applying = imports.apply(scope, id, mutation(ready.revision()));
    var work = context(scope, id, applying.operationId());
    assertEquals("WAITING", apply.execute(work).state());
    assertEquals("SUCCEEDED", apply.execute(work).state());
    assertEquals("COMMITTED", header(scope, id).status());
    assertEquals(3, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
    assertEquals(
        originalRows,
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT values::text FROM app_import_row WHERE import_id=:id")
                    .param("id", id)
                    .query(String.class)
                    .single()));
    assertEquals(
        "STALE",
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT state FROM app_import_validation WHERE id=:id")
                    .param("id", initial.validationId())
                    .query(String.class)
                    .single()));
    apply.execute(context(scope, id, first.operationId()));
    assertEquals(3, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
    verify(files).retain(initial.fileId(), "IMPORT_APPLIED", id, scope);
    verify(files, never()).open(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void listAndExportShareAccountDateFiltersAndFinancialReadPermissions() {
    Scope scope = account();
    UUID inside = parsed(scope, "cost selected.csv", Instant.parse("2026-01-31T21:30:00Z"));
    parsed(scope, "cost previous.csv", Instant.parse("2026-01-31T20:30:00Z"));
    var day = LocalDate.parse("2026-02-01");
    var query =
        new TableExportSource.Query(
            "cost",
            Map.of(
                "from", day.toString(), "to", day.toString(), "status", "DRAFT", "kind", "COSTS"),
            "date,desc",
            0,
            50);
    transactions.run(
        scope,
        () -> {
          var listed =
              imports.list(scope, 0, 50, "DRAFT", "COSTS", "cost", "date", "desc", day, day);
          var exported = imports.projection(scope, query, List.of());
          assertEquals(
              List.of(inside), listed.items().stream().map(ImportService.Summary::id).toList());
          assertEquals(
              List.of(inside),
              jdbc.sql(exported.sql())
                  .params(exported.parameters())
                  .query((row, index) -> row.getObject("canonical_id", UUID.class))
                  .list());
          assertTrue(exported.includesRowPermissions());
          assertTrue(
              jdbc.sql(exported.sql())
                  .params(exported.parameters())
                  .query((row, index) -> row.getString("permissions"))
                  .single()
                  .contains("finance.read"));
        });
    access.grantAccountAccess(
        scope, reader, AccessService.Role.VIEWER, Set.of(), null, UUID.randomUUID());
    var restricted = new Scope(company, scope.accountId(), reader);
    transactions.run(
        restricted,
        () -> {
          assertEquals(
              0, imports.list(restricted, 0, 50, "", "", "", "id", "asc", null, null).total());
          var projection = imports.projection(restricted, query, List.of(inside));
          assertTrue(
              jdbc.sql(projection.sql())
                  .params(projection.parameters())
                  .query((row, index) -> row.getObject("canonical_id", UUID.class))
                  .list()
                  .isEmpty());
        });
  }

  @Test
  void revokedInitiatorCannotPublishAndTheImportDoesNotRemainPreparing() throws Exception {
    Scope owner = account();
    offer(owner);
    var grant =
        access.grantAccountAccess(
            owner,
            reader,
            AccessService.Role.MANAGER,
            Set.of("file.write", "finance.write", "finance.read"),
            null,
            UUID.randomUUID());
    Scope author = new Scope(company, owner.accountId(), reader);
    UUID id = parsed(author, "revoked.csv", CLOCK.instant());
    validation.execute(context(author, id, header(author, id).validationJobId()));
    var accepted = imports.apply(author, id, mutation(header(author, id).revision()));
    access.revokeAccountAccess(owner, reader, grant.revision(), UUID.randomUUID());
    assertEquals("BLOCKED", apply.execute(context(author, id, accepted.operationId())).state());
    assertEquals("FAILED", header(owner, id).status());
    assertFalse(header(owner, id).reason().isBlank());
  }

  @Test
  void interruptedParsingKeepsInputUnpublishedAndReusesSavedRowsOnRetry() throws Exception {
    Scope scope = account();
    UUID offer = offer(scope);
    publishCost(scope, offer, "100", 0);
    UUID id = streamingDraft(scope);
    var initial = header(scope, id);
    byte[] content = costTable();
    var file = sourceFile(scope, initial.fileId(), content);
    var streamFiles = mock(StoredFileService.class);
    when(streamFiles.get(file.id())).thenReturn(file);
    InputStream lostResponse =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("Lost source stream after committed parsing batches");
          }
        };
    when(streamFiles.open(file))
        .thenReturn(
            new SequenceInputStream(
                new ByteArrayInputStream(content, 0, content.length / 2), lostResponse))
        .thenAnswer(call -> new ByteArrayInputStream(content));
    var parser = streamingValidation(streamFiles);
    var work = context(scope, id, initial.validationJobId());

    assertThrows(IOException.class, () -> parser.execute(work));
    assertEquals("DRAFT", header(scope, id).status());
    assertFalse(header(scope, id).parsed());
    assertTrue(importRows(scope, id) >= 500);
    assertTrue(importRows(scope, id) < 1501);
    assertEquals(1, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
    assertThrows(
        BusinessException.class,
        () -> imports.apply(scope, id, mutation(header(scope, id).revision())));

    assertEquals("SUCCEEDED", parser.execute(work).state());
    var validated = header(scope, id);
    assertEquals("VALIDATED", validated.status());
    assertEquals(1501, validated.totalRows());
    assertEquals(1501, importRows(scope, id));
    assertEquals(initial.fileId(), validated.fileId());
    assertEquals(initial.validationId(), validated.validationId());
    assertEquals(1, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
    parser.execute(work);
    verify(streamFiles, times(2)).open(file);

    var accepted = imports.apply(scope, id, mutation(validated.revision()));
    var applying = context(scope, id, accepted.operationId());
    assertEquals("WAITING", apply.execute(applying).state());
    assertEquals(1, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
    assertEquals("SUCCEEDED", apply.execute(applying).state());
    assertEquals("COMMITTED", header(scope, id).status());
    assertEquals(2, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
    apply.execute(applying);
    assertEquals(2, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
  }

  @Test
  void malformedFinalRecordCannotPublishPreviouslyParsedBatches() throws Exception {
    Scope scope = account();
    UUID offer = offer(scope);
    publishCost(scope, offer, "100", 0);
    UUID id = streamingDraft(scope);
    var initial = header(scope, id);
    byte[] content =
        (new String(costTable(), StandardCharsets.UTF_8) + "\"unfinished")
            .getBytes(StandardCharsets.UTF_8);
    var file = sourceFile(scope, initial.fileId(), content);
    var streamFiles = mock(StoredFileService.class);
    when(streamFiles.get(file.id())).thenReturn(file);
    when(streamFiles.open(file)).thenAnswer(call -> new ByteArrayInputStream(content));
    var outcome =
        streamingValidation(streamFiles).execute(context(scope, id, initial.validationJobId()));

    assertEquals("SUCCEEDED", outcome.state());
    assertEquals("INVALID", header(scope, id).status());
    assertEquals("INVALID_FILE_FORMAT", header(scope, id).reason());
    assertTrue(importRows(scope, id) >= 1000);
    assertFalse(header(scope, id).parsed());
    assertThrows(
        BusinessException.class,
        () -> imports.apply(scope, id, mutation(header(scope, id).revision())));
    assertEquals(1, transactions.run(scope, () -> economics.getCost(scope, offer)).revision());
  }

  private static ImportValidationHandler streamingValidation(StoredFileService streamFiles) {
    var source =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_api", "api-test-only");
    return new ImportValidationHandler(
        imports,
        transactions,
        streamFiles,
        new FileWorkGate(jdbc, CLOCK),
        mock(JobRuntime.class),
        JSON,
        jdbc,
        new NamedParameterJdbcTemplate(source),
        CLOCK);
  }

  private static UUID streamingDraft(Scope scope) {
    UUID id = parsed(scope, "streaming-cost.csv", CLOCK.instant());
    transactions.run(
        scope,
        () -> {
          jdbc.sql("DELETE FROM app_import_row WHERE import_id=:id").param("id", id).update();
          jdbc.sql("UPDATE app_import SET parsed=false,total_rows=0,valid_rows=0 WHERE id=:id")
              .param("id", id)
              .update();
        });
    return id;
  }

  private static StoredFileService.FileRecord sourceFile(Scope scope, UUID id, byte[] content) {
    return new StoredFileService.FileRecord(
        id,
        "test-stream",
        "immutable-version",
        "text/csv",
        "streaming-cost.csv",
        "STORED",
        content.length,
        IdempotencyService.sha256(content),
        true,
        scope);
  }

  private static byte[] costTable() {
    var content = new StringBuilder("sku;amount;extraExpense;validFrom;validUntil\n");
    LocalDate start = LocalDate.of(2026, 1, 1);
    for (int row = 0; row < 1501; row++) {
      content
          .append("001;120;;")
          .append(start.plusDays(row))
          .append(';')
          .append(start.plusDays(row + 1))
          .append('\n');
    }
    byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
    assertTrue(bytes.length < 64 * 1024);
    return bytes;
  }

  @Test
  void temporaryRowsWaitForTerminalWorkAndTwentyFourHoursThenExpireInBoundedPortions() {
    Scope scope = account();
    UUID id = parsed(scope, "expired.csv", Instant.now().minusSeconds(9 * 86400L));
    transactions.run(
        scope,
        () ->
            jdbc.sql("UPDATE app_import SET state='INVALID' WHERE id=:id")
                .param("id", id)
                .update());
    Set<String> retention = Set.of("app.files.retention");
    assertEquals(
        0, transactions.runService(scope, retention, () -> imports.cleanupTemporary(scope)));
    transactions.run(
        scope,
        () -> {
          jdbc.sql(
                  "UPDATE platform_job SET state='SUCCEEDED' WHERE id=(SELECT validation_job_id"
                      + " FROM app_import WHERE id=:id)")
              .param("id", id)
              .update();
          jdbc.sql(
                  """
                  INSERT INTO app_import_row(organization_id,account_id,import_id,row_number,values,errors,valid)
                  SELECT :org,:account,:id,n,'{}','[]',false FROM generate_series(2,600) n
                  """)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("id", id)
              .update();
          return true;
        });
    assertEquals(
        1, transactions.runService(scope, retention, () -> imports.cleanupTemporary(scope)));
    assertTrue(header(scope, id).temporaryExpired());
    assertEquals(600, importRows(scope, id));
    assertEquals(
        0, transactions.runService(scope, retention, () -> imports.cleanupTemporary(scope)));
    assertEquals(
        410,
        assertThrows(
                BusinessException.class,
                () -> transactions.run(scope, () -> imports.errors(scope, id, 0)))
            .status());
    assertEquals(
        410,
        assertThrows(
                BusinessException.class,
                () ->
                    imports.revalidate(
                        scope,
                        id,
                        new ImportService.Revalidation(
                            UUID.randomUUID(), header(scope, id).revision())))
            .status());
    assertEquals(
        410,
        assertThrows(
                BusinessException.class,
                () -> imports.apply(scope, id, mutation(header(scope, id).revision())))
            .status());
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    "UPDATE app_import SET temporary_expired_at=clock_timestamp()-interval '25"
                        + " hours' WHERE id=:id")
                .param("id", id)
                .update());
    assertEquals(
        1, transactions.runService(scope, retention, () -> imports.cleanupTemporary(scope)));
    assertEquals(100, importRows(scope, id));
    assertEquals(
        1, transactions.runService(scope, retention, () -> imports.cleanupTemporary(scope)));
    assertEquals(0, importRows(scope, id));
    assertEquals("INVALID", header(scope, id).status());
    assertEquals(
        0, transactions.runService(scope, retention, () -> imports.cleanupTemporary(scope)));
  }

  private static int importRows(Scope scope, UUID id) {
    return transactions.run(
        scope,
        () ->
            jdbc.sql("SELECT count(*) FROM app_import_row WHERE import_id=:id")
                .param("id", id)
                .query(Integer.class)
                .single());
  }

  private static Scope account() {
    UUID id = UUID.randomUUID();
    transactions.run(
        new Scope(company, null, admin),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'OZON',:external,'Import account','Europe/Moscow')
                    """)
                .param("id", id)
                .param("org", company)
                .param("external", id.toString())
                .update());
    return new Scope(company, id, admin);
  }

  private static UUID offer(Scope scope) {
    UUID id = UUID.randomUUID();
    UUID publication = UUID.randomUUID();
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state,completed_at)
                    VALUES (:id,:org,:account,'CATALOG','PUBLISHED',clock_timestamp())
                    """)
                .param("id", publication)
                .param("org", company)
                .param("account", scope.accountId())
                .update());
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,observed_at,publication_id)
                    VALUES (:id,:org,:account,'001','001','Cost source',clock_timestamp(),:publication)
                    """)
                .param("id", id)
                .param("publication", publication)
                .param("org", company)
                .param("account", scope.accountId())
                .update());
    return id;
  }

  private static UUID parsed(Scope scope, String name, Instant created) {
    UUID id = UUID.randomUUID();
    UUID file = UUID.randomUUID();
    UUID job = UUID.randomUUID();
    transactions.run(
        scope,
        () -> {
          jdbc.sql(
                  """
                  INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,media_type,state)
                  VALUES (:id,:org,:account,:subject,'IMPORT',:key,'text/csv','READY')
                  """)
              .param("id", file)
              .param("org", company)
              .param("account", scope.accountId())
              .param("subject", scope.subjectId())
              .param("key", file.toString())
              .update();
          insertJob(scope, job, "IMPORT_VALIDATE", job.toString(), "{}");
          jdbc.sql(
                  """
                  INSERT INTO app_import(id,organization_id,account_id,subject_id,kind,name,file_id,content_hash,
                    options,options_hash,state,parsed,total_rows,valid_rows,validation_id,validation_job_id,created_at)
                  VALUES (:id,:org,:account,:subject,'COSTS',:name,:file,:hash,CAST(:options AS jsonb),:hash,
                    'DRAFT',true,1,1,:id,:job,:created)
                  """)
              .param("id", id)
              .param("org", company)
              .param("account", scope.accountId())
              .param("subject", scope.subjectId())
              .param("name", name)
              .param("file", file)
              .param(
                  "hash",
                  IdempotencyService.sha256(
                      id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
              .param(
                  "options",
                  JSON.encode(new ImportService.Options("CSV", "SEMICOLON", "DOT", null)))
              .param("job", job)
              .param("created", Timestamp.from(created))
              .update();
          jdbc.sql(
                  """
                  INSERT INTO app_import_validation(id,organization_id,account_id,import_id,edition,state)
                  VALUES (:id,:org,:account,:id,1,'DRAFT')
                  """)
              .param("id", id)
              .param("org", company)
              .param("account", scope.accountId())
              .update();
          jdbc.sql(
                  """
                  INSERT INTO app_import_row(organization_id,account_id,import_id,row_number,values,errors,valid)
                  VALUES (:org,:account,:id,1,CAST(:row AS jsonb),'[]',true)
                  """)
              .param("org", company)
              .param("account", scope.accountId())
              .param("id", id)
              .param(
                  "row",
                  JSON.encode(
                      new TabularImportTarget.InputRow(
                          1, Map.of("sku", "001", "amount", "120", "validFrom", "2026-01-01"))))
              .update();
        });
    return id;
  }

  private static UUID insertJob(Scope scope, UUID id, String type, String key, String payload) {
    jdbc.sql(
            """
            INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,business_key,
              payload,state,due_at)
            VALUES (:id,:org,:account,:subject,:type,'canonicalization',:key,CAST(:payload AS jsonb),
              'READY',clock_timestamp())
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("type", type)
        .param("key", key)
        .param("payload", payload)
        .update();
    return id;
  }

  private static void publishCost(Scope scope, UUID offer, String amount, long revision) {
    transactions.run(
        scope,
        () ->
            economics.publishCost(
                scope,
                new EconomicsService.CostInput(
                    offer,
                    new BigDecimal(amount),
                    BigDecimal.ZERO,
                    LocalDate.parse("2026-01-01"),
                    null,
                    revision)));
  }

  private static ImportService.Header header(Scope scope, UUID id) {
    return transactions.run(scope, () -> imports.load(id, false));
  }

  private static ImportService.Mutation mutation(long revision) {
    return new ImportService.Mutation(UUID.randomUUID(), revision, revision);
  }

  private static JobContext context(Scope scope, UUID id, UUID job) {
    return new JobContext(
        job,
        scope,
        JSON.encode(new ImportService.Work(id)),
        1,
        CLOCK.instant().plusSeconds(120),
        0,
        "canonicalization");
  }
}
