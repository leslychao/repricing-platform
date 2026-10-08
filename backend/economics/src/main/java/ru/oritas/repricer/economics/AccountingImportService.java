package ru.oritas.repricer.economics;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.economics.accounting.AccountingBasisService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** Bounded staging and preparation; only commit publishes the complete set of immutable scales. */
@Service
public class AccountingImportService {
  public enum Kind {
    COSTS,
    SELLER_COSTS,
    TAX,
    PRICE_PARAMETERS
  }

  public record StagedCost(
      UUID offerId,
      LocalDate validFrom,
      LocalDate validUntil,
      BigDecimal amount,
      BigDecimal extraExpense,
      long expectedRevision) {
    public StagedCost {
      if (offerId == null
          || validFrom == null
          || expectedRevision < 0
          || (amount == null && extraExpense == null)
          || (validUntil != null && !validUntil.isAfter(validFrom))
          || (amount != null && !money(amount))
          || (extraExpense != null && !money(extraExpense))) {
        throw new IllegalArgumentException("Invalid imported cost interval");
      }
    }
  }

  public record Receipt(UUID id, Kind kind, String state, long rows, long targets) {}

  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final OutboxService outbox;
  private final AccountingBasisService accountingBasis;

  public AccountingImportService(
      JdbcClient jdbc,
      NamedParameterJdbcTemplate batch,
      AuthorizationService authorization,
      AuditService audit,
      OutboxService outbox,
      AccountingBasisService accountingBasis) {
    this.jdbc = jdbc;
    this.batch = batch;
    this.authorization = authorization;
    this.audit = audit;
    this.outbox = outbox;
    this.accountingBasis = accountingBasis;
  }

  @Transactional
  public Receipt begin(Scope scope, UUID id, Kind kind) {
    authorization.require(scope, "finance.write");
    sql(
            scope,
            id,
            """
            INSERT INTO economics_import(organization_id,account_id,id,kind,state,author_id)
            VALUES (:org,:account,:set,:kind,'STAGING',:author) ON CONFLICT DO NOTHING
            """)
        .param("kind", kind.name())
        .param("author", scope.subjectId())
        .update();
    Receipt receipt = read(scope, id, false);
    if (receipt.kind() != kind) {
      throw invalid("IMPORT_TYPE_CONFLICT");
    }
    return receipt;
  }

  @Transactional
  public void stageCosts(Scope scope, UUID id, List<StagedCost> rows) {
    authorization.require(scope, "finance.write");
    Receipt receipt = read(scope, id, true);
    if (!receipt.state().equals("STAGING") || receipt.kind() == Kind.TAX) {
      throw invalid("IMPORT_NOT_STAGING");
    }
    bounded(rows.size());
    List<MapSqlParameterSource> parameters = new ArrayList<>(rows.size());
    for (StagedCost row : rows) {
      if ((receipt.kind() == Kind.COSTS && row.amount() == null)
          || (receipt.kind() == Kind.SELLER_COSTS && row.extraExpense() == null)) {
        throw invalid("IMPORT_VALUE_REQUIRED");
      }
      parameters.add(
          parameters(
              scope,
              id,
              row.offerId(),
              row.validFrom(),
              row.validUntil(),
              row.amount(),
              row.extraExpense(),
              null,
              row.expectedRevision()));
    }
    stage(scope, id, parameters);
  }

  @Transactional
  public void stageTax(Scope scope, UUID id, List<EconomicsService.TaxInput> rows) {
    authorization.require(scope, "finance.write");
    Receipt receipt = read(scope, id, true);
    if (!receipt.state().equals("STAGING") || receipt.kind() != Kind.TAX) {
      throw invalid("IMPORT_NOT_STAGING");
    }
    bounded(rows.size());
    List<MapSqlParameterSource> parameters = new ArrayList<>(rows.size());
    for (var row : rows) {
      parameters.add(
          parameters(
              scope,
              id,
              scope.accountId(),
              row.validFrom(),
              row.validUntil(),
              null,
              null,
              row.rate(),
              row.expectedRevision()));
    }
    stage(scope, id, parameters);
  }

