package ru.oritas.repricer.marketplace;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

/** Immutable whole-period editions; consumers replace a cohort, never add report editions. */
@Service
public final class FinancialSourceService {
  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final JsonCodec json;
  private final StoredFileService files;

  public FinancialSourceService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      JsonCodec json,
      StoredFileService files) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.json = json;
    this.files = files;
  }

  public Publication publication(Scope scope, UUID id) {
    authorization.require(scope, "finance.read");
    var accrual =
        jdbc.sql(
                """
                SELECT *,jsonb_array_length(facts) AS fact_count
                FROM marketplace_financial_observation WHERE id=:id AND kind='ACCRUAL'
                """)
            .param("id", id)
            .query(
                (row, index) ->
                    new Publication(
                        id,
                        row.getObject("accounting_date", LocalDate.class),
                        row.getObject("accounting_date", LocalDate.class).plusDays(1),
                        row.getTimestamp("observed_at").toInstant(),
                        row.getLong("fact_count"),
                        incompleteCoverage(),
                        row.getString("content_hash"),
                        row.getObject("raw_file_id", UUID.class),
                        "OZON_ACCRUAL:" + row.getString("external_id")))
            .optional();
    if (accrual.isPresent()) {
      return accrual.orElseThrow();
    }
    var publication =
        jdbc.sql(
                """
                SELECT * FROM marketplace_report WHERE id=:id
                  AND kind IN ('SERVICES','REALIZATION') AND state='PUBLISHED'
                """)
            .param("id", id)
            .query(
                (row, index) ->
                    new Publication(
                        id,
                        row.getObject("date_from", LocalDate.class),
                        row.getObject("date_until", LocalDate.class).plusDays(1),
                        row.getTimestamp("generation_finished_at").toInstant(),
                        row.getLong("row_count"),
                        Map.of(
                            "SERVICE",
                            row.getString("kind").equals("SERVICES")
                                && row.getBoolean("financial_complete"),
                            "REVENUE",
                            row.getString("kind").equals("REALIZATION")
                                && row.getBoolean("financial_complete"),
                            "REFUND",
                            false,
                            "PAYMENT",
                            false),
                        null,
                        row.getObject("raw_file_id", UUID.class),
                        (row.getString("kind").equals("SERVICES")
                                ? "SERVICES:SERVICE_ACCRUAL_DATE:"
                                : "REALIZATION:"
                                    + row.getString("campaign_id")
                                    + ":DELIVERY_MONTH:")
                            + YearMonth.from(row.getObject("date_from", LocalDate.class))))
            .optional()
            .orElseThrow(
                () ->
                    new BusinessException(
                        "FINANCIAL_PUBLICATION_NOT_FOUND",
                        404,
                        "Полная редакция финансового источника не опубликована"));
    return new Publication(
        publication.id(),
        publication.fromInclusive(),
        publication.untilExclusive(),
        publication.generatedAt(),
        publication.rowCount(),
        publication.completeByComponent(),
        files.get(publication.rawFileId()).sha256(),
        publication.rawFileId(),
        publication.cohortKey());
  }

  public Optional<Publication> latest(Scope scope, YearMonth month, String kind) {
    authorization.require(scope, "finance.read");
    if (month == null || !"SERVICES".equals(kind)) {
      throw new BusinessException(
          "FINANCIAL_SOURCE_UNSUPPORTED", 422, "Некорректный финансовый источник");
    }
    return jdbc.sql(
            """
            SELECT id FROM marketplace_report WHERE state='PUBLISHED' AND kind=:kind
              AND date_from=:from AND date_until=:until
            ORDER BY generation_finished_at DESC,id DESC LIMIT 1
            """)
        .param("kind", kind)
        .param("from", month.atDay(1))
        .param("until", month.atEndOfMonth())
        .query(UUID.class)
        .optional()
        .map(id -> publication(scope, id));
  }

  public List<FinancialFact> page(Scope scope, UUID id, long afterRow, int limit) {
    authorization.require(scope, "finance.read");
    if (afterRow < 0 || limit < 1 || limit > 500) {
      throw new BusinessException(
          "INVALID_FINANCIAL_PAGE", 422, "Некорректная порция финансовых фактов");
    }
    Publication publication = publication(scope, id);
    if (publication.cohortKey().startsWith("OZON_ACCRUAL:")) {
      return jdbc.sql(
              """
              SELECT f.fact::text FROM marketplace_financial_observation o
              CROSS JOIN LATERAL jsonb_array_elements(o.facts) WITH ORDINALITY f(fact,n)
              WHERE o.id=:id AND f.n>:after ORDER BY f.n LIMIT :limit
              """)
          .param("id", id)
          .param("after", afterRow)
          .param("limit", limit)
          .query((row, index) -> json.decode(row.getString(1), FinancialFact.class))
          .list();
    }
    return jdbc.sql(
            """
            SELECT f.fact::text
            FROM marketplace_report_row r
            CROSS JOIN LATERAL jsonb_array_elements(r.canonical_fact)
              WITH ORDINALITY f(fact,component)
            WHERE r.report_id=:id AND r.fact_end>:after
              AND (f.fact->>'rowNumber')::bigint>:after
            ORDER BY r.fact_end,f.component LIMIT :limit
            """)
        .param("id", id)
        .param("after", afterRow)
        .param("limit", limit)
        .query((row, index) -> json.decode(row.getString(1), FinancialFact.class))
        .list();
  }

  /** Latest edition of each campaign cohort, bounded independently of the number of campaigns. */
  public List<UUID> publicationIds(
      Scope scope, YearMonth month, String kind, UUID afterId, int limit) {
    authorization.require(scope, "finance.read");
    if (month == null
        || !List.of("SERVICES", "REALIZATION", "ACCRUALS").contains(kind)
        || limit < 1
        || limit > 100) {
      throw new BusinessException("INVALID_FINANCIAL_PAGE", 422, "Некорректная порция источников");
    }
    if (kind.equals("ACCRUALS")) {
      return jdbc.sql(
              """
              SELECT id FROM (
                SELECT DISTINCT ON(external_id) id,accounting_date
                FROM marketplace_financial_observation WHERE kind='ACCRUAL'
                ORDER BY external_id,revision DESC
              ) latest WHERE accounting_date>=:from AND accounting_date<:until
                AND (CAST(:after AS uuid) IS NULL OR id>:after) ORDER BY id LIMIT :limit
              """)
          .param("from", month.atDay(1))
          .param("until", month.plusMonths(1).atDay(1))
          .param("after", afterId)
          .param("limit", limit)
          .query(UUID.class)
          .list();
    }
    return jdbc.sql(
            """
            SELECT id FROM (
              SELECT DISTINCT ON (COALESCE(campaign_id,'')) id FROM marketplace_report
              WHERE kind=:kind AND state='PUBLISHED' AND date_from=:from AND date_until=:until
              ORDER BY COALESCE(campaign_id,''),generation_finished_at DESC,id DESC
            ) editions WHERE CAST(:after AS uuid) IS NULL OR id>CAST(:after AS uuid)
            ORDER BY id LIMIT :limit
            """)
        .param("kind", kind)
        .param("from", month.atDay(1))
        .param("until", month.atEndOfMonth())
        .param("after", afterId)
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  /** Account coverage is the conjunction of verified editions, never one campaign's flag. */
  public Map<String, Boolean> coverage(Scope scope, YearMonth month) {
    return coverageEvidence(scope, month).completeByComponent();
  }

  public CoverageEvidence coverageEvidence(Scope scope, YearMonth month) {
    authorization.require(scope, "finance.read");
    if (month == null) {
      throw new BusinessException("INVALID_FINANCIAL_PERIOD", 422, "Не указан месяц учёта");
    }
    return jdbc.sql(
            """
              WITH editions AS (
            SELECT DISTINCT ON (kind,COALESCE(campaign_id,'')) id,kind,campaign_id,financial_complete
                FROM marketplace_report WHERE state='PUBLISHED' AND date_from=:from AND date_until=:until
                ORDER BY kind,COALESCE(campaign_id,''),generation_finished_at DESC,id DESC
              ), accruals AS (
                SELECT id FROM (SELECT DISTINCT ON(external_id) id,accounting_date
                  FROM marketplace_financial_observation WHERE kind='ACCRUAL'
                  ORDER BY external_id,revision DESC) latest
                WHERE accounting_date>=:from AND accounting_date<=:until
              ), campaigns AS (SELECT external_id,availability,discovered_run FROM marketplace_campaign)
              SELECT EXISTS(SELECT 1 FROM editions WHERE kind='SERVICES' AND financial_complete) AS services,
                EXISTS(SELECT 1 FROM campaigns)
                AND EXISTS(SELECT 1 FROM marketplace_source WHERE source_type='STOCKS' AND status='READY')
                AND NOT EXISTS(SELECT 1 FROM campaigns c WHERE c.availability<>'AVAILABLE'
                  OR NOT EXISTS(SELECT 1 FROM marketplace_source s WHERE s.source_type='STOCKS'
                    AND s.status='READY' AND s.publication_id=c.discovered_run)
                  OR NOT EXISTS(SELECT 1 FROM editions e WHERE e.kind='REALIZATION'
                AND e.campaign_id=c.external_id AND e.financial_complete)) AS revenues,
            to_json(ARRAY(SELECT id FROM (
              SELECT id FROM editions WHERE kind IN ('SERVICES','REALIZATION')
              UNION ALL SELECT id FROM accruals) publications
              ORDER BY id LIMIT 1001))::text AS publications
            """)
        .param("from", month.atDay(1))
        .param("until", month.atEndOfMonth())
        .query(
            (row, index) ->
                new CoverageEvidence(
                    Map.of(
                        "SERVICE",
                        row.getBoolean("services"),
                        "REVENUE",
                        row.getBoolean("revenues"),
                        "REFUND",
                        false,
                        "COMPENSATION",
                        false,
                        "PAYMENT",
                        false),
                    Set.copyOf(
                        Arrays.asList(json.decode(row.getString("publications"), UUID[].class)))))
        .single();
  }

  /** A validated page publishes identified records; missing records never erase a partial day. */
  UUID publishOzon(
      Scope scope,
      UUID run,
      Instant observedAt,
      UUID raw,
      OzonFinancialCanonicalizer.Observation observation,
      OutboxService outbox) {
    authorization.require(scope, "finance.read");
    jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
        .param("id", scope.requireAccount())
        .query(UUID.class)
        .single();
    String normalized = OzonFinancialCanonicalizer.encode(observation, json);
    String digest = IdempotencyService.sha256(normalized.getBytes(StandardCharsets.UTF_8));
    var previous =
        jdbc.sql(
                """
                SELECT id,revision,observed_at,content_hash FROM marketplace_financial_observation
                WHERE kind=:kind AND external_id=:external ORDER BY revision DESC LIMIT 1
                """)
            .param("kind", observation.kind())
            .param("external", observation.externalId())
            .query(
                (row, index) ->
                    new ObservationHead(
                        row.getObject(1, UUID.class),
                        row.getLong(2),
                        row.getTimestamp(3).toInstant(),
                        row.getString(4)))
            .optional();
    if (previous.isPresent()) {
      ObservationHead old = previous.orElseThrow();
      if (digest.equals(old.digest()) || observedAt.isBefore(old.observedAt())) {
        return old.id();
      }
      if (observedAt.equals(old.observedAt())) {
        throw new BusinessException(
            "FINANCIAL_EDITION_CONFLICT",
            409,
            "Одна редакция источника содержит противоречивые записи");
      }
    }
    UUID id = UUID.randomUUID();
    long revision = previous.map(value -> value.revision() + 1).orElse(1L);
    List<FinancialFact> facts = ozonFacts(scope, id, revision, digest, raw, observation);
    jdbc.sql(
            """
            INSERT INTO marketplace_financial_observation(id,organization_id,account_id,run_id,
              kind,external_id,accounting_date,observed_at,revision,content_hash,normalized,facts,raw_file_id)
            VALUES (:id,:org,:account,:run,:kind,:external,:date,:observed,:revision,:hash,
              CAST(:normalized AS jsonb),CAST(:facts AS jsonb),:raw)
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("run", run)
        .param("kind", observation.kind())
        .param("external", observation.externalId())
        .param("date", observation.date())
        .param("observed", Timestamp.from(observedAt))
        .param("revision", revision)
        .param("hash", digest)
        .param("normalized", normalized)
        .param("facts", json.gson().toJson(facts))
        .param("raw", raw)
        .update();
    files.retain(raw, "financial-observation", id, scope);
    if (observation.kind().equals("ACCRUAL")) {
      outbox.emit(
          scope,
          "ozon-accrual:" + id,
          "source.changed",
          new OutboxService.EntityChange("accruals", id, revision));
    } else if (observation.kind().equals("RETURN")) {
      publishOzonReturn(scope, observation, observedAt, raw, digest);
      outbox.emit(
          scope,
          "ozon-return:" + id,
          "source.changed",
          new OutboxService.EntityChange("returns", id, revision));
    }
    return id;
  }

  private List<FinancialFact> ozonFacts(
      Scope scope,
      UUID publication,
      long revision,
      String digest,
      UUID raw,
      OzonFinancialCanonicalizer.Observation observation) {
    List<OzonFinancialCanonicalizer.Component> revenue =
        observation.components().stream()
            .filter(
                value -> value.meaning().equals("SELLER_REVENUE") && value.currency().equals("RUB"))
            .toList();
    if (revenue.size() > 200) {
      throw new BusinessException(
          "FINANCIAL_COMPONENT_LIMIT", 422, "Слишком много компонентов выручки");
    }
    Map<String, UUID> offers = new HashMap<>();
    if (!revenue.isEmpty()) {
      jdbc.sql(
              """
              SELECT min(id::text)::uuid,vendor_sku FROM marketplace_offer
              WHERE vendor_sku IN (:skus) GROUP BY vendor_sku HAVING count(*)=1
              """)
          .param(
              "skus",
              revenue.stream()
                  .map(OzonFinancialCanonicalizer.Component::vendorSku)
                  .distinct()
                  .toList())
          .query((row, index) -> Map.entry(row.getString(2), row.getObject(1, UUID.class)))
          .list()
          .forEach(value -> offers.put(value.getKey(), value.getValue()));
    }
    List<FinancialFact> facts = new ArrayList<>();
    for (OzonFinancialCanonicalizer.Component value : revenue) {
      long ordinal = facts.size() + 1L;
      facts.add(
          new FinancialFact(
              ordinal,
              UUID.nameUUIDFromBytes(
                  (publication + ":" + ordinal).getBytes(StandardCharsets.UTF_8)),
              revision,
              digest,
              offers.get(value.vendorSku()),
              observation.date(),
              "REVENUE",
              value.amount(),
              null,
              null,
              null,
              raw,
              new SettlementEvidence(
                  null,
                  null,
                  value.amount(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  InventoryDisposition.UNKNOWN,
                  null,
                  null,
                  null),
              null));
    }
    return List.copyOf(facts);
  }

  private void publishOzonReturn(
      Scope scope,
      OzonFinancialCanonicalizer.Observation observation,
      Instant observed,
      UUID raw,
      String digest) {
    var value = observation.returned();
    if (value.returnedAt() == null) {
      return;
    }
    var offer =
        jdbc.sql("SELECT id FROM marketplace_offer WHERE sku=:sku AND vendor_sku=:vendor")
            .param("sku", value.sku())
            .param("vendor", value.vendorSku())
            .query(UUID.class)
            .optional();
    if (offer.isEmpty()) {
      return;
    }
    String external = "OZON:" + observation.externalId();
    jdbc.sql(
            """
            INSERT INTO marketplace_return(id,organization_id,account_id,external_id,offer_id,line_id,
              returned_quantity,returned_at,refund_confirmed,physical_receipt_confirmed,
              source_revision,content_hash,raw_file_id)
            VALUES(:id,:org,:account,:external,:offer,:line,:quantity,:returned,:refund,:physical,
              :revision,:hash,:raw)
            ON CONFLICT(organization_id,account_id,external_id) DO UPDATE SET
              returned_quantity=EXCLUDED.returned_quantity,returned_at=EXCLUDED.returned_at,
              refund_confirmed=EXCLUDED.refund_confirmed,physical_receipt_confirmed=EXCLUDED.physical_receipt_confirmed,
              source_revision=EXCLUDED.source_revision,content_hash=EXCLUDED.content_hash,
              raw_file_id=EXCLUDED.raw_file_id,revision=marketplace_return.revision+1
            WHERE marketplace_return.source_revision::timestamptz<EXCLUDED.source_revision::timestamptz
              AND marketplace_return.content_hash<>EXCLUDED.content_hash
            """)
        .param(
            "id",
            UUID.nameUUIDFromBytes(
                (scope.accountId() + ":return:" + external).getBytes(StandardCharsets.UTF_8)))
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("external", external)
        .param("offer", offer.orElseThrow())
        .param("line", "ozon-return:" + observation.externalId())
        .param("quantity", value.quantity())
        .param("returned", Timestamp.from(value.returnedAt()))
        .param("refund", value.refundConfirmed())
        .param("physical", value.physicalReceiptConfirmed())
        .param("revision", observed.toString())
        .param("hash", digest)
        .param("raw", raw)
        .update();
  }

  private static Map<String, Boolean> incompleteCoverage() {
    return Map.of(
        "SERVICE",
        false,
        "REVENUE",
        false,
        "REFUND",
        false,
        "COMPENSATION",
        false,
        "PAYMENT",
        false);
  }

  private record ObservationHead(UUID id, long revision, Instant observedAt, String digest) {}

  public record CoverageEvidence(
      Map<String, Boolean> completeByComponent, Set<UUID> publicationIds) {
    public CoverageEvidence {
      if (publicationIds.size() > 1000) {
        completeByComponent =
            Map.of(
                "SERVICE",
                false,
                "REVENUE",
                false,
                "REFUND",
                false,
                "COMPENSATION",
                false,
                "PAYMENT",
                false);
        publicationIds = Set.of();
      } else {
        completeByComponent = Map.copyOf(completeByComponent);
        publicationIds = Set.copyOf(publicationIds);
      }
    }
  }

  public record Publication(
      UUID id,
      LocalDate fromInclusive,
      LocalDate untilExclusive,
      Instant generatedAt,
      long rowCount,
      Map<String, Boolean> completeByComponent,
      String digest,
      UUID rawFileId,
      String cohortKey) {}

  /** Kind defines the component; unknown money is null and independent of physical evidence. */
  public record FinancialFact(
      long rowNumber,
      UUID id,
      long revision,
      String digest,
      UUID offerId,
      LocalDate accountingDate,
      String kind,
      BigDecimal amount,
      String serviceCode,
      BigDecimal quantity,
      String orderLineKey,
      UUID rawFileId,
      SettlementEvidence settlement,
      ServiceEvidence serviceEvidence) {}

  /** Coordinates identify proven units of one incurred service, not a guessed quantity match. */
  public record ServiceEvidence(
      UUID scopeId,
      ServiceValueKind valueKind,
      String quantityUnit,
      BigDecimal scopeQuantity,
      BigDecimal coveredFrom,
      BigDecimal coveredUntil,
      LocalDate incurredOn,
      UUID rawFileId,
      AllocationEvidence allocation) {
    public ServiceEvidence {
      Objects.requireNonNull(scopeId);
      Objects.requireNonNull(valueKind);
      Objects.requireNonNull(quantityUnit);
      Objects.requireNonNull(scopeQuantity);
      Objects.requireNonNull(coveredFrom);
      Objects.requireNonNull(coveredUntil);
      Objects.requireNonNull(incurredOn);
      Objects.requireNonNull(rawFileId);
      if (quantityUnit.isBlank()
          || quantityUnit.length() > 64
          || scopeQuantity.signum() <= 0
          || coveredFrom.signum() < 0
          || coveredUntil.compareTo(coveredFrom) <= 0
          || coveredUntil.compareTo(scopeQuantity) > 0) {
        throw new IllegalArgumentException("Invalid proven service coverage");
      }
    }
  }

  public enum ServiceValueKind {
    ACTUAL,
    ESTIMATE
  }

  /** A source-proven basis applies uniformly to this covered quantity, including its remainder. */
  public record AllocationEvidence(String contractId, List<AllocationTarget> targets) {
    public AllocationEvidence {
      Objects.requireNonNull(contractId);
      targets = List.copyOf(targets);
      if (contractId.isBlank()
          || contractId.length() > 128
          || targets.isEmpty()
          || targets.size() > 200) {
        throw new IllegalArgumentException(
            "A proven allocation contract and 1–200 recipients are required");
      }
      var recipients = new java.util.HashSet<UUID>();
      BigDecimal total = BigDecimal.ZERO;
      for (var target : targets) {
        if (!recipients.add(target.offerId())) {
          throw new IllegalArgumentException("Allocation recipients must be unique");
        }
        total = total.add(target.weight());
      }
      if (total.signum() <= 0) {
        throw new IllegalArgumentException("A positive proven allocation basis is required");
      }
    }
  }

  public record AllocationTarget(UUID offerId, BigDecimal weight) {
    public AllocationTarget {
      Objects.requireNonNull(offerId);
      Objects.requireNonNull(weight);
      if (weight.signum() < 0) {
        throw new IllegalArgumentException("Allocation weight cannot be negative");
      }
    }
  }

  /** Null proofs remain unknown; physical receipt alone never establishes a monetary refund. */
  public record SettlementEvidence(
      UUID originalOrderLineId,
      BigDecimal units,
      BigDecimal sellerRevenue,
      BigDecimal taxBase,
      Boolean refundConfirmed,
      Boolean physicalReceiptConfirmed,
      Boolean resalable,
      Boolean stockRestored,
      InventoryDisposition inventoryDisposition,
      UUID returnId,
      CostBasisEvidence costBasis,
      InventoryConsumptionEvidence consumption) {}

  /** Positive proof of one physical loss; compensation money never establishes this movement. */
  public record InventoryConsumptionEvidence(
      UUID movementId,
      ConsumptionKind kind,
      UUID offerId,
      BigDecimal quantity,
      LocalDate occurredOn,
      UUID rawFileId) {
    public InventoryConsumptionEvidence {
      Objects.requireNonNull(movementId);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(offerId);
      Objects.requireNonNull(quantity);
      Objects.requireNonNull(occurredOn);
      Objects.requireNonNull(rawFileId);
      if (quantity.signum() <= 0) {
        throw new IllegalArgumentException("Physical consumption quantity must be positive");
      }
    }
  }

  public enum ConsumptionKind {
    LOSS_BEFORE_DELIVERY
  }

  public record CostBasisEvidence(LocalDate date, CostBasisKind kind, UUID rawFileId) {
    public CostBasisEvidence {
      Objects.requireNonNull(date);
      Objects.requireNonNull(kind);
      Objects.requireNonNull(rawFileId);
    }
  }

  public enum CostBasisKind {
    ISSUE_DATE,
    DELIVERY_FALLBACK
  }

  public enum InventoryDisposition {
    CONSUMED,
    RESTORED,
    NONE,
    UNKNOWN
  }
}
