package ru.oritas.repricer.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.platform.BusinessException;

class OrderCanonicalizerTest {
  @Test
  void ozonShipmentRetainsParentAndNeverInventsOriginalQuantityOrOrderDate() {
    JsonObject result =
        OrderCanonicalizer.ozonPosting(
            JsonParser.parseString(
                    """
                    {"posting_number":"100-1-2","parent_posting_number":"100-1-1",
                      "order_id":9007199254740993,"order_number":"100-1","status":"delivered",
                      "in_process_at":"2026-10-01T10:00:00Z","customer":{"email":"private@example.invalid"},
                      "products":[{"offer_id":"sku-1","quantity":2}]}
                    """)
                .getAsJsonObject());
    assertThat(result.get("order_id").getAsString()).isEqualTo("9007199254740993");
    assertThat(result.get("parent_posting_number").getAsString()).isEqualTo("100-1-1");
    assertThat(result.has("created_at")).isFalse();
    assertThat(result.has("customer")).isFalse();
    assertThat(result.getAsJsonArray("items").get(0).getAsJsonObject().has("original")).isFalse();
  }

  @Test
  void originalCompositionReplacesCurrentCountsAndRetainsRemovedProducts() {
    JsonObject result =
        OrderCanonicalizer.yandex(
            order(),
            stats(
                """
                "items":[{"shopSku":"a","count":1,"initialCount":3}],
                "initialItems":[{"shopSku":"a","initialCount":3},
                  {"shopSku":"b","initialCount":2}]
                """));
    var items = result.getAsJsonArray("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).getAsJsonObject().get("original").getAsString()).isEqualTo("3");
    assertThat(items.get(1).getAsJsonObject().get("original").getAsString()).isEqualTo("2");
    assertThat(items.get(1).getAsJsonObject().get("current").getAsString()).isEqualTo("0");
  }

  @Test
  void localStatsTimeUsesDocumentedMoscowZone() {
    JsonObject stats = stats("\"items\":[{\"shopSku\":\"a\",\"count\":1}]");
    stats.addProperty("statusUpdateDate", "2026-10-01T13:00:00");
    var result = OrderCanonicalizer.yandex(order(), stats);
    assertThat(result.get("updatedAt").getAsString()).isEqualTo("2026-10-01T10:00:00Z");
  }

  @Test
  void ambiguousDuplicateLinesCannotMultiplyOriginalDemand() {
    assertThatThrownBy(
            () ->
                OrderCanonicalizer.yandex(
                    order(),
                    stats(
                        """
                        "items":[{"shopSku":"a","count":1},{"shopSku":"a","count":1}]
                        """)))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).code())
                    .isEqualTo("ORIGINAL_LINE_IDENTITY_AMBIGUOUS"));
  }

  @Test
  void staleStatsDoNotProveTheUpdatedOriginalComposition() {
    JsonObject stats = stats("\"items\":[{\"shopSku\":\"a\",\"count\":1}]");
    stats.addProperty("statusUpdateDate", "2026-10-01T08:00:00Z");
    assertThatThrownBy(() -> OrderCanonicalizer.yandex(order(), stats))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            exception ->
                assertThat(((BusinessException) exception).code())
                    .isEqualTo("ORIGINAL_COMPOSITION_STALE"));
  }

  @Test
  void fakeAndNegativeOrEmptyCompositionsNeverBecomeDemand() {
    JsonObject fake = order();
    fake.addProperty("fake", true);
    assertThatThrownBy(
            () ->
                OrderCanonicalizer.yandex(
                    fake, stats("\"items\":[{\"shopSku\":\"a\",\"count\":1}]")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () ->
                OrderCanonicalizer.yandex(
                    order(), stats("\"items\":[{\"shopSku\":\"a\",\"count\":-1}]")))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> OrderCanonicalizer.yandex(order(), stats("\"items\":[]")))
        .isInstanceOf(BusinessException.class);
  }

  private static JsonObject order() {
    return JsonParser.parseString(
            """
            {"orderId":123,"campaignId":456,"fake":false,
              "creationDate":"2026-10-01T07:00:00Z","updateDate":"2026-10-01T09:00:00Z"}
            """)
        .getAsJsonObject();
  }

  private static JsonObject stats(String items) {
    return JsonParser.parseString(
            "{\"id\":123,\"fake\":false,"
                + "\"statusUpdateDate\":\"2026-10-01T10:00:00Z\","
                + items
                + "}")
        .getAsJsonObject();
  }
}
