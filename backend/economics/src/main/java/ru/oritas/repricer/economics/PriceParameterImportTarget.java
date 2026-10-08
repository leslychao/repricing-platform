package ru.oritas.repricer.economics;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TabularImportTarget;

@Component
public final class PriceParameterImportTarget implements TabularImportTarget {
  private final AccountingImportService imports;
  private final SafetyEnvelopeService safety;
  private final MarketplaceReadService marketplace;
  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;

  public PriceParameterImportTarget(
      AccountingImportService imports,
      SafetyEnvelopeService safety,
      MarketplaceReadService marketplace,
      JdbcClient jdbc,
      NamedParameterJdbcTemplate batch) {
    this.imports = imports;
    this.safety = safety;
    this.marketplace = marketplace;
    this.jdbc = jdbc;
    this.batch = batch;
  }

  @Override
  public String kind() {
    return "PRICE_PARAMETERS";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("finance.write", "catalog.read");
  }

  @Override
  public List<Column> columns() {
    return List.of(
        new Column("sku", Type.TEXT, true),
        new Column("enabled", Type.BOOLEAN, true),
        new Column("minimumPrice", Type.DECIMAL, false),
        new Column("maximumPrice", Type.DECIMAL, false));
  }

  @Override
  public void begin(Scope scope, UUID importId) {
    imports.begin(scope, importId, AccountingImportService.Kind.PRICE_PARAMETERS);
  }

  @Override
  public void stage(Scope scope, UUID importId, List<InputRow> rows) {
    if (!state(scope, importId).equals("STAGING")) {
      throw new BusinessException("IMPORT_NOT_STAGING", 409, "Импорт уже закрыт для новых строк");
    }
    var identities =
        marketplace.resolveSkus(
            scope, rows.stream().map(row -> row.value("sku")).distinct().toList());
    for (InputRow row : rows) {
      if (!identities.containsKey(row.value("sku"))) {
        throw new BusinessException(
            "OFFER_NOT_FOUND", 422, "Неизвестный товар в строке " + row.number());
      }
    }
    record Revision(UUID offer, long revision) {}
    var revisions = new HashMap<UUID, Long>();
    jdbc.sql(
            """
            SELECT offer_id,revision FROM economics_offer_price_bounds WHERE organization_id=:org
              AND account_id=:account AND current_revision AND offer_id IN (:offers)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("offers", identities.values())
        .query((rs, row) -> new Revision(rs.getObject(1, UUID.class), rs.getLong(2)))
        .list()
        .forEach(row -> revisions.put(row.offer(), row.revision()));
    var parameters = new ArrayList<MapSqlParameterSource>();
    var hashes = new ArrayList<String>();
    for (InputRow row : rows) {
      UUID offer = identities.get(row.value("sku"));
      var bounds =
          new SafetyEnvelopeService.OfferBounds(
              offer,
              revisions.getOrDefault(offer, 0L),
              Boolean.parseBoolean(row.value("enabled")),
              decimal(row, "minimumPrice"),
              decimal(row, "maximumPrice"));
      String basis =
          offer
              + "|"
              + bounds.enabled()
              + "|"
              + bounds.minimumPrice()
              + "|"
              + bounds.maximumPrice();
      String hash = IdempotencyService.sha256(basis.getBytes(StandardCharsets.UTF_8));
      hashes.add(hash);
      parameters.add(
          new MapSqlParameterSource()
              .addValue("org", scope.organizationId())
              .addValue("account", scope.accountId())
              .addValue("set", importId)
              .addValue("offer", offer)
              .addValue("revision", bounds.revision())
              .addValue("enabled", bounds.enabled())
              .addValue("minimum", bounds.minimumPrice())
              .addValue("maximum", bounds.maximumPrice())
              .addValue("hash", hash));
    }
    batch.batchUpdate(
        """
        INSERT INTO economics_price_import_row(organization_id,account_id,import_id,offer_id,
          expected_revision,enabled,minimum_price,maximum_price,body_hash)
        VALUES (:org,:account,:set,:offer,:revision,:enabled,:minimum,:maximum,:hash) ON CONFLICT DO NOTHING
        """,
        parameters.toArray(MapSqlParameterSource[]::new));
    long matching =
        jdbc.sql(
                """
                SELECT count(*) FROM economics_price_import_row WHERE organization_id=:org AND account_id=:account
                  AND import_id=:id AND body_hash IN (:hashes)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", importId)
            .param("hashes", hashes)
            .query(Long.class)
            .single();
    if (matching != hashes.size()) {
      throw new BusinessException(
          "CONFLICTING_IMPORT_ROW", 422, "Повторяющиеся или конфликтующие товары");
    }
  }

  @Override
  public void seal(Scope scope, UUID importId) {
    if (!state(scope, importId).equals("STAGING")) {
      return;
    }
    long rows =
        jdbc.sql(
                """
                SELECT count(*) FROM economics_price_import_row WHERE organization_id=:org AND account_id=:account
                  AND import_id=:id
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("id", importId)
            .query(Long.class)
            .single();
    if (rows < 1 || rows > 100000) {
      throw new BusinessException("IMPORT_ROW_LIMIT", 422, "Допустимо от 1 до 100000 строк");
    }
    jdbc.sql(
            """
            UPDATE economics_import SET state='PREPARED' WHERE organization_id=:org AND account_id=:account AND id=:id
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
    imports.lockForPublication(scope);
  }

  @Override
  public void commit(Scope scope, UUID importId) {
    safety.publishPriceImport(scope, importId);
  }

  private String state(Scope scope, UUID importId) {
    return jdbc.sql(
            """
            SELECT state FROM economics_import WHERE organization_id=:org AND account_id=:account
              AND id=:id AND kind='PRICE_PARAMETERS' FOR UPDATE
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("id", importId)
        .query(String.class)
        .single();
  }

  private static BigDecimal decimal(InputRow row, String key) {
    return row.value(key) == null ? null : new BigDecimal(row.value(key));
  }
}
