package ru.oritas.repricer.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current authorization must be checked by the owner before invoking this service. */
@Service
public final class IdempotencyService {
  private final JdbcClient jdbc;
  private final JsonCodec json;

  public IdempotencyService(JdbcClient jdbc, JsonCodec json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public <T> T execute(Scope scope, String operation, UUID requestId, Object intent,
      Class<T> resultType, Supplier<T> action) {
    if (requestId == null || !TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalArgumentException("Request key and owner transaction are required");
    }
    String scopeKey = (scope.organizationId() == null ? "user:" + scope.subjectId() : scope.organizationId().toString())
        + ":" + (scope.accountId() == null ? "organization" : scope.accountId().toString());
    String lock = scopeKey + ":" + scope.subjectId() + ":" + operation + ":" + requestId;
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key", lock).query((row, index) -> Boolean.TRUE).single();
    String hash = sha256(canonical(com.google.gson.JsonParser.parseString(json.encode(intent)))
        .toString().getBytes(StandardCharsets.UTF_8));
    var existing = jdbc.sql("""
        SELECT body_hash,result::text FROM platform_request
        WHERE scope_key=:scope AND subject_id=:subject AND operation=:operation AND request_id=:request
        """).param("scope", scopeKey).param("subject", scope.subjectId())
        .param("operation", operation).param("request", requestId)
        .query((row, index) -> new Saved(row.getString(1), row.getString(2))).optional();
    if (existing.isPresent()) {
      Saved saved = existing.orElseThrow();
      if (!saved.hash().equals(hash)) {
        throw new BusinessException("REQUEST_KEY_CONFLICT", 409, "Ключ запроса уже использован для другого действия");
      }
      return json.decode(saved.result(), resultType);
    }
    T result = action.get();
    jdbc.sql("""
        INSERT INTO platform_request(organization_id,account_id,subject_id,operation,request_id,body_hash,result)
        VALUES (:org,:account,:subject,:operation,:request,:hash,CAST(:result AS jsonb))
        """).param("org", scope.organizationId()).param("account", scope.accountId())
        .param("subject", scope.subjectId()).param("operation", operation).param("request", requestId)
        .param("hash", hash).param("result", json.encode(result)).update();
    return result;
  }

  public static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }

  private static com.google.gson.JsonElement canonical(com.google.gson.JsonElement value) {
    if (value.isJsonObject()) {
      var sorted = new com.google.gson.JsonObject();
      value.getAsJsonObject().keySet().stream().sorted().forEach(key ->
          sorted.add(key, canonical(value.getAsJsonObject().get(key))));
      return sorted;
    }
    if (value.isJsonArray()) {
      var array = new com.google.gson.JsonArray();
      value.getAsJsonArray().forEach(item -> array.add(canonical(item)));
      return array;
    }
    return value;
  }

  private record Saved(String hash, String result) {}
}
