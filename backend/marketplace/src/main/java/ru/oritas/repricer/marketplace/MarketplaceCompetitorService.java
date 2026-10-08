package ru.oritas.repricer.marketplace;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.SqlReadProjection;
import ru.oritas.repricer.platform.TableExportSource;

@Service
public class MarketplaceCompetitorService implements TableExportSource {
  public record Input(
      UUID offerId,
      UUID sourceId,
      String sellerName,
      String segment,
      Instant observedAt,
      Boolean inStock,
      BigDecimal price,
      BigDecimal delivery,
      boolean ownOffer) {
    public Input {
      if (offerId == null
          || sourceId == null
          || sellerName == null
          || sellerName.isBlank()
          || sellerName.length() > 200
          || segment == null
          || segment.isBlank()
          || segment.length() > 1000
          || observedAt == null
          || (price != null
              && (price.signum() <= 0
                  || price.scale() > 12
                  || price.precision() - price.scale() > 26))
          || (delivery != null
              && (delivery.signum() < 0
                  || delivery.scale() > 12
                  || delivery.precision() - delivery.scale() > 26))) {
        throw new IllegalArgumentException("Invalid competitor observation");
      }
    }
  }

  public record Observation(
      UUID id,
      UUID offerId,
      UUID sourceId,
      String sellerName,
      String segment,
      Instant observedAt,
      Boolean inStock,
      BigDecimal price,
      BigDecimal delivery,
      boolean ownOffer,
      boolean revoked,
      long revision,
      String offerName) {}

  public record Source(UUID sourceId, String sellerName) {}

  private record Selection(String source, String order, Map<String, Object> parameters) {}

  private static final String OBSERVATIONS =
      """
      SELECT o.*,p.name AS offer_name FROM marketplace_competitor_observation o
      JOIN marketplace_offer p ON p.organization_id=o.organization_id
        AND p.account_id=o.account_id AND p.id=o.offer_id
      """;

  public record ReferenceHead(String fingerprint, boolean complete) {}

  /** Whole latest reference, including unavailable and revoked sellers; at most 501 heads. */
  public SqlReadProjection referenceProjection(Scope scope) {
    authorization.require(scope, "competitor.read");
    return new SqlReadProjection(
        """
        SELECT groups.offer_id,groups.segment,
          CASE WHEN head.total<=500 THEN head.fingerprint END AS fingerprint,
          head.total<=500 AS complete
        FROM (SELECT DISTINCT offer_id,segment FROM marketplace_competitor_observation
          WHERE organization_id=:referenceOrganization AND account_id=:referenceAccount) groups
        CROSS JOIN LATERAL (
          SELECT count(*) AS total,md5(string_agg(id::text||':'||revision::text,','
            ORDER BY id,revision)) AS fingerprint
          FROM (SELECT id,revision FROM (
            SELECT id,revision,rank() OVER(PARTITION BY source_id ORDER BY observed_at DESC) newest
            FROM marketplace_competitor_observation
            WHERE organization_id=:referenceOrganization AND account_id=:referenceAccount
              AND offer_id=groups.offer_id AND segment=groups.segment AND current_revision
          ) ranked WHERE newest=1 ORDER BY id,revision LIMIT 501) bounded
        ) head
        """,
        Map.of(
            "referenceOrganization",
            scope.requireOrganization(),
            "referenceAccount",
            scope.requireAccount()));
  }

  public ReferenceHead referenceHead(Scope scope, UUID offerId, String segment) {
    var source = referenceProjection(scope);
    return jdbc.sql(
            "SELECT fingerprint,complete FROM ("
                + source.sql()
                + ") heads WHERE offer_id=:offer AND segment=:segment")
        .params(source.parameters())
        .param("offer", offerId)
        .param("segment", segment)
        .query((row, index) -> new ReferenceHead(row.getString(1), row.getBoolean(2)))
        .optional()
        .orElse(new ReferenceHead("EMPTY", true));
  }

  private final JdbcClient jdbc;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final OutboxService outbox;
  private final Clock clock;

