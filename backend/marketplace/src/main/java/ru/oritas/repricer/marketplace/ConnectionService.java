package ru.oritas.repricer.marketplace;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
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

/** Permanent external identity and replaceable credentials have independent lifetimes. */
@Service
public final class ConnectionService {
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final IdempotencyService idempotency;
  private final AuditService audit;
  private final OutboxService outbox;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final VaultSecretStore vault;
  private final Clock clock;
  private final CapabilityService capabilities;

  public ConnectionService(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      IdempotencyService idempotency,
      AuditService audit,
      OutboxService outbox,
      JobRuntime jobs,
      JsonCodec json,
      VaultSecretStore vault,
      Clock clock,
      CapabilityService capabilities) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.idempotency = idempotency;
    this.audit = audit;
    this.outbox = outbox;
    this.jobs = jobs;
    this.json = json;
    this.vault = vault;
    this.clock = clock;
    this.capabilities = capabilities;
  }

  public MarketplaceReadService.Account createAccount(
      Scope scope,
      String marketplace,
      String externalId,
      String name,
      String timezone,
      UUID requestId) {
    if (scope.accountId() != null
        || !Set.of("OZON", "YANDEX").contains(marketplace)
        || externalId == null
        || !externalId.matches("[1-9][0-9]{0,19}")
        || name == null
        || name.isBlank()
        || name.length() > 160) {
      throw new BusinessException("INVALID_ACCOUNT", 422, "Проверьте данные кабинета");
    }
    try {
      ZoneId.of(timezone);
    } catch (DateTimeException | NullPointerException exception) {
      throw new BusinessException("INVALID_TIMEZONE", 422, "Выберите часовой пояс кабинета");
    }
    try {
      return transactions.run(
          scope,
          () -> {
            authorization.require(scope, "organization.manage");
            return idempotency.execute(
                scope,
                "account.create",
                requestId,
                new AccountIntent(marketplace, externalId, name.strip(), timezone),
                MarketplaceReadService.Account.class,
                () -> {
                  UUID id = UUID.randomUUID();
                  jdbc.sql(
                          """
                          INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                          VALUES (:id,:org,:marketplace,:external,:name,:timezone)
                          """)
                      .param("id", id)
                      .param("org", scope.organizationId())
                      .param("marketplace", marketplace)
                      .param("external", externalId)
                      .param("name", name.strip())
                      .param("timezone", timezone)
                      .update();
                  audit.record(scope, "account.created", id, "marketplace=" + marketplace);
                  outbox.emit(
                      scope,
                      "account:" + id,
                      "account.changed",
                      new OutboxService.EntityChange("accounts", id, 1));
                  return new MarketplaceReadService.Account(
                      id,
                      name.strip(),
                      marketplace,
                      externalId,
                      "NOT_CONNECTED",
                      false,
                      1,
                      timezone);
                });
          });
    } catch (DuplicateKeyException exception) {
      throw new BusinessException(
          "ACCOUNT_ALREADY_REGISTERED",
          409,
          "Этот кабинет уже зарегистрирован. Повторное подключение не создаёт новую область");
    }
  }

  public ConnectionView rotate(
      Scope scope,
      String apiKey,
      String clientId,
      boolean readOnly,
      long expectedRevision,
      UUID requestId) {
    if (apiKey == null
        || apiKey.isBlank()
        || apiKey.length() > 16384
        || apiKey.contains("\r")
        || apiKey.contains("\n")
        || requestId == null) {
      throw new BusinessException("INVALID_CREDENTIALS", 422, "Проверьте ключ подключения");
    }
    AccountIdentity account =
        transactions.run(
            scope,
            () -> {
              authorization.require(scope, "connection.manage");
              return identity(scope);
            });
    if (account.marketplace().equals("OZON") && !account.externalId().equals(clientId)) {
      throw new BusinessException(
          "CLIENT_ID_MISMATCH", 422, "Client-Id должен совпадать с кабинетом");
    }
    String path =
        "repricer/connections/"
            + scope.organizationId()
            + "/"
            + scope.accountId()
            + "/"
            + requestId;
    Map<String, String> stored = vault.putIfAbsent(path, Map.of("apiKey", apiKey));
    if (!stored.get("apiKey").equals(apiKey)) {
      throw new BusinessException(
          "REQUEST_KEY_CONFLICT",
          409,
          "Ключ запроса уже использован для другого ключа подключения");
    }
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "connection.manage");
          return idempotency.execute(
              scope,
              "connection.rotate",
              requestId,
              new RotateIntent(path, clientId, readOnly, expectedRevision),
              ConnectionView.class,
              () -> {
                lockAccount(scope);
                long revision =
                    jdbc.sql(
                            """
                            SELECT revision FROM marketplace_connection WHERE account_id=:account FOR UPDATE
                            """)
                        .param("account", scope.accountId())
                        .query(Long.class)
                        .optional()
                        .orElse(0L);
                if (revision != expectedRevision) {
                  throw new BusinessException(
                      "REVISION_CONFLICT", 409, "Подключение уже изменилось");
                }
                UUID candidate = UUID.randomUUID();
                jdbc.sql(
                        """
                        UPDATE marketplace_connection_candidate SET state='CANCELLED'
                        WHERE account_id=:account AND state='PENDING'
                        """)
                    .param("account", scope.accountId())
                    .update();
                jdbc.sql(
                        """
                        INSERT INTO marketplace_connection_candidate(id,organization_id,account_id,
                          secret_path,client_id,quota_credential_hash,read_only,expected_revision,state)
                        VALUES (:id,:org,:account,:path,:client,:quota,:readOnly,:revision,'PENDING')
                        """)
                    .param("id", candidate)
                    .param("org", scope.organizationId())
                    .param("account", scope.accountId())
                    .param("path", path)
                    .param("client", clientId)
                    .param("quota", QuotaManager.credentialSubject(account.marketplace(), apiKey))
                    .param("readOnly", readOnly)
                    .param("revision", revision)
                    .update();
                jobs.submit(
                    scope,
                    "CONNECTION_CHECK",
                    candidate.toString(),
                    json.encode(new CheckRequest(candidate)));
                audit.record(
                    scope,
                    "connection.candidate.created",
                    scope.accountId(),
                    "expectedRevision=" + revision);
                changed(scope);
                return view(scope);
              });
        });
  }

  public ConnectionView view(Scope scope) {
    authorization.require(scope, "connection.manage");
    boolean pending =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_connection_candidate
                  WHERE account_id=:id AND state='PENDING')
                """)
            .param("id", scope.requireAccount())
            .query(Boolean.class)
            .single();
    return jdbc.sql(
            """
            SELECT state,read_only,revision,checked_at FROM marketplace_connection WHERE account_id=:id
            """)
        .param("id", scope.requireAccount())
        .query(
            (row, index) ->
                new ConnectionView(
                    true,
                    pending ? "CHECKING" : row.getString("state"),
                    row.getBoolean("read_only"),
                    row.getLong("revision"),
                    row.getTimestamp("checked_at") == null
                        ? null
                        : row.getTimestamp("checked_at").toInstant()))
        .optional()
        .orElse(new ConnectionView(pending, pending ? "CHECKING" : "NOT_CONNECTED", true, 0, null));
  }

  public UUID check(Scope scope, UUID requestId) {
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "connection.manage");
          UUID candidate =
              jdbc.sql(
                      """
                      SELECT id FROM marketplace_connection_candidate WHERE account_id=:id AND state='PENDING'
                      """)
                  .param("id", scope.requireAccount())
                  .query(UUID.class)
                  .optional()
                  .orElse(null);
          if (candidate == null) {
            current(scope);
          }
          return jobs.submit(
              scope,
              "CONNECTION_CHECK",
              requestId.toString(),
              json.encode(new CheckRequest(candidate)));
        });
  }

  public ConnectionView disconnect(Scope scope, long expectedRevision, UUID requestId) {
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "connection.manage");
          return idempotency.execute(
              scope,
              "connection.disconnect",
              requestId,
              expectedRevision,
              ConnectionView.class,
              () -> {
                lockAccount(scope);
                jdbc.sql(
                        """
                        UPDATE marketplace_connection_candidate SET state='CANCELLED'
                        WHERE account_id=:account AND state='PENDING'
                        """)
                    .param("account", scope.accountId())
                    .update();
                int changed =
                    jdbc.sql(
                            """
                            UPDATE marketplace_connection SET state='DISABLED',revision=revision+1,
                              credential_generation=credential_generation+1
                            WHERE account_id=:account AND revision=:revision
                            """)
                        .param("account", scope.accountId())
                        .param("revision", expectedRevision)
                        .update();
                if (changed != 1) {
                  if (expectedRevision == 0
                      && jdbc.sql(
                              """
                              SELECT NOT EXISTS(SELECT 1 FROM marketplace_connection WHERE account_id=:account)
                              """)
                          .param("account", scope.accountId())
                          .query(Boolean.class)
                          .single()) {
                    audit.record(
                        scope, "connection.candidate.cancelled", scope.accountId(), "notConnected");
                    changed(scope);
                    return view(scope);
                  }
                  throw new BusinessException(
                      "REVISION_CONFLICT", 409, "Подключение уже изменилось");
                }
                jdbc.sql(
                        """
                        UPDATE marketplace_account SET status='DISABLED',
                          capabilities_revision=capabilities_revision+1 WHERE id=:account
                        """)
                    .param("account", scope.accountId())
                    .update();
                capabilities.invalidateConfirmed(scope, "CONNECTION_DISABLED");
                audit.record(
                    scope,
                    "connection.disabled",
                    scope.accountId(),
                    "revision=" + (expectedRevision + 1));
                changed(scope);
                return view(scope);
              });
        });
  }

  Credentials current(Scope scope) {
    ScopeTransactionRunner.requireCurrent(scope);
    return jdbc.sql(
            """
            SELECT a.marketplace,a.external_id,c.secret_path,c.secret_version,c.client_id,
              c.read_only,c.revision,c.state,c.quota_credential_hash FROM marketplace_connection c
            JOIN marketplace_account a ON a.id=c.account_id AND a.organization_id=c.organization_id
            WHERE c.account_id=:account AND c.state<>'DISABLED'
            """)
        .param("account", scope.requireAccount())
        .query(
            (row, index) ->
                new Credentials(
                    row.getString("marketplace"),
                    row.getString("external_id"),
                    row.getString("secret_path"),
                    row.getLong("secret_version"),
                    row.getString("client_id"),
                    row.getBoolean("read_only"),
                    row.getLong("revision"),
                    row.getString("state"),
                    row.getString("quota_credential_hash")))
        .optional()
        .orElseThrow(() -> new BusinessException("CONNECTION_REQUIRED", 422, "Подключите кабинет"));
  }

  Credentials candidate(Scope scope, UUID id) {
    ScopeTransactionRunner.requireCurrent(scope);
    return jdbc.sql(
            """
            SELECT a.marketplace,a.external_id,c.secret_path,c.client_id,c.read_only,
              c.expected_revision,c.quota_credential_hash
            FROM marketplace_connection_candidate c JOIN marketplace_account a ON a.id=c.account_id
            WHERE c.id=:id AND c.state='PENDING'
            """)
        .param("id", id)
        .query(
            (row, index) ->
                new Credentials(
                    row.getString("marketplace"),
                    row.getString("external_id"),
                    row.getString("secret_path"),
                    1,
                    row.getString("client_id"),
                    row.getBoolean("read_only"),
                    row.getLong("expected_revision") + 1,
                    "UNCHECKED",
                    row.getString("quota_credential_hash")))
        .optional()
        .orElseThrow(
            () ->
                new BusinessException(
                    "CREDENTIAL_CANDIDATE_CANCELLED",
                    409,
                    "Проверяемый ключ уже отменён или применён"));
  }

  void finishCandidate(Scope scope, UUID id, boolean valid, boolean sameCapabilities) {
    lockAccount(scope);
    Credentials candidate = candidate(scope, id);
    long currentRevision =
        jdbc.sql(
                """
                SELECT revision FROM marketplace_connection WHERE account_id=:account FOR UPDATE
                """)
            .param("account", scope.accountId())
            .query(Long.class)
            .optional()
            .orElse(0L);
    if (currentRevision + 1 != candidate.revision()) {
      throw new BusinessException("REVISION_CONFLICT", 409, "Подключение уже изменилось");
    }
    jdbc.sql("UPDATE marketplace_connection_candidate SET state=:state WHERE id=:id")
        .param("state", valid ? "APPLIED" : "INVALID")
        .param("id", id)
        .update();
    if (!valid) {
      audit.record(scope, "connection.candidate.rejected", scope.accountId(), "candidate=" + id);
      changed(scope);
      return;
    }
    jdbc.sql(
            """
            INSERT INTO marketplace_connection(id,organization_id,account_id,secret_path,secret_version,
              client_id,quota_credential_hash,state,read_only,revision,checked_at)
            VALUES (:id,:org,:account,:path,1,:client,:quota,'ACTIVE',:readOnly,:revision,clock_timestamp())
            ON CONFLICT(organization_id,account_id) DO UPDATE SET secret_path=EXCLUDED.secret_path,
              secret_version=1,client_id=EXCLUDED.client_id,quota_credential_hash=EXCLUDED.quota_credential_hash,
              state='ACTIVE',read_only=EXCLUDED.read_only,revision=EXCLUDED.revision,
              checked_at=EXCLUDED.checked_at,credential_generation=marketplace_connection.credential_generation+1
            """)
        .param("id", UUID.randomUUID())
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("path", candidate.path())
        .param("client", candidate.clientId())
        .param("quota", candidate.quotaCredentialHash())
        .param("readOnly", candidate.readOnly())
        .param("revision", candidate.revision())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_account SET status='CONNECTED',
              capabilities_revision=capabilities_revision+CASE WHEN :same THEN 0 ELSE 1 END
            WHERE id=:account
            """)
        .param("account", scope.accountId())
        .param("same", sameCapabilities)
        .update();
    audit.record(
        scope, "connection.rotated", scope.accountId(), "revision=" + candidate.revision());
    changed(scope);
  }

  void checked(Scope scope, long credentialRevision, boolean valid) {
    int changed =
        jdbc.sql(
                """
                UPDATE marketplace_connection SET state=:state,checked_at=:now
                WHERE account_id=:account AND revision=:revision AND state<>'DISABLED'
                """)
            .param("state", valid ? "ACTIVE" : "INVALID")
            .param("now", java.sql.Timestamp.from(clock.instant()))
            .param("account", scope.accountId())
            .param("revision", credentialRevision)
            .update();
    if (changed == 1) {
      jdbc.sql(
              """
              UPDATE marketplace_account SET status=:state WHERE id=:account
              """)
          .param("state", valid ? "CONNECTED" : "CONNECTION_ERROR")
          .param("account", scope.accountId())
          .update();
      changed(scope);
    }
  }

  private AccountIdentity identity(Scope scope) {
    return jdbc.sql("SELECT marketplace,external_id FROM marketplace_account WHERE id=:id")
        .param("id", scope.requireAccount())
        .query((row, index) -> new AccountIdentity(row.getString(1), row.getString(2)))
        .single();
  }

  private void lockAccount(Scope scope) {
    jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
        .param("id", scope.requireAccount())
        .query(UUID.class)
        .single();
  }

  private void changed(Scope scope) {
    long revision =
        jdbc.sql(
                """
                UPDATE marketplace_account SET revision=revision+1 WHERE id=:account RETURNING revision
                """)
            .param("account", scope.requireAccount())
            .query(Long.class)
            .single();
    outbox.emit(
        scope,
        "connection:" + scope.accountId() + ":" + revision,
        "connection.changed",
        new OutboxService.EntityChange("sources", scope.accountId(), revision));
  }

  public record ConnectionView(
      boolean configured, String state, boolean readOnly, long revision, Instant checkedAt) {}

  record CheckRequest(UUID candidateId) {}

  record Credentials(
      String marketplace,
      String externalId,
      String path,
      long version,
      String clientId,
      boolean readOnly,
      long revision,
      String state,
      String quotaCredentialHash) {}

  private record AccountIdentity(String marketplace, String externalId) {}

  private record AccountIntent(
      String marketplace, String externalId, String name, String timezone) {}

  private record RotateIntent(String path, String clientId, boolean readOnly, long revision) {}
}
