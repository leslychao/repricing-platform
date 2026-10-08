package ru.oritas.repricer.automation.decision;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.automation.execution.ExecutionService;
import ru.oritas.repricer.automation.runtime.AutoService;
import ru.oritas.repricer.automation.runtime.ScenarioService;
import ru.oritas.repricer.marketplace.CommercialGateway;
import ru.oritas.repricer.marketplace.CommercialGateway.CommercialIntent;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.ScopeExecutor;
import ru.oritas.repricer.platform.SqlReadProjection;
import ru.oritas.repricer.platform.StoredFileService;

/**
 * Immutable calculation bases and current approval, kept separate from observed marketplace facts.
 */
@Service
public class DecisionService {
  public record Basis(
      UUID offerId,
      UUID policyId,
      long policyVersion,
      UUID assignmentId,
      long assignmentRevision,
      long offerRevision,
      long costRevision,
      long taxRevision,
      long safetyRevision,
      Instant calculatedAt,
      Instant validUntil,
      int maximumAgeSeconds,
      boolean executable,
      long offerPriceRevision,
      UUID scenarioId,
      long scenarioRevision,
      boolean finishingScenario,
      String marketplaceFingerprint,
      String competitorSegment,
      String competitorFingerprint) {}

  public record Snapshot(
      Basis basis,
      DecisionEngine.Context context,
      List<DecisionEngine.Candidate> candidates,
      DecisionEngine.Decision result,
      DecisionContextAssembler.Evidence evidence,
      UUID searchTraceFileId) {
    public Snapshot {
      candidates = List.copyOf(candidates);
    }
  }

  public record DecisionView(
      UUID id,
      UUID offerId,
      String state,
      String reason,
      boolean targetAchieved,
      boolean requiresConfirmation,
      UUID selectedCandidate,
      Instant calculatedAt,
      Instant validUntil,
      long revision,
      UUID snapshotFileId) {}

  public record ExecutionJob(UUID commandId) {}

  public record ApprovalRequest(UUID decisionId, long expectedRevision) {}

  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final Clock clock;
  private final ScopeExecutor scopes;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final OutboxService outbox;
  private final StoredFileService files;
  private final CommercialGateway gateway;
  private final ExecutionService execution;
  private final JobRuntime jobs;
  private final ScenarioService scenarios;
  private final DecisionValidityService validity;
  private final CurrentEconomicsService currentEconomics;
  private final QuantityComparisonService quantities;
  private final AutoService automatic;
  private final DecisionContinuationService continuation;
  private final DecisionEngine engine = new DecisionEngine();

  /** Metadata is separate from financial evidence and does not deserialize stored snapshots. */
  public SqlReadProjection latestDecisionProjection(Scope scope) {
    authorization.require(scope, "command.read");
    return new SqlReadProjection(
        """
        SELECT DISTINCT ON (offer_id) offer_id,id AS decision_id,calculated_at,state,reason
        FROM automation_decision WHERE organization_id=:decisionOrganization
          AND account_id=:decisionAccount ORDER BY offer_id,calculated_at DESC,id
        """,
        Map.of(
            "decisionOrganization",
            scope.requireOrganization(),
            "decisionAccount",
            scope.requireAccount()));
  }

  public DecisionService(
      JdbcClient jdbc,
      JsonCodec json,
      Clock clock,
      ScopeExecutor scopes,
      AuthorizationService authorization,
      AuditService audit,
      OutboxService outbox,
      StoredFileService files,
      CommercialGateway gateway,
      ExecutionService execution,
      JobRuntime jobs,
      ScenarioService scenarios,
      DecisionValidityService validity,
      CurrentEconomicsService currentEconomics,
      AutoService automatic,
      QuantityComparisonService quantities,
      DecisionContinuationService continuation) {
    this.jdbc = jdbc;
    this.json = json;
    this.clock = clock;
    this.scopes = scopes;
    this.authorization = authorization;
    this.audit = audit;
    this.outbox = outbox;
    this.files = files;
    this.gateway = gateway;
    this.execution = execution;
    this.jobs = jobs;
    this.scenarios = scenarios;
    this.validity = validity;
    this.currentEconomics = currentEconomics;
    this.quantities = quantities;
    this.automatic = automatic;
    this.continuation = continuation;
  }

