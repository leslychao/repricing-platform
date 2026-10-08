package ru.oritas.repricer.marketplace;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.SqlReadProjection;

@Service
public final class MarketplaceReadService {
  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final JsonCodec json;
  private final Clock clock;

  public MarketplaceReadService(
      JdbcClient jdbc, AuthorizationService authorization, JsonCodec json, Clock clock) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.json = json;
    this.clock = clock;
  }

  public record ScenarioSource(UUID offerId, BigDecimal basePrice, CommercialState state) {}

  /** Complete current source rows for a bounded, already authorized frozen episode scope. */
  public List<ScenarioSource> scenarioSources(Scope scope, Set<UUID> offerIds) {
    if (offerIds.isEmpty() || offerIds.size() > 1000) {
      throw new IllegalArgumentException("A bounded nonempty episode scope is required");
    }
    return jdbc.sql(
            """
            SELECT o.id,o.seller_price,c.snapshot::text FROM marketplace_offer o
              JOIN marketplace_commercial_state c ON c.organization_id=o.organization_id
                AND c.account_id=o.account_id AND c.offer_id=o.id AND c.current
            WHERE o.organization_id=:org AND o.account_id=:account AND o.id IN (:offers)
            ORDER BY o.id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("offers", offerIds)
        .query(
            (row, index) ->
                new ScenarioSource(
                    row.getObject(1, UUID.class),
                    row.getBigDecimal(2),
                    json.decode(row.getString(3), CommercialState.class)))
        .list();
  }

  public Page<Account> accounts(Scope scope, int page, int size) {
    authorization.require(scope, "organization.read");
    long offset = Page.offset(page, size);
    long total = jdbc.sql("SELECT count(*) FROM marketplace_account").query(Long.class).single();
    List<Account> items =
        jdbc.sql(
                """
                SELECT id,name,marketplace,external_id,status,revision,timezone FROM marketplace_account
                ORDER BY name,id LIMIT :size OFFSET :offset
                """)
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) ->
                    new Account(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("marketplace"),
                        row.getString("external_id"),
                        row.getString("status"),
                        !row.getString("status").equals("NOT_CONNECTED"),
                        row.getLong("revision"),
                        row.getString("timezone")))
            .list();
    return new Page<>(items, total, page, size);
  }

  public Account account(Scope scope) {
    authorization.require(scope, "account.read");
    return jdbc.sql("SELECT * FROM marketplace_account WHERE id=:id")
        .param("id", scope.requireAccount())
        .query(
            (row, index) ->
                new Account(
                    row.getObject("id", UUID.class),
                    row.getString("name"),
                    row.getString("marketplace"),
                    row.getString("external_id"),
                    row.getString("status"),
                    !row.getString("status").equals("NOT_CONNECTED"),
                    row.getLong("revision"),
                    row.getString("timezone")))
        .optional()
        .orElseThrow(MarketplaceReadService::missing);
  }

  private static final String CURRENT_CATEGORY_PATH =
      """
      CASE WHEN NOT EXISTS(SELECT 1 FROM unnest(o.category_path) category_id
        LEFT JOIN marketplace_category category ON category.organization_id=o.organization_id
          AND category.account_id=o.account_id AND category.id=category_id
        WHERE category.id IS NULL OR NOT category.current OR category.valid_until<=clock_timestamp())
      THEN o.category_path ELSE NULL END
      """;

  /** Null means unknown hierarchy; an explicitly empty confirmed path remains empty. */
  public SqlReadProjection categoryPathsProjection(Scope scope) {
    authorization.require(scope, "catalog.read");
    return new SqlReadProjection(
        """
        SELECT o.id,o.organization_id,o.account_id,o.revision,%s AS category_path
        FROM marketplace_offer o
        WHERE o.organization_id=:categoryOrganization AND o.account_id=:categoryAccount
        """
            .formatted(CURRENT_CATEGORY_PATH),
        Map.of(
            "categoryOrganization",
            scope.requireOrganization(),
            "categoryAccount",
            scope.requireAccount()));
  }

  /** Published catalog columns only; all prices are source observations, never recalculated. */
  public SqlReadProjection offerProjection(Scope scope) {
    authorization.require(scope, "catalog.read");
    return new SqlReadProjection(
        """
        SELECT o.id,o.organization_id,o.account_id,o.sku,o.name,o.image_url,o.seller_price,
          o.buyer_price,o.currency,o.revision,o.observed_at,%s AS category_path,a.timezone AS account_timezone
        FROM marketplace_offer o JOIN marketplace_account a
          ON (a.organization_id,a.id)=(o.organization_id,o.account_id)
        WHERE o.organization_id=:catalogOrganization AND o.account_id=:catalogAccount
        """
            .formatted(CURRENT_CATEGORY_PATH),
        Map.of(
            "catalogOrganization",
            scope.requireOrganization(),
            "catalogAccount",
            scope.requireAccount()));
  }

  /** Only fresh, complete commercial facts identify current participations and price targets. */
  public SqlReadProjection commercialPresentationProjection(Scope scope) {
    authorization.require(scope, "catalog.read");
    return new SqlReadProjection(
        """
        SELECT commercial.offer_id,commercial.snapshot->'targetIds' AS price_targets,
          COALESCE(promotions.items,'[]'::jsonb) AS promotions,
          COALESCE(promotions.names,'') AS promotion_names
        FROM marketplace_commercial_state commercial
        LEFT JOIN LATERAL (
          SELECT jsonb_agg(jsonb_build_object('id',participation->>'promotionId',
            'targetId',participation->>'targetId',
            'name',COALESCE(promotion.name,participation->>'promotionId'),
            'processing',participation->'processing')
            ORDER BY participation->>'promotionId',participation->>'targetId') AS items,
            string_agg(DISTINCT COALESCE(promotion.name,participation->>'promotionId'),', '
              ORDER BY COALESCE(promotion.name,participation->>'promotionId')) AS names
          FROM jsonb_array_elements(commercial.snapshot->'currentParticipations') participation
          LEFT JOIN marketplace_promotion promotion
            ON promotion.organization_id=commercial.organization_id
              AND promotion.account_id=commercial.account_id
              AND promotion.id=CAST(participation->>'promotionId' AS uuid)
        ) promotions ON true
        WHERE commercial.organization_id=:presentationOrganization
          AND commercial.account_id=:presentationAccount AND commercial.current
          AND (commercial.snapshot->>'complete')::boolean
          AND (commercial.snapshot->>'validUntil')::timestamptz>clock_timestamp()
          AND jsonb_array_length(commercial.snapshot->'targetIds') BETWEEN 1 AND 200
          AND jsonb_array_length(commercial.snapshot->'currentParticipations')<=100
        """,
        Map.of(
            "presentationOrganization",
            scope.requireOrganization(),
            "presentationAccount",
            scope.requireAccount()));
  }

  /** Captured nonfinancial inputs for one buyer segment and placement. */
  public record BuyerComparisonHead(String fingerprint, Instant validUntil) {}

  /** Public buyer-price inputs only: no costs, seller revenue, tariffs or financial revision. */
  public SqlReadProjection buyerComparisonProjection(Scope scope) {
    authorization.require(scope, "catalog.read");
    return new SqlReadProjection(
        """
        SELECT offer.id AS offer_id,terms.placement_id,
          md5(jsonb_build_object('price',offer.seller_price,'target',terms.value->'targetId',
            'segment',terms.value->'segment','field',terms.value->'buyerPriceField',
            'multiplier',terms.value->'buyerPriceMultiplier','offset',terms.value->'buyerPriceOffset',
            'participations',terms.value->'participationIds','promotions',promotions.participations)::text)
            AS fingerprint,
          LEAST((terms.value->>'validUntil')::timestamptz,
            (commercial.snapshot->>'validUntil')::timestamptz) AS valid_until
        FROM marketplace_offer offer
        JOIN marketplace_commercial_state commercial ON commercial.offer_id=offer.id
          AND commercial.organization_id=offer.organization_id
          AND commercial.account_id=offer.account_id AND commercial.current
        CROSS JOIN LATERAL (SELECT placement_id,terms AS value FROM marketplace_economic_terms
          WHERE organization_id=offer.organization_id AND account_id=offer.account_id
            AND offer_id=offer.id AND current ORDER BY placement_id LIMIT 201) terms
        CROSS JOIN LATERAL (SELECT jsonb_agg(jsonb_build_object('target',p->'targetId',
          'promotion',p->'promotionId','base',p->'basePrice','promo',p->'promotionPrice',
          'processing',p->'processing') ORDER BY p->>'targetId',p->>'promotionId') AS participations
          FROM jsonb_array_elements(commercial.snapshot->'currentParticipations') p) promotions
        WHERE offer.organization_id=:buyerOrganization AND offer.account_id=:buyerAccount
          AND NOT offer.archived AND (commercial.snapshot->>'complete')::boolean
          AND (terms.value->>'complete')::boolean
          AND jsonb_array_length(commercial.snapshot->'currentParticipations')<=100
          AND terms.value->>'buyerPriceMultiplier' IS NOT NULL
          AND terms.value->>'buyerPriceOffset' IS NOT NULL
          AND jsonb_typeof(terms.value->'buyerPriceField')='object'
        """,
        Map.of(
            "buyerOrganization",
            scope.requireOrganization(),
            "buyerAccount",
            scope.requireAccount()));
  }

  public Map<UUID, BuyerComparisonHead> buyerComparisonInputs(Scope scope, UUID offerId) {
    var source = buyerComparisonProjection(scope);
    record Entry(UUID placement, BuyerComparisonHead head) {}
    var rows =
        jdbc.sql("SELECT * FROM (" + source.sql() + ") inputs WHERE offer_id=:offer")
            .params(source.parameters())
            .param("offer", offerId)
            .query(
                (row, index) ->
                    new Entry(
                        row.getObject("placement_id", UUID.class),
                        new BuyerComparisonHead(
                            row.getString("fingerprint"),
                            row.getTimestamp("valid_until").toInstant())))
            .list();
    if (rows.size() > 200) {
      throw new BusinessException("ECONOMIC_SCOPE_LIMIT", 422, "Область наблюдения цены превышена");
    }
    Map<UUID, BuyerComparisonHead> result = new LinkedHashMap<>();
    rows.forEach(row -> result.put(row.placement(), row.head()));
    return Map.copyOf(result);
  }

  public record SupplierIndex(BigDecimal value, BigDecimal minimumPrice, String currency) {}

  public record SupplierPriceIndices(
      String source, String color, SupplierIndex ozon, SupplierIndex external) {}

  public SupplierPriceIndices supplierPriceIndices(Scope scope, UUID offerId) {
    authorization.require(scope, "catalog.read");
    return jdbc.sql(
            """
            SELECT CASE WHEN account.marketplace='OZON' THEN 'OZON_PRODUCT_INFO_PRICES' END AS source,
              supplier_price_data#>>'{price_indexes,color_index}' AS color,
              supplier_price_data#>>'{price_indexes,ozon_index_data,price_index_value}' AS ozon_value,
              supplier_price_data#>>'{price_indexes,ozon_index_data,min_price}' AS ozon_minimum,
              supplier_price_data#>>'{price_indexes,ozon_index_data,min_price_currency}' AS ozon_currency,
              supplier_price_data#>>'{price_indexes,external_index_data,price_index_value}' AS external_value,
              supplier_price_data#>>'{price_indexes,external_index_data,min_price}' AS external_minimum,
              supplier_price_data#>>'{price_indexes,external_index_data,min_price_currency}' AS external_currency
            FROM marketplace_offer offer JOIN marketplace_account account
              ON account.organization_id=offer.organization_id AND account.id=offer.account_id
            WHERE offer.organization_id=:org AND offer.account_id=:account AND offer.id=:offer
            """)
        .param("org", scope.requireOrganization())
        .param("account", scope.requireAccount())
        .param("offer", offerId)
        .query(
            (row, index) ->
                new SupplierPriceIndices(
                    row.getString("source"),
                    row.getString("color"),
                    new SupplierIndex(
                        sourceDecimal(row.getString("ozon_value")),
                        sourceDecimal(row.getString("ozon_minimum")),
                        row.getString("ozon_currency")),
                    new SupplierIndex(
                        sourceDecimal(row.getString("external_value")),
                        sourceDecimal(row.getString("external_minimum")),
                        row.getString("external_currency"))))
        .optional()
        .orElseThrow(MarketplaceReadService::missing);
  }

  private static BigDecimal sourceDecimal(String value) {
    if (value == null || !value.matches("-?[0-9]{1,26}(\\.[0-9]{1,12})?")) {
      return null;
    }
    return new BigDecimal(value);
  }

  /** Canonical pool facts; absence or multiple pools does not establish additive inventory. */
  public SqlReadProjection stockProjection(Scope scope) {
    authorization.require(scope, "catalog.read");
    return new SqlReadProjection(
        """
        SELECT s.id AS pool_id,s.offer_id,s.name,s.free_quantity,s.obligations_covered,s.revision,
          s.observed_at,s.valid_until,s.complete,s.warehouse_type,s.external_id,s.physical_quantity,
          (SELECT count(*) FROM marketplace_placement p WHERE p.organization_id=s.organization_id
            AND p.account_id=s.account_id AND p.offer_id=s.offer_id) AS placement_count
        FROM marketplace_stock_pool s
        WHERE organization_id=:stockOrganization AND account_id=:stockAccount
        """,
        Map.of(
            "stockOrganization",
            scope.requireOrganization(),
            "stockAccount",
            scope.requireAccount()));
  }

  /** A shared physical group can correspond to several discovered warehouse identities. */
  public SqlReadProjection warehousePoolsProjection(Scope scope) {
    authorization.require(scope, "catalog.read");
    return new SqlReadProjection(
        """
        SELECT p.offer_id,p.id AS pool_id,w.id AS warehouse_id
        FROM marketplace_stock_pool p JOIN marketplace_offer o ON o.id=p.offer_id
        JOIN marketplace_warehouse w ON w.organization_id=p.organization_id AND w.account_id=p.account_id
          AND p.external_id=(CASE
            WHEN w.warehouse_model='OZON_FBS' THEN 'fbs:'||w.external_id||':'||o.sku
            WHEN w.warehouse_model='OZON_FBO' THEN 'fbo:'||w.external_id||':'||o.vendor_sku
            WHEN w.warehouse_model='WAREHOUSE' THEN 'partner:'||w.external_id||':'||o.sku
            WHEN w.group_id IS NOT NULL THEN 'group:'||w.group_id||':'||o.sku
            ELSE 'warehouse:'||w.external_id||':'||o.sku END)
        WHERE p.organization_id=:warehousePoolOrganization AND p.account_id=:warehousePoolAccount
          AND EXISTS(SELECT 1 FROM marketplace_stock_observation observation
            WHERE observation.pool_id=p.id AND observation.source_run_id=w.discovered_run
              AND observation.observed_at=p.observed_at)
        """,
        Map.of(
            "warehousePoolOrganization",
            scope.requireOrganization(),
            "warehousePoolAccount",
            scope.requireAccount()));
  }

  public Offer offer(Scope scope, UUID id) {
    var source = offerProjection(scope);
    return jdbc.sql("SELECT * FROM (" + source.sql() + ") offer WHERE id=:id")
        .params(source.parameters())
        .param("id", id)
        .query(
            (row, index) ->
                new Offer(
                    row.getObject("id", UUID.class),
                    row.getString("sku"),
                    row.getString("name"),
                    row.getString("image_url"),
                    row.getBigDecimal("seller_price"),
                    row.getBigDecimal("buyer_price"),
                    row.getString("currency"),
                    row.getLong("revision"),
                    row.getTimestamp("observed_at").toInstant()))
        .optional()
        .orElseThrow(MarketplaceReadService::missing);
  }

  public Map<String, UUID> resolveSkus(Scope scope, List<String> skus) {
    authorization.require(scope, "catalog.read");
    scope.requireAccount();
    if (skus == null
        || skus.isEmpty()
        || skus.size() > 500
        || skus.stream().anyMatch(sku -> sku == null || sku.isBlank() || sku.length() > 4096)) {
      throw new BusinessException("INVALID_SKU_BATCH", 422, "Некорректная порция кодов товаров");
    }
    List<SkuIdentity> found =
        jdbc.sql("SELECT sku,id FROM marketplace_offer WHERE sku IN (:skus)")
            .param("skus", skus)
            .query(
                (row, index) ->
                    new SkuIdentity(row.getString("sku"), row.getObject("id", UUID.class)))
            .list();
    Map<String, UUID> result = new LinkedHashMap<>();
    for (SkuIdentity item : found) {
      if (result.putIfAbsent(item.sku(), item.id()) != null) {
        throw new BusinessException(
            "SKU_IDENTITY_AMBIGUOUS", 422, "Код не определяет единственный товар");
      }
    }
    return Map.copyOf(result);
  }

  public ZoneId zoneId(Scope scope) {
    authorization.require(scope, "account.read");
    return jdbc.sql("SELECT timezone FROM marketplace_account WHERE id=:id")
        .param("id", scope.requireAccount())
        .query(String.class)
        .optional()
        .map(ZoneId::of)
        .orElseThrow(MarketplaceReadService::missing);
  }

  public StockSnapshot stock(Scope scope, UUID poolId) {
    return readStock(scope, poolId, false);
  }

  public List<UUID> categoryAncestors(Scope scope, UUID offerId) {
    var source = categoryPathsProjection(scope);
    return jdbc.sql("SELECT category_path FROM (" + source.sql() + ") offers WHERE id=:id")
        .params(source.parameters())
        .param("id", offerId)
        .query(
            (row, index) -> {
              var path = row.getArray(1);
              if (path == null) {
                throw new BusinessException(
                    "CATEGORY_UNKNOWN",
                    422,
                    "Иерархия категории не подтверждена актуальным источником");
              }
              try {
                return List.of((UUID[]) path.getArray());
              } finally {
                path.free();
              }
            })
        .optional()
        .orElseThrow(MarketplaceReadService::missing);
  }

  public record CatalogNames(Map<UUID,String> offers, Map<UUID,String> categories) {}

  /** Bounded names for existing references, including retained historical category identities. */
  public CatalogNames catalogNames(Scope scope, Set<UUID> offers, Set<UUID> categories) {
    authorization.require(scope,"catalog.read");
    Set<UUID> ids = new HashSet<>(offers);
    ids.addAll(categories);
    if(ids.size()>200){throw new IllegalArgumentException("At most 200 catalog names per page");}
    if(ids.isEmpty()){return new CatalogNames(Map.of(),Map.of());}
    record Name(String kind,UUID id,String value) {}
    var names=jdbc.sql("""
        SELECT 'OFFER' AS kind,id,name FROM marketplace_offer
          WHERE organization_id=:org AND account_id=:account AND id IN (:ids)
        UNION ALL SELECT 'CATEGORY',id,name FROM marketplace_category
          WHERE organization_id=:org AND account_id=:account AND id IN (:ids)
        """).param("org",scope.organizationId()).param("account",scope.requireAccount())
        .param("ids",ids).query((row,index)->new Name(row.getString(1),row.getObject(2,UUID.class),row.getString(3))).list();
    Map<UUID,String> offerNames=new LinkedHashMap<>();
    Map<UUID,String> categoryNames=new LinkedHashMap<>();
    for(var name:names){
      if(name.kind().equals("OFFER")&&offers.contains(name.id())){offerNames.put(name.id(),name.value());}
      if(name.kind().equals("CATEGORY")&&categories.contains(name.id())){categoryNames.put(name.id(),name.value());}
    }
    return new CatalogNames(Map.copyOf(offerNames),Map.copyOf(categoryNames));
  }

  public record Category(
      UUID id,
      String externalId,
      String name,
      String kind,
      UUID parentId,
      boolean disabled,
      long revision,
      Instant observedAt,
      Instant validUntil) {}

  public record Warehouse(
      UUID id,
      String externalId,
      String name,
      String model,
      String campaignId,
      String groupId,
      List<String> models,
      Instant observedAt) {}

  /**
   * Confirmed parent categories, nearest first; an unknown or expired tree is not an empty path.
   */
  public List<UUID> categoryAncestry(Scope scope, UUID categoryId) {
    authorization.require(scope, "catalog.read");
    return jdbc.sql(
            """
            SELECT ancestors FROM marketplace_category WHERE organization_id=:org AND account_id=:account
              AND id=:id AND current AND valid_until>clock_timestamp()
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("id", categoryId)
        .query(
            (row, index) -> {
              java.sql.Array path = row.getArray(1);
              try {
                return List.of((UUID[]) path.getArray());
              } finally {
                path.free();
              }
            })
        .optional()
        .orElseThrow(
            () ->
                new BusinessException(
                    "CATEGORY_UNKNOWN",
                    422,
                    "Категория или её иерархия не подтверждена актуальным источником"));
  }

  public Page<Category> categories(Scope scope, int page, int size, String search, UUID parentId) {
    authorization.require(scope, "catalog.read");
    long offset = Page.offset(page, size);
    requireDirectorySearch(search);
    String filter =
        """
        WHERE organization_id=:org AND account_id=:account AND current
          AND (:search='' OR name ILIKE '%'||:search||'%' OR external_id=:search)
          AND (CAST(:parent AS uuid) IS NULL OR parent_id=:parent)
        """;
    Map<String, Object> parameters = new java.util.HashMap<>();
    parameters.put("org", scope.organizationId());
    parameters.put("account", scope.requireAccount());
    parameters.put("search", search);
    parameters.put("parent", parentId);
    long total =
        jdbc.sql("SELECT count(*) FROM marketplace_category " + filter)
            .params(parameters)
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                "SELECT * FROM marketplace_category "
                    + filter
                    + " ORDER BY name,id LIMIT :size OFFSET :offset")
            .params(parameters)
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) ->
                    new Category(
                        row.getObject("id", UUID.class),
                        row.getString("external_id"),
                        row.getString("name"),
                        row.getString("kind"),
                        row.getObject("parent_id", UUID.class),
                        row.getBoolean("disabled"),
                        row.getLong("revision"),
                        row.getTimestamp("observed_at").toInstant(),
                        row.getTimestamp("valid_until").toInstant()))
            .list();
    return new Page<>(items, total, page, size);
  }

  public Page<Warehouse> warehouses(Scope scope, int page, int size, String search) {
    authorization.require(scope, "catalog.read");
    long offset = Page.offset(page, size);
    requireDirectorySearch(search);
    String filter =
        """
        WHERE organization_id=:org AND account_id=:account
          AND (:search='' OR name ILIKE '%'||:search||'%' OR external_id=:search)
        """;
    Map<String, Object> parameters =
        Map.of("org", scope.organizationId(), "account", scope.requireAccount(), "search", search);
    long total =
        jdbc.sql("SELECT count(*) FROM marketplace_warehouse " + filter)
            .params(parameters)
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                "SELECT *,jsonb_path_query_array(models,'$[*].placementType')::text AS model_names"
                    + " FROM marketplace_warehouse "
                    + filter
                    + " ORDER BY name,id LIMIT :size OFFSET :offset")
            .params(parameters)
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) ->
                    new Warehouse(
                        row.getObject("id", UUID.class),
                        row.getString("external_id"),
                        row.getString("name"),
                        row.getString("warehouse_model"),
                        row.getString("campaign_id"),
                        row.getString("group_id"),
                        List.of(json.decode(row.getString("model_names"), String[].class)),
                        row.getTimestamp("observed_at").toInstant()))
            .list();
    return new Page<>(items, total, page, size);
  }

  private static void requireDirectorySearch(String search) {
    if (search == null || search.length() > 200) {
      throw new IllegalArgumentException("Invalid directory search");
    }
  }

  public List<EconomicTerms> economicTerms(Scope scope, UUID offerId) {
    authorization.require(scope, "finance.read");
    return jdbc.sql(
            """
            SELECT terms::text FROM marketplace_economic_terms
            WHERE offer_id=:offer AND current ORDER BY placement_id LIMIT 201
            """)
        .param("offer", offerId)
        .query((row, index) -> json.decode(row.getString(1), EconomicTerms.class))
        .list();
  }

  public CommercialState commercialState(Scope scope, UUID offerId) {
    authorization.require(scope, "catalog.read");
    return jdbc.sql(
            """
            SELECT snapshot::text FROM marketplace_commercial_state WHERE offer_id=:offer AND current
            """)
        .param("offer", offerId)
        .query((row, index) -> json.decode(row.getString(1), CommercialState.class))
        .optional()
        .orElseThrow(
            () ->
                new BusinessException(
                    "COMMERCIAL_SCOPE_UNKNOWN",
                    422,
                    "Полная область цен и участия не подтверждена источником"));
  }

  /** Canonical current target identities, without an N+1 snapshot fetch for each offer. */
  public Map<UUID, List<UUID>> priceTargets(Scope scope, Set<UUID> offerIds) {
    authorization.require(scope, "catalog.read");
    if (offerIds == null || offerIds.size() > 200 || offerIds.stream().anyMatch(Objects::isNull)) {
      throw new BusinessException("INVALID_OFFER_BATCH", 422, "Некорректная порция предложений");
    }
    if (offerIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, List<UUID>> result = new LinkedHashMap<>();
    jdbc.sql(
            """
            SELECT offer_id,(snapshot->'targetIds')::text FROM marketplace_commercial_state
            WHERE offer_id IN (:ids) AND current AND snapshot->>'complete'='true'
              AND (snapshot->>'validUntil')::timestamptz>clock_timestamp()
              AND CASE WHEN jsonb_typeof(snapshot->'targetIds')='array'
                THEN jsonb_array_length(snapshot->'targetIds') BETWEEN 1 AND 200 ELSE false END
            """)
        .param("ids", offerIds)
        .query(
            (row, index) ->
                Map.entry(
                    row.getObject(1, UUID.class),
                    List.of(json.decode(row.getString(2), UUID[].class))))
        .list()
        .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
    return Map.copyOf(result);
  }

  /** Meaningful normalized inputs; observation freshness is checked separately by the caller. */
  public String decisionFingerprint(Scope scope, UUID offerId) {
    DecisionInputs inputs = decisionInputs(scope, Set.of(offerId)).get(offerId);
    if (inputs == null) {
      throw new BusinessException(
          "COMMERCIAL_SCOPE_UNKNOWN", 422, "Нормализованные основания не опубликованы");
    }
    return inputs.fingerprint();
  }

  public Map<UUID, DecisionInputs> decisionInputs(Scope scope, Set<UUID> offerIds) {
    authorization.require(scope, "catalog.read");
    authorization.require(scope, "finance.read");
    if (offerIds == null || offerIds.size() > 200 || offerIds.stream().anyMatch(Objects::isNull)) {
      throw new BusinessException("INVALID_OFFER_BATCH", 422, "Некорректная порция предложений");
    }
    if (offerIds.isEmpty()) {
      return Map.of();
    }
    var rows =
        jdbc.sql(decisionInputsSql() + " AND c.offer_id IN (:offers)")
            .param("offers", offerIds)
            .query(
                (row, index) ->
                    new DecisionInputRow(
                        row.getObject("offer_id", UUID.class),
                        new DecisionInputs(
                            row.getString("fingerprint"),
                            row.getTimestamp("valid_until") == null
                                ? null
                                : row.getTimestamp("valid_until").toInstant(),
                            row.getBoolean("complete"))))
            .list();
    Map<UUID, DecisionInputs> result = new LinkedHashMap<>();
    for (DecisionInputRow row : rows) {
      result.put(row.id(), row.inputs());
    }
    return Map.copyOf(result);
  }

  public DecisionInputProjection decisionInputsProjection(Scope scope) {
    authorization.require(scope, "catalog.read");
    authorization.require(scope, "finance.read");
    return new DecisionInputProjection(decisionInputsSql(), Map.of());
  }

  private static String decisionInputsSql() {
    return """
    SELECT c.offer_id,encode(sha256(convert_to(jsonb_build_object(
      'commercial',c.snapshot-'observedAt'-'validUntil'-'revision',
      'economics',COALESCE(terms.entries,'[]'::jsonb),
      'stock',COALESCE(stock.entries,'[]'::jsonb))::text,'UTF8')),'hex') AS fingerprint,
      LEAST((c.snapshot->>'validUntil')::timestamptz,terms.valid_until,stock.valid_until) AS valid_until,
      COALESCE((c.snapshot->>'complete')::boolean,false) AND terms.complete AND stock.complete AS complete
    FROM marketplace_commercial_state c LEFT JOIN LATERAL (
      SELECT jsonb_agg(t.terms-'validUntil'-'revision' ORDER BY t.placement_id) AS entries,
        min((t.terms->>'validUntil')::timestamptz) AS valid_until,
        count(*)>0 AND count(*)<=200 AND bool_and(COALESCE((t.terms->>'complete')::boolean,false)) AS complete
      FROM (SELECT terms,placement_id FROM marketplace_economic_terms
        WHERE offer_id=c.offer_id AND current ORDER BY placement_id LIMIT 201) t
    ) terms ON true LEFT JOIN LATERAL (
      SELECT jsonb_agg(jsonb_build_object('id',s.id,'free',s.free_quantity::text,
        'obligations',s.obligations_covered::text,'complete',s.complete,'revision',s.revision)
        ORDER BY s.id) AS entries,min(s.valid_until) AS valid_until,
        count(*)>0 AND count(*)<=200 AND bool_and(s.complete
          AND s.free_quantity IS NOT NULL AND s.obligations_covered IS NOT NULL) AS complete
      FROM (SELECT id,free_quantity,obligations_covered,complete,revision,valid_until
        FROM marketplace_stock_pool WHERE offer_id=c.offer_id ORDER BY id LIMIT 201) s
    ) stock ON true WHERE c.current
    """;
  }

  public StockSnapshot stockForReservation(Scope scope, UUID poolId) {
    return readStock(scope, poolId, true);
  }

  private StockSnapshot readStock(Scope scope, UUID poolId, boolean lock) {
    authorization.require(scope, "catalog.read");
    return jdbc.sql(
            "SELECT * FROM marketplace_stock_pool WHERE id=:id" + (lock ? " FOR SHARE" : ""))
        .param("id", poolId)
        .query(
            (row, index) ->
                new StockSnapshot(
                    row.getObject("id", UUID.class),
                    row.getBigDecimal("free_quantity"),
                    row.getBigDecimal("obligations_covered"),
                    row.getLong("revision"),
                    row.getTimestamp("observed_at").toInstant(),
                    row.getTimestamp("valid_until").toInstant(),
                    row.getBoolean("complete")))
        .optional()
        .orElseThrow(MarketplaceReadService::missing);
  }

  public Page<SourceStatus> sources(Scope scope, int page, int size) {
    authorization.require(scope, "account.read");
    long offset = Page.offset(page, size);
    long count = jdbc.sql("SELECT count(*) FROM marketplace_source").query(Long.class).single();
    List<Capability> capabilities = capabilities(scope);
    List<SourceStatus> rows =
        jdbc.sql(
                """
                SELECT * FROM marketplace_source ORDER BY source_type,id LIMIT :size OFFSET :offset
                """)
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) -> {
                  String source = row.getString("source_type");
                  return new SourceStatus(
                      row.getObject("id", UUID.class),
                      source,
                      row.getString("status"),
                      row.getTimestamp("last_success_at") == null
                          ? null
                          : row.getTimestamp("last_success_at").toInstant(),
                      row.getString("reason"),
                      capabilities.stream()
                          .filter(capability -> related(source, capability.name()))
                          .toList());
                })
            .list();
    return new Page<>(rows, count, page, size);
  }

  public List<Capability> capabilities(Scope scope) {
    authorization.require(scope, "account.read");
    return jdbc.sql(
            """
              WITH current AS (SELECT DISTINCT ON(method) * FROM marketplace_profile
                ORDER BY method,revision DESC)
              SELECT method,status,reason,expires_at,contract_uri,constraints::text,
                (SELECT constraints::text FROM current WHERE method='YANDEX_AUTH_TOKEN'
                  AND status='CONFIRMED' AND expires_at>clock_timestamp()) AS authorization
              FROM current ORDER BY method
            """)
        .query(
            (row, index) -> {
              VendorMethod method = VendorMethod.valueOf(row.getString("method"));
              boolean fresh =
                  row.getTimestamp("expires_at") != null
                      && row.getTimestamp("expires_at").toInstant().isAfter(clock.instant());
              Boolean available =
                  CapabilityService.apiAvailable(method, row.getString("authorization"));
              boolean confirmed = fresh && row.getString("status").equals("CONFIRMED");
              var contract = json.decode(row.getString("constraints"), JsonObject.class);
              return new Capability(
                  row.getString("method"),
                  row.getString("status").equals("CONFIRMED")
                          && row.getTimestamp("expires_at") != null
                          && row.getTimestamp("expires_at").toInstant().isBefore(clock.instant())
                      ? "EXPIRED"
                      : row.getString("status"),
                  row.getString("reason"),
                  !row.getString("contract_uri").isBlank(),
                  confirmed && !method.write() ? Boolean.TRUE : available,
                  confirmed
                      && (!method.write()
                          || (contract.has("writeTested")
                              && contract.get("writeTested").getAsBoolean())),
                  row.getTimestamp("expires_at") == null
                      ? null
                      : row.getTimestamp("expires_at").toInstant());
            })
        .list();
  }

  public Page<Promotion> promotions(Scope scope, int page, int size, String search) {
    authorization.require(scope, "catalog.read");
    requireDirectorySearch(search);
    long offset = Page.offset(page, size);
    String filter =
        "WHERE organization_id=:org AND account_id=:account AND (:search='' OR name ILIKE"
            + " '%'||:search||'%')";
    Map<String, Object> parameters =
        Map.of("org", scope.organizationId(), "account", scope.requireAccount(), "search", search);
    long total =
        jdbc.sql("SELECT count(*) FROM marketplace_promotion " + filter)
            .params(parameters)
            .query(Long.class)
            .single();
    List<Promotion> rows =
        jdbc.sql(
                """
                SELECT id,name,promotion_type,starts_at,ends_at,processing,revision,valid_until
                FROM marketplace_promotion
                """
                    + filter
                    + " ORDER BY starts_at DESC,id LIMIT :size OFFSET :offset")
            .params(parameters)
            .param("size", size)
            .param("offset", offset)
            .query(
                (row, index) ->
                    new Promotion(
                        row.getObject("id", UUID.class),
                        row.getString("name"),
                        row.getString("promotion_type"),
                        row.getTimestamp("starts_at").toInstant(),
                        row.getTimestamp("ends_at").toInstant(),
                        row.getObject("processing", Boolean.class),
                        row.getLong("revision"),
                        row.getTimestamp("valid_until").toInstant()))
            .list();
    return new Page<>(rows, total, page, size);
  }

  public Page<SyncRun> syncRuns(Scope scope, int page, int size) {
    authorization.require(scope, "account.read");
    long total = jdbc.sql("SELECT count(*) FROM marketplace_sync_run").query(Long.class).single();
    List<SyncRun> rows =
        jdbc.sql(
                """
                SELECT id,job_id,source_type,state,page_number,parsed_count,reason,started_at,revision
                FROM marketplace_sync_run ORDER BY started_at DESC,id LIMIT :size OFFSET :offset
                """)
            .param("size", size)
            .param("offset", Page.offset(page, size))
            .query(
                (row, index) ->
                    new SyncRun(
                        row.getObject("id", UUID.class),
                        row.getObject("job_id", UUID.class),
                        row.getString("source_type"),
                        row.getString("state"),
                        row.getInt("page_number"),
                        row.getLong("parsed_count"),
                        row.getString("reason"),
                        row.getTimestamp("started_at").toInstant(),
                        row.getLong("revision")))
            .list();
    return new Page<>(rows, total, page, size);
  }

  public Page<Placement> placements(Scope scope, UUID offerId, int page, int size) {
    authorization.require(scope, "catalog.read");
    offer(scope, offerId);
    long total =
        jdbc.sql("SELECT count(*) FROM marketplace_placement WHERE offer_id=:offer")
            .param("offer", offerId)
            .query(Long.class)
            .single();
    List<Placement> rows =
        jdbc.sql(
                """
                SELECT id,external_id,model,status,available,revision,observed_at,valid_until
                FROM marketplace_placement WHERE offer_id=:offer ORDER BY external_id,id
                LIMIT :size OFFSET :offset
                """)
            .param("offer", offerId)
            .param("size", size)
            .param("offset", Page.offset(page, size))
            .query(
                (row, index) ->
                    new Placement(
                        row.getObject("id", UUID.class),
                        row.getString("external_id"),
                        row.getString("model"),
                        row.getString("status"),
                        row.getObject("available", Boolean.class),
                        row.getLong("revision"),
                        row.getTimestamp("observed_at").toInstant(),
                        row.getTimestamp("valid_until").toInstant()))
            .list();
    return new Page<>(rows, total, page, size);
  }

  private static boolean related(String source, String method) {
    String group =
        Map.of(
                "CATALOG",
                "CATALOG",
                "PRICES",
                "PRICE",
                "STOCKS",
                "STOCK",
                "PROMOTIONS",
                "PROMO",
                "HISTORY",
                "ORDER",
                "FINANCE",
                "ACCRUAL")
            .get(source);
    return group == null || method.contains(group);
  }

  private static BusinessException missing() {
    return new BusinessException("NOT_FOUND", 404, "Данные не найдены в выбранном кабинете");
  }

  public record Account(
      UUID id,
      String name,
      String marketplace,
      String externalId,
      String status,
      boolean keyConfigured,
      long revision,
      String timezone) {}

  public record Offer(
      UUID id,
      String sku,
      String name,
      String imageUrl,
      BigDecimal sellerPrice,
      BigDecimal buyerPrice,
      String currency,
      long revision,
      Instant observedAt) {}

  public record StockSnapshot(
      UUID poolId,
      BigDecimal freeQuantity,
      BigDecimal obligationsCovered,
      long revision,
      Instant observedAt,
      Instant validUntil,
      boolean complete) {}

  public record Capability(
      String name,
      String status,
      String reason,
      boolean documented,
      Boolean apiAvailable,
      boolean executionVerified,
      Instant expiresAt) {}

  public record SourceStatus(
      UUID id,
      String name,
      String status,
      Instant lastSuccessAt,
      String message,
      List<Capability> capabilities) {}

  public record Promotion(
      UUID id,
      String name,
      String type,
      Instant startsAt,
      Instant endsAt,
      Boolean processing,
      long revision,
      Instant validUntil) {}

  public record SyncRun(
      UUID id,
      UUID jobId,
      String source,
      String state,
      int pageNumber,
      long parsedCount,
      String reason,
      Instant startedAt,
      long revision) {}

  public record Placement(
      UUID id,
      String externalId,
      String model,
      String status,
      Boolean available,
      long revision,
      Instant observedAt,
      Instant validUntil) {}

  private record SkuIdentity(String sku, UUID id) {}

  public record DecisionInputs(String fingerprint, Instant validUntil, boolean complete) {}

  public record DecisionInputProjection(String sql, Map<String, Object> parameters) {}

  private record DecisionInputRow(UUID id, DecisionInputs inputs) {}
}
