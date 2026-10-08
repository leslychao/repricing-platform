package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.GeneratedFile;
import ru.oritas.repricer.platform.ObjectStorage;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;
import software.amazon.awssdk.core.exception.SdkClientException;

/** The application's file lifecycle against the shipped S3 implementation and PostgreSQL RLS. */
class StoredFileIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("test-container-only");
  private static final GenericContainer<?> S3 =
      new GenericContainer<>(
              "chrislusf/seaweedfs:4.48@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d")
          .withEnv("AWS_ACCESS_KEY_ID", "integration-access")
          .withEnv("AWS_SECRET_ACCESS_KEY", "integration-secret-only")
          .withEnv("S3_BUCKET", "repricer")
          .withCreateContainerCmdModifier(
              command -> {
                command.withEntrypoint("/bin/sh");
                Objects.requireNonNull(command.getHostConfig())
                    .withMemory(1024L * 1024 * 1024)
                    .withMemorySwap(1024L * 1024 * 1024);
              })
          .withCommand(
              "-ec",
              "mkdir -p /data/m9333; chown seaweed:seaweed /data/m9333; exec /entrypoint.sh server"
                  + " -dir=/data -master.dir=/data/m9333 -ip=127.0.0.1 -ip.bind=127.0.0.1 -filer"
                  + " -s3 -s3.ip.bind=0.0.0.0 -s3.port.iceberg=0 -s3.port.lance=0 -s3.iam=false"
                  + " -s3.readerCacheSizeMB=128"
                  + " -master.telemetry=false -master.volumeSizeLimitMB=1024 -volume.max=0"
                  + " -s3.allowDeleteBucketNotEmpty=false")
          .withExposedPorts(8333)
          .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403))
          .withStartupTimeout(Duration.ofMinutes(2));
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner scopes;
  private static ObjectStorage storage;
  private static StoredFileService files;
  private static final Scope SCOPE =
      new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

  @BeforeAll
  static void start() throws Exception {
    POSTGRES.start();
    try (var connection =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "test-container-only");
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
    try (var liquibase =
        new Liquibase("db/changelog/master.xml", new ClassLoaderResourceAccessor(), database)) {
      liquibase.update(new liquibase.Contexts());
    }
    var dataSource =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_api", "api-test-only");
    jdbc = JdbcClient.create(dataSource);
    scopes =
        new ScopeTransactionRunner(
            jdbc, new DataSourceTransactionManager(dataSource), new AuthorizationService(jdbc));
    S3.start();
    storage =
        new ObjectStorage(
            URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(8333)),
            "us-east-1",
            "repricer",
            "integration-access",
            "integration-secret-only");
    storage.verifyVersioning();
    files = new StoredFileService(jdbc, scopes, storage, Clock.systemUTC());
  }

  @AfterAll
  static void stop() {
    if (storage != null) {
      storage.destroy();
    }
    S3.stop();
    POSTGRES.stop();
  }

  @Test
  void unknownLengthMultipartIsReadBackUsingOnlyItsCommittedVersion(
      org.junit.jupiter.api.TestReporter reporter) throws Exception {
    long size = Long.getLong("repricer.test.multipartBytes", 17L * 1024 * 1024 + 13);
    int count = Integer.getInteger("repricer.test.multipartFiles", 1);
    assertTrue(size >= 17L * 1024 * 1024 && size <= 2L * 1024 * 1024 * 1024);
    assertTrue(count >= 1 && count <= 16);
    if (System.getProperty("repricer.test.multipartBytes") != null) {
      assertTrue(
          size * count >= 10 * Runtime.getRuntime().maxMemory(),
          "The volume acceptance input must be at least ten times the actual Java heap");
    }
    for (int index = 0; index < count; index++) {
      var file =
          files.store(
              SCOPE,
              "RAW",
              "application/octet-stream",
              "unknown.bin",
              repeating(size),
              size,
              Instant.now().plusSeconds(600));
      assertEquals("STORED", file.state());
      assertEquals(size, file.byteCount());
      assertTrue(file.eofConfirmed());
      assertNotNull(file.objectVersion());
      try (var input = files.open(file)) {
        assertEquals(size, input.transferTo(java.io.OutputStream.nullOutputStream()));
      }
      // A later version cannot silently change the object referenced by the database.
      String later = storage.putEmpty(file.objectKey(), file.mediaType());
      assertNotEquals(file.objectVersion(), later);
      try (var input = files.open(file)) {
        assertEquals(size, input.transferTo(java.io.OutputStream.nullOutputStream()));
      }
    }
    reporter.publishEntry(
        java.util.Map.of(
            "input_bytes", Long.toString(size * count),
            "maximum_heap_bytes", Long.toString(Runtime.getRuntime().maxMemory()),
            "committed_version_readback", "verified_before_and_after_new_version"));
  }

  @Test
  void lostCompleteResponseReconcilesExactDigestWithoutRepeatingComplete() throws Exception {
    var transport = spy(storage);
    doAnswer(
            call -> {
              call.callRealMethod();
              throw SdkClientException.create("Simulated lost multipart complete reply");
            })
        .when(transport)
        .complete(anyString(), anyString(), anyList());
    var interrupted = new StoredFileService(jdbc, scopes, transport, Clock.systemUTC());
    UUID id = interrupted.prepare(SCOPE, "RAW", "application/octet-stream", "lost.bin");
    long size = 9L * 1024 * 1024;
    assertThrows(
        SdkClientException.class,
        () ->
            interrupted.storePrepared(
                SCOPE, id, repeating(size), size, Instant.now().plusSeconds(60)));
    var pending = read(id);
    assertEquals("UPLOADING", pending.state());
    assertTrue(pending.eofConfirmed());
    var committed = interrupted.reconcile(SCOPE, id);
    assertEquals("STORED", committed.state());
    try (var input = interrupted.open(committed)) {
      assertEquals(size, input.transferTo(java.io.OutputStream.nullOutputStream()));
    }
    verify(transport, times(1)).complete(anyString(), anyString(), anyList());
  }

  @Test
  void lastBlockFailureNeverProducesStoredFileOrBusinessReference() {
    String name = "failed-" + UUID.randomUUID() + ".csv";
    assertThrows(
        IOException.class,
        () ->
            GeneratedFile.store(
                files,
                SCOPE,
                "REPORT",
                "text/csv",
                name,
                16L * 1024 * 1024,
                Instant.now().plusSeconds(60),
                output -> {
                  repeating(9L * 1024 * 1024).transferTo(output);
                  throw new IOException("Simulated final writer failure");
                }));
    var failed =
        scopes.runService(
            SCOPE,
            Set.of("platform.file.manage"),
            () -> {
              UUID id =
                  jdbc.sql("SELECT id FROM platform_file WHERE original_name=:name")
                      .param("name", name)
                      .query(UUID.class)
                      .single();
              assertEquals(
                  0L,
                  jdbc.sql("SELECT count(*) FROM platform_file_reference WHERE file_id=:id")
                      .param("id", id)
                      .query(Long.class)
                      .single());
              return files.get(id);
            });
    assertEquals("FAILED", failed.state());
    assertFalse(failed.eofConfirmed());
    assertThrows(BusinessException.class, () -> files.open(failed));
  }

  @Test
  void sizeBoundaryRejectsUnknownLengthBeforePublication() throws Exception {
    UUID id = files.prepare(SCOPE, "RAW", "application/octet-stream", "too-large.bin");
    var exception =
        assertThrows(
            BusinessException.class,
            () ->
                files.storePrepared(
                    SCOPE, id, repeating(1025), 1024, Instant.now().plusSeconds(60)));
    assertEquals("FILE_TOO_LARGE", exception.code());
    assertEquals("FAILED", read(id).state());
  }

  private static StoredFileService.FileRecord read(UUID id) {
    return scopes.runService(SCOPE, Set.of("platform.file.manage"), () -> files.get(id));
  }

  @Test
  void competingWriterCannotOverwriteOrAbortTheOriginalMultipart() throws Exception {
    UUID id = files.prepare(SCOPE, "RAW", "application/octet-stream", "one-writer.bin");
    var started = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var original =
          executor.submit(
              () -> {
                InputStream input =
                    new java.io.FilterInputStream(repeating(4096)) {
                      private boolean waiting = true;

                      @Override
                      public int read(byte[] buffer, int offset, int length) throws IOException {
                        if (waiting) {
                          waiting = false;
                          started.countDown();
                          try {
                            if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                              throw new IOException("Competing writer test timed out");
                            }
                          } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException(exception);
                          }
                        }
                        return super.read(buffer, offset, length);
                      }
                    };
                return files.storePrepared(SCOPE, id, input, 4096, Instant.now().plusSeconds(30));
              });
      try {
        assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
        var denied =
            assertThrows(
                BusinessException.class,
                () ->
                    files.storePrepared(
                        SCOPE, id, repeating(100), 4096, Instant.now().plusSeconds(30)));
        assertEquals("UPLOAD_STATE_CONFLICT", denied.code());
        assertNotNull(files.recover(SCOPE, id).retryAt());
      } finally {
        release.countDown();
      }
      var committed = original.get(20, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals("STORED", committed.state());
      assertEquals(4096, committed.byteCount());
      try (var input = files.open(committed)) {
        assertEquals(4096, input.transferTo(java.io.OutputStream.nullOutputStream()));
      }
    }
  }

  private static InputStream repeating(long size) {
    return new InputStream() {
      private long remaining = size;

      @Override
      public int read() {
        if (remaining == 0) {
          return -1;
        }
        remaining--;
        return 61;
      }

      @Override
      public int read(byte[] target, int offset, int length) {
        java.util.Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) {
          return 0;
        }
        if (remaining == 0) {
          return -1;
        }
        int count = (int) Math.min(remaining, length);
        Arrays.fill(target, offset, offset + count, (byte) 61);
        remaining -= count;
        return count;
      }
    };
  }
}
