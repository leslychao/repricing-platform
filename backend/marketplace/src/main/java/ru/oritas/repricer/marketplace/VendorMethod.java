package ru.oritas.repricer.marketplace;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Explicit semantic methods, including vendor POST endpoints that only read data. */
public enum VendorMethod {
  OZON_CATEGORIES("OZON", "/v1/description-category/tree", "POST", false, 1000),
  OZON_CATALOG("OZON", "/v3/product/list", "POST", false, 1000),
  OZON_INFO("OZON", "/v3/product/info/list", "POST", false, 1000),
  OZON_ATTRIBUTES("OZON", "/v4/product/info/attributes", "POST", false, 1000),
  OZON_PRICES("OZON", "/v5/product/info/prices", "POST", false, 1000),
  OZON_STOCKS("OZON", "/v4/product/info/stocks", "POST", false, 1000),
  OZON_FBS_STOCKS("OZON", "/v2/product/info/stocks-by-warehouse/fbs", "POST", false, 1000),
  OZON_FBO_AVAILABLE("OZON", "/v1/analytics/stocks", "POST", false, 1000),
  OZON_SET_PRICE("OZON", "/v1/product/import/prices", "POST", true, 1000),
  OZON_FBS_ORDERS("OZON", "/v4/posting/fbs/list", "POST", false, 1000),
  OZON_FBO_ORDERS("OZON", "/v3/posting/fbo/list", "POST", false, 1000),
  OZON_FBS_ORDER("OZON", "/v3/posting/fbs/get", "POST", false, 1000),
  OZON_FBO_ORDER("OZON", "/v2/posting/fbo/get", "POST", false, 1000),
  OZON_RETURNS("OZON", "/v1/returns/list", "POST", false, 1000),
  OZON_ACCRUAL_DAY("OZON", "/v1/finance/accrual/by-day", "POST", false, 1000),
  OZON_ACCRUAL_TYPES("OZON", "/v1/finance/accrual/types", "POST", false, 1000),
  OZON_ACTIONS("OZON", "/v1/actions", "GET", false, 1000),
  OZON_ACTION_CANDIDATES("OZON", "/v2/actions/candidates", "POST", false, 1000),
  OZON_ACTION_PRODUCTS("OZON", "/v2/actions/products", "POST", false, 1000),
  YANDEX_AUTH_TOKEN("YANDEX", "/v2/auth/token", "POST", false, 36000),
  YANDEX_CAMPAIGNS("YANDEX", "/v2/campaigns", "GET", false, 3600),
  YANDEX_BUSINESS_SETTINGS("YANDEX", "/v2/businesses/{businessId}/settings", "POST", false, 36000),
  YANDEX_WAREHOUSES("YANDEX", "/v2/businesses/{businessId}/warehouses", "POST", false, 36000),
  YANDEX_PARTNER_WAREHOUSES(
      "YANDEX", "/v3/businesses/{businessId}/warehouses", "POST", false, 36000),
  YANDEX_CATEGORIES("YANDEX", "/v2/categories/tree", "POST", false, 72000),
  YANDEX_CATALOG("YANDEX", "/v2/businesses/{businessId}/offer-mappings", "POST", false, 1000),
  YANDEX_PRICES("YANDEX", "/v2/businesses/{businessId}/offer-prices", "POST", false, 1000),
  YANDEX_STOCKS("YANDEX", "/v2/campaigns/{campaignId}/offers/stocks", "POST", false, 1000),
  YANDEX_PARTNER_STOCKS("YANDEX", "/v3/businesses/{businessId}/offers/stocks", "POST", false, 1000),
  YANDEX_PLACEMENTS("YANDEX", "/v2/campaigns/{campaignId}/offers", "POST", false, 1000),
  YANDEX_HIDDEN_OFFERS("YANDEX", "/v2/campaigns/{campaignId}/hidden-offers", "GET", false, 1000),
  YANDEX_ORDERS("YANDEX", "/v1/businesses/{businessId}/orders", "POST", false, 1000),
  YANDEX_ORDER_STATS("YANDEX", "/v2/campaigns/{campaignId}/stats/orders", "POST", false, 1000),
  YANDEX_REPORT_PAYMENTS("YANDEX", "/v2/reports/united-netting/generate", "POST", false, 120000),
  YANDEX_REPORT_RETURNS("YANDEX", "/v2/reports/united-returns/generate", "POST", false, 120000),
  YANDEX_REPORT_SERVICES(
      "YANDEX", "/v2/reports/united-marketplace-services/generate", "POST", false, 120000),
  YANDEX_REPORT_REALIZATION(
      "YANDEX", "/v2/reports/goods-realization/generate", "POST", false, 120000),
  YANDEX_REPORT_INFO("YANDEX", "/v2/reports/info/{reportId}", "GET", false, 1000),
  YANDEX_PROMOS("YANDEX", "/v2/businesses/{businessId}/promos", "POST", false, 1000),
  YANDEX_PROMO_OFFERS("YANDEX", "/v2/businesses/{businessId}/promos/offers", "POST", false, 1000),
  YANDEX_SET_PRICE(
      "YANDEX", "/v2/businesses/{businessId}/offer-prices/updates", "POST", true, 1000),
  YANDEX_SET_PROMO(
      "YANDEX", "/v2/businesses/{businessId}/promos/offers/update", "POST", true, 1000),
  YANDEX_LEAVE_PROMO(
      "YANDEX", "/v2/businesses/{businessId}/promos/offers/delete", "POST", true, 1000);

