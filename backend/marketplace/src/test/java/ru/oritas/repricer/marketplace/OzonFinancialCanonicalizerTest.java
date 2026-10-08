package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;

class OzonFinancialCanonicalizerTest {
  @Test
  void finalComponentsKeepSignedValuesWithoutRecognizingNetTransferOrBuyerPrice() {
    var value =
        OzonFinancialCanonicalizer.accrual(
            JsonParser.parseString(
                    """
                    {"accrual_id":100,"date":"2026-09-12","unit_number":"posting-1",
                     "total_amount":{"amount":"801","currency":"RUB"},
                     "posting":{"products":[{"sku":123,"commission":{
                       "sale_amount":{"amount":"1000","currency":"RUB"},
                       "commission":{"amount":"-199","currency":"RUB"},
                       "sale_commission":{"amount":"-250","currency":"RUB"},
                       "sale_price":{"amount":"920","currency":"RUB"},
                       "seller_price":{"amount":"1100","currency":"RUB"}}}]}}
                    """)
                .getAsJsonObject(),
            LocalDate.of(2026, 9, 12));
    assertEquals(2, value.components().size());
    assertEquals("SELLER_REVENUE", value.components().getFirst().meaning());
    assertEquals(new BigDecimal("1000"), value.components().getFirst().amount());
    assertEquals("COMMISSION_POLARITY_UNKNOWN", value.components().getLast().meaning());
    assertEquals(new BigDecimal("-199"), value.components().getLast().amount());
  }

  @Test
  void confirmedPhysicalReceiptDoesNotEstablishRefundOrStockRestoration() {
    var value =
        OzonFinancialCanonicalizer.returned(
            JsonParser.parseString(
                    """
                    {"id":20,"company_id":100,"type":"ClientReturn","posting_number":"p-1",
                     "product":{"sku":123,"offer_id":"sku-1","quantity":2,
                       "price":{"currency_code":"RUB","price":"2000"}},
                     "logistic":{"return_date":"2026-09-12T12:00:00Z","final_moment":"2026-09-15T12:00:00Z"},
                     "visual":{"status":{"sys_name":"ReturnedToOzon"}}}
                    """)
                .getAsJsonObject(),
            "100");
    assertEquals(Boolean.TRUE, value.returned().physicalReceiptConfirmed());
    assertNull(value.returned().refundConfirmed());
    assertEquals(Instant.parse("2026-09-12T12:00:00Z"), value.returned().returnedAt());
    assertEquals(new BigDecimal("2"), value.returned().quantity());
    assertEquals(0, value.components().size());
  }

  @Test
  void wrongAccountAndWrongDayCannotEnterCanonicalPublication() {
    assertThrows(
        BusinessException.class,
        () ->
            OzonFinancialCanonicalizer.accrual(
                JsonParser.parseString("{\"accrual_id\":1,\"date\":\"2026-09-13\"}")
                    .getAsJsonObject(),
                LocalDate.of(2026, 9, 12)));
    assertThrows(
        BusinessException.class,
        () ->
            OzonFinancialCanonicalizer.returned(
                JsonParser.parseString("{\"company_id\":200}").getAsJsonObject(), "100"));
  }

  @Test
  void extremeDecimalScaleAndNestedScalarAreRejectedBeforeBigDecimalExpansion() {
    String source =
        """
        {"accrual_id":1,"date":"2026-09-12","non_item_fee":{"type_id":10,
          "accrued":{"amount":"1e-1000000000","currency":"RUB"}}}
        """;
    assertThrows(
        BusinessException.class,
        () ->
            OzonFinancialCanonicalizer.accrual(
                JsonParser.parseString(source).getAsJsonObject(), LocalDate.of(2026, 9, 12)));
    assertThrows(
        BusinessException.class,
        () ->
            OzonFinancialCanonicalizer.accrual(
                JsonParser.parseString("{\"accrual_id\":{},\"date\":\"2026-09-12\"}")
                    .getAsJsonObject(),
                LocalDate.of(2026, 9, 12)));
  }

  @Test
  void boundedAccrualRecordCanExceedTheIndependentJobMessageLimit() {
    String products =
        IntStream.range(1, 201)
            .mapToObj(
                index ->
                    """
                    {"sku":%d,"commission":{
                      "sale_amount":{"amount":"12345678901234567890123456789012345678","currency":"RUB"},
                      "commission":{"amount":"12345678901234567890123456789012345678","currency":"RUB"},
                      "coinvestment":{"amount":"12345678901234567890123456789012345678","currency":"RUB"},
                      "bonus":{"amount":"12345678901234567890123456789012345678","currency":"RUB"}}}
                    """
                        .formatted(index))
            .collect(Collectors.joining(","));
    var observation =
        OzonFinancialCanonicalizer.accrual(
            JsonParser.parseString(
                    "{\"accrual_id\":1,\"date\":\"2026-09-12\",\"posting\":{\"products\":["
                        + products
                        + "]}}")
                .getAsJsonObject(),
            LocalDate.of(2026, 9, 12));
    JsonCodec json = new JsonCodec();
    String encoded = OzonFinancialCanonicalizer.encode(observation, json);
    assertTrue(encoded.length() > JsonCodec.MAX_MESSAGE_BYTES);
    assertTrue(encoded.length() < 262144);
    assertEquals(observation, OzonFinancialCanonicalizer.decode(encoded, json));
    assertThrows(BusinessException.class, () -> json.encode(observation));
  }
}
