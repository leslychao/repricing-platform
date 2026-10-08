package ru.oritas.repricer.automation.policy;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.automation.decision.DecisionContextAssembler;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.marketplace.MarketplaceReadService;

/** Captures all rows before any application, then records each bounded row outcome durably. */
@Service
public final class AssignmentBatchService {
  public record Batch(UUID id, String kind, UUID selectionId, UUID assignmentId,
      long assignmentRevision, UUID policyId, long policyVersion, String mode, String state,
      long total, long captured, UUID cursor, long applied, long failed, long waiting,
      Instant createdAt) {}

  public record Row(UUID id, UUID offerId, UUID targetId, String state, String reason, UUID operationId,
      UUID assignmentId, long assignmentRevision) {}

  private record Work(UUID id, UUID offerId, UUID targetId, PolicyService.CapturedOffer captured) {}

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate writes;
  private final PolicyService policies;
  private final DecisionService decisions;
  private final AuthorizationService authorization;
  private final JsonCodec json;
  private final JobRuntime jobs;
  private final OutboxService outbox;
  private final MarketplaceReadService marketplace;

  public AssignmentBatchService(JdbcClient jdbc, NamedParameterJdbcTemplate writes,
      PolicyService policies, DecisionService decisions,
      AuthorizationService authorization, JsonCodec json, JobRuntime jobs, OutboxService outbox,
      MarketplaceReadService marketplace) {
    this.jdbc = jdbc;
    this.writes = writes;
    this.policies = policies;
    this.decisions = decisions;
    this.authorization = authorization;
    this.json = json;
    this.jobs = jobs;
    this.outbox = outbox;
    this.marketplace = marketplace;
  }

  public UUID beginBulk(Scope scope, UUID requestId, UUID selectionId, long total,
      UUID policyId, String mode) {
    authorization.requireLocked(scope, Set.of("policy.assign", "policy.read", "finance.read"));
    if (!Set.of("PREVIEW", "MANUAL").contains(mode) || total < 1) {
      throw new BusinessException("INVALID_ASSIGNMENT_BATCH", 422, "Выберите товары и режим");
    }
    long version = policies.requireBatchPolicy(scope, policyId);
    return begin(scope, requestId, "ASSIGN", selectionId, null, 0, policyId, version, mode, total);
  }

  public UUID beginPreview(Scope scope, UUID requestId, UUID assignmentId, long revision) {
    authorization.requireLocked(scope, Set.of("decision.preview", "policy.read"));
    var assignment = policies.getAssignment(scope, assignmentId);
    if (assignment.revision() != revision) {
      throw new BusinessException("ASSIGNMENT_CHANGED", 409, "Назначение изменилось");
    }
    long version = policies.assignmentPolicyVersion(scope, assignmentId);
    if (version == 0) {
      throw new BusinessException("POLICY_UNPUBLISHED", 409, "Опубликуйте политику");
    }
    return begin(scope, requestId, "PREVIEW", null, assignmentId, revision, assignment.policyId(),
        version, "PREVIEW", 0);
  }

  private UUID begin(Scope scope, UUID requestId, String kind, UUID selectionId, UUID assignmentId,
      long assignmentRevision, UUID policyId, long policyVersion, String mode, long total) {
    UUID id = jobs.submit(scope, "ASSIGNMENT_BATCH", requestId.toString(), "{}");
    jdbc.sql("""
        INSERT INTO automation_assignment_batch(organization_id,account_id,id,kind,selection_id,
          assignment_id,assignment_revision,policy_id,policy_version,mode,total)
        VALUES (:org,:account,:id,:kind,:selection,:assignment,:revision,:policy,:version,:mode,:total)
        ON CONFLICT(organization_id,account_id,id) DO NOTHING
        """).param("org", scope.organizationId()).param("account", scope.requireAccount())
        .param("id", id).param("kind", kind).param("selection", selectionId)
        .param("assignment", assignmentId).param("revision", assignmentRevision)
        .param("policy", policyId).param("version", policyVersion).param("mode", mode)
        .param("total", total).update();
    return id;
  }