  private final String marketplace;
  private final String path;
  private final String httpMethod;
  private final boolean write;
  private final int spacingMillis;

  VendorMethod(
      String marketplace, String path, String httpMethod, boolean write, int spacingMillis) {
    this.marketplace = marketplace;
    this.path = path;
    this.httpMethod = httpMethod;
    this.write = write;
    this.spacingMillis = spacingMillis;
  }

  public String marketplace() {
    return marketplace;
  }

  public boolean write() {
    return write;
  }

  public String httpMethod() {
    return httpMethod;
  }

  public int spacingMillis() {
    return Math.max(2000, (spacingMillis * 10 + 8) / 9);
  }

  public int resourceCost(int elements) {
    return switch (this) {
      case YANDEX_PRICES, YANDEX_STOCKS -> 100;
      case YANDEX_SET_PRICE -> Math.max(1, elements);
      case YANDEX_ORDER_STATS -> 1000;
      default -> 1;
    };
  }

  public String quotaGroup() {
    if (this == YANDEX_AUTH_TOKEN) {
      return "YANDEX:auth";
    }
    if (this == YANDEX_CATEGORIES
        || this == YANDEX_BUSINESS_SETTINGS
        || this == YANDEX_WAREHOUSES
        || this == YANDEX_PARTNER_WAREHOUSES) {
      return "YANDEX:" + name();
    }
    return marketplace + (generatesReport() ? ":report-generation" : ":shared");
  }

  public boolean generatesReport() {
    return this == YANDEX_REPORT_PAYMENTS
        || this == YANDEX_REPORT_RETURNS
        || this == YANDEX_REPORT_SERVICES
        || this == YANDEX_REPORT_REALIZATION;
  }

  public URI endpoint(String account, String campaign, String pageToken) {
    if (!account.matches("[1-9][0-9]{0,19}")
        || (path.contains("{campaignId}")
            && (campaign == null || !campaign.matches("[1-9][0-9]{0,19}")))) {
      throw new IllegalArgumentException("Invalid supplier identity");
    }
    String resolved =
        path.replace("{businessId}", account)
            .replace("{campaignId}", campaign == null ? "" : campaign);
    if (this == YANDEX_REPORT_INFO) {
      if (!validReportId(campaign)) {
        throw new IllegalArgumentException("Invalid supplier report identity");
      }
      String reportSegment =
          URLEncoder.encode(campaign, StandardCharsets.UTF_8)
              .replace("+", "%20")
              .replace(".", "%2E")
              .replace("*", "%2A");
      resolved = resolved.replace("{reportId}", reportSegment);
    } else if (generatesReport()) {
      resolved += "?format=JSON";
    } else if (marketplace.equals("YANDEX")
        && !write
        && this != YANDEX_CATEGORIES
        && this != YANDEX_PROMOS
        && this != YANDEX_BUSINESS_SETTINGS
        && this != YANDEX_AUTH_TOKEN) {
      resolved +=
          this == YANDEX_ORDERS
              ? "?limit=50"
              : this == YANDEX_WAREHOUSES || this == YANDEX_PARTNER_WAREHOUSES
                  ? "?limit=30"
                  : "?limit=100";
      if (pageToken != null && !pageToken.isEmpty()) {
        if (pageToken.length() > 4096) {
          throw new IllegalArgumentException("Supplier cursor exceeds its limit");
        }
        resolved += "&pageToken=" + URLEncoder.encode(pageToken, StandardCharsets.UTF_8);
      }
    }
    return URI.create(
        (marketplace.equals("OZON")
                ? "https://api-seller.ozon.ru"
                : "https://api.partner.market.yandex.ru")
            + resolved);
  }

  public String contractUri() {
    return marketplace.equals("OZON")
        ? "https://docs.ozon.ru/api/seller/"
        : "https://yandex.ru/dev/market/partner-api/doc/ru/";
  }

  static boolean validReportId(String reportId) {
    return reportId != null
        && !reportId.isBlank()
        && reportId.codePointCount(0, reportId.length()) <= 255
        && StandardCharsets.UTF_8.newEncoder().canEncode(reportId)
        && reportId.codePoints().noneMatch(Character::isISOControl);
  }
}
