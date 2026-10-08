package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.platform.BusinessException;

class RealizationReportCanonicalizerTest {
  private static final UUID REPORT = UUID.randomUUID();
  private static final UUID RAW = UUID.randomUUID();
  private static final UUID OFFER = UUID.randomUUID();
  private static final UUID LINE = UUID.randomUUID();

  @Test
  void deliveredRecognizesTheReportedPriceAfterDiscountsAndExactQuantity() {
    var fact =
        convert(
                "delivered",
                """
                {"orderId":1,"yourSku":"sku","deliveryDate":"2026-09-20","deliveredCount":2,
                  "deliveredPriceSumWithVatAndDiscounts":"180.50",
                  "deliveredPriceSumWithVatAndNoDiscounts":"200.50","deliveredDiscountSum":"20"}
                """)
            .getFirst();
    assertEquals("REVENUE", fact.kind());
    assertEquals(new BigDecimal("180.50"), fact.amount());
    assertEquals(new BigDecimal("2"), fact.settlement().units());
    assertEquals(LINE, fact.settlement().originalOrderLineId());
    assertEquals(new BigDecimal("180.50"), fact.settlement().taxBase());
    assertEquals(
        FinancialSourceService.InventoryDisposition.CONSUMED,
        fact.settlement().inventoryDisposition());
    assertEquals(
        new FinancialSourceService.CostBasisEvidence(
            LocalDate.of(2026, 9, 20), FinancialSourceService.CostBasisKind.DELIVERY_FALLBACK, RAW),
        fact.settlement().costBasis());
  }

  @Test
  void physicalReturnNeverBecomesCashRefundOrRestoredStock() {
    var fact =
        convert(
                "returned",
                """
                {"orderId":1,"yourSku":"sku","returnWarehouseOrScAcceptDate":"22.09.2026",
                  "returnedCount":1,"returnPriceSumWithVatAndDiscounts":90.25}
                """)
            .getFirst();
    assertEquals("RETURN_PHYSICAL", fact.kind());
    assertNull(fact.amount());
    assertNull(fact.settlement().refundConfirmed());
    assertNull(fact.settlement().stockRestored());
    assertNull(fact.settlement().resalable());
    assertEquals(Boolean.TRUE, fact.settlement().physicalReceiptConfirmed());
  }

  @Test
  void compensationAndReversalAreIndependentDatedFactsWithoutGuessedInventoryOrTax() {
    var facts =
        convert(
            "lost_items",
            """
            {"orderId":1,"yourSku":"sku","compensationDate":"2026-09-20",
              "compensationAmount":"100","decompensationDate":"2026-10-02",
              "decompensationAmount":"60","transferredToDeliveryCount":10}
            """);
    assertEquals(2, facts.size());
    assertEquals("COMPENSATION", facts.get(0).kind());
    assertEquals("COMPENSATION_REVERSAL", facts.get(1).kind());
    assertEquals(new BigDecimal("-60"), facts.get(1).amount());
    assertEquals(LocalDate.of(2026, 10, 2), facts.get(1).accountingDate());
    assertNotEquals(facts.get(0).id(), facts.get(1).id());
    assertEquals(2, facts.get(1).rowNumber());
    assertNull(facts.get(0).settlement().taxBase());
    assertNull(facts.get(0).settlement().units());
    assertEquals(
        FinancialSourceService.InventoryDisposition.UNKNOWN,
        facts.get(0).settlement().inventoryDisposition());
  }

  @Test
  void incompleteMoneyAndConflictingDiscountsCannotBecomeACompleteSale() {
    String source =
        """
        {"orderId":1,"yourSku":"sku","deliveryDate":"2026-09-20","deliveredCount":2,
          "deliveredPriceSumWithVatAndDiscounts":"180",
          "deliveredPriceSumWithVatAndNoDiscounts":"200","deliveredDiscountSum":"30"}
        """;
    assertEquals(
        "REALIZATION_PRICE_COMPONENT_CONFLICT",
        assertThrows(BusinessException.class, () -> convert("delivered", source)).code());
    assertThrows(
        BusinessException.class,
        () ->
            convert(
                "lost_items",
                """
                {"orderId":1,"yourSku":"sku","compensationAmount":"100"}
                """));
  }

  @Test
  void noCompensationAndUnredeemedProductsAreNotFabricatedZeroRevenue() {
    assertEquals(
        List.of(),
        convert(
            "lost_items",
            """
            {"orderId":1,"yourSku":"sku","compensationDate":"","compensationAmount":"",
              "decompensationDate":null,"decompensationAmount":null}
            """));
    assertEquals(
        List.of(),
        convert(
            "unredeemed",
            """
            {"orderId":1,"yourSku":"sku","unredeemedCount":4}
            """));
  }

  private static List<FinancialSourceService.FinancialFact> convert(String sheet, String source) {
    JsonObject row = JsonParser.parseString(source).getAsJsonObject();
    return RealizationReportCanonicalizer.canonicalize(
        REPORT,
        1,
        sheet,
        row,
        LocalDate.of(2026, 9, 1),
        LocalDate.of(2026, 9, 30),
        OFFER,
        LINE,
        RAW);
  }
}
