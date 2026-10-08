package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.platform.BusinessException;

class ServiceReportCanonicalizerTest {
  private static final UUID REPORT = UUID.fromString("bc10b854-2782-4f99-89c5-5290a4816dcd");
  private static final UUID RAW = UUID.fromString("20c732cf-e5a4-4c77-9e36-dead35df4413");

  @Test
  void serviceUsesFinalCostAndNeverBuyerPriceOrTariffEstimate() {
    JsonObject row = base();
    row.addProperty("serviceDateTime", "2026-09-20T10:00:00+03:00");
    row.addProperty("totalAmount", "-17.45");
    row.addProperty("price", "2000");
    row.addProperty("tariff", "20");
    row.addProperty("amountWithoutBonuses", "400");
    var fact = canonicalize("placement", row);
    assertEquals("SERVICE", fact.kind());
    assertEquals(new BigDecimal("-17.45"), fact.amount());
    assertNull(fact.offerId());
    assertNull(fact.orderLineKey());
    assertNull(fact.quantity());
  }

  @Test
  void currentStorageSheetUsesPaidStorageAndPreservesItsActualScope() {
    JsonObject row = base();
    row.addProperty("serviceDate", "30.09.2026");
    row.addProperty("paidStorage", "120.25");
    row.addProperty("countProductUnit", "20");
    var fact = canonicalize("paid_storage_after_01-09-26", row);
    assertEquals(new BigDecimal("120.25"), fact.amount());
    assertNull(fact.quantity());
    assertEquals(LocalDate.of(2026, 9, 30), fact.accountingDate());
  }

  @Test
  void offsetTimestampIsAssignedToAccountDayAndOutsideMonthIsRejected() {
    JsonObject row = base();
    row.addProperty("serviceDateTime", "2026-09-30T23:00:00Z");
    row.addProperty("servicePrice", "1");
    assertEquals(
        "SERVICE_OUTSIDE_REQUESTED_PERIOD",
        assertThrows(BusinessException.class, () -> canonicalize("delivery", row)).code());
  }

  @Test
  void unknownSheetAndMissingFinalCostCannotPublishCompleteFacts() {
    JsonObject row = base();
    row.addProperty("serviceDateTime", "2026-09-12T12:00:00");
    row.addProperty("tariff", "20");
    assertThrows(BusinessException.class, () -> canonicalize("delivery", row));
    assertThrows(BusinessException.class, () -> canonicalize("unexpected_summary", row));
    assertTrue(ServiceReportCanonicalizer.supports("cpm-boost"));
  }

  private static JsonObject base() {
    JsonObject row = new JsonObject();
    row.addProperty("businessId", "100");
    return row;
  }

  private static FinancialSourceService.FinancialFact canonicalize(String sheet, JsonObject row) {
    return ServiceReportCanonicalizer.canonicalize(
        REPORT,
        1,
        sheet,
        row,
        "100",
        ZoneId.of("Europe/Moscow"),
        LocalDate.of(2026, 9, 1),
        LocalDate.of(2026, 9, 30),
        null,
        RAW);
  }
}