  /** Called with a backend-assembled basis, never deserialized from a user-supplied context. */
  public DecisionView calculate(
      Scope scope, UUID calculationId, DecisionContextAssembler.Assembly assembly)
      throws IOException {
    return calculate(scope, calculationId, assembly, null);
  }

  public DecisionView calculate(
      Scope scope, UUID calculationId, DecisionContextAssembler.Assembly assembly, JobContext job)
      throws IOException {
    Basis basis = assembly.basis();
    scopes.execute(
        scope,
        Set.of(),
        () -> {
          authorization.requireActorLocked(
              scope,
              scope.subjectId(),
              DecisionAuthorization.action(
                  assembly.evidence().effectiveAssignment(), "decision.preview"));
          return true;
        });
    var existing =
        scopes.execute(
            scope,
            Set.of(),
            () ->
                jdbc.sql(
                        """
                        SELECT * FROM automation_decision WHERE organization_id=:org AND account_id=:account
                          AND id=:id
                        """)
                    .param("org", scope.organizationId())
                    .param("account", scope.accountId())
                    .param("id", calculationId)
                    .query(this::map)
                    .optional());
    if (existing.isPresent()) {
      return existing.orElseThrow();
    }
    if (basis.calculatedAt() == null
        || basis.validUntil() == null
        || basis.maximumAgeSeconds() < 1
        || basis.maximumAgeSeconds() > 300) {
      throw new IllegalArgumentException("Invalid calculation basis");
    }
    Snapshot snapshot = calculateSnapshot(scope, calculationId, assembly, job);
    DecisionEngine.Decision result = snapshot.result();
    UUID id = calculationId;
    StoredFileService.FileRecord stored = storeSnapshot(scope, snapshot, "DECISION", id);
    return scopes.execute(
        scope,
        Set.of(),
        () -> {
          if (job != null) {
            jobs.requireOwnership(job);
          }
          authorization.requireActorLocked(
              scope,
              scope.subjectId(),
              DecisionAuthorization.action(
                  assembly.evidence().effectiveAssignment(), "decision.preview"));
          jdbc.sql(
                  """
                  INSERT INTO automation_decision(organization_id,account_id,id,offer_id,policy_id,
                    policy_version,assignment_id,assignment_revision,offer_revision,cost_revision,
                    tax_revision,safety_revision,state,reason,target_achieved,requires_confirmation,
                    selected_candidate,calculated_at,valid_until,maximum_age_seconds,executable,
                    snapshot_file_id,revision,author_id,offer_price_revision,scenario_id,scenario_revision,
                    calculation_job_id,effective_assignment)
                  VALUES (:org,:account,:id,:offer,:policy,:version,:assignment,:assignmentRevision,
                    :offerRevision,:costRevision,:taxRevision,:safetyRevision,'CALCULATED',:reason,
                    :achieved,:confirmation,:selected,:calculated,:valid,:age,:executable,:file,1,:author,
                    :bounds,:run,:runRevision,:calculationJob,:effectiveAssignment)
                  """)
              .param("org", scope.requireOrganization())
              .param("account", scope.requireAccount())
              .param("id", id)
              .param("offer", basis.offerId())
              .param("policy", basis.policyId())
              .param("version", basis.policyVersion())
              .param("assignment", basis.assignmentId())
              .param("assignmentRevision", basis.assignmentRevision())
              .param("offerRevision", basis.offerRevision())
              .param("costRevision", basis.costRevision())
              .param("taxRevision", basis.taxRevision())
              .param("safetyRevision", basis.safetyRevision())
              .param("reason", result.reason())
              .param("achieved", result.targetAchieved())
              .param("confirmation", result.requiresManualConfirmation())
              .param("selected", result.selectedCandidate())
              .param("calculated", Timestamp.from(basis.calculatedAt()))
              .param("valid", Timestamp.from(basis.validUntil()))
              .param("age", basis.maximumAgeSeconds())
              .param("executable", basis.executable() && result.status().equals("CALCULATED"))
              .param("file", stored.id())
              .param("author", scope.subjectId())
              .param("bounds", basis.offerPriceRevision())
              .param("run", basis.scenarioId())
              .param("runRevision", basis.scenarioRevision())
              .param("calculationJob", job == null ? null : job.id())
              .param("effectiveAssignment", assembly.evidence().effectiveAssignment())
              .update();
          files.retain(stored.id(), "DECISION", id, scope);
          files.retain(snapshot.searchTraceFileId(), "DECISION_SEARCH", id, scope);
          currentEconomics.publish(scope, id, snapshot);
          quantities.publishDefault(scope, id, snapshot);
          changed(scope, id, 1, "DECISION_CALCULATED");
          return read(scope, id, false);
        });
  }

