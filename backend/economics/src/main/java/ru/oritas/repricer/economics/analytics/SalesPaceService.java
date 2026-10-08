package ru.oritas.repricer.economics.analytics;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.economics.ResourceService;
import ru.oritas.repricer.marketplace.MarketplaceHistoryService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;

/** Materializes immutable, bounded observations through the canonical pace calculator. */
@Service
public final class SalesPaceService implements TableExportSource {
  public record Result(
      UUID id,
      UUID offerId,
      UUID placementId,
      String offerName,
      String status,
      String reason,
      long revision,
      String period,
      BigDecimal observedRate,
      BigDecimal quantity,
      int observedDays,
      long observedSeconds,
      BigDecimal orderedUnits,
      Instant calculatedAt,
      Instant futureStart,
      Instant futureEnd,
      Instant validUntil,
      List<UUID> sourceIds,
      CurrentInventory currentInventory) {}

  public record CurrentInventory(
      Instant assessedAt,
      long poolCount,
      List<ResourceService.PoolInventory> pools,
      BigDecimal stockDurationDays,
      String reason) {}

  private record Basis(
      MarketplaceHistoryService.PaceEvidence evidence, LocalDate day, String zone) {}

  private final MarketplaceHistoryService history;
  private final MarketplaceReadService marketplace;
  private final AuthorizationService authorization;
  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batches;
  private final JsonCodec json;
  private final Clock clock;
  private final JobRuntime jobs;
  private final OutboxService outbox;
  private final ResourceService resources;
  private final AnalyticsEngine engine = new AnalyticsEngine();

  public SalesPaceService(
      MarketplaceHistoryService history,
      MarketplaceReadService marketplace,
      AuthorizationService authorization,
      JdbcClient jdbc,
      NamedParameterJdbcTemplate batches,
      JsonCodec json,
      Clock clock,
      JobRuntime jobs,
      OutboxService outbox,
      ResourceService resources) {
    this.history = history;
    this.marketplace = marketplace;
    this.authorization = authorization;
    this.jdbc = jdbc;
    this.batches = batches;
    this.json = json;
    this.clock = clock;
    this.jobs = jobs;
    this.outbox = outbox;
    this.resources = resources;
  }

  public record Refresh(UUID id, int page, long generation, long observedGeneration) {}

