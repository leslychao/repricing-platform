package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.VaultSecretStore;

class CapabilityLifecycleTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("test-only");
  private static JdbcClient owner;
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner transactions;
  private static AuthorizationService authorization;
  private Scope scope;
  private CapabilityService capabilities;

  @BeforeAll
  static void prepare() throws Exception {
    POSTGRES.start();
    owner =
        JdbcClient.create(
            new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "postgres", "test-only"));
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
    authorization = new AuthorizationService(jdbc);
    transactions =
        new ScopeTransactionRunner(jdbc, new DataSourceTransactionManager(source), authorization);
  }

  @BeforeEach
  void fixture() {
    scope = new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    owner
        .sql(
            """
            INSERT INTO access_user(id,issuer,subject,display_name,email,email_verified)
            VALUES (:id,'test',:sub,'Test','test@example.invalid',true)
            """)
        .param("id", scope.subjectId())
        .param("sub", scope.subjectId().toString())
        .update();
    owner
        .sql("INSERT INTO access_organization(id,name) VALUES (:id,'Profile test')")
        .param("id", scope.organizationId())
        .update();
    owner
        .sql(
            """
            INSERT INTO access_membership(id,organization_id,subject_id,role)
            VALUES (gen_random_uuid(),:org,:subject,'OWNER')
            """)
        .param("org", scope.organizationId())
        .param("subject", scope.subjectId())
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
            VALUES (:id,:org,'YANDEX',:external,'Profile test','Europe/Moscow')
            """)
        .param("id", scope.accountId())
        .param("org", scope.organizationId())
        .param("external", scope.accountId().toString())
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_connection(id,organization_id,account_id,secret_path,secret_version,
              client_id,quota_credential_hash,state,read_only,revision)
            VALUES (gen_random_uuid(),:org,:account,'repricer/fixture',1,'fixture','fixture','ACTIVE',false,1)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .update();
    capabilities = new CapabilityService(jdbc, Clock.systemUTC());
  }

  @AfterAll
  static void close() {
    POSTGRES.stop();
  }

  @Test
  void replayAndOldCredentialReadNeverRestoreReplacedCapability() {
    UUID raw = evidence(VendorMethod.YANDEX_PRICES, 1);
    transactions.run(scope, () -> capabilities.confirmRead(scope, VendorMethod.YANDEX_PRICES, raw));
    long revision = revision();
    transactions.run(scope, () -> capabilities.confirmRead(scope, VendorMethod.YANDEX_PRICES, raw));
    assertEquals(revision, revision());
    assertEquals(1, profiles());

    owner
        .sql("UPDATE marketplace_connection SET revision=2 WHERE account_id=:id")
        .param("id", scope.accountId())
        .update();
    transactions.run(scope, () -> capabilities.invalidateConfirmed(scope, "CREDENTIAL_CHANGED"));
    UUID lateRaw = evidence(VendorMethod.YANDEX_PRICES, 1);
    transactions.run(
        scope, () -> capabilities.confirmRead(scope, VendorMethod.YANDEX_PRICES, lateRaw));
    assertEquals(2, profiles());
    assertEquals(
        "REVOKED",
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT status FROM marketplace_profile ORDER BY revision DESC LIMIT 1")
                    .query(String.class)
                    .single()));
    assertEquals(
        1,
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT count(*) FROM marketplace_profile WHERE status='CONFIRMED'")
                    .query(Integer.class)
                    .single()));

    UUID currentRaw = evidence(VendorMethod.YANDEX_PRICES, 2);
    transactions.run(
        scope, () -> capabilities.confirmRead(scope, VendorMethod.YANDEX_PRICES, currentRaw));
    assertEquals(3, profiles());
  }

  @Test
  void apiScopesRemainSeparateFromTestedExecutionAndExactRotationScope() {
    UUID raw = evidence(VendorMethod.YANDEX_AUTH_TOKEN, 1);
    transactions.run(
        scope,
        () -> {
          capabilities.initializeUnconfirmed(scope, "YANDEX");
          capabilities.confirmYandexScopes(scope, raw, Set.of("PRICING"));
          assertTrue(capabilities.equivalentYandexScopes(scope, Set.of("PRICING"), false));
          assertFalse(
              capabilities.equivalentYandexScopes(scope, Set.of("PRICING_READ_ONLY"), false));
          assertFalse(capabilities.equivalentYandexScopes(scope, Set.of("PRICING"), true));
          BusinessException failure =
              assertThrows(
                  BusinessException.class,
                  () ->
                      capabilities.requireWrite(scope, VendorMethod.YANDEX_SET_PRICE, revision()));
          assertEquals("CAPABILITY_UNCONFIRMED", failure.code());
          var reads =
              new MarketplaceReadService(jdbc, authorization, new JsonCodec(), Clock.systemUTC());
          var write =
              reads.capabilities(scope).stream()
                  .filter(value -> value.name().equals("YANDEX_SET_PRICE"))
                  .findFirst()
                  .orElseThrow();
          assertTrue(write.documented());
          assertEquals(Boolean.TRUE, write.apiAvailable());
          assertFalse(write.executionVerified());
          assertEquals("UNCONFIRMED", write.status());
        });
  }

  @Test
  void checkingTheSameCredentialPublishesEachStateChangeWithItsOwnRevision() {
    OutboxService outbox = mock(OutboxService.class);
    var connections =
        new ConnectionService(
            jdbc,
            transactions,
            authorization,
            mock(IdempotencyService.class),
            mock(AuditService.class),
            outbox,
            mock(JobRuntime.class),
            new JsonCodec(),
            mock(VaultSecretStore.class),
            Clock.systemUTC(),
            capabilities);
    transactions.run(scope, () -> connections.checked(scope, 1, false));
    transactions.run(scope, () -> connections.checked(scope, 1, true));
    var keys = ArgumentCaptor.forClass(String.class);
    verify(outbox, times(2)).emit(eq(scope), keys.capture(), eq("connection.changed"), any());
    assertEquals(2, Set.copyOf(keys.getAllValues()).size());
    assertEquals(1L, transactions.run(scope, () -> connections.current(scope).revision()));
  }

  @Test
  void tokenMetadataRejectsDuplicatesAndUnboundedPayload() throws IOException {
    String valid = "{\"status\":\"OK\",\"result\":{\"apiKey\":{\"authScopes\":[\"PRICING\"]}}}";
    assertEquals(Set.of("PRICING"), CapabilityService.yandexScopes(input(valid)));
    assertThrows(
        IOException.class,
        () ->
            CapabilityService.yandexScopes(
                input(valid.replace("[\"PRICING\"]", "[\"PRICING\",\"PRICING\"]"))));
    assertThrows(
        IOException.class, () -> CapabilityService.yandexScopes(input(" ".repeat(16_385))));
  }

  private static ByteArrayInputStream input(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }

  private UUID evidence(VendorMethod method, long credentialRevision) {
    UUID raw = UUID.randomUUID();
    owner
        .sql(
            """
            INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,
              object_version,media_type,state)
            VALUES (:id,:org,:account,:subject,'RAW',:key,'version','application/json','STORED')
            """)
        .param("id", raw)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("subject", scope.subjectId())
        .param("key", raw.toString())
        .update();
    owner
        .sql(
            """
            INSERT INTO marketplace_external_call(id,organization_id,account_id,method,semantic_kind,
              completed_at,status_code,raw_file_id,connection_revision,outcome)
            VALUES (gen_random_uuid(),:org,:account,:method,'READ',clock_timestamp(),200,:raw,:revision,'RESPONSE')
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("method", method.name())
        .param("raw", raw)
        .param("revision", credentialRevision)
        .update();
    return raw;
  }

  private long revision() {
    return owner
        .sql("SELECT capabilities_revision FROM marketplace_account WHERE id=:id")
        .param("id", scope.accountId())
        .query(Long.class)
        .single();
  }

  private int profiles() {
    return owner
        .sql("SELECT count(*) FROM marketplace_profile WHERE account_id=:id")
        .param("id", scope.accountId())
        .query(Integer.class)
        .single();
  }
}