  private record SearchRecord(
      DecisionEngine.Candidate candidate, DecisionEngine.Evaluation evaluation) {}

  /**
   * Full search evidence is spooled record by record; only baseline and selected plans are
   * retained.
   */
  private Snapshot calculateSnapshot(
      Scope scope, UUID id, DecisionContextAssembler.Assembly assembly, JobContext job)
      throws IOException {
    Path trace = Files.createTempFile("repricer-search-", ".json");
    Path recordFile = Files.createTempFile("repricer-search-record-", ".json");
    try {
      DecisionEngine.Decision result;
      Instant deadline = clock.instant().plusSeconds(120);
      if (job != null && job.deadline().isBefore(deadline)) {
        deadline = job.deadline();
      }
      Instant searchDeadline = deadline;
      try (var writer = Files.newBufferedWriter(trace, StandardCharsets.UTF_8)) {
        writer.write('[');
        var sink = new SearchTraceSink(writer, recordFile);
        result =
            engine.decideStreaming(
                assembly.context(),
                assembly.candidates(),
                sink::append,
                () ->
                    clock.instant().isBefore(searchDeadline)
                        && !Thread.currentThread().isInterrupted());
        writer.write(']');
      } catch (UncheckedIOException failure) {
        throw failure.getCause();
      }
      List<DecisionEngine.Candidate> retained = new ArrayList<>();
      Map<UUID, DecisionEngine.Evaluation> detailed = new HashMap<>();
      try (var reader =
          new com.google.gson.stream.JsonReader(
              Files.newBufferedReader(trace, StandardCharsets.UTF_8))) {
        reader.setNestingLimit(64);
        reader.beginArray();
        while (reader.hasNext()) {
          SearchRecord record = json.gson().fromJson(reader, SearchRecord.class);
          if (record.candidate().unchanged()
              || record.candidate().id().equals(result.selectedCandidate())) {
            if (retained.size() >= 2) {
              throw new IllegalStateException(
                  "A search has one exact baseline and one selected candidate");
            }
            retained.add(record.candidate());
            detailed.put(record.candidate().id(), record.evaluation());
          }
        }
        reader.endArray();
      }
      var finalResult =
          new DecisionEngine.Decision(
              result.status(),
              result.reason(),
              result.selectedCandidate(),
              result.targetAchieved(),
              result.requiresManualConfirmation(),
              result.workUsed(),
              result.evaluations().stream()
                  .map(evaluation -> detailed.getOrDefault(evaluation.candidateId(), evaluation))
                  .toList());
      StoredFileService.FileRecord storedTrace;
      try (InputStream stream = Files.newInputStream(trace)) {
        storedTrace =
            files.store(
                scope,
                "DECISION_SEARCH",
                "application/json",
                id + "-search.json",
                stream,
                128L * 1024 * 1024,
                clock.instant().plusSeconds(120));
      }
      return new Snapshot(
          assembly.basis(),
          assembly.context(),
          retained,
          finalResult,
          assembly.evidence(),
          storedTrace.id());
    } finally {
      Files.deleteIfExists(trace);
      Files.deleteIfExists(recordFile);
    }
  }

