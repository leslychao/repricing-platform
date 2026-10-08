package ru.oritas.repricer.economics;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.economics.calculation.EconomicCalculator.Outcome;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

@Service
public class SafetyEnvelopeService {
  public record OfferBounds(
      UUID offerId,
      long revision,
      boolean enabled,
      BigDecimal minimumPrice,
      BigDecimal maximumPrice) {
    public OfferBounds {
      if (offerId == null
          || revision < 0
          || (enabled && minimumPrice == null && maximumPrice == null)
          || (minimumPrice != null && (minimumPrice.signum() <= 0 || minimumPrice.scale() > 12))
          || (maximumPrice != null && (maximumPrice.signum() <= 0 || maximumPrice.scale() > 12))
          || (minimumPrice != null
              && maximumPrice != null
              && minimumPrice.compareTo(maximumPrice) > 0)) {
        throw new IllegalArgumentException("Invalid additional offer price bounds");
      }
    }
  }

  public record Envelope(
      long revision,
      BigDecimal minimumPrice,
      BigDecimal maximumPrice,
      BigDecimal minimumMargin,
      BigDecimal minimumProfit,
      Map<Outcome, BigDecimal> maximumOutcomeLoss) {
    public Envelope {
      maximumOutcomeLoss = Map.copyOf(maximumOutcomeLoss);
      if (revision < 0
          || minimumPrice == null
          || maximumPrice == null
          || minimumPrice.signum() <= 0
          || minimumPrice.compareTo(maximumPrice) > 0
          || minimumMargin == null
          || minimumMargin.signum() < 0
          || minimumMargin.compareTo(BigDecimal.ONE) >= 0
          || (minimumProfit != null && minimumProfit.signum() < 0)
          || maximumOutcomeLoss.values().stream().anyMatch(v -> v.signum() < 0)) {
        throw new IllegalArgumentException("Invalid account safety envelope");
      }
    }
  }

  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final OutboxService outbox;

  public SafetyEnvelopeService(
      JdbcClient jdbc,
      JsonCodec json,
      AuthorizationService authorization,
      AuditService audit,
      OutboxService outbox) {
    this.jdbc = jdbc;
    this.json = json;
    this.authorization = authorization;
    this.audit = audit;
    this.outbox = outbox;
  }

