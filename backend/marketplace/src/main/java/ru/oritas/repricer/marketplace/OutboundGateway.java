package ru.oritas.repricer.marketplace;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;
import ru.oritas.repricer.platform.VaultSecretStore;

/** The only supplier HTTP client: admit, persist raw, then expose a bounded reading stream. */
@Service
public final class OutboundGateway implements DisposableBean {
  private static final long RAW_LIMIT = 40L * 1024 * 1024;
  private static final long JSON_LIMIT = 32L * 1024 * 1024;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ScheduledExecutorService timeouts = Executors.newSingleThreadScheduledExecutor();
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ConnectionService connections;
  private final VaultSecretStore vault;
  private final StoredFileService files;
  private final QuotaManager quotas;
  private final PageAdmissionService pages;
  private final Clock clock;
  private final UnaryOperator<URI> transportEndpoint;

  @Autowired
  public OutboundGateway(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      VaultSecretStore vault,
      StoredFileService files,
      QuotaManager quotas,
      PageAdmissionService pages,
      Clock clock) {
    this(
        jdbc,
        transactions,
        authorization,
        connections,
        vault,
        files,
        quotas,
        pages,
        clock,
        UnaryOperator.identity());
  }

  OutboundGateway(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      VaultSecretStore vault,
      StoredFileService files,
      QuotaManager quotas,
      PageAdmissionService pages,
      Clock clock,
      UnaryOperator<URI> transportEndpoint) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.connections = connections;
    this.vault = vault;
    this.files = files;
    this.quotas = quotas;
    this.pages = pages;
    this.clock = clock;
    this.transportEndpoint = transportEndpoint;
  }

  public Response execute(
      Scope scope,
      VendorMethod method,
      String body,
      String campaign,
      String cursor,
      int elements,
      UUID logicalCall)
      throws IOException, InterruptedException {
    return execute(
        scope, method, body, campaign, cursor, elements, logicalCall, Set.of(), null, null);
  }

  Response executePage(
      Scope scope,
      UUID runId,
      int page,
      VendorMethod method,
      String body,
      String campaign,
      String cursor,
      int elements)
      throws IOException, InterruptedException {
    if (method.write()) {
      throw new IllegalArgumentException("Source pages cannot change vendor state");
    }
    PageAdmissionService.Admission admission = pages.acquire(scope, runId, page);
    if (admission.id() == null) {
      return new Response(null, 0, null, "identity", pages.retryAt(), 0);
    }
    if (!admission.fresh()) {
      PageAdmissionService.Pending pending = pages.pending(scope, admission.id());
      if (pending.rawFileId() != null) {
        StoredFileService.Recovery recovery = files.recover(scope, pending.rawFileId());
        if (Set.of("STORED", "READY").contains(recovery.file().state())
            && pending.status() != null) {
          transactions.runService(
              scope,
              Set.of("marketplace.transport.record"),
              () -> {
                pages.stored(admission.id(), recovery.file().byteCount());
                files.retain(recovery.file().id(), "external-call", admission.id(), scope);
                return true;
              });
          Response response =
              new Response(
                  admission.id(),
                  pending.status(),
                  recovery.file(),
                  pending.encoding(),
                  null,
                  pending.connectionRevision());
          if (!response.successful()) {
            pages.release(scope, admission.id());
          }
          return response;
        }
        if (recovery.retryAt() != null) {
          return new Response(null, 0, null, "identity", recovery.retryAt(), 0);
        }
      }
      if (!pending.safeAfter().isAfter(clock.instant())) {
        pages.release(scope, admission.id());
      }
      return new Response(null, 0, null, "identity", pages.retryAt(), 0);
    }
    Response response =
        execute(
            scope,
            method,
            body,
            campaign,
            cursor,
            elements,
            admission.id(),
            Set.of(),
            null,
            admission.id());
    if (response.retryAt() != null || !response.successful()) {
      pages.release(scope, admission.id());
    }
    return response;
  }

  Response checkCandidate(Scope scope, UUID candidateId, VendorMethod method, String body)
      throws IOException, InterruptedException {
    if (method.write()) {
      throw new IllegalArgumentException("Credential verification cannot change vendor state");
    }
    return execute(scope, method, body, null, null, 1, null, Set.of(), candidateId, null);
  }

  public Response readReconciliation(
      Scope scope, UUID commandId, VendorMethod method, String body, String cursor, int elements)
      throws IOException, InterruptedException {
    if (method.write()) {
      throw new IllegalArgumentException("Reconciliation is read only");
    }
    Set<String> authority = Set.of("catalog.read", "marketplace.reconcile");
    transactions.runService(
        scope,
        authority,
        () ->
            jdbc.sql(
                    """
                    SELECT id FROM marketplace_external_call WHERE id=:id AND semantic_kind='WRITE'
                    """)
                .param("id", commandId)
                .query(UUID.class)
                .single());
    return execute(scope, method, body, null, cursor, elements, null, authority, null, null);
  }

  private Response execute(
      Scope scope,
      VendorMethod method,
      String body,
      String campaign,
      String cursor,
      int elements,
      UUID logicalCall,
      Set<String> authority,
      UUID candidateId,
      UUID pageAdmissionId)
      throws IOException, InterruptedException {
    if (body == null
        || body.getBytes(StandardCharsets.UTF_8).length > 65536
        || elements < 0
        || elements > 1000) {
      throw new IllegalArgumentException("Supplier request exceeds bounded method envelope");
    }
    ConnectionService.Credentials connection =
        transactions.runService(
            scope,
            authority,
            () -> {
              authorization.require(
                  scope,
                  candidateId != null
                      ? "connection.manage"
                      : method.write() ? "decision.approve" : "catalog.read");
              ConnectionService.Credentials current =
                  candidateId == null
                      ? connections.current(scope)
                      : connections.candidate(scope, candidateId);
              if (!current.marketplace().equals(method.marketplace())
                  || (method.write()
                      && (current.readOnly() || !current.state().equals("ACTIVE")))) {
                throw new BusinessException(
                    "CAPABILITY_UNAVAILABLE", 422, "Это действие недоступно для подключения");
              }
              return current;
            });
    String apiKey = vault.read(connection.path(), connection.version()).get("apiKey");
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalStateException("Credential envelope is invalid");
    }
    List<String> accountSubjects =
        quotas.subjects(connection.marketplace(), connection.externalId(), apiKey);
    List<String> subjects =
        candidateId == null
            ? accountSubjects
            : quotas.verificationSubjects(connection.marketplace(), accountSubjects);
    UUID callId = logicalCall == null ? UUID.randomUUID() : logicalCall;
    QuotaManager.Purpose purpose =
        candidateId != null
            ? QuotaManager.Purpose.CHECK
            : authority.contains("marketplace.reconcile")
                ? QuotaManager.Purpose.RECONCILE
                : QuotaManager.Purpose.SYNC;
    Instant next =
        method.write() ? null : quotas.reserve(scope, callId, purpose, subjects, method, elements);
    if (next != null) {
      return new Response(null, 0, null, "identity", next, connection.revision());
    }
    URI endpoint = method.endpoint(connection.externalId(), campaign, cursor);
    String digest =
        IdempotencyService.sha256(
            (method.name() + "\n" + endpoint + "\n" + body).getBytes(StandardCharsets.UTF_8));
    UUID rawId = files.prepare(scope, "RAW", "application/json", method.name() + ".json");
    boolean admitted =
        transactions.runService(
            scope,
            authority,
            () -> {
              authorization.require(
                  scope,
                  candidateId != null
                      ? "connection.manage"
                      : method.write() ? "decision.approve" : "catalog.read");
              ConnectionService.Credentials latest =
                  candidateId == null
                      ? connections.current(scope)
                      : connections.candidate(scope, candidateId);
              if (latest.revision() != connection.revision()) {
                throw new BusinessException(
                    "CREDENTIAL_CHANGED", 409, "Ключ подключения изменился");
              }
              if (pageAdmissionId != null) {
                pages.attach(scope, pageAdmissionId, rawId, method, cursor, connection.revision());
              }
              if (method.write()) {
                if (logicalCall == null) {
                  throw new IllegalArgumentException(
                      "A write requires its committed command admission");
                }
                return jdbc.sql(
                            """
                            UPDATE marketplace_external_call SET transport_started_at=clock_timestamp(),raw_file_id=:raw
                            WHERE id=:id AND semantic_kind='WRITE' AND outcome='ADMITTED'
                              AND transport_started_at IS NULL AND request_digest=:digest
                              AND connection_revision=:revision
                            """)
                        .param("id", callId)
                        .param("digest", digest)
                        .param("raw", rawId)
                        .param("revision", connection.revision())
                        .update()
                    == 1;
              }
              return jdbc.sql(
                          """
                          INSERT INTO marketplace_external_call(id,organization_id,account_id,method,
                            semantic_kind,outcome,request_digest,connection_revision,raw_file_id,transport_started_at)
                          VALUES (:id,:org,:account,:method,:kind,'ADMITTED',:digest,:revision,:raw,clock_timestamp())
                          ON CONFLICT(id) DO NOTHING
                          """)
                      .param("id", callId)
                      .param("org", scope.organizationId())
                      .param("account", scope.accountId())
                      .param("method", method.name())
                      .param("kind", method.write() ? "WRITE" : "READ")
                      .param("digest", digest)
                      .param("raw", rawId)
                      .param("revision", connection.revision())
                      .update()
                  == 1;
            });
    if (!admitted) {
      throw new BusinessException(
          "EXTERNAL_CALL_ALREADY_ADMITTED",
          409,
          "Повторная отправка этой операции запрещена; требуется сверка результата");
    }
    HttpRequest.Builder request =
        HttpRequest.newBuilder(transportEndpoint.apply(endpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .header("Content-Type", "application/json");
    if (method.marketplace().equals("OZON")) {
      request.header("Client-Id", connection.clientId()).header("Api-Key", apiKey);
    } else {
      request.header("Api-Key", apiKey);
    }
    request.method(
        method.httpMethod(),
        method.httpMethod().equals("GET")
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    try {
      Instant transportStarted = clock.instant();
      HttpResponse<InputStream> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream responseBody = response.body()) {
        quotas.observeResponse(subjects, method, transportStarted, response.headers());
        String encoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        if (pageAdmissionId != null) {
          pages.response(scope, pageAdmissionId, response.statusCode(), encoding);
        }
        AtomicLong progress = new AtomicLong(System.nanoTime());
        var timeout =
            timeouts.scheduleAtFixedRate(
                () -> {
                  if (System.nanoTime() - progress.get() > TimeUnit.SECONDS.toNanos(30)) {
                    try {
                      response.body().close();
                    } catch (IOException ignored) {
                      // The consuming call reports the interrupted body as an incomplete attempt.
                    }
                  }
                },
                1,
                1,
                TimeUnit.SECONDS);
        StoredFileService.FileRecord raw;
        try (InputStream input = new ProgressStream(responseBody, progress)) {
          raw =
              files.storePrepared(scope, rawId, input, RAW_LIMIT, clock.instant().plusSeconds(120));
        } finally {
          timeout.cancel(false);
        }
        transactions.runService(
            scope,
            Set.of("marketplace.transport.record"),
            () -> {
              jdbc.sql(
                      """
                      UPDATE marketplace_external_call SET outcome='RESPONSE',status_code=:status,
                        raw_file_id=:raw,completed_at=:now,content_encoding=:encoding
                      WHERE id=:id AND outcome='ADMITTED'
                      """)
                  .param("status", response.statusCode())
                  .param("raw", raw.id())
                  .param("encoding", encoding)
                  .param("now", Timestamp.from(clock.instant()))
                  .param("id", callId)
                  .update();
              files.retain(raw.id(), "external-call", callId, scope);
              if (pageAdmissionId != null) {
                pages.stored(pageAdmissionId, raw.byteCount());
              }
              return true;
            });
        if (Set.of(420, 429).contains(response.statusCode())) {
          quotas.rateLimited(
              subjects, method, response.headers().firstValue("Retry-After").orElse(null));
        }
        return new Response(
            callId, response.statusCode(), raw, encoding, null, connection.revision());
      }
    } catch (IOException | InterruptedException | RuntimeException exception) {
      transactions.runService(
          scope,
          Set.of("marketplace.transport.record"),
          () ->
              jdbc.sql(
                      """
                      UPDATE marketplace_external_call SET outcome='UNKNOWN',completed_at=:now
                      WHERE id=:id AND outcome='ADMITTED'
                      """)
                  .param("id", callId)
                  .param("now", Timestamp.from(clock.instant()))
                  .update());
      throw exception;
    } finally {
      quotas.release(callId);
    }
  }

  /** Joins the final command transaction. This method never reads Vault or performs HTTP. */
  public CommercialGateway.Admission admit(
      Scope scope, UUID commandId, VendorMethod method, String body, int elements) {
    ScopeTransactionRunner.requireCurrent(scope);
    authorization.require(scope, "decision.approve");
    ConnectionService.Credentials connection = connections.current(scope);
    if (!method.write()
        || !connection.marketplace().equals(method.marketplace())
        || connection.readOnly()
        || !connection.state().equals("ACTIVE")) {
      throw new BusinessException(
          "CAPABILITY_UNAVAILABLE", 422, "Подключение не допускает эту запись");
    }
    URI endpoint = method.endpoint(connection.externalId(), null, null);
    String digest =
        IdempotencyService.sha256(
            (method.name() + "\n" + endpoint + "\n" + body).getBytes(StandardCharsets.UTF_8));
    Instant next =
        quotas.reserve(
            scope,
            commandId,
            QuotaManager.Purpose.WRITE,
            quotas.subjectsFromHash(
                connection.marketplace(),
                connection.externalId(),
                connection.quotaCredentialHash()),
            method,
            elements);
    if (next != null) {
      return new CommercialGateway.Admission(false, next);
    }
    int inserted =
        jdbc.sql(
                """
                INSERT INTO marketplace_external_call(id,organization_id,account_id,method,
                  semantic_kind,outcome,request_digest,connection_revision)
                VALUES (:id,:org,:account,:method,'WRITE','ADMITTED',:digest,:revision)
                ON CONFLICT(id) DO NOTHING
                """)
            .param("id", commandId)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("method", method.name())
            .param("digest", digest)
            .param("revision", connection.revision())
            .update();
    if (inserted != 1) {
      throw new BusinessException(
          "EXTERNAL_CALL_ALREADY_ADMITTED",
          409,
          "Эта операция уже допущена к единственной отправке");
    }
    return new CommercialGateway.Admission(true, null);
  }

  public InputStream open(Response response) throws IOException {
    if (response.raw() == null) {
      throw new IllegalArgumentException("A persisted response is required");
    }
    InputStream raw = files.open(response.raw());
    try {
      InputStream decoded;
      if (response.encoding().equalsIgnoreCase("gzip")) {
        decoded = new GZIPInputStream(raw, 65536);
      } else if (response.encoding().equalsIgnoreCase("identity")) {
        decoded = raw;
      } else {
        throw new BusinessException(
            "UNSUPPORTED_ENCODING", 422, "Неизвестное сжатие ответа площадки");
      }
      return new LimitedStream(
          decoded, Math.min(JSON_LIMIT, Math.max(1, response.raw().byteCount()) * 100));
    } catch (IOException | RuntimeException exception) {
      try {
        raw.close();
      } catch (IOException closeFailure) {
        exception.addSuppressed(closeFailure);
      }
      throw exception;
    }
  }

  /** The URL must come from the authenticated report-info response for this report. */
  public StoredFileService.FileRecord downloadReport(
      Scope scope, UUID reportId, String url, Instant deadline)
      throws IOException, InterruptedException {
    URI endpoint;
    try {
      endpoint = URI.create(url);
    } catch (IllegalArgumentException exception) {
      throw new BusinessException("REPORT_DOWNLOAD_URL_INVALID", 422, "Некорректный адрес отчёта");
    }
    String host = endpoint.getHost();
    if (!"https".equals(endpoint.getScheme())
        || host == null
        || endpoint.getUserInfo() != null
        || endpoint.getFragment() != null
        || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
        || !(host.equals("storage.yandexcloud.net")
            || host.endsWith(".storage.yandexcloud.net")
            || host.endsWith(".yandex.net"))) {
      throw new BusinessException(
          "REPORT_DOWNLOAD_HOST_UNCONFIRMED",
          422,
          "Хранилище отчёта не входит в подтверждённый контракт площадки");
    }
    transactions.run(
        scope,
        () -> {
          authorization.require(scope, "finance.read");
          jdbc.sql(
                  "SELECT id FROM marketplace_report WHERE id=:id AND supplier_report_id IS NOT"
                      + " NULL")
              .param("id", reportId)
              .query(UUID.class)
              .single();
        });
    UUID rawId = files.prepare(scope, "RAW", "application/zip", "report.zip");
    UUID callId = UUID.randomUUID();
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_external_call(id,organization_id,account_id,method,
                      semantic_kind,outcome,request_digest,connection_revision,raw_file_id,transport_started_at)
                    VALUES (:id,:org,:account,'YANDEX_REPORT_DOWNLOAD','READ','ADMITTED',:digest,0,:raw,clock_timestamp())
                    """)
                .param("id", callId)
                .param("org", scope.organizationId())
                .param("account", scope.accountId())
                .param("digest", IdempotencyService.sha256(url.getBytes(StandardCharsets.UTF_8)))
                .param("raw", rawId)
                .update());
    try {
      HttpResponse<InputStream> response =
          http.send(
              HttpRequest.newBuilder(transportEndpoint.apply(endpoint))
                  .timeout(Duration.ofSeconds(30))
                  .header("Accept-Encoding", "identity")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      AtomicLong progress = new AtomicLong(System.nanoTime());
      var timeout =
          timeouts.scheduleAtFixedRate(
              () -> {
                if (System.nanoTime() - progress.get() > TimeUnit.SECONDS.toNanos(30)) {
                  try {
                    response.body().close();
                  } catch (IOException ignored) {
                    // The reader reports the interrupted response and no publication follows.
                  }
                }
              },
              1,
              1,
              TimeUnit.SECONDS);
      StoredFileService.FileRecord raw;
      try (InputStream input = new ProgressStream(response.body(), progress)) {
        raw = files.storePrepared(scope, rawId, input, 2L * 1024 * 1024 * 1024, deadline);
      } finally {
        timeout.cancel(false);
      }
      transactions.run(
          scope,
          () -> {
            jdbc.sql(
                    """
                    UPDATE marketplace_external_call SET outcome='RESPONSE',status_code=:status,
                      completed_at=clock_timestamp(),content_encoding='identity' WHERE id=:id
                    """)
                .param("status", response.statusCode())
                .param("id", callId)
                .update();
            files.retain(raw.id(), "supplier-report", reportId, scope);
          });
      if (response.statusCode() != 200) {
        throw new BusinessException(
            "REPORT_DOWNLOAD_REJECTED",
            422,
            "Файл отчёта не получен. Ссылка будет обновлена для того же отчёта");
      }
      return raw;
    } catch (IOException | InterruptedException | RuntimeException exception) {
      transactions.runService(
          scope,
          Set.of("marketplace.transport.record"),
          () ->
              jdbc.sql(
                      """
                      UPDATE marketplace_external_call SET outcome='UNKNOWN',completed_at=clock_timestamp()
                      WHERE id=:id AND outcome='ADMITTED'
                      """)
                  .param("id", callId)
                  .update());
      throw exception;
    }
  }

  @Override
  public void destroy() {
    timeouts.shutdownNow();
    http.close();
  }

  public record Response(
      UUID callId,
      int status,
      StoredFileService.FileRecord raw,
      String encoding,
      Instant retryAt,
      long credentialRevision) {
    public boolean successful() {
      return status >= 200 && status < 300;
    }
  }

  private static final class ProgressStream extends FilterInputStream {
    private final AtomicLong progress;

    private ProgressStream(InputStream input, AtomicLong progress) {
      super(input);
      this.progress = progress;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int count = in.read(buffer, offset, length);
      if (count > 0) {
        progress.set(System.nanoTime());
      }
      return count;
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value != -1) {
        progress.set(System.nanoTime());
      }
      return value;
    }
  }

  private static final class LimitedStream extends FilterInputStream {
    private final long limit;
    private long count;

    private LimitedStream(InputStream input, long limit) {
      super(input);
      this.limit = limit;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int read = in.read(buffer, offset, (int) Math.min(length, limit - count + 1));
      if (read > 0) {
        count += read;
        if (count > limit) {
          throw new IOException("Supplier JSON exceeds its decompressed limit");
        }
      }
      return read;
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value != -1 && ++count > limit) {
        throw new IOException("Supplier JSON exceeds its decompressed limit");
      }
      return value;
    }
  }
}