  public Batch get(Scope scope, UUID id) {
    authorization.require(scope, "policy.read");
    return read(scope, id, false);
  }

  public Batch lock(Scope scope, UUID id) {
    Batch batch = read(scope, id, true);
    authorization.requireLocked(scope, batch.kind().equals("ASSIGN")
        ? Set.of("policy.assign", "policy.read", "finance.read")
        : Set.of("decision.preview", "policy.read"));
    return batch;
  }

  private Batch read(Scope scope, UUID id, boolean lock) {
    return jdbc.sql("""
        SELECT * FROM automation_assignment_batch WHERE organization_id=:org AND account_id=:account
          AND id=:id
        """ + (lock ? " FOR UPDATE" : ""))
        .param("org", scope.organizationId()).param("account", scope.requireAccount()).param("id", id)
        .query((row, index) -> new Batch(row.getObject("id", UUID.class), row.getString("kind"),
            row.getObject("selection_id", UUID.class), row.getObject("assignment_id", UUID.class),
            row.getLong("assignment_revision"), row.getObject("policy_id", UUID.class),
            row.getLong("policy_version"), row.getString("mode"), row.getString("state"),
            row.getLong("total"), row.getLong("captured"), row.getObject("capture_cursor", UUID.class),
            row.getLong("applied"), row.getLong("failed"), row.getLong("waiting"),
            row.getTimestamp("created_at").toInstant())).optional()
        .orElseThrow(() -> new BusinessException("BATCH_NOT_FOUND", 404, "Пакетная операция недоступна"));
  }

  public List<UUID> nextPreviewOffers(Scope scope, Batch batch) {
    return policies.assignmentOffers(scope, batch.assignmentId(), batch.cursor(), 1);
  }

  public void capture(Scope scope, Batch batch, List<UUID> offerIds) {
    if (!batch.state().equals("CAPTURING") || offerIds.size() > 200) {
      throw new IllegalArgumentException("Invalid capture transition");
    }
    Map<UUID, PolicyService.CapturedOffer> captured = policies.captureOffers(scope, offerIds).stream()
        .collect(Collectors.toUnmodifiableMap(PolicyService.CapturedOffer::offerId, Function.identity()));
    boolean preview = batch.kind().equals("PREVIEW");
    Map<UUID, List<UUID>> targets = preview
        ? marketplace.priceTargets(scope, Set.copyOf(offerIds)) : Map.of();
    List<MapSqlParameterSource> rows = new ArrayList<>(offerIds.size());
    for (UUID offerId : offerIds) {
      var head = captured.get(offerId);
      boolean stale = head == null || (batch.kind().equals("PREVIEW")
          && (!batch.assignmentId().equals(head.effectiveAssignmentId())
              || batch.assignmentRevision() != head.effectiveAssignmentRevision()
              || batch.policyVersion() != head.effectivePolicyVersion()));
      List<UUID> priceTargets = targets.getOrDefault(offerId, List.of());
      if (preview && !priceTargets.isEmpty()) {
        for (UUID target : priceTargets) {
          rows.add(captureRow(scope, batch.id(), offerId, target, head,
              stale ? "STALE" : "PENDING", stale ? "CAPTURE_SOURCE_CHANGED" : null));
        }
      } else {
        rows.add(captureRow(scope, batch.id(), offerId, null, head,
            stale ? "STALE" : preview ? "FAILED" : "PENDING",
            stale ? "CAPTURE_SOURCE_CHANGED" : preview ? "COMMERCIAL_SCOPE_UNKNOWN" : null));
      }
    }
    if (!rows.isEmpty()) {
      writes.batchUpdate("""
          INSERT INTO automation_assignment_batch_row(organization_id,account_id,batch_id,id,offer_id,
            target_id,captured,state,reason)
          VALUES (:org,:account,:batch,:id,:offer,:target,CAST(:captured AS jsonb),:state,:reason)
          ON CONFLICT(organization_id,account_id,batch_id,id) DO NOTHING
          """, rows.toArray(MapSqlParameterSource[]::new));
    }
    boolean end = offerIds.size() < (preview ? 1 : 200);
    jdbc.sql("""
        UPDATE automation_assignment_batch SET capture_cursor=:cursor,captured=captured+:count,
          total=CASE WHEN kind='PREVIEW' THEN captured+:count ELSE total END,
          state=CASE WHEN :end THEN 'APPLYING' ELSE state END
        WHERE organization_id=:org AND account_id=:account AND id=:id
        """).param("cursor", offerIds.isEmpty() ? batch.cursor() : offerIds.getLast())
        .param("count", rows.size()).param("end", end)
        .param("org", scope.organizationId()).param("account", scope.accountId())
        .param("id", batch.id()).update();
    changed(scope, batch.id());
  }

