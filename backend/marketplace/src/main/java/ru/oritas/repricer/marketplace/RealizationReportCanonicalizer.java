package ru.oritas.repricer.marketplace;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ru.oritas.repricer.marketplace.FinancialSourceService.FinancialFact;
import ru.oritas.repricer.marketplace.FinancialSourceService.InventoryDisposition;
import ru.oritas.repricer.marketplace.FinancialSourceService.SettlementEvidence;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;

/**
 * Official goods-realization sheets; cash refunds and inventory restoration require other proof.
 */
final class RealizationReportCanonicalizer {
  private static final Set<String> SHEETS =
      Set.of("transferred_to_delivery", "delivered", "unredeemed", "returned", "lost_items");
  private static final DateTimeFormatter RUSSIAN_DATE =
      DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);

  private RealizationReportCanonicalizer() {}

  static boolean supports(String sheet) {
    return SHEETS.contains(sheet);
  }

  static String sku(JsonObject row) {
    return CatalogSyncService.text(row, "yourSku", true);
  }

  static List<FinancialFact> canonicalize(
      UUID report,
      long firstFact,
      String sheet,
      JsonObject row,
      LocalDate from,
      LocalDate until,
      UUID offer,
      UUID originalLine,
      UUID raw) {
    if (!supports(sheet)) {
      throw invalid("REALIZATION_SHEET_UNSUPPORTED");
    }
    String order = CatalogSyncService.text(row, "orderId", true);
    String lineKey = order + ":sku:" + sku(row);
    List<FinancialFact> facts = new ArrayList<>(2);
    if (sheet.equals("delivered")) {
      LocalDate date = date(row, "deliveryDate", true);
      if (date.isBefore(from) || date.isAfter(until)) {
        throw invalid("REALIZATION_DATE_OUTSIDE_PERIOD");
      }
      BigDecimal units = positiveQuantity(row, "deliveredCount");
      BigDecimal amount = amount(row, "deliveredPriceSumWithVatAndDiscounts", true);
      BigDecimal gross = amount(row, "deliveredPriceSumWithVatAndNoDiscounts", true);
      BigDecimal discount = amount(row, "deliveredDiscountSum", true);
      if (gross.subtract(discount).compareTo(amount) != 0) {
        throw invalid("REALIZATION_PRICE_COMPONENT_CONFLICT");
      }
      facts.add(
          fact(
              report,
              firstFact,
              "REVENUE",
              date,
              amount,
              units,
              lineKey,
              offer,
              raw,
              new SettlementEvidence(
                  originalLine,
                  units,
                  amount,
                  amount,
                  null,
                  null,
                  null,
                  null,
                  InventoryDisposition.CONSUMED,
                  null,
                  new FinancialSourceService.CostBasisEvidence(
                      date, FinancialSourceService.CostBasisKind.DELIVERY_FALLBACK, raw),
                  null)));
    } else if (sheet.equals("returned")) {
      LocalDate date = date(row, "returnWarehouseOrScAcceptDate", true);
      BigDecimal units = positiveQuantity(row, "returnedCount");
      facts.add(
          fact(
              report,
              firstFact,
              "RETURN_PHYSICAL",
              date,
              null,
              units,
              lineKey,
              offer,
              raw,
              new SettlementEvidence(
                  originalLine,
                  units,
                  null,
                  null,
                  null,
                  true,
                  null,
                  null,
                  InventoryDisposition.UNKNOWN,
                  null,
                  null,
                  null)));
    } else if (sheet.equals("lost_items")) {
      compensation(
          facts, report, firstFact, row, "compensation", false, lineKey, offer, originalLine, raw);
      compensation(
          facts,
          report,
          firstFact + facts.size(),
          row,
          "decompensation",
          true,
          lineKey,
          offer,
          originalLine,
          raw);
    }
    return List.copyOf(facts);
  }

  private static void compensation(
      List<FinancialFact> facts,
      UUID report,
      long ordinal,
      JsonObject row,
      String prefix,
      boolean reversal,
      String lineKey,
      UUID offer,
      UUID originalLine,
      UUID raw) {
    BigDecimal value = amount(row, prefix + "Amount", false);
    LocalDate date = date(row, prefix + "Date", false);
    if (value == null || value.signum() == 0) {
      if (date != null) {
        throw invalid("COMPENSATION_AMOUNT_UNKNOWN");
      }
      return;
    }
    if (date == null) {
      throw invalid("COMPENSATION_DATE_UNKNOWN");
    }
    BigDecimal signed = reversal ? value.negate() : value;
    facts.add(
        fact(
            report,
            ordinal,
            reversal ? "COMPENSATION_REVERSAL" : "COMPENSATION",
            date,
            signed,
            null,
            lineKey,
            offer,
            raw,
            new SettlementEvidence(
                originalLine,
                null,
                signed,
                null,
                null,
                null,
                null,
                null,
                InventoryDisposition.UNKNOWN,
                null,
                null,
                null)));
  }

  private static FinancialFact fact(
      UUID report,
      long ordinal,
      String kind,
      LocalDate date,
      BigDecimal amount,
      BigDecimal units,
      String lineKey,
      UUID offer,
      UUID raw,
      SettlementEvidence evidence) {
    String identity = report + ":" + ordinal;
    String digest =
        IdempotencyService.sha256(
            (identity + "\n" + kind + "\n" + date + "\n" + amount + "\n" + units + "\n" + lineKey
                    + "\n" + offer + "\n" + evidence)
                .getBytes(StandardCharsets.UTF_8));
    return new FinancialFact(
        ordinal,
        UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
        1,
        digest,
        offer,
        date,
        kind,
        amount,
        null,
        units,
        lineKey,
        raw,
        evidence,
        null);
  }

  private static BigDecimal positiveQuantity(JsonObject row, String field) {
    BigDecimal value = amount(row, field, true);
    if (value.signum() <= 0 || value.stripTrailingZeros().scale() > 0) {
      throw invalid("REALIZATION_QUANTITY_INVALID");
    }
    return value;
  }

  private static BigDecimal amount(JsonObject row, String field, boolean required) {
    String source = optionalText(row, field);
    BigDecimal value = null;
    if (source != null) {
      try {
        value = new BigDecimal(source);
      } catch (NumberFormatException exception) {
        throw invalid("REALIZATION_AMOUNT_INVALID");
      }
      if (value.signum() < 0 || value.precision() > 38 || value.scale() > 12) {
        throw invalid("REALIZATION_AMOUNT_INVALID");
      }
    }
    if (value == null && required) {
      throw invalid("REALIZATION_AMOUNT_UNKNOWN");
    }
    return value;
  }

  private static LocalDate date(JsonObject row, String field, boolean required) {
    String value = optionalText(row, field);
    if (value == null) {
      if (required) {
        throw invalid("REALIZATION_DATE_UNKNOWN");
      }
      return null;
    }
    try {
      return value.matches("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")
          ? LocalDate.parse(value, RUSSIAN_DATE)
          : LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw invalid("REALIZATION_DATE_INVALID");
    }
  }

  private static String optionalText(JsonObject row, String field) {
    var value = row.get(field);
    if (value == null || value.isJsonNull()) {
      return null;
    }
    if (!value.isJsonPrimitive() || value.getAsJsonPrimitive().isBoolean()) {
      throw invalid("REALIZATION_FIELD_INVALID");
    }
    String text = value.getAsString();
    if (text.length() > 128) {
      throw invalid("REALIZATION_FIELD_INVALID");
    }
    return text.isBlank() ? null : text;
  }

  private static BusinessException invalid(String code) {
    return new BusinessException(code, 422, "Строка реализации не подтверждает финансовый факт");
  }
}
