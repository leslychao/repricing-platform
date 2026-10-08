package ru.oritas.repricer.marketplace;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.Scope;

/** Conservative admission shared by permanent account and the token's actual shared quota. */
@Service
public final class QuotaManager {
  private static final Logger log = LoggerFactory.getLogger(QuotaManager.class);
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final boolean worker;

  public QuotaManager(
      JdbcClient jdbc,
      PlatformTransactionManager manager,
      Clock clock,
      @Value("${repricer.mode:api}") String mode) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.clock = clock;
    this.worker = mode.equals("worker");
  }

  @Scheduled(fixedDelay = 3600000)
  public void cleanup() {
    if (!worker) {
      return;
    }
    try {
      jdbc.sql("SELECT repricer_cleanup_quota()").query(Integer.class).single();
    } catch (RuntimeException failure) {
      log.warn("Quota retention failed: {}", failure.getClass().getSimpleName());
    }
  }

  public List<String> subjects(String marketplace, String externalId, String credential) {
    return subjectsFromHash(marketplace, externalId, credentialSubject(marketplace, credential));
  }

  public List<String> subjectsFromHash(
      String marketplace, String externalId, String credentialHash) {
    String account = hash(marketplace + ":account:" + externalId);
    return marketplace.equals("OZON")
        ? List.of(account, credentialHash, hash("OZON:unconfirmed-shared-egress"))
        : List.of(account, credentialHash);
  }

  static String credentialSubject(String marketplace, String credential) {
    return hash(marketplace + ":token:" + credential);
  }

  List<String> verificationSubjects(String marketplace, List<String> subjects) {
    var result = new java.util.ArrayList<>(subjects);
    result.add(hash(marketplace + ":candidate-check-entry"));
    return List.copyOf(result);
  }

  public Instant reserve(
      Scope scope,
      UUID admissionId,
      Purpose purpose,
      List<String> subjects,
      VendorMethod method,
      int elements) {
    int spacing = Math.max(method.spacingMillis(), Math.multiplyExact(Math.max(elements, 1), 12));
    QuotaDecision decision =
        transactions.execute(
            status ->
                jdbc.sql(
                        """
                        SELECT repricer_reserve_quota(:id,:org,:account,:purpose,CAST(:subjects AS text[]),:method,:spacing,:resource,:cost)
                        """)
                    .param("subjects", array(subjects))
                    .param("method", method.quotaGroup())
                    .param("id", admissionId)
                    .param("org", scope.requireOrganization())
                    .param("account", scope.requireAccount())
                    .param("purpose", purpose.name())
                    .param("resource", method.name())
                    .param("cost", method.resourceCost(elements))
                    .param("spacing", spacing)
                    .query(
                        (row, index) -> {
                          Timestamp next = row.getTimestamp(1);
                          return new QuotaDecision(next == null ? null : next.toInstant());
                        })
                    .single());
    if (decision == null) {
      throw new IllegalStateException("Quota transaction did not return a decision");
    }
    return decision.next();
  }

  public void release(UUID admissionId) {
    transactions.execute(
        status ->
            jdbc.sql("SELECT repricer_release_quota(:id)")
                .param("id", admissionId)
                .query((row, index) -> true)
                .single());
  }

  void finishReport(UUID admissionId) {
    transactions.execute(
        status ->
            jdbc.sql("SELECT repricer_finish_report_quota(:id)")
                .param("id", admissionId)
                .query((row, index) -> true)
                .single());
  }

  void observeResponse(
      List<String> subjects, VendorMethod method, Instant started, HttpHeaders headers) {
    if (!method.marketplace().equals("YANDEX")) {
      return;
    }
    String limit = headers.firstValue("X-RateLimit-Resource-Limit").orElse(null);
    String remaining = headers.firstValue("X-RateLimit-Resource-Remaining").orElse(null);
    String until = headers.firstValue("X-RateLimit-Resource-Until").orElse(null);
    if (limit == null
        || remaining == null
        || until == null
        || limit.length() > 10
        || remaining.length() > 10
        || until.length() > 100) {
      return;
    }
    long limitValue;
    long remainingValue;
    Instant untilValue;
    try {
      limitValue = Long.parseLong(limit);
      remainingValue = Long.parseLong(remaining);
      untilValue = ZonedDateTime.parse(until, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    } catch (NumberFormatException | DateTimeParseException exception) {
      return;
    }
    transactions.execute(
        status ->
            jdbc.sql(
                    """
                    SELECT repricer_observe_resource(CAST(:subjects AS text[]),:group,:resource,:started,:until,:limit,:remaining)
                    """)
                .param("subjects", array(subjects))
                .param("group", method.quotaGroup())
                .param("resource", method.name())
                .param("started", Timestamp.from(started))
                .param("until", Timestamp.from(untilValue))
                .param("limit", limitValue)
                .param("remaining", remainingValue)
                .query((row, index) -> true)
                .single());
  }

  public void rateLimited(List<String> subjects, VendorMethod method, String retryAfter) {
    Instant until = clock.instant().plusSeconds(60);
    if (retryAfter != null && retryAfter.length() < 100) {
      try {
        long seconds = Long.parseLong(retryAfter);
        until = clock.instant().plusSeconds(Math.max(1, Math.min(seconds, 604800)));
      } catch (NumberFormatException exception) {
        try {
          Instant parsed =
              ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
          if (parsed.isAfter(until)) {
            until =
                parsed.isBefore(clock.instant().plusSeconds(604800))
                    ? parsed
                    : clock.instant().plusSeconds(604800);
          }
        } catch (DateTimeParseException ignored) {
          // Missing or malformed advice keeps the conservative pause.
        }
      }
    }
    Instant pauseUntil = until;
    transactions.execute(
        status ->
            jdbc.sql(
                    """
                    SELECT repricer_pause_quota(CAST(:subjects AS text[]),:method,:until)
                    """)
                .param("subjects", array(subjects))
                .param("method", method.quotaGroup())
                .param("until", Timestamp.from(pauseUntil))
                .query((row, index) -> true)
                .single());
  }

  private static String hash(String value) {
    return IdempotencyService.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String array(List<String> subjects) {
    return "{" + String.join(",", subjects) + "}";
  }

  private record QuotaDecision(Instant next) {}

  public enum Purpose {
    SYNC,
    RECONCILE,
    WRITE,
    CHECK
  }
}
