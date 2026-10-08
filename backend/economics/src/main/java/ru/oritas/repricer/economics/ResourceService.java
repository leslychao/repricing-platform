package ru.oritas.repricer.economics;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.marketplace.CommercialGateway.ObligationEvidence;
import ru.oritas.repricer.marketplace.CommercialGateway.ResourceCommitment;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.SqlReadProjection;

/** Quantity ownership independent of commercial policy and financial valuation. */
@Service
public class ResourceService {
  public record Reservation(UUID commandId, UUID poolId, BigDecimal quantity, String state) {}

  public record Availability(MarketplaceReadService.StockSnapshot stock, BigDecimal quantity) {}

  public record PoolInventory(
      UUID poolId,
      BigDecimal availableQuantity,
      long revision,
      Instant observedAt,
      Instant validUntil,
      long placementCount) {}

  public record Inventory(long poolCount, List<PoolInventory> pools) {}

  public record ReservationView(
      UUID commandId,
      UUID poolId,
      UUID offerId,
      BigDecimal quantity,
      String state,
      long stockRevision) {}

  public record ObligationView(
      UUID id,
      UUID commandId,
      UUID poolId,
      UUID offerId,
      BigDecimal quantity,
      String state,
      boolean reflectedInStock,
      Long reflectedStockRevision,
      Long sourceRevision,
      Instant observedAt,
      BigDecimal remainingQuantity,
      Boolean admissionClosed) {}

  private static final String COMMITMENTS_SQL =
      """
      SELECT pool_id,quantity,NULL::bigint AS reflected_stock_revision FROM economics_resource_hold
        WHERE organization_id=:quantityOrganization AND account_id=:quantityAccount
          AND state='HELD'
        UNION ALL
        SELECT pool_id,quantity,CASE WHEN reflected_in_stock THEN reflected_stock_revision END
        FROM economics_resource_obligation
        WHERE organization_id=:quantityOrganization AND account_id=:quantityAccount
          AND state IN ('EXPECTED','CONFIRMED','DISPUTED')
      """;

  private final JdbcClient jdbc;
  private final MarketplaceReadService marketplace;
  private final Clock clock;
  private final OutboxService outbox;

  public ResourceService(
      JdbcClient jdbc, MarketplaceReadService marketplace, Clock clock, OutboxService outbox) {
    this.jdbc = jdbc;
    this.marketplace = marketplace;
    this.clock = clock;
    this.outbox = outbox;
  }

