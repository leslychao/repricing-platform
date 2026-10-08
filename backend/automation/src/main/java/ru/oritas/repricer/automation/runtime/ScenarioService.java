package ru.oritas.repricer.automation.runtime;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.automation.policy.PolicyService;
import ru.oritas.repricer.automation.policy.PolicySettings;
import ru.oritas.repricer.automation.runtime.RuntimeRules.ScenarioState;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.economics.RiskLimitService;
import ru.oritas.repricer.marketplace.CommercialGateway;
import ru.oritas.repricer.marketplace.CommercialState;
import ru.oritas.repricer.marketplace.MarketplaceHistoryService;
import ru.oritas.repricer.marketplace.MarketplaceHistoryService.OriginalDemand;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

/**
 * A frozen temporary episode survives publication, restarts and pauses without extending itself.
 */
@Service
public class ScenarioService {
  public record Input(
      UUID assignmentId,
      long expectedAssignmentRevision,
      List<UUID> offerIds,
      Instant startsAt,
      Instant endsAt,
      BigDecimal maximumAcceptedQuantity,
      BigDecimal minimumRemainingStock,
      String expectedCompletionReviewDigest) {
    public Input {
      offerIds = List.copyOf(offerIds);
      if (assignmentId == null
          || expectedAssignmentRevision < 1
          || offerIds.isEmpty()
          || offerIds.size() > 1000
          || offerIds.stream().distinct().count() != offerIds.size()
          || startsAt == null
          || endsAt == null
          || !endsAt.isAfter(startsAt)
          || (maximumAcceptedQuantity != null
              && (maximumAcceptedQuantity.signum() <= 0
                  || maximumAcceptedQuantity.stripTrailingZeros().scale() > 0))
          || (minimumRemainingStock != null && minimumRemainingStock.signum() < 0)) {
        throw new IllegalArgumentException("Invalid frozen episode scope and stop conditions");
      }
    }
  }

  public record Run(
      UUID id,
      UUID assignmentId,
      long assignmentRevision,
      UUID policyId,
      long policyVersion,
      ScenarioState state,
      Instant startsAt,
      Instant endsAt,
      BigDecimal maximumAcceptedQuantity,
      BigDecimal minimumRemainingStock,
      BigDecimal acceptedQuantity,
      String scopeDigest,
      PolicySettings settings,
      String reason,
      long revision,
      ScenarioCompletion.Review completionReview) {}

  public record RunJob(UUID runId) {}

  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final Clock clock;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final OutboxService outbox;
  private final PolicyService policies;
  private final JobRuntime jobs;
  private final RiskLimitService risks;
  private final MarketplaceHistoryService history;
  private final MarketplaceReadService marketplace;
  private final StoredFileService files;
  private final ResourceService resources;

