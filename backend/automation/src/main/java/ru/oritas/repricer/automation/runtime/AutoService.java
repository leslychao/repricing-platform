package ru.oritas.repricer.automation.runtime;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.AutomationGrantService;
import ru.oritas.repricer.automation.decision.DecisionService;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;

/** Binds AUTO to the complete effective scope and coalesces source generations durably. */
@Service
public final class AutoService {
  public record Activate(
      UUID assignmentId,
      long expectedRevision,
      long expectedGrantRevision,
      long expectedPolicyVersion,
      String expectedScopeDigest) {}

  public record Review(
      UUID assignmentId,
      long assignmentRevision,
      long policyVersion,
      String scopeDigest,
      Set<UUID> targetIds,
      Set<String> operations,
      int targetsCount) {}

  public record Target(UUID offerId, UUID targetId) {}

  public record Work(UUID grantId, UUID offerId, UUID targetId, long cycle) {}

  public record Generation(long requested, long completed, Instant dueAt) {}

  public record Activation(UUID grantId, UUID issuerId, AutomationGrantService.Binding binding) {}

  public record Sweep(long cycle, UUID afterGrant, UUID afterOffer, UUID afterTarget) {}

  public record SweepStatus(UUID accountId, long cycle, Instant cycleStartedAt, long completedCycles) {}

  /** Current persisted cursor, not a claim that its worker is running or its commands succeeded. */
  public Page<SweepStatus> sweeps(Scope scope, int page, int size) {
    authorization.require(scope, "command.read");
    int offset = Page.offset(page, size);
    long total = jdbc.sql("""
            SELECT count(*) FROM automation_sweep WHERE organization_id=:org AND account_id=:account
            """)
        .param("org", scope.requireOrganization()).param("account", scope.requireAccount())
        .query(Long.class).single();
    List<SweepStatus> items = jdbc.sql("""
            SELECT account_id,cycle,cycle_started_at FROM automation_sweep
            WHERE organization_id=:org AND account_id=:account ORDER BY account_id LIMIT :size OFFSET :offset
            """)
        .param("org", scope.organizationId()).param("account", scope.accountId())
        .param("size", size).param("offset", offset)
        .query((row, index) -> new SweepStatus(row.getObject(1, UUID.class), row.getLong(2),
            row.getTimestamp(3) == null ? null : row.getTimestamp(3).toInstant(), row.getLong(2) - 1))
        .list();
    return new Page<>(items, total, page, size);
  }

  private final JdbcClient jdbc;
  private final PolicyService policies;
  private final AutomationGrantService grants;
  private final AuthorizationService authorization;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final Clock clock;
  private final OutboxService outbox;
  private final MarketplaceReadService marketplace;

  public AutoService(
      JdbcClient jdbc,
      PolicyService policies,
      AutomationGrantService grants,
      AuthorizationService authorization,
      JobRuntime jobs,
      JsonCodec json,
      Clock clock,
      OutboxService outbox,
      MarketplaceReadService marketplace) {
    this.jdbc = jdbc;
    this.policies = policies;
    this.grants = grants;
    this.authorization = authorization;
    this.jobs = jobs;
    this.json = json;
    this.clock = clock;
    this.outbox = outbox;
    this.marketplace = marketplace;
  }

  public UUID submitActivation(Scope scope, UUID requestId, Activate request) {
    authorization.requireLocked(
        scope, Set.of("automation.manage", "finance.read", "decision.approve"));
    if (request.assignmentId() == null
        || request.expectedRevision() < 1
        || request.expectedGrantRevision() < 0
        || request.expectedPolicyVersion() < 1
        || request.expectedScopeDigest() == null
        || !request.expectedScopeDigest().matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid AUTO activation");
    }
    return jobs.submit(scope, "AUTO_ACTIVATE", requestId.toString(), json.encode(request));
  }

