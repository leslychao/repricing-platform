package ru.oritas.repricer.marketplace;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.SqlReadProjection;
import ru.oritas.repricer.platform.TableExportSource;

/** Canonical upstream evidence. Settlements, physical returns and demand remain separate facts. */
@Service
public final class MarketplaceHistoryService {
  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final MarketplaceReadService reads;
  private final Clock clock;

  public MarketplaceHistoryService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      MarketplaceReadService reads,
      Clock clock) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.reads = reads;
    this.clock = clock;
  }

  public Page<Payment> payments(
      Scope scope,
      int page,
      int size,
      LocalDate from,
      LocalDate until,
      String search,
      String sort,
      String direction) {
    authorization.require(scope, "finance.read");
    HistoryQuery query =
        historyQuery(scope, HistoryTable.PAYMENTS, from, until, search, sort, direction, List.of());
    long total =
        jdbc.sql("SELECT count(*) FROM marketplace_payment" + query.where())
            .params(query.parameters())
            .query(Long.class)
            .single();
    List<Payment> items =
        jdbc.sql(
                "SELECT * FROM marketplace_payment"
                    + query.where()
                    + query.order()
                    + " LIMIT :size OFFSET :offset")
            .params(query.parameters())
            .param("size", size)
            .param("offset", Page.offset(page, size))
            .query(
                (row, index) ->
                    new Payment(
                        row.getObject("id", UUID.class),
                        row.getString("external_id"),
                        row.getTimestamp("occurred_at").toInstant(),
                        row.getBigDecimal("amount"),
                        row.getString("currency"),
                        row.getString("payment_scope"),
                        row.getObject("raw_file_id", UUID.class),
                        row.getLong("revision")))
            .list();
    return new Page<>(items, total, page, size);
  }

  /**
   * A published original order line is one accepted demand, independently of later cancellations.
   */
  public List<OriginalDemand> originalDemand(
      Scope scope, UUID publicationId, UUID afterId, int limit) {
    authorization.require(scope, "finance.read");
    if (publicationId == null || limit < 1 || limit > 500) {
      throw new BusinessException(
          "INVALID_DEMAND_PAGE", 422, "Некорректная порция исходного спроса");
    }
    boolean published =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_sync_run WHERE id=:id AND source_type='HISTORY' AND state='PUBLISHED')
                """)
            .param("id", publicationId)
            .query(Boolean.class)
            .single();
    if (!published) {
      throw new BusinessException(
          "HISTORY_NOT_PUBLISHED", 404, "Полная история заказов не опубликована");
    }
    return jdbc.sql(
            """
            SELECT * FROM marketplace_demand_observation WHERE publication_id=:publication
              AND (CAST(:after AS uuid) IS NULL OR demand_id>CAST(:after AS uuid))
            ORDER BY demand_id LIMIT :limit
            """)
        .param("publication", publicationId)
        .param("after", afterId == null ? null : afterId.toString())
        .param("limit", limit)
        .query(
            (row, index) ->
                new OriginalDemand(
                    row.getObject("demand_id", UUID.class),
                    row.getObject("offer_id", UUID.class),
                    row.getTimestamp("accepted_at").toInstant(),
                    row.getBigDecimal("original_quantity"),
                    row.getObject("raw_file_id", UUID.class)))
        .list();
  }

  /**
   * Current full original-order coverage; stock continuity is deliberately not part of this proof.
   */
  public Map<UUID, DemandCoverage> demandCoverage(Scope scope, Set<UUID> offerIds) {
    authorization.require(scope, "finance.read");
    if (offerIds == null || offerIds.size() > 1000 || offerIds.stream().anyMatch(Objects::isNull)) {
      throw new BusinessException(
          "INVALID_OFFER_BATCH", 422, "Некорректная область исходного спроса");
    }
    if (offerIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, DemandCoverage> result = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT o.id,r.id AS publication,r.started_at,r.completed_at+interval '15 minutes' AS valid_until,
              COALESCE(s.status='READY' AND r.state='PUBLISHED'
                AND r.range_from<=(clock_timestamp() AT TIME ZONE a.timezone)::date
                AND r.range_until>=(clock_timestamp() AT TIME ZONE a.timezone)::date
                AND r.completed_at+interval '15 minutes'>clock_timestamp()
                AND coverage.complete,false) AS complete
            FROM marketplace_offer o JOIN marketplace_account a ON a.id=o.account_id
            LEFT JOIN marketplace_source s ON s.source_type='HISTORY'
            LEFT JOIN marketplace_sync_run r ON r.id=s.publication_id AND r.source_type='HISTORY'
            LEFT JOIN LATERAL (
              SELECT count(*) BETWEEN 1 AND 200 AND bool_and(p.valid_until>clock_timestamp()
                AND h.orders_complete AND h.sources_valid_until>clock_timestamp()
                AND EXISTS(SELECT 1 FROM marketplace_raw_page raw
                  WHERE raw.run_id=r.id AND raw.file_id=h.raw_file_id AND raw.parsed)) AS complete
              FROM (SELECT id,valid_until FROM marketplace_placement WHERE offer_id=o.id
                ORDER BY id LIMIT 201) p
              LEFT JOIN marketplace_history_day h ON h.placement_id=p.id
                AND h.day=(clock_timestamp() AT TIME ZONE a.timezone)::date
            ) coverage ON true
            WHERE o.id IN (:offers)
            """)
        .param("offers", offerIds)
        .query(
            (row, index) ->
                Map.entry(
                    row.getObject("id", UUID.class),
                    new DemandCoverage(
                        row.getBoolean("complete"),
                        instant(row.getTimestamp("started_at")),
                        instant(row.getTimestamp("valid_until")),
                        row.getObject("publication", UUID.class))))
        .list()
        .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
    return Map.copyOf(result);
  }

  public Page<Return> returns(
      Scope scope,
      int page,
      int size,
      LocalDate from,
      LocalDate until,
      String search,
      String sort,
      String direction) {
    authorization.require(scope, "finance.read");
    HistoryQuery query =
        historyQuery(scope, HistoryTable.RETURNS, from, until, search, sort, direction, List.of());
    long total =
        jdbc.sql("SELECT count(*) FROM marketplace_return" + query.where())
            .params(query.parameters())
            .query(Long.class)
            .single();
    List<Return> items =
        jdbc.sql(
                "SELECT * FROM marketplace_return"
                    + query.where()
                    + query.order()
                    + " LIMIT :size OFFSET :offset")
            .params(query.parameters())
            .param("size", size)
            .param("offset", Page.offset(page, size))
            .query(
                (row, index) ->
                    new Return(
                        row.getObject("id", UUID.class),
                        row.getObject("offer_id", UUID.class),
                        row.getString("line_id"),
                        row.getBigDecimal("returned_quantity"),
                        row.getTimestamp("returned_at").toInstant(),
                        row.getObject("refund_confirmed", Boolean.class),
                        row.getBigDecimal("refunded_amount"),
                        row.getObject("physical_receipt_confirmed", Boolean.class),
                        row.getObject("resalable", Boolean.class),
                        row.getObject("stock_confirmed", Boolean.class),
                        row.getObject("raw_file_id", UUID.class),
                        row.getLong("revision")))
            .list();
    return new Page<>(items, total, page, size);
  }

  /** Bounded current inputs for detecting a changed basis before asynchronous recalculation. */
  public SqlReadProjection paceBasisProjection(Scope scope) {
    authorization.require(scope, "finance.read");
    LocalDate yesterday = LocalDate.now(clock.withZone(reads.zoneId(scope))).minusDays(1);
    return new SqlReadProjection(
        """
        SELECT p.id AS placement_id,conditions.regime,
          encode(sha256(convert_to(jsonb_build_object(
          'regime',conditions.regime,'days',days.entries)::text,'UTF8')),'hex')
          AS fingerprint,LEAST(p.valid_until,(c.snapshot->>'validUntil')::timestamptz,
            (t.terms->>'validUntil')::timestamptz) AS valid_until,
          (SELECT min((participation->>'endsAt')::timestamptz)
            FROM jsonb_array_elements(c.snapshot->'currentParticipations') participation)
            AS known_future_change
        FROM marketplace_placement p
        JOIN marketplace_offer o ON o.organization_id=p.organization_id
          AND o.account_id=p.account_id AND o.id=p.offer_id
        LEFT JOIN marketplace_commercial_state c ON c.organization_id=p.organization_id
          AND c.account_id=p.account_id AND c.offer_id=p.offer_id AND c.current
        LEFT JOIN marketplace_economic_terms t ON t.organization_id=p.organization_id
          AND t.account_id=p.account_id AND t.placement_id=p.id AND t.current
        CROSS JOIN LATERAL (
          SELECT encode(sha256(convert_to(jsonb_build_object(
            'price',o.seller_price,'buyerPrice',o.buyer_price,'archived',o.archived,
            'model',p.model,'status',p.status,'available',p.available,
            'targets',c.snapshot->'targetIds','participations',c.snapshot->'currentParticipations',
            'complete',c.snapshot->'complete','segment',t.terms->'segment',
            'buyerField',t.terms->'buyerPriceField','buyerMultiplier',t.terms->'buyerPriceMultiplier',
            'buyerOffset',t.terms->'buyerPriceOffset')::text,'UTF8')),'hex') AS regime
        ) conditions
        LEFT JOIN LATERAL (
          SELECT jsonb_agg(jsonb_build_object('day',h.day,'units',h.ordered_units,
            'complete',h.orders_complete,'continuous',h.stock_continuous,'regime',h.regime,
            'futureChange',h.known_future_change) ORDER BY h.day) AS entries
          FROM marketplace_history_day h WHERE h.organization_id=p.organization_id
            AND h.account_id=p.account_id AND h.placement_id=p.id
            AND h.day BETWEEN :paceFrom AND :paceUntil
        ) days ON true
        WHERE p.organization_id=:paceOrganization AND p.account_id=:paceAccount
        """,
        Map.of(
            "paceOrganization", scope.requireOrganization(),
            "paceAccount", scope.requireAccount(),
            "paceFrom", yesterday.minusDays(13),
            "paceUntil", yesterday));
  }

  public Page<PaceEvidence> paceEvidence(Scope scope, int page, int size, String search) {
    authorization.require(scope, "finance.read");
    if (search == null || search.length() > 200) {
      throw new BusinessException("INVALID_SEARCH", 422, "Некорректный поиск");
    }
    ZoneId zone = reads.zoneId(scope);
    LocalDate yesterday = LocalDate.now(clock.withZone(zone)).minusDays(1);
    long total =
        jdbc.sql(
                """
                SELECT count(*) FROM marketplace_placement p JOIN marketplace_offer o ON o.id=p.offer_id
                WHERE position(lower(:search) in lower(o.sku||' '||o.name))>0
                """)
            .param("search", search)
            .query(Long.class)
            .single();
    List<Placement> placements =
        jdbc.sql(
                """
                SELECT o.id,p.id AS placement_id,o.name FROM marketplace_placement p
                JOIN marketplace_offer o ON o.id=p.offer_id
                WHERE position(lower(:search) in lower(o.sku||' '||o.name))>0
                ORDER BY o.name,o.id,p.id LIMIT :size OFFSET :offset
                """)
            .param("search", search)
            .param("size", size)
            .param("offset", Page.offset(page, size))
            .query(
                (row, index) ->
                    new Placement(
                        row.getObject("id", UUID.class),
                        row.getObject("placement_id", UUID.class),
                        row.getString("name")))
            .list();
    if (placements.isEmpty()) {
      return new Page<>(List.of(), total, page, size);
    }
    var heads = paceBasisProjection(scope);
    Map<UUID, PaceBasis> fingerprints =
        jdbc
            .sql("SELECT * FROM (" + heads.sql() + ") heads WHERE placement_id IN (:placements)")
            .params(heads.parameters())
            .param("placements", placements.stream().map(Placement::id).toList())
            .query(
                (row, index) ->
                    Map.entry(
                        row.getObject("placement_id", UUID.class),
                        new PaceBasis(
                            row.getString("regime"),
                            row.getString("fingerprint"),
                            instant(row.getTimestamp("valid_until")),
                            instant(row.getTimestamp("known_future_change")))))
            .list()
            .stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    List<DayRow> days =
        jdbc.sql(
                """
                SELECT placement_id,day,ordered_units,orders_complete,stock_continuous,regime,
                  sources_valid_until,known_future_change,raw_file_id FROM marketplace_history_day
                WHERE placement_id IN (:ids) AND day>=:from AND day<=:until ORDER BY placement_id,day DESC
                """)
            .param("ids", placements.stream().map(Placement::id).toList())
            .param("from", yesterday.minusDays(13))
            .param("until", yesterday)
            .query(
                (row, index) ->
                    new DayRow(
                        row.getObject("placement_id", UUID.class),
                        new DayEvidence(
                            row.getObject("day", LocalDate.class),
                            row.getBigDecimal("ordered_units"),
                            row.getBoolean("orders_complete"),
                            row.getBoolean("stock_continuous"),
                            row.getString("regime")),
                        instant(row.getTimestamp("sources_valid_until")),
                        instant(row.getTimestamp("known_future_change")),
                        row.getObject("raw_file_id", UUID.class)))
            .list();
    List<PaceEvidence> result = new ArrayList<>(placements.size());
    Map<UUID, List<DayRow>> byPlacement =
        days.stream().collect(Collectors.groupingBy(DayRow::placementId));
    for (Placement placement : placements) {
      List<DayRow> matching = byPlacement.getOrDefault(placement.id(), List.of());
      PaceBasis basis = Objects.requireNonNull(fingerprints.get(placement.id()));
      result.add(
          new PaceEvidence(
              placement.offerId(),
              placement.id(),
              placement.name(),
              basis.regime(),
              matching.stream().map(DayRow::evidence).toList(),
              earliest(
                  basis.validUntil(),
                  matching.stream()
                      .map(DayRow::validUntil)
                      .filter(java.util.Objects::nonNull)
                      .min(Instant::compareTo)
                      .orElse(null)),
              earliest(
                  basis.knownFutureChange(),
                  matching.stream()
                      .map(DayRow::futureChange)
                      .filter(java.util.Objects::nonNull)
                      .min(Instant::compareTo)
                      .orElse(null)),
              matching.stream().map(DayRow::raw).distinct().toList(),
              basis.fingerprint()));
    }
    return new Page<>(result, total, page, size);
  }

  private static Instant earliest(Instant left, Instant right) {
    if (left == null) {
      return right;
    }
    return right == null || left.isBefore(right) ? left : right;
  }

  private Period period(Scope scope, LocalDate from, LocalDate until, String search) {
    if (from == null
        || until == null
        || until.isBefore(from)
        || from.plusDays(366).isBefore(until)
        || search == null
        || search.length() > 200) {
      throw new BusinessException("INVALID_PERIOD", 422, "Период должен быть не больше года");
    }
    ZoneId zone = reads.zoneId(scope);
    return new Period(
        Timestamp.from(from.atStartOfDay(zone).toInstant()),
        Timestamp.from(until.plusDays(1).atStartOfDay(zone).toInstant()));
  }

  TableExportSource.Projection historyProjection(
      Scope scope, HistoryTable table, TableExportSource.Query query, List<UUID> ids) {
    authorization.require(scope, "finance.read");
    if (!query.filters().keySet().equals(Set.of("from", "to"))) {
      throw new BusinessException("INVALID_HISTORY_FILTER", 422, "Укажите период истории");
    }
    String[] sort = query.sort().split(",", -1);
    if (sort.length != 2) {
      throw new BusinessException("INVALID_SORT", 422, "Некорректная сортировка истории");
    }
    LocalDate from;
    LocalDate to;
    try {
      from = LocalDate.parse(query.filters().get("from"));
      to = LocalDate.parse(query.filters().get("to"));
    } catch (DateTimeParseException exception) {
      throw new BusinessException("INVALID_HISTORY_FILTER", 422, "Некорректный период истории");
    }
    HistoryQuery parts =
        historyQuery(scope, table, from, to, query.search(), sort[0], sort[1], ids);
    String cells =
        String.join(",", table.columns.stream().map(column -> column.key() + "::text").toList());
    return new TableExportSource.Projection(
        "SELECT id AS canonical_id,jsonb_build_array("
            + cells
            + ") AS cells FROM "
            + table.table
            + parts.where()
            + parts.order(),
        parts.parameters(),
        table.columns);
  }

  private HistoryQuery historyQuery(
      Scope scope,
      HistoryTable table,
      LocalDate from,
      LocalDate to,
      String search,
      String sort,
      String direction,
      List<UUID> ids) {
    Period period = period(scope, from, to, search);
    String column = table.sorting.get(sort);
    if (column == null
        || !Set.of("asc", "desc").contains(direction)
        || ids.size() > 1000
        || ids.stream().anyMatch(java.util.Objects::isNull)) {
      throw new BusinessException(
          "INVALID_SORT", 422, "Некорректная сортировка или выборка истории");
    }
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("from", period.from());
    parameters.put("until", period.until());
    parameters.put("search", search);
    String predicate =
        " WHERE "
            + table.date
            + ">=:from AND "
            + table.date
            + "<:until"
            + " AND position(lower(:search) in lower("
            + table.name
            + "))>0";
    if (!ids.isEmpty()) {
      predicate += " AND id IN (:ids)";
      parameters.put("ids", ids);
    }
    return new HistoryQuery(
        predicate, " ORDER BY " + column + " " + direction + " NULLS LAST,id", parameters);
  }

  enum HistoryTable {
    PAYMENTS(
        "marketplace_payment",
        "occurred_at",
        "external_id",
        Map.of(
            "id",
            "id",
            "date",
            "occurred_at",
            "name",
            "external_id",
            "amount",
            "amount",
            "scope",
            "payment_scope",
            "source",
            "raw_file_id"),
        List.of(
            new TableExportSource.Column("external_id", "Платёж", false),
            new TableExportSource.Column("occurred_at", "Дата", false),
            new TableExportSource.Column("amount", "Сумма", true),
            new TableExportSource.Column("currency", "Валюта", false),
            new TableExportSource.Column("payment_scope", "Область выплаты", false),
            new TableExportSource.Column("raw_file_id", "Источник", false))),
    RETURNS(
        "marketplace_return",
        "returned_at",
        "line_id",
        Map.of(
            "id",
            "id",
            "date",
            "returned_at",
            "name",
            "line_id",
            "quantity",
            "returned_quantity",
            "amount",
            "refunded_amount",
            "refund",
            "refund_confirmed",
            "receipt",
            "physical_receipt_confirmed",
            "resalable",
            "resalable",
            "stock",
            "stock_confirmed"),
        List.of(
            new TableExportSource.Column("line_id", "Позиция", false),
            new TableExportSource.Column("returned_at", "Дата", false),
            new TableExportSource.Column("returned_quantity", "Количество", true),
            new TableExportSource.Column("refunded_amount", "Возмещено", true),
            new TableExportSource.Column("refund_confirmed", "Возмещение подтверждено", false),
            new TableExportSource.Column(
                "physical_receipt_confirmed", "Приёмка подтверждена", false),
            new TableExportSource.Column("resalable", "Пригоден к продаже", false),
            new TableExportSource.Column("stock_confirmed", "Остаток подтверждён", false)));

    private final String table;
    private final String date;
    private final String name;
    private final Map<String, String> sorting;
    private final List<TableExportSource.Column> columns;

    HistoryTable(
        String table,
        String date,
        String name,
        Map<String, String> sorting,
        List<TableExportSource.Column> columns) {
      this.table = table;
      this.date = date;
      this.name = name;
      this.sorting = sorting;
      this.columns = columns;
    }
  }

  private record HistoryQuery(String where, String order, Map<String, Object> parameters) {}

  private static Instant instant(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  public record Payment(
      UUID id,
      String externalId,
      Instant occurredAt,
      BigDecimal amount,
      String currency,
      String scope,
      UUID rawFileId,
      long revision) {}

  public record Return(
      UUID id,
      UUID offerId,
      String lineId,
      BigDecimal returnedQuantity,
      Instant returnedAt,
      Boolean refundConfirmed,
      BigDecimal refundedAmount,
      Boolean physicalReceiptConfirmed,
      Boolean resalable,
      Boolean stockConfirmed,
      UUID rawFileId,
      long revision) {}

  public record PaceEvidence(
      UUID offerId,
      UUID placementId,
      String offerName,
      String regime,
      List<DayEvidence> days,
      Instant sourcesValidUntil,
      Instant knownFutureChange,
      List<UUID> sourceIds,
      String sourceFingerprint) {
    public PaceEvidence {
      days = List.copyOf(days);
      sourceIds = List.copyOf(sourceIds);
    }
  }

  public record DayEvidence(
      LocalDate date,
      BigDecimal orderedUnits,
      boolean complete,
      boolean continuouslyAvailable,
      String regime) {}

  private record Period(Timestamp from, Timestamp until) {}

  private record Placement(UUID offerId, UUID id, String name) {}

  private record PaceBasis(
      String regime, String fingerprint, Instant validUntil, Instant knownFutureChange) {}

  private record DayRow(
      UUID placementId, DayEvidence evidence, Instant validUntil, Instant futureChange, UUID raw) {}

  public record OriginalDemand(
      UUID id, UUID offerId, Instant acceptedAt, BigDecimal originalQuantity, UUID rawFileId) {}

  public record DemandCoverage(
      boolean complete, Instant observedAt, Instant validUntil, UUID publicationId) {}

  public record HistoryPublication(UUID id, long revision) {}
}
