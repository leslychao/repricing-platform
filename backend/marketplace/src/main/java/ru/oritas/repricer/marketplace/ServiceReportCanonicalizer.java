package ru.oritas.repricer.marketplace;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Set;
import java.util.UUID;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;

/** Exact official united-marketplace-services columns, without allocating account-wide services. */
final class ServiceReportCanonicalizer {
  private static final Set<String> TOTAL_AMOUNT =
      Set.of("placement", "sale_commission", "item_booking");
  private static final Set<String> DATE_ONLY =
      Set.of(
          "boost",
          "shelf",
          "cpm-boost",
          "product-banners",
          "banners",
          "pushes",
          "popups",
          "paid_storage_before_31-05-22",
          "paid_storage_after_01-06-22",
          "paid_storage_after_01-09-26",
          "delivery_via_transit_warehouse",
          "order_processing",
          "order_processing_on_warehouse",
          "storage_of_returns",
          "extended_service_access",
          "business_subscription",
          "personal_manager",
          "mailing",
          "web",
          "tv",
          "yandex-market",
          "pads");
  private static final Set<String> DATE_TIME =
      Set.of(
          "placement",
          "sale_commission",
          "item_booking",
          "warehouse_processing",
          "goods_acceptance",
          "loyalty_and_reviews",
          "installment_plan",
          "delivery",
          "crossregional_delivery",
          "express_delivery",
          "delivery_from_abroad",
          "agency_commission_3pl",
          "payment_accepting",
          "payment_transfer",
          "money_withdraw",
          "reception_of_surplus",
          "product_marking",
          "export_from_warehouse",
          "intake_logistics",
          "utilization");
  private static final DateTimeFormatter RUSSIAN_DATE =
      DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
  private static final DateTimeFormatter RUSSIAN_TIME =
      DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

  private ServiceReportCanonicalizer() {}

  static boolean supports(String sheet) {
    return DATE_ONLY.contains(sheet) || DATE_TIME.contains(sheet) || sheet.equals("expropriation");
  }

  static String sku(JsonObject row, String sheet) {
    return CatalogSyncService.text(row, sheet.equals("expropriation") ? "sku" : "shopSku", false);
  }

  static FinancialSourceService.FinancialFact canonicalize(
      UUID report,
      long ordinal,
      String sheet,
      JsonObject row,
      String businessId,
      ZoneId zone,
      LocalDate from,
      LocalDate until,
      UUID offer,
      UUID raw) {
    if (!supports(sheet) || !businessId.equals(CatalogSyncService.text(row, "businessId", true))) {
      throw invalid("SERVICE_REPORT_SCOPE_UNCONFIRMED");
    }
    String amountField;
    if (TOTAL_AMOUNT.contains(sheet)) {
      amountField = "totalAmount";
    } else if (sheet.equals("expropriation")) {
      amountField = "fullPrice";
    } else if (sheet.equals("paid_storage_after_01-06-22")
        || sheet.equals("paid_storage_after_01-09-26")) {
      amountField = "paidStorage";
    } else {
      amountField = "servicePrice";
    }
    BigDecimal amount = signedAmount(row, amountField);
    String dateField =
        sheet.equals("expropriation")
            ? "dateTime"
            : DATE_ONLY.contains(sheet) ? "serviceDate" : "serviceDateTime";
    LocalDate date = date(CatalogSyncService.text(row, dateField, true), zone);
    if (date.isBefore(from) || date.isAfter(until)) {
      throw invalid("SERVICE_OUTSIDE_REQUESTED_PERIOD");
    }
    BigDecimal quantity = CatalogSyncService.decimal(row, "count");
    if (quantity != null && quantity.signum() < 0) {
      throw invalid("SERVICE_QUANTITY_INVALID");
    }
    String sku = sku(row, sheet);
    String order =
        CatalogSyncService.text(
            row, sheet.equals("expropriation") ? "orderNumber" : "orderId", false);
    String partner = CatalogSyncService.text(row, "partnerId", false);
    String line =
        order == null || sku == null || partner == null
            ? null
            : partner + ":" + order + ":sku:" + sku;
    String identity = report + ":" + ordinal;
    String digest =
        IdempotencyService.sha256(
            (identity
                    + "\n"
                    + sheet
                    + "\n"
                    + date
                    + "\n"
                    + amount.toPlainString()
                    + "\n"
                    + quantity
                    + "\n"
                    + line
                    + "\n"
                    + offer)
                .getBytes(StandardCharsets.UTF_8));
    return new FinancialSourceService.FinancialFact(
        ordinal,
        UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
        1,
        digest,
        offer,
        date,
        "SERVICE",
        amount,
        sheet,
        quantity,
        line,
        raw,
        null,
        null);
  }

  private static LocalDate date(String value, ZoneId zone) {
    try {
      if (value.matches("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")) {
        return LocalDate.parse(value, RUSSIAN_DATE);
      }
      if (value.matches("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2}")) {
        return LocalDateTime.parse(value, RUSSIAN_TIME).toLocalDate();
      }
      if (value.length() == 10) {
        return LocalDate.parse(value);
      }
      if (value.endsWith("Z") || value.matches(".*[+-][0-9]{2}:[0-9]{2}$")) {
        return OffsetDateTime.parse(value).atZoneSameInstant(zone).toLocalDate();
      }
      return LocalDateTime.parse(value.replace(' ', 'T')).toLocalDate();
    } catch (DateTimeParseException exception) {
      throw invalid("SERVICE_DATE_INVALID");
    }
  }

  private static BigDecimal signedAmount(JsonObject row, String field) {
    String value = CatalogSyncService.text(row, field, true);
    if (value.length() > 64) {
      throw invalid("SERVICE_AMOUNT_INVALID");
    }
    try {
      BigDecimal amount = new BigDecimal(value);
      if (amount.scale() > 12 || amount.precision() - amount.scale() > 26) {
        throw invalid("SERVICE_AMOUNT_INVALID");
      }
      return amount;
    } catch (NumberFormatException exception) {
      throw invalid("SERVICE_AMOUNT_INVALID");
    }
  }

  private static BusinessException invalid(String code) {
    return new BusinessException(
        code, 422, "Строка услуг не подтверждает однозначный финансовый факт");
  }
}