  private MapSqlParameterSource captureRow(Scope scope, UUID batchId, UUID offerId, UUID targetId,
      PolicyService.CapturedOffer head, String state, String reason) {
    UUID id = targetId == null ? offerId : UUID.nameUUIDFromBytes(
        (offerId + ":" + targetId).getBytes(StandardCharsets.UTF_8));
    return new MapSqlParameterSource().addValue("org", scope.organizationId())
        .addValue("account", scope.accountId()).addValue("batch", batchId).addValue("id", id)
        .addValue("offer", offerId).addValue("target", targetId)
        .addValue("captured", head == null ? null : json.encode(head))
        .addValue("state", state).addValue("reason", reason);
  }

  /** At most fifty configuration changes or preview submissions share a transaction. */
  public boolean applyNext(Scope scope, Batch batch) {
    if (batch.state().equals("COMPLETED")) {
      return true;
    }
    if (!Set.of("APPLYING", "WAITING").contains(batch.state())
        || batch.captured() != batch.total()) {
      throw new BusinessException("BATCH_CAPTURE_INCOMPLETE", 409, "Не вся выборка зафиксирована");
    }
    var work = jdbc.sql("""
        SELECT id,offer_id,target_id,captured::text FROM automation_assignment_batch_row
        WHERE organization_id=:org AND account_id=:account AND batch_id=:batch AND state='PENDING'
        ORDER BY id LIMIT 50 FOR UPDATE
        """).param("org", scope.organizationId()).param("account", scope.accountId())
        .param("batch", batch.id()).query((row, index) -> new Work(row.getObject(1, UUID.class),
            row.getObject(2, UUID.class), row.getObject(3, UUID.class),
            json.decode(row.getString(4), PolicyService.CapturedOffer.class))).list();
    for (Work row : work) {
      try {
        if (batch.kind().equals("ASSIGN")) {
          var assignment = policies.applyCaptured(scope, row.captured(), batch.policyId(),
              batch.policyVersion(), batch.mode());
          outcome(scope, batch.id(), row.id(), "APPLIED", null, null,
              assignment.id(), assignment.revision());
        } else {
          policies.requireCaptured(scope, row.captured());
          UUID operation = decisions.submitPreview(scope, UUID.randomUUID(),
              new DecisionContextAssembler.PreviewRequest(row.offerId(), row.targetId(), null, false,
                  null, row.captured()));
          outcome(scope, batch.id(), row.id(), "PREVIEW_PENDING", null, operation, null, 0);
        }
      } catch (BusinessException rejected) {
        if (rejected.status() == 403) {
          throw rejected;
        }
        outcome(scope, batch.id(), row.id(), rejected.status() == 409 ? "STALE" : "FAILED",
            rejected.code(), null, null, 0);
      }
    }
    settlePreviews(scope, batch.id());
    jdbc.sql("""
        UPDATE automation_assignment_batch b SET applied=x.applied,failed=x.failed,waiting=x.waiting,
          state=CASE WHEN x.waiting=0 THEN 'COMPLETED' ELSE 'WAITING' END
        FROM (SELECT count(*) FILTER(WHERE state IN ('APPLIED','PREVIEW_READY')) AS applied,
          count(*) FILTER(WHERE state IN ('STALE','FAILED')) AS failed,
          count(*) FILTER(WHERE state IN ('PENDING','PREVIEW_PENDING')) AS waiting
          FROM automation_assignment_batch_row WHERE organization_id=:org AND account_id=:account
            AND batch_id=:id) x
        WHERE b.organization_id=:org AND b.account_id=:account AND b.id=:id
        """).param("org", scope.organizationId()).param("account", scope.accountId())
        .param("id", batch.id()).update();
    changed(scope, batch.id());
    return read(scope, batch.id(), false).state().equals("COMPLETED");
  }