  @Transactional
  public Receipt seal(Scope scope, UUID id) {
    authorization.require(scope, "finance.write");
    Receipt receipt = read(scope, id, true);
    if (!receipt.state().equals("STAGING")) {
      return receipt;
    }
    if (receipt.rows() < 1 || receipt.rows() > 100000) {
      throw invalid("IMPORT_ROW_LIMIT");
    }
    boolean invalid =
        sql(
                scope,
                id,
                """
                SELECT EXISTS(SELECT 1 FROM (
                  SELECT target_id,valid_from,valid_until,expected_revision,
                    lead(valid_from) OVER(PARTITION BY target_id ORDER BY valid_from) next_start,
                    min(expected_revision) OVER(PARTITION BY target_id) minimum_revision,
                    max(expected_revision) OVER(PARTITION BY target_id) maximum_revision
                  FROM economics_import_row WHERE organization_id=:org AND account_id=:account AND import_id=:set
                ) intervals WHERE minimum_revision<>maximum_revision OR
                  (next_start IS NOT NULL AND (valid_until IS NULL OR valid_until>next_start)))
                """)
            .query(Boolean.class)
            .single();
    if (invalid) {
      throw invalid("OVERLAPPING_IMPORT_INTERVALS");
    }
    sql(
            scope,
            id,
            """
            UPDATE economics_import SET state='PREPARING'
            WHERE organization_id=:org AND account_id=:account AND id=:set
            """)
        .update();
    return read(scope, id, false);
  }

  /** Call in separate short transactions until true; a prepared target is immutable. */
  @Transactional
  public boolean prepareNext(Scope scope, UUID id) {
    authorization.require(scope, "finance.write");
    Receipt receipt = read(scope, id, true);
    if (Set.of("PREPARED", "APPLIED").contains(receipt.state())) {
      return true;
    }
    if (!receipt.state().equals("PREPARING")) {
      throw invalid("IMPORT_NOT_PREPARING");
    }
    List<UUID> targets =
        sql(
                scope,
                id,
                """
                SELECT DISTINCT r.target_id FROM economics_import_row r
                WHERE r.organization_id=:org AND r.account_id=:account AND r.import_id=:set
                  AND NOT EXISTS(SELECT 1 FROM economics_import_target p
                    WHERE (p.organization_id,p.account_id,p.import_id,p.target_id)=
                      (r.organization_id,r.account_id,r.import_id,r.target_id))
                ORDER BY r.target_id LIMIT 500
                """)
            .query(UUID.class)
            .list();
    if (targets.isEmpty()) {
      sql(
              scope,
              id,
              """
              UPDATE economics_import SET state='PREPARED'
              WHERE organization_id=:org AND account_id=:account AND id=:set
              """)
          .update();
      return true;
    }
    boolean tax = receipt.kind() == Kind.TAX;
    String old = oldValues(tax);
    String head = tax ? "economics_tax_head" : "economics_cost_head";
    String target = tax ? "h.account_id" : "h.offer_id";
    boolean stale =
        sql(
                scope,
                id,
                """
                SELECT EXISTS(SELECT 1 FROM economics_import_row r LEFT JOIN
                """
                    + head
                    + " h ON h.organization_id=r.organization_id AND h.account_id=r.account_id AND "
                    + target
                    + "=r.target_id WHERE r.organization_id=:org AND r.account_id=:account"
                    + " AND r.import_id=:set AND r.target_id IN (:targets)"
                    + " AND r.expected_revision<>COALESCE(h.revision,0))")
            .param("targets", targets)
            .query(Boolean.class)
            .single();
    if (stale) {
      throw invalid("STALE_REVISION");
    }
    sql(
            scope,
            id,
            """
            WITH old AS (
            """
                + old
                + """
                ), incoming AS (SELECT * FROM economics_import_row WHERE organization_id=:org
                  AND account_id=:account AND import_id=:set AND target_id IN (:targets)),
                boundaries AS (SELECT target_id,valid_from boundary FROM old
                  UNION SELECT target_id,valid_until FROM old WHERE valid_until IS NOT NULL
                  UNION SELECT target_id,valid_from FROM incoming
                  UNION SELECT target_id,valid_until FROM incoming WHERE valid_until IS NOT NULL),
                segments AS (SELECT target_id,boundary valid_from,
                  lead(boundary) OVER(PARTITION BY target_id ORDER BY boundary) valid_until FROM boundaries),
                valued AS (SELECT s.target_id,s.valid_from,s.valid_until,
                  COALESCE(n.amount,o.amount) amount,COALESCE(n.extra_expense,o.extra_expense) extra_expense,
                  COALESCE(n.rate,o.rate) rate
                FROM segments s LEFT JOIN old o ON o.target_id=s.target_id
                  AND s.valid_from>=o.valid_from AND (o.valid_until IS NULL OR s.valid_from<o.valid_until)
                LEFT JOIN incoming n ON n.target_id=s.target_id AND s.valid_from>=n.valid_from
                  AND (n.valid_until IS NULL OR s.valid_from<n.valid_until)
                WHERE o.target_id IS NOT NULL OR n.target_id IS NOT NULL),
                changed AS (SELECT *,CASE WHEN lag(valid_until) OVER w IS DISTINCT FROM valid_from
                  OR lag(amount) OVER w IS DISTINCT FROM amount
                  OR lag(extra_expense) OVER w IS DISTINCT FROM extra_expense
                  OR lag(rate) OVER w IS DISTINCT FROM rate THEN 1 ELSE 0 END boundary
                  FROM valued WINDOW w AS (PARTITION BY target_id ORDER BY valid_from)),
                grouped AS (SELECT *,sum(boundary) OVER(PARTITION BY target_id ORDER BY valid_from) island
                  FROM changed)
                INSERT INTO economics_import_prepared(organization_id,account_id,import_id,target_id,
                  valid_from,valid_until,amount,extra_expense,rate)
                SELECT :org,:account,:set,target_id,min(valid_from),
                  CASE WHEN bool_or(valid_until IS NULL) THEN NULL ELSE max(valid_until) END,
                  min(amount),min(extra_expense),min(rate) FROM grouped GROUP BY target_id,island
                """)
        .param("targets", targets)
        .update();
    boolean missing =
        sql(
                scope,
                id,
                """
                SELECT EXISTS(SELECT 1 FROM economics_import_prepared WHERE organization_id=:org
                  AND account_id=:account AND import_id=:set AND target_id IN (:targets) AND
                """
                    + (tax ? "rate IS NULL)" : "(amount IS NULL OR extra_expense IS NULL))"))
            .param("targets", targets)
            .query(Boolean.class)
            .single();
    if (missing) {
      throw invalid("IMPORT_PREVIOUS_VALUE_REQUIRED");
    }
    sql(
            scope,
            id,
            """
            WITH old AS (
            """
                + old
                + """
                ), differences AS (
                  (SELECT target_id,valid_from,valid_until,amount,extra_expense,rate FROM old
                   EXCEPT SELECT target_id,valid_from,valid_until,amount,extra_expense,rate
                     FROM economics_import_prepared WHERE organization_id=:org AND account_id=:account
                       AND import_id=:set AND target_id IN (:targets))
                  UNION ALL
                  (SELECT target_id,valid_from,valid_until,amount,extra_expense,rate
                     FROM economics_import_prepared WHERE organization_id=:org AND account_id=:account
                       AND import_id=:set AND target_id IN (:targets)
                   EXCEPT SELECT target_id,valid_from,valid_until,amount,extra_expense,rate FROM old))
                INSERT INTO economics_import_target(organization_id,account_id,import_id,target_id,
                  expected_revision,changed)
                SELECT :org,:account,:set,r.target_id,min(r.expected_revision),
                  EXISTS(SELECT 1 FROM differences d WHERE d.target_id=r.target_id)
                FROM economics_import_row r WHERE r.organization_id=:org AND r.account_id=:account
                  AND r.import_id=:set AND r.target_id IN (:targets) GROUP BY r.target_id
                """)
        .param("targets", targets)
        .update();
    return false;
  }