  public ScenarioService(
      JdbcClient jdbc,
      JsonCodec json,
      Clock clock,
      AuthorizationService authorization,
      AuditService audit,
      OutboxService outbox,
      PolicyService policies,
      JobRuntime jobs,
      RiskLimitService risks,
      MarketplaceHistoryService history,
      MarketplaceReadService marketplace,
      StoredFileService files,
      ResourceService resources) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.authorization = authorization;
    this.audit = audit;
    this.outbox = outbox;
    this.policies = policies;
    this.jobs = jobs;
    this.risks = risks;
    this.history = history;
    this.marketplace = marketplace;
    this.files = files;
    this.resources = resources;
  }

  @Transactional
  public RiskLimitService.Permission grantPermission(
      Scope scope,
      UUID runId,
      long expectedRevision,
      BigDecimal profitFloor,
      BigDecimal lossBudget) {
    authorization.requireLocked(
        scope, Set.of("automation.manage", "finance.write", "resource.manage"));
    lockAccount(scope);
    Run run =
        read(scope, runId, true)
            .orElseThrow(
                () -> new BusinessException("SCENARIO_NOT_FOUND", 404, "Эпизод недоступен"));
    if (run.revision() != expectedRevision
        || run.state() == ScenarioState.FINISHING
        || run.state() == ScenarioState.FINISHED
        || !run.endsAt().isAfter(clock.instant())) {
      throw new BusinessException("SCENARIO_CHANGED", 409, "Эпизод изменён или завершается");
    }
    if (risks.forRun(scope, runId).isPresent()) {
      throw new BusinessException(
          "SCENARIO_PERMISSION_EXISTS",
          409,
          "Экономическое разрешение этого эпизода уже зафиксировано");
    }
    List<CommercialState> states = currentStates(scope, runId);
    long expectedOffers =
        jdbc.sql(
                """
                SELECT count(DISTINCT offer_id) FROM automation_scenario_scope
                WHERE organization_id=:org AND account_id=:account AND run_id=:run
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("run", runId)
            .query(Long.class)
            .single();
    if (states.isEmpty()
        || states.size() != expectedOffers
        || states.stream()
            .anyMatch(state -> !state.complete() || !state.validUntil().isAfter(clock.instant()))) {
      throw new BusinessException(
          "SCENARIO_SCOPE_UNKNOWN", 409, "Для разрешения нужен полный актуальный охват эпизода");
    }
    EconomicBoundary.Coverage boundary =
        profitFloor.signum() < 0 ? EconomicBoundary.require(states, clock.instant()) : null;
    BigDecimal quantity = boundary == null ? null : boundary.quantity();
    Set<CommercialGateway.Operation> operations =
        run.settings().allowedOperations().stream()
            .map(
                operation ->
                    switch (operation) {
                      case SET_BASE_PRICE -> CommercialGateway.Operation.SET_PRICE;
                      case SET_PROMO_PRICE -> CommercialGateway.Operation.SET_PROMO_PRICE;
                      case JOIN_PROMO -> CommercialGateway.Operation.JOIN_PROMO;
                      case LEAVE_PROMO -> CommercialGateway.Operation.LEAVE_PROMO;
                    })
            .collect(java.util.stream.Collectors.toSet());
    operations.removeIf(
        operation ->
            states.stream().anyMatch(state -> !state.supportedOperations().contains(operation)));
    String evidence = boundary == null ? null : boundary.digest();
    var permission =
        risks.grant(
            scope,
            new RiskLimitService.Permission(
                UUID.randomUUID(),
                run.id(),
                1,
                run.startsAt(),
                run.endsAt(),
                profitFloor,
                lossBudget,
                quantity,
                evidence,
                run.scopeDigest(),
                "ACTIVE",
                operations));
    if (boundary != null) {
      for (var proof : boundary.boundaries()) {
        files.retain(proof.rawFileId(), "ECONOMIC_PERMISSION_PROOF", permission.id(), scope);
      }
    }
    // Consent changes the calculation basis, including previews created before it existed.
    transition(scope, run, run.state(), "ECONOMIC_PERMISSION_GRANTED");
    return permission;
  }

  public CommercialState.LossBoundary requireEconomicBoundary(
      Scope scope, UUID runId, String expectedDigest, UUID targetId) {
    var coverage = EconomicBoundary.require(currentStates(scope, runId), clock.instant());
    if (!coverage.digest().equals(expectedDigest)) {
      throw new BusinessException(
          "ECONOMIC_BOUNDARY_CHANGED",
          409,
          "Подтверждённая внешняя область ответственности изменилась");
    }
    return coverage.boundaries().stream()
        .filter(boundary -> boundary.targetIds().contains(targetId))
        .findFirst()
        .orElseThrow(
            () ->
                new BusinessException(
                    "ECONOMIC_BOUNDARY_SCOPE",
                    409,
                    "Коммерческая цель не входит в подтверждённую область ответственности"));
  }

  private List<CommercialState> currentStates(Scope scope, UUID runId) {
    Set<UUID> offers =
        jdbc.sql(
                """
                SELECT DISTINCT offer_id FROM automation_scenario_scope
                WHERE organization_id=:org AND account_id=:account AND run_id=:run
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("run", runId)
            .query(UUID.class)
            .set();
    return marketplace.scenarioSources(scope, offers).stream()
        .map(MarketplaceReadService.ScenarioSource::state)
        .toList();
  }

  @Transactional
  public Run revokePermission(
      Scope scope, UUID runId, long expectedRunRevision, long expectedPermissionRevision) {
    authorization.requireLocked(
        scope, Set.of("automation.manage", "finance.write", "resource.manage"));
    lockAccount(scope);
    Run run =
        read(scope, runId, true)
            .orElseThrow(
                () -> new BusinessException("SCENARIO_NOT_FOUND", 404, "Эпизод недоступен"));
    if (run.revision() != expectedRunRevision) {
      throw new BusinessException("STALE_REVISION", 409, "Эпизод изменён");
    }
    var permission =
        risks
            .forRun(scope, runId)
            .orElseThrow(
                () -> new BusinessException("PERMISSION_NOT_FOUND", 404, "Разрешение недоступно"));
    risks.revoke(scope, permission.id(), expectedPermissionRevision);
    advance(scope, run, true, "ECONOMIC_PERMISSION_REVOKED");
    return read(scope, runId, false).orElseThrow();
  }

  @Transactional
  public UUID submit(Scope scope, UUID requestId, Input input) {
    authorization.require(scope, "automation.manage");
    authorization.require(scope, "finance.read");
    if (input.expectedCompletionReviewDigest() == null) {
      throw new BusinessException(
          "SCENARIO_REVIEW_REQUIRED", 409, "Подтвердите предварительный охват завершения");
    }
    return jobs.submit(scope, "SCENARIO_CREATE", requestId.toString(), json.encode(input));
  }

  public ScenarioCompletion.Review review(Scope scope, Input input) {
    authorization.requireLocked(scope, Set.of("automation.manage", "finance.read"));
    var policy =
        policies.scenarioPolicy(
            scope, input.assignmentId(), input.expectedAssignmentRevision(), input.offerIds());
    var sources = marketplace.scenarioSources(scope, Set.copyOf(input.offerIds()));
    requireSourceScope(sources, input.offerIds().size());
    return ScenarioCompletion.review(
        policy.settings(),
        sources.stream().map(MarketplaceReadService.ScenarioSource::state).toList(),
        input.startsAt(),
        input.endsAt(),
        clock.instant());
  }

  private void requireSourceScope(
      List<MarketplaceReadService.ScenarioSource> sources, int expectedCount) {
    if (sources.size() != expectedCount
        || sources.stream()
            .anyMatch(
                source ->
                    !source.state().complete()
                        || source.state().validUntil() == null
                        || !source.state().validUntil().isAfter(clock.instant())
                        || source.basePrice() == null
                        || source.state().targetIds().isEmpty()
                        || source.state().currentParticipations().stream()
                            .anyMatch(p -> p.processing()))) {
      throw new BusinessException(
          "SCENARIO_SCOPE_UNKNOWN", 409, "Нужен полный актуальный охват полей эпизода");
    }
  }

  @Transactional
  public Run create(Scope scope, UUID runId, Input input) {
    authorization.requireLocked(scope, Set.of("automation.manage", "finance.read"));
    lockAccount(scope);
    var previous = read(scope, runId, true);
    if (previous.isPresent()) {
      return previous.orElseThrow();
    }
    if (!input.endsAt().isAfter(clock.instant())) {
      throw new BusinessException("SCENARIO_ALREADY_ENDED", 422, "Период эпизода уже завершён");
    }
    var policy =
        policies.scenarioPolicy(
            scope, input.assignmentId(), input.expectedAssignmentRevision(), input.offerIds());
    var sources = marketplace.scenarioSources(scope, Set.copyOf(input.offerIds()));
    requireSourceScope(sources, input.offerIds().size());
    var completionReview =
        ScenarioCompletion.review(
            policy.settings(),
            sources.stream().map(MarketplaceReadService.ScenarioSource::state).toList(),
            input.startsAt(),
            input.endsAt(),
            clock.instant());
    if (!completionReview.digest().equals(input.expectedCompletionReviewDigest())) {
      throw new BusinessException(
          "SCENARIO_REVIEW_STALE",
          409,
          "Охват завершения изменился; подтвердите новый предварительный просмотр");
    }
    if (input.maximumAcceptedQuantity() != null) {
      requireDemandCoverage(scope, Set.copyOf(input.offerIds()));
    }
    List<UUID> targets =
        sources.stream()
            .flatMap(source -> source.state().targetIds().stream())
            .distinct()
            .sorted()
            .toList();
    String scopeDigest =
        IdempotencyService.sha256(
            (scope.accountId() + ":" + targets).getBytes(StandardCharsets.UTF_8));
    jdbc.sql(
            """
            INSERT INTO automation_scenario(organization_id,account_id,id,assignment_id,assignment_revision,policy_id,
              policy_version,state,starts_at,ends_at,maximum_accepted_quantity,minimum_remaining_stock,
              accepted_quantity,scope_digest,settings,reason,revision,author_id,completion_review)
            VALUES (:org,:account,:id,:assignment,:assignmentRevision,:policy,:version,'READY',:start,:end,:quantity,
              :stock,0,:digest,CAST(:settings AS jsonb),'ACTIVATED',1,:author,CAST(:completion AS jsonb))
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", runId)
        .param("assignment", policy.assignmentId())
        .param("policy", policy.policyId())
        .param("version", policy.version())
        .param("assignmentRevision", policy.assignmentRevision())
        .param("start", Timestamp.from(input.startsAt()))
        .param("end", Timestamp.from(input.endsAt()))
        .param("quantity", input.maximumAcceptedQuantity())
        .param("stock", input.minimumRemainingStock())
        .param("digest", scopeDigest)
        .param("settings", json.encode(policy.settings()))
        .param("completion", json.encode(completionReview))
        .param("author", scope.subjectId())
        .update();
    for (var source : sources) {
      for (UUID target : source.state().targetIds().stream().sorted().toList()) {
        jdbc.sql(
                """
                INSERT INTO automation_scenario_scope(organization_id,account_id,run_id,offer_id,target_id,
                  initial_price,initial_state,completed) VALUES (:org,:account,:run,:offer,:target,:price,
                    CAST(:state AS jsonb),false)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("run", runId)
            .param("offer", source.offerId())
            .param("target", target)
            .param("price", source.basePrice())
            .param("state", json.encode(source.state()))
            .update();
        jdbc.sql(
                """
                INSERT INTO automation_scenario_claim(organization_id,account_id,target_id,run_id)
                VALUES (:org,:account,:target,:run)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("target", target)
            .param("run", runId)
            .update();
      }
    }
    jobs.schedule(
        scope,
        "SCENARIO_PROGRESS",
        runId.toString(),
        json.encode(new RunJob(runId)),
        input.startsAt());
    changed(scope, runId, 1, "SCENARIO_ACTIVATED");
    return get(scope, runId);
  }

  public Run get(Scope scope, UUID id) {
    authorization.require(scope, "policy.read");
    return read(scope, id, false)
        .orElseThrow(() -> new BusinessException("SCENARIO_NOT_FOUND", 404, "Эпизод недоступен"));
  }

  public Page<Run> page(Scope scope, int page, int size) {
    return page(scope, page, size, null);
  }

  public Page<Run> page(Scope scope, int page, int size, UUID offerId) {
    authorization.require(scope, "policy.read");
    long offset = Page.offset(page, size);
    String filter =
        """
        WHERE r.organization_id=:org AND r.account_id=:account
          AND (CAST(:offer AS uuid) IS NULL OR EXISTS(SELECT 1 FROM automation_scenario_scope s
            WHERE (s.organization_id,s.account_id,s.run_id)=(r.organization_id,r.account_id,r.id)
              AND s.offer_id=:offer))
        """;
    long total =
        jdbc.sql("SELECT count(*) FROM automation_scenario r " + filter)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("offer", offerId)
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                "SELECT r.* FROM automation_scenario r "
                    + filter
                    + " ORDER BY r.created_at DESC,r.id LIMIT :size OFFSET :offset")
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("offer", offerId)
            .param("size", size)
            .param("offset", offset)
            .query(this::map)
            .list();
    return new Page<>(items, total, page, size);
  }

  public Optional<Run> active(Scope scope, UUID offerId, UUID targetId) {
    return jdbc.sql(
            """
            SELECT r.* FROM automation_scenario r JOIN automation_scenario_scope s
              ON (s.organization_id,s.account_id,s.run_id)=(r.organization_id,r.account_id,r.id)
            WHERE r.organization_id=:org AND r.account_id=:account AND s.offer_id=:offer
              AND s.target_id=:target AND r.state<>'FINISHED'
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("offer", offerId)
        .param("target", targetId)
        .query(this::map)
        .optional();
  }

  @Transactional
  public Run finish(Scope scope, UUID id, long expectedRevision) {
    authorization.requireLocked(scope, Set.of("automation.manage"));
    Run run = read(scope, id, true).orElseThrow();
    if (run.revision() != expectedRevision) {
      throw new BusinessException("STALE_REVISION", 409, "Эпизод уже изменён");
    }
    advance(scope, run, true, "MANUAL_FINISH");
    return get(scope, id);
  }

  @Transactional
  public Run progress(Scope scope, UUID id) {
    Run run = read(scope, id, true).orElseThrow();
    boolean quantityReached =
        run.maximumAcceptedQuantity() != null
            && run.acceptedQuantity().compareTo(run.maximumAcceptedQuantity()) >= 0;
    var permission = risks.forRun(scope, id);
    boolean revoked = permission.isPresent() && !permission.orElseThrow().status().equals("ACTIVE");
    boolean recognizedLimit =
        permission.isPresent() && risks.recognizedLimitReached(scope, permission.orElseThrow());
    boolean stockReached =
        run.minimumRemainingStock() != null
            && resources.minimumReached(
                scope,
                currentStates(scope, id).stream()
                    .filter(
                        state ->
                            state.complete()
                                && state.validUntil() != null
                                && state.validUntil().isAfter(clock.instant()))
                    .map(CommercialState::stockPoolId)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toSet()),
                run.minimumRemainingStock());
    String reason =
        revoked
            ? "ECONOMIC_PERMISSION_REVOKED"
            : recognizedLimit
                ? "RECOGNIZED_LOSS_LIMIT"
                : quantityReached
                    ? "QUANTITY_REACHED"
                    : stockReached ? "MINIMUM_STOCK_REACHED" : "PERIOD_ENDED";
    advance(scope, run, quantityReached || revoked || recognizedLimit || stockReached, reason);
    return read(scope, id, false).orElseThrow();
  }

  public List<DecisionTarget> targets(Scope scope, UUID runId) {
    return jdbc.sql(
            """
            SELECT offer_id,target_id FROM automation_scenario_scope WHERE organization_id=:org
              AND account_id=:account AND run_id=:run AND NOT completed ORDER BY offer_id,target_id LIMIT 1001
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("run", runId)
        .query(
            (rs, row) ->
                new DecisionTarget(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
        .list();
  }

  public record DecisionTarget(UUID offerId, UUID targetId) {}

  public Set<UUID> completionParticipations(Scope scope, UUID runId, UUID offerId, UUID targetId) {
    CommercialState initial =
        jdbc.sql(
                """
                SELECT initial_state::text FROM automation_scenario_scope WHERE organization_id=:org
                  AND account_id=:account AND run_id=:run AND offer_id=:offer AND target_id=:target
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("run", runId)
            .param("offer", offerId)
            .param("target", targetId)
            .query((rs, row) -> json.decode(rs.getString(1), CommercialState.class))
            .single();
    Set<UUID> participations =
        initial.currentParticipations().stream()
            .filter(value -> value.targetId().equals(targetId))
            .map(CommercialState.Participation::promotionId)
            .collect(java.util.stream.Collectors.toSet());
    var run = read(scope, runId, false).orElseThrow();
    return ScenarioCompletion.allowedRegularParticipations(
        participations, run.settings().regularRule());
  }

  @Transactional
  public void markCompleted(
      Scope scope, UUID runId, UUID offerId, UUID targetId, Set<UUID> remainingParticipations) {
    if (runId == null) {
      return;
    }
    Run run = read(scope, runId, true).orElseThrow();
    if (run.state() != ScenarioState.FINISHING) {
      return;
    }
    CommercialState initial =
        jdbc.sql(
                """
                SELECT initial_state::text FROM automation_scenario_scope WHERE organization_id=:org
                  AND account_id=:account AND run_id=:run AND offer_id=:offer AND target_id=:target
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("run", runId)
            .param("offer", offerId)
            .param("target", targetId)
            .query((rs, row) -> json.decode(rs.getString(1), CommercialState.class))
            .single();
    Set<UUID> original =
        initial.currentParticipations().stream()
            .filter(p -> p.targetId().equals(targetId))
            .map(CommercialState.Participation::promotionId)
            .collect(java.util.stream.Collectors.toSet());
    boolean possible =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM automation_scenario_command s JOIN automation_command c
                  ON (c.organization_id,c.account_id,c.id)=(s.organization_id,s.account_id,s.command_id)
                  WHERE s.organization_id=:org AND s.account_id=:account AND s.run_id=:run
                    AND c.state IN ('PENDING','SENT','UNKNOWN'))
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("run", runId)
            .query(Boolean.class)
            .single();
    if (possible
        || !ScenarioCompletion.allowedRegularParticipations(original, run.settings().regularRule())
            .containsAll(remainingParticipations)) {
      return;
    }
    jdbc.sql(
            """
            UPDATE automation_scenario_scope SET completed=true WHERE organization_id=:org
              AND account_id=:account AND run_id=:run AND offer_id=:offer AND target_id=:target
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", runId)
        .param("offer", offerId)
        .param("target", targetId)
        .update();
    advance(scope, run, true, "REGULAR_STATE_CONFIRMED");
  }

  @Transactional
  public void admit(Scope scope, UUID runId, long expectedRevision, UUID commandId) {
    if (runId == null) {
      return;
    }
    Run run = read(scope, runId, true).orElseThrow();
    Instant now = clock.instant();
    if (run.revision() != expectedRevision
        || run.state() == ScenarioState.FINISHED
        || now.isBefore(run.startsAt())
        || (run.state() != ScenarioState.FINISHING && !now.isBefore(run.endsAt()))) {
      throw new BusinessException("SCENARIO_ADMISSION_CHANGED", 409, "Условия эпизода изменились");
    }
    if (run.maximumAcceptedQuantity() != null && run.state() != ScenarioState.FINISHING) {
      requireDemandCoverage(
          scope,
          targets(scope, runId).stream()
              .map(DecisionTarget::offerId)
              .collect(java.util.stream.Collectors.toSet()));
    }
    jdbc.sql(
            """
            INSERT INTO automation_scenario_command(organization_id,account_id,run_id,command_id,
              before_revision,after_revision)
            VALUES (:org,:account,:run,:command,:before,:after) ON CONFLICT DO NOTHING
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", runId)
        .param("command", commandId)
        .param("before", run.revision())
        .param("after", run.revision() + (run.state() == ScenarioState.READY ? 1 : 0))
        .update();
    if (run.state() == ScenarioState.READY) {
      transition(scope, run, ScenarioState.RUNNING, "FIRST_POSSIBLE_EFFECT");
    }
  }

  private void requireDemandCoverage(Scope scope, Set<UUID> offers) {
    var coverage = history.demandCoverage(scope, offers);
    if (coverage.size() != offers.size()
        || coverage.values().stream()
            .anyMatch(
                value ->
                    !value.complete()
                        || value.validUntil() == null
                        || !value.validUntil().isAfter(clock.instant()))) {
      throw new BusinessException(
          "SCENARIO_DEMAND_SOURCE_REQUIRED",
          409,
          "Количественное окончание требует полного актуального источника исходных заказов");
    }
  }

  /** Only this decision's recorded READY-to-RUNNING admission may advance its captured revision. */
  public long continuationRevision(
      Scope scope, UUID runId, UUID decisionId, long capturedRevision, int completedSteps) {
    if (runId == null) {
      return capturedRevision;
    }
    Run run = read(scope, runId, true).orElseThrow();
    record AdmissionRevision(int step, Long before, Long after) {}
    var admissions =
        jdbc.sql(
                """
                SELECT c.step,s.before_revision,s.after_revision FROM automation_scenario_command s
                JOIN automation_command c ON (c.organization_id,c.account_id,c.id)=
                  (s.organization_id,s.account_id,s.command_id)
                WHERE s.organization_id=:org AND s.account_id=:account AND s.run_id=:run
                  AND c.decision_id=:decision AND c.step<:steps AND c.state='APPLIED'
                ORDER BY c.step
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("run", runId)
            .param("decision", decisionId)
            .param("steps", completedSteps)
            .query(
                (rs, row) ->
                    new AdmissionRevision(
                        rs.getInt(1), rs.getObject(2, Long.class), rs.getObject(3, Long.class)))
            .list();
    if (admissions.size() != completedSteps) {
      return -1;
    }
    long expected = capturedRevision;
    for (int i = 0; i < admissions.size(); i++) {
      AdmissionRevision admission = admissions.get(i);
      if (admission.step() != i
          || admission.before() == null
          || admission.after() == null
          || admission.before() != expected) {
        return -1;
      }
      expected = admission.after();
    }
    return run.revision() == expected && run.state() != ScenarioState.FINISHED ? expected : -1;
  }

  @Transactional
  public void observeOriginalDemand(Scope scope, OriginalDemand demand) {
    if (demand.id() == null
        || demand.offerId() == null
        || demand.acceptedAt() == null
        || demand.rawFileId() == null
        || demand.originalQuantity() == null
        || demand.originalQuantity().signum() < 0) {
      throw new IllegalArgumentException(
          "A canonical original order line and acceptance time are required");
    }
    List<UUID> episodes =
        jdbc.sql(
                """
                SELECT r.id FROM automation_scenario r WHERE r.organization_id=:org
                  AND r.account_id=:account AND r.starts_at<=:accepted AND r.ends_at>:accepted
                  AND EXISTS(SELECT 1 FROM automation_scenario_command sc JOIN automation_command c
                    ON (c.organization_id,c.account_id,c.id)=(sc.organization_id,sc.account_id,sc.command_id)
                    JOIN automation_decision d ON (d.organization_id,d.account_id,d.id)=
                      (c.organization_id,c.account_id,c.decision_id)
                    WHERE (sc.organization_id,sc.account_id,sc.run_id)=(r.organization_id,r.account_id,r.id)
                      AND d.offer_id=:offer
                      AND c.admitted_at<=:accepted AND c.state IN ('SENT','UNKNOWN','APPLIED','PARTIALLY_APPLIED'))
                  AND EXISTS(SELECT 1 FROM automation_scenario_scope s
                    WHERE (s.organization_id,s.account_id,s.run_id)=(r.organization_id,r.account_id,r.id)
                      AND s.offer_id=:offer)
                ORDER BY r.id LIMIT 201
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("accepted", Timestamp.from(demand.acceptedAt()))
            .param("offer", demand.offerId())
            .query(UUID.class)
            .list();
    if (episodes.size() > 200) {
      throw new BusinessException(
          "SCENARIO_DEMAND_SCOPE_LIMIT",
          409,
          "Исходный заказ пересекает слишком много исторических эпизодов");
    }
    for (UUID episode : episodes) {
      observeDemand(scope, episode, demand.offerId(), demand.id(), demand.originalQuantity());
    }
  }

  @Transactional
  public void observeDemand(
      Scope scope, UUID runId, UUID offerId, UUID canonicalDemandId, BigDecimal acceptedQuantity) {
    if (acceptedQuantity == null || acceptedQuantity.signum() < 0) {
      throw new IllegalArgumentException("A canonical nonnegative accepted quantity is required");
    }
    Run run = read(scope, runId, true).orElseThrow();
    boolean inside =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM automation_scenario_scope WHERE organization_id=:org
                  AND account_id=:account AND run_id=:run AND offer_id=:offer)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("run", runId)
            .param("offer", offerId)
            .query(Boolean.class)
            .single();
    if (!inside) {
      throw new BusinessException("SCENARIO_SCOPE_MISMATCH", 409, "Заказ вне охвата эпизода");
    }
    int changed =
        jdbc.sql(
                """
                INSERT INTO automation_scenario_demand(organization_id,account_id,run_id,demand_id,quantity)
                VALUES (:org,:account,:run,:demand,:quantity) ON CONFLICT(organization_id,account_id,run_id,demand_id)
                  DO UPDATE SET quantity=GREATEST(automation_scenario_demand.quantity,excluded.quantity)
                  WHERE automation_scenario_demand.quantity<excluded.quantity
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("run", runId)
            .param("demand", canonicalDemandId)
            .param("quantity", acceptedQuantity)
            .update();
    if (changed == 0) {
      return;
    }
    jdbc.sql(
            """
            UPDATE automation_scenario r SET accepted_quantity=(SELECT sum(quantity)
              FROM automation_scenario_demand d WHERE (d.organization_id,d.account_id,d.run_id)=
                (r.organization_id,r.account_id,r.id)),revision=revision+1
            WHERE r.organization_id=:org AND r.account_id=:account AND r.id=:run
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", runId)
        .update();
    changed(scope, run.id(), run.revision() + 1, "SCENARIO_DEMAND_OBSERVED");
    progress(scope, run.id());
  }

  private void advance(Scope scope, Run run, boolean stop, String reason) {
    if (run.state() == ScenarioState.FINISHED) {
      return;
    }
    boolean possible =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM automation_scenario_command WHERE organization_id=:org
                  AND account_id=:account AND run_id=:run)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    boolean completed =
        jdbc.sql(
                """
                SELECT NOT EXISTS(SELECT 1 FROM automation_scenario_scope WHERE organization_id=:org
                  AND account_id=:account AND run_id=:run AND NOT completed)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    ScenarioState next =
        new RuntimeRules()
            .scenarioState(
                run.state(), clock.instant(), run.endsAt(), stop, possible, !possible || completed);
    if (next != run.state()) {
      transition(scope, run, next, reason);
    }
  }

  private void transition(Scope scope, Run run, ScenarioState state, String reason) {
    jdbc.sql(
            """
            UPDATE automation_scenario SET state=:state,reason=:reason,revision=revision+1
            WHERE organization_id=:org AND account_id=:account AND id=:run
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", run.id())
        .param("state", state.name())
        .param("reason", reason)
        .update();
    if (state == ScenarioState.FINISHED) {
      jdbc.sql(
              """
              DELETE FROM automation_scenario_claim WHERE organization_id=:org AND account_id=:account AND run_id=:run
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("run", run.id())
          .update();
    }
    changed(scope, run.id(), run.revision() + 1, "SCENARIO_" + state.name());
  }

  private Optional<Run> read(Scope scope, UUID id, boolean lock) {
    return jdbc.sql(
            """
            SELECT * FROM automation_scenario WHERE organization_id=:org AND account_id=:account AND id=:id
            """
                + (lock ? " FOR UPDATE" : ""))
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("id", id)
        .query(this::map)
        .optional();
  }

  private Run map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Run(
        rs.getObject("id", UUID.class),
        rs.getObject("assignment_id", UUID.class),
        rs.getLong("assignment_revision"),
        rs.getObject("policy_id", UUID.class),
        rs.getLong("policy_version"),
        ScenarioState.valueOf(rs.getString("state")),
        rs.getTimestamp("starts_at").toInstant(),
        rs.getTimestamp("ends_at").toInstant(),
        rs.getBigDecimal("maximum_accepted_quantity"),
        rs.getBigDecimal("minimum_remaining_stock"),
        rs.getBigDecimal("accepted_quantity"),
        rs.getString("scope_digest"),
        json.decode(rs.getString("settings"), PolicySettings.class),
        rs.getString("reason"),
        rs.getLong("revision"),
        rs.getString("completion_review") == null
            ? null
            : json.decode(rs.getString("completion_review"), ScenarioCompletion.Review.class));
  }

  /** A frozen episode keeps its binding until its completion has been confirmed. */
  public void requireNoActiveRun(Scope scope, UUID assignmentId) {
    authorization.requireLocked(scope, Set.of("policy.assign", "finance.read"));
    lockAccount(scope);
    boolean active =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM automation_scenario
                  WHERE organization_id=:org AND account_id=:account AND assignment_id=:assignment
                    AND state IN ('READY','RUNNING','FINISHING'))
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("assignment", assignmentId)
            .query(Boolean.class)
            .single();
    if (active) {
      throw new BusinessException(
          "ASSIGNMENT_HAS_ACTIVE_SCENARIO",
          409,
          "Сначала завершите временный сценарий этого назначения");
    }
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

  private void changed(Scope scope, UUID id, long revision, String action) {
    audit.record(scope, action, id, "revision=" + revision);
    outbox.emit(
        scope,
        action + ":" + id + ":" + revision,
        action,
        new OutboxService.EntityChange("temporary-runs", id, revision));
  }
}
