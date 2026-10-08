package ru.oritas.repricer.marketplace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.Scope;

/** Converts a complete report using only separately evidenced monetary and physical facts. */
@Component
public final class ReportPublication {
  private final JdbcClient jdbc;

  public ReportPublication(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  String publish(
      Scope scope, UUID reportId, String kind, Instant sourceRevision, Set<String> sheets) {
    Account account =
        jdbc.sql("SELECT external_id,timezone FROM marketplace_account WHERE id=:id FOR UPDATE")
            .param("id", scope.accountId())
            .query((row, index) -> new Account(row.getString(1), ZoneId.of(row.getString(2))))
            .single();
    long cursor = 0;
    String sheet =
        switch (kind) {
          case "RETURNS" -> "returns_for_selected_range";
          case "PAYMENTS" -> "transaction_date";
          case "SERVICES" -> null;
          case "REALIZATION" -> null;
          default -> throw invalid("REPORT_KIND_UNKNOWN");
        };
    if (sheet == null) {
      if (kind.equals("REALIZATION") && !sheets.contains("delivered.json")) {
        return "REALIZATION_DELIVERED_SHEET_MISSING";
      }
      // Each service row was normalized in bounded staging; the complete archive is now verified.
      LocalDate until =
          jdbc.sql("SELECT date_until FROM marketplace_report WHERE id=:id")
              .param("id", reportId)
              .query(LocalDate.class)
              .single();
      return sourceRevision.atZone(account.zone()).toLocalDate().isAfter(until)
          ? null
          : "FINANCIAL_PERIOD_NOT_CLOSED";
    }
    if (!sheets.contains(sheet + ".json")) {
      return "REPORT_SHEET_MISSING";
    }
    JsonObject paymentContract = paymentContract();
    boolean uncertain = false;
    while (true) {
      List<Row> rows =
          jdbc.sql(
                  """
                  SELECT rr.row_number,rr.data::text,r.raw_file_id FROM marketplace_report_row rr
                  JOIN marketplace_report r ON r.id=rr.report_id
                  WHERE rr.report_id=:report AND rr.sheet=:sheet AND rr.row_number>:cursor
                  ORDER BY rr.row_number LIMIT 100
                  """)
              .param("report", reportId)
              .param("sheet", sheet)
              .param("cursor", cursor)
              .query(
                  (row, index) ->
                      new Row(row.getLong(1), row.getString(2), row.getObject(3, UUID.class)))
              .list();
      if (rows.isEmpty()) {
        break;
      }
      for (Row row : rows) {
        JsonObject data = JsonParser.parseString(row.json()).getAsJsonObject();
        if (!account.externalId().equals(CatalogSyncService.text(data, "businessId", true))) {
          throw invalid("REPORT_ACCOUNT_MISMATCH");
        }
        if (kind.equals("RETURNS")) {
          publishReturn(scope, account.zone(), data, row.raw(), sourceRevision);
        } else {
          uncertain |=
              !publishPayment(
                  scope, account.zone(), data, row.raw(), sourceRevision, paymentContract);
        }
        cursor = row.number();
      }
    }
    return uncertain ? "PAYMENT_CONFIRMATION_INCOMPLETE" : null;
  }

  private void publishReturn(
      Scope scope, ZoneId zone, JsonObject source, UUID raw, Instant revision) {
    String number = CatalogSyncService.text(source, "returnNumber", true);
    String campaign = CatalogSyncService.text(source, "partnerId", true);
    String sku = CatalogSyncService.text(source, "shopSku", true);
    String order = CatalogSyncService.text(source, "orderId", true);
    BigDecimal quantity = requiredPositive(source, "count");
    BigDecimal fit = CatalogSyncService.decimal(source, "countOfFit");
    if (fit != null && fit.compareTo(quantity) > 0) {
      throw invalid("RETURN_QUANTITY_CONFLICT");
    }
    String external = campaign + ":" + number + ":" + order + ":" + sku;
    UUID offer =
        jdbc.sql("SELECT id FROM marketplace_offer WHERE sku=:sku")
            .param("sku", sku)
            .query(UUID.class)
            .optional()
            .orElseThrow(() -> invalid("RETURN_OFFER_UNKNOWN"));
    Instant occurred = date(source, "dateOfReturnCreation").atStartOfDay(zone).toInstant();
    String status = CatalogSyncService.text(source, "status", true);
    Boolean received =
        Set.of("PICKED", "FULFILMENT_RECEIVED").contains(status) ? Boolean.TRUE : null;
    Boolean resalable =
        fit == null
            ? null
            : fit.compareTo(quantity) == 0
                ? Boolean.TRUE
                : fit.signum() == 0 ? Boolean.FALSE : null;
    String hash =
        IdempotencyService.sha256(
            (external
                    + "\n"
                    + quantity.toPlainString()
                    + "\n"
                    + occurred
                    + "\n"
                    + status
                    + "\n"
                    + fit)
                .getBytes(StandardCharsets.UTF_8));
    jdbc.sql(
            """
            INSERT INTO marketplace_return(id,organization_id,account_id,external_id,offer_id,line_id,
              returned_quantity,returned_at,physical_receipt_confirmed,resalable,source_revision,content_hash,raw_file_id)
            VALUES (:id,:org,:account,:external,:offer,:line,:quantity,:occurred,:received,:resalable,:revision,:hash,:raw)
            ON CONFLICT(organization_id,account_id,external_id) DO UPDATE SET
              returned_quantity=EXCLUDED.returned_quantity,returned_at=EXCLUDED.returned_at,
              physical_receipt_confirmed=EXCLUDED.physical_receipt_confirmed,resalable=EXCLUDED.resalable,
              source_revision=EXCLUDED.source_revision,content_hash=EXCLUDED.content_hash,
              raw_file_id=EXCLUDED.raw_file_id,revision=marketplace_return.revision+1
            WHERE marketplace_return.source_revision::timestamptz<EXCLUDED.source_revision::timestamptz
              AND marketplace_return.content_hash<>EXCLUDED.content_hash
            """)
        .param(
            "id",
            UUID.nameUUIDFromBytes(
                (scope.accountId() + ":return:" + external).getBytes(StandardCharsets.UTF_8)))
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("external", external)
        .param("offer", offer)
        .param("line", "sku:" + sku)
        .param("quantity", quantity)
        .param("occurred", Timestamp.from(occurred))
        .param("received", received)
        .param("resalable", resalable)
        .param("revision", revision.toString())
        .param("hash", hash)
        .param("raw", raw)
        .update();
  }

  private JsonObject paymentContract() {
    return jdbc.sql(
            """
            SELECT constraints::text FROM marketplace_profile WHERE method='YANDEX_REPORT_PAYMENTS'
              AND status='CONFIRMED' AND expires_at>clock_timestamp()
              AND revision=(SELECT max(revision) FROM marketplace_profile WHERE method='YANDEX_REPORT_PAYMENTS')
            """)
        .query(String.class)
        .optional()
        .map(value -> JsonParser.parseString(value).getAsJsonObject())
        .orElseGet(JsonObject::new);
  }

  private boolean publishPayment(
      Scope scope,
      ZoneId zone,
      JsonObject source,
      UUID raw,
      Instant revision,
      JsonObject contract) {
    String bankId = CatalogSyncService.text(source, "bankOrderId", false);
    if (bankId == null || bankId.isBlank() || bankId.equals("0")) {
      return false;
    }
    // Report payment statuses are free text, not the order PAYMENT enum. Only a live-evidenced
    // report profile can establish which values confirm a transfer and its currency.
    String status = CatalogSyncService.text(source, "paymentStatus", true);
    if (!contract.has("confirmedTransferStatuses")
        || !contract.get("confirmedTransferStatuses").isJsonArray()
        || contract.getAsJsonArray("confirmedTransferStatuses").size() > 20
        || !contract.has("currency")) {
      return false;
    }
    boolean confirmed = false;
    for (var candidate : contract.getAsJsonArray("confirmedTransferStatuses")) {
      confirmed |= candidate.isJsonPrimitive() && candidate.getAsString().equals(status);
    }
    if (!confirmed) {
      return false;
    }
    String currency = CatalogSyncService.text(contract, "currency", true);
    if (!currency.matches("[A-Z]{3}")) {
      throw invalid("PAYMENT_CURRENCY_UNCONFIRMED");
    }
    String inn = CatalogSyncService.text(source, "inn", true);
    LocalDate day = date(source, "bankOrderDate");
    String external = inn + ":" + day + ":" + bankId;
    BigDecimal amount = requiredPositive(source, "bankSum");
    String hash =
        IdempotencyService.sha256(
            (external + "\n" + amount.toPlainString() + "\n" + currency)
                .getBytes(StandardCharsets.UTF_8));
    boolean conflict =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_payment WHERE external_id=:external AND content_hash<>:hash)
                """)
            .param("external", external)
            .param("hash", hash)
            .query(Boolean.class)
            .single();
    if (conflict) {
      throw invalid("PAYMENT_ORDER_CONFLICT");
    }
    jdbc.sql(
            """
            INSERT INTO marketplace_payment(id,organization_id,account_id,external_id,occurred_at,
              amount,currency,payment_scope,source_revision,content_hash,raw_file_id)
            VALUES (:id,:org,:account,:external,:occurred,:amount,:currency,'BANK_ORDER',:revision,:hash,:raw)
            ON CONFLICT(organization_id,account_id,external_id) DO NOTHING
            """)
        .param(
            "id",
            UUID.nameUUIDFromBytes(
                (scope.accountId() + ":payment:" + external).getBytes(StandardCharsets.UTF_8)))
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("external", external)
        .param("occurred", Timestamp.from(day.atStartOfDay(zone).toInstant()))
        .param("amount", amount)
        .param("currency", currency)
        .param("revision", revision.toString())
        .param("hash", hash)
        .param("raw", raw)
        .update();
    return true;
  }

  private static BigDecimal requiredPositive(JsonObject row, String field) {
    BigDecimal value = CatalogSyncService.decimal(row, field);
    if (value == null || value.signum() <= 0) {
      throw invalid("REPORT_QUANTITY_OR_AMOUNT_INVALID");
    }
    return value;
  }

  private static LocalDate date(JsonObject row, String field) {
    String value = CatalogSyncService.text(row, field, true);
    try {
      if (value.matches("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}")) {
        return LocalDate.parse(
            value,
            DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT));
      }
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw invalid("REPORT_DATE_INVALID");
    }
  }

  private static BusinessException invalid(String code) {
    return new BusinessException(code, 422, "Строки отчёта не подтверждают однозначный факт");
  }

  private record Row(long number, String json, UUID raw) {}

  private record Account(String externalId, ZoneId zone) {}
}
