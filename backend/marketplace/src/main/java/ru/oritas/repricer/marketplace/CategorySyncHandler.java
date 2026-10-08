package ru.oritas.repricer.marketplace;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.StoredFileService;

/** Retains raw evidence before bounded parsing and atomically publishes the verified whole tree. */
@Component
public final class CategorySyncHandler implements JobHandler {
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ConnectionService connections;
  private final OutboundGateway gateway;
  private final CapabilityService capabilities;
  private final CatalogSyncService catalog;
  private final CategoryService categories;
  private final JobRuntime jobs;
  private final StoredFileService files;
  private final JsonCodec json;
  private final OutboxService outbox;
  private final Clock clock;

  public CategorySyncHandler(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      OutboundGateway gateway,
      CapabilityService capabilities,
      CatalogSyncService catalog,
      CategoryService categories,
      JobRuntime jobs,
      StoredFileService files,
      JsonCodec json,
      OutboxService outbox,
      Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.connections = connections;
    this.gateway = gateway;
    this.capabilities = capabilities;
    this.catalog = catalog;
    this.categories = categories;
    this.jobs = jobs;
    this.files = files;
    this.json = json;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Override
  public String type() {
    return "CATEGORY_SYNC";
  }

  @Override
  public String lane() {
    return "fetch";
  }

  @Override
  public Duration maximumAttemptDuration() {
    return Duration.ofMinutes(10);
  }

  @Override
  public JobOutcome execute(JobContext context) throws Exception {
    UUID runId = json.decode(context.payload(), CatalogSyncService.SyncJob.class).runId();
    var run =
        transactions.run(
            context.scope(),
            () -> {
              jobs.requireOwnership(context);
              authorization.require(context.scope(), "sync.request");
              return catalog.run(runId);
            });
    if (run.state().equals("PUBLISHED")) {
      return JobOutcome.succeeded("{}");
    }
    if (!Set.of("FETCHING", "PARSING", "VALIDATING").contains(run.state())) {
      return JobOutcome.blocked("SYNC_INCOMPLETE");
    }
    if (run.startedAt().plusSeconds(3600).isBefore(clock.instant())) {
      catalog.fail(context.scope(), runId, "TRAVERSAL_LIMIT");
      return JobOutcome.blocked("TRAVERSAL_LIMIT");
    }
    var connection = transactions.run(context.scope(), () -> connections.current(context.scope()));
    boolean ozon = connection.marketplace().equals("OZON");
    VendorMethod method = ozon ? VendorMethod.OZON_CATEGORIES : VendorMethod.YANDEX_CATEGORIES;
    var cached =
        transactions.run(
            context.scope(),
            () ->
                jdbc.sql(
                        """
                        SELECT p.file_id,p.content_encoding,COALESCE((SELECT c.connection_revision
                          FROM marketplace_external_call c WHERE c.organization_id=p.organization_id
                            AND c.account_id=p.account_id AND c.raw_file_id=p.file_id AND c.method=:method
                            AND c.outcome='RESPONSE' ORDER BY c.completed_at DESC LIMIT 1),0) AS credential_revision
                        FROM marketplace_raw_page p WHERE p.run_id=:run AND p.page_number=0
                        """)
                    .param("run", runId)
                    .param("method", method.name())
                    .query(
                        (row, index) ->
                            new OutboundGateway.Response(
                                null,
                                200,
                                files.get(row.getObject(1, UUID.class)),
                                row.getString(2),
                                null,
                                row.getLong(3)))
                    .optional());
    String requiredLane = cached.isPresent() ? "canonicalization" : "fetch";
    if (!requiredLane.equals(context.lane())) {
      return JobOutcome.handoff(requiredLane, clock.instant());
    }
    OutboundGateway.Response response;
    if (cached.isPresent()) {
      response = cached.orElseThrow();
    } else {
      response =
          gateway.executePage(
              context.scope(), runId, 0, method, "{\"language\":\"RU\"}", null, null, 1);
      if (response.retryAt() != null) {
        return JobOutcome.waiting("QUOTA_WAIT", response.retryAt());
      }
      if (!response.successful()) {
        if (response.status() >= 500 || Set.of(420, 429).contains(response.status())) {
          return JobOutcome.waiting("SUPPLIER_TEMPORARY", clock.instant().plusSeconds(60));
        }
        catalog.fail(context.scope(), runId, "SUPPLIER_REJECTED");
        return JobOutcome.blocked("SUPPLIER_REJECTED");
      }
      var received = response;
      transactions.run(
          context.scope(),
          () -> {
            jobs.requireOwnership(context);
            jdbc.sql(
                    """
                    INSERT INTO marketplace_raw_page(organization_id,account_id,run_id,page_number,
                      file_id,method,content_encoding) VALUES (:org,:account,:run,0,:file,:method,:encoding)
                    ON CONFLICT(run_id,page_number) DO NOTHING
                    """)
                .param("org", context.scope().organizationId())
                .param("account", context.scope().accountId())
                .param("run", runId)
                .param("file", received.raw().id())
                .param("method", method.name())
                .param("encoding", received.encoding())
                .update();
            files.retain(received.raw().id(), "sync-run", runId, context.scope());
            jdbc.sql(
                    """
                    UPDATE marketplace_sync_run SET state='PARSING',phase='TREE',revision=revision+1
                    WHERE id=:run
                    """)
                .param("run", runId)
                .update();
            return true;
          });
      return JobOutcome.handoff("canonicalization", clock.instant());
    }
    try {
      List<CategoryTreeReader.Node> batch = new ArrayList<>(100);
      int count;
      try (InputStream input = gateway.open(response)) {
        count =
            CategoryTreeReader.parse(
                input,
                ozon,
                node -> {
                  batch.add(node);
                  if (batch.size() == 100) {
                    saveBatch(context, runId, batch);
                    batch.clear();
                  }
                });
      }
      if (!batch.isEmpty()) {
        saveBatch(context, runId, batch);
      }
      var evidence = response;
      return transactions.run(
          context.scope(),
          () -> {
            jobs.requireOwnership(context);
            authorization.require(context.scope(), "sync.request");
            capabilities.requireCredential(context.scope(), evidence.credentialRevision());
            categories.publish(context.scope(), runId, evidence.raw().id(), run.startedAt(), count);
            jdbc.sql(
                    """
                    UPDATE marketplace_sync_run SET state='PUBLISHED',phase='PUBLISH',parsed_count=:count,
                      completed_at=clock_timestamp(),revision=revision+1 WHERE id=:run
                    """)
                .param("run", runId)
                .param("count", count)
                .update();
            jdbc.sql(
                    """
                    UPDATE marketplace_raw_page SET parsed=true,item_count=:count WHERE run_id=:run AND page_number=0
                    """)
                .param("run", runId)
                .param("count", count)
                .update();
            jdbc.sql(
                    """
                    UPDATE marketplace_source SET status='READY',last_success_at=:observed,publication_id=:run,
                      reason=NULL,revision=revision+1 WHERE source_type='CATEGORIES'
                    """)
                .param("observed", Timestamp.from(run.startedAt()))
                .param("run", runId)
                .update();
            capabilities.confirmRead(context.scope(), method, evidence.raw().id());
            outbox.emit(
                context.scope(),
                "categories:" + runId,
                "source.changed",
                new OutboxService.EntityChange("sources", runId, 1));
            return JobOutcome.succeeded("{}");
          });
    } catch (IOException error) {
      if (error instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
        throw error;
      }
      catalog.fail(context.scope(), runId, "CATEGORY_TREE_INVALID");
      return JobOutcome.blocked("CATEGORY_TREE_INVALID");
    } catch (BusinessException error) {
      catalog.fail(context.scope(), runId, error.code());
      return JobOutcome.blocked(error.code());
    }
  }

  private void saveBatch(JobContext context, UUID run, List<CategoryTreeReader.Node> batch) {
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          categories.stage(context.scope(), run, batch);
          return true;
        });
  }
}
