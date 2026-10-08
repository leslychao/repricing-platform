package ru.oritas.repricer.automation.decision;

import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.FileRetentionOwner;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

/** Releases obsolete preview files only; any possible execution keeps all of its evidence. */
@Service
public final class DecisionFileRetentionOwner implements FileRetentionOwner {
  private static final String ELIGIBLE =
      """
      d.evidence_expired_at IS NULL
      AND d.calculated_at<clock_timestamp()-interval '12 months'
      AND d.valid_until<clock_timestamp()
      AND d.state IN ('CALCULATED','STALE','CANCELLED')
      AND d.scenario_id IS NULL AND d.approved_at IS NULL AND d.approved_by IS NULL
      AND d.approval_job_id IS NULL
      AND NOT EXISTS (SELECT 1 FROM automation_command c
        WHERE c.organization_id=d.organization_id AND c.account_id=d.account_id
          AND c.decision_id=d.id)
      AND NOT EXISTS (SELECT 1 FROM automation_decision_auto a
        WHERE a.organization_id=d.organization_id AND a.account_id=d.account_id
          AND a.decision_id=d.id)
      AND EXISTS (SELECT 1 FROM automation_economics_projection p
        WHERE p.organization_id=d.organization_id AND p.account_id=d.account_id
          AND p.offer_id=d.offer_id AND (p.calculated_at,p.id)>(d.calculated_at,d.id))
      """;

  private final JdbcClient jdbc;
  private final StoredFileService files;
  private final AuditService audit;
  private final OutboxService outbox;

  public DecisionFileRetentionOwner(
      JdbcClient jdbc, StoredFileService files, AuditService audit, OutboxService outbox) {
    this.jdbc = jdbc;
    this.files = files;
    this.audit = audit;
    this.outbox = outbox;
  }

  @Override
  public String ownerType() {
    return "automation.preview";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("platform.file.manage");
  }

  @Override
  public int releaseEligible(Scope scope, int limit) {
    ScopeTransactionRunner.requireCurrent(scope);
    if (limit < 1 || limit > 50) {
      throw new IllegalArgumentException(
          "Preview retention page must contain at most 50 decisions");
    }
    if (scope.accountId() == null) {
      return 0;
    }
    var candidates =
        jdbc.sql(
                "SELECT d.id FROM automation_decision d WHERE d.organization_id=:org"
                    + " AND d.account_id=:account AND "
                    + ELIGIBLE
                    + " ORDER BY d.calculated_at,d.id LIMIT :limit FOR UPDATE OF d SKIP LOCKED")
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("limit", limit)
            .query(UUID.class)
            .list();
    record Released(long revision, UUID offerId) {}
    int released = 0;
    for (UUID id : candidates) {
      // A second statement sees child references committed while the decision lock was acquired.
      var revision =
          jdbc.sql(
                  "UPDATE automation_decision d SET evidence_expired_at=clock_timestamp(),"
                      + " state=CASE WHEN state='CALCULATED' THEN 'STALE' ELSE state END,"
                      + " revision=revision+1 WHERE d.organization_id=:org AND"
                      + " d.account_id=:account AND d.id=:id AND "
                      + ELIGIBLE
                      + " RETURNING revision,offer_id")
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("id", id)
              .query((rs, row) -> new Released(rs.getLong(1), rs.getObject(2, UUID.class)))
              .optional();
      if (revision.isEmpty()) {
        continue;
      }
      files.releaseOwner(scope, "DECISION", id, 1);
      files.releaseOwner(scope, "DECISION_SEARCH", id, 1);
      audit.recordForOffer(
          scope,
          "DECISION_EVIDENCE_EXPIRED",
          id,
          revision.orElseThrow().offerId(),
          "Unexecuted obsolete preview");
      outbox.emit(
          scope,
          "decision-evidence-expired:" + id,
          "DECISION_EVIDENCE_EXPIRED",
          new OutboxService.EntityChange("decisions", id, revision.orElseThrow().revision()));
      released++;
    }
    return released;
  }
}
