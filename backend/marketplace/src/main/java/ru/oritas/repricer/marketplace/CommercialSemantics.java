package ru.oritas.repricer.marketplace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

/** Interprets vendor price fields once and preserves the original scope during reconciliation. */
@Service
public final class CommercialSemantics implements CommercialGateway {
  private static final Set<String> OZON_COMPANIONS =
      Set.of(
          "old_price",
          "min_price",
          "currency_code",
          "vat",
          "min_price_for_auto_actions_enabled",
          "price_strategy_enabled");
  private static final Set<String> YANDEX_COMPANIONS =
      Set.of("currencyId", "discountBase", "minimumForBestseller", "excludedFromBestsellers");
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ConnectionService connections;
  private final CapabilityService capabilities;
  private final OutboundGateway gateway;
  private final StoredFileService files;
  private final JsonCodec json;
  private final Clock clock;

  public CommercialSemantics(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      CapabilityService capabilities,
      OutboundGateway gateway,
      StoredFileService files,
      JsonCodec json,
      Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.connections = connections;
    this.capabilities = capabilities;
    this.gateway = gateway;
    this.files = files;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public PreparedCommercialCommand prepare(Scope scope, CommercialIntent intent) {
    authorization.require(scope, "decision.approve");
    if (intent.committedQuantity() != null) {
      // None of the currently confirmed write profiles has an allocating quantity field.
      throw unavailable("QUANTITY_ALLOCATION_UNSUPPORTED");
    }
    ConnectionService.Credentials credentials = connections.current(scope);
    boolean ozon = credentials.marketplace().equals("OZON");
    VendorMethod method = method(ozon, intent.operation());
    CapabilityService.Profile profile =
        capabilities.requireWrite(scope, method, intent.expectedProfileRevision());
    ConfirmedContract contract = json.decode(profile.constraints(), ConfirmedContract.class);
    if (!contract.writeTested()
        || !contract.originalScopeComplete()
        || contract.companionFields() == null) {
      throw unavailable("WRITE_CONTRACT_INCOMPLETE");
    }
    Offer offer = offer(intent.offerId());
    if (offer.revision() != intent.expectedSourceRevision()
        || offer.fields() == null
        || offer.observedAt().plusSeconds(900).isBefore(clock.instant())) {
      throw unavailable("COMMERCIAL_SOURCE_STALE");
    }
    if (!intent.targetId().equals(intent.offerId())) {
      throw unavailable("TARGET_SCOPE_UNCONFIRMED");
    }
    if (intent.operation() != Operation.SET_PRICE) {
      return preparePromotion(scope, intent, credentials, profile, contract, offer, method);
    }
    if (intent.price() == null
        || intent.price().signum() <= 0
        || intent.price().precision() > 38
        || intent.price().scale() > 2) {
      throw new BusinessException("INVALID_PRICE", 422, "Некорректная цена для площадки");
    }
    JsonObject source = JsonParser.parseString(offer.fields()).getAsJsonObject();
    JsonObject preserved =
        preserve(source, contract.companionFields(), ozon ? OZON_COMPANIONS : YANDEX_COMPANIONS);
    List<CommercialEffect> effects = new ArrayList<>();
    effects.add(new CommercialEffect(intent.targetId(), "sellerPrice", intent.price(), null));
    String body;
    if (ozon) {
      if (intent.renewMinimumProtection() && !contract.minimumProtectionRenewalTested()) {
        throw unavailable("MINIMUM_PROTECTION_RENEWAL_UNCONFIRMED");
      }
      preserved.addProperty("offer_id", offer.sku());
      preserved.addProperty("price", intent.price().toPlainString());
      body = json.encode(Map.of("prices", List.of(preserved)));
    } else {
      if (!source.has("minimumForBestseller") || !source.has("excludedFromBestsellers")) {
        throw unavailable("BESTSELLER_STATE_UNKNOWN");
      }
      if (!contract
          .companionFields()
          .containsAll(Set.of("minimumForBestseller", "excludedFromBestsellers"))) {
        throw unavailable("BESTSELLER_PRESERVATION_UNCONFIRMED");
      }
      preserved.addProperty("value", intent.price());
      body =
          json.encode(
              Map.of("offers", List.of(Map.of("offerId", offer.sku(), "price", preserved))));
    }
    return prepared(profile, method, credentials, body, offer.revision(), effects);
  }

  private PreparedCommercialCommand preparePromotion(
      Scope scope,
      CommercialIntent intent,
      ConnectionService.Credentials credentials,
      CapabilityService.Profile profile,
      ConfirmedContract contract,
      Offer offer,
      VendorMethod method) {
    if (credentials.marketplace().equals("OZON")
        || intent.promotionId() == null
        || !contract.processingReadTested()) {
      throw unavailable("PROMOTION_CONTRACT_UNCONFIRMED");
    }
    Promotion promotion =
        jdbc.sql(
                """
                SELECT external_id,promotion_type,base_price,minimum_price,maximum_price,revision
                FROM marketplace_promotion_offer WHERE offer_id=:offer AND promotion_id=:promo
                  AND complete AND valid_until>clock_timestamp()
                """)
            .param("offer", intent.offerId())
            .param("promo", intent.promotionId())
            .query(
                (row, index) ->
                    new Promotion(
                        row.getString("external_id"),
                        row.getString("promotion_type"),
                        row.getBigDecimal("base_price"),
                        row.getBigDecimal("minimum_price"),
                        row.getBigDecimal("maximum_price")))
            .optional()
            .orElseThrow(() -> unavailable("PROMOTION_TERMS_UNKNOWN"));
    String body;
    boolean joining = intent.operation() != Operation.LEAVE_PROMO;
    var effects = new ArrayList<CommercialEffect>();
    effects.add(
        new CommercialEffect(
            intent.targetId(), "participation:" + intent.promotionId(), null, joining));
    if (!joining) {
      body =
          json.encode(Map.of("promoId", promotion.externalId(), "offerIds", List.of(offer.sku())));
    } else {
      BigDecimal price = intent.price();
      if (!Set.of("DIRECT_DISCOUNT", "BLUE_FLASH").contains(promotion.type())
          || promotion.base() == null
          || price == null
          || price.stripTrailingZeros().scale() > 0
          || promotion.base().stripTrailingZeros().scale() > 0
          || price.compareTo(BigDecimal.ONE) < 0
          || price.compareTo(promotion.base().multiply(new BigDecimal("0.01"))) < 0
          || price.compareTo(promotion.base().multiply(new BigDecimal("0.95"))) > 0
          || promotion.minimum() == null
          || promotion.maximum() == null
          || price.compareTo(promotion.minimum()) < 0
          || price.compareTo(promotion.maximum()) > 0) {
        throw unavailable("PROMOTION_PRICE_OUTSIDE_CONFIRMED_TERMS");
      }
      body =
          json.encode(promotionBody(promotion.externalId(), offer.sku(), promotion.base(), price));
      effects.add(
          new CommercialEffect(
              intent.targetId(), "promotionPrice:" + intent.promotionId(), price, null));
    }
    return prepared(profile, method, credentials, body, offer.revision(), effects);
  }

  @Override
  public Admission admit(Scope scope, UUID commandId, PreparedCommercialCommand command) {
    VendorMethod method = VendorMethod.valueOf(command.method());
    capabilities.requireWrite(scope, method, command.profileRevision());
    if (!IdempotencyService.sha256(command.canonicalBody().getBytes(StandardCharsets.UTF_8))
            .equals(command.digest())
        || !clock.instant().isBefore(command.profileExpiresAt())) {
      throw unavailable("PREPARED_COMMAND_CHANGED");
    }
    ConnectionService.Credentials connection = connections.current(scope);
    if (!method
        .endpoint(connection.externalId(), null, null)
        .toString()
        .equals(command.endpoint())) {
      throw unavailable("TARGET_SCOPE_CHANGED");
    }
    return gateway.admit(
        scope, commandId, method, command.canonicalBody(), command.effects().size());
  }

  @Override
  public SendResult send(Scope scope, UUID commandId, PreparedCommercialCommand command) {
    try {
      OutboundGateway.Response response =
          gateway.execute(
              scope,
              VendorMethod.valueOf(command.method()),
              command.canonicalBody(),
              null,
              null,
              command.effects().size(),
              commandId);
      if (!response.successful()) {
        boolean rejected = Set.of(400, 401, 403, 404, 422, 429).contains(response.status());
        return new SendResult(
            rejected ? Outcome.REJECTED : Outcome.UNKNOWN,
            response.raw().id(),
            rejected ? "SUPPLIER_REJECTED" : "SUPPLIER_OUTCOME_UNKNOWN");
      }
      boolean accepted = originalAccepted(response, VendorMethod.valueOf(command.method()));
      return new SendResult(
          accepted ? Outcome.ACCEPTED : Outcome.UNKNOWN,
          response.raw().id(),
          accepted ? null : "ITEM_ACCEPTANCE_NOT_PROVEN");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new SendResult(Outcome.UNKNOWN, null, "TRANSPORT_INTERRUPTED");
    } catch (IOException | RuntimeException exception) {
      return new SendResult(Outcome.UNKNOWN, null, "TRANSPORT_OUTCOME_UNKNOWN");
    }
  }

  @Override
  public Reconciliation reconcile(Scope scope, UUID commandId, PreparedCommercialCommand command) {
    VendorMethod sent = VendorMethod.valueOf(command.method());
    try {
      OutboundGateway.Response original =
          transactions.runService(
              scope, Set.of("marketplace.reconcile", "catalog.read"), () -> original(commandId));
      if (original == null || !original.successful() || !originalAccepted(original, sent)) {
        return unknown(command, null, "ORIGINAL_ACCEPTANCE_NOT_PROVEN");
      }
      if (sent != VendorMethod.OZON_SET_PRICE && sent != VendorMethod.YANDEX_SET_PRICE) {
        return reconcilePromotion(scope, commandId, command);
      }
      JsonObject sentBody = JsonParser.parseString(command.canonicalBody()).getAsJsonObject();
      boolean ozon = sent == VendorMethod.OZON_SET_PRICE;
      JsonObject target =
          sentBody.getAsJsonArray(ozon ? "prices" : "offers").get(0).getAsJsonObject();
      String sku = CatalogSyncService.text(target, ozon ? "offer_id" : "offerId", true);
      String body =
          ozon
              ? json.encode(
                  Map.of(
                      "filter",
                      Map.of("offer_id", List.of(sku), "visibility", "ALL"),
                      "limit",
                      100))
              : json.encode(Map.of("offerIds", List.of(sku)));
      String cursor = null;
      UUID evidence = null;
      BigDecimal observed = null;
      boolean companionsPreserved = false;
      int matching = 0;
      for (int page = 0; page < 20; page++) {
        var response =
            gateway.readReconciliation(
                scope,
                commandId,
                ozon ? VendorMethod.OZON_PRICES : VendorMethod.YANDEX_PRICES,
                body,
                cursor,
                1);
        if (response.retryAt() != null || !response.successful()) {
          return unknown(command, evidence, "CONTROL_READ_PENDING");
        }
        var values = new ArrayList<PriceObservation>(1);
        VendorJsonReader.PageInfo info;
        try (InputStream input = gateway.open(response)) {
          info =
              VendorJsonReader.parse(
                  input,
                  ozon ? "items" : "result.offers",
                  item -> {
                    if (!sku.equals(
                        CatalogSyncService.text(item, ozon ? "offer_id" : "offerId", true))) {
                      throw unavailable("CONTROL_SCOPE_CHANGED");
                    }
                    BigDecimal value =
                        CatalogSyncService.decimal(
                            item.getAsJsonObject("price"), ozon ? "price" : "value");
                    if (value == null || values.size() >= 1) {
                      throw unavailable("CONTROL_SCOPE_CHANGED");
                    }
                    JsonObject expected = ozon ? target : target.getAsJsonObject("price");
                    JsonObject actual = item.getAsJsonObject("price");
                    values.add(
                        new PriceObservation(
                            value,
                            companionsMatch(
                                expected, actual, ozon ? OZON_COMPANIONS : YANDEX_COMPANIONS)));
                  });
        }
        matching += values.size();
        if (!values.isEmpty()) {
          observed = values.getFirst().value();
          companionsPreserved = values.getFirst().companionsPreserved();
          evidence = response.raw().id();
        }
        if (info.cursor() == null || info.cursor().isBlank()) {
          if (matching != 1) {
            return unknown(command, evidence, "CONTROL_SCOPE_INCOMPLETE");
          }
          var effects = new ArrayList<EffectEvidence>();
          for (CommercialEffect effect : command.effects()) {
            boolean matches =
                companionsPreserved
                    && effect.field().equals("sellerPrice")
                    && effect.expectedValue() != null
                    && effect.expectedValue().compareTo(observed) == 0;
            effects.add(
                new EffectEvidence(
                    effect.targetId(),
                    effect.field(),
                    matches ? EffectState.CONFIRMED : EffectState.UNKNOWN,
                    evidence,
                    matches ? null : "EXPECTED_VALUE_NOT_OBSERVED"));
          }
          return new Reconciliation(true, true, effects);
        }
        if (info.cursor().equals(cursor)) {
          return unknown(command, evidence, "CONTROL_CURSOR_STALLED");
        }
        cursor = info.cursor();
        if (ozon) {
          body =
              json.encode(
                  Map.of(
                      "filter",
                      Map.of("offer_id", List.of(sku), "visibility", "ALL"),
                      "limit",
                      100,
                      "cursor",
                      cursor));
        }
      }
      return unknown(command, evidence, "CONTROL_PAGE_LIMIT");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return unknown(command, null, "CONTROL_INTERRUPTED");
    } catch (IOException | RuntimeException exception) {
      return unknown(command, null, "CONTROL_UNAVAILABLE");
    }
  }

  private Reconciliation reconcilePromotion(
      Scope scope, UUID commandId, PreparedCommercialCommand command)
      throws IOException, InterruptedException {
    JsonObject sent = JsonParser.parseString(command.canonicalBody()).getAsJsonObject();
    String promoId = CatalogSyncService.text(sent, "promoId", true);
    String sku =
        sent.has("offerIds")
            ? sent.getAsJsonArray("offerIds").get(0).getAsString()
            : sent.getAsJsonArray("offers").get(0).getAsJsonObject().get("offerId").getAsString();
    var promotions =
        gateway.readReconciliation(scope, commandId, VendorMethod.YANDEX_PROMOS, "{}", null, 1);
    if (promotions.retryAt() != null || !promotions.successful()) {
      return unknown(command, null, "PROMOTION_CONTROL_PENDING");
    }
    List<Boolean> processing = new ArrayList<>(1);
    try (InputStream input = gateway.open(promotions)) {
      VendorJsonReader.parse(
          input,
          "result.promos",
          item -> {
            if (promoId.equals(CatalogSyncService.text(item, "id", true))) {
              JsonObject assortment = item.getAsJsonObject("assortmentInfo");
              if (assortment == null || !assortment.has("processing") || processing.size() >= 1) {
                throw unavailable("PROMOTION_PROCESSING_UNKNOWN");
              }
              processing.add(assortment.get("processing").getAsBoolean());
            }
          });
    }
    if (processing.size() != 1 || processing.getFirst()) {
      return unknown(command, promotions.raw().id(), "PROMOTION_PROCESSING_PENDING");
    }
    String cursor = null;
    List<PromotionObservation> matches = new ArrayList<>(1);
    for (int page = 0; page < 100; page++) {
      var response =
          gateway.readReconciliation(
              scope,
              commandId,
              VendorMethod.YANDEX_PROMO_OFFERS,
              json.encode(Map.of("promoId", promoId)),
              cursor,
              100);
      if (response.retryAt() != null || !response.successful()) {
        return unknown(command, null, "PROMOTION_CONTROL_PENDING");
      }
      VendorJsonReader.PageInfo info;
      try (InputStream input = gateway.open(response)) {
        info =
            VendorJsonReader.parse(
                input,
                "result.offers",
                item -> {
                  if (!sku.equals(CatalogSyncService.text(item, "offerId", true))) {
                    return;
                  }
                  if (!matches.isEmpty()) {
                    throw unavailable("PROMOTION_SCOPE_CONFLICT");
                  }
                  String status = CatalogSyncService.text(item, "status", true);
                  JsonObject params = item.getAsJsonObject("params");
                  JsonObject discount =
                      params == null ? null : params.getAsJsonObject("discountParams");
                  matches.add(
                      new PromotionObservation(
                          status,
                          discount == null ? null : CatalogSyncService.decimal(discount, "price"),
                          discount == null
                              ? null
                              : CatalogSyncService.decimal(discount, "promoPrice"),
                          response.raw().id()));
                });
      }
      if (info.cursor() == null || info.cursor().isBlank()) {
        if (matches.size() != 1) {
          return unknown(command, response.raw().id(), "PROMOTION_SCOPE_INCOMPLETE");
        }
        PromotionObservation observed = matches.getFirst();
        List<EffectEvidence> effects = new ArrayList<>();
        for (CommercialEffect effect : command.effects()) {
          boolean confirmed;
          if (effect.field().startsWith("participation:")) {
            confirmed =
                Boolean.FALSE.equals(effect.expectedParticipation())
                    ? observed.status().equals("NOT_PARTICIPATING")
                    : observed.status().equals("MANUAL");
          } else {
            BigDecimal base =
                sent.getAsJsonArray("offers")
                    .get(0)
                    .getAsJsonObject()
                    .getAsJsonObject("params")
                    .getAsJsonObject("discountParams")
                    .get("price")
                    .getAsBigDecimal();
            confirmed =
                effect.field().startsWith("promotionPrice:")
                    && observed.status().equals("MANUAL")
                    && observed.price() != null
                    && observed.base() != null
                    && observed.base().compareTo(base) == 0
                    && effect.expectedValue() != null
                    && observed.price().compareTo(effect.expectedValue()) == 0;
          }
          effects.add(
              new EffectEvidence(
                  effect.targetId(),
                  effect.field(),
                  confirmed ? EffectState.CONFIRMED : EffectState.UNKNOWN,
                  observed.raw(),
                  confirmed ? null : "PROMOTION_EXPECTED_STATE_NOT_OBSERVED"));
        }
        return new Reconciliation(true, true, effects);
      }
      if (info.cursor().equals(cursor)) {
        return unknown(command, response.raw().id(), "PROMOTION_CURSOR_STALLED");
      }
      cursor = info.cursor();
    }
    return unknown(command, null, "PROMOTION_CONTROL_PAGE_LIMIT");
  }

  private boolean originalAccepted(OutboundGateway.Response response, VendorMethod method)
      throws IOException {
    if (method.marketplace().equals("OZON")) {
      List<Boolean> accepted = new ArrayList<>(1);
      try (InputStream input = gateway.open(response)) {
        VendorJsonReader.parse(
            input,
            "result",
            item -> {
              boolean updated = item.has("updated") && item.get("updated").getAsBoolean();
              boolean errors = item.has("errors") && !item.getAsJsonArray("errors").isEmpty();
              accepted.add(updated && !errors);
            });
      }
      return accepted.size() == 1 && accepted.getFirst();
    }
    // The documented successful mutation body has a checked small envelope.
    try (InputStream input = gateway.open(response)) {
      byte[] bytes = input.readNBytes(65537);
      if (bytes.length > 65536 || input.read() != -1) {
        return false;
      }
      JsonObject envelope =
          JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
      return yandexMutationAccepted(envelope, method);
    }
  }

  static JsonObject promotionBody(String promoId, String sku, BigDecimal base, BigDecimal price) {
    JsonObject discount = new JsonObject();
    try {
      discount.addProperty("price", base.longValueExact());
      discount.addProperty("promoPrice", price.longValueExact());
    } catch (ArithmeticException exception) {
      throw unavailable("PROMOTION_PRICE_OUTSIDE_CONFIRMED_TERMS");
    }
    JsonObject params = new JsonObject();
    params.add("discountParams", discount);
    JsonObject offer = new JsonObject();
    offer.addProperty("offerId", sku);
    offer.add("params", params);
    var offers = new JsonArray();
    offers.add(offer);
    JsonObject body = new JsonObject();
    body.addProperty("promoId", promoId);
    body.add("offers", offers);
    return body;
  }

  static boolean yandexMutationAccepted(JsonObject envelope, VendorMethod method) {
    if (!envelope.has("status")
        || !envelope.get("status").getAsString().equals("OK")
        || envelope.has("errors")
            && !envelope.get("errors").isJsonNull()
            && !envelope.getAsJsonArray("errors").isEmpty()) {
      return false;
    }
    if (method != VendorMethod.YANDEX_SET_PROMO && method != VendorMethod.YANDEX_LEAVE_PROMO) {
      return true;
    }
    // Both SKU-scoped promotion mutations report individual refusals inside result.
    if (!envelope.has("result") || !envelope.get("result").isJsonObject()) {
      return false;
    }
    JsonObject result = envelope.getAsJsonObject("result");
    return !result.has("rejectedOffers")
        || result.get("rejectedOffers").isJsonNull()
        || result.get("rejectedOffers").isJsonArray()
            && result.getAsJsonArray("rejectedOffers").isEmpty();
  }

  private OutboundGateway.Response original(UUID commandId) {
    return jdbc.sql(
            """
            SELECT status_code,raw_file_id,content_encoding,connection_revision
            FROM marketplace_external_call WHERE id=:id AND outcome='RESPONSE'
            """)
        .param("id", commandId)
        .query(
            (row, index) ->
                new OutboundGateway.Response(
                    commandId,
                    row.getInt("status_code"),
                    files.get(row.getObject("raw_file_id", UUID.class)),
                    row.getString("content_encoding"),
                    null,
                    row.getLong("connection_revision")))
        .optional()
        .orElse(null);
  }

  private Offer offer(UUID id) {
    return jdbc.sql(
            """
            SELECT sku,revision,commercial_fields::text,observed_at FROM marketplace_offer WHERE id=:id
            """)
        .param("id", id)
        .query(
            (row, index) ->
                new Offer(
                    row.getString("sku"),
                    row.getLong("revision"),
                    row.getString("commercial_fields"),
                    row.getTimestamp("observed_at").toInstant()))
        .single();
  }

  private PreparedCommercialCommand prepared(
      CapabilityService.Profile profile,
      VendorMethod method,
      ConnectionService.Credentials credentials,
      String body,
      long sourceRevision,
      List<CommercialEffect> effects) {
    return new PreparedCommercialCommand(
        profile.id(),
        profile.revision(),
        profile.expiresAt(),
        method.name(),
        method.endpoint(credentials.externalId(), null, null).toString(),
        body,
        IdempotencyService.sha256(body.getBytes(StandardCharsets.UTF_8)),
        sourceRevision,
        effects);
  }

  private static JsonObject preserve(JsonObject source, Set<String> required, Set<String> allowed) {
    if (!allowed.containsAll(required)) {
      throw unavailable("UNKNOWN_COMPANION_FIELD");
    }
    var result = new JsonObject();
    for (String field : required) {
      if (!source.has(field) || source.get(field).isJsonNull()) {
        throw unavailable("COMPANION_VALUE_UNKNOWN");
      }
      result.add(field, source.get(field).deepCopy());
    }
    return result;
  }

  static boolean companionsMatch(JsonObject expected, JsonObject actual, Set<String> fields) {
    if (actual == null) {
      return false;
    }
    for (String field : fields) {
      if (!expected.has(field)) {
        continue;
      }
      if (!actual.has(field) || actual.get(field).isJsonNull()) {
        return false;
      }
      if (Set.of("old_price", "min_price", "discountBase", "minimumForBestseller")
          .contains(field)) {
        if (expected.get(field).getAsBigDecimal().compareTo(actual.get(field).getAsBigDecimal())
            != 0) {
          return false;
        }
      } else if (!expected.get(field).equals(actual.get(field))) {
        return false;
      }
    }
    return true;
  }

  private static VendorMethod method(boolean ozon, Operation operation) {
    return switch (operation) {
      case SET_PRICE -> ozon ? VendorMethod.OZON_SET_PRICE : VendorMethod.YANDEX_SET_PRICE;
      case JOIN_PROMO, SET_PROMO_PRICE -> {
        if (ozon) {
          throw unavailable("OZON_PROMOTION_V2_CONTRACT_UNCONFIRMED");
        }
        yield VendorMethod.YANDEX_SET_PROMO;
      }
      case LEAVE_PROMO -> {
        if (ozon) {
          throw unavailable("OZON_PROMOTION_V2_CONTRACT_UNCONFIRMED");
        }
        yield VendorMethod.YANDEX_LEAVE_PROMO;
      }
    };
  }

  private static Reconciliation unknown(
      PreparedCommercialCommand command, UUID raw, String reason) {
    return new Reconciliation(
        false,
        false,
        command.effects().stream()
            .map(
                effect ->
                    new EffectEvidence(
                        effect.targetId(), effect.field(), EffectState.UNKNOWN, raw, reason))
            .toList());
  }

  private static BusinessException unavailable(String code) {
    return new BusinessException(code, 422, "Площадочная семантика действия не подтверждена");
  }

  private record Offer(String sku, long revision, String fields, java.time.Instant observedAt) {}

  private record Promotion(
      String externalId, String type, BigDecimal base, BigDecimal minimum, BigDecimal maximum) {}

  private record PromotionObservation(String status, BigDecimal base, BigDecimal price, UUID raw) {}

  private record PriceObservation(BigDecimal value, boolean companionsPreserved) {}

  private record ConfirmedContract(
      boolean writeTested,
      boolean originalScopeComplete,
      boolean processingReadTested,
      boolean minimumProtectionRenewalTested,
      Set<String> companionFields) {}
}