  /** Persisted holds, including history; reading never releases uncertain commercial effects. */
  public Page<ReservationView> reservations(
      Scope scope, UUID offerId, UUID poolId, int page, int size) {
    int offset = Page.offset(page, size);
    var source = resourceRead(scope, offerId, poolId, "economics_resource_hold");
    long total =
        jdbc.sql("SELECT count(*) " + source.sql())
            .params(source.parameters())
            .query(Long.class)
            .single();
    var rows =
        jdbc.sql(
                "SELECT r.command_id,r.pool_id,s.offer_id,r.quantity,r.state,"
                    + "r.stock_revision "
                    + source.sql()
                    + " ORDER BY r.command_id,r.pool_id LIMIT :size OFFSET :offset")
            .params(source.parameters())
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) ->
                    new ReservationView(
                        row.getObject("command_id", UUID.class),
                        row.getObject("pool_id", UUID.class),
                        row.getObject("offer_id", UUID.class),
                        row.getBigDecimal("quantity"),
                        row.getString("state"),
                        row.getLong("stock_revision")))
            .list();
    return new Page<>(rows, total, page, size);
  }

  /** Current obligation state and its latest immutable source proof, without financial fields. */
  public Page<ObligationView> obligations(
      Scope scope, UUID offerId, UUID poolId, int page, int size) {
    int offset = Page.offset(page, size);
    var source = resourceRead(scope, offerId, poolId, "economics_resource_obligation");
    long total =
        jdbc.sql("SELECT count(*) " + source.sql())
            .params(source.parameters())
            .query(Long.class)
            .single();
    var rows =
        jdbc.sql(
                """
                SELECT selected.*,e.source_revision,e.observed_at,e.remaining_quantity,e.admission_closed
                FROM (SELECT r.*,s.offer_id %s ORDER BY r.id LIMIT :size OFFSET :offset) selected
                LEFT JOIN LATERAL (
                  SELECT source_revision,observed_at,remaining_quantity,admission_closed
                  FROM economics_resource_evidence e WHERE e.organization_id=selected.organization_id
                    AND e.account_id=selected.account_id AND e.command_id=selected.source_command_id
                    AND e.pool_id=selected.pool_id ORDER BY source_revision DESC LIMIT 1
                ) e ON true ORDER BY selected.id
                """
                    .formatted(source.sql()))
            .params(source.parameters())
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) -> {
                  var observed = row.getTimestamp("observed_at");
                  return new ObligationView(
                      row.getObject("id", UUID.class),
                      row.getObject("source_command_id", UUID.class),
                      row.getObject("pool_id", UUID.class),
                      row.getObject("offer_id", UUID.class),
                      row.getBigDecimal("quantity"),
                      row.getString("state"),
                      row.getBoolean("reflected_in_stock"),
                      row.getObject("reflected_stock_revision", Long.class),
                      row.getObject("source_revision", Long.class),
                      observed == null ? null : observed.toInstant(),
                      row.getBigDecimal("remaining_quantity"),
                      row.getObject("admission_closed", Boolean.class));
                })
            .list();
    return new Page<>(rows, total, page, size);
  }

  private SqlReadProjection resourceRead(Scope scope, UUID offerId, UUID poolId, String table) {
    // The stock owner authorizes catalog.read and supplies only the current exact account scope.
    var stock = marketplace.stockProjection(scope);
    var parameters = new LinkedHashMap<String, Object>(stock.parameters());
    parameters.put("resourceOrganization", scope.requireOrganization());
    parameters.put("resourceAccount", scope.requireAccount());
    String source =
        "FROM "
            + table
            + " r JOIN ("
            + stock.sql()
            + ") s ON s.pool_id=r.pool_id"
            + " WHERE r.organization_id=:resourceOrganization AND r.account_id=:resourceAccount";
    if (offerId != null) {
      source += " AND s.offer_id=:resourceOffer";
      parameters.put("resourceOffer", offerId);
    }
    if (poolId != null) {
      source += " AND r.pool_id=:resourcePool";
      parameters.put("resourcePool", poolId);
    }
    return new SqlReadProjection(source, parameters);
  }

  /** Each complete, fresh source pool keeps its identity and subtracts outstanding commitments. */
  public SqlReadProjection poolAvailabilityProjection(Scope scope) {
    var stock = marketplace.stockProjection(scope);
    var parameters = new LinkedHashMap<String, Object>(stock.parameters());
    parameters.put("quantityOrganization", scope.requireOrganization());
    parameters.put("quantityAccount", scope.requireAccount());
    parameters.put("quantityNow", java.sql.Timestamp.from(clock.instant()));
    return new SqlReadProjection(
        """
        WITH source_stock AS (%s), commitments AS (%s), occupied AS (
          SELECT stock.pool_id,sum(commitments.quantity) AS quantity FROM source_stock stock
          JOIN commitments ON commitments.pool_id=stock.pool_id
          WHERE commitments.reflected_stock_revision IS DISTINCT FROM stock.revision
          GROUP BY stock.pool_id)
        SELECT stock.*,CASE WHEN stock.complete AND stock.free_quantity IS NOT NULL
            AND stock.obligations_covered IS NOT NULL AND stock.valid_until>:quantityNow
          THEN greatest(stock.free_quantity-coalesce(occupied.quantity,0),0)
          ELSE NULL END AS available_quantity,
          CASE WHEN stock.complete AND stock.valid_until>:quantityNow
            THEN stock.obligations_covered END AS covered_quantity
        FROM source_stock stock LEFT JOIN occupied ON occupied.pool_id=stock.pool_id
        ORDER BY stock.offer_id,stock.pool_id
        """
            .formatted(stock.sql(), COMMITMENTS_SQL),
        parameters);
  }

  /** Pool identities are not additive without a source-proven partition. */
  public SqlReadProjection offerAvailabilityProjection(Scope scope) {
    var pools = poolAvailabilityProjection(scope);
    return new SqlReadProjection(
        "SELECT offer_id,CASE WHEN count(*)=1 THEN max(available_quantity) "
            + "END AS available_quantity FROM ("
            + pools.sql()
            + ") pools GROUP BY offer_id",
        pools.parameters());
  }

  /** A bounded read, with the same obligation deductions as reservation admission. */
  public Map<UUID, Inventory> inventory(Scope scope, List<UUID> offerIds) {
    if (offerIds.isEmpty()) {
      return Map.of();
    }
    if (offerIds.size() > 200) {
      throw new IllegalArgumentException("Inventory reads are limited to 200 offers");
    }
    var source = poolAvailabilityProjection(scope);
    record Row(UUID offerId, long count, PoolInventory pool) {}
    List<Row> rows =
        jdbc.sql(
                """
                SELECT * FROM (SELECT p.*,count(*) OVER (PARTITION BY offer_id) AS pool_count,
                  row_number() OVER (PARTITION BY offer_id ORDER BY pool_id) AS ordinal
                  FROM (%s) p WHERE offer_id IN (:inventoryOffers)) bounded WHERE ordinal<=200
                ORDER BY offer_id,pool_id
                """
                    .formatted(source.sql()))
            .params(source.parameters())
            .param("inventoryOffers", offerIds)
            .query(
                (row, index) ->
                    new Row(
                        row.getObject("offer_id", UUID.class),
                        row.getLong("pool_count"),
                        new PoolInventory(
                            row.getObject("pool_id", UUID.class),
                            row.getBigDecimal("available_quantity"),
                            row.getLong("revision"),
                            row.getTimestamp("observed_at").toInstant(),
                            row.getTimestamp("valid_until").toInstant(),
                            row.getLong("placement_count"))))
            .list();
    Map<UUID, List<PoolInventory>> pools = new LinkedHashMap<>();
    Map<UUID, Long> counts = new LinkedHashMap<>();
    for (Row row : rows) {
      pools.computeIfAbsent(row.offerId(), ignored -> new ArrayList<>()).add(row.pool());
      counts.put(row.offerId(), row.count());
    }
    Map<UUID, Inventory> result = new LinkedHashMap<>();
    pools.forEach(
        (offer, items) -> result.put(offer, new Inventory(counts.get(offer), List.copyOf(items))));
    return Map.copyOf(result);
  }

  public boolean minimumReached(Scope scope, Set<UUID> poolIds, BigDecimal minimum) {
    if (poolIds.isEmpty()) {
      return false;
    }
    if (poolIds.size() > 1000 || minimum == null || minimum.signum() < 0) {
      throw new IllegalArgumentException(
          "A bounded exact pool scope and nonnegative threshold are required");
    }
    var projection = poolAvailabilityProjection(scope);
    return jdbc.sql(
            "SELECT EXISTS(SELECT 1 FROM ("
                + projection.sql()
                + ") pools WHERE pool_id IN (:pools) AND available_quantity<=:minimum)")
        .params(projection.parameters())
        .param("pools", poolIds)
        .param("minimum", minimum)
        .query(Boolean.class)
        .single();
  }

  @Transactional
  public Availability availability(Scope scope, UUID poolId) {
    var stock = marketplace.stockForReservation(scope, poolId);
    ensureGuard(scope, poolId);
    lock(scope, poolId);
    if (!stock.complete()
        || stock.freeQuantity() == null
        || stock.obligationsCovered() == null
        || stock.validUntil() == null
        || !stock.validUntil().isAfter(clock.instant())) {
      return new Availability(stock, null);
    }
    return new Availability(
        stock,
        availableQuantity(scope, poolId, stock.freeQuantity(), stock.revision())
            .max(BigDecimal.ZERO));
  }

  @Transactional
  public Reservation reserve(
      Scope scope, UUID commandId, UUID poolId, BigDecimal quantity, long expectedStockRevision) {
    if (quantity == null || quantity.signum() <= 0) {
      throw new IllegalArgumentException("A positive explicit quantity is required");
    }
    var stock = marketplace.stockForReservation(scope, poolId);
    ensureGuard(scope, poolId);
    lock(scope, poolId);
    var existing =
        jdbc.sql(
                """
                SELECT quantity,state FROM economics_resource_hold
                WHERE organization_id=:org AND account_id=:account AND command_id=:command
                  AND pool_id=:pool
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("command", commandId)
            .param("pool", poolId)
            .query(
                (rs, row) ->
                    new Reservation(commandId, poolId, rs.getBigDecimal(1), rs.getString(2)))
            .optional();
    if (existing.isPresent()) {
      Reservation reservation = existing.orElseThrow();
      if (reservation.quantity().compareTo(quantity) != 0) {
        throw new BusinessException("QUANTITY_CONFLICT", 409, "Количество команды уже закреплено");
      }
      return reservation;
    }
    if (!stock.complete()
        || stock.freeQuantity() == null
        || stock.obligationsCovered() == null
        || stock.revision() != expectedStockRevision
        || stock.validUntil() == null
        || !stock.validUntil().isAfter(clock.instant())) {
      throw new BusinessException(
          "STOCK_UNKNOWN", 409, "Остаток или покрытие обязательств неизвестны");
    }
    if (availableQuantity(scope, poolId, stock.freeQuantity(), stock.revision()).compareTo(quantity)
        < 0) {
      throw new BusinessException("INSUFFICIENT_STOCK", 409, "Свободное количество уже занято");
    }
    jdbc.sql(
            """
            INSERT INTO economics_resource_hold
              (organization_id,account_id,command_id,pool_id,quantity,state,stock_revision)
            VALUES (:org,:account,:command,:pool,:quantity,'HELD',:revision)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("command", commandId)
        .param("pool", poolId)
        .param("quantity", quantity)
        .param("revision", stock.revision())
        .update();
    changed(scope, commandId, poolId, "HELD", stock.revision());
    return new Reservation(commandId, poolId, quantity, "HELD");
  }

  private void ensureGuard(Scope scope, UUID poolId) {
    jdbc.sql(
            """
            INSERT INTO economics_resource_guard(organization_id,account_id,pool_id)
            VALUES (:org,:account,:pool) ON CONFLICT DO NOTHING
            """)
        .param("org", scope.requireOrganization())
        .param("account", scope.requireAccount())
        .param("pool", poolId)
        .update();
  }

  private BigDecimal availableQuantity(
      Scope scope, UUID poolId, BigDecimal reportedFree, long stockRevision) {
    return jdbc.sql(
            "SELECT :reported-COALESCE((SELECT sum(quantity) FROM ("
                + COMMITMENTS_SQL
                + ") obligations WHERE pool_id=:pool AND reflected_stock_revision IS DISTINCT FROM"
                + " :stockRevision),0)")
        .param("reported", reportedFree)
        .param("quantityOrganization", scope.organizationId())
        .param("quantityAccount", scope.accountId())
        .param("pool", poolId)
        .param("stockRevision", stockRevision)
        .query(BigDecimal.class)
        .single();
  }

  private void convert(
      Scope scope, UUID commandId, UUID poolId, UUID obligationId, boolean reflectedInStock) {
    lock(scope, poolId);
    Reservation hold =
        jdbc.sql(
                """
                SELECT quantity,state FROM economics_resource_hold
                WHERE organization_id=:org AND account_id=:account AND command_id=:command
                  AND pool_id=:pool FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("command", commandId)
            .param("pool", poolId)
            .query(
                (rs, row) ->
                    new Reservation(commandId, poolId, rs.getBigDecimal(1), rs.getString(2)))
            .single();
    if (hold.state().equals("CONVERTED")) {
      UUID previous =
          jdbc.sql(
                  """
                  SELECT id FROM economics_resource_obligation WHERE organization_id=:org
                    AND account_id=:account AND source_command_id=:command AND pool_id=:pool
                  """)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("command", commandId)
              .param("pool", poolId)
              .query(UUID.class)
              .single();
      if (!previous.equals(obligationId)) {
        throw new BusinessException("OBLIGATION_CONFLICT", 409, "Удержание уже передано");
      }
      return;
    }
    if (!hold.state().equals("HELD")) {
      throw new BusinessException("HOLD_RELEASED", 409, "Количество уже освобождено");
    }
    jdbc.sql(
            """
            INSERT INTO economics_resource_obligation
              (organization_id,account_id,id,pool_id,source_command_id,quantity,state,reflected_in_stock)
            VALUES (:org,:account,:id,:pool,:command,:quantity,'CONFIRMED',:reflected)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", obligationId)
        .param("pool", poolId)
        .param("command", commandId)
        .param("quantity", hold.quantity())
        .param("reflected", reflectedInStock)
        .update();
    jdbc.sql(
            """
            UPDATE economics_resource_hold SET state='CONVERTED'
            WHERE organization_id=:org AND account_id=:account AND command_id=:command AND pool_id=:pool
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("command", commandId)
        .param("pool", poolId)
        .update();
  }

  @Transactional
  public void releaseUnsent(
      Scope scope, UUID commandId, UUID poolId, boolean absenceOfExternalEffectProven) {
    if (!absenceOfExternalEffectProven) {
      throw new BusinessException(
          "EXTERNAL_EFFECT_UNKNOWN", 409, "Неизвестный эффект не освобождает количество");
    }
    lock(scope, poolId);
    int changed =
        jdbc.sql(
                """
                UPDATE economics_resource_hold SET state='RELEASED'
                WHERE organization_id=:org AND account_id=:account AND command_id=:command
                  AND pool_id=:pool AND state='HELD'
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("command", commandId)
            .param("pool", poolId)
            .update();
    if (changed > 0) {
      changed(scope, commandId, poolId, "RELEASED", 0);
    }
  }

  public boolean pending(Scope scope, UUID commandId, UUID poolId) {
    return jdbc.sql(
            """
            SELECT EXISTS(SELECT 1 FROM economics_resource_hold h
              LEFT JOIN economics_resource_obligation o ON o.organization_id=h.organization_id
                AND o.account_id=h.account_id AND o.source_command_id=h.command_id AND o.pool_id=h.pool_id
              WHERE h.organization_id=:org AND h.account_id=:account AND h.command_id=:command
                AND h.pool_id=:pool AND (h.state='HELD' OR o.state IN ('EXPECTED','CONFIRMED','DISPUTED')))
            """)
        .param("org", scope.requireOrganization())
        .param("account", scope.requireAccount())
        .param("command", commandId)
        .param("pool", poolId)
        .query(Boolean.class)
        .single();
  }

  /**
   * Only a complete, later observation of the exact admitted allocation may change its quantity.
   */
  @Transactional
  public boolean observe(
      Scope scope,
      UUID commandId,
      ResourceCommitment commitment,
      Instant admittedAt,
      ObligationEvidence evidence) {
    if (!evidence.complete()) {
      return false;
    }
    if (!commandId.equals(evidence.commandId())
        || !commitment.stockPoolId().equals(evidence.stockPoolId())
        || evidence.committedQuantity() == null
        || commitment.quantity().compareTo(evidence.committedQuantity()) != 0
        || evidence.remainingQuantity() == null
        || evidence.remainingQuantity().signum() < 0
        || evidence.remainingQuantity().compareTo(commitment.quantity()) > 0
        || evidence.externalObligationId() == null
        || evidence.externalObligationId().isBlank()
        || evidence.externalObligationId().length() > 500
        || evidence.rawFileId() == null
        || evidence.sourceRevision() < 1
        || evidence.observedAt() == null
        || admittedAt == null
        || evidence.observedAt().isBefore(admittedAt)
        || evidence.observedAt().isAfter(clock.instant())) {
      throw new BusinessException(
          "OBLIGATION_EVIDENCE_INVALID",
          409,
          "Подтверждение не совпадает с отправленным внешним обязательством");
    }
    var stock = marketplace.stockForReservation(scope, commitment.stockPoolId());
    lock(scope, commitment.stockPoolId());
    String identity = commitment.externalScopeKey() + ":" + evidence.externalObligationId();
    UUID obligationId =
        UUID.nameUUIDFromBytes(
            (commandId + ":" + commitment.stockPoolId() + ":" + identity)
                .getBytes(StandardCharsets.UTF_8));
    record Previous(long revision, String identity, Instant observedAt) {}
    var previous =
        jdbc.sql(
                """
                SELECT source_revision,external_identity,observed_at FROM economics_resource_evidence
                WHERE organization_id=:org AND account_id=:account AND command_id=:command AND pool_id=:pool
                ORDER BY source_revision DESC LIMIT 1
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("command", commandId)
            .param("pool", commitment.stockPoolId())
            .query(
                (rs, row) ->
                    new Previous(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()))
            .optional();
    if (previous.isPresent()) {
      var before = previous.orElseThrow();
      if (!before.identity().equals(identity)) {
        throw new BusinessException(
            "OBLIGATION_IDENTITY_CHANGED",
            409,
            "Внешнее обязательство команды изменило идентичность");
      }
      if (evidence.sourceRevision() < before.revision()) {
        return false;
      }
      if (evidence.sourceRevision() == before.revision()) {
        boolean identical =
            jdbc.sql(
                    """
                    SELECT remaining_quantity=:quantity AND admission_closed=:closed
                      AND reflected_in_stock=:reflected AND observed_at=:observed
                    FROM economics_resource_evidence WHERE organization_id=:org AND account_id=:account
                      AND command_id=:command AND pool_id=:pool AND source_revision=:revision
                    """)
                .param("org", scope.organizationId())
                .param("account", scope.accountId())
                .param("command", commandId)
                .param("pool", commitment.stockPoolId())
                .param("revision", evidence.sourceRevision())
                .param("quantity", evidence.remainingQuantity())
                .param("closed", evidence.admissionClosed())
                .param("reflected", evidence.reflectedInStock())
                .param("observed", Timestamp.from(evidence.observedAt()))
                .query(Boolean.class)
                .single();
        if (!identical) {
          throw new BusinessException(
              "OBLIGATION_REVISION_CONFLICT",
              409,
              "Одна редакция внешнего подтверждения содержит разные данные");
        }
        return false;
      }
      if (evidence.observedAt().isBefore(before.observedAt())) {
        throw new BusinessException(
            "OBLIGATION_TIME_REGRESSION",
            409,
            "Внешнее подтверждение предшествует предыдущей редакции");
      }
    }
    // No new admissions must be positively proven before a remaining amount can reduce the hold.
    BigDecimal occupied =
        evidence.admissionClosed() ? evidence.remainingQuantity() : commitment.quantity();
    boolean reflected =
        evidence.admissionClosed()
            && evidence.reflectedInStock()
            && stock.complete()
            && stock.observedAt() != null
            && !stock.observedAt().isBefore(evidence.observedAt())
            && stock.validUntil() != null
            && stock.validUntil().isAfter(clock.instant());
    convert(scope, commandId, commitment.stockPoolId(), obligationId, false);
    jdbc.sql(
            """
            INSERT INTO economics_resource_evidence(organization_id,account_id,command_id,pool_id,
              source_revision,external_identity,remaining_quantity,admission_closed,reflected_in_stock,
              observed_at,raw_file_id)
            VALUES(:org,:account,:command,:pool,:revision,:identity,:quantity,:closed,:reflected,:observed,:raw)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("command", commandId)
        .param("pool", commitment.stockPoolId())
        .param("revision", evidence.sourceRevision())
        .param("identity", identity)
        .param("quantity", evidence.remainingQuantity())
        .param("closed", evidence.admissionClosed())
        .param("reflected", evidence.reflectedInStock())
        .param("observed", Timestamp.from(evidence.observedAt()))
        .param("raw", evidence.rawFileId())
        .update();
    jdbc.sql(
            """
            UPDATE economics_resource_obligation SET quantity=:quantity,state=:state,
              reflected_in_stock=:reflected,reflected_stock_revision=:stockRevision
            WHERE organization_id=:org AND account_id=:account AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", obligationId)
        .param("quantity", occupied.signum() == 0 ? commitment.quantity() : occupied)
        .param("state", occupied.signum() == 0 ? "SETTLED" : "CONFIRMED")
        .param("reflected", reflected)
        .param("stockRevision", reflected ? stock.revision() : null)
        .update();
    changed(scope, commandId, commitment.stockPoolId(), "OBSERVED", evidence.sourceRevision());
    return true;
  }

  private void changed(Scope scope, UUID commandId, UUID poolId, String state, long revision) {
    outbox.emit(
        scope,
        "QUANTITY:" + commandId + ":" + poolId + ":" + state + ":" + revision,
        "QUANTITY_CHANGED",
        new OutboxService.EntityChange("stock-pools", poolId, revision));
  }

  private void lock(Scope scope, UUID poolId) {
    jdbc.sql(
            """
            SELECT pool_id FROM economics_resource_guard
            WHERE organization_id=:org AND account_id=:account AND pool_id=:pool FOR UPDATE
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("pool", poolId)
        .query(UUID.class)
        .single();
  }
}