  @Transactional
  public Receipt commit(Scope scope, UUID id) {
    authorization.requireLocked(scope, Set.of("finance.write"));
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    Receipt receipt = read(scope, id, true);
    if (receipt.state().equals("APPLIED")) {
      return receipt;
    }
    if (!receipt.state().equals("PREPARED")) {
      throw invalid("IMPORT_NOT_PREPARED");
    }
    boolean tax = receipt.kind() == Kind.TAX;
    String head = tax ? "economics_tax_head" : "economics_cost_head";
    String target = tax ? "account_id" : "offer_id";
    String headColumns =
        tax
            ? "organization_id,account_id,revision"
            : "organization_id,account_id,offer_id,revision";
    sql(
            scope,
            id,
            "INSERT INTO "
                + head
                + "("
                + headColumns
                + ") SELECT :org,:account,"
                + (tax ? "" : "target_id,")
                + "0 FROM economics_import_target WHERE organization_id=:org"
                + " AND account_id=:account AND import_id=:set ON CONFLICT DO NOTHING")
        .update();
    boolean stale =
        sql(
                scope,
                id,
                "SELECT EXISTS(SELECT 1 FROM economics_import_target p JOIN "
                    + head
                    + " h ON h.organization_id=p.organization_id AND h.account_id=p.account_id"
                    + " AND h."
                    + target
                    + "=p.target_id WHERE p.organization_id=:org AND p.account_id=:account"
                    + " AND p.import_id=:set AND p.expected_revision<>h.revision)")
            .query(Boolean.class)
            .single();
    if (stale) {
      throw invalid("STALE_REVISION");
    }
    String interval = tax ? "economics_tax_interval" : "economics_cost_interval";
    String columns =
        tax
            ? "organization_id,account_id,revision,valid_from,valid_until,rate,author_id"
            : "organization_id,account_id,offer_id,revision,valid_from,valid_until,amount,extra_expense,author_id";
    sql(
            scope,
            id,
            "INSERT INTO "
                + interval
                + "("
                + columns
                + ") SELECT :org,:account,"
                + (tax ? "" : "p.target_id,")
                + "t.expected_revision+1,p.valid_from,p.valid_until,"
                + (tax ? "p.rate" : "p.amount,p.extra_expense")
                + ",:author FROM economics_import_prepared p JOIN economics_import_target t ON"
                + " (t.organization_id,t.account_id,t.import_id,t.target_id)=(p.organization_id,p.account_id,p.import_id,p.target_id)"
                + " WHERE p.organization_id=:org AND p.account_id=:account AND p.import_id=:set AND"
                + " t.changed")
        .param("author", scope.subjectId())
        .update();
    int changedHeads =
        sql(
                scope,
                id,
                "UPDATE "
                    + head
                    + " h SET revision=t.expected_revision+1 FROM economics_import_target t WHERE"
                    + " t.organization_id=:org AND t.account_id=:account AND t.import_id=:set AND"
                    + " t.changed AND h.organization_id=t.organization_id AND"
                    + " h.account_id=t.account_id AND h."
                    + target
                    + "=t.target_id")
            .update();
    if (changedHeads > 0) {
      accountingBasis.changed(scope);
    }
    sql(
            scope,
            id,
            """
            UPDATE economics_import SET state='APPLIED',applied_at=clock_timestamp()
            WHERE organization_id=:org AND account_id=:account AND id=:set
            """)
        .update();
    audit.record(scope, "ACCOUNTING_IMPORT_APPLIED", id, "rows=" + receipt.rows());
    outbox.emit(
        scope,
        "ACCOUNTING_IMPORT:" + id,
        "ECONOMICS_INPUT_PUBLISHED",
        new OutboxService.EntityChange(tax ? "tax" : "costs", id, 1));
    return read(scope, id, false);
  }

