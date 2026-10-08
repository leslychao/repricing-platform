package ru.oritas.repricer.economics.accounting;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.marketplace.FinancialSourceService;
import ru.oritas.repricer.marketplace.FinancialSourceService.FinancialFact;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Scope;

/** Canonical source revisions, never payments-as-profit or expense estimates added to facts. */
@Service
public class AccountingService {
  public static final String CERTAINTY_SQL =
      "CASE WHEN preliminary IS NULL THEN 'UNKNOWN' WHEN preliminary THEN 'PRELIMINARY' ELSE"
          + " 'CONFIRMED' END";
  public static final String STATUS_SQL =
      "CASE WHEN preliminary IS NULL THEN 'UNKNOWN' WHEN preliminary THEN 'PRELIMINARY' WHEN"
          + " complete THEN 'CONFIRMED' ELSE 'INCOMPLETE' END";
  public static final String COMPLETENESS_SQL =
      "CASE WHEN preliminary IS NULL THEN 'UNKNOWN' WHEN complete THEN 'COMPLETE' ELSE 'INCOMPLETE'"
          + " END";

  private final JdbcClient jdbc;
  private final EconomicCalculator calculator = new EconomicCalculator();
  private final JsonCodec json = new JsonCodec();

  public AccountingService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  private record Declaration(
      BigDecimal unitCost,
      BigDecimal unitExpense,
      BigDecimal taxRate,
      long costRevision,
      long taxRevision) {}

  public record RecognitionBasis(
      UUID sourcePublicationId,
      UUID sourceId,
      long costRevision,
      long taxRevision,
      BigDecimal unitCost,
      BigDecimal unitExpense,
      BigDecimal taxRate) {}

  private record SourceFact(UUID publicationId, FinancialFact fact) {}

  private record Progress(UUID afterId, boolean complete) {}

  /**
   * Immutable normalized input pages are invisible until the connected recognition set is ready.
   */
  @Transactional
  public void stageFacts(Scope scope, UUID editionId, List<FinancialFact> facts) {
    if (facts.size() > 200) {
      throw new IllegalArgumentException("Financial recognition page exceeds 200 rows");
    }
    for (var fact : facts) {
      jdbc.sql(
              """
              INSERT INTO economics_source_fact(organization_id,account_id,publication_id,source_id,
                source_revision,component,original_order_line_id,recognition_scope,fact)
              VALUES(:org,:account,:publication,:source,:revision,:component,:line,:recognitionScope,CAST(:fact AS jsonb))
              """)
          .param("org", scope.organizationId())
          .param("account", scope.requireAccount())
          .param("publication", editionId)
          .param("source", fact.id())
          .param("revision", fact.revision())
          .param("component", component(fact))
          .param("line", linkedLine(fact))
          .param("recognitionScope", recognitionScope(fact))
          .param("fact", json.encode(fact))
          .update();
    }
  }