  public Review previewActivation(Scope scope, UUID assignmentId, long expectedRevision) {
    authorization.requireLocked(
        scope, Set.of("automation.manage", "finance.read", "decision.approve"));
    lockAccount(scope);
    var assignment = policies.getAssignment(scope, assignmentId);
    if (assignment.revision() != expectedRevision
        || !assignment.enabled()
        || assignment.paused()
        || !assignment.mode().equals("AUTO")) {
      throw unavailable("ASSIGNMENT_NOT_AUTO");
    }
    var policy = policies.get(scope, assignment.policyId());
    if (!policy.status().equals("ACTIVE") || policy.settings() == null) {
      throw unavailable("POLICY_NOT_ACTIVE");
    }
    var targets = effectiveTargets(scope, assignmentId);
    validateInputs(scope, targets);
    Set<String> operations =
        policy.settings().allowedOperations().stream()
            .filter(operation -> operation != PolicySettings.Operation.JOIN_PROMO)
            .map(Enum::name)
            .collect(Collectors.toSet());
    return new Review(
        assignmentId,
        assignment.revision(),
        policy.version(),
        digest(scope, targets),
        targets.stream().map(Target::targetId).collect(Collectors.toSet()),
        operations,
        targets.size());
  }

  public Activation activate(Scope scope, Activate request, UUID operationId) {
    authorization.requireLocked(
        scope, Set.of("automation.manage", "finance.read", "decision.approve"));
    lockAccount(scope);
    var previous =
        jdbc.sql("SELECT * FROM automation_auto_activation WHERE operation_id=:operation")
            .param("operation", operationId)
            .query(this::activation)
            .optional();
    if (previous.isPresent()) {
      return previous.orElseThrow();
    }
    var assignment = policies.getAssignment(scope, request.assignmentId());
    if (assignment.revision() != request.expectedRevision()
        || !assignment.enabled()
        || assignment.paused()
        || !assignment.mode().equals("AUTO")) {
      throw unavailable("ASSIGNMENT_NOT_AUTO");
    }
    var policy = policies.get(scope, assignment.policyId());
    if (!policy.status().equals("ACTIVE") || policy.settings() == null) {
      throw unavailable("POLICY_NOT_ACTIVE");
    }
    var targets = effectiveTargets(scope, assignment.id());
    validateInputs(scope, targets);
    if (policy.version() != request.expectedPolicyVersion()
        || !digest(scope, targets).equals(request.expectedScopeDigest())) {
      throw unavailable("AUTO_REVIEW_CHANGED");
    }
    Set<String> operations =
        policy.settings().allowedOperations().stream()
            .filter(operation -> operation != PolicySettings.Operation.JOIN_PROMO)
            .map(Enum::name)
            .collect(Collectors.toSet());
    Set<UUID> ids = targets.stream().map(Target::targetId).collect(Collectors.toSet());
    var grant =
        grants.grant(
            scope,
            assignment.id(),
            assignment.revision(),
            policy.version(),
            digest(scope, targets),
            operations,
            ids,
            request.expectedGrantRevision());
    jdbc.sql(
            """
            INSERT INTO automation_auto_activation(organization_id,account_id,grant_id,assignment_id,
              issuer_id,binding,operation_id,grant_revision)
            VALUES (:org,:account,:grant,:assignment,:issuer,CAST(:binding AS jsonb),:operation,:revision)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("grant", grant.id())
        .param("assignment", assignment.id())
        .param("issuer", grant.issuerId())
        .param("binding", json.encode(grant.binding()))
        .param("operation", operationId)
        .param("revision", grant.revision())
        .update();
    var activation = new Activation(grant.id(), grant.issuerId(), grant.binding());
    for (Target target : targets) {
      trigger(scope, activation, target);
    }
    if (jdbc.sql(
                """
                INSERT INTO automation_sweep(organization_id,account_id,cycle) VALUES (:org,:account,1)
                ON CONFLICT DO NOTHING
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .update()
        == 1) {
      scheduleSweep(scope, new Sweep(1, null, null, null), clock.instant().plusSeconds(300));
      sweepChanged(scope, 1);
    }
    outbox.emit(
        scope,
        "AUTO_ACTIVATED:" + grant.id(),
        "AUTO_ACTIVATED",
        new OutboxService.EntityChange(
            "policy-assignments", assignment.id(), assignment.revision()));
    return activation;
  }