  private void settlePreviews(Scope scope, UUID batchId) {
    var pending = jdbc.sql("""
        SELECT id,operation_id FROM automation_assignment_batch_row
        WHERE organization_id=:org AND account_id=:account AND batch_id=:id
          AND state='PREVIEW_PENDING' ORDER BY id LIMIT 200
        """).param("org", scope.organizationId()).param("account", scope.accountId())
        .param("id", batchId).query((row, index) -> Map.entry(row.getObject(1, UUID.class),
            row.getObject(2, UUID.class))).list();
    Set<UUID> ids = pending.stream().map(Map.Entry::getValue).collect(Collectors.toUnmodifiableSet());
    Map<UUID, String> statuses = jobs.statuses(scope, ids);
    for (var row : pending) {
      String state = statuses.get(row.getValue());
      if (state == null) {
        throw new BusinessException("PREVIEW_OPERATION_MISSING", 409, "Операция расчёта недоступна");
      }
      if (Set.of("SUCCEEDED", "DEAD", "BLOCKED", "CANCELLED").contains(state)) {
        outcome(scope, batchId, row.getKey(), state.equals("SUCCEEDED") ? "PREVIEW_READY" : "FAILED",
            state.equals("SUCCEEDED") ? null : "PREVIEW_" + state, row.getValue(), null, 0);
      }
    }
  }

  private void outcome(Scope scope, UUID batchId, UUID rowId, String state, String reason,
      UUID operationId, UUID assignmentId, long assignmentRevision) {
    jdbc.sql("""
        UPDATE automation_assignment_batch_row SET state=:state,reason=:reason,operation_id=:operation,
          assignment_id=:assignment,assignment_revision=:revision
        WHERE organization_id=:org AND account_id=:account AND batch_id=:batch AND id=:id
        """).param("state", state).param("reason", reason).param("operation", operationId)
        .param("assignment", assignmentId).param("revision", assignmentRevision)
        .param("org", scope.organizationId()).param("account", scope.accountId())
        .param("batch", batchId).param("id", rowId).update();
  }

  public Page<Row> rows(Scope scope, UUID id, int page, int size) {
    Batch batch = get(scope, id);
    int offset = Page.offset(page, size);
    var rows = jdbc.sql("""
        SELECT id,offer_id,target_id,state,reason,operation_id,assignment_id,assignment_revision
        FROM automation_assignment_batch_row WHERE organization_id=:org AND account_id=:account
          AND batch_id=:id ORDER BY id LIMIT :size OFFSET :offset
        """).param("org", scope.organizationId()).param("account", scope.accountId())
        .param("id", id).param("size", size).param("offset", offset)
        .query((row, index) -> new Row(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
            row.getObject(3, UUID.class), row.getString(4), row.getString(5), row.getObject(6, UUID.class),
            row.getObject(7, UUID.class), row.getLong(8))).list();
    return new Page<>(rows, batch.captured(), page, size);
  }

  private void changed(Scope scope, UUID id) {
    outbox.emit(scope, id + ":" + UUID.randomUUID(), "ASSIGNMENT_BATCH_CHANGED",
        new OutboxService.EntityChange("policy-assignments", id, 0));
  }
}
