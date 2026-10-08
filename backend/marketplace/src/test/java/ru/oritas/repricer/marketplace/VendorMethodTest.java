package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class VendorMethodTest {
  @Test
  void opaqueReportIdOccupiesOneEncodedPathSegment() {
    var endpoint = VendorMethod.YANDEX_REPORT_INFO.endpoint("1", "../a?key=1#x+ %", null);
    assertEquals("api.partner.market.yandex.ru", endpoint.getHost());
    assertEquals("/v2/reports/info/%2E%2E%2Fa%3Fkey%3D1%23x%2B%20%25", endpoint.getRawPath());
    assertNull(endpoint.getRawQuery());
    assertNull(endpoint.getRawFragment());
  }

  @Test
  void reportIdentityUsesTheDocumentedTwoHundredFiftyFiveCharacterLimit() {
    String longest = "r".repeat(255);
    assertEquals(
        "/v2/reports/info/" + longest,
        VendorMethod.YANDEX_REPORT_INFO.endpoint("1", longest, null).getRawPath());
    assertThrows(
        IllegalArgumentException.class,
        () -> VendorMethod.YANDEX_REPORT_INFO.endpoint("1", longest + "r", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> VendorMethod.YANDEX_REPORT_INFO.endpoint("1", "x\nHeader: value", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> VendorMethod.YANDEX_REPORT_INFO.endpoint("1", "", null));
  }
}