  public Activation requireCurrent(Scope scope, UUID grantId) {
    Activation activation = read(scope, grantId);
    grants.requireCurrent(scope, grantId, activation.binding());
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " SHARE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    var assignment = policies.getAssignment(scope, activation.binding().assignmentId());
    var policy = policies.get(scope, assignment.policyId());
    var targets = effectiveTargets(scope, assignment.id());
    if (!assignment.enabled()
        || assignment.paused()
        || !assignment.mode().equals("AUTO")
        || assignment.revision() != activation.binding().assignmentRevision()
        || !policy.status().equals("ACTIVE")
        || policy.version() != activation.binding().policyVersion()
        || !activation.binding().scopeDigest().equals(digest(scope, targets))) {
      throw unavailable("AUTO_SCOPE_CHANGED");
    }
    return activation;
  }

  public void bindDecision(
      Scope scope, Work work, long generation, UUID decisionId, DecisionService.Snapshot snapshot) {
    Activation activation = requireCurrent(scope, work.grantId());
    if (!snapshot.basis().assignmentId().equals(activation.binding().assignmentId())
        || snapshot.basis().assignmentRevision() != activation.binding().assignmentRevision()
        || snapshot.basis().policyVersion() != activation.binding().policyVersion()
        || snapshot.result().requiresManualConfirmation()
        || !activation.binding().targetIds().containsAll(snapshot.context().originalTargets())) {
      throw unavailable("AUTO_DECISION_NOT_COVERED");
    }
    var candidate =
        snapshot.candidates().stream()
            .filter(value -> value.id().equals(snapshot.result().selectedCandidate()))
            .findFirst()
            .orElseThrow(() -> unavailable("AUTO_DECISION_NOT_EXECUTABLE"));
    if (candidate.path().stream()
        .anyMatch(step -> !activation.binding().operations().contains(step.operation().name()))) {
      throw unavailable("AUTO_OPERATION_NOT_COVERED");
    }
    jdbc.sql(
            """
            INSERT INTO automation_decision_auto(organization_id,account_id,decision_id,grant_id,
              offer_id,target_id,generation)
            VALUES (:org,:account,:decision,:grant,:offer,:target,:generation) ON CONFLICT DO NOTHING
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("decision", decisionId)
        .param("grant", work.grantId())
        .param("offer", work.offerId())
        .param("target", work.targetId())
        .param("generation", generation)
        .update();
  }

  public void requireDecisionAuthority(Scope scope, UUID decisionId) {
    record Bound(UUID grantId, long captured, long current) {}
    var binding =
        jdbc.sql(
                """
                SELECT d.grant_id,d.generation,g.requested_generation FROM automation_decision_auto d
                  JOIN automation_generation g ON (g.organization_id,g.account_id,g.grant_id,g.offer_id,g.target_id)=
                    (d.organization_id,d.account_id,d.grant_id,d.offer_id,d.target_id)
                WHERE d.organization_id=:org AND d.account_id=:account AND d.decision_id=:decision
                FOR SHARE OF g
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("decision", decisionId)
            .query(
                (rs, row) -> new Bound(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3)))
            .optional();
    if (binding.isPresent()) {
      Bound bound = binding.orElseThrow();
      if (bound.captured() != bound.current()) {
        throw unavailable("AUTO_GENERATION_CHANGED");
      }
      requireCurrent(scope, bound.grantId());
    }
  }

  public List<Target> effectiveTargets(Scope scope, UUID assignmentId) {
    record Source(UUID offerId, UUID targetId, boolean categoryKnown, boolean complete) {}
    var categories = marketplace.categoryPathsProjection(scope);
    var sources =
        jdbc.sql(
                """
                SELECT o.id,t.value::uuid AS target_id,
                  (o.category_path IS NOT NULL OR selected.scope_kind='OFFER') AS category_known,
                  COALESCE((c.snapshot->>'complete')::boolean,false) AS complete
                FROM (%s) o CROSS JOIN LATERAL (
                  SELECT a.id,a.scope_kind FROM automation_assignment a
                  WHERE a.organization_id=o.organization_id AND a.account_id=o.account_id AND NOT a.detached
                    AND ((a.scope_kind='ACCOUNT' AND a.target_id=o.account_id)
                      OR (a.scope_kind='OFFER' AND a.target_id=o.id)
                      OR (a.scope_kind='CATEGORY' AND a.target_id=ANY(o.category_path)))
                  ORDER BY CASE a.scope_kind WHEN 'OFFER' THEN 0 WHEN 'CATEGORY' THEN 1 ELSE 2 END,
                    array_position(o.category_path,a.target_id) NULLS LAST LIMIT 1
                ) selected LEFT JOIN marketplace_commercial_state c
                  ON (c.organization_id,c.account_id,c.offer_id)=(o.organization_id,o.account_id,o.id)
                    AND c.current
                  LEFT JOIN LATERAL jsonb_array_elements_text(c.snapshot->'targetIds') t ON true
                WHERE o.organization_id=:org AND o.account_id=:account AND selected.id=:assignment
                ORDER BY o.id,t.value LIMIT 1001
                """
                    .formatted(categories.sql()))
            .params(categories.parameters())
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("assignment", assignmentId)
            .query(
                (rs, row) ->
                    new Source(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getBoolean(3),
                        rs.getBoolean(4)))
            .list();
    if (sources.isEmpty()
        || sources.size() > 1000
        || sources.stream()
            .anyMatch(
                source ->
                    source.targetId() == null || !source.categoryKnown() || !source.complete())) {
      throw unavailable("AUTO_FULL_SCOPE_REQUIRED");
    }
    return sources.stream().map(source -> new Target(source.offerId(), source.targetId())).toList();
  }