  /** A record is committed to the trace only when the entire bounded record fits. */
  private final class SearchTraceSink {
    private final Writer trace;
    private final Path recordFile;
    private long bytes = 2;
    private boolean first = true;

    private SearchTraceSink(Writer trace, Path recordFile) {
      this.trace = trace;
      this.recordFile = recordFile;
    }

    private void append(DecisionEngine.Candidate candidate, DecisionEngine.Evaluation evaluation) {
      try {
        try (Writer writer = Files.newBufferedWriter(recordFile, StandardCharsets.UTF_8)) {
          json.gson().toJson(new SearchRecord(candidate, evaluation), writer);
        }
        long recordBytes = Files.size(recordFile);
        long separator = first ? 0 : 1;
        if (bytes + recordBytes + separator > 128L * 1024 * 1024) {
          throw new DecisionEngine.IncompleteSearch("DECISION_SIZE_LIMIT");
        }
        if (!first) {
          trace.write(',');
        }
        try (Reader reader = Files.newBufferedReader(recordFile, StandardCharsets.UTF_8)) {
          reader.transferTo(trace);
        }
        bytes += recordBytes + separator;
        first = false;
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }
  }

  @Transactional
  public UUID submitPreview(
      Scope scope, UUID requestId, DecisionContextAssembler.PreviewRequest request) {
    requirePreviewAuthority(scope, request);
    return jobs.submit(scope, "DECISION_PREVIEW", requestId.toString(), json.encode(request));
  }

  @Transactional
  public UUID submitApproval(Scope scope, UUID requestId, ApprovalRequest request) {
    requireApprovalAuthority(scope, request.decisionId());
    return jobs.submit(scope, "DECISION_APPROVE", requestId.toString(), json.encode(request));
  }

  public void requirePreviewAuthority(
      Scope scope, DecisionContextAssembler.PreviewRequest request) {
    authorization.requireActorLocked(
        scope, scope.subjectId(), DecisionAuthorization.preview(request));
  }

  public void requireApprovalAuthority(Scope scope, UUID decisionId) {
    requireStoredAuthority(scope, decisionId, "decision.approve");
  }

  public void requireDisplayAuthority(Scope scope, UUID decisionId) {
    requireStoredAuthority(scope, decisionId, "command.read");
  }

  private void requireStoredAuthority(Scope scope, UUID decisionId, String action) {
    boolean effective =
        jdbc.sql(
                """
                SELECT effective_assignment FROM automation_decision
                WHERE organization_id=:org AND account_id=:account AND id=:id
                """)
            .param("org", scope.requireOrganization())
            .param("account", scope.requireAccount())
            .param("id", decisionId)
            .query(Boolean.class)
            .optional()
            .orElseThrow(
                () -> new BusinessException("DECISION_NOT_FOUND", 404, "Решение недоступно"));
    authorization.requireActorLocked(
        scope, scope.subjectId(), DecisionAuthorization.action(effective, action));
  }

  /** Internal evidence for the app's explicitly redacted response, never an HTTP snapshot. */
  public Snapshot snapshotForDisplay(Scope scope, UUID decisionId) throws IOException {
    return authorizedSnapshot(scope, decisionId, "command.read");
  }

  private Snapshot authorizedSnapshot(Scope scope, UUID decisionId, String action)
      throws IOException {
    var file =
        scopes.execute(
            scope,
            Set.of(),
            () -> {
              requireStoredAuthority(scope, decisionId, action);
              return snapshotFile(scope, decisionId);
            });
    return readSnapshot(file);
  }

  public Snapshot snapshot(Scope scope, UUID decisionId) throws IOException {
    StoredFileService.FileRecord file =
        scopes.execute(
            scope,
            Set.of(),
            () -> {
              authorization.require(scope, "decision.preview");
              authorization.require(scope, "finance.read");
              return snapshotFile(scope, decisionId);
            });
    return readSnapshot(file);
  }

  public Snapshot snapshotForReconciliation(Scope scope, UUID decisionId) throws IOException {
    StoredFileService.FileRecord file =
        scopes.execute(
            scope, Set.of("command.reconcile", "file.read"), () -> snapshotFile(scope, decisionId));
    return readSnapshot(file);
  }

