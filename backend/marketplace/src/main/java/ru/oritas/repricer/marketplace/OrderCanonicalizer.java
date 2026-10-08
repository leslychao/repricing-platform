package ru.oritas.repricer.marketplace;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import ru.oritas.repricer.platform.BusinessException;

/** Reconstructs original demand from stats, without adding current and initial quantities. */
final class OrderCanonicalizer {
  private OrderCanonicalizer() {}

  /** A posting's quantity and processing date do not prove original order composition or date. */
  static JsonObject ozonPosting(JsonObject posting) {
    JsonObject result = new JsonObject();
    for (String field : new String[] {"posting_number", "order_id", "order_number", "status"}) {
      result.addProperty(field, CatalogSyncService.text(posting, field, true));
    }
    String parent =
        posting.has("parent_posting_number") && !posting.get("parent_posting_number").isJsonNull()
            ? posting.get("parent_posting_number").getAsString()
            : "";
    if (parent.length() > 256 || parent.equals(result.get("posting_number").getAsString())) {
      throw invalid("SHIPMENT_PARENT_INVALID");
    }
    result.addProperty("parent_posting_number", parent);
    for (String field : new String[] {"created_at", "in_process_at"}) {
      if (posting.has(field) && !posting.get(field).isJsonNull()) {
        result.addProperty(field, instant(posting, field).toString());
      }
    }
    JsonArray items = new JsonArray();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (JsonElement value : array(posting, "products", true)) {
      JsonObject source = product(value);
      String sku = CatalogSyncService.text(source, "offer_id", true);
      if (!seen.add(sku)) {
        throw invalid("SHIPMENT_LINE_IDENTITY_AMBIGUOUS");
      }
      JsonObject item = new JsonObject();
      item.addProperty("sku", sku);
      item.addProperty("quantity", quantity(source, "quantity").toPlainString());
      items.add(item);
    }
    result.add("items", items);
    return result;
  }

  static JsonObject yandex(JsonObject order, JsonObject stats) {
    requireReal(order);
    requireReal(stats);
    String orderId = CatalogSyncService.text(order, "orderId", true);
    if (!orderId.equals(CatalogSyncService.text(stats, "id", true))) {
      throw invalid("ORDER_IDENTITY_MISMATCH");
    }
    Instant created = instant(order, "creationDate");
    Instant updated = instant(order, "updateDate");
    Instant statsUpdated = statsUpdated(stats);
    if (statsUpdated.isBefore(updated)) {
      throw invalid("ORIGINAL_COMPOSITION_STALE");
    }
    Map<String, Quantity> items = new LinkedHashMap<>();
    for (var item : array(stats, "items", true)) {
      JsonObject product = product(item);
      if (product.has("shopServiceType") && !product.get("shopServiceType").isJsonNull()) {
        continue;
      }
      String sku = CatalogSyncService.text(product, "shopSku", true);
      BigDecimal count = quantity(product, "count");
      BigDecimal original = CatalogSyncService.decimal(product, "initialCount");
      if (items.putIfAbsent(sku, new Quantity(count, original == null ? count : original))
          != null) {
        throw invalid("ORIGINAL_LINE_IDENTITY_AMBIGUOUS");
      }
    }
    Map<String, BigDecimal> changed = new LinkedHashMap<>();
    for (var item : array(stats, "initialItems", false)) {
      JsonObject product = product(item);
      if (product.has("shopServiceType") && !product.get("shopServiceType").isJsonNull()) {
        continue;
      }
      String sku = CatalogSyncService.text(product, "shopSku", true);
      BigDecimal original = quantity(product, "initialCount");
      if (changed.putIfAbsent(sku, original) != null) {
        throw invalid("ORIGINAL_LINE_IDENTITY_AMBIGUOUS");
      }
      Quantity current = items.get(sku);
      items.put(sku, new Quantity(current == null ? BigDecimal.ZERO : current.current(), original));
    }
    JsonArray canonical = new JsonArray();
    if (items.isEmpty()) {
      throw invalid("ORIGINAL_COMPOSITION_MISSING");
    }
    for (var entry : items.entrySet()) {
      if (entry.getValue().original().signum() <= 0
          || entry.getValue().current().compareTo(entry.getValue().original()) > 0) {
        throw invalid("ORIGINAL_QUANTITY_CONFLICT");
      }
      JsonObject item = new JsonObject();
      item.addProperty("sku", entry.getKey());
      item.addProperty("current", entry.getValue().current().toPlainString());
      item.addProperty("original", entry.getValue().original().toPlainString());
      canonical.add(item);
    }
    JsonObject result = new JsonObject();
    result.addProperty("orderId", orderId);
    result.addProperty("campaignId", CatalogSyncService.text(order, "campaignId", true));
    result.addProperty("createdAt", created.toString());
    result.addProperty("updatedAt", statsUpdated.toString());
    result.add("items", canonical);
    return result;
  }

  private static void requireReal(JsonObject order) {
    if (!order.has("fake")
        || !order.get("fake").isJsonPrimitive()
        || !order.get("fake").getAsJsonPrimitive().isBoolean()
        || order.get("fake").getAsBoolean()) {
      throw invalid("REAL_ORDER_NOT_PROVEN");
    }
  }

  private static BigDecimal quantity(JsonObject item, String field) {
    BigDecimal quantity = CatalogSyncService.decimal(item, field);
    if (quantity == null || quantity.stripTrailingZeros().scale() > 0 || quantity.signum() < 0) {
      throw invalid("ORDER_QUANTITY_UNKNOWN");
    }
    return quantity;
  }

  private static JsonArray array(JsonObject value, String field, boolean required) {
    if (!value.has(field) || value.get(field).isJsonNull()) {
      if (required) {
        throw invalid("ORIGINAL_COMPOSITION_MISSING");
      }
      return new JsonArray();
    }
    if (!value.get(field).isJsonArray()) {
      throw invalid("ORIGINAL_COMPOSITION_INVALID");
    }
    JsonArray values = value.getAsJsonArray(field);
    if (values.size() > 1000) {
      throw invalid("ORDER_LINE_LIMIT");
    }
    return values;
  }

  private static JsonObject product(JsonElement item) {
    if (!item.isJsonObject()) {
      throw invalid("ORIGINAL_COMPOSITION_INVALID");
    }
    return item.getAsJsonObject();
  }

  private static Instant instant(JsonObject object, String field) {
    try {
      return Instant.parse(CatalogSyncService.text(object, field, true));
    } catch (DateTimeParseException exception) {
      throw invalid("ORDER_TIME_INVALID");
    }
  }

  private static Instant statsUpdated(JsonObject stats) {
    String value = CatalogSyncService.text(stats, "statusUpdateDate", true);
    try {
      // This method documents local Moscow time, unlike the business-orders timestamps.
      if (value.endsWith("Z") || value.matches(".*[+-][0-9]{2}:[0-9]{2}$")) {
        return Instant.parse(value);
      }
      return LocalDateTime.parse(value).toInstant(ZoneOffset.ofHours(3));
    } catch (DateTimeParseException exception) {
      throw invalid("ORDER_TIME_INVALID");
    }
  }

  private static BusinessException invalid(String reason) {
    return new BusinessException(reason, 422, "Исходный состав заказа не подтверждён");
  }

  private record Quantity(BigDecimal current, BigDecimal original) {}
}
