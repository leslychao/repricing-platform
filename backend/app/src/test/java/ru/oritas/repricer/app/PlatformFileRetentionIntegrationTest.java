package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.List;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.ObjectStorage;
import ru.oritas.repricer.platform.PlatformFileRetentionService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

class PlatformFileRetentionIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("retention-test-only");
  private static final Set<String> AUTHORITY = Set.of("platform.file.manage");
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner scopes;

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
    scopes =
        new ScopeTransactionRunner(
            jdbc, new DataSourceTransactionManager(source), new AuthorizationService(jdbc));
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void durableReadPinAndOwnerReferencePreventMarkThenTwentyFourHourDelayProtectsDeletion()
      throws Exception {
    Scope scope = scope(UUID.randomUUID());
    ObjectStorage storage = mock(ObjectStorage.class);
    StoredFileService files = new StoredFileService(jdbc, scopes, storage, Clock.systemUTC());
    PlatformFileRetentionService retention = retention(storage);
    UUID id = file(scope, "STORED", true);
    var file = scopes.runService(scope, AUTHORITY, () -> files.get(id));
    when(storage.read(file.objectKey(), file.objectVersion()))
        .thenReturn(
            new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[0]))));
    UUID owner = UUID.randomUUID();
    try (var input = files.open(file)) {
      assertEquals(-1, input.read());
      assertEquals(
          1,
          scopes.runService(
              scope,
              AUTHORITY,
              () ->
                  jdbc.sql("SELECT count(*) FROM platform_file_read_pin WHERE file_id=:id")
                      .param("id", id)
                      .query(Integer.class)
                      .single()));
      assertEquals(0, scopes.runService(scope, AUTHORITY, () -> retention.markEligible(scope)));
      scopes.runService(
          scope,
          AUTHORITY,
          () -> {
            files.retain(id, "REPORT", owner, scope);
            return true;
          });
    }
    assertEquals(0, scopes.runService(scope, AUTHORITY, () -> retention.markEligible(scope)));
    scopes.runService(
        scope,
        AUTHORITY,
        () -> {
          files.release(scope, id, "REPORT", owner);
          files.release(scope, id, "REPORT", owner);
          return true;
        });
    assertEquals(1, scopes.runService(scope, AUTHORITY, () -> retention.markEligible(scope)));
    assertThrows(BusinessException.class, () -> files.open(file));
    assertThrows(
        BusinessException.class,
        () ->
            scopes.runService(
                scope,
                AUTHORITY,
                () -> {
                  files.retain(id, "REPORT", owner, scope);
                  return true;
                }));
    assertFalse(retention.deleteMarked(scope, id));
    verify(storage, never()).delete(file.objectKey(), file.objectVersion());
    scopes.runService(
        scope,
        AUTHORITY,
        () ->
            jdbc.sql(
                    "UPDATE platform_file SET deletion_after=clock_timestamp()-interval '1 second'"
                        + " WHERE id=:id")
                .param("id", id)
                .update());
    assertTrue(retention.deleteMarked(scope, id));
    assertFalse(retention.deleteMarked(scope, id));
    verify(storage).delete(file.objectKey(), "immutable-version");
    assertEquals("DELETED", scopes.runService(scope, AUTHORITY, () -> files.get(id)).state());
  }

  @Test
  void uncertainCompletedUploadIsNeverTreatedAsAnAbandonedMultipart() {
    Scope scope = scope(UUID.randomUUID());
    UUID id = file(scope, "UPLOADING", true);
    PlatformFileRetentionService retention = retention(mock(ObjectStorage.class));
    assertEquals(0, scopes.runService(scope, AUTHORITY, () -> retention.markEligible(scope)));
    assertEquals(
        "UPLOADING",
        scopes.runService(
            scope,
            AUTHORITY,
            () ->
                jdbc.sql("SELECT state FROM platform_file WHERE id=:id")
                    .param("id", id)
                    .query(String.class)
                    .single()));
  }

  @Test
  void boundedOwnerReleaseDoesNotDropAPartialEvidenceSet() {
    Scope scope = scope(UUID.randomUUID());
    StoredFileService files =
        new StoredFileService(jdbc, scopes, mock(ObjectStorage.class), Clock.systemUTC());
    UUID first = file(scope, "STORED", true);
    UUID second = file(scope, "STORED", true);
    UUID owner = UUID.randomUUID();
    scopes.runService(
        scope,
        AUTHORITY,
        () -> {
          files.retain(first, "DECISION", owner, scope);
          files.retain(second, "DECISION", owner, scope);
          return true;
        });
    assertThrows(
        BusinessException.class,
        () ->
            scopes.runService(
                scope, AUTHORITY, () -> files.releaseOwner(scope, "DECISION", owner, 1)));
    assertEquals(
        2,
        scopes.runService(
            scope,
            AUTHORITY,
            () ->
                jdbc.sql("SELECT count(*) FROM platform_file_reference WHERE owner_id=:owner")
                    .param("owner", owner)
                    .query(Integer.class)
                    .single()));
    assertEquals(
        2,
        scopes.runService(scope, AUTHORITY, () -> files.releaseOwner(scope, "DECISION", owner, 2)));
    assertEquals(
        0,
        scopes.runService(scope, AUTHORITY, () -> files.releaseOwner(scope, "DECISION", owner, 2)));
  }

  @Test
  void nullAccountCannotBypassFileReferenceScopeForeignKey() {
    UUID organization = UUID.randomUUID();
    Scope account = new Scope(organization, UUID.randomUUID(), UUID.randomUUID());
    Scope company = new Scope(organization, null, account.subjectId());
    UUID id = file(account, "STORED", true);
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            scopes.runService(
                company,
                AUTHORITY,
                () ->
                    jdbc.sql(
                            """
                            INSERT INTO platform_file_reference(file_id,organization_id,account_id,owner_type,owner_id)
                            VALUES (:file,:org,NULL,'REPORT',:owner)
                            """)
                        .param("file", id)
                        .param("org", organization)
                        .param("owner", UUID.randomUUID())
                        .update()));
  }

  private static PlatformFileRetentionService retention(ObjectStorage storage) {
    return new PlatformFileRetentionService(
        jdbc,
        scopes,
        storage,
        new DefaultListableBeanFactory().getBeanProvider(JobRuntime.class),
        List.of(),
        Clock.systemUTC(),
        "api");
  }

  private static Scope scope(UUID account) {
    return new Scope(UUID.randomUUID(), account, UUID.randomUUID());
  }

  private static UUID file(Scope scope, String state, boolean eof) {
    UUID id = UUID.randomUUID();
    return scopes.runService(
        scope,
        AUTHORITY,
        () -> {
          jdbc.sql(
                  """
                  INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,
                    object_version,media_type,state,byte_count,sha256,eof_confirmed,created_at,expires_at)
                  VALUES (:id,:org,:account,:subject,'RAW',:key,:version,'application/octet-stream',
                    :state,0,:digest,:eof,clock_timestamp()-interval '100 days',
                    clock_timestamp()-interval '10 days')
                  """)
              .param("id", id)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("subject", scope.subjectId())
              .param("key", id.toString())
              .param("version", state.equals("STORED") ? "immutable-version" : null)
              .param("state", state)
              .param("eof", eof)
              .param("digest", IdempotencyService.sha256(new byte[0]))
              .update();
          return id;
        });
  }
}