  private StoredFileService.FileRecord snapshotFile(Scope scope, UUID decisionId) {
    record Reference(UUID fileId, boolean expired) {}
    var reference =
        jdbc.sql(
                """
                SELECT snapshot_file_id,evidence_expired_at IS NOT NULL FROM automation_decision
                WHERE organization_id=:org AND account_id=:account AND id=:id
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("id", decisionId)
            .query((row, index) -> new Reference(row.getObject(1, UUID.class), row.getBoolean(2)))
            .optional()
            .orElseThrow(
                () -> new BusinessException("DECISION_NOT_FOUND", 404, "Решение не найдено"));
    if (reference.expired()) {
      throw new BusinessException(
          "DECISION_EVIDENCE_EXPIRED",
          410,
          "Истёк срок хранения подробностей предварительного расчёта");
    }
    return files.get(reference.fileId());
  }

  private Snapshot readSnapshot(StoredFileService.FileRecord file) throws IOException {
    try (InputStream stream = files.open(file);
        Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      return json.decode(reader, Snapshot.class);
    }
  }

  @Transactional
  public void stopRemainder(Scope scope, UUID decisionId, String reason) {
    DecisionView decision = read(scope, decisionId, true);
    if (!Set.of("COMPLETED", "CANCELLED").contains(decision.state())) {
      complete(scope, decision, reason);
    }
  }

  public DecisionView approve(Scope scope, UUID id, long expectedRevision) throws IOException {
    return approve(scope, id, expectedRevision, null);
  }

  public DecisionView approve(Scope scope, UUID id, long expectedRevision, JobContext job)
      throws IOException {
    Snapshot snapshot = authorizedSnapshot(scope, id, "decision.approve");
    return scopes.execute(
        scope,
        job == null ? Set.of() : DecisionAuthorization.INTERNAL_INPUTS,
        () -> {
          requireApprovalAuthority(scope, id);
          automatic.requireDecisionAuthority(scope, id);
          if (job != null) {
            jobs.requireOwnership(job);
          }
          DecisionView decision = read(scope, id, true);
          if (job != null
              && jdbc.sql(
                      """
                      SELECT EXISTS(SELECT 1 FROM automation_decision WHERE organization_id=:org
                        AND account_id=:account AND id=:id AND approval_job_id=:job)
                      """)
                  .param("org", scope.organizationId())
                  .param("account", scope.accountId())
                  .param("id", id)
                  .param("job", job.id())
                  .query(Boolean.class)
                  .single()) {
            return decision;
          }
          if (decision.revision() != expectedRevision
              || !decision.state().equals("CALCULATED")
              || !snapshot.basis().executable()
              || snapshot.result().selectedCandidate() == null
              || !snapshot.result().status().equals("CALCULATED")
              || !current(scope, snapshot.basis())) {
            throw new BusinessException(
                "DECISION_STALE", 409, "Решение изменено, условно или утратило актуальность");
          }
          DecisionEngine.Candidate chosen = selected(snapshot);
          jdbc.sql(
                  """
                  UPDATE automation_decision SET state=:state,revision=revision+1,
                    approved_by=:subject,approved_at=:now,approval_job_id=:job
                  WHERE organization_id=:org AND account_id=:account AND id=:id
                  """)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("id", id)
              .param("state", chosen.path().isEmpty() ? "COMPLETED" : "APPROVED")
              .param("subject", scope.subjectId())
              .param("job", job == null ? null : job.id())
              .param("now", Timestamp.from(clock.instant()))
              .update();
          if (!chosen.path().isEmpty()) {
            prepareStep(scope, id, snapshot, chosen, 0);
          } else {
            finishScenario(scope, snapshot, chosen);
          }
          changed(scope, id, decision.revision() + 1, "DECISION_APPROVED");
          return read(scope, id, false);
        });
  }

  public StoredFileService.FileRecord storeJournal(
      Scope scope,
      UUID commandId,
      Snapshot snapshot,
      CommercialGateway.PreparedCommercialCommand prepared)
      throws IOException {
    return storeSnapshot(scope, new Journal(snapshot, prepared), "SEND_JOURNAL", commandId);
  }

  public record Journal(Snapshot snapshot, CommercialGateway.PreparedCommercialCommand request) {}

  public List<DecisionView> list(Scope scope, int limit, int offset) {
    authorization.require(scope, "command.read");
    if (limit < 1 || limit > 200 || offset < 0) {
      throw new IllegalArgumentException("Invalid page");
    }
    return jdbc.sql(
            """
            SELECT * FROM automation_decision WHERE organization_id=:org AND account_id=:account
            ORDER BY calculated_at DESC,id LIMIT :limit OFFSET :offset
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("limit", limit)
        .param("offset", offset)
        .query(this::map)
        .list();
  }

  public DecisionView get(Scope scope, UUID id) {
    authorization.require(scope, "command.read");
    return read(scope, id, false);
  }

  public boolean current(Scope scope, Basis basis) {
    return validity.current(scope, basis);
  }

  public DecisionContinuationService.Result currentStep(
      Scope scope, UUID decisionId, Snapshot snapshot, int step) {
    return continuation.current(scope, decisionId, snapshot, step);
  }

  @Transactional
  public boolean finishStep(Scope scope, UUID decisionId, Snapshot snapshot) {
    DecisionView decision = read(scope, decisionId, true);
    if (Set.of("COMPLETED", "CANCELLED").contains(decision.state())) {
      return true;
    }
    record StepState(int step, String state) {}
    List<StepState> commands =
        jdbc.sql(
                """
                SELECT step,state FROM automation_command WHERE organization_id=:org
                  AND account_id=:account AND decision_id=:decision ORDER BY step
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("decision", decisionId)
            .query((rs, row) -> new StepState(rs.getInt(1), rs.getString(2)))
            .list();
    if (commands.isEmpty()
        || commands.stream()
            .anyMatch(c -> Set.of("PENDING", "SENT", "UNKNOWN").contains(c.state()))) {
      return true;
    }
    DecisionEngine.Candidate chosen = selected(snapshot);
    boolean allApplied = commands.stream().allMatch(c -> c.state().equals("APPLIED"));
    if (allApplied) {
      var current = currentStep(scope, decisionId, snapshot, commands.size());
      if (current.status() == DecisionContinuationService.Status.AWAITING_SOURCE) {
        return false;
      }
      if (current.status() != DecisionContinuationService.Status.CURRENT) {
        complete(
            scope,
            decision,
            commands.size() < chosen.path().size()
                ? "PARTIAL_RECALCULATION_REQUIRED"
                : "APPLIED_RECALCULATION_REQUIRED");
        return true;
      }
      if (commands.size() < chosen.path().size()) {
        prepareStep(scope, decisionId, snapshot, chosen, commands.size(), current.basis());
      } else {
        complete(scope, decision, "APPLIED");
        finishScenario(scope, snapshot, chosen);
      }
    } else {
      complete(scope, decision, "PARTIAL_OR_FAILED");
    }
    return true;
  }

  private void finishScenario(Scope scope, Snapshot snapshot, DecisionEngine.Candidate chosen) {
    if (!snapshot.basis().finishingScenario()) {
      return;
    }
    for (UUID target : snapshot.context().originalTargets()) {
      scenarios.markCompleted(
          scope,
          snapshot.basis().scenarioId(),
          snapshot.basis().offerId(),
          target,
          chosen.participations());
    }
  }

  private void prepareStep(
      Scope scope,
      UUID decisionId,
      Snapshot snapshot,
      DecisionEngine.Candidate chosen,
      int stepIndex) {
    prepareStep(scope, decisionId, snapshot, chosen, stepIndex, snapshot.basis());
  }

  private void prepareStep(
      Scope scope,
      UUID decisionId,
      Snapshot snapshot,
      DecisionEngine.Candidate chosen,
      int stepIndex,
      Basis currentBasis) {
    DecisionEngine.Step step = chosen.path().get(stepIndex);
    CommercialGateway.Operation operation =
        switch (step.operation()) {
          case SET_BASE_PRICE -> CommercialGateway.Operation.SET_PRICE;
          case SET_PROMO_PRICE -> CommercialGateway.Operation.SET_PROMO_PRICE;
          case JOIN_PROMO -> CommercialGateway.Operation.JOIN_PROMO;
          case LEAVE_PROMO -> CommercialGateway.Operation.LEAVE_PROMO;
        };
    var prepared =
        gateway.prepare(
            scope,
            new CommercialIntent(
                snapshot.basis().offerId(),
                step.targetId(),
                operation,
                step.absolutePrice(),
                step.promoId(),
                snapshot.evidence().commercial().profileRevision(),
                currentBasis.offerRevision(),
                false,
                step.committedQuantity()));
    ExecutionService.Command command =
        execution.prepare(
            scope,
            decisionId,
            stepIndex,
            prepared,
            snapshot.basis().calculatedAt(),
            snapshot.basis().validUntil());
    jobs.submit(
        scope,
        "AUTOMATION_EXECUTE",
        command.id().toString(),
        json.encode(new ExecutionJob(command.id())));
  }

  private StoredFileService.FileRecord storeSnapshot(
      Scope scope, Object snapshot, String kind, UUID id) throws IOException {
    Path temporary = Files.createTempFile("repricer-evidence-", ".json");
    try {
      try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
        json.write(snapshot, writer);
      }
      long maximum = kind.equals("DECISION") ? 32L * 1024 * 1024 : 128L * 1024 * 1024;
      if (Files.size(temporary) > maximum) {
        throw new BusinessException("DECISION_SIZE_LIMIT", 422, "Превышен предел расчёта");
      }
      try (InputStream stream = Files.newInputStream(temporary)) {
        return files.store(
            scope,
            kind,
            "application/json",
            id + ".json",
            stream,
            maximum,
            clock.instant().plusSeconds(120));
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private DecisionEngine.Candidate selected(Snapshot snapshot) {
    return snapshot.candidates().stream()
        .filter(c -> c.id().equals(snapshot.result().selectedCandidate()))
        .findFirst()
        .orElseThrow();
  }

  private void complete(Scope scope, DecisionView decision, String reason) {
    jdbc.sql(
            """
            UPDATE automation_decision SET state='COMPLETED',reason=:reason,revision=revision+1
            WHERE organization_id=:org AND account_id=:account AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", decision.id())
        .param("reason", reason)
        .update();
    changed(scope, decision.id(), decision.revision() + 1, "DECISION_COMPLETED");
  }

  private DecisionView read(Scope scope, UUID id, boolean lock) {
    return jdbc.sql(
            """
            SELECT * FROM automation_decision WHERE organization_id=:org AND account_id=:account
              AND id=:id
            """
                + (lock ? " FOR UPDATE" : ""))
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", id)
        .query(this::map)
        .optional()
        .orElseThrow(() -> new BusinessException("DECISION_NOT_FOUND", 404, "Решение недоступно"));
  }

  private DecisionView map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new DecisionView(
        rs.getObject("id", UUID.class),
        rs.getObject("offer_id", UUID.class),
        rs.getString("state"),
        rs.getString("reason"),
        rs.getBoolean("target_achieved"),
        rs.getBoolean("requires_confirmation"),
        rs.getObject("selected_candidate", UUID.class),
        rs.getTimestamp("calculated_at").toInstant(),
        rs.getTimestamp("valid_until").toInstant(),
        rs.getLong("revision"),
        rs.getObject("snapshot_file_id", UUID.class));
  }

  private void changed(Scope scope, UUID id, long revision, String action) {
    UUID offerId =
        jdbc.sql(
                "SELECT offer_id FROM automation_decision WHERE organization_id=:org AND"
                    + " account_id=:account AND id=:id")
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", id)
            .query(UUID.class)
            .single();
    audit.recordForOffer(scope, action, id, offerId, "revision=" + revision);
    outbox.emit(
        scope,
        action + ":" + id + ":" + revision,
        action,
        new OutboxService.EntityChange("decisions", id, revision));
  }
}