  public UUID submitRefresh(Scope scope, UUID requestId) {
    ScopeTransactionRunner.requireCurrent(scope);
    authorization.require(scope, "finance.read");
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope,0))")
        .param("scope", "pace-refresh:" + scope.organizationId() + ":" + scope.requireAccount())
        .query(Object.class)
        .single();
    var active =
        jdbc.sql(
                """
                SELECT id FROM economics_pace_refresh WHERE organization_id=:org AND account_id=:account
                  AND state='RUNNING' FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .query(UUID.class)
            .optional();
    if (active.isPresent()) {
      UUID id = active.orElseThrow();
      if (!jobs.terminal(scope, id)) {
        jdbc.sql("UPDATE economics_pace_refresh SET generation=generation+1 WHERE id=:id")
            .param("id", id)
            .update();
        return id;
      }
      jdbc.sql("UPDATE economics_pace_refresh SET state='DONE' WHERE id=:id")
          .param("id", id)
          .update();
    }
    outbox.requireHeavyAdmission();
    UUID job = jobs.submit(scope, "SALES_PACE_REFRESH", requestId.toString(), "{}");
    jdbc.sql(
            """
            INSERT INTO economics_pace_refresh(organization_id,account_id,id)
            VALUES (:org,:account,:id) ON CONFLICT(organization_id,account_id,id) DO NOTHING
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", job)
        .update();
    return job;
  }

  public boolean prepareNext(Scope scope, UUID jobId) {
    ScopeTransactionRunner.requireCurrent(scope);
    var work =
        jdbc.sql(
                """
                SELECT id,next_page,generation,observed_generation FROM economics_pace_refresh
                WHERE organization_id=:org AND account_id=:account AND id=:id FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", jobId)
            .query(
                (row, index) ->
                    new Refresh(
                        row.getObject(1, UUID.class),
                        row.getInt(2),
                        row.getLong(3),
                        row.getLong(4)))
            .single();
    Page<Result> result = publishPage(scope, work.page(), 200, "");
    boolean end = (long) (work.page() + 1) * 200 >= result.total();
    boolean complete = end && work.generation() == work.observedGeneration();
    jdbc.sql(
            """
            UPDATE economics_pace_refresh SET next_page=:page,observed_generation=:generation,
              state=:state WHERE organization_id=:org AND account_id=:account AND id=:id
            """)
        .param("page", end ? 0 : work.page() + 1)
        .param("generation", end ? work.generation() : work.observedGeneration())
        .param("state", complete ? "DONE" : "RUNNING")
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", jobId)
        .update();
    outbox.emit(
        scope,
        jobId + ":" + work.observedGeneration() + ":" + work.page(),
        "SALES_PACE_CALCULATED",
        new OutboxService.EntityChange("economics", jobId, work.page()));
    return complete;
  }

  public Page<Result> page(
      Scope scope,
      int page,
      int size,
      String search,
      String sort,
      String direction,
      String status) {
    authorization.require(scope, "finance.read");
    int offset = Page.offset(page, size);
    var selection =
        selection(
            scope,
            new Query(
                search,
                status.isEmpty() ? Map.of() : Map.of("status", status),
                sort + "," + direction,
                page,
                size),
            List.of());
    long total =
        jdbc.sql("SELECT count(*) " + selection.source())
            .params(selection.parameters())
            .query(Long.class)
            .single();
    Instant now = clock.instant();
    var rows =
        jdbc.sql(
                "SELECT snapshot::text,status,reason "
                    + selection.source()
                    + " ORDER BY "
                    + selection.ordering()
                    + ",id LIMIT :size OFFSET :offset")
            .params(selection.parameters())
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) -> {
                  Result stored = json.decode(row.getString(1), Result.class);
                  return row.getString("status").equals(stored.status())
                      ? stored
                      : unavailable(stored, row.getString("reason"));
                })
            .list();
    var inventory =
        resources.inventory(scope, rows.stream().map(Result::offerId).distinct().toList());
    var enriched =
        rows.stream()
            .map(
                result ->
                    withInventory(
                        result,
                        inventory.getOrDefault(
                            result.offerId(), new ResourceService.Inventory(0, List.of())),
                        now))
            .toList();
    return new Page<>(enriched, total, page, size);
  }

  @Override
  public String resource() {
    return "economics/sales-pace";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("finance.read");
  }

  @Override
  public Projection projection(Scope scope, Query query, List<UUID> ids) {
    authorization.require(scope, "finance.read");
    var selection = selection(scope, query, ids);
    return new Projection(
        """
        SELECT id AS canonical_id,jsonb_build_array(snapshot->>'offerName',snapshot->>'observedRate',
          snapshot->>'period',quantity::text,reason,status) AS cells
        """
            + selection.source()
            + " ORDER BY "
            + selection.ordering()
            + ",id",
        selection.parameters(),
        List.of(
            new Column("product", "Товар", false),
            new Column("rate", "Наблюдаемый темп / 24 часа", true),
            new Column("period", "Период", false),
            new Column("quantity", "Количество", true),
            new Column("source", "Основание", false),
            new Column("status", "Состояние", false)));
  }

  private record Selection(String source, String ordering, Map<String, Object> parameters) {}

  private Selection selection(Scope scope, Query query, List<UUID> ids) {
    if (!Set.of("status").containsAll(query.filters().keySet()) || ids.size() > 1000) {
      throw new IllegalArgumentException("Invalid sales pace filter");
    }
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("org", scope.organizationId());
    parameters.put("account", scope.requireAccount());
    parameters.put("search", query.search());
    parameters.put("now", java.sql.Timestamp.from(clock.instant()));
    var heads = history.paceBasisProjection(scope);
    parameters.putAll(heads.parameters());
    String source =
        """
        FROM (SELECT checked.*,CASE WHEN current_basis
            AND (snapshot->>'validUntil')::timestamptz>:now AND current_valid_until>:now
            THEN (snapshot->>'quantity')::numeric END AS quantity,
          CASE WHEN snapshot->>'status'='AVAILABLE'
              AND (NOT current_basis OR (snapshot->>'validUntil')::timestamptz<=:now
                OR current_valid_until<=:now)
            THEN 'INCOMPLETE' ELSE snapshot->>'status' END AS status,
          CASE WHEN snapshot->>'status'='AVAILABLE' AND NOT current_basis THEN 'SOURCE_CHANGED'
            WHEN snapshot->>'status'='AVAILABLE'
              AND ((snapshot->>'validUntil')::timestamptz<=:now OR current_valid_until<=:now)
            THEN 'STALE_SOURCE' ELSE snapshot->>'reason' END AS reason
          FROM (SELECT latest.*,COALESCE(head.fingerprint=
              latest.basis->'evidence'->>'sourceFingerprint',false) AS current_basis,
              head.valid_until AS current_valid_until
            FROM (SELECT DISTINCT ON (placement_id) * FROM economics_sales_pace
            WHERE organization_id=:org AND account_id=:account
            ORDER BY placement_id,created_at DESC,id) latest
            LEFT JOIN (%s) head ON head.placement_id=latest.placement_id) checked) item
        WHERE position(lower(:search) in lower(snapshot->>'offerName'))>0
        """
            .formatted(heads.sql());
    String status = query.filters().getOrDefault("status", "");
    if (!status.isEmpty()) {
      if (!Set.of("AVAILABLE", "INCOMPLETE").contains(status)) {
        throw new IllegalArgumentException("Invalid sales pace status");
      }
      parameters.put("status", status);
      source += " AND status=:status";
    }
    if (!ids.isEmpty()) {
      parameters.put("ids", ids);
      source += " AND id IN (:ids)";
    }
    String[] sort = query.sort().split(",", -1);
    if (sort.length > 2) {
      throw new IllegalArgumentException("Invalid sales pace sort");
    }
    String column =
        switch (sort[0]) {
          case "", "id" -> "id";
          case "product", "name" -> "snapshot->>'offerName'";
          case "rate" -> "(snapshot->>'observedRate')::numeric";
          case "quantity", "status" -> sort[0];
          case "period" -> "snapshot->>'period'";
          case "source" -> "reason";
          default -> throw new IllegalArgumentException("Invalid sales pace sort");
        };
    String direction = sort.length == 2 ? sort[1] : "asc";
    if (!Set.of("asc", "desc").contains(direction)) {
      throw new IllegalArgumentException("Invalid sales pace sort direction");
    }
    return new Selection(source, column + " " + direction + " NULLS LAST", parameters);
  }

  private Page<Result> publishPage(Scope scope, int page, int size, String search) {
    ScopeTransactionRunner.requireCurrent(scope);
    authorization.require(scope, "finance.read");
    var evidence = history.paceEvidence(scope, page, size, search);
    if (evidence.items().isEmpty()) {
      return new Page<>(List.of(), evidence.total(), page, size);
    }
    ZoneId zone = marketplace.zoneId(scope);
    Instant now = clock.instant();
    LocalDate today = now.atZone(zone).toLocalDate();
    List<UUID> ids =
        evidence.items().stream()
            .map(item -> id(scope, new Basis(item, today, zone.getId())))
            .toList();
    Map<UUID, Result> existing =
        jdbc
            .sql(
                """
                SELECT snapshot::text FROM economics_sales_pace
                WHERE organization_id=:org AND account_id=:account AND id IN (:ids)
                """)
            .param("org", scope.requireOrganization())
            .param("account", scope.requireAccount())
            .param("ids", ids)
            .query((row, index) -> json.decode(row.getString(1), Result.class))
            .list()
            .stream()
            .collect(Collectors.toMap(Result::id, Function.identity()));
    List<MapSqlParameterSource> writes = new ArrayList<>();
    List<Result> results = new ArrayList<>(evidence.items().size());
    for (int index = 0; index < evidence.items().size(); index++) {
      UUID id = ids.get(index);
      Result previous = existing.get(id);
      if (previous != null) {
        results.add(expired(previous, now));
        continue;
      }
      var item = evidence.items().get(index);
      Result result = calculate(id, item, zone, now);
      results.add(result);
      writes.add(
          new MapSqlParameterSource()
              .addValue("org", scope.organizationId())
              .addValue("account", scope.accountId())
              .addValue("id", id)
              .addValue("offer", item.offerId())
              .addValue("placement", item.placementId())
              .addValue("basis", json.encode(new Basis(item, today, zone.getId())))
              .addValue("snapshot", json.encode(result)));
    }
    if (!writes.isEmpty()) {
      batches.batchUpdate(
          """
          INSERT INTO economics_sales_pace
            (organization_id,account_id,id,offer_id,placement_id,basis,snapshot)
          VALUES (:org,:account,:id,:offer,:placement,CAST(:basis AS jsonb),CAST(:snapshot AS jsonb))
          ON CONFLICT(organization_id,account_id,id) DO NOTHING
          """,
          writes.toArray(MapSqlParameterSource[]::new));
      // Concurrent readers may have inserted the same basis first. Return that stored snapshot.
      Map<UUID, Result> published =
          jdbc
              .sql(
                  """
                  SELECT snapshot::text FROM economics_sales_pace
                  WHERE organization_id=:org AND account_id=:account AND id IN (:ids)
                  """)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("ids", ids)
              .query((row, index) -> json.decode(row.getString(1), Result.class))
              .list()
              .stream()
              .collect(Collectors.toMap(Result::id, Function.identity()));
      results =
          ids.stream()
              .map(
                  id ->
                      expired(
                          Objects.requireNonNull(
                              published.get(id), "Published pace snapshot is missing"),
                          now))
              .toList();
    }
    return new Page<>(List.copyOf(results), evidence.total(), page, size);
  }

  private Result calculate(
      UUID id, MarketplaceHistoryService.PaceEvidence evidence, ZoneId zone, Instant now) {
    var days =
        evidence.days().stream()
            .map(
                day ->
                    new AnalyticsEngine.Day(
                        day.date(),
                        day.orderedUnits(),
                        day.complete(),
                        day.continuouslyAvailable(),
                        day.regime()))
            .toList();
    Instant expiry = evidence.sourcesValidUntil() == null ? now : evidence.sourcesValidUntil();
    var pace =
        engine.currentPace(
            days, zone, now, evidence.regime(), expiry, evidence.knownFutureChange());
    String reason =
        evidence.sourcesValidUntil() == null ? "SOURCE_COVERAGE_UNKNOWN" : pace.reason();
    String period =
        pace.firstDay() == null
            ? "Нет пригодных последовательных суток"
            : pace.firstDay() + " — " + pace.lastDay();
    return new Result(
        id,
        evidence.offerId(),
        evidence.placementId(),
        evidence.offerName(),
        pace.available() ? "AVAILABLE" : "INCOMPLETE",
        reason,
        1,
        period,
        pace.dailyPace(),
        pace.futureUnits(),
        pace.observedDays(),
        pace.observedSeconds(),
        pace.observedDays() == 0 ? null : pace.units(),
        now,
        pace.futureStart(),
        pace.futureEnd(),
        pace.validUntil(),
        evidence.sourceIds(),
        null);
  }

  private UUID id(Scope scope, Basis basis) {
    String digest = IdempotencyService.sha256(json.encode(basis).getBytes(StandardCharsets.UTF_8));
    return UUID.nameUUIDFromBytes(
        (scope.organizationId() + ":" + scope.accountId() + ":" + digest)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static Result expired(Result result, Instant now) {
    if (!result.status().equals("AVAILABLE") || result.validUntil().isAfter(now)) {
      return result;
    }
    return unavailable(result, "STALE_SOURCE");
  }

  private static Result unavailable(Result result, String reason) {
    return new Result(
        result.id(),
        result.offerId(),
        result.placementId(),
        result.offerName(),
        "INCOMPLETE",
        reason,
        result.revision(),
        result.period(),
        result.observedRate(),
        null,
        result.observedDays(),
        result.observedSeconds(),
        result.orderedUnits(),
        result.calculatedAt(),
        result.futureStart(),
        result.futureEnd(),
        result.validUntil(),
        result.sourceIds(),
        null);
  }

  private Result withInventory(Result result, ResourceService.Inventory inventory, Instant now) {
    BigDecimal duration = null;
    String reason;
    if (inventory.poolCount() == 0) {
      reason = "STOCK_UNKNOWN";
    } else if (inventory.poolCount() > 200) {
      reason = "STOCK_POOL_LIMIT";
    } else if (inventory.poolCount() > 1) {
      reason = "MULTIPLE_STOCK_POOLS";
    } else {
      var pool = inventory.pools().getFirst();
      if (pool.placementCount() != 1) {
        reason = pool.placementCount() == 0 ? "STOCK_PLACEMENT_UNKNOWN" : "SHARED_STOCK_POOL";
      } else {
        var estimate =
            engine.stockDuration(
                pool.availableQuantity(),
                result.observedRate(),
                result.status().equals("AVAILABLE") && result.validUntil().isAfter(now));
        duration = estimate.days();
        reason = estimate.reason();
      }
    }
    return new Result(
        result.id(),
        result.offerId(),
        result.placementId(),
        result.offerName(),
        result.status(),
        result.reason(),
        result.revision(),
        result.period(),
        result.observedRate(),
        result.quantity(),
        result.observedDays(),
        result.observedSeconds(),
        result.orderedUnits(),
        result.calculatedAt(),
        result.futureStart(),
        result.futureEnd(),
        result.validUntil(),
        result.sourceIds(),
        new CurrentInventory(now, inventory.poolCount(), inventory.pools(), duration, reason));
  }
}
