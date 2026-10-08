package ru.oritas.repricer.economics.accounting;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.marketplace.FinancialSourceService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

/** Replaces a complete factual service cohort atomically, preserving all old source editions. */
@Service
public final class LedgerService {
  public static final int RECOGNITION_VERSION = 3;

  private record Progress(String state, long afterRow) {}

  private record Published(
      UUID id,
      Instant generatedAt,
      String digest,
      long basisRevision,
      UUID sourceId,
      boolean connected,
      boolean recognitionComplete,
      int recognitionVersion) {}

  private final JdbcClient jdbc;
  private final FinancialSourceService sources;
  private final AccountingService accounting;
  private final OutboxService outbox;
  private final StoredFileService files;
  private final AccountingBasisService basis;
  private final JsonCodec json = new JsonCodec();

  public LedgerService(
      JdbcClient jdbc,
      FinancialSourceService sources,
      AccountingService accounting,
      OutboxService outbox,
      StoredFileService files,
      AccountingBasisService basis) {
    this.jdbc = jdbc;
    this.sources = sources;
    this.accounting = accounting;
    this.outbox = outbox;
    this.files = files;
    this.basis = basis;
  }

  public boolean prepareNext(Scope scope, UUID publicationId) {
    var publication = sources.publication(scope, publicationId);
    if ((publication.cohortKey().startsWith("SERVICES:")
            && !Boolean.TRUE.equals(publication.completeByComponent().get("SERVICE")))
        || (!publication.cohortKey().startsWith("SERVICES:")
            && !publication.cohortKey().startsWith("REALIZATION:")
            && !publication.cohortKey().startsWith("OZON_ACCRUAL:"))) {
      throw new BusinessException(
          "SERVICE_COHORT_INCOMPLETE",
          409,
          "Неполный источник не заменяет подтверждённую редакцию расходов");
    }
    long accountingRevision = basis.current(scope);
    UUID editionId =
        UUID.nameUUIDFromBytes(
            (publicationId
                    + ":"
                    + accountingRevision
                    + ":connected-recognition-v"
                    + RECOGNITION_VERSION)
                .getBytes(StandardCharsets.UTF_8));
    int inserted =
        jdbc.sql(
                """
                INSERT INTO economics_ledger_publication(organization_id,account_id,id,cohort_key,
                  generated_at,source_digest,from_day,until_day,expected_rows,processed_rows,state,raw_file_id,source_publication_id,accounting_revision,component_coverage,recognition_version)
                VALUES (:org,:account,:id,:cohort,:generated,:digest,:start,:end,:rows,0,'PREPARING',:raw,:sourcePublication,:accountingRevision,CAST(:coverage AS jsonb),:recognitionVersion)
                ON CONFLICT DO NOTHING
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("id", editionId)
            .param("cohort", publication.cohortKey())
            .param("generated", Timestamp.from(publication.generatedAt()))
            .param("digest", publication.digest())
            .param("start", publication.fromInclusive())
            .param("end", publication.untilExclusive())
            .param("rows", publication.rowCount())
            .param("raw", publication.rawFileId())
            .param("sourcePublication", publicationId)
            .param("accountingRevision", accountingRevision)
            .param("recognitionVersion", RECOGNITION_VERSION)
            .param("coverage", json.encode(publication.completeByComponent()))
            .update();
    if (inserted == 1) {
      files.retain(publication.rawFileId(), "FINANCIAL_LEDGER", editionId, scope);
    }
    var progress =
        jdbc.sql(
                """
                SELECT state,processed_rows FROM economics_ledger_publication WHERE organization_id=:org
                  AND account_id=:account AND id=:id FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", editionId)
            .query((rs, row) -> new Progress(rs.getString(1), rs.getLong(2)))
            .single();
    if (!progress.state().equals("PREPARING")) {
      return true;
    }
    var facts = sources.page(scope, publicationId, progress.afterRow(), 200);
    long last = progress.afterRow();
    var costProofs = new HashSet<UUID>();
    for (var fact : facts) {
      if (fact.rowNumber() != last + 1
          || ((fact.kind().equals("SERVICE") || fact.kind().equals("REVENUE"))
              && (fact.accountingDate().isBefore(publication.fromInclusive())
                  || !fact.accountingDate().isBefore(publication.untilExclusive())))) {
        throw new BusinessException(
            "SERVICE_COHORT_CONFLICT",
            409,
            "Исходник имеет неподтверждённые строки, период или вид финансового факта");
      }
      last = fact.rowNumber();
      if (fact.settlement() != null && fact.settlement().costBasis() != null) {
        UUID raw = fact.settlement().costBasis().rawFileId();
        if (!raw.equals(publication.rawFileId())) {
          costProofs.add(raw);
        }
      }
      if (fact.settlement() != null && fact.settlement().consumption() != null) {
        UUID raw = fact.settlement().consumption().rawFileId();
        if (!raw.equals(publication.rawFileId())) {
          costProofs.add(raw);
        }
      }
      if (fact.serviceEvidence() != null
          && !fact.serviceEvidence().rawFileId().equals(publication.rawFileId())) {
        costProofs.add(fact.serviceEvidence().rawFileId());
      }
    }
    for (UUID raw : costProofs) {
      files.retain(raw, "FINANCIAL_LEDGER", editionId, scope);
    }
    accounting.stageFacts(scope, editionId, facts);
    jdbc.sql(
            """
            UPDATE economics_ledger_publication SET processed_rows=:after WHERE organization_id=:org
              AND account_id=:account AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", editionId)
        .param("after", last)
        .update();
    if (last > publication.rowCount() || (facts.isEmpty() && last != publication.rowCount())) {
      throw new BusinessException(
          "SERVICE_COHORT_INCOMPLETE", 409, "Полное число строк источника не подтверждено");
    }
    if (last != publication.rowCount()) {
      return false;
    }
    if (!accounting.prepareRecognitionNext(scope, editionId, accountingRevision)) {
      return false;
    }
    publish(scope, editionId, accountingRevision, publication);
    return true;
  }

  private void publish(
      Scope scope,
      UUID editionId,
      long accountingRevision,
      FinancialSourceService.Publication publication) {
    if (basis.current(scope) != accountingRevision) {
      throw new BusinessException("ACCOUNTING_BASIS_CHANGED", 409, "Учётное основание изменилось");
    }
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    var previous =
        jdbc.sql(
                """
                SELECT p.id,p.generated_at,p.source_digest,p.accounting_revision,p.source_publication_id,
                    EXISTS(SELECT 1 FROM economics_source_fact e WHERE e.organization_id=p.organization_id
                      AND e.account_id=p.account_id AND e.publication_id=p.id
                      AND (e.recognition_scope IS NOT NULL OR e.component IN ('REVENUE','REFUND','RETURN_PHYSICAL'))),p.recognition_complete,p.recognition_version
                FROM economics_ledger_publication p
                WHERE p.organization_id=:org AND p.account_id=:account AND p.cohort_key=:cohort AND p.state='PUBLISHED'
                FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("cohort", publication.cohortKey())
            .query(
                (rs, row) ->
                    new Published(
                        rs.getObject(1, UUID.class),
                        rs.getTimestamp(2).toInstant(),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getObject(5, UUID.class),
                        rs.getBoolean(6),
                        rs.getBoolean(7),
                        rs.getInt(8)))
            .optional();
    if (previous.isPresent()) {
      Published old = previous.orElseThrow();
      if (old.recognitionVersion() > RECOGNITION_VERSION) {
        throw new BusinessException(
            "RECOGNITION_VERSION_UNSUPPORTED", 409, "Расчёт опубликован более новой версией учёта");
      }
      if (old.generatedAt().equals(publication.generatedAt())
          && !old.digest().equals(publication.digest())) {
        throw new BusinessException(
            "SERVICE_EDITION_CONFLICT", 409, "Одновременные источники содержат разные суммы");
      }
      if (publication.generatedAt().isBefore(old.generatedAt())
          || (old.digest().equals(publication.digest())
              && old.basisRevision() >= accountingRevision
              && old.recognitionComplete()
              && old.recognitionVersion() == RECOGNITION_VERSION)) {
        state(scope, editionId, "OBSOLETE");
        return;
      }
      state(scope, old.id(), "SUPERSEDED");
    }
    accounting.publishRecognition(scope, editionId, previous.map(Published::id).orElse(null));
    state(scope, editionId, "PUBLISHED");
    boolean newOriginalSource =
        previous.isEmpty() || !previous.orElseThrow().sourceId().equals(publication.id());
    if (newOriginalSource
        && (previous.map(Published::connected).orElse(false)
            || jdbc.sql(
                    """
                    SELECT EXISTS(SELECT 1 FROM economics_source_fact WHERE organization_id=:org
                      AND account_id=:account AND publication_id=:id
                      AND (recognition_scope IS NOT NULL OR component IN ('REVENUE','REFUND','RETURN_PHYSICAL')))
                    """)
                .param("org", scope.organizationId())
                .param("account", scope.accountId())
                .param("id", editionId)
                .query(Boolean.class)
                .single())) {
      // Invalidate unfinished builds that captured the previous connected source set. The linked
      // financial scope above already switches atomically; other cohorts rebuild separately.
      basis.changed(scope);
    }
    outbox.emit(
        scope,
        "LEDGER_PUBLICATION:" + editionId,
        "FINANCIAL_FACTS_PUBLISHED",
        new OutboxService.EntityChange("economics", editionId, 1));
  }

  public List<UUID> activeSources(Scope scope, UUID afterId) {
    return jdbc.sql(
            """
            SELECT DISTINCT source_publication_id FROM economics_ledger_publication
            WHERE organization_id=:org AND account_id=:account AND state IN ('PREPARING','PUBLISHED')
              AND (CAST(:after AS uuid) IS NULL OR source_publication_id>:after)
            ORDER BY source_publication_id LIMIT 100
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("after", afterId)
        .query(UUID.class)
        .list();
  }

  private void state(Scope scope, UUID id, String state) {
    jdbc.sql(
            """
            UPDATE economics_ledger_publication SET state=:state WHERE organization_id=:org
              AND account_id=:account AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", id)
        .param("state", state)
        .update();
  }
}