  public Receipt get(Scope scope, UUID id) {
    authorization.require(scope, "finance.read");
    return read(scope, id, false);
  }

  public Map<UUID, Long> capturedRevisions(Scope scope, UUID id, List<UUID> targets) {
    authorization.require(scope, "finance.write");
    bounded(targets.size());
    boolean tax = read(scope, id, false).kind() == Kind.TAX;
    String head = tax ? "economics_tax_head" : "economics_cost_head";
    String column = tax ? "account_id" : "offer_id";
    record Revision(UUID target, long revision) {}
    List<Revision> revisions =
        sql(
                scope,
                id,
                "SELECT h."
                    + column
                    + " target_id,h.revision FROM "
                    + head
                    + " h WHERE h.organization_id=:org AND h.account_id=:account AND h."
                    + column
                    + " IN (:targets)")
            .param("targets", targets)
            .query((rs, row) -> new Revision(rs.getObject(1, UUID.class), rs.getLong(2)))
            .list();
    Map<UUID, Long> result = new HashMap<>();
    targets.forEach(target -> result.put(target, 0L));
    revisions.forEach(revision -> result.put(revision.target(), revision.revision()));
    sql(
            scope,
            id,
            """
            SELECT target_id,min(expected_revision) FROM economics_import_row
            WHERE organization_id=:org AND account_id=:account AND import_id=:set
              AND target_id IN (:targets) GROUP BY target_id
            """)
        .param("targets", targets)
        .query((rs, row) -> new Revision(rs.getObject(1, UUID.class), rs.getLong(2)))
        .list()
        .forEach(revision -> result.put(revision.target(), revision.revision()));
    return Map.copyOf(result);
  }

  public void lockForPublication(Scope scope) {
    authorization.requireLocked(scope, Set.of("finance.write"));
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
  }