  public record SourceRequest(UUID eventId, UUID afterGrant, UUID afterOffer, UUID afterTarget) {}

  /** One durable account sweep owns its cursor; a later cycle starts only after its last page. */
  public void sweep(Scope scope, UUID jobId, Sweep request) {
    List<Pending> pending =
        sourcePage(scope, request.afterGrant(), request.afterOffer(), request.afterTarget());
    Set<UUID> activeGrants = new java.util.HashSet<>();
    for (UUID grant :
        pending.stream().map(value -> value.activation().grantId()).distinct().sorted().toList()) {
      try {
        requireCurrent(scope, grant);
        activeGrants.add(grant);
      } catch (BusinessException changed) {
        // Revoked or changed authority is skipped; a later sweep still checks other grants.
      }
    }
    record Cycle(long number, Instant startedAt) {}
    Cycle current =
        jdbc.sql(
                """
                SELECT cycle,cycle_started_at FROM automation_sweep
                WHERE organization_id=:org AND account_id=:account FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .query(
                (row, index) ->
                    new Cycle(
                        row.getLong(1),
                        row.getTimestamp(2) == null ? null : row.getTimestamp(2).toInstant()))
            .single();
    long cycle = current.number();
    if (cycle != request.cycle() || !claimSourceBatch(scope, jobId)) {
      return;
    }
    Instant cycleStart = current.startedAt();
    if (cycleStart == null) {
      cycleStart = clock.instant();
      jdbc.sql(
              """
              UPDATE automation_sweep SET cycle_started_at=:started
              WHERE organization_id=:org AND account_id=:account
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("started", Timestamp.from(cycleStart))
          .update();
      sweepChanged(scope, cycle * 2);
    }
    for (Pending item : pending) {
      if (activeGrants.contains(item.activation().grantId())) {
        trigger(scope, item.activation(), item.target());
      }
    }
    if (pending.size() == 100) {
      Pending last = pending.getLast();
      scheduleSweep(
          scope,
          new Sweep(
              cycle,
              last.activation().grantId(),
              last.target().offerId(),
              last.target().targetId()),
          clock.instant());
    } else {
      jdbc.sql(
              """
              UPDATE automation_sweep SET cycle=cycle+1,cycle_started_at=NULL
              WHERE organization_id=:org AND account_id=:account
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .update();
      sweepChanged(scope, cycle * 2 + 1);
      scheduleSweep(
          scope,
          new Sweep(cycle + 1, null, null, null),
          new RuntimeRules().nextSweepAt(cycleStart, clock.instant()));
    }
  }

  private void scheduleSweep(Scope scope, Sweep request, Instant due) {
    jobs.schedule(
        scope,
        "AUTO_SWEEP",
        scope.requireAccount()
            + ":"
            + request.cycle()
            + ":"
            + request.afterGrant()
            + ":"
            + request.afterOffer()
            + ":"
            + request.afterTarget(),
        json.encode(request),
        due);
  }

  private void sweepChanged(Scope scope, long revision) {
    outbox.emit(scope, "sweep:" + scope.requireAccount() + ":" + revision,
        "AUTOMATION_SWEEP_CHANGED",
        new OutboxService.EntityChange("automation-sweeps", scope.accountId(), revision));
  }

  public void sourceChanged(Scope scope, UUID eventId) {
    if (scope.accountId() == null) {
      return;
    }
    jobs.submit(
        scope,
        "AUTO_SOURCE",
        eventId.toString(),
        json.encode(new SourceRequest(eventId, null, null, null)));
  }

  public void sourceBatch(Scope scope, UUID jobId, SourceRequest request) {
    if (!claimSourceBatch(scope, jobId)) {
      return;
    }
    List<Pending> pending =
        sourcePage(scope, request.afterGrant(), request.afterOffer(), request.afterTarget());
    for (Pending item : pending) {
      trigger(scope, item.activation(), item.target());
    }
    if (pending.size() == 100) {
      Pending last = pending.getLast();
      SourceRequest next =
          new SourceRequest(
              request.eventId(),
              last.activation().grantId(),
              last.target().offerId(),
              last.target().targetId());
      jobs.submit(
          scope,
          "AUTO_SOURCE",
          request.eventId()
              + ":"
              + next.afterGrant()
              + ":"
              + next.afterOffer()
              + ":"
              + next.afterTarget(),
          json.encode(next));
    }
  }

  private record Pending(Activation activation, Target target) {}

  private boolean claimSourceBatch(Scope scope, UUID jobId) {
    return jdbc.sql(
                """
                INSERT INTO automation_source_batch(organization_id,account_id,job_id)
                VALUES (:org,:account,:job) ON CONFLICT DO NOTHING
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("job", jobId)
            .update()
        == 1;
  }

  private List<Pending> sourcePage(
      Scope scope, UUID afterGrant, UUID afterOffer, UUID afterTarget) {
    return jdbc.sql(
            """
            WITH latest AS (
              SELECT DISTINCT ON(assignment_id) * FROM automation_auto_activation
              WHERE organization_id=:org AND account_id=:account
              ORDER BY assignment_id,grant_revision DESC
            ) SELECT a.*,g.offer_id,g.target_id FROM latest a
              JOIN automation_generation g ON (g.organization_id,g.account_id,g.grant_id)=
                (a.organization_id,a.account_id,a.grant_id)
            WHERE CAST(:afterGrant AS uuid) IS NULL
              OR (g.grant_id,g.offer_id,g.target_id)>(:afterGrant,:afterOffer,:afterTarget)
            ORDER BY g.grant_id,g.offer_id,g.target_id LIMIT 100
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("afterGrant", afterGrant)
        .param("afterOffer", afterOffer)
        .param("afterTarget", afterTarget)
        .query(
            (rs, row) ->
                new Pending(
                    activation(rs, row),
                    new Target(
                        rs.getObject("offer_id", UUID.class),
                        rs.getObject("target_id", UUID.class))))
        .list();
  }

  private void trigger(Scope scope, Activation activation, Target target) {
    Instant now = clock.instant();
    record Trigger(long cycle, boolean started, Instant due) {}
    Trigger trigger =
        jdbc.sql(
                """
                INSERT INTO automation_generation(organization_id,account_id,grant_id,offer_id,target_id,
                  requested_generation,completed_generation,cycle,first_event_at,due_at)
                VALUES (:org,:account,:grant,:offer,:target,1,0,1,:now,:due)
                ON CONFLICT(organization_id,account_id,grant_id,offer_id,target_id) DO UPDATE SET
                  requested_generation=automation_generation.requested_generation+1,
                  cycle=CASE WHEN automation_generation.completed_generation=automation_generation.requested_generation
                    THEN automation_generation.requested_generation+1 ELSE automation_generation.cycle END,
                  first_event_at=CASE WHEN automation_generation.completed_generation=automation_generation.requested_generation
                    THEN :now ELSE automation_generation.first_event_at END,
                  due_at=CASE WHEN automation_generation.completed_generation=automation_generation.requested_generation
                    THEN :due ELSE LEAST(automation_generation.first_event_at+interval '30 seconds',:due) END
                RETURNING cycle,requested_generation=cycle,due_at
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("grant", activation.grantId())
            .param("offer", target.offerId())
            .param("target", target.targetId())
            .param("now", Timestamp.from(now))
            .param("due", Timestamp.from(now.plusSeconds(2)))
            .query(
                (rs, row) ->
                    new Trigger(rs.getLong(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant()))
            .single();
    if (trigger.started()) {
      Scope issuer = new Scope(scope.organizationId(), scope.accountId(), activation.issuerId());
      Work work =
          new Work(activation.grantId(), target.offerId(), target.targetId(), trigger.cycle());
      jobs.schedule(
          issuer,
          "AUTO_CALCULATE",
          activation.grantId() + ":" + target.targetId() + ":" + trigger.cycle(),
          json.encode(work),
          trigger.due());
    }
  }

  public Generation generation(Scope scope, Work work) {
    return jdbc.sql(
            """
            SELECT requested_generation,completed_generation,due_at FROM automation_generation
            WHERE organization_id=:org AND account_id=:account AND grant_id=:grant
              AND offer_id=:offer AND target_id=:target AND cycle=:cycle FOR UPDATE
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("grant", work.grantId())
        .param("offer", work.offerId())
        .param("target", work.targetId())
        .param("cycle", work.cycle())
        .query(
            (rs, row) ->
                new Generation(rs.getLong(1), rs.getLong(2), rs.getTimestamp(3).toInstant()))
        .single();
  }

  public boolean complete(Scope scope, Work work, long generation) {
    return jdbc.sql(
            """
            UPDATE automation_generation SET completed_generation=GREATEST(completed_generation,:generation)
            WHERE organization_id=:org AND account_id=:account AND grant_id=:grant
              AND offer_id=:offer AND target_id=:target AND cycle=:cycle
            RETURNING requested_generation=completed_generation
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("grant", work.grantId())
        .param("offer", work.offerId())
        .param("target", work.targetId())
        .param("cycle", work.cycle())
        .param("generation", generation)
        .query(Boolean.class)
        .single();
  }

  private void validateInputs(Scope scope, List<Target> targets) {
    var offers = targets.stream().map(Target::offerId).distinct().toList();
    long count =
        jdbc.sql(
                """
                SELECT count(*) FROM marketplace_offer o JOIN economics_cost_head cost
                  ON (cost.organization_id,cost.account_id,cost.offer_id)=(o.organization_id,o.account_id,o.id)
                JOIN marketplace_commercial_state c
                  ON (c.organization_id,c.account_id,c.offer_id)=(o.organization_id,o.account_id,o.id) AND c.current
                WHERE o.organization_id=:org AND o.account_id=:account AND o.id IN (:offers)
                  AND o.seller_price>0 AND cost.revision>0
                  AND (c.snapshot->>'validUntil')::timestamptz>:now
                  AND EXISTS(SELECT 1 FROM marketplace_economic_terms t
                    WHERE (t.organization_id,t.account_id,t.offer_id)=(o.organization_id,o.account_id,o.id) AND t.current)
                  AND EXISTS(SELECT 1 FROM economics_tax_head t WHERE t.organization_id=:org AND t.account_id=:account AND t.revision>0)
                  AND EXISTS(SELECT 1 FROM economics_safety_envelope s WHERE s.organization_id=:org AND s.account_id=:account AND s.current_revision)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("offers", offers)
            .param("now", Timestamp.from(clock.instant()))
            .query(Long.class)
            .single();
    if (count != offers.size()) {
      throw unavailable("AUTO_INPUTS_REQUIRED");
    }
  }

  private Activation read(Scope scope, UUID grantId) {
    return jdbc.sql(
            """
            SELECT * FROM automation_auto_activation WHERE organization_id=:org AND account_id=:account AND grant_id=:grant
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("grant", grantId)
        .query(this::activation)
        .optional()
        .orElseThrow(() -> unavailable("AUTO_AUTHORITY_REQUIRED"));
  }

  private Activation activation(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Activation(
        rs.getObject("grant_id", UUID.class),
        rs.getObject("issuer_id", UUID.class),
        json.decode(rs.getString("binding"), AutomationGrantService.Binding.class));
  }

  private static String digest(Scope scope, List<Target> targets) {
    String values =
        targets.stream()
            .map(target -> target.offerId() + ":" + target.targetId())
            .sorted()
            .collect(Collectors.joining(";"));
    return IdempotencyService.sha256(
        (scope.accountId() + ":" + values).getBytes(StandardCharsets.UTF_8));
  }

  private void lockAccount(Scope scope) {
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
  }

  private static BusinessException unavailable(String code) {
    return new BusinessException(code, 409, "Автоматическое исполнение требует проверки: " + code);
  }
}
