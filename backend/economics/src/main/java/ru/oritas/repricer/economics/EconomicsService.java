package ru.oritas.repricer.economics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.economics.accounting.AccountingBasisService;
import ru.oritas.repricer.economics.accounting.AccountingService;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.SqlReadProjection;
import ru.oritas.repricer.platform.TableExportSource;

/** Owns publication of seller-declared cost and tax timelines. Called by durable input jobs. */
@Service
public class EconomicsService {
  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final OutboxService outbox;
  private final AccountingBasisService accountingBasis;

  public EconomicsService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      AuditService audit,
      OutboxService outbox,
      AccountingBasisService accountingBasis) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.audit = audit;
    this.outbox = outbox;
    this.accountingBasis = accountingBasis;
  }

  public record CostInput(
      UUID offerId,
      BigDecimal amount,
      BigDecimal extraExpense,
      LocalDate validFrom,
      LocalDate validUntil,
      long expectedRevision) {
    public CostInput {
      if (offerId == null
          || amount == null
          || extraExpense == null
          || validFrom == null
          || amount.signum() < 0
          || extraExpense.signum() < 0
          || expectedRevision < 0
          || (validUntil != null && !validUntil.isAfter(validFrom))) {
        throw new IllegalArgumentException("Invalid cost interval");
      }
      exactMoney(amount);
      exactMoney(extraExpense);
    }
  }

  public record CostInterval(
      LocalDate validFrom, LocalDate validUntil, BigDecimal amount, BigDecimal extraExpense) {}

  public record CostView(UUID offerId, long revision, List<CostInterval> intervals) {
    public CostView {
      intervals = List.copyOf(intervals);
    }
  }

  public record TaxInput(
      BigDecimal rate, LocalDate validFrom, LocalDate validUntil, long expectedRevision) {
    public TaxInput {
      if (rate == null
          || validFrom == null
          || rate.signum() < 0
          || rate.compareTo(BigDecimal.ONE) >= 0
          || rate.scale() > 18
          || expectedRevision < 0
          || (validUntil != null && !validUntil.isAfter(validFrom))) {
        throw new IllegalArgumentException("Invalid tax interval");
      }
    }
  }

  public record TaxInterval(LocalDate validFrom, LocalDate validUntil, BigDecimal rate) {}

  public record TaxView(long revision, List<TaxInterval> intervals) {
    public TaxView {
      intervals = List.copyOf(intervals);
    }
  }

  public record CostRow(
      UUID offerId,
      String sku,
      String name,
      long revision,
      LocalDate validFrom,
      LocalDate validUntil,
      BigDecimal amount,
      BigDecimal extraExpense) {}

  public record FinancialRow(
      UUID id,
      UUID sourceId,
      String component,
      long sourceRevision,
      UUID offerId,
      LocalDate accountingDate,
      BigDecimal income,
      BigDecimal cost,
      BigDecimal expenses,
      BigDecimal tax,
      BigDecimal profit,
      boolean complete,
      String certainty,
      String status) {}

  public record CostRevision(
      UUID id,
      UUID offerId,
      long revision,
      LocalDate validFrom,
      LocalDate validUntil,
      BigDecimal amount,
      BigDecimal extraExpense,
      UUID authorId,
      java.time.Instant recordedAt) {}

  public Page<CostRevision> costHistory(Scope scope, UUID offerId, int page, int size) {
    return costHistory(scope, offerId, page, size, "", "createdAt", "desc", null, null);
  }

  public Page<CostRevision> costHistory(
      Scope scope,
      UUID offerId,
      int page,
      int size,
      String search,
      String sort,
      String direction,
      LocalDate from,
      LocalDate to) {
    authorization.require(scope, "finance.read");
    long offset = Page.offset(page, size);
    Map<String, String> filters = dateFilters(from, to);
    if (offerId != null) {
      filters.put("offerId", offerId.toString());
    }
    ReadSelection selection =
        costSelection(
            scope,
            new TableExportSource.Query(search, filters, sort + "," + direction, page, size),
            List.of());
    long total =
        jdbc.sql("SELECT count(*) " + selection.source())
            .params(selection.parameters())
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                "SELECT * "
                    + selection.source()
                    + " ORDER BY "
                    + selection.ordering()
                    + ",id LIMIT :size"
                    + " OFFSET :offset")
            .params(selection.parameters())
            .param("size", size)
            .param("offset", offset)
            .query(
                (rs, row) ->
                    new CostRevision(
                        rs.getObject("id", UUID.class),
                        rs.getObject("offer_id", UUID.class),
                        rs.getLong("revision"),
                        rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_until", LocalDate.class),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("extra_expense"),
                        rs.getObject("author_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()))
            .list();
    return new Page<>(items, total, page, size);
  }

  /** Current immutable cost interval, using the account's accounting date and half-open bounds. */
  public SqlReadProjection currentCostProjection(Scope scope) {
    authorization.require(scope, "finance.read");
    return new SqlReadProjection(
        """
        SELECT h.offer_id,h.revision,i.amount,i.extra_expense,i.valid_from,i.valid_until
        FROM economics_cost_head h JOIN economics_cost_interval i
          ON (i.organization_id,i.account_id,i.offer_id,i.revision)=
            (h.organization_id,h.account_id,h.offer_id,h.revision)
        JOIN marketplace_account a ON (a.organization_id,a.id)=(h.organization_id,h.account_id)
        WHERE h.organization_id=:costOrganization AND h.account_id=:costAccount
          AND i.valid_from<=(CURRENT_TIMESTAMP AT TIME ZONE a.timezone)::date
          AND (i.valid_until IS NULL OR
            i.valid_until>(CURRENT_TIMESTAMP AT TIME ZONE a.timezone)::date)
        """,
        Map.of(
            "costOrganization",
            scope.requireOrganization(),
            "costAccount",
            scope.requireAccount()));
  }

  public Page<CostRow> costPage(
      Scope scope, int page, int size, String search, UUID offerId, String sort, String direction) {
    authorization.require(scope, "finance.read");
    long offset = Page.offset(page, size);
    if (search == null || search.length() > 200) {
      throw new IllegalArgumentException("Invalid cost search");
    }
    String order =
        switch (sort) {
          case "", "sku" -> "o.sku";
          case "id", "offerId" -> "h.offer_id";
          case "name" -> "o.name";
          case "amount" -> "i.amount";
          case "validFrom" -> "i.valid_from";
          default -> throw new IllegalArgumentException("Invalid cost sort");
        };
    String ordering = ordering(direction);
    String source =
        """
        FROM economics_cost_head h JOIN economics_cost_interval i
          ON (i.organization_id,i.account_id,i.offer_id,i.revision)=
             (h.organization_id,h.account_id,h.offer_id,h.revision)
        JOIN marketplace_offer o ON (o.organization_id,o.account_id,o.id)=
          (h.organization_id,h.account_id,h.offer_id)
        WHERE h.organization_id=:org AND h.account_id=:account
          AND (CAST(:offer AS uuid) IS NULL OR h.offer_id=:offer)
          AND position(lower(:search) in lower(o.sku||' '||o.name))>0
        """;
    long total =
        jdbc.sql("SELECT count(*) " + source)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("offer", offerId)
            .param("search", search)
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                "SELECT h.offer_id,o.sku,o.name,h.revision,i.* "
                    + source
                    + " ORDER BY "
                    + order
                    + ordering
                    + ",h.offer_id,i.valid_from LIMIT :size OFFSET :offset")
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("offer", offerId)
            .param("search", search)
            .param("size", size)
            .param("offset", offset)
            .query(
                (rs, row) ->
                    new CostRow(
                        rs.getObject("offer_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("name"),
                        rs.getLong("revision"),
                        rs.getObject("valid_from", LocalDate.class),
                        rs.getObject("valid_until", LocalDate.class),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("extra_expense")))
            .list();
    return new Page<>(items, total, page, size);
  }

  public Page<FinancialRow> financialPage(
      Scope scope,
      int page,
      int size,
      LocalDate from,
      LocalDate until,
      UUID offerId,
      String component) {
    return financialPage(
        scope, page, size, from, until, offerId, component, "", "", "date", "desc");
  }

  public Page<FinancialRow> financialPage(
      Scope scope,
      int page,
      int size,
      LocalDate from,
      LocalDate until,
      UUID offerId,
      String component,
      String search,
      String status,
      String sort,
      String direction) {
    authorization.require(scope, "finance.read");
    long offset = Page.offset(page, size);
    period(from, until);
    if (component == null || component.length() > 128) {
      throw new IllegalArgumentException("Invalid accounting component");
    }
    Map<String, String> filters = dateFilters(from, until.minusDays(1));
    filters.put("component", component);
    filters.put("status", status);
    if (offerId != null) {
      filters.put("offerId", offerId.toString());
    }
    ReadSelection selection =
        financialSelection(
            scope,
            new TableExportSource.Query(search, filters, sort + "," + direction, page, size),
            List.of());
    long total =
        jdbc.sql("SELECT count(*) " + selection.source())
            .params(selection.parameters())
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                "SELECT *,"
                    + AccountingService.CERTAINTY_SQL
                    + " AS certainty,"
                    + AccountingService.STATUS_SQL
                    + " AS status "
                    + selection.source()
                    + " ORDER BY "
                    + selection.ordering()
                    + ",id LIMIT :size OFFSET :offset")
            .params(selection.parameters())
            .param("size", size)
            .param("offset", offset)
            .query(
                (rs, row) ->
                    new FinancialRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("source_id", UUID.class),
                        rs.getString("component"),
                        rs.getLong("source_revision"),
                        rs.getObject("offer_id", UUID.class),
                        rs.getObject("accounting_date", LocalDate.class),
                        rs.getBigDecimal("income"),
                        rs.getBigDecimal("cost"),
                        rs.getBigDecimal("expenses"),
                        rs.getBigDecimal("tax"),
                        rs.getBigDecimal("profit"),
                        rs.getBoolean("complete") && rs.getObject("preliminary") != null,
                        rs.getString("certainty"),
                        rs.getString("status")))
            .list();
    return new Page<>(items, total, page, size);
  }

  private record ReadSelection(String source, String ordering, Map<String, Object> parameters) {}

  private ReadSelection costSelection(Scope scope, TableExportSource.Query query, List<UUID> ids) {
    authorization.require(scope, "finance.read");
    if (!Set.of("from", "to", "offerId").containsAll(query.filters().keySet())) {
      throw new IllegalArgumentException("Unsupported cost history filter");
    }
    Map<String, Object> parameters = readParameters(scope, query);
    String source =
        """
        FROM (SELECT c.*,md5(c.organization_id::text||':'||c.account_id::text||':'||
          c.offer_id::text||':'||c.revision::text||':'||c.valid_from::text)::uuid AS id
          FROM economics_cost_interval c) item
        WHERE organization_id=:org AND account_id=:account
          AND position(lower(:search) in lower(offer_id::text))>0
        """
            + readFilters(query, parameters, "valid_from", ids);
    String[] sort = query.sort().split(",", -1);
    String field =
        switch (sort[0]) {
          case "id", "" -> "id";
          case "name", "offerId" -> "offer_id";
          case "amount", "extraExpense" -> "extra_expense";
          case "cost" -> "amount";
          case "date", "validFrom" -> "valid_from";
          case "until", "validUntil" -> "valid_until";
          case "revision" -> "revision";
          case "source", "authorId" -> "author_id";
          case "createdAt" -> "created_at";
          default -> throw new IllegalArgumentException("Unsupported cost history sort");
        };
    return new ReadSelection(source, field + queryDirection(sort) + " NULLS LAST", parameters);
  }

  private ReadSelection financialSelection(
      Scope scope, TableExportSource.Query query, List<UUID> ids) {
    authorization.require(scope, "finance.read");
    if (!Set.of("from", "to", "offerId", "component", "status")
        .containsAll(query.filters().keySet())) {
      throw new IllegalArgumentException("Unsupported financial filter");
    }
    Map<String, Object> parameters = readParameters(scope, query);
    String source =
        """
        FROM economics_financial_event WHERE organization_id=:org AND account_id=:account
          AND current_revision
          AND position(lower(:search) in lower(component||' '||COALESCE(offer_id::text,'')
            ||' '||source_id::text))>0
        """
            + readFilters(query, parameters, "accounting_date", ids);
    String status = query.filters().getOrDefault("status", "");
    if (!status.isEmpty()) {
      if (!Set.of("CONFIRMED", "INCOMPLETE", "PRELIMINARY", "UNKNOWN").contains(status)) {
        throw new IllegalArgumentException("Unsupported financial status");
      }
      parameters.put("status", status);
      source += " AND (" + AccountingService.STATUS_SQL + ")=:status";
    }
    String component = query.filters().getOrDefault("component", "");
    if (!component.isEmpty()) {
      parameters.put("component", component);
      source += " AND component=:component";
    }
    String[] sort = query.sort().split(",", -1);
    String field =
        switch (sort[0]) {
          case "id", "" -> "id";
          case "date", "accountingDate", "occurredAt" -> "accounting_date";
          case "name", "component" -> "component";
          case "income", "cost", "expenses", "tax", "profit" -> sort[0];
          case "status" -> "(" + AccountingService.STATUS_SQL + ")";
          case "source" -> "source_id";
          default -> throw new IllegalArgumentException("Unsupported financial sort");
        };
    return new ReadSelection(source, field + queryDirection(sort) + " NULLS LAST", parameters);
  }

  private static Map<String, Object> readParameters(Scope scope, TableExportSource.Query query) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("org", scope.requireOrganization());
    values.put("account", scope.requireAccount());
    values.put("search", query.search());
    return values;
  }

  private static String readFilters(
      TableExportSource.Query query,
      Map<String, Object> parameters,
      String dateColumn,
      List<UUID> ids) {
    StringBuilder filter = new StringBuilder();
    LocalDate from = null;
    LocalDate to = null;
    if (query.filters().containsKey("from")) {
      from = LocalDate.parse(query.filters().get("from"));
      parameters.put("from", from);
      filter.append(" AND ").append(dateColumn).append(">=:from");
    }
    if (query.filters().containsKey("to")) {
      to = LocalDate.parse(query.filters().get("to"));
      parameters.put("to", to);
      filter.append(" AND ").append(dateColumn).append("<=:to");
    }
    if (from != null && to != null && to.isBefore(from)) {
      throw new IllegalArgumentException("Invalid date range");
    }
    if (query.filters().containsKey("offerId")) {
      parameters.put("offer", UUID.fromString(query.filters().get("offerId")));
      filter.append(" AND offer_id=:offer");
    }
    if (!ids.isEmpty()) {
      if (ids.size() > 1000) {
        throw new IllegalArgumentException("At most 1000 selected rows");
      }
      parameters.put("ids", ids);
      filter.append(" AND id IN (:ids)");
    }
    return filter.toString();
  }

  private static Map<String, String> dateFilters(LocalDate from, LocalDate to) {
    Map<String, String> filters = new LinkedHashMap<>();
    if (from != null) {
      filters.put("from", from.toString());
    }
    if (to != null) {
      filters.put("to", to.toString());
    }
    return filters;
  }

  private static String queryDirection(String[] sort) {
    if (sort.length > 2) {
      throw new IllegalArgumentException("One canonical sort is required");
    }
    return ordering(sort.length == 2 ? sort[1] : "asc");
  }

  @Bean
  TableExportSource sellerCostHistoryExport() {
    return new TableExportSource() {
      @Override
      public String resource() {
        return "seller-costs/revisions";
      }

      @Override
      public Set<String> permissions() {
        return Set.of("finance.read");
      }

      @Override
      public Projection projection(Scope scope, Query query, List<UUID> ids) {
        ReadSelection selection = costSelection(scope, query, ids);
        return new Projection(
            """
            SELECT id AS canonical_id,jsonb_build_array(offer_id::text,extra_expense::text,
              amount::text,valid_from::text,valid_until::text,revision::text,author_id::text) AS cells
            """
                + selection.source()
                + " ORDER BY "
                + selection.ordering()
                + ",id",
            selection.parameters(),
            List.of(
                new Column("name", "Товар", false),
                new Column("amount", "Собственные расходы / единица", true),
                new Column("cost", "Себестоимость / единица", true),
                new Column("date", "Действует с", false),
                new Column("until", "Действует до", false),
                new Column("revision", "Редакция", true),
                new Column("source", "Автор", false)));
      }
    };
  }

  @Bean
  public TableExportSource financialEventsExport() {
    return new TableExportSource() {
      @Override
      public String resource() {
        return "economics/accruals";
      }

      @Override
      public Set<String> permissions() {
        return Set.of("finance.read");
      }

      @Override
      public Projection projection(Scope scope, Query query, List<UUID> ids) {
        ReadSelection selection = financialSelection(scope, query, ids);
        return new Projection(
            """
            SELECT id AS canonical_id,jsonb_build_array(accounting_date::text,component,
              income::text,cost::text,expenses::text,tax::text,profit::text,
              %s,%s,%s) AS cells
            """
                    .formatted(
                        AccountingService.STATUS_SQL,
                        AccountingService.COMPLETENESS_SQL,
                        AccountingService.CERTAINTY_SQL)
                + selection.source()
                + " ORDER BY "
                + selection.ordering()
                + ",id",
            selection.parameters(),
            List.of(
                new Column("date", "Учётная дата", false),
                new Column("name", "Компонент", false),
                new Column("income", "Доход", true),
                new Column("cost", "Себестоимость", true),
                new Column("expenses", "Расходы", true),
                new Column("tax", "Налог", true),
                new Column("profit", "Результат", true),
                new Column("status", "Статус", false),
                new Column("complete", "Полнота сумм", false),
                new Column("certainty", "Основание сумм", false)));
      }
    };
  }

  private static void period(LocalDate from, LocalDate until) {
    if (from == null
        || until == null
        || !until.isAfter(from)
        || until.isAfter(from.plusYears(10))) {
      throw new IllegalArgumentException("An explicit bounded accounting period is required");
    }
  }

  private static String ordering(String direction) {
    return switch (direction) {
      case "", "asc" -> " ASC";
      case "desc" -> " DESC";
      default -> throw new IllegalArgumentException("Invalid sort direction");
    };
  }

  public List<CostView> listCosts(Scope scope, int limit, int offset) {
    authorization.require(scope, "finance.read");
    bounds(limit, offset);
    List<UUID> offers =
        jdbc.sql(
                """
                SELECT offer_id FROM economics_cost_head
                WHERE organization_id=:org AND account_id=:account ORDER BY offer_id
                LIMIT :limit OFFSET :offset
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("limit", limit)
            .param("offset", offset)
            .query(UUID.class)
            .list();
    if (offers.isEmpty()) {
      return List.of();
    }
    record Row(UUID offerId, long revision, CostInterval interval) {}
    List<Row> rows =
        jdbc.sql(
                """
                SELECT h.offer_id,h.revision,i.valid_from,i.valid_until,i.amount,i.extra_expense
                FROM economics_cost_head h JOIN economics_cost_interval i
                  ON (i.organization_id,i.account_id,i.offer_id,i.revision)=
                     (h.organization_id,h.account_id,h.offer_id,h.revision)
                WHERE h.organization_id=:org AND h.account_id=:account AND h.offer_id IN (:offers)
                ORDER BY h.offer_id,i.valid_from
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("offers", offers)
            .query(
                (rs, row) ->
                    new Row(
                        rs.getObject(1, UUID.class),
                        rs.getLong(2),
                        new CostInterval(
                            rs.getObject(3, LocalDate.class),
                            rs.getObject(4, LocalDate.class),
                            rs.getBigDecimal(5),
                            rs.getBigDecimal(6))))
            .list();
    List<CostView> result = new ArrayList<>();
    UUID current = null;
    long revision = 0;
    List<CostInterval> intervals = new ArrayList<>();
    for (Row row : rows) {
      if (!row.offerId().equals(current)) {
        if (current != null) {
          result.add(new CostView(current, revision, intervals));
        }
        current = row.offerId();
        revision = row.revision();
        intervals = new ArrayList<>();
      }
      intervals.add(row.interval());
    }
    if (current != null) {
      result.add(new CostView(current, revision, intervals));
    }
    return List.copyOf(result);
  }

  public CostView getCost(Scope scope, UUID offerId) {
    authorization.require(scope, "finance.read");
    return readCost(scope, offerId, false);
  }

  @Transactional
  public CostView publishCost(Scope scope, CostInput input) {
    authorization.require(scope, "finance.write");
    lockInputs(scope);
    jdbc.sql(
            """
            INSERT INTO economics_cost_head(organization_id,account_id,offer_id,revision)
            VALUES (:org,:account,:offer,0) ON CONFLICT DO NOTHING
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("offer", input.offerId())
        .update();
    CostView current = readCost(scope, input.offerId(), true);
    if (current.revision() != input.expectedRevision()) {
      throw new IllegalStateException("STALE_REVISION");
    }
    CostInterval replacement =
        new CostInterval(
            input.validFrom(), input.validUntil(), input.amount(), input.extraExpense());
    List<CostInterval> revised = replace(current.intervals(), replacement);
    if (equivalent(revised, current.intervals())) {
      return current;
    }
    long revision = current.revision() + 1;
    for (CostInterval interval : revised) {
      jdbc.sql(
              """
              INSERT INTO economics_cost_interval
              (organization_id,account_id,offer_id,revision,valid_from,valid_until,amount,
               extra_expense,author_id)
              VALUES (:org,:account,:offer,:revision,:start,:end,:amount,:extra,:author)
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("offer", input.offerId())
          .param("revision", revision)
          .param("start", interval.validFrom())
          .param("end", interval.validUntil())
          .param("amount", interval.amount())
          .param("extra", interval.extraExpense())
          .param("author", scope.subjectId())
          .update();
    }
    jdbc.sql(
            """
            UPDATE economics_cost_head SET revision=:revision
            WHERE organization_id=:org AND account_id=:account AND offer_id=:offer
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("offer", input.offerId())
        .param("revision", revision)
        .update();
    changed(scope, "COST", input.offerId(), revision);
    return new CostView(input.offerId(), revision, revised);
  }

  @Transactional
  public TaxView publishTax(Scope scope, TaxInput input) {
    authorization.require(scope, "finance.write");
    lockInputs(scope);
    jdbc.sql(
            """
            INSERT INTO economics_tax_head(organization_id,account_id,revision)
            VALUES (:org,:account,0) ON CONFLICT DO NOTHING
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .update();
    long current =
        jdbc.sql(
                """
                SELECT revision FROM economics_tax_head
                WHERE organization_id=:org AND account_id=:account FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .query(Long.class)
            .single();
    if (current != input.expectedRevision()) {
      throw new IllegalStateException("STALE_REVISION");
    }
    List<CostInterval> old =
        readTax(scope, current).stream()
            .map(i -> new CostInterval(i.validFrom(), i.validUntil(), i.rate(), BigDecimal.ZERO))
            .toList();
    List<CostInterval> revised =
        replace(
            old,
            new CostInterval(input.validFrom(), input.validUntil(), input.rate(), BigDecimal.ZERO));
    if (equivalent(revised, old)) {
      return new TaxView(current, readTax(scope, current));
    }
    long revision = current + 1;
    for (CostInterval interval : revised) {
      jdbc.sql(
              """
              INSERT INTO economics_tax_interval
              (organization_id,account_id,revision,valid_from,valid_until,rate,author_id)
              VALUES (:org,:account,:revision,:start,:end,:rate,:author)
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("revision", revision)
          .param("start", interval.validFrom())
          .param("end", interval.validUntil())
          .param("rate", interval.amount())
          .param("author", scope.subjectId())
          .update();
    }
    jdbc.sql(
            """
            UPDATE economics_tax_head SET revision=:revision
            WHERE organization_id=:org AND account_id=:account
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("revision", revision)
        .update();
    changed(scope, "TAX", scope.accountId(), revision);
    return new TaxView(revision, readTax(scope, revision));
  }

  public TaxView getTax(Scope scope) {
    authorization.require(scope, "finance.read");
    long revision =
        jdbc.sql(
                """
                SELECT revision FROM economics_tax_head
                WHERE organization_id=:org AND account_id=:account
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .query(Long.class)
            .optional()
            .orElse(0L);
    return new TaxView(revision, readTax(scope, revision));
  }

  private CostView readCost(Scope scope, UUID offerId, boolean lock) {
    long revision =
        jdbc.sql(
                """
                SELECT revision FROM economics_cost_head
                WHERE organization_id=:org AND account_id=:account AND offer_id=:offer
                """
                    + (lock ? " FOR UPDATE" : ""))
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("offer", offerId)
            .query(Long.class)
            .optional()
            .orElse(0L);
    List<CostInterval> intervals =
        jdbc.sql(
                """
                SELECT valid_from,valid_until,amount,extra_expense FROM economics_cost_interval
                WHERE organization_id=:org AND account_id=:account AND offer_id=:offer
                  AND revision=:revision ORDER BY valid_from
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("offer", offerId)
            .param("revision", revision)
            .query(
                (rs, row) ->
                    new CostInterval(
                        rs.getObject(1, LocalDate.class),
                        rs.getObject(2, LocalDate.class),
                        rs.getBigDecimal(3),
                        rs.getBigDecimal(4)))
            .list();
    return new CostView(offerId, revision, intervals);
  }

  private List<TaxInterval> readTax(Scope scope, long revision) {
    return jdbc.sql(
            """
            SELECT valid_from,valid_until,rate FROM economics_tax_interval
            WHERE organization_id=:org AND account_id=:account AND revision=:revision
            ORDER BY valid_from
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("revision", revision)
        .query(
            (rs, row) ->
                new TaxInterval(
                    rs.getObject(1, LocalDate.class),
                    rs.getObject(2, LocalDate.class),
                    rs.getBigDecimal(3)))
        .list();
  }

  static List<CostInterval> replace(List<CostInterval> old, CostInterval replacement) {
    List<CostInterval> revised = new ArrayList<>();
    for (CostInterval interval : old) {
      if ((interval.validUntil() != null && !interval.validUntil().isAfter(replacement.validFrom()))
          || (replacement.validUntil() != null
              && !replacement.validUntil().isAfter(interval.validFrom()))) {
        revised.add(interval);
        continue;
      }
      if (interval.validFrom().isBefore(replacement.validFrom())) {
        revised.add(
            new CostInterval(
                interval.validFrom(),
                replacement.validFrom(),
                interval.amount(),
                interval.extraExpense()));
      }
      if (replacement.validUntil() != null
          && (interval.validUntil() == null
              || interval.validUntil().isAfter(replacement.validUntil()))) {
        revised.add(
            new CostInterval(
                replacement.validUntil(),
                interval.validUntil(),
                interval.amount(),
                interval.extraExpense()));
      }
    }
    revised.add(replacement);
    revised.sort(Comparator.comparing(CostInterval::validFrom));
    List<CostInterval> merged = new ArrayList<>();
    for (CostInterval interval : revised) {
      if (!merged.isEmpty()) {
        CostInterval previous = merged.getLast();
        if (interval.validFrom().equals(previous.validUntil())
            && interval.amount().compareTo(previous.amount()) == 0
            && interval.extraExpense().compareTo(previous.extraExpense()) == 0) {
          merged.set(
              merged.size() - 1,
              new CostInterval(
                  previous.validFrom(),
                  interval.validUntil(),
                  previous.amount(),
                  previous.extraExpense()));
          continue;
        }
      }
      merged.add(interval);
    }
    return List.copyOf(merged);
  }

  private void changed(Scope scope, String kind, UUID objectId, long revision) {
    accountingBasis.changed(scope);
    audit.recordForOffer(
        scope,
        kind + "_PUBLISHED",
        objectId,
        kind.equals("COST") ? objectId : null,
        "revision=" + revision);
    outbox.emit(
        scope,
        kind + ":" + objectId + ":" + revision,
        kind + "_PUBLISHED",
        new OutboxService.EntityChange(kind.equals("COST") ? "costs" : "tax", objectId, revision));
  }

  private static boolean equivalent(List<CostInterval> first, List<CostInterval> second) {
    if (first.size() != second.size()) {
      return false;
    }
    for (int i = 0; i < first.size(); i++) {
      CostInterval a = first.get(i);
      CostInterval b = second.get(i);
      if (!a.validFrom().equals(b.validFrom())
          || !java.util.Objects.equals(a.validUntil(), b.validUntil())
          || a.amount().compareTo(b.amount()) != 0
          || a.extraExpense().compareTo(b.extraExpense()) != 0) {
        return false;
      }
    }
    return true;
  }

  private static void exactMoney(BigDecimal amount) {
    if (amount.scale() > 12 || amount.precision() - amount.scale() > 26) {
      throw new IllegalArgumentException("Amount exceeds numeric(38,12)");
    }
  }

  private void lockInputs(Scope scope) {
    jdbc.sql(
            """
            SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR UPDATE
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
  }

  private static void bounds(int limit, int offset) {
    if (limit < 1 || limit > 200 || offset < 0) {
      throw new IllegalArgumentException("Invalid page");
    }
  }
}
