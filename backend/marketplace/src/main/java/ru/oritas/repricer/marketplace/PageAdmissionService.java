package ru.oritas.repricer.marketplace;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** Durable space admission for unparsed pages, independent of the supplier's rate quota. */
@Service
public final class PageAdmissionService {
  private static final Set<String> AUTHORITY = Set.of("marketplace.transport.record");
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final Clock clock;
  private final OutboxService outbox;

  public PageAdmissionService(
      JdbcClient jdbc, ScopeTransactionRunner transactions, Clock clock, OutboxService outbox) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.clock = clock;
    this.outbox = outbox;
  }

  public Admission acquire(Scope scope, UUID run, int page) {
    return transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    SELECT * FROM repricer_admit_page(:id,:org,:account,:run,:page,:heavyAllowed)
                    """)
                .param("id", UUID.randomUUID())
                .param("org", scope.requireOrganization())
                .param("account", scope.requireAccount())
                .param("run", run)
                .param("page", page)
                .param("heavyAllowed", outbox.heavyAllowed())
                .query(
                    (row, index) -> new Admission(row.getObject(1, UUID.class), row.getBoolean(2)))
                .single());
  }

  void attach(Scope scope, UUID id, UUID file, VendorMethod method, String cursor, long revision) {
    int changed =
        jdbc.sql(
                """
                UPDATE marketplace_page_queue SET raw_file_id=:file,method=:method,cursor=:cursor,
                  connection_revision=:revision WHERE id=:id AND raw_file_id IS NULL
                  AND closed_at IS NULL AND created_at>clock_timestamp()-interval '5 minutes'
                """)
            .param("file", file)
            .param("method", method.name())
            .param("cursor", cursor)
            .param("revision", revision)
            .param("id", id)
            .update();
    if (changed != 1) {
      throw new BusinessException("PAGE_ADMISSION_EXPIRED", 409, "Допуск страницы истёк");
    }
  }

  void response(Scope scope, UUID id, int status, String encoding) {
    transactions.runService(
        scope,
        AUTHORITY,
        () ->
            jdbc.sql(
                    """
                    UPDATE marketplace_page_queue SET status_code=:status,content_encoding=:encoding
                    WHERE id=:id AND closed_at IS NULL
                    """)
                .param("id", id)
                .param("status", status)
                .param("encoding", encoding)
                .update());
  }

  void stored(UUID id, long bytes) {
    jdbc.sql(
            "UPDATE marketplace_page_queue SET byte_count=:bytes WHERE id=:id AND closed_at IS"
                + " NULL")
        .param("bytes", bytes)
        .param("id", id)
        .update();
  }

  void release(Scope scope, UUID id) {
    transactions.runService(
        scope,
        AUTHORITY,
        () ->
            jdbc.sql(
                    """
                    UPDATE marketplace_page_queue SET closed_at=clock_timestamp()
                    WHERE id=:id AND closed_at IS NULL
                    """)
                .param("id", id)
                .update());
  }

  Pending pending(Scope scope, UUID id) {
    return transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    SELECT raw_file_id,status_code,content_encoding,connection_revision,created_at
                    FROM marketplace_page_queue WHERE id=:id AND closed_at IS NULL
                    """)
                .param("id", id)
                .query(
                    (row, index) ->
                        new Pending(
                            row.getObject(1, UUID.class),
                            row.getObject(2, Integer.class),
                            row.getString(3),
                            row.getLong(4),
                            row.getTimestamp(5).toInstant().plusSeconds(300)))
                .single());
  }

  Instant retryAt() {
    return clock.instant().plusSeconds(5);
  }

  public record Admission(UUID id, boolean fresh) {}

  record Pending(
      UUID rawFileId,
      Integer status,
      String encoding,
      long connectionRevision,
      Instant safeAfter) {}
}
