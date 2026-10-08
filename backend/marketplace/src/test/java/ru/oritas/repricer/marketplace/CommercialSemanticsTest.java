package ru.oritas.repricer.marketplace;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.platform.JsonCodec;

class CommercialSemanticsTest {
  @Test
  void promoPricesUseNestedIntegerRublesInsteadOfInternalDecimalStrings() {
    var body =
        CommercialSemantics.promotionBody(
            "promo", "001", new BigDecimal("1200.000000000000"), new BigDecimal("1000.0"));
    JsonObject wire = object(new JsonCodec().encode(body));
    JsonObject offer = wire.getAsJsonArray("offers").get(0).getAsJsonObject();
    assertThat(offer.has("price")).isFalse();
    JsonObject discount = offer.getAsJsonObject("params").getAsJsonObject("discountParams");
    assertThat(discount.getAsJsonPrimitive("price").isNumber()).isTrue();
    assertThat(discount.getAsJsonPrimitive("promoPrice").isNumber()).isTrue();
    assertThat(discount.get("price").getAsLong()).isEqualTo(1200);
    assertThat(discount.get("promoPrice").getAsLong()).isEqualTo(1000);
  }

  @Test
  void okEnvelopeDoesNotConfirmIndividuallyRejectedPromotionMutation() {
    JsonObject response =
        object(
            """
            {"status":"OK","result":{"rejectedOffers":[{"offerId":"001","reason":"OFFER_DOES_NOT_EXIST"}]}}
            """);
    assertThat(CommercialSemantics.yandexMutationAccepted(response, VendorMethod.YANDEX_SET_PROMO))
        .isFalse();
    assertThat(
            CommercialSemantics.yandexMutationAccepted(response, VendorMethod.YANDEX_LEAVE_PROMO))
        .isFalse();
    assertThat(
            CommercialSemantics.yandexMutationAccepted(
                object("{\"status\":\"OK\",\"result\":{}}"), VendorMethod.YANDEX_SET_PROMO))
        .isTrue();
    assertThat(
            CommercialSemantics.yandexMutationAccepted(
                object("{\"status\":\"OK\"}"), VendorMethod.YANDEX_SET_PROMO))
        .isFalse();
  }

  @Test
  void matchingPriceCannotHideDeletedBestsellerProtection() {
    JsonObject sent =
        object(
            """
            {"value":1000,"currencyId":"RUB","minimumForBestseller":950,"excludedFromBestsellers":true}
            """);
    JsonObject observed =
        object(
            """
            {"value":1000,"currencyId":"RUB","excludedFromBestsellers":true}
            """);
    assertThat(
            CommercialSemantics.companionsMatch(
                sent,
                observed,
                Set.of("currencyId", "minimumForBestseller", "excludedFromBestsellers")))
        .isFalse();
  }

  @Test
  void numericFormattingDoesNotChangeTheCompanionValueButCurrencyDoes() {
    JsonObject sent = object("{\"old_price\":\"1200.00\",\"currency_code\":\"RUB\"}");
    JsonObject observed = object("{\"old_price\":1200,\"currency_code\":\"RUB\"}");
    Set<String> fields = Set.of("old_price", "currency_code");
    assertThat(CommercialSemantics.companionsMatch(sent, observed, fields)).isTrue();
    observed.addProperty("currency_code", "USD");
    assertThat(CommercialSemantics.companionsMatch(sent, observed, fields)).isFalse();
  }

  private static JsonObject object(String json) {
    return JsonParser.parseString(json).getAsJsonObject();
  }
}