  public MarketplaceCompetitorService(
      JdbcClient jdbc,
      AuthorizationService authorization,
      AuditService audit,
      OutboxService outbox,
      Clock clock) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.audit = audit;
    this.outbox = outbox;
    this.clock = clock;
  }

  public Page<Observation> page(Scope scope, Query query) {
    var selected = selection(scope, query, List.of());
    long total =
        jdbc.sql("SELECT count(*) " + selected.source())
            .params(selected.parameters())
            .query(Long.class)
            .single();
    List<Observation> rows =
        jdbc.sql(
                "SELECT o.* "
                    + selected.source()
                    + " ORDER BY "
                    + selected.order()
                    + ",o.id LIMIT :limit OFFSET :offset")
            .params(selected.parameters())
            .param("limit", query.size())
            .param("offset", Page.offset(query.page(), query.size()))
            .query(this::map)
            .list();
    return new Page<>(rows, total, query.page(), query.size());
  }

  public Page<Source> sources(Scope scope, String search, int page, int size) {
    authorization.require(scope, "competitor.read");
    if (search == null || search.length() > 200) {
      throw new IllegalArgumentException("Invalid source search");
    }
    String source =
        """
        FROM (SELECT DISTINCT ON (source_id) source_id,seller_name
          FROM marketplace_competitor_observation WHERE organization_id=:org AND account_id=:account
            AND current_revision ORDER BY source_id,observed_at DESC,revision DESC,id) sources
        WHERE position(lower(:search) in lower(seller_name))>0
        """;
    var parameters =
        Map.of(
            "org",
            scope.requireOrganization(),
            "account",
            scope.requireAccount(),
            "search",
            search);
    long total =
        jdbc.sql("SELECT count(*) " + source).params(parameters).query(Long.class).single();
    List<Source> rows =
        jdbc.sql(
                "SELECT * " + source + " ORDER BY seller_name,source_id LIMIT :size OFFSET :offset")
            .params(parameters)
            .param("size", size)
            .param("offset", Page.offset(page, size))
            .query((row, index) -> new Source(row.getObject(1, UUID.class), row.getString(2)))
            .list();
    return new Page<>(rows, total, page, size);
  }

  public Observation get(Scope scope, UUID id) {
    authorization.require(scope, "competitor.read");
    return read(scope, id, false);
  }

  @Override
  public String resource() {
    return "competitor-observations";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("competitor.read");
  }

  @Override
  public Projection projection(Scope scope, Query query, List<UUID> ids) {
    var selected = selection(scope, query, ids);
    return new Projection(
        """
        SELECT o.id AS canonical_id,jsonb_build_array(o.offer_name,o.seller_name,o.source_id::text,
          o.segment,o.price::text,o.delivery::text,o.in_stock::text,o.own_offer::text,
          o.observed_at::text,CASE WHEN o.revoked THEN 'REVOKED' ELSE 'ACTIVE' END) AS cells
        """
            + selected.source()
            + " ORDER BY "
            + selected.order()
            + ",o.id",
        selected.parameters(),
        List.of(
            new Column("product", "Товар", false),
            new Column("seller", "Продавец", false),
            new Column("sourceId", "Источник", false),
            new Column("segment", "Сопоставимые условия", false),
            new Column("price", "Цена", true),
            new Column("delivery", "Доставка", true),
            new Column("inStock", "В наличии", false),
            new Column("ownOffer", "Своё предложение", false),
            new Column("date", "Время наблюдения", false),
            new Column("status", "Состояние", false)));
  }

  private Selection selection(Scope scope, Query query, List<UUID> ids) {
    authorization.require(scope, "competitor.read");
    if (query.search().length() > 200 || ids.size() > 1000) {
      throw new IllegalArgumentException("Invalid competitor query");
    }
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("org", scope.requireOrganization());
    parameters.put("account", scope.requireAccount());
    parameters.put("search", query.search());
    var source =
        new StringBuilder(
            "FROM ("
                + OBSERVATIONS
                + ") o WHERE o.organization_id=:org AND o.account_id=:account AND"
                + " o.current_revision AND position(lower(:search) in lower(o.offer_name||'"
                + " '||o.seller_name||' '||o.segment))>0");
    for (var entry : query.filters().entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      String column =
          switch (key) {
            case "offerId" -> "offer_id";
            case "sourceId" -> "source_id";
            case "segment" -> "segment";
            case "status" -> "revoked";
            default -> throw new IllegalArgumentException("Invalid competitor filter");
          };
      if (value.isEmpty()) {
        continue;
      }
      Object parameter = value;
      if (key.equals("offerId") || key.equals("sourceId")) {
        parameter = UUID.fromString(value);
      } else if (key.equals("status")) {
        if (!Set.of("ACTIVE", "REVOKED").contains(value)) {
          throw new IllegalArgumentException("Invalid competitor status");
        }
        parameter = value.equals("REVOKED");
      } else if (value.length() > 1000) {
        throw new IllegalArgumentException("Invalid competitor segment");
      }
      String name = "filter" + parameters.size();
      parameters.put(name, parameter);
      source.append(" AND o.").append(column).append("=:").append(name);
    }
    if (!ids.isEmpty()) {
      parameters.put("ids", ids);
      source.append(" AND o.id IN (:ids)");
    }
    String[] sorting = query.sort().split(",", -1);
    if (sorting.length != 2 || !Set.of("asc", "desc").contains(sorting[1])) {
      throw new IllegalArgumentException("Invalid competitor sorting");
    }
    String column =
        switch (sorting[0]) {
          case "id", "price", "delivery", "segment" -> sorting[0];
          case "product" -> "offer_name";
          case "seller" -> "seller_name";
          case "inStock" -> "in_stock";
          case "status" -> "revoked";
          case "date", "observedAt" -> "observed_at";
          default -> throw new IllegalArgumentException("Invalid competitor sorting");
        };
    return new Selection(
        source.toString(), "o." + column + " " + sorting[1] + " NULLS LAST", parameters);
  }

  public List<Observation> latest(Scope scope, UUID offerId, String segment) {
    authorization.require(scope, "competitor.read");
    List<Observation> observations =
        jdbc.sql(
                """
                SELECT * FROM (
                  SELECT o.*,rank() OVER (PARTITION BY source_id ORDER BY observed_at DESC) latest
                  FROM (%s) o
                  WHERE organization_id=:org AND account_id=:account AND offer_id=:offer
                    AND segment=:segment AND current_revision
                ) observations WHERE latest=1 ORDER BY source_id,revision DESC,id LIMIT 501
                """
                    .formatted(OBSERVATIONS))
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("offer", offerId)
            .param("segment", segment)
            .query(this::map)
            .list();
    if (observations.size() > 500) {
      throw new BusinessException(
          "COMPETITOR_SOURCE_LIMIT",
          422,
          "Превышен полный набор источников конкурентного ориентира");
    }
    return observations;
  }

  @Transactional
  public Observation add(Scope scope, Input input) {
    authorization.require(scope, "competitor.write");
    lockAccount(scope);
    if (input.observedAt().isAfter(clock.instant().plusSeconds(60))) {
      throw new BusinessException("FUTURE_OBSERVATION", 422, "Наблюдение находится в будущем");
    }
    UUID id = UUID.randomUUID();
    insert(scope, id, 1, input, false);
    changed(scope, id, input.offerId(), 1);
    return read(scope, id, false);
  }

  @Transactional
  public Observation correct(Scope scope, UUID id, Input input, long expectedRevision) {
    authorization.require(scope, "competitor.write");
    lockAccount(scope);
    Observation old = read(scope, id, true);
    if (old.revision() != expectedRevision
        || !old.offerId().equals(input.offerId())
        || !old.sourceId().equals(input.sourceId())
        || !old.segment().equals(input.segment())
        || !old.observedAt().equals(input.observedAt())
        || old.revoked()) {
      throw new BusinessException(
          "OBSERVATION_CONFLICT",
          409,
          "Изменение товара, даты или сегмента требует нового наблюдения");
    }
    jdbc.sql(
            """
            UPDATE marketplace_competitor_observation SET current_revision=false
            WHERE organization_id=:org AND account_id=:account AND id=:id AND current_revision
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", id)
        .update();
    insert(scope, id, old.revision() + 1, input, false);
    changed(scope, id, old.offerId(), old.revision() + 1);
    return read(scope, id, false);
  }

  @Transactional
  public void revoke(Scope scope, UUID id, long expectedRevision) {
    authorization.require(scope, "competitor.write");
    lockAccount(scope);
    Observation old = read(scope, id, true);
    if (old.revision() != expectedRevision) {
      throw new BusinessException("STALE_REVISION", 409, "Наблюдение уже изменено");
    }
    if (old.revoked()) {
      return;
    }
    jdbc.sql(
            """
            UPDATE marketplace_competitor_observation SET current_revision=false
            WHERE organization_id=:org AND account_id=:account AND id=:id AND current_revision
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", id)
        .update();
    insert(
        scope,
        id,
        old.revision() + 1,
        new Input(
            old.offerId(),
            old.sourceId(),
            old.sellerName(),
            old.segment(),
            old.observedAt(),
            old.inStock(),
            old.price(),
            old.delivery(),
            old.ownOffer()),
        true);
    changed(scope, id, old.offerId(), old.revision() + 1);
  }

  private void insert(Scope scope, UUID id, long revision, Input input, boolean revoked) {
    jdbc.sql(
            """
            INSERT INTO marketplace_competitor_observation(organization_id,account_id,id,revision,
              offer_id,source_id,seller_name,segment,observed_at,in_stock,price,delivery,own_offer,
              revoked,current_revision,author_id)
            VALUES (:org,:account,:id,:revision,:offer,:source,:seller,:segment,:observed,:stock,
              :price,:delivery,:own,:revoked,true,:author)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("id", id)
        .param("revision", revision)
        .param("offer", input.offerId())
        .param("source", input.sourceId())
        .param("seller", input.sellerName())
        .param("segment", input.segment())
        .param("observed", Timestamp.from(input.observedAt()))
        .param("stock", input.inStock())
        .param("price", input.price())
        .param("delivery", input.delivery())
        .param("own", input.ownOffer())
        .param("revoked", revoked)
        .param("author", scope.subjectId())
        .update();
  }

  private void lockAccount(Scope scope) {
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.requireOrganization())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
  }

  private Observation read(Scope scope, UUID id, boolean lock) {
    return jdbc.sql(
            """
            SELECT * FROM (%s) o WHERE organization_id=:org
              AND account_id=:account AND id=:id AND current_revision
            """
                    .formatted(OBSERVATIONS)
                + (lock ? " FOR UPDATE" : ""))
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", id)
        .query(this::map)
        .optional()
        .orElseThrow(
            () -> new BusinessException("OBSERVATION_NOT_FOUND", 404, "Наблюдение недоступно"));
  }

  private Observation map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Observation(
        rs.getObject("id", UUID.class),
        rs.getObject("offer_id", UUID.class),
        rs.getObject("source_id", UUID.class),
        rs.getString("seller_name"),
        rs.getString("segment"),
        rs.getTimestamp("observed_at").toInstant(),
        rs.getObject("in_stock", Boolean.class),
        rs.getBigDecimal("price"),
        rs.getBigDecimal("delivery"),
        rs.getBoolean("own_offer"),
        rs.getBoolean("revoked"),
        rs.getLong("revision"),
        rs.getString("offer_name"));
  }

  private void changed(Scope scope, UUID id, UUID offerId, long revision) {
    audit.recordForOffer(scope, "COMPETITOR_CHANGED", id, offerId, "revision=" + revision);
    outbox.emit(
        scope,
        id + ":" + revision,
        "COMPETITOR_CHANGED",
        new OutboxService.EntityChange("competitor-observations", id, revision));
  }
}