  /** Each bounded batch rebuilds complete original lines, including independent later returns. */
  public boolean prepareRecognitionNext(Scope scope, UUID editionId, long basisRevision) {
    Progress progress =
        jdbc.sql(
                """
                SELECT recognition_after,recognition_complete FROM economics_ledger_publication
                WHERE organization_id=:org AND account_id=:account AND id=:id FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("id", editionId)
            .query((rs, row) -> new Progress(rs.getObject(1, UUID.class), rs.getBoolean(2)))
            .single();
    if (progress.complete()) {
      return true;
    }
    var ids =
        jdbc.sql(
                """
                SELECT DISTINCT f.source_id FROM economics_source_fact f
                JOIN economics_ledger_publication p ON (p.organization_id,p.account_id,p.id)=
                  (f.organization_id,f.account_id,f.publication_id)
                JOIN economics_ledger_publication prepared ON prepared.organization_id=p.organization_id
                  AND prepared.account_id=p.account_id AND prepared.id=:id
                WHERE f.organization_id=:org AND f.account_id=:account
                  AND (p.id=:id OR (p.state='PUBLISHED' AND p.cohort_key=prepared.cohort_key))
                  AND (CAST(:after AS uuid) IS NULL OR f.source_id>:after)
                ORDER BY f.source_id LIMIT 201
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", editionId)
            .param("after", progress.afterId())
            .query(UUID.class)
            .list();
    var pageIds = ids.stream().limit(200).toList();
    var versions =
        pageIds.isEmpty()
            ? List.<SourceFact>of()
            : jdbc.sql(
                    """
                    SELECT f.publication_id,f.fact::text FROM economics_source_fact f
                    JOIN economics_ledger_publication p ON (p.organization_id,p.account_id,p.id)=
                      (f.organization_id,f.account_id,f.publication_id)
                    JOIN economics_ledger_publication prepared ON prepared.organization_id=p.organization_id
                      AND prepared.account_id=p.account_id AND prepared.id=:edition
                    WHERE f.organization_id=:org AND f.account_id=:account AND f.source_id IN (:sources)
                      AND (p.id=:edition OR (p.state='PUBLISHED' AND p.cohort_key=prepared.cohort_key))
                    ORDER BY f.source_id,f.publication_id LIMIT 401
                    """)
                .param("org", scope.organizationId())
                .param("account", scope.accountId())
                .param("edition", editionId)
                .param("sources", pageIds)
                .query(
                    (rs, row) ->
                        new SourceFact(
                            rs.getObject(1, UUID.class),
                            json.decode(rs.getString(2), FinancialFact.class)))
                .list();
    if (versions.size() > 400) {
      throw new BusinessException(
          "FINANCIAL_SOURCE_CONFLICT",
          409,
          "Для одного источника найдено несколько основных компонентов");
    }
    var bySource =
        versions.stream().collect(java.util.stream.Collectors.groupingBy(s -> s.fact().id()));
    var declared =
        declarations(
            scope,
            versions.stream()
                .filter(
                    v -> v.publicationId().equals(editionId) && recognitionScope(v.fact()) == null)
                .map(SourceFact::fact)
                .toList());
    UUID after = progress.afterId();
    int recognized = 0;
    int visited = 0;
    sources:
    for (UUID id : pageIds) {
      if (recognized >= 200) {
        break;
      }
      for (var version : bySource.getOrDefault(id, List.of())) {
        String group = recognitionScope(version.fact());
        if (group != null) {
          var prepared = recognizeScope(scope, editionId, basisRevision, group);
          recognized += prepared.written();
          if (!prepared.ready()) {
            break sources;
          }
        } else if (version.publicationId().equals(editionId)) {
          recognizeIndependent(scope, editionId, basisRevision, version, declared.get(id));
          recognized++;
        }
      }
      after = id;
      visited++;
    }
    boolean complete = visited == ids.size();
    jdbc.sql(
            """
            UPDATE economics_ledger_publication SET recognition_after=:after,recognition_complete=:complete
            WHERE organization_id=:org AND account_id=:account AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", editionId)
        .param("after", after)
        .param("complete", complete)
        .update();
    return complete;
  }

  private record ScopeProgress(boolean ready, int written, boolean coverageComplete) {}

  private record CapturedScope(boolean ready, int preparedRows, String signature) {}

  private ScopeProgress recognizeScope(
      Scope scope, UUID editionId, long basisRevision, String group) {
    var captured =
        jdbc.sql(
                """
                SELECT ready,prepared_rows,source_signature FROM economics_recognition_scope
                WHERE organization_id=:org AND account_id=:account AND publication_id=:id AND scope_key=:group
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", editionId)
            .param("group", group)
            .query((rs, row) -> new CapturedScope(rs.getBoolean(1), rs.getInt(2), rs.getString(3)))
            .optional();
    if (captured.isPresent() && captured.orElseThrow().ready()) {
      return new ScopeProgress(true, 0, true);
    }
    var sources =
        jdbc.sql(
                """
                SELECT f.publication_id,f.fact::text FROM economics_source_fact f
                JOIN economics_ledger_publication p ON (p.organization_id,p.account_id,p.id)=
                  (f.organization_id,f.account_id,f.publication_id)
                JOIN economics_ledger_publication prepared ON prepared.organization_id=p.organization_id
                  AND prepared.account_id=p.account_id AND prepared.id=:id
                WHERE f.organization_id=:org AND f.account_id=:account AND f.recognition_scope=:group
                  AND (p.id=:id OR (p.state='PUBLISHED' AND p.cohort_key<>prepared.cohort_key))
                ORDER BY f.source_id LIMIT 401
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", editionId)
            .param("group", group)
            .query(
                (rs, row) ->
                    new SourceFact(
                        rs.getObject(1, UUID.class),
                        json.decode(rs.getString(2), FinancialFact.class)))
            .list();
    if (sources.size() > 400) {
      throw new BusinessException(
          "ORIGINAL_BASIS_SCOPE_LIMIT",
          409,
          "Связанный финансовый набор превышает проверяемый предел 400 источников");
    }
    var ids = new java.util.HashSet<UUID>();
    StringBuilder fingerprint = new StringBuilder(Long.toString(basisRevision));
    for (var source : sources) {
      if (!ids.add(source.fact().id())) {
        throw new BusinessException(
            "FINANCIAL_SOURCE_CONFLICT",
            409,
            "Один источник опубликован в нескольких основных финансовых наборах");
      }
      fingerprint
          .append('\n')
          .append(source.fact().id())
          .append(':')
          .append(source.fact().revision())
          .append(':')
          .append(source.fact().digest());
    }
    String signature =
        IdempotencyService.sha256(fingerprint.toString().getBytes(StandardCharsets.UTF_8));
    if (captured.isPresent() && !captured.orElseThrow().signature().equals(signature)) {
      throw new BusinessException(
          "ACCOUNTING_SOURCE_CHANGED",
          409,
          "Связанные финансовые источники изменились во время подготовки");
    }
    if (captured.isEmpty()) {
      jdbc.sql(
              """
              INSERT INTO economics_recognition_scope(organization_id,account_id,publication_id,
                scope_key,expected_revision,source_signature,ready)
              SELECT :org,:account,:id,:group,coalesce((SELECT revision FROM economics_recognition_head
                WHERE organization_id=:org AND account_id=:account AND scope_key=:group),0),:signature,false
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("id", editionId)
          .param("group", group)
          .param("signature", signature)
          .update();
    }
    int previousRows = captured.map(CapturedScope::preparedRows).orElse(0);
    ScopeProgress progress;
    if (group.startsWith("SERVICE:")) {
      progress = recognizeServices(scope, editionId, basisRevision, sources, previousRows);
    } else if (group.startsWith("CONSUMPTION:")) {
      progress = recognizeConsumption(scope, editionId, basisRevision, sources, previousRows);
    } else {
      recognizeReturns(scope, editionId, basisRevision, sources);
      progress = new ScopeProgress(true, sources.size(), true);
    }
    jdbc.sql(
            """
            UPDATE economics_recognition_scope SET ready=:ready,prepared_rows=:rows,coverage_complete=:coverage
            WHERE organization_id=:org AND account_id=:account AND publication_id=:id AND scope_key=:group
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", editionId)
        .param("group", group)
        .param("ready", progress.ready())
        .param("rows", previousRows + progress.written())
        .param("coverage", progress.coverageComplete())
        .update();
    return progress;
  }

  private ScopeProgress recognizeServices(
      Scope scope, UUID editionId, long basisRevision, List<SourceFact> sources, int afterPart) {
    if (sources.isEmpty()) {
      return new ScopeProgress(true, 0, true);
    }
    var first = sources.getFirst().fact();
    var proof = first.serviceEvidence();
    var actual = new java.util.ArrayList<SourceFact>();
    SourceFact estimate = null;
    for (var source : sources) {
      var fact = source.fact();
      var evidence = fact.serviceEvidence();
      if (evidence == null
          || fact.serviceCode() == null
          || fact.serviceCode().isBlank()
          || !java.util.Objects.equals(first.serviceCode(), fact.serviceCode())
          || !evidence.quantityUnit().equals(proof.quantityUnit())
          || evidence.scopeQuantity().compareTo(proof.scopeQuantity()) != 0
          || !evidence.incurredOn().equals(proof.incurredOn())
          || evidence.incurredOn().isAfter(fact.accountingDate())) {
        throw serviceConflict("Не подтверждены единица, период или область одной услуги");
      }
      if (evidence.allocation() != null && fact.offerId() != null) {
        throw serviceConflict("Прямая связь услуги не заменяется другим распределением");
      }
      if (evidence.valueKind() == FinancialSourceService.ServiceValueKind.ESTIMATE) {
        if (estimate != null
            || fact.amount() == null
            || fact.amount().signum() < 0
            || evidence.coveredFrom().signum() != 0
            || evidence.coveredUntil().compareTo(evidence.scopeQuantity()) != 0) {
          throw serviceConflict("Нужна одна полная предварительная оценка возникшей услуги");
        }
        estimate = source;
      } else {
        actual.add(source);
      }
    }
    actual.sort(
        java.util.Comparator.comparing(value -> value.fact().serviceEvidence().coveredFrom()));
    BigDecimal until = BigDecimal.ZERO;
    BigDecimal covered = BigDecimal.ZERO;
    for (var source : actual) {
      var coverage = source.fact().serviceEvidence();
      if (coverage.coveredFrom().compareTo(until) < 0) {
        throw serviceConflict("Основные факты услуги имеют пересекающееся покрытие");
      }
      covered = covered.add(coverage.coveredUntil().subtract(coverage.coveredFrom()));
      until = coverage.coveredUntil();
    }
    boolean fullyCovered = covered.compareTo(proof.scopeQuantity()) == 0;
    int position = 0;
    int written = 0;
    for (var source : sources) {
      var fact = source.fact();
      boolean estimated =
          fact.serviceEvidence().valueKind() == FinancialSourceService.ServiceValueKind.ESTIMATE;
      BigDecimal amount =
          estimated
              ? remainingEstimate(fact.amount(), proof.scopeQuantity(), covered)
              : fact.amount();
      boolean confirmed = !estimated || fullyCovered;
      var allocations = serviceAllocations(fact, amount);
      if (position + allocations.size() <= afterPart) {
        position += allocations.size();
        continue;
      }
      for (var part : allocations) {
        if (position++ < afterPart) {
          continue;
        }
        if (written == 200) {
          return new ScopeProgress(false, written, fullyCovered);
        }
        save(
            scope,
            editionId,
            basisRevision,
            source,
            calculator.serviceExpense(part.amount(), confirmed),
            new Declaration(null, null, null, 0, 0),
            part.recipientId());
        written++;
      }
    }
    return new ScopeProgress(true, written, fullyCovered);
  }

  private List<Allocation.Part> serviceAllocations(FinancialFact fact, BigDecimal amount) {
    var proof = fact.serviceEvidence().allocation();
    if (proof == null) {
      return List.of(new Allocation.Part(fact.offerId(), amount));
    }
    var weights =
        proof.targets().stream()
            .map(target -> new Allocation.Weight(target.offerId(), target.weight()))
            .toList();
    var parts = new Allocation().allocate(amount == null ? BigDecimal.ZERO : amount, weights);
    return amount == null
        ? parts.stream().map(part -> new Allocation.Part(part.recipientId(), null)).toList()
        : parts;
  }

  private static BusinessException serviceConflict(String message) {
    return new BusinessException("SERVICE_SCOPE_CONFLICT", 409, message);
  }

  private ScopeProgress recognizeConsumption(
      Scope scope, UUID editionId, long basisRevision, List<SourceFact> sources, int afterPart) {
    if (sources.isEmpty()) {
      return new ScopeProgress(true, 0, true);
    }
    var first = sources.getFirst();
    var movement = first.fact().settlement().consumption();
    var declared = declarations(scope, sources.stream().map(SourceFact::fact).toList());
    SourceFact costSource = null;
    LocalDate issueDate = null;
    for (var source : sources) {
      var fact = source.fact();
      var settlement = fact.settlement();
      var proof = settlement.consumption();
      if (!compensation(fact)
          || settlement.inventoryDisposition()
              != FinancialSourceService.InventoryDisposition.CONSUMED
          || proof.kind() != FinancialSourceService.ConsumptionKind.LOSS_BEFORE_DELIVERY
          || !proof.offerId().equals(movement.offerId())
          || proof.quantity().compareTo(movement.quantity()) != 0
          || !proof.occurredOn().equals(movement.occurredOn())
          || proof.occurredOn().isAfter(fact.accountingDate())
          || (fact.offerId() != null && !fact.offerId().equals(proof.offerId()))
          || (settlement.units() != null && settlement.units().compareTo(proof.quantity()) != 0)) {
        throw consumptionConflict("Доказательства физического расхода противоречат друг другу");
      }
      var costBasis = settlement.costBasis();
      if (costBasis != null) {
        if (costBasis.kind() != FinancialSourceService.CostBasisKind.ISSUE_DATE
            || costBasis.date().isAfter(proof.occurredOn())
            || (issueDate != null && !issueDate.equals(costBasis.date()))) {
          throw consumptionConflict(
              "Для потери до доставки нужна единая подтверждённая дата выпуска");
        }
        issueDate = costBasis.date();
        if (costSource == null) {
          costSource = source;
        }
      }
    }
    int written = 0;
    for (int index = afterPart; index < sources.size(); index++) {
      if (written == 200) {
        return new ScopeProgress(false, written, true);
      }
      var source = sources.get(index);
      var fact = source.fact();
      save(
          scope,
          editionId,
          basisRevision,
          source,
          recognizeCompensation(fact, declared.get(fact.id()), BigDecimal.ZERO),
          declared.get(fact.id()));
      written++;
    }
    if (written == 200) {
      return new ScopeProgress(false, written, true);
    }
    SourceFact anchor = costSource == null ? first : costSource;
    Declaration costDeclaration = declared.get(anchor.fact().id());
    BigDecimal consumed =
        costSource == null ? null : multiply(costDeclaration.unitCost(), movement.quantity());
    var result =
        calculator.calculate(
            new EconomicCalculator.Input(
                EconomicCalculator.Outcome.LOST,
                BigDecimal.ZERO,
                consumed,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of()));
    var basis =
        new Basis(
            anchor.fact().id(),
            "INVENTORY_CONSUMPTION",
            anchor.fact().revision(),
            anchor.fact().digest(),
            movement.offerId(),
            movement.occurredOn());
    save(scope, editionId, basisRevision, anchor, result, costDeclaration, basis);
    return new ScopeProgress(true, written + 1, true);
  }

  private static BusinessException consumptionConflict(String message) {
    return new BusinessException("CONSUMPTION_SOURCE_CONFLICT", 409, message);
  }

  private void recognizeReturns(
      Scope scope, UUID editionId, long basisRevision, List<SourceFact> sources) {
    var declarations = declarations(scope, sources.stream().map(SourceFact::fact).toList());
    List<ReturnAllocation.Original> originals = new java.util.ArrayList<>();
    List<FinancialFact> returns = new java.util.ArrayList<>();
    var sourceIds = new java.util.HashSet<UUID>();
    for (var source : sources) {
      if (!sourceIds.add(source.fact().id())) {
        throw new BusinessException(
            "FINANCIAL_SOURCE_CONFLICT",
            409,
            "Один источник опубликован в нескольких основных финансовых наборах");
      }
      var fact = source.fact();
      if (fact.kind().equals("REVENUE")) {
        var result = recognizeSimple(fact, declarations.get(fact.id()));
        originals.add(new ReturnAllocation.Original(fact, result));
        save(scope, editionId, basisRevision, source, result, declarations.get(fact.id()));
      } else {
        returns.add(fact);
      }
    }
    var returned = new ReturnAllocation().recognize(originals, returns);
    for (var source : sources) {
      var result = returned.get(source.fact().id());
      if (result != null) {
        // Return allocation retains the complete original source set below, not one arbitrary part.
        save(
            scope,
            editionId,
            basisRevision,
            source,
            result,
            new Declaration(null, null, null, 0, 0));
      }
    }
  }

  private void recognizeIndependent(
      Scope scope, UUID editionId, long basisRevision, SourceFact source, Declaration declared) {
    var fact = source.fact();
    EconomicCalculator.Result result;
    if (fact.kind().equals("REFUND") || fact.kind().equals("RETURN_PHYSICAL")) {
      result = new ReturnAllocation().recognize(List.of(), List.of(fact)).get(fact.id());
    } else {
      result = recognizeSimple(fact, declared);
    }
    save(scope, editionId, basisRevision, source, result, declared);
  }

  private EconomicCalculator.Result recognizeSimple(FinancialFact fact, Declaration declared) {
    var source = fact.settlement();
    BigDecimal units = source == null ? null : source.units();
    if (fact.kind().equals("SERVICE")) {
      return calculator.serviceExpense(fact.amount());
    }
    if (fact.kind().equals("PAYMENT")) {
      return calculator.serviceExpense(BigDecimal.ZERO);
    }
    if (fact.kind().equals("REVENUE")) {
      return calculator.calculate(
          new EconomicCalculator.Input(
              EconomicCalculator.Outcome.KEPT_PURCHASE,
              source == null ? null : source.sellerRevenue(),
              multiply(declared.unitCost(), units),
              multiply(declared.unitExpense(), units),
              source == null ? null : source.taxBase(),
              declared.taxRate(),
              List.of()));
    }
    if (compensation(fact)) {
      var disposition =
          source == null
              ? FinancialSourceService.InventoryDisposition.UNKNOWN
              : source.inventoryDisposition();
      BigDecimal consumed;
      if (disposition == FinancialSourceService.InventoryDisposition.NONE
          || disposition == FinancialSourceService.InventoryDisposition.RESTORED) {
        consumed = BigDecimal.ZERO;
      } else {
        consumed = null;
      }
      return recognizeCompensation(fact, declared, consumed);
    }
    throw new BusinessException(
        "FINANCIAL_KIND_UNKNOWN", 409, "Не подтверждено назначение финансового факта");
  }

  private EconomicCalculator.Result recognizeCompensation(
      FinancialFact fact, Declaration declared, BigDecimal consumed) {
    return calculator.calculate(
        new EconomicCalculator.Input(
            EconomicCalculator.Outcome.LOST,
            fact.amount(),
            consumed,
            BigDecimal.ZERO,
            fact.settlement() == null ? null : fact.settlement().taxBase(),
            declared.taxRate(),
            List.of()));
  }

  private static boolean compensation(FinancialFact fact) {
    return fact.kind().equals("COMPENSATION") || fact.kind().equals("COMPENSATION_REVERSAL");
  }

  /** Called under the same account lock as the final publication switch. */
  public void publishRecognition(Scope scope, UUID editionId, UUID previousEdition) {
    boolean stale =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM economics_recognition_scope captured
                  LEFT JOIN economics_recognition_head head ON head.organization_id=captured.organization_id
                    AND head.account_id=captured.account_id
                    AND head.scope_key=captured.scope_key
                  WHERE captured.organization_id=:org AND captured.account_id=:account
                    AND captured.publication_id=:id AND (NOT captured.ready OR (captured.expected_revision<>coalesce(head.revision,0)
                    AND captured.source_signature IS DISTINCT FROM head.source_signature)))
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", editionId)
            .query(Boolean.class)
            .single();
    if (stale) {
      throw new BusinessException(
          "ACCOUNTING_SOURCE_CHANGED",
          409,
          "Связанные исходные продажи или возвраты изменились во время расчёта");
    }
    jdbc.sql(
            """
            UPDATE economics_financial_event e SET current_revision=false
            WHERE e.organization_id=:org AND e.account_id=:account AND e.current_revision
              AND (e.publication_id=:previous OR EXISTS(SELECT 1 FROM economics_financial_event next
                WHERE next.organization_id=e.organization_id AND next.account_id=e.account_id
                  AND next.preparation_id=:id AND next.source_id=e.source_id AND next.component=e.component)
                OR EXISTS(SELECT 1 FROM economics_source_fact f
                  JOIN economics_recognition_scope captured ON captured.organization_id=f.organization_id
                    AND captured.account_id=f.account_id AND captured.scope_key=f.recognition_scope
                    AND captured.publication_id=:id
                  WHERE (f.organization_id,f.account_id,f.publication_id,f.source_id)=
                    (e.organization_id,e.account_id,e.publication_id,e.source_id)))
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", editionId)
        .param("previous", previousEdition)
        .update();
    jdbc.sql(
            """
            UPDATE economics_financial_event SET current_revision=true WHERE organization_id=:org
              AND account_id=:account AND preparation_id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", editionId)
        .update();
    jdbc.sql(
            """
            INSERT INTO economics_recognition_head(organization_id,account_id,scope_key,revision,source_signature,coverage_complete)
            SELECT organization_id,account_id,scope_key,expected_revision+1,source_signature,coverage_complete
              FROM economics_recognition_scope WHERE organization_id=:org AND account_id=:account
                AND publication_id=:id
            ON CONFLICT(organization_id,account_id,scope_key)
              DO UPDATE SET revision=economics_recognition_head.revision+1,source_signature=EXCLUDED.source_signature,
                coverage_complete=EXCLUDED.coverage_complete
              WHERE economics_recognition_head.source_signature<>EXCLUDED.source_signature
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", editionId)
        .update();
  }

  private void save(
      Scope scope,
      UUID preparationId,
      long revision,
      SourceFact source,
      EconomicCalculator.Result result,
      Declaration declared) {
    save(scope, preparationId, revision, source, result, declared, source.fact().offerId());
  }

  private void save(
      Scope scope,
      UUID preparationId,
      long revision,
      SourceFact source,
      EconomicCalculator.Result result,
      Declaration declared,
      UUID recipient) {
    var fact = source.fact();
    String component = component(fact);
    var basis =
        new Basis(
            fact.id(), component, fact.revision(), fact.digest(), recipient, fact.accountingDate());
    save(scope, preparationId, revision, source, result, declared, basis);
  }

  private void save(
      Scope scope,
      UUID preparationId,
      long revision,
      SourceFact source,
      EconomicCalculator.Result result,
      Declaration declared,
      Basis basis) {
    var fact = source.fact();
    jdbc.sql(
            """
            INSERT INTO economics_recognition_guard(organization_id,account_id,source_id,component)
            VALUES(:org,:account,:source,:component) ON CONFLICT DO NOTHING
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("source", fact.id())
        .param("component", basis.component())
        .update();
    var recognition =
        new RecognitionBasis(
            source.publicationId(),
            fact.id(),
            declared.costRevision(),
            declared.taxRevision(),
            declared.unitCost(),
            declared.unitExpense(),
            declared.taxRate());
    BigDecimal units =
        fact.kind().equals("REVENUE") && fact.settlement() != null
            ? fact.settlement().units()
            : null;
    insert(
        scope,
        basis,
        result,
        units,
        source.publicationId(),
        preparationId,
        revision,
        recognition,
        fact.settlement() == null ? null : fact.settlement().originalOrderLineId(),
        fact.kind().equals("PAYMENT") ? fact.amount() : null);
  }

  private static String component(FinancialFact fact) {
    return fact.kind().equals("SERVICE") ? "SERVICE:" + fact.serviceCode() : fact.kind();
  }

  private static String recognitionScope(FinancialFact fact) {
    if (fact.kind().equals("SERVICE") && fact.serviceEvidence() != null) {
      return "SERVICE:" + fact.serviceEvidence().scopeId();
    }
    if (compensation(fact)
        && fact.settlement() != null
        && fact.settlement().consumption() != null) {
      return "CONSUMPTION:" + fact.settlement().consumption().movementId();
    }
    UUID line = linkedLine(fact);
    return line == null ? null : "LINE:" + line;
  }

  private static UUID linkedLine(FinancialFact fact) {
    return (fact.kind().equals("REVENUE")
                || fact.kind().equals("REFUND")
                || fact.kind().equals("RETURN_PHYSICAL"))
            && fact.settlement() != null
        ? fact.settlement().originalOrderLineId()
        : null;
  }

  private Map<UUID, Declaration> declarations(Scope scope, List<FinancialFact> facts) {
    if (facts.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("org", scope.organizationId());
    parameters.put("account", scope.requireAccount());
    List<String> rows = new java.util.ArrayList<>();
    for (int index = 0; index < facts.size(); index++) {
      var fact = facts.get(index);
      rows.add(
          "(CAST(:id"
              + index
              + " AS uuid),CAST(:offer"
              + index
              + " AS uuid),CAST(:day"
              + index
              + " AS date),CAST(:costDay"
              + index
              + " AS date))");
      parameters.put("id" + index, fact.id());
      UUID costOffer = fact.offerId();
      if (compensation(fact)
          && fact.settlement() != null
          && fact.settlement().consumption() != null) {
        costOffer = fact.settlement().consumption().offerId();
      }
      parameters.put("offer" + index, costOffer);
      parameters.put("day" + index, fact.accountingDate());
      var costBasis = fact.settlement() == null ? null : fact.settlement().costBasis();
      if (costBasis != null && costBasis.date().isAfter(fact.accountingDate())) {
        throw new BusinessException(
            "COST_BASIS_DATE_CONFLICT",
            409,
            "Дата исходной выдачи или доставки позже признаваемого финансового факта");
      }
      parameters.put("costDay" + index, costBasis == null ? null : costBasis.date());
    }
    record Declared(UUID id, Declaration value) {}
    var declared =
        jdbc.sql(
                """
                SELECT f.id,c.amount,own.extra_expense,t.rate,coalesce(ch.revision,0),coalesce(th.revision,0)
                FROM (VALUES %s) f(id,offer,day,cost_day)
                LEFT JOIN economics_cost_head ch ON ch.organization_id=:org AND ch.account_id=:account AND ch.offer_id=f.offer
                LEFT JOIN economics_cost_interval c ON (c.organization_id,c.account_id,c.offer_id,c.revision)=
                  (ch.organization_id,ch.account_id,ch.offer_id,ch.revision)
                  AND c.valid_from<=f.cost_day AND (c.valid_until IS NULL OR c.valid_until>f.cost_day)
                LEFT JOIN economics_cost_interval own ON (own.organization_id,own.account_id,own.offer_id,own.revision)=
                  (ch.organization_id,ch.account_id,ch.offer_id,ch.revision)
                  AND own.valid_from<=f.day AND (own.valid_until IS NULL OR own.valid_until>f.day)
                LEFT JOIN economics_tax_head th ON th.organization_id=:org AND th.account_id=:account
                LEFT JOIN economics_tax_interval t ON (t.organization_id,t.account_id,t.revision)=
                  (th.organization_id,th.account_id,th.revision)
                  AND t.valid_from<=f.day AND (t.valid_until IS NULL OR t.valid_until>f.day)
                """
                    .formatted(String.join(",", rows)))
            .params(parameters)
            .query(
                (rs, index) ->
                    new Declared(
                        rs.getObject(1, UUID.class),
                        new Declaration(
                            rs.getBigDecimal(2),
                            rs.getBigDecimal(3),
                            rs.getBigDecimal(4),
                            rs.getLong(5),
                            rs.getLong(6))))
            .list();
    Map<UUID, Declaration> result = new HashMap<>();
    for (var row : declared) {
      if (result.putIfAbsent(row.id(), row.value()) != null) {
        throw new BusinessException(
            "ACCOUNTING_INTERVAL_CONFLICT", 409, "Неоднозначный действующий интервал учёта");
      }
    }
    return result;
  }

  private static BigDecimal multiply(BigDecimal amount, BigDecimal units) {
    return amount == null || units == null ? null : amount.multiply(units);
  }

  private record Basis(
      UUID sourceId,
      String component,
      long sourceRevision,
      String sourceDigest,
      UUID offerId,
      LocalDate accountingDate) {}

  private UUID insert(
      Scope scope,
      Basis basis,
      EconomicCalculator.Result result,
      BigDecimal recognizedUnits,
      UUID publicationId,
      UUID preparationId,
      long accountingRevision,
      RecognitionBasis recognitionBasis,
      UUID originalLineId,
      BigDecimal paymentAmount) {
    var units =
        recognizedUnits == null || !result.complete()
            ? null
            : calculator.perRecognizedUnit(result, recognizedUnits);
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO economics_financial_event(organization_id,account_id,id,source_id,
              component,source_revision,source_digest,offer_id,accounting_date,income,cost,
              expenses,tax,profit,complete,preliminary,current_revision,recognized_units,unit_income,unit_cost,unit_profit,publication_id,preparation_id,accounting_revision,recognition_basis,original_order_line_id,payment_amount)
            VALUES (:org,:account,:id,:source,:component,:revision,:digest,:offer,:date,
              :income,:cost,:expenses,:tax,:profit,:complete,:preliminary,:current,:units,:unitIncome,:unitCost,:unitProfit,:publication,:preparation,:accountingRevision,CAST(:recognitionBasis AS jsonb),:originalLine,:payment)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", id)
        .param("source", basis.sourceId())
        .param("component", basis.component())
        .param("revision", basis.sourceRevision())
        .param("digest", basis.sourceDigest())
        .param("offer", basis.offerId())
        .param("date", basis.accountingDate())
        .param("income", result.sellerRevenue())
        .param("cost", result.costConsumed())
        .param("expenses", result.operatingExpenses())
        .param("tax", result.tax())
        .param("profit", result.profit())
        .param("complete", result.complete())
        .param("preliminary", result.preliminary())
        .param("accountingRevision", accountingRevision)
        .param("recognitionBasis", recognitionBasis == null ? null : json.encode(recognitionBasis))
        .param("originalLine", originalLineId)
        .param("payment", paymentAmount)
        .param("units", recognizedUnits)
        .param("current", publicationId == null)
        .param("publication", publicationId)
        .param("preparation", preparationId)
        .param("unitIncome", units == null ? null : units.income())
        .param("unitCost", units == null ? null : units.cost())
        .param("unitProfit", units == null ? null : units.profit())
        .update();
    return id;
  }

  /** A fact replaces only the quantity of the very same covered service. */
  public BigDecimal remainingEstimate(
      BigDecimal estimatedTotal, BigDecimal estimatedQuantity, BigDecimal factCoveredQuantity) {
    if (estimatedQuantity.signum() <= 0
        || estimatedTotal.signum() < 0
        || factCoveredQuantity.signum() < 0
        || factCoveredQuantity.compareTo(estimatedQuantity) > 0) {
      throw new IllegalArgumentException("Fact coverage is outside the estimated service");
    }
    return EconomicCalculator.divide(
        estimatedTotal.multiply(estimatedQuantity.subtract(factCoveredQuantity)),
        estimatedQuantity);
  }
}