  private void stage(Scope scope, UUID id, List<MapSqlParameterSource> rows) {
    List<String> hashes = rows.stream().map(r -> r.getValue("hash").toString()).distinct().toList();
    if (hashes.size() != rows.size()) {
      throw invalid("DUPLICATE_IMPORT_ROW");
    }
    batch.batchUpdate(
        """
        INSERT INTO economics_import_row(organization_id,account_id,import_id,target_id,valid_from,
          valid_until,amount,extra_expense,rate,expected_revision,body_hash)
        VALUES (:org,:account,:set,:target,:start,:end,:amount,:extra,:rate,:revision,:hash)
        ON CONFLICT DO NOTHING
        """,
        rows.toArray(MapSqlParameterSource[]::new));
    long matching =
        sql(
                scope,
                id,
                """
                SELECT count(*) FROM economics_import_row WHERE organization_id=:org AND account_id=:account
                  AND import_id=:set AND body_hash IN (:hashes)
                """)
            .param("hashes", hashes)
            .query(Long.class)
            .single();
    if (matching != hashes.size()) {
      throw invalid("CONFLICTING_IMPORT_ROW");
    }
  }

  private MapSqlParameterSource parameters(
      Scope scope,
      UUID id,
      UUID target,
      LocalDate from,
      LocalDate until,
      BigDecimal amount,
      BigDecimal extra,
      BigDecimal rate,
      long revision) {
    String basis =
        target + "|" + from + "|" + until + "|" + amount + "|" + extra + "|" + rate + "|"
            + revision;
    return new MapSqlParameterSource()
        .addValue("org", scope.organizationId())
        .addValue("account", scope.requireAccount())
        .addValue("set", id)
        .addValue("target", target)
        .addValue("start", from)
        .addValue("end", until)
        .addValue("amount", amount)
        .addValue("extra", extra)
        .addValue("rate", rate)
        .addValue("revision", revision)
        .addValue("hash", hash(basis));
  }

  private Receipt read(Scope scope, UUID id, boolean lock) {
    return sql(
            scope,
            id,
            """
            SELECT i.*,(SELECT count(*) FROM economics_import_row r WHERE r.organization_id=i.organization_id
              AND r.account_id=i.account_id AND r.import_id=i.id) row_count,
              (SELECT count(*) FROM economics_import_target t WHERE t.organization_id=i.organization_id
              AND t.account_id=i.account_id AND t.import_id=i.id) target_count
            FROM economics_import i WHERE organization_id=:org AND account_id=:account AND id=:set
            """
                + (lock ? " FOR UPDATE OF i" : ""))
        .query(
            (rs, row) ->
                new Receipt(
                    id,
                    Kind.valueOf(rs.getString("kind")),
                    rs.getString("state"),
                    rs.getLong("row_count"),
                    rs.getLong("target_count")))
        .optional()
        .orElseThrow(() -> invalid("IMPORT_NOT_FOUND"));
  }

  private JdbcClient.StatementSpec sql(Scope scope, UUID id, String sql) {
    return jdbc.sql(sql)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("set", id);
  }

  private static String oldValues(boolean tax) {
    return tax
        ? """
        SELECT h.account_id target_id,i.valid_from,i.valid_until,NULL::numeric amount,
          NULL::numeric extra_expense,i.rate FROM economics_tax_head h JOIN economics_tax_interval i
          ON (i.organization_id,i.account_id,i.revision)=(h.organization_id,h.account_id,h.revision)
        WHERE h.organization_id=:org AND h.account_id=:account AND h.account_id IN (:targets)
        """
        : """
        SELECT h.offer_id target_id,i.valid_from,i.valid_until,i.amount,i.extra_expense,NULL::numeric rate
        FROM economics_cost_head h JOIN economics_cost_interval i
          ON (i.organization_id,i.account_id,i.offer_id,i.revision)=
            (h.organization_id,h.account_id,h.offer_id,h.revision)
        WHERE h.organization_id=:org AND h.account_id=:account AND h.offer_id IN (:targets)
        """;
  }

  private static boolean money(BigDecimal value) {
    return value.signum() >= 0 && value.scale() <= 12 && value.precision() - value.scale() <= 26;
  }

  private static void bounded(int rows) {
    if (rows < 1 || rows > 1000) {
      throw new IllegalArgumentException("Import batches contain 1..1000 rows");
    }
  }

  private static String hash(String value) {
    return IdempotencyService.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static BusinessException invalid(String code) {
    return new BusinessException(code, 409, "Импорт не может быть опубликован: " + code);
  }
}
