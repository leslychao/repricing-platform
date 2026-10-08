package ru.oritas.repricer.marketplace;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TabularImportTarget;

/**
 * Manual observations retain their declared time and become visible only after whole-file publish.
 */
@Component
public final class MarketplaceCompetitorImportTarget implements TabularImportTarget {
  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;
  private final MarketplaceReadService marketplace;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final OutboxService outbox;
  private final JsonCodec json;
  private final Clock clock;

  public MarketplaceCompetitorImportTarget(
      JdbcClient jdbc,
      NamedParameterJdbcTemplate batch,
      MarketplaceReadService marketplace,
      AuthorizationService authorization,
      AuditService audit,
      OutboxService outbox,
      JsonCodec json,
      Clock clock) {
    this.jdbc = jdbc;
    this.batch = batch;
    this.marketplace = marketplace;
    this.authorization = authorization;
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public String kind() {
    return "COMPETITORS";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("competitor.write", "catalog.read");
  }

  @Override
  public List<Column> columns() {
    return List.of(
        new Column("sku", Type.TEXT, true),
        new Column("sourceId", Type.TEXT, true),
        new Column("sellerName", Type.TEXT, true),
        new Column("segment", Type.TEXT, true),
        new Column("observedAt", Type.INSTANT, true),
        new Column("inStock", Type.BOOLEAN, false),
        new Column("price", Type.DECIMAL, false),
        new Column("delivery", Type.DECIMAL, false),
        new Column("ownOffer", Type.BOOLEAN, true));
  }

  @Override
  public void begin(Scope scope, UUID importId) {
    authorization.require(scope, "competitor.write");
    jdbc.sql(
            """
            INSERT INTO marketplace_competitor_import(organization_id,account_id,id,state,author_id)
            VALUES (:org,:account,:id,'STAGING',:author) ON CONFLICT DO NOTHING
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("id", importId)
        .param("author", scope.subjectId())
        .update();
  }

  @Override
  public void stage(Scope scope, UUID importId, List<InputRow> rows) {
    authorization.require(scope, "competitor.write");
    if (!state(scope, importId).equals("STAGING") || rows.isEmpty() || rows.size() > 500) {
      throw new BusinessException("IMPORT_NOT_STAGING", 409, "Недопустимая порция импорта");
    }
    var offers =
        marketplace.resolveSkus(
            scope, rows.stream().map(row -> row.value("sku")).distinct().toList());
    List<MapSqlParameterSource> parameters = new ArrayList<>(rows.size());
    List<String> hashes = new ArrayList<>(rows.size());
    for (InputRow row : rows) {
      UUID offer = offers.get(row.value("sku"));
      if (offer == null) {
        throw new BusinessException(
            "OFFER_NOT_FOUND", 422, "Неизвестный товар в строке " + row.number());
      }
      var input =
          new MarketplaceCompetitorService.Input(
              offer,
              UUID.fromString(row.value("sourceId")),
              row.value("sellerName"),
              row.value("segment"),
              Instant.parse(row.value("observedAt")),
              row.value("inStock") == null ? null : Boolean.valueOf(row.value("inStock")),
              decimal(row, "price"),
              decimal(row, "delivery"),
              Boolean.parseBoolean(row.value("ownOffer")));
      if (input.observedAt().isAfter(clock.instant().plusSeconds(60))) {
        throw new BusinessException(
            "FUTURE_OBSERVATION", 422, "Наблюдение в будущем, строка " + row.number());
      }
      UUID observationId =
          UUID.nameUUIDFromBytes((importId + ":" + row.number()).getBytes(StandardCharsets.UTF_8));
      String hash =
          IdempotencyService.sha256(
              (row.number() + ":" + json.encode(input)).getBytes(StandardCharsets.UTF_8));
      hashes.add(hash);
      parameters.add(
          new MapSqlParameterSource()
              .addValue("org", scope.organizationId())
              .addValue("account", scope.accountId())
              .addValue("set", importId)
              .addValue("row", row.number())
              .addValue("id", observationId)
              .addValue("offer", offer)
              .addValue("source", input.sourceId())
              .addValue("seller", input.sellerName())
              .addValue("segment", input.segment())
              .addValue("observed", Timestamp.from(input.observedAt()))
              .addValue("stock", input.inStock())
              .addValue("price", input.price())
              .addValue("delivery", input.delivery())
              .addValue("own", input.ownOffer())
              .addValue("hash", hash));
    }
    batch.batchUpdate(
        """
        INSERT INTO marketplace_competitor_import_row(organization_id,account_id,import_id,row_number,
          observation_id,offer_id,source_id,seller_name,segment,observed_at,in_stock,price,delivery,own_offer,body_hash)
        VALUES (:org,:account,:set,:row,:id,:offer,:source,:seller,:segment,:observed,:stock,:price,:delivery,:own,:hash)
        ON CONFLICT DO NOTHING
        """,
        parameters.toArray(MapSqlParameterSource[]::new));
    long matches =
        jdbc.sql(
                """
                SELECT count(*) FROM marketplace_competitor_import_row WHERE organization_id=:org
                  AND account_id=:account AND import_id=:id AND body_hash IN (:hashes)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", importId)
            .param("hashes", hashes)
            .query(Long.class)
            .single();
    if (matches != hashes.size()) {
      throw new BusinessException(
          "CONFLICTING_IMPORT_ROW", 409, "Исходная строка импорта уже закреплена");
    }
  }

  @Override
  public void seal(Scope scope, UUID importId) {
    authorization.require(scope, "competitor.write");
    if (!state(scope, importId).equals("STAGING")) {
      return;
    }
    long count =
        jdbc.sql(
                """
                SELECT count(*) FROM marketplace_competitor_import_row WHERE organization_id=:org
                  AND account_id=:account AND import_id=:id
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("id", importId)
            .query(Long.class)
            .single();
    if (count < 1 || count > 100000) {
      throw new BusinessException("IMPORT_ROW_LIMIT", 422, "Допустимо от 1 до 100000 строк");
    }
    jdbc.sql(
            """
            UPDATE marketplace_competitor_import SET state='PREPARED'
            WHERE organization_id=:org AND account_id=:account AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", importId)
        .update();
  }

  @Override
  public boolean prepareNext(Scope scope, UUID importId) {
    return Set.of("PREPARED", "APPLIED").contains(state(scope, importId));
  }

  @Override
  public void lockForPublication(Scope scope, UUID importId) {
    authorization.requireLocked(scope, Set.of("competitor.write"));
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
  }

  @Override
  public void commit(Scope scope, UUID importId) {
    lockForPublication(scope, importId);
    String state = state(scope, importId);
    if (state.equals("APPLIED")) {
      return;
    }
    if (!state.equals("PREPARED")) {
      throw new BusinessException("IMPORT_NOT_PREPARED", 409, "Импорт не подготовлен");
    }
    jdbc.sql(
            """
            INSERT INTO marketplace_competitor_observation(organization_id,account_id,id,revision,
              offer_id,source_id,seller_name,segment,observed_at,in_stock,price,delivery,own_offer,
              revoked,current_revision,author_id)
            SELECT organization_id,account_id,observation_id,1,offer_id,source_id,seller_name,segment,
              observed_at,in_stock,price,delivery,own_offer,false,true,:author
            FROM marketplace_competitor_import_row WHERE organization_id=:org AND account_id=:account AND import_id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", importId)
        .param("author", scope.subjectId())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_competitor_import SET state='APPLIED',applied_at=clock_timestamp()
            WHERE organization_id=:org AND account_id=:account AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", importId)
        .update();
    audit.record(scope, "COMPETITORS_IMPORTED", importId, "atomic publication");
    outbox.emit(
        scope,
        "COMPETITORS_IMPORT:" + importId,
        "COMPETITORS_IMPORTED",
        new OutboxService.EntityChange("competitor-observations", importId, 1));
  }

  private String state(Scope scope, UUID importId) {
    return jdbc.sql(
            """
            SELECT state FROM marketplace_competitor_import WHERE organization_id=:org AND account_id=:account
              AND id=:id FOR UPDATE
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("id", importId)
        .query(String.class)
        .single();
  }

  private static BigDecimal decimal(InputRow row, String column) {
    return row.value(column) == null ? null : new BigDecimal(row.value(column));
  }
}
