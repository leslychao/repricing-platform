package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.FileWorkGate;
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
import ru.oritas.repricer.platform.VaultSecretStore;

/** Local official-format HTTP fixtures; these tests do not certify a real Yandex account. */
class YandexGatewayContractTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("gateway-test-only");
  private static final GenericContainer<?> S3 =
      new GenericContainer<>(
              "chrislusf/seaweedfs:4.48@sha256:4e61d15fd35994cb1e43e1e553dff106794841fd9a99ade2fc8c8bfce4d7872d")
          .withEnv("AWS_ACCESS_KEY_ID", "gateway-test-access")
          .withEnv("AWS_SECRET_ACCESS_KEY", "gateway-test-secret")
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
                  + " -s3.readerCacheSizeMB=128 -master.telemetry=false"
                  + " -master.volumeSizeLimitMB=1024 -volume.max=0"
                  + " -s3.allowDeleteBucketNotEmpty=false")
          .withExposedPorts(8333)
          .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCode(403))
          .withStartupTimeout(Duration.ofMinutes(2));
  private static final JsonCodec JSON = new JsonCodec();
  private static final Clock CLOCK = Clock.systemUTC();
  private static JdbcClient owner;
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner scopes;
  private static AuthorizationService authorization;
  private static DataSourceTransactionManager manager;
  private static ObjectStorage storage;
  private static StoredFileService files;
  private Scope scope;
  private String externalId;
  private String apiKey;
  private HttpServer peer;
  private OutboundGateway gateway;
  private CapabilityService capabilities;
  private ConnectionService connections;
  private JobRuntime jobs;
  private OutboxService outbox;
  private UUID catalogPublication;
  private final AtomicInteger requests = new AtomicInteger();
  private final AtomicReference<Throwable> peerFailure = new AtomicReference<>();

  @BeforeAll
  static void start() throws Exception {
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
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_api", "api-test-only");
    jdbc = JdbcClient.create(source);
    manager = new DataSourceTransactionManager(source);
    authorization = new AuthorizationService(jdbc);
    scopes = new ScopeTransactionRunner(jdbc, manager, authorization);
    S3.start();
    storage =
        new ObjectStorage(
            URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(8333)),
            "us-east-1",
            "repricer",
            "gateway-test-access",
            "gateway-test-secret");
    storage.verifyVersioning();
    files = new StoredFileService(jdbc, scopes, storage, CLOCK);
  }

  @AfterAll
  static void stop() {
    if (storage != null) {
      storage.destroy();
    }
    S3.stop();
    POSTGRES.stop();
  }

  @BeforeEach
  void fixture() throws IOException {
    scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    externalId = Long.toString((scope.accountId().getMostSignificantBits() & Long.MAX_VALUE) | 1);
    apiKey = "local-simulation-only-" + UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO access_user(id,issuer,subject,display_name,email,email_verified)
            VALUES(:id,'simulation',:subject,'Simulation','simulation@example.invalid',true)
            """)
        .param("id", scope.subjectId())
        .param("subject", scope.subjectId().toString())
        .update();
    owner
        .sql("INSERT INTO access_organization(id,name) VALUES(:id,'HTTP simulation')")
        .param("id", scope.organizationId())
        .update();
    owner
        .sql(
            """
            INSERT INTO access_membership(id,organization_id,subject_id,role)
            VALUES(gen_random_uuid(),:org,:subject,'OWNER')
            """)
        .param("org", scope.organizationId())
        .param("subject", scope.subjectId())
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
            VALUES(:id,:org,'YANDEX',:external,'Local HTTP fixture','Europe/Moscow')
            """)
        .param("id", scope.accountId())
        .param("org", scope.organizationId())
        .param("external", externalId)
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_connection(id,organization_id,account_id,secret_path,secret_version,
              quota_credential_hash,state,read_only) VALUES(gen_random_uuid(),:org,:account,
              'repricer/http-simulation',1,:hash,'ACTIVE',true)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("hash", QuotaManager.credentialSubject("YANDEX", apiKey))
        .update();
    jobs = mock(JobRuntime.class);
    outbox =
        new OutboxService(
            jdbc,
            JSON,
            jobs,
            new DefaultListableBeanFactory().getBeanProvider(OutboxRecipient.class));
    capabilities = new CapabilityService(jdbc, CLOCK);
    var vault = mock(VaultSecretStore.class);
    when(vault.read(anyString(), anyLong())).thenReturn(Map.of("apiKey", apiKey));
    connections =
        new ConnectionService(
            jdbc,
            scopes,
            authorization,
            new IdempotencyService(jdbc, JSON),
            new AuditService(jdbc),
            outbox,
            jobs,
            JSON,
            vault,
            CLOCK,
            capabilities);
    peer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    peer.start();
    gateway =
        new OutboundGateway(
            jdbc,
            scopes,
            authorization,
            connections,
            vault,
            files,
            new QuotaManager(jdbc, manager, CLOCK, "api"),
            new PageAdmissionService(jdbc, scopes, CLOCK, outbox),
            CLOCK,
            original -> {
              assertTrue(
                  Set.of("api.partner.market.yandex.ru", "storage.yandexcloud.net")
                      .contains(original.getHost()));
              return URI.create(
                  "http://127.0.0.1:"
                      + peer.getAddress().getPort()
                      + original.getRawPath()
                      + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery()));
            });
  }

  @AfterEach
  void closePeer() {
    gateway.destroy();
    peer.stop(0);
    if (peerFailure.get() != null) {
      throw new AssertionError("HTTP fixture rejected a transport request", peerFailure.get());
    }
  }

  @Test
  void authorizationScopesArePersistedWithRawEvidenceAndNeverUnlockWrites() throws Exception {
    fixtureEndpoint(
        "/v2/auth/token",
        exchange -> {
          request(exchange, "POST");
          assertEquals("{}", body(exchange));
          respond(
              exchange,
              200,
              """
              {"status":"OK","result":{"apiKey":{"name":"Simulation",
              "authScopes":["PRICING_READ_ONLY","FINANCE_AND_ACCOUNTING"]}}}
              """);
        });
    var response = read(VendorMethod.YANDEX_AUTH_TOKEN, "{}", null, null);
    assertStored(response);
    assertEquals(
        IdempotencyService.sha256(
            ("YANDEX_AUTH_TOKEN\nhttps://api.partner.market.yandex.ru/v2/auth/token\n{}")
                .getBytes(StandardCharsets.UTF_8)),
        owner
            .sql("SELECT request_digest FROM marketplace_external_call WHERE id=:id")
            .param("id", response.callId())
            .query(String.class)
            .single());
    Set<String> observed;
    try (InputStream input = gateway.open(response)) {
      observed = CapabilityService.yandexScopes(input);
    }
    scopes.run(
        scope,
        () -> {
          capabilities.initializeUnconfirmed(scope, "YANDEX");
          capabilities.confirmYandexScopes(scope, response.raw().id(), observed);
          assertTrue(capabilities.equivalentYandexScopes(scope, observed, true));
        });
    assertEquals(
        0,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_profile WHERE account_id=:account AND method='YANDEX_SET_PRICE'
                  AND status='CONFIRMED'
                """)
            .param("account", scope.accountId())
            .query(Integer.class)
            .single());
    assertEquals(
        "CAPABILITY_UNAVAILABLE",
        assertThrows(
                BusinessException.class,
                () ->
                    gateway.execute(
                        scope,
                        VendorMethod.YANDEX_SET_PRICE,
                        "{}",
                        null,
                        null,
                        1,
                        UUID.randomUUID()))
            .code());
    assertEquals(1, requests.get());
  }

  @Test
  void catalogPagesPublishThroughOwnerAndKeepExactEncodedCursor() throws Exception {
    UUID run = run("CATALOG", "CATALOG");
    List<String> queries = new ArrayList<>();
    fixtureEndpoint(
        "/v2/businesses/" + externalId + "/offer-mappings",
        exchange -> {
          request(exchange, "POST");
          queries.add(exchange.getRequestURI().getRawQuery());
          boolean archived =
              JsonParser.parseString(body(exchange))
                  .getAsJsonObject()
                  .get("archived")
                  .getAsBoolean();
          String response;
          if (archived) {
            response = "{\"status\":\"OK\",\"result\":{\"offerMappings\":[]}}";
          } else if (queries.size() == 1) {
            response =
                """
                {"status":"OK","result":{"paging":{"nextPageToken":"opaque+/= &"},
                  "offerMappings":[{"offer":{"offerId":"A","name":"First"}}]}}
                """;
          } else {
            response =
                """
                {"status":"OK","result":{"offerMappings":[
                  {"offer":{"offerId":"B","name":"Second"}}]}}
                """;
          }
          respond(exchange, 200, response);
        });
    fixtureEndpoint(
        "/v2/businesses/" + externalId + "/offer-prices",
        exchange -> {
          request(exchange, "POST");
          boolean archived =
              JsonParser.parseString(body(exchange))
                  .getAsJsonObject()
                  .get("archived")
                  .getAsBoolean();
          respond(
              exchange,
              200,
              archived
                  ? "{\"status\":\"OK\",\"result\":{\"offers\":[]}}"
                  : """
                  {"status":"OK","result":{"offers":[
                    {"offerId":"A","price":{"value":100,"currencyId":"RUR"}},
                    {"offerId":"B","price":{"value":200,"currencyId":"RUR"}}]}}
                  """);
        });
    var catalog = catalog();
    var context = context(run);
    assertEquals("canonicalization", catalog.execute(context).nextLane());
    assertEquals(1, requests.get());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_offer_stage WHERE run_id=:id")
            .param("id", run)
            .query(Integer.class)
            .single());
    assertEquals(
        1,
        owner
            .sql(
                "SELECT count(*) FROM marketplace_raw_page p JOIN platform_file f"
                    + " ON f.id=p.file_id WHERE p.run_id=:id AND NOT p.parsed"
                    + " AND f.state='STORED' AND f.eof_confirmed")
            .param("id", run)
            .query(Integer.class)
            .single());
    // A worker may die after raw/checkpoint commit, before persisting the lane handoff.
    owner
        .sql("UPDATE marketplace_sync_run SET state='PARSING' WHERE id=:id")
        .param("id", run)
        .update();
    assertEquals("canonicalization", catalog.execute(context).nextLane());
    assertEquals(1, requests.get());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_offer_stage WHERE run_id=:id")
            .param("id", run)
            .query(Integer.class)
            .single());
    var canonical = SourceJobSteps.inLane(context, "canonicalization");
    assertEquals("fetch", catalog.execute(canonical).nextLane());
    assertEquals(1, requests.get());
    assertEquals(
        1,
        owner
            .sql("SELECT count(*) FROM marketplace_offer_stage WHERE run_id=:id")
            .param("id", run)
            .query(Integer.class)
            .single());
    // A committed cursor must not let the old canonical claimant fetch the next page.
    assertEquals("fetch", catalog.execute(canonical).nextLane());
    assertEquals(1, requests.get());
    assertEquals(0, offers());
    for (int i = 0; i < 6; i++) {
      awaitQuotaSpacing();
      var result = SourceJobSteps.executeStep(catalog, context);
      if (result.state().equals("SUCCEEDED")) {
        break;
      }
    }
    assertEquals(2, offers());
    assertEquals(
        List.of("limit=100", "limit=100&pageToken=opaque%2B%2F%3D+%26", "limit=100"), queries);
    assertEquals(
        "PUBLISHED",
        owner
            .sql("SELECT state FROM marketplace_sync_run WHERE id=:id")
            .param("id", run)
            .query(String.class)
            .single());
    assertEquals(
        5,
        owner
            .sql("SELECT count(*) FROM marketplace_raw_page WHERE run_id=:id AND parsed")
            .param("id", run)
            .query(Integer.class)
            .single());
  }

  @Test
  void rateLimitRawIsStoredAndRetryAfterStopsTheNextHttpAdmission() throws Exception {
    fixtureEndpoint(
        "/v2/businesses/" + externalId + "/offer-prices",
        exchange -> {
          request(exchange, "POST");
          body(exchange);
          exchange.getResponseHeaders().add("Retry-After", "120");
          respond(exchange, 429, "{\"status\":\"ERROR\",\"errors\":[{\"code\":\"LIMIT\"}]}");
        });
    var rejected = read(VendorMethod.YANDEX_PRICES, "{}", null, null);
    assertEquals(429, rejected.status());
    assertStored(rejected);
    var waiting = read(VendorMethod.YANDEX_PRICES, "{}", null, null);
    assertNotNull(waiting.retryAt());
    assertTrue(waiting.retryAt().isAfter(Instant.now().plusSeconds(90)));
    assertNull(waiting.raw());
    assertEquals(1, requests.get());
  }

  @Test
  void redirectIsStoredButNeverFollowedAndForeignReportUrlNeverReachesTransport() throws Exception {
    AtomicInteger leaked = new AtomicInteger();
    fixtureEndpoint(
        "/v2/campaigns",
        exchange -> {
          request(exchange, "GET");
          exchange.getResponseHeaders().add("Location", "/leak");
          respond(exchange, 302, "redirect");
        });
    fixtureEndpoint(
        "/leak",
        exchange -> {
          leaked.incrementAndGet();
          respond(exchange, 200, "{}");
        });
    var response = read(VendorMethod.YANDEX_CAMPAIGNS, "", null, null);
    assertEquals(302, response.status());
    assertStored(response);
    assertFalse(response.successful());
    assertEquals(0, leaked.get());
    assertEquals(
        "REPORT_DOWNLOAD_HOST_UNCONFIRMED",
        assertThrows(
                BusinessException.class,
                () ->
                    gateway.downloadReport(
                        scope,
                        UUID.randomUUID(),
                        "https://storage.yandexcloud.net.evil.invalid/report",
                        Instant.now().plusSeconds(60)))
            .code());
    assertEquals(1, requests.get());
  }

  @Test
  void malformedCompleteHttpBodyStaysRawAndCannotPublishCatalog() throws Exception {
    UUID run = run("CATALOG", "CATALOG");
    fixtureEndpoint(
        "/v2/businesses/" + externalId + "/offer-mappings",
        exchange -> {
          request(exchange, "POST");
          body(exchange);
          respond(
              exchange,
              200,
              "{\"status\":\"OK\",\"result\":{\"offerMappings\":[{\"offer\":{\"offerId\":\"A\",\"name\":\"First\"}}]");
        });
    assertEquals("BLOCKED", SourceJobSteps.executeStep(catalog(), context(run)).state());
    assertEquals(0, offers());
    assertEquals(
        1,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_external_call e JOIN platform_file f ON f.id=e.raw_file_id
                WHERE e.account_id=:account AND e.outcome='RESPONSE' AND f.state='STORED' AND f.eof_confirmed
                """)
            .param("account", scope.accountId())
            .query(Integer.class)
            .single());
    assertEquals(
        0,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_profile WHERE account_id=:account AND status='CONFIRMED'
                """)
            .param("account", scope.accountId())
            .query(Integer.class)
            .single());
  }

  @Test
  void compressedExpansionIsLimitedAfterExactCompressedRawIsCommitted() throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var gzip = new GZIPOutputStream(bytes)) {
      byte[] block = new byte[8192];
      for (int i = 0; i < 128; i++) {
        gzip.write(block);
      }
    }
    byte[] compressed = bytes.toByteArray();
    fixtureEndpoint(
        "/v2/campaigns",
        exchange -> {
          request(exchange, "GET");
          exchange.getResponseHeaders().add("Content-Encoding", "gzip");
          exchange.sendResponseHeaders(200, compressed.length);
          try (var output = exchange.getResponseBody()) {
            output.write(compressed);
          }
        });
    var response = read(VendorMethod.YANDEX_CAMPAIGNS, "", null, null);
    assertStored(response);
    assertEquals(compressed.length, response.raw().byteCount());
    try (InputStream input = gateway.open(response)) {
      assertThrows(
          IOException.class, () -> input.transferTo(java.io.OutputStream.nullOutputStream()));
    }
  }

  @Test
  void lostWriteResponseCannotCauseASecondSendOfTheAdmittedCommand() throws Exception {
    owner
        .sql("UPDATE marketplace_connection SET read_only=false WHERE account_id=:account")
        .param("account", scope.accountId())
        .update();
    String payload =
        "{\"offers\":[{\"offerId\":\"A\",\"price\":{\"value\":100,\"currencyId\":\"RUR\"}}]}";
    fixtureEndpoint(
        "/v2/businesses/" + externalId + "/offer-prices/updates",
        exchange -> {
          request(exchange, "POST");
          assertEquals(payload, body(exchange));
          exchange.close();
        });
    UUID command = UUID.randomUUID();
    assertTrue(
        scopes
            .run(
                scope,
                () -> gateway.admit(scope, command, VendorMethod.YANDEX_SET_PRICE, payload, 1))
            .admitted());
    assertThrows(
        IOException.class,
        () ->
            gateway.execute(scope, VendorMethod.YANDEX_SET_PRICE, payload, null, null, 1, command));
    assertEquals(
        "UNKNOWN",
        owner
            .sql("SELECT outcome FROM marketplace_external_call WHERE id=:id")
            .param("id", command)
            .query(String.class)
            .single());
    assertEquals(
        "EXTERNAL_CALL_ALREADY_ADMITTED",
        assertThrows(
                BusinessException.class,
                () ->
                    gateway.execute(
                        scope, VendorMethod.YANDEX_SET_PRICE, payload, null, null, 1, command))
            .code());
    assertEquals(1, requests.get());
  }

  @Test
  void invalidGzipHeaderReleasesTheRawReadPinWhenDecoderCannotBeCreated() throws Exception {
    fixtureEndpoint(
        "/v2/campaigns",
        exchange -> {
          request(exchange, "GET");
          exchange.getResponseHeaders().add("Content-Encoding", "gzip");
          respond(exchange, 200, "invalid gzip header");
        });
    var response = read(VendorMethod.YANDEX_CAMPAIGNS, "", null, null);
    assertStored(response);
    assertThrows(IOException.class, () -> gateway.open(response));
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM platform_file_read_pin WHERE file_id=:id")
            .param("id", response.raw().id())
            .query(Integer.class)
            .single());
  }

  @Test
  void foreignCampaignResponseCannotEnterTheAccountOrConfirmDiscovery() throws Exception {
    UUID run = run("STOCKS", "DISCOVERY");
    fixtureEndpoint(
        "/v2/campaigns",
        exchange -> {
          request(exchange, "GET");
          respond(
              exchange,
              200,
              """
              {"campaigns":[{"id":101,"business":{"id":%s},
                "placementType":"FBS","apiAvailability":"AVAILABLE"}]}
              """
                  .formatted(externalId.equals("1") ? "2" : "1"));
        });
    var handler =
        new StockSyncHandler(
            jdbc,
            scopes,
            authorization,
            connections,
            catalog(),
            gateway,
            capabilities,
            files,
            jobs,
            JSON,
            outbox,
            CLOCK,
            new SourceSnapshotPublisher(jdbc));
    assertEquals(
        "ACCOUNT_SCOPE_MISMATCH",
        assertThrows(
                BusinessException.class, () -> SourceJobSteps.executeStep(handler, context(run)))
            .code());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_campaign WHERE account_id=:id")
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
    assertEquals(
        "INCOMPLETE",
        owner
            .sql("SELECT state FROM marketplace_sync_run WHERE id=:id")
            .param("id", run)
            .query(String.class)
            .single());
    assertEquals(1, requests.get());
  }

  @Test
  void reportGenerationPollingAndArchiveRunThroughTheDurableOwner() throws Exception {
    var context = reportContext();
    UUID report = JSON.decode(context.payload(), ReportSyncService.ReportJob.class).id();
    var zipBytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(zipBytes, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry("transaction_date.json"));
      zip.write("[]".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    byte[] archive = zipBytes.toByteArray();
    String reportIdentity = "r".repeat(220) + "/part?key=1#fragment+ .";
    AtomicInteger generations = new AtomicInteger();
    AtomicInteger polls = new AtomicInteger();
    AtomicInteger downloads = new AtomicInteger();
    fixtureEndpoint(
        "/v2/reports/united-netting/generate",
        exchange -> {
          request(exchange, "POST");
          assertEquals("format=JSON", exchange.getRequestURI().getRawQuery());
          var requested = JsonParser.parseString(body(exchange)).getAsJsonObject();
          assertEquals(externalId, requested.get("businessId").getAsString());
          assertEquals("2026-09-01", requested.get("dateFrom").getAsString());
          generations.incrementAndGet();
          respond(
              exchange,
              200,
              JSON.encode(Map.of("status", "OK", "result", Map.of("reportId", reportIdentity))));
        });
    fixtureEndpoint(
        "/v2/reports/info/",
        exchange -> {
          request(exchange, "GET");
          assertEquals(
              "/v2/reports/info/" + "r".repeat(220) + "%2Fpart%3Fkey%3D1%23fragment%2B%20%2E",
              exchange.getRequestURI().getRawPath());
          assertNull(exchange.getRequestURI().getRawQuery());
          if (polls.incrementAndGet() == 1) {
            respond(exchange, 200, "{\"status\":\"OK\",\"result\":{\"status\":\"PENDING\"}}");
          } else {
            respond(
                exchange,
                200,
                """
                {"status":"OK","result":{"status":"DONE",
                  "generationFinishedAt":"2026-10-01T04:00:00Z",
                  "file":"https://storage.yandexcloud.net/simulation/report.zip?signature=fixture"}}
                """);
          }
        });
    fixtureEndpoint(
        "/simulation/report.zip",
        exchange -> {
          downloads.incrementAndGet();
          assertEquals("GET", exchange.getRequestMethod());
          assertNull(exchange.getRequestHeaders().getFirst("Api-Key"));
          assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.sendResponseHeaders(200, 0);
          try (var output = exchange.getResponseBody()) {
            output.write(archive);
          }
        });
    var handler = reports();
    assertEquals("WAITING", handler.execute(context).state());
    assertEquals("WAITING", handler.execute(context).state());
    awaitQuotaSpacing();
    assertEquals("canonicalization", handler.execute(context).nextLane());
    assertEquals(
        "STORED",
        owner
            .sql("SELECT state FROM marketplace_report WHERE id=:id")
            .param("id", report)
            .query(String.class)
            .single());
    assertEquals("SUCCEEDED", SourceJobSteps.executeStep(handler, context).state());
    assertEquals("SUCCEEDED", SourceJobSteps.executeStep(handler, context).state());
    assertEquals(1, generations.get());
    assertEquals(2, polls.get());
    assertEquals(1, downloads.get());
    assertEquals(
        reportIdentity,
        owner
            .sql("SELECT supplier_report_id FROM marketplace_report WHERE id=:id")
            .param("id", report)
            .query(String.class)
            .single());
    assertEquals(
        "PUBLISHED",
        owner
            .sql("SELECT state FROM marketplace_report WHERE id=:id")
            .param("id", report)
            .query(String.class)
            .single());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_payment WHERE account_id=:id")
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
  }

  @Test
  void lostReportGenerationResponseStaysUnknownAndIsNeverGeneratedAgain() throws Exception {
    var context = reportContext();
    UUID report = JSON.decode(context.payload(), ReportSyncService.ReportJob.class).id();
    fixtureEndpoint(
        "/v2/reports/united-netting/generate",
        exchange -> {
          request(exchange, "POST");
          body(exchange);
          exchange.close();
        });
    var handler = reports();
    assertEquals("BLOCKED", SourceJobSteps.executeStep(handler, context).state());
    assertEquals(
        "UNKNOWN",
        owner
            .sql("SELECT state FROM marketplace_report WHERE id=:id")
            .param("id", report)
            .query(String.class)
            .single());
    assertEquals("BLOCKED", SourceJobSteps.executeStep(handler, context).state());
    assertEquals(1, requests.get());
    assertEquals(
        "UNKNOWN",
        owner
            .sql(
                """
                SELECT c.outcome FROM marketplace_external_call c
                JOIN marketplace_report r ON r.generation_call_id=c.id WHERE r.id=:id
                """)
            .param("id", report)
            .query(String.class)
            .single());
  }

  @ParameterizedTest
  @ValueSource(strings = {"TOO_LARGE", "RESOURCE_NOT_FOUND"})
  void incompleteReportNeverDownloadsOrPublishesEvenWhenStatusIsDone(String subStatus)
      throws Exception {
    var context = reportContext();
    UUID report = JSON.decode(context.payload(), ReportSyncService.ReportJob.class).id();
    fixtureEndpoint(
        "/v2/reports/united-netting/generate",
        exchange -> {
          request(exchange, "POST");
          body(exchange);
          respond(
              exchange, 200, "{\"status\":\"OK\",\"result\":{\"reportId\":\"incomplete-report\"}}");
        });
    fixtureEndpoint(
        "/v2/reports/info/incomplete-report",
        exchange -> {
          request(exchange, "GET");
          respond(
              exchange,
              200,
              """
              {"status":"OK","result":{"status":"DONE","subStatus":"%s",
                "generationFinishedAt":"2026-10-01T04:00:00Z",
                "file":"https://storage.yandexcloud.net/incomplete.zip"}}
              """
                  .formatted(subStatus));
        });
    var handler = reports();
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context).state());
    assertEquals("BLOCKED", SourceJobSteps.executeStep(handler, context).state());
    assertEquals("BLOCKED", SourceJobSteps.executeStep(handler, context).state());
    assertEquals(2, requests.get());
    assertEquals(
        "REPORT_INCOMPLETE",
        owner
            .sql("SELECT reason FROM marketplace_report WHERE id=:id")
            .param("id", report)
            .query(String.class)
            .single());
    assertFalse(
        owner
            .sql("SELECT financial_complete FROM marketplace_report WHERE id=:id")
            .param("id", report)
            .query(Boolean.class)
            .single());
    assertEquals(
        0,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_external_call WHERE account_id=:id
                  AND method='YANDEX_REPORT_DOWNLOAD'
                """)
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
    assertEquals(
        2,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_external_call c JOIN platform_file f ON f.id=c.raw_file_id
                WHERE c.account_id=:id AND c.outcome='RESPONSE' AND f.state='STORED' AND f.eof_confirmed
                """)
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
  }

  @Test
  void revokedMembershipCannotReadTheSupplierOrAllocateRawStorage() {
    owner
        .sql(
            "UPDATE access_membership SET active=false WHERE organization_id=:org AND"
                + " subject_id=:subject")
        .param("org", scope.organizationId())
        .param("subject", scope.subjectId())
        .update();
    var denied =
        assertThrows(
            BusinessException.class, () -> read(VendorMethod.YANDEX_CATALOG, "{}", null, null));
    assertEquals(403, denied.status());
    assertEquals(0, requests.get());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM platform_file WHERE account_id=:id")
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
    assertEquals(
        0,
        owner
            .sql("SELECT count(*) FROM marketplace_external_call WHERE account_id=:id")
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
  }

  private JobContext reportContext() {
    return reportContext("PAYMENTS");
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMPLETE", "PROCESSING", "TRUNCATED"})
  void promotionCompositionWaitsForEveryPageAndNeverEnablesWrites(String scenario)
      throws Exception {
    fixtureOffer("A");
    fixtureOffer("B");
    UUID run = run("PROMOTIONS", "PROMOS");
    fixtureEndpoint(
        "/v2/businesses/" + externalId + "/promos",
        exchange -> {
          request(exchange, "POST");
          assertEquals("{}", body(exchange));
          respond(
              exchange,
              200,
              """
              {"status":"OK","result":{"promos":[{"id":"discount","name":"Discount",
                "mechanicsInfo":{"type":"DIRECT_DISCOUNT"},
                "period":{"dateTimeFrom":"2026-01-01T00:00:00Z","dateTimeTo":"2027-01-01T00:00:00Z"},
                "assortmentInfo":{"processing":%s}}]}}
              """
                  .formatted(scenario.equals("PROCESSING")));
        });
    AtomicInteger pages = new AtomicInteger();
    fixtureEndpoint(
        "/v2/businesses/" + externalId + "/promos/offers",
        exchange -> {
          request(exchange, "POST");
          assertEquals(
              JsonParser.parseString("{\"promoId\":\"discount\"}"),
              JsonParser.parseString(body(exchange)));
          if (pages.incrementAndGet() == 1) {
            respond(
                exchange,
                200,
                """
                {"status":"OK","result":{"paging":{"nextPageToken":"promo+/="},"offers":[
                  {"offerId":"A","status":"AUTO","params":{"discountParams":{
                    "price":100,"promoPrice":90,"maxPromoPrice":95}},
                    "autoParticipatingDetails":{"campaignIds":[101]}}]}}
                """);
          } else {
            assertEquals(
                "limit=100&pageToken=promo%2B%2F%3D", exchange.getRequestURI().getRawQuery());
            respond(
                exchange,
                200,
                scenario.equals("TRUNCATED")
                    ? "{\"status\":\"OK\",\"result\":"
                    : """
                    {"status":"OK","result":{"offers":[
                      {"offerId":"B","status":"PARTIALLY_AUTO","params":{"discountParams":{
                        "price":100,"promoPrice":90,"maxPromoPrice":95}},
                        "autoParticipatingDetails":{"campaignIds":[101]}}]}}
                    """);
          }
        });
    var handler =
        new PromotionSyncHandler(
            jdbc,
            scopes,
            authorization,
            connections,
            gateway,
            capabilities,
            catalog(),
            jobs,
            files,
            JSON,
            outbox,
            new AuditService(jdbc),
            CLOCK,
            new SourceSnapshotPublisher(jdbc));
    var context = context(run);
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context).state());
    awaitQuotaSpacing();
    assertEquals("WAITING", SourceJobSteps.executeStep(handler, context).state());
    assertEquals(0, count("marketplace_promotion_offer"));
    var result = finish(handler, context);
    if (scenario.equals("COMPLETE")) {
      assertEquals("SUCCEEDED", result.state());
      assertEquals(
          List.of("AUTO", "PARTIALLY_AUTO"),
          owner
              .sql(
                  """
                  SELECT status FROM marketplace_promotion_offer WHERE account_id=:id ORDER BY status
                  """)
              .param("id", scope.accountId())
              .query(String.class)
              .list());
    } else {
      assertEquals("BLOCKED", result.state());
      assertEquals(0, count("marketplace_promotion_offer"));
    }
    assertEquals(2, pages.get());
    assertEquals(
        0,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_profile WHERE account_id=:id
                  AND constraints->>'writeTested'='true'
                """)
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
  }

  @ParameterizedTest
  @ValueSource(strings = {"ORIGINAL", "MISSING_STATS", "FOREIGN_CAMPAIGN"})
  void originalOrdersNeedMatchingCompositionAndVerifiedCampaign(String scenario) throws Exception {
    UUID offer = fixtureOffer("A");
    fixtureCampaign(offer);
    UUID run = run("HISTORY", "BUSINESS");
    owner
        .sql(
            "UPDATE marketplace_sync_run SET range_from='2026-09-01',range_until='2026-09-01' WHERE"
                + " id=:id")
        .param("id", run)
        .update();
    fixtureEndpoint(
        "/v1/businesses/" + externalId + "/orders",
        exchange -> {
          request(exchange, "POST");
          assertFalse(
              JsonParser.parseString(body(exchange)).getAsJsonObject().get("fake").getAsBoolean());
          respond(
              exchange,
              200,
              """
              {"orders":[{"orderId":501,"campaignId":%s,"fake":false,
                "creationDate":"2026-09-01T09:00:00Z","updateDate":"2026-09-01T10:00:00Z"}]}
              """
                  .formatted(scenario.equals("FOREIGN_CAMPAIGN") ? "202" : "101"));
        });
    AtomicInteger stats = new AtomicInteger();
    fixtureEndpoint(
        "/v2/campaigns/101/stats/orders",
        exchange -> {
          request(exchange, "POST");
          body(exchange);
          stats.incrementAndGet();
          respond(
              exchange,
              200,
              scenario.equals("MISSING_STATS")
                  ? "{\"status\":\"OK\",\"result\":{\"orders\":[]}}"
                  : """
                  {"status":"OK","result":{"orders":[{"id":501,"fake":false,
                    "statusUpdateDate":"2026-09-01T14:00:00",
                    "items":[{"shopSku":"A","count":2,"initialCount":5}],
                    "initialItems":[{"shopSku":"A","initialCount":5}]}]}}
                  """);
        });
    var handler =
        new HistorySyncHandler(
            jdbc,
            scopes,
            authorization,
            connections,
            catalog(),
            gateway,
            capabilities,
            files,
            jobs,
            JSON,
            outbox,
            new AuditService(jdbc),
            CLOCK);
    var result = finish(handler, context(run));
    if (scenario.equals("ORIGINAL")) {
      assertEquals("SUCCEEDED", result.state());
      assertEquals(1, count("marketplace_order_item"));
      assertEquals(1, count("marketplace_demand_observation"));
      assertEquals(
          0,
          new BigDecimal("5")
              .compareTo(
                  owner
                      .sql(
                          """
                          SELECT original_quantity FROM marketplace_order_item WHERE account_id=:id
                          """)
                      .param("id", scope.accountId())
                      .query(BigDecimal.class)
                      .single()));
      assertEquals(
          0,
          new BigDecimal("2")
              .compareTo(
                  owner
                      .sql(
                          """
                          SELECT current_quantity FROM marketplace_order_item WHERE account_id=:id
                          """)
                      .param("id", scope.accountId())
                      .query(BigDecimal.class)
                      .single()));
    } else {
      assertEquals("BLOCKED", result.state());
      assertEquals(0, count("marketplace_order_item"));
      assertEquals(0, count("marketplace_demand_observation"));
    }
    assertEquals(scenario.equals("FOREIGN_CAMPAIGN") ? 0 : 1, stats.get());
    if (scenario.equals("FOREIGN_CAMPAIGN")) {
      assertEquals(
          0,
          owner
              .sql(
                  "SELECT count(*) FROM marketplace_external_call WHERE account_id=:account"
                      + " AND method='YANDEX_ORDER_STATS'")
              .param("account", scope.accountId())
              .query(Integer.class)
              .single());
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"CAMPAIGN", "WAREHOUSE", "MISSING_RESERVED", "UNKNOWN_MODEL", "FOREIGN_WAREHOUSE"})
  void stockVersionComesFromVerifiedSettingsAndHiddenOffersAreReadCompletely(String scenario)
      throws Exception {
    fixtureOffer("A");
    fixtureOffer("B");
    boolean unified = !scenario.equals("CAMPAIGN");
    UUID run = run("STOCKS", "DISCOVERY");
    fixtureEndpoint(
        "/v2/campaigns",
        exchange -> {
          request(exchange, "GET");
          respond(
              exchange,
              200,
              """
              {"campaigns":[{"id":101,"business":{"id":%s},
                "placementType":"FBS","apiAvailability":"AVAILABLE"}]}
              """
                  .formatted(externalId));
        });
    fixtureEndpoint(
        "/v2/businesses/" + externalId + "/settings",
        exchange -> {
          request(exchange, "POST");
          assertNull(exchange.getRequestURI().getRawQuery());
          body(exchange);
          respond(
              exchange,
              200,
              """
              {"status":"OK","result":{"info":{"id":%s},
                "settings":{"warehouseModel":"%s","onlyDefaultPrice":true,"currency":"RUR"}}}
              """
                  .formatted(
                      externalId,
                      scenario.equals("UNKNOWN_MODEL")
                          ? "FUTURE"
                          : unified ? "WAREHOUSE" : "CAMPAIGN"));
        });
    fixtureEndpoint(
        (unified ? "/v3" : "/v2") + "/businesses/" + externalId + "/warehouses",
        exchange -> {
          request(exchange, "POST");
          assertEquals("limit=30", exchange.getRequestURI().getRawQuery());
          assertEquals("{}", body(exchange));
          respond(
              exchange,
              200,
              unified
                  ? """
                  {"status":"OK","result":{"warehouses":[{"id":700,"name":"Основной",
                    "models":[{"placementType":"FBS","apiAvailability":"AVAILABLE"}]}]}}
                  """
                  : """
                  {"status":"OK","result":{"warehouses":[{"id":700,"name":"Основной",
                    "campaignId":101,"groupInfo":{"id":900,"name":"Общий остаток"}}]}}
                  """);
        });
    fixtureEndpoint(
        "/v2/campaigns/101/offers",
        exchange -> {
          request(exchange, "POST");
          body(exchange);
          // The deprecated available field is deliberately absent.
          respond(
              exchange,
              200,
              """
              {"status":"OK","result":{"offers":[{"offerId":"A","status":"PUBLISHED"},
                {"offerId":"B","status":"PUBLISHED"}]}}
              """);
        });
    AtomicInteger hiddenPages = new AtomicInteger();
    fixtureEndpoint(
        "/v2/campaigns/101/hidden-offers",
        exchange -> {
          request(exchange, "GET");
          int page = hiddenPages.incrementAndGet();
          assertEquals(
              page == 1 ? "limit=100" : "limit=100&pageToken=hidden-next",
              exchange.getRequestURI().getRawQuery());
          respond(
              exchange,
              200,
              page == 1
                  ? "{\"status\":\"OK\",\"result\":{\"hiddenOffers\":[],\"paging\":{\"nextPageToken\":\"hidden-next\"}}}"
                  : "{\"status\":\"OK\",\"result\":{\"hiddenOffers\":[{\"offerId\":\"B\"}]}}");
        });
    AtomicInteger stockPages = new AtomicInteger();
    fixtureEndpoint(
        unified
            ? "/v3/businesses/" + externalId + "/offers/stocks"
            : "/v2/campaigns/101/offers/stocks",
        exchange -> {
          request(exchange, "POST");
          String requested = body(exchange);
          if (unified) {
            assertEquals(
                "700",
                JsonParser.parseString(requested)
                    .getAsJsonObject()
                    .get("partnerWarehouseId")
                    .getAsString());
          } else {
            assertEquals("{}", requested);
          }
          int page = stockPages.incrementAndGet();
          assertEquals(
              page == 1 ? "limit=100" : "limit=100&pageToken=stock%2B%2F%3D",
              exchange.getRequestURI().getRawQuery());
          String offer =
              """
              {"offerId":"%s","stocks":[{"type":"AVAILABLE","count":8}%s],"updatedAt":"%s"}
              """
                  .formatted(
                      page == 1 ? "A" : "B",
                      scenario.equals("MISSING_RESERVED")
                          ? ""
                          : ",{\"type\":\"FREEZE\",\"count\":2}",
                      CLOCK.instant());
          String paging = page == 1 ? ",\"paging\":{\"nextPageToken\":\"stock+/=\"}" : "";
          String result =
              unified
                  ? "\"offers\":["
                      + offer
                      + "],\"partnerWarehouseId\":"
                      + (scenario.equals("FOREIGN_WAREHOUSE") ? "701" : "700")
                  : "\"warehouses\":[{\"warehouseId\":700,\"offers\":[" + offer + "]}]";
          respond(exchange, 200, "{\"status\":\"OK\",\"result\":{" + result + paging + "}}");
        });
    var handler =
        new StockSyncHandler(
            jdbc,
            scopes,
            authorization,
            connections,
            catalog(),
            gateway,
            capabilities,
            files,
            jobs,
            JSON,
            outbox,
            CLOCK,
            new SourceSnapshotPublisher(jdbc));
    if (scenario.equals("UNKNOWN_MODEL")) {
      assertEquals(
          "WAREHOUSE_MODEL_UNKNOWN",
          assertThrows(BusinessException.class, () -> finish(handler, context(run))).code());
      assertEquals(0, stockPages.get());
      assertEquals(
          0,
          owner
              .sql("SELECT count(*) FROM marketplace_stock_pool WHERE account_id=:id")
              .param("id", scope.accountId())
              .query(Integer.class)
              .single());
      return;
    }
    var outcome = finish(handler, context(run));
    if (scenario.equals("FOREIGN_WAREHOUSE")) {
      assertEquals("BLOCKED", outcome.state());
      assertEquals(1, stockPages.get());
      assertEquals(
          0,
          owner
              .sql("SELECT count(*) FROM marketplace_stock_pool WHERE account_id=:id")
              .param("id", scope.accountId())
              .query(Integer.class)
              .single());
      return;
    }
    assertEquals(scenario.equals("MISSING_RESERVED") ? "BLOCKED" : "SUCCEEDED", outcome.state());
    assertEquals(2, stockPages.get());
    assertEquals(2, hiddenPages.get());
    assertEquals(
        1,
        owner
            .sql(
                "SELECT count(*) FROM marketplace_warehouse WHERE account_id=:id AND"
                    + " name='Основной'")
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
    assertEquals(
        List.of(true, false),
        owner
            .sql(
                """
                SELECT p.available FROM marketplace_placement p JOIN marketplace_offer o ON o.id=p.offer_id
                WHERE p.account_id=:id ORDER BY o.sku
                """)
            .param("id", scope.accountId())
            .query(Boolean.class)
            .list());
    assertEquals(
        scenario.equals("MISSING_RESERVED") ? 0 : 2,
        owner
            .sql("SELECT count(*) FROM marketplace_stock_pool WHERE account_id=:id AND complete")
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
    assertEquals(
        0,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_external_call WHERE account_id=:id AND method=:wrong
                """)
            .param("id", scope.accountId())
            .param("wrong", unified ? "YANDEX_STOCKS" : "YANDEX_PARTNER_STOCKS")
            .query(Integer.class)
            .single());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void returnArchivePreservesPhysicalProofWithoutInventingRefundOrStock(boolean foreignBusiness)
      throws Exception {
    fixtureOffer("A");
    var context = reportContext("RETURNS");
    var reportId = JSON.decode(context.payload(), ReportSyncService.ReportJob.class).id();
    var archiveBytes = new ByteArrayOutputStream();
    try (var zip = new ZipOutputStream(archiveBytes, StandardCharsets.UTF_8)) {
      zip.putNextEntry(new ZipEntry("returns_for_selected_range.json"));
      zip.write(
          """
          [{"businessId":"%s","returnNumber":"r1","partnerId":"101","shopSku":"A",
            "orderId":"501","count":2,"countOfFit":2,"dateOfReturnCreation":"01.09.2026",
            "status":"PICKED"}]
          """
              .formatted(foreignBusiness ? externalId.equals("1") ? "2" : "1" : externalId)
              .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    fixtureEndpoint(
        "/v2/reports/united-returns/generate",
        exchange -> {
          request(exchange, "POST");
          assertEquals(
              externalId,
              JsonParser.parseString(body(exchange))
                  .getAsJsonObject()
                  .get("businessId")
                  .getAsString());
          respond(
              exchange, 200, "{\"status\":\"OK\",\"result\":{\"reportId\":\"return-fixture\"}}");
        });
    fixtureEndpoint(
        "/v2/reports/info/return-fixture",
        exchange -> {
          request(exchange, "GET");
          respond(
              exchange,
              200,
              """
              {"status":"OK","result":{"status":"DONE","generationFinishedAt":"2026-10-01T04:00:00Z",
                "file":"https://storage.yandexcloud.net/simulation/returns.zip"}}
              """);
        });
    fixtureEndpoint(
        "/simulation/returns.zip",
        exchange -> {
          assertNull(exchange.getRequestHeaders().getFirst("Api-Key"));
          byte[] bytes = archiveBytes.toByteArray();
          exchange.sendResponseHeaders(200, bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    var result = finish(reports(), context);
    assertEquals(foreignBusiness ? "BLOCKED" : "SUCCEEDED", result.state());
    assertEquals(
        foreignBusiness ? 0 : 1,
        owner
            .sql(
                """
                SELECT count(*) FROM marketplace_return WHERE account_id=:id
                  AND physical_receipt_confirmed AND resalable AND refund_confirmed IS NULL AND stock_confirmed IS NULL
                """)
            .param("id", scope.accountId())
            .query(Integer.class)
            .single());
    if (foreignBusiness) {
      assertEquals(
          0,
          owner
              .sql("SELECT count(*) FROM marketplace_return WHERE account_id=:id")
              .param("id", scope.accountId())
              .query(Integer.class)
              .single());
    }
    assertEquals(
        0,
        owner
            .sql(
                "SELECT count(*) FROM marketplace_report_row WHERE report_id=:id AND canonical_fact"
                    + " IS NOT NULL")
            .param("id", reportId)
            .query(Integer.class)
            .single());
  }

  private UUID fixtureOffer(String sku) {
    if (catalogPublication == null) {
      catalogPublication = run("CATALOG", "PUBLISH");
      owner
          .sql("UPDATE marketplace_sync_run SET state='PUBLISHED' WHERE id=:id")
          .param("id", catalogPublication)
          .update();
    }
    UUID id = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
              seller_price,info_complete,commercial_fields,observed_at,publication_id)
            VALUES(:id,:org,:account,:sku,:sku,:sku,100,true,
              '{"value":100,"currencyId":"RUR"}',clock_timestamp(),:publication)
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("sku", sku)
        .param("publication", catalogPublication)
        .update();
    return id;
  }

  private void fixtureCampaign(UUID offer) {
    UUID discovery = run("STOCKS", "PUBLISH");
    owner
        .sql("UPDATE marketplace_sync_run SET state='PUBLISHED' WHERE id=:id")
        .param("id", discovery)
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_campaign(organization_id,account_id,external_id,placement_type,
              availability,discovered_run) VALUES(:org,:account,'101','FBS','AVAILABLE',:run)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", discovery)
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_placement(id,organization_id,account_id,offer_id,external_id,model,
              status,available,fields,publication_id,observed_at,valid_until)
            VALUES(gen_random_uuid(),:org,:account,:offer,'101','FBS','PUBLISHED',true,'{}',:run,
              clock_timestamp(),clock_timestamp()+interval '15 minutes')
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("offer", offer)
        .param("run", discovery)
        .update();
  }

  private JobOutcome finish(JobHandler handler, JobContext context) throws Exception {
    for (int i = 0; i < 16; i++) {
      awaitQuotaSpacing();
      JobOutcome outcome = SourceJobSteps.executeStep(handler, context);
      if (!outcome.state().equals("WAITING")) {
        return outcome;
      }
    }
    throw new AssertionError("Fixture traversal did not finish within 16 bounded attempts");
  }

  private int count(String table) {
    if (!Set.of(
            "marketplace_promotion_offer",
            "marketplace_order_item",
            "marketplace_demand_observation")
        .contains(table)) {
      throw new IllegalArgumentException("Unsupported fixture table");
    }
    return owner
        .sql("SELECT count(*) FROM " + table + " WHERE account_id=:id")
        .param("id", scope.accountId())
        .query(Integer.class)
        .single();
  }

  private JobContext reportContext(String kind) {
    UUID report = UUID.randomUUID();
    UUID job = UUID.randomUUID();
    UUID generation = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,
              business_key,payload,state,due_at) VALUES(:id,:org,:account,:subject,'REPORT_SYNC',
              'fetch',:key,'{}','READY',clock_timestamp())
            """)
        .param("id", job)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("key", report.toString())
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_report(id,organization_id,account_id,job_id,kind,date_from,date_until,
              state,generation_call_id) VALUES(:id,:org,:account,:job,:kind,
              '2026-09-01','2026-09-30','QUEUED',:generation)
            """)
        .param("id", report)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("job", job)
        .param("kind", kind)
        .param("generation", generation)
        .update();
    return new JobContext(
        job,
        scope,
        JSON.encode(new ReportSyncService.ReportJob(report)),
        1,
        Instant.now().plusSeconds(60),
        1,
        "fetch");
  }

  private ReportSyncService reports() {
    return new ReportSyncService(
        jdbc,
        scopes,
        authorization,
        connections,
        gateway,
        jobs,
        new IdempotencyService(jdbc, JSON),
        JSON,
        files,
        new FileWorkGate(jdbc, CLOCK),
        new AuditService(jdbc),
        outbox,
        CLOCK,
        new ReportPublication(jdbc),
        new QuotaManager(jdbc, manager, CLOCK, "api"),
        capabilities,
        mock(OzonFinancialSync.class));
  }

  private OutboundGateway.Response read(
      VendorMethod method, String body, String campaign, String cursor)
      throws IOException, InterruptedException {
    return gateway.execute(scope, method, body, campaign, cursor, 1, null);
  }

  private void request(HttpExchange exchange, String method) {
    requests.incrementAndGet();
    assertEquals(method, exchange.getRequestMethod());
    assertEquals(apiKey, exchange.getRequestHeaders().getFirst("Api-Key"));
    assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
    assertNull(exchange.getRequestHeaders().getFirst("Client-Id"));
  }

  private void fixtureEndpoint(String path, HttpHandler handler) {
    peer.createContext(
        path,
        exchange -> {
          try {
            handler.handle(exchange);
          } catch (IOException | RuntimeException | AssertionError failure) {
            peerFailure.compareAndSet(null, failure);
            exchange.close();
          }
        });
  }

  private static String body(HttpExchange exchange) throws IOException {
    try (var input = exchange.getRequestBody()) {
      byte[] bytes = input.readNBytes(65537);
      assertTrue(bytes.length <= 65536);
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static void respond(HttpExchange exchange, int status, String text) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private void assertStored(OutboundGateway.Response response) throws IOException {
    assertNotNull(response.raw());
    assertEquals("STORED", response.raw().state());
    assertTrue(response.raw().eofConfirmed());
    assertNotNull(response.raw().objectVersion());
    try (InputStream input = files.open(response.raw())) {
      assertEquals(
          response.raw().byteCount(), input.transferTo(java.io.OutputStream.nullOutputStream()));
    }
    assertTrue(
        owner
            .sql(
                """
                SELECT EXISTS(SELECT 1 FROM platform_file_reference WHERE file_id=:file
                  AND owner_type='external-call' AND owner_id=:call)
                """)
            .param("file", response.raw().id())
            .param("call", response.callId())
            .query(Boolean.class)
            .single());
  }

  private CatalogSyncService catalog() {
    return new CatalogSyncService(
        jdbc,
        scopes,
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
        CLOCK,
        new SourceSnapshotPublisher(jdbc),
        new CategoryService(
            jdbc,
            new NamedParameterJdbcTemplate(
                java.util.Objects.requireNonNull(manager.getDataSource())),
            authorization,
            outbox));
  }

  private UUID run(String source, String phase) {
    UUID run = UUID.randomUUID();
    UUID job = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,
              business_key,payload,state,due_at) VALUES(:id,:org,:account,:subject,:type,
              'fetch',:key,'{}','READY',clock_timestamp())
            """)
        .param("id", job)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param(
            "type",
            switch (source) {
              case "STOCKS" -> "STOCK_SYNC";
              case "PROMOTIONS" -> "PROMOTION_SYNC";
              case "HISTORY" -> "HISTORY_SYNC";
              default -> "CATALOG_SYNC";
            })
        .param("key", run.toString())
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_sync_run(id,organization_id,account_id,job_id,source_type,state,phase)
            VALUES(:id,:org,:account,:job,:source,'FETCHING',:phase)
            """)
        .param("id", run)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("job", job)
        .param("source", source)
        .param("phase", phase)
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_source(id,organization_id,account_id,source_type,status)
            VALUES(gen_random_uuid(),:org,:account,:source,'SYNCING')
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("source", source)
        .update();
    return run;
  }

  private JobContext context(UUID run) {
    UUID job =
        owner
            .sql("SELECT job_id FROM marketplace_sync_run WHERE id=:id")
            .param("id", run)
            .query(UUID.class)
            .single();
    return new JobContext(
        job,
        scope,
        JSON.encode(Map.of("runId", run)),
        1,
        Instant.now().plusSeconds(60),
        1,
        "fetch");
  }

  private int offers() {
    return owner
        .sql("SELECT count(*) FROM marketplace_offer WHERE account_id=:account")
        .param("account", scope.accountId())
        .query(Integer.class)
        .single();
  }

  private static void awaitQuotaSpacing() throws InterruptedException {
    Thread.sleep(2100);
  }
}
