package ru.oritas.repricer.marketplace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;

/** Ozon's documented amounts and physical proofs, without guessing missing monetary polarity. */
final class OzonFinancialCanonicalizer {
  private static final int MAX_NORMALIZED_BYTES = 262144;

  private OzonFinancialCanonicalizer() {}

  static String encode(Observation observation, JsonCodec json) {
    // Every string and the list of 1000 small components are bounded by the normalizer.
    // This record may exceed the independent 64 KiB limit for jobs and outbox messages.
    String encoded = json.gson().toJson(observation);
    requireSize(encoded);
    return encoded;
  }

  static Observation decode(String encoded, JsonCodec json) {
    requireSize(encoded);
    try (var reader = new StringReader(encoded)) {
      return json.decode(reader, Observation.class);
    } catch (IOException exception) {
      throw invalid();
    }
  }

  private static void requireSize(String encoded) {
    if (encoded.length() > MAX_NORMALIZED_BYTES
        || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_NORMALIZED_BYTES) {
      throw new BusinessException(
          "FINANCIAL_COMPONENT_LIMIT", 422, "Финансовая запись превышает допустимый размер");
    }
  }

  static Observation accrual(JsonObject source, LocalDate requestedDate) {
    String id = identifier(source, "accrual_id");
    LocalDate date;
    try {
      date = LocalDate.parse(text(source, "date", true));
    } catch (DateTimeParseException exception) {
      throw invalid();
    }
    if (!date.equals(requestedDate)) {
      throw invalid();
    }
    List<Component> amounts = new ArrayList<>();
    JsonObject posting = object(source, "posting");
    if (posting != null) {
      for (JsonElement element : array(posting, "products", 200)) {
        JsonObject product = asObject(element);
        String sku = identifier(product, "sku");
        JsonObject commission = object(product, "commission");
        if (commission != null) {
          money(amounts, commission, "sale_amount", sku, "SELLER_REVENUE");
          money(amounts, commission, "commission", sku, "COMMISSION_POLARITY_UNKNOWN");
          money(amounts, commission, "coinvestment", sku, "COINVESTMENT_SEMANTICS_UNKNOWN");
          money(amounts, commission, "bonus", sku, "NON_CASH_BONUS");
        }
        JsonObject delivery = object(product, "delivery");
        if (delivery != null) {
          fees(amounts, array(delivery, "services", 200), sku);
        }
      }
    }
    JsonObject nonItem = object(source, "non_item_fee");
    if (nonItem != null) {
      fee(amounts, nonItem, null);
    }
    JsonObject itemFees = object(source, "item_fees");
    if (itemFees != null) {
      for (JsonElement item : array(itemFees, "fees", 200)) {
        JsonObject row = asObject(item);
        fees(amounts, array(row, "fees", 200), identifier(row, "sku"));
      }
    }
    JsonObject container = object(source, "container_fees");
    if (container != null) {
      fees(amounts, array(container, "fees", 200), null);
    }
    // total_amount and delivery.total_accrued are aggregates of these components. They are
    // retained in the raw response, never recognized a second time as income or expense.
    return new Observation(
        "ACCRUAL", id, date, text(source, "unit_number", false), List.copyOf(amounts), null, null);
  }

  static Observation accrualType(JsonObject source) {
    String id = identifier(source, "id");
    return new Observation(
        "ACCRUAL_TYPE",
        id,
        null,
        null,
        List.of(),
        null,
        new AccrualType(text(source, "name", true), text(source, "description", true)));
  }

  static Observation returned(JsonObject source, String account) {
    if (!account.equals(identifier(source, "company_id"))) {
      throw new BusinessException(
          "SOURCE_ACCOUNT_MISMATCH", 422, "Источник относится к другому кабинету");
    }
    String id = identifier(source, "id");
    JsonObject product = requiredObject(source, "product");
    String quantityValue = text(product, "quantity", true);
    if (!quantityValue.matches("[1-9][0-9]{0,9}")) {
      throw invalid();
    }
    BigDecimal quantity = new BigDecimal(quantityValue);
    if (quantity.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
      throw invalid();
    }
    String kind = text(source, "type", true);
    if (!Set.of("Cancellation", "FullReturn", "PartialReturn", "ClientReturn", "Unknown")
        .contains(kind)) {
      throw invalid();
    }
    JsonObject logistic = object(source, "logistic");
    Instant returnedAt = instant(logistic, "return_date");
    Instant receivedAt = instant(logistic, "final_moment");
    JsonObject visual = object(source, "visual");
    JsonObject status = visual == null ? null : object(visual, "status");
    String state = status == null ? null : text(status, "sys_name", false);
    ReturnEvidence evidence =
        new ReturnEvidence(
            text(product, "offer_id", true),
            identifier(product, "sku"),
            quantity,
            returnedAt,
            receivedAt == null ? null : Boolean.TRUE,
            Set.of("MoneyReturned", "PartialCompensationReturned")
                    .contains(state == null ? "" : state)
                ? Boolean.TRUE
                : null,
            kind,
            text(source, "posting_number", false),
            text(source, "source_id", false));
    return new Observation("RETURN", id, null, null, List.of(), evidence, null);
  }

