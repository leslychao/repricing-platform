package ru.oritas.repricer.marketplace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Scope;

/** A successful read confirms only that exact read method; it never unlocks a write. */
@Service
public final class CapabilityService {
  private final JdbcClient jdbc;
  private final Clock clock;

  public CapabilityService(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public void confirmRead(Scope scope, VendorMethod method, UUID rawFileId) {
    if (method.write()) {
      throw new IllegalArgumentException("Read evidence cannot confirm writes");
    }
    if (!currentReadEvidence(scope, method, rawFileId)) {
      return;
    }
    appendRead(scope, method, rawFileId, "{}");
  }

  private void appendRead(Scope scope, VendorMethod method, UUID rawFileId, String constraints) {
    if (jdbc.sql(
            """
            SELECT EXISTS(SELECT 1 FROM marketplace_profile WHERE method=:method
              AND evidence_file_id=:raw AND status='CONFIRMED')
            """)
        .param("method", method.name())
        .param("raw", rawFileId)
        .query(Boolean.class)
        .single()) {
      return;
    }
    boolean previousConfirmed =
        jdbc.sql(
                """
                SELECT COALESCE((SELECT status='CONFIRMED' AND expires_at>clock_timestamp()
                  FROM marketplace_profile WHERE method=:method ORDER BY revision DESC LIMIT 1),false)
                """)
            .param("method", method.name())
            .query(Boolean.class)
            .single();
    if (!previousConfirmed) {
      jdbc.sql(
              "UPDATE marketplace_account SET capabilities_revision=capabilities_revision+1 WHERE"
                  + " id=:id")
          .param("id", scope.accountId())
          .update();
    }
    long revision =
        jdbc.sql(
                """
                SELECT COALESCE(max(revision),0)+1 FROM marketplace_profile WHERE method=:method
                """)
            .param("method", method.name())
            .query(Long.class)
            .single();
    jdbc.sql(
            """
            INSERT INTO marketplace_profile(id,organization_id,account_id,method,revision,status,
              confirmed_at,expires_at,contract_uri,evidence_file_id,reason,constraints)
            VALUES (:id,:org,:account,:method,:revision,'CONFIRMED',:now,:expires,:uri,:raw,
              'LIVE_READ_VALIDATED',CAST(:constraints AS jsonb))
            """)
        .param("id", UUID.randomUUID())
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("method", method.name())
        .param("revision", revision)
        .param("now", Timestamp.from(clock.instant()))
        .param("expires", Timestamp.from(clock.instant().plusSeconds(86400)))
        .param("uri", method.contractUri())
        .param("raw", rawFileId)
        .param("constraints", constraints)
        .update();
  }

  /** Old-key reads remain usable sources, but cannot re-confirm the replacement key's rights. */
  private boolean currentReadEvidence(Scope scope, VendorMethod method, UUID rawFileId) {
    ScopeTransactionRunner.requireCurrent(scope);
    jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
        .param("id", scope.requireAccount())
        .query(UUID.class)
        .single();
    return jdbc.sql(
            """
            SELECT EXISTS(SELECT 1 FROM marketplace_external_call e
              JOIN marketplace_connection c ON c.account_id=e.account_id
              WHERE e.raw_file_id=:raw AND e.method=:method AND e.semantic_kind='READ'
                AND e.outcome='RESPONSE' AND e.status_code BETWEEN 200 AND 299
                AND c.revision=e.connection_revision AND c.state='ACTIVE')
            """)
        .param("raw", rawFileId)
        .param("method", method.name())
        .query(Boolean.class)
        .single();
  }

  static Set<String> yandexScopes(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(16_385);
    if (bytes.length > 16_384) {
      throw new IOException("Authorization metadata exceeds its limit");
    }
    JsonObject document =
        JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    if (!"OK".equals(CatalogSyncService.text(document, "status", true))
        || !document.has("result")
        || !document.get("result").isJsonObject()) {
      throw new IOException("Authorization metadata is incomplete");
    }
    JsonObject result = document.getAsJsonObject("result");
    if (!result.has("apiKey") || !result.get("apiKey").isJsonObject()) {
      throw new IOException("API-key scope proof is unavailable");
    }
    JsonObject key = result.getAsJsonObject("apiKey");
    if (!key.has("authScopes")
        || !key.get("authScopes").isJsonArray()
        || key.getAsJsonArray("authScopes").size() > 32) {
      throw new IOException("API-key scopes exceed the supported contract");
    }
    Set<String> scopes = new TreeSet<>();
    for (var value : key.getAsJsonArray("authScopes")) {
      if (!value.isJsonPrimitive()
          || !value.getAsJsonPrimitive().isString()
          || !value.getAsString().matches("[A-Z_]{1,80}")
          || !scopes.add(value.getAsString())) {
        throw new IOException("Invalid API-key scope");
      }
    }
    return Set.copyOf(scopes);
  }

  boolean equivalentYandexScopes(Scope scope, Set<String> scopes, boolean readOnly) {
    ScopeTransactionRunner.requireCurrent(scope);
    if (!jdbc.sql(
            """
            SELECT EXISTS(SELECT 1 FROM marketplace_connection
              WHERE account_id=:account AND state='ACTIVE' AND read_only=:readOnly)
            """)
        .param("account", scope.accountId())
        .param("readOnly", readOnly)
        .query(Boolean.class)
        .single()) {
      return false;
    }
    String previous =
        jdbc.sql(
                """
                SELECT constraints::text FROM marketplace_profile WHERE method='YANDEX_AUTH_TOKEN'
                  AND status='CONFIRMED' AND expires_at>clock_timestamp()
                  AND revision=(SELECT max(revision) FROM marketplace_profile WHERE method='YANDEX_AUTH_TOKEN')
                """)
            .query(String.class)
            .optional()
            .orElse(null);
    if (previous == null) {
      return false;
    }
    return scopeConstraints(scopes).equals(JsonParser.parseString(previous));
  }

  void confirmYandexScopes(Scope scope, UUID raw, Set<String> scopes) {
    if (!currentReadEvidence(scope, VendorMethod.YANDEX_AUTH_TOKEN, raw)) {
      return;
    }
    // A verified permission is not a tested write contract. Never set writeTested here.
    appendRead(scope, VendorMethod.YANDEX_AUTH_TOKEN, raw, scopeConstraints(scopes).toString());
  }

  private static JsonObject scopeConstraints(Set<String> scopes) {
    var constraints = new JsonObject();
    var values = new JsonArray();
    new TreeSet<>(scopes).forEach(values::add);
    constraints.add("authScopes", values);
    return constraints;
  }

  static Boolean apiAvailable(VendorMethod method, String authorizationConstraints) {
    if (authorizationConstraints == null || !method.marketplace().equals("YANDEX")) {
      return null;
    }
    JsonObject constraints = JsonParser.parseString(authorizationConstraints).getAsJsonObject();
    if (!constraints.has("authScopes")) {
      return null;
    }
    Set<String> scopes = new TreeSet<>();
    constraints.getAsJsonArray("authScopes").forEach(value -> scopes.add(value.getAsString()));
    if (scopes.contains("ALL_METHODS")
        || (!method.write() && scopes.contains("ALL_METHODS_READ_ONLY"))) {
      return true;
    }
    return switch (method) {
      case YANDEX_AUTH_TOKEN -> true;
      case YANDEX_SET_PRICE, YANDEX_SET_PROMO, YANDEX_LEAVE_PROMO -> scopes.contains("PRICING");
      case YANDEX_PRICES, YANDEX_PROMOS, YANDEX_PROMO_OFFERS ->
          scopes.contains("PRICING") || scopes.contains("PRICING_READ_ONLY");
      case YANDEX_REPORT_PAYMENTS,
          YANDEX_REPORT_RETURNS,
          YANDEX_REPORT_SERVICES,
          YANDEX_REPORT_REALIZATION ->
          scopes.contains("FINANCE_AND_ACCOUNTING");
      default -> null;
    };
  }

  void invalidateConfirmed(Scope scope, String reason) {
    ScopeTransactionRunner.requireCurrent(scope);
    int invalidated =
        jdbc.sql(
                """
                INSERT INTO marketplace_profile(id,organization_id,account_id,method,revision,status,
                  confirmed_at,expires_at,contract_uri,evidence_file_id,reason,constraints)
                SELECT gen_random_uuid(),organization_id,account_id,method,revision+1,'REVOKED',
                  confirmed_at,expires_at,contract_uri,evidence_file_id,:reason,constraints
                FROM (SELECT DISTINCT ON(method) * FROM marketplace_profile ORDER BY method,revision DESC) p
                WHERE p.status='CONFIRMED'
                """)
            .param("reason", reason)
            .update();
    if (invalidated > 0) {
      jdbc.sql(
              "UPDATE marketplace_account SET capabilities_revision=capabilities_revision+1 WHERE"
                  + " id=:id")
          .param("id", scope.requireAccount())
          .update();
    }
  }

  void requireCredential(Scope scope, long revision) {
    jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
        .param("id", scope.requireAccount())
        .query(UUID.class)
        .single();
    if (!jdbc.sql(
            """
            SELECT EXISTS(SELECT 1 FROM marketplace_connection
              WHERE account_id=:account AND revision=:revision AND state<>'DISABLED')
            """)
        .param("account", scope.accountId())
        .param("revision", revision)
        .query(Boolean.class)
        .single()) {
      throw new BusinessException("CREDENTIAL_CHANGED", 409, "Подключение уже изменилось");
    }
  }

  public Profile requireWrite(Scope scope, VendorMethod method, long expectedRevision) {
    if (!method.write()) {
      throw new IllegalArgumentException("Expected a write method");
    }
    Profile profile =
        jdbc.sql(
                """
                SELECT id,(SELECT capabilities_revision FROM marketplace_account WHERE id=:account) AS revision,
                  expires_at,constraints::text FROM marketplace_profile
                WHERE method=:method AND status='CONFIRMED' AND expires_at>clock_timestamp()
                  AND revision=(SELECT max(revision) FROM marketplace_profile WHERE method=:method)
                """)
            .param("method", method.name())
            .param("account", scope.requireAccount())
            .query(
                (row, index) ->
                    new Profile(
                        row.getObject("id", UUID.class), row.getLong("revision"),
                        row.getTimestamp("expires_at").toInstant(), row.getString("constraints")))
            .optional()
            .orElseThrow(CapabilityService::unconfirmed);
    if (profile.revision() != expectedRevision) {
      throw unconfirmed();
    }
    return profile;
  }

  public void initializeUnconfirmed(Scope scope, String marketplace) {
    for (VendorMethod method : VendorMethod.values()) {
      if (!method.marketplace().equals(marketplace)) {
        continue;
      }
      jdbc.sql(
              """
              INSERT INTO marketplace_profile(id,organization_id,account_id,method,revision,status,
                contract_uri,reason)
              VALUES (:id,:org,:account,:method,0,'UNCONFIRMED',:uri,'LIVE_CONTRACT_NOT_VERIFIED')
              ON CONFLICT(organization_id,account_id,method,revision) DO NOTHING
              """)
          .param("id", UUID.randomUUID())
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("method", method.name())
          .param("uri", method.contractUri())
          .update();
    }
  }

  private static BusinessException unconfirmed() {
    return new BusinessException(
        "CAPABILITY_UNCONFIRMED",
        422,
        "Контракт записи не подтверждён для этого кабинета или срок проверки истёк");
  }

  public record Profile(UUID id, long revision, java.time.Instant expiresAt, String constraints) {}
}
