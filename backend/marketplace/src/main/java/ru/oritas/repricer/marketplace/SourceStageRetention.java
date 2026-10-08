package ru.oritas.repricer.marketplace;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.Scope;

/** Discards only expired traversal staging; source evidence and publications remain retained. */
@Component
public final class SourceStageRetention implements JobHandler {
  private static final Logger log = LoggerFactory.getLogger(SourceStageRetention.class);
  private static final Set<String> AUTHORITY = Set.of("marketplace.stage.retention");
  private static final int MAXIMUM_ROWS = 500;
  private static final List<Stage> STAGES =
      List.of(
          new Stage("offer", "external_id"),
          new Stage("promotion", "external_id,sku"),
          new Stage("placement", "campaign_id,offer_id"),
          new Stage("stock", "external_id"),
          new Stage("history", "source_kind,external_id"),
          new Stage("category", "sequence"));
  private static final String HAS_STAGE =
      """
      (EXISTS(SELECT 1 FROM marketplace_offer_stage s WHERE s.run_id=r.id)
        OR EXISTS(SELECT 1 FROM marketplace_promotion_stage s WHERE s.run_id=r.id)
        OR EXISTS(SELECT 1 FROM marketplace_placement_stage s WHERE s.run_id=r.id)
        OR EXISTS(SELECT 1 FROM marketplace_stock_stage s WHERE s.run_id=r.id)
        OR EXISTS(SELECT 1 FROM marketplace_history_stage s WHERE s.run_id=r.id)
        OR EXISTS(SELECT 1 FROM marketplace_category_stage s
          WHERE (s.organization_id,s.account_id,s.run_id)=(r.organization_id,r.account_id,r.id)))
      """;
  private static final String ELIGIBLE =
      """
      r.state IN ('PUBLISHED','INCOMPLETE','FAILED')
        AND j.state IN ('SUCCEEDED','BLOCKED','DEAD','CANCELLED')
        AND GREATEST(r.completed_at,j.updated_at)<clock_timestamp()-interval '7 days'
      """;
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final ObjectProvider<JobRuntime> jobs;
  private final Clock clock;
  private final boolean worker;

  public SourceStageRetention(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      ObjectProvider<JobRuntime> jobs,
      Clock clock,
      @Value("${repricer.mode:api}") String mode) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.jobs = jobs;
    this.clock = clock;
    this.worker = mode.equals("worker");
  }

  @Override
  public String type() {
    return "SOURCE_STAGE_RETENTION";
  }

  @Override
  public Set<String> servicePermissions() {
    return AUTHORITY;
  }

  @Scheduled(fixedDelay = 60000)
  public void schedule() {
    if (!worker) {
      return;
    }
    try {
      var scopes =
          jdbc.sql("SELECT * FROM repricer_source_stage_retention_scopes()")
              .query(
                  (row, index) ->
                      new Scope(
                          row.getObject(1, UUID.class),
                          row.getObject(2, UUID.class),
                          row.getObject(3, UUID.class)))
              .list();
      String hour = clock.instant().truncatedTo(ChronoUnit.HOURS).toString();
      for (Scope scope : scopes) {
        transactions.runService(
            scope, AUTHORITY, () -> jobs.getObject().submit(scope, type(), hour, "{}"));
      }
    } catch (RuntimeException failure) {
      log.warn(
          "Source staging retention scheduling failed: {}", failure.getClass().getSimpleName());
    }
  }

  @Override
  public JobOutcome execute(JobContext context) {
    int changed =
        transactions.runService(
            context.scope(),
            AUTHORITY,
            () -> {
              jobs.getObject().requireOwnership(context);
              int rows = clean();
              jobs.getObject().requireOwnership(context);
              return rows;
            });
    return changed == 0
        ? JobOutcome.succeeded("{}")
        : JobOutcome.waiting("SOURCE_STAGE_RETENTION_CONTINUE", clock.instant().plusSeconds(1));
  }

  private int clean() {
    record Candidate(UUID id, boolean marked) {}
    var candidate =
        jdbc.sql(
                """
                SELECT r.id,r.stage_deletion_after IS NOT NULL AS marked
                FROM marketplace_sync_run r JOIN platform_job j
                  ON (j.organization_id,j.account_id,j.id)=(r.organization_id,r.account_id,r.job_id)
                WHERE
                """
                    + ELIGIBLE
                    + " AND "
                    + HAS_STAGE
                    + """
                     AND (r.stage_deletion_after IS NULL OR r.stage_deletion_after<=clock_timestamp())
                    ORDER BY COALESCE(r.stage_deletion_after,GREATEST(r.completed_at,j.updated_at)),r.id
                    LIMIT 1 FOR UPDATE OF j,r SKIP LOCKED
                    """)
            .query((row, index) -> new Candidate(row.getObject(1, UUID.class), row.getBoolean(2)))
            .optional();
    if (candidate.isEmpty()) {
      return 0;
    }
    Candidate run = candidate.orElseThrow();
    // Recheck after both locks: an operation may have been resumed while the first scan waited.
    boolean eligible =
        jdbc.sql(
                "SELECT EXISTS(SELECT 1 FROM marketplace_sync_run r JOIN platform_job j ON"
                    + " (j.organization_id,j.account_id,j.id)=(r.organization_id,r.account_id,r.job_id)"
                    + " WHERE r.id=:id AND "
                    + ELIGIBLE
                    + ")")
            .param("id", run.id())
            .query(Boolean.class)
            .single();
    if (!eligible) {
      return 0;
    }
    if (!run.marked()) {
      return jdbc.sql(
              "UPDATE marketplace_sync_run SET stage_deletion_after=clock_timestamp()+interval '24"
                  + " hours' WHERE id=:id AND stage_deletion_after IS NULL")
          .param("id", run.id())
          .update();
    }
    int deleted = 0;
    for (Stage stage : STAGES) {
      // Table identifiers come only from the fixed owner list above, never request data.
      String table = "marketplace_" + stage.name() + "_stage";
      deleted +=
          jdbc.sql(
                  "DELETE FROM "
                      + table
                      + " WHERE ctid IN (SELECT ctid FROM "
                      + table
                      + " WHERE run_id=:id ORDER BY "
                      + stage.primaryKeyOrder()
                      + " LIMIT :limit)")
              .param("id", run.id())
              .param("limit", MAXIMUM_ROWS - deleted)
              .update();
      if (deleted == MAXIMUM_ROWS) {
        break;
      }
    }
    return deleted;
  }

  static void requireAvailable(boolean marked) {
    if (marked) {
      throw new BusinessException(
          "SOURCE_STAGE_EXPIRED",
          410,
          "Промежуточные данные обхода истекли. Запустите новую синхронизацию");
    }
  }

  private record Stage(String name, String primaryKeyOrder) {}
}