  private static void fees(List<Component> target, JsonArray source, String sku) {
    for (JsonElement element : source) {
      fee(target, asObject(element), sku);
    }
  }

  private static void fee(List<Component> target, JsonObject source, String sku) {
    money(target, source, "accrued", sku, "FEE_POLARITY_UNKNOWN:" + identifier(source, "type_id"));
  }

  private static void money(
      List<Component> target, JsonObject source, String field, String sku, String meaning) {
    JsonObject value = object(source, field);
    if (value == null) {
      return;
    }
    if (target.size() >= 1000) {
      throw new BusinessException(
          "FINANCIAL_COMPONENT_LIMIT", 422, "Запись содержит слишком много денежных компонентов");
    }
    String decimal = text(value, "amount", true);
    if (!decimal.matches("-?[0-9]{1,38}(\\.[0-9]{1,12})?")) {
      throw invalid();
    }
    BigDecimal amount;
    try {
      amount = new BigDecimal(decimal);
    } catch (NumberFormatException exception) {
      throw invalid();
    }
    String currency = text(value, "currency", true);
    if (amount.precision() > 38 || amount.scale() > 12 || !currency.matches("[A-Z]{3}")) {
      throw invalid();
    }
    target.add(new Component(meaning, sku, amount, currency));
  }

  private static JsonArray array(JsonObject source, String field, int maximum) {
    JsonElement value = source.get(field);
    if (value == null || value.isJsonNull()) {
      return new JsonArray();
    }
    if (!value.isJsonArray() || value.getAsJsonArray().size() > maximum) {
      throw invalid();
    }
    return value.getAsJsonArray();
  }

  private static JsonObject asObject(JsonElement element) {
    if (element == null || !element.isJsonObject()) {
      throw invalid();
    }
    return element.getAsJsonObject();
  }

  private static JsonObject object(JsonObject source, String field) {
    JsonElement element = source.get(field);
    return element == null || element.isJsonNull() ? null : asObject(element);
  }

  private static JsonObject requiredObject(JsonObject source, String field) {
    return asObject(source.get(field));
  }

  private static String identifier(JsonObject source, String field) {
    String value = text(source, field, true);
    if (!value.matches("[1-9][0-9]{0,19}")) {
      throw invalid();
    }
    return value;
  }

  private static String text(JsonObject source, String field, boolean required) {
    JsonElement element = source.get(field);
    if (element == null || element.isJsonNull()) {
      if (required) {
        throw invalid();
      }
      return null;
    }
    if (!element.isJsonPrimitive() || element.getAsJsonPrimitive().isBoolean()) {
      throw invalid();
    }
    String value = element.getAsString();
    if (value.isBlank() || value.length() > 2000) {
      throw invalid();
    }
    return value;
  }

  private static Instant instant(JsonObject source, String field) {
    if (source == null) {
      return null;
    }
    String value = text(source, field, false);
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException exception) {
      throw invalid();
    }
  }

  private static BusinessException invalid() {
    return new BusinessException(
        "OZON_FINANCIAL_RECORD_INVALID",
        422,
        "Финансовая запись площадки не соответствует контракту");
  }

  record Observation(
      String kind,
      String externalId,
      LocalDate date,
      String externalUnit,
      List<Component> components,
      ReturnEvidence returned,
      AccrualType accrualType) {}

  record Component(String meaning, String vendorSku, BigDecimal amount, String currency) {}

  record ReturnEvidence(
      String sku,
      String vendorSku,
      BigDecimal quantity,
      Instant returnedAt,
      Boolean physicalReceiptConfirmed,
      Boolean refundConfirmed,
      String kind,
      String postingNumber,
      String previousReturnId) {}

  record AccrualType(String name, String description) {}
}