  public OfferBounds offerBounds(Scope scope, UUID offerId) {
    authorization.require(scope, "finance.read");
    return jdbc.sql(
            """
            SELECT * FROM economics_offer_price_bounds WHERE organization_id=:org AND account_id=:account
              AND offer_id=:offer AND current_revision
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("offer", offerId)
        .query(
            (rs, row) ->
                new OfferBounds(
                    offerId,
                    rs.getLong("revision"),
                    rs.getBoolean("enabled"),
                    rs.getBigDecimal("minimum_price"),
                    rs.getBigDecimal("maximum_price")))
        .optional()
        .orElse(new OfferBounds(offerId, 0, false, null, null));
  }

  @Transactional
  public OfferBounds publishOfferBounds(Scope scope, OfferBounds input) {
    authorization.require(scope, "finance.write");
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    OfferBounds current = offerBounds(scope, input.offerId());
    if (input.revision() != current.revision()) {
      throw new BusinessException("STALE_REVISION", 409, "Ценовые границы уже изменены");
    }
    if (input.equals(current)) {
      return current;
    }
    jdbc.sql(
            """
            UPDATE economics_offer_price_bounds SET current_revision=false
            WHERE organization_id=:org AND account_id=:account AND offer_id=:offer AND current_revision
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("offer", input.offerId())
        .update();
    jdbc.sql(
            """
            INSERT INTO economics_offer_price_bounds(organization_id,account_id,offer_id,revision,
              enabled,minimum_price,maximum_price,current_revision,author_id)
            VALUES (:org,:account,:offer,:revision,:enabled,:minimum,:maximum,true,:author)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("offer", input.offerId())
        .param("revision", input.revision() + 1)
        .param("enabled", input.enabled())
        .param("minimum", input.minimumPrice())
        .param("maximum", input.maximumPrice())
        .param("author", scope.subjectId())
        .update();
    audit.recordForOffer(
        scope,
        "OFFER_PRICE_BOUNDS_PUBLISHED",
        input.offerId(),
        input.offerId(),
        "revision=" + (input.revision() + 1));
    outbox.emit(
        scope,
        "OFFER_BOUNDS:" + input.offerId() + ":" + (input.revision() + 1),
        "OFFER_PRICE_BOUNDS_PUBLISHED",
        new OutboxService.EntityChange("safety-envelopes", input.offerId(), input.revision() + 1));
    return new OfferBounds(
        input.offerId(),
        input.revision() + 1,
        input.enabled(),
        input.minimumPrice(),
        input.maximumPrice());
  }

  @Transactional
  public void publishPriceImport(Scope scope, UUID importId) {
    authorization.require(scope, "finance.write");
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    String state =
        jdbc.sql(
                """
                SELECT state FROM economics_import WHERE organization_id=:org AND account_id=:account
                  AND id=:id AND kind='PRICE_PARAMETERS' FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", importId)
            .query(String.class)
            .single();
    if (state.equals("APPLIED")) {
      return;
    }
    if (!state.equals("PREPARED")) {
      throw new BusinessException("IMPORT_NOT_PREPARED", 409, "Импорт не подготовлен");
    }
    boolean stale =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM economics_price_import_row r
                  LEFT JOIN economics_offer_price_bounds b ON b.organization_id=r.organization_id
                    AND b.account_id=r.account_id AND b.offer_id=r.offer_id AND b.current_revision
                  WHERE r.organization_id=:org AND r.account_id=:account AND r.import_id=:id
                    AND r.expected_revision<>COALESCE(b.revision,0))
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", importId)
            .query(Boolean.class)
            .single();
    if (stale) {
      throw new BusinessException(
          "STALE_REVISION", 409, "Ценовые параметры изменились после проверки");
    }
    jdbc.sql(
            """
            UPDATE economics_offer_price_bounds b SET current_revision=false FROM economics_price_import_row r
            WHERE r.organization_id=:org AND r.account_id=:account AND r.import_id=:id
              AND b.organization_id=r.organization_id AND b.account_id=r.account_id
              AND b.offer_id=r.offer_id AND b.current_revision
              AND (b.enabled,b.minimum_price,b.maximum_price) IS DISTINCT FROM (r.enabled,r.minimum_price,r.maximum_price)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", importId)
        .update();
    jdbc.sql(
            """
            INSERT INTO economics_offer_price_bounds(organization_id,account_id,offer_id,revision,
              enabled,minimum_price,maximum_price,current_revision,author_id)
            SELECT r.organization_id,r.account_id,r.offer_id,r.expected_revision+1,
              r.enabled,r.minimum_price,r.maximum_price,true,:author FROM economics_price_import_row r
            LEFT JOIN economics_offer_price_bounds b ON b.organization_id=r.organization_id
              AND b.account_id=r.account_id AND b.offer_id=r.offer_id AND b.revision=r.expected_revision
            WHERE r.organization_id=:org AND r.account_id=:account AND r.import_id=:id
              AND (COALESCE(b.enabled,false),b.minimum_price,b.maximum_price)
                IS DISTINCT FROM (r.enabled,r.minimum_price,r.maximum_price)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", importId)
        .param("author", scope.subjectId())
        .update();
    jdbc.sql(
            """
            UPDATE economics_import SET state='APPLIED',applied_at=clock_timestamp()
            WHERE organization_id=:org AND account_id=:account AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("id", importId)
        .update();
    audit.record(scope, "PRICE_PARAMETERS_IMPORTED", importId, "atomic input publication");
    outbox.emit(
        scope,
        "PRICE_PARAMETERS:" + importId,
        "PRICE_PARAMETERS_PUBLISHED",
        new OutboxService.EntityChange("safety-envelopes", importId, 1));
  }

  public Envelope get(Scope scope) {
    authorization.require(scope, "finance.read");
    return jdbc.sql(
            """
            SELECT settings::text FROM economics_safety_envelope
            WHERE organization_id=:org AND account_id=:account AND current_revision
            """)
        .param("org", scope.requireOrganization())
        .param("account", scope.requireAccount())
        .query(String.class)
        .optional()
        .map(value -> json.decode(value, Envelope.class))
        .orElseThrow(
            () ->
                new BusinessException(
                    "SAFETY_ENVELOPE_REQUIRED",
                    409,
                    "Задайте обязательные денежные границы кабинета"));
  }

  @Transactional
  public Envelope publish(Scope scope, Envelope input) {
    authorization.require(scope, "finance.write");
    jdbc.sql(
            """
            SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR UPDATE
            """)
        .param("org", scope.requireOrganization())
        .param("account", scope.requireAccount())
        .query(java.util.UUID.class)
        .single();
    long previous =
        jdbc.sql(
                """
                SELECT revision FROM economics_safety_envelope
                WHERE organization_id=:org AND account_id=:account AND current_revision
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .query(Long.class)
            .optional()
            .orElse(0L);
    if (previous != input.revision()) {
      throw new BusinessException("STALE_REVISION", 409, "Границы уже изменены");
    }
    Envelope published =
        new Envelope(
            previous + 1,
            input.minimumPrice(),
            input.maximumPrice(),
            input.minimumMargin(),
            input.minimumProfit(),
            input.maximumOutcomeLoss());
    jdbc.sql(
            """
            UPDATE economics_safety_envelope SET current_revision=false
            WHERE organization_id=:org AND account_id=:account AND current_revision
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .update();
    jdbc.sql(
            """
            INSERT INTO economics_safety_envelope
              (organization_id,account_id,revision,settings,current_revision,author_id)
            VALUES (:org,:account,:revision,CAST(:settings AS jsonb),true,:author)
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("revision", published.revision())
        .param("settings", json.encode(published))
        .param("author", scope.subjectId())
        .update();
    audit.record(
        scope, "SAFETY_ENVELOPE_PUBLISHED", scope.accountId(), "revision=" + published.revision());
    outbox.emit(
        scope,
        "SAFETY_ENVELOPE:" + published.revision(),
        "SAFETY_ENVELOPE_PUBLISHED",
        new OutboxService.EntityChange("safety-envelope", scope.accountId(), published.revision()));
    return published;
  }
}
