package ru.oritas.repricer.marketplace;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;

/** Publishes available quantities only after all relevant pages and physical pools were read. */
@Component
public final class StockSyncHandler implements JobHandler {
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ConnectionService connections;
  private final CatalogSyncService sync;
  private final OutboundGateway gateway;
  private final CapabilityService capabilities;
  private final StoredFileService files;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final OutboxService outbox;
  private final Clock clock;
  private final SourceSnapshotPublisher snapshots;

  public StockSyncHandler(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      CatalogSyncService sync,
      OutboundGateway gateway,
      CapabilityService capabilities,
      StoredFileService files,
      JobRuntime jobs,
      JsonCodec json,
      OutboxService outbox,
      Clock clock,
      SourceSnapshotPublisher snapshots) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.connections = connections;
    this.sync = sync;
    this.gateway = gateway;
    this.capabilities = capabilities;
    this.files = files;
    this.jobs = jobs;
    this.json = json;
    this.outbox = outbox;
    this.clock = clock;
    this.snapshots = snapshots;
  }

  @Override
  public String type() {
    return "STOCK_SYNC";
  }

  @Override
  public String lane() {
    return "fetch";
  }

  @Override
  public JobOutcome execute(JobContext context) throws Exception {
    UUID runId = json.decode(context.payload(), CatalogSyncService.SyncJob.class).runId();
    CatalogSyncService.Run run =
        transactions.run(
            context.scope(),
            () -> {
              jobs.requireOwnership(context);
              authorization.require(context.scope(), "sync.request");
              return sync.run(runId);
            });
    if (run.state().equals("PUBLISHED")) {
      return JobOutcome.succeeded("{}");
    }
    if (Set.of("FAILED", "INCOMPLETE").contains(run.state())) {
      return JobOutcome.blocked("SYNC_INCOMPLETE");
    }
    if (run.page() >= 10000 || run.startedAt().plusSeconds(1800).isBefore(clock.instant())) {
      sync.fail(context.scope(), runId, "TRAVERSAL_LIMIT");
      return JobOutcome.blocked("TRAVERSAL_LIMIT");
    }
    try {
      ConnectionService.Credentials connection =
          transactions.run(context.scope(), () -> connections.current(context.scope()));
      boolean ozon = connection.marketplace().equals("OZON");
      if (ozon && run.phase().equals("DISCOVERY")) {
        phase(context, run, "BASIC", null);
        return waiting();
      }
      Request request = transactions.run(context.scope(), () -> request(run, ozon));
      if (request == null) {
        if (!context.lane().equals("canonicalization")) {
          return JobOutcome.handoff("canonicalization", clock.instant());
        }
        return transactions.run(
            context.scope(),
            () ->
                run.phase().equals(sync.run(runId).phase())
                    ? publish(context, run, ozon)
                    : waiting());
      }
      var previous =
          transactions.run(
              context.scope(),
              () ->
                  jdbc.sql(
                          """
                          SELECT file_id,content_encoding FROM marketplace_raw_page WHERE run_id=:run AND page_number=:page
                          """)
                      .param("run", runId)
                      .param("page", run.page())
                      .query(
                          (row, index) ->
                              new RawPage(row.getObject(1, UUID.class), row.getString(2)))
                      .optional());
      String requiredLane = previous.isPresent() ? "canonicalization" : "fetch";
      if (!context.lane().equals(requiredLane)) {
        return JobOutcome.handoff(requiredLane, clock.instant());
      }
      OutboundGateway.Response response;
      if (previous.isPresent()) {
        RawPage raw = previous.orElseThrow();
        response =
            new OutboundGateway.Response(
                null,
                200,
                transactions.run(context.scope(), () -> files.get(raw.id())),
                raw.encoding(),
                null,
                connection.revision());
      } else {
        response =
            gateway.executePage(
                context.scope(),
                runId,
                run.page(),
                request.method(),
                request.body(),
                request.campaign(),
                run.cursor(),
                request.elements());
      }
      if (response.retryAt() != null) {
        return JobOutcome.waiting("QUOTA_WAIT", response.retryAt());
      }
      if (!response.successful()) {
        if (Set.of(420, 429, 500, 502, 503, 504).contains(response.status())) {
          return JobOutcome.waiting("SUPPLIER_TEMPORARY", clock.instant().plusSeconds(60));
        }
        sync.fail(context.scope(), runId, "SUPPLIER_REJECTED_" + response.status());
        return JobOutcome.blocked("SUPPLIER_REJECTED");
      }
      transactions.run(
          context.scope(),
          () -> {
            jobs.requireOwnership(context);
            jdbc.sql(
                    """
                    INSERT INTO marketplace_raw_page(organization_id,account_id,run_id,page_number,
                      file_id,cursor,method,content_encoding)
                    VALUES (:org,:account,:run,:page,:raw,:cursor,:method,:encoding)
                    ON CONFLICT(run_id,page_number) DO NOTHING
                    """)
                .param("org", context.scope().organizationId())
                .param("account", context.scope().accountId())
                .param("run", runId)
                .param("page", run.page())
                .param("raw", response.raw().id())
                .param("cursor", run.cursor())
                .param("method", request.method().name())
                .param("encoding", response.encoding())
                .update();
            files.retain(response.raw().id(), "sync-run", runId, context.scope());
          });
      if (previous.isEmpty()) {
        return JobOutcome.handoff("canonicalization", clock.instant());
      }
      VendorJsonReader.PageInfo info;
      var poolBatch = new ArrayList<Pool>(200);
      Set<String> requestedOffers =
          request.method() == VendorMethod.OZON_FBS_STOCKS
              ? Set.copyOf(
                  JsonParser.parseString(request.body())
                      .getAsJsonObject()
                      .getAsJsonArray("offer_id")
                      .asList()
                      .stream()
                      .map(value -> value.getAsString())
                      .toList())
              : Set.of();
      try (InputStream input = gateway.open(response)) {
        if (request.method() == VendorMethod.YANDEX_BUSINESS_SETTINGS) {
          saveSettings(
              context, run, connection.externalId(), VendorJsonReader.single(input, "result"));
          info = new VendorJsonReader.PageInfo(1, null, null, false);
        } else {
          info =
              VendorJsonReader.parse(
                  input,
                  request.recordsPath(),
                  request.method() == VendorMethod.YANDEX_PARTNER_STOCKS
                      ? Map.of("result.partnerWarehouseId", request.batchAfter())
                      : Map.of(),
                  record -> {
                    if (run.phase().equals("DISCOVERY")) {
                      saveCampaign(context, run, connection.externalId(), record);
                    } else if (run.phase().equals("WAREHOUSES")) {
                      saveWarehouse(context, run, request.method(), record, response.raw().id());
                    } else if (run.phase().equals("PLACEMENTS")) {
                      savePlacement(context, run, request.campaign(), record);
                    } else if (run.phase().equals("HIDDEN")) {
                      saveHidden(context, run, request.campaign(), record);
                    } else {
                      if (request.method() == VendorMethod.OZON_FBS_STOCKS
                          && !requestedOffers.contains(
                              CatalogSyncService.text(record, "offer_id", true))) {
                        throw new BusinessException(
                            "STOCK_OFFER_SCOPE_MISMATCH",
                            422,
                            "Ответ содержит товар вне запрошенной области");
                      }
                      List<Pool> pools = pools(record, run, request, ozon);
                      for (Pool pool : pools) {
                        poolBatch.add(pool);
                        if (poolBatch.size() == 200) {
                          savePoolBatch(context, run, response.raw().id(), poolBatch);
                          poolBatch.clear();
                        }
                      }
                    }
                  });
        }
      }
      if (!poolBatch.isEmpty()) {
        savePoolBatch(context, run, response.raw().id(), poolBatch);
      }
      transactions.run(
          context.scope(),
          () -> {
            jobs.requireOwnership(context);
            capabilities.confirmRead(context.scope(), request.method(), response.raw().id());
            advance(context, run, request, info, ozon);
          });
      return JobOutcome.handoff("fetch", clock.instant().plusMillis(100));
    } catch (BusinessException exception) {
      sync.fail(context.scope(), runId, exception.code());
      throw exception;
    } catch (java.io.IOException | com.google.gson.JsonParseException exception) {
      sync.fail(context.scope(), runId, "INVALID_OR_INCOMPLETE_SOURCE");
      return JobOutcome.blocked("INVALID_OR_INCOMPLETE_SOURCE");
    }
  }

  private Request request(CatalogSyncService.Run run, boolean ozon) {
    if (run.phase().equals("DISCOVERY")) {
      return new Request(VendorMethod.YANDEX_CAMPAIGNS, "{}", null, "campaigns", 100, null);
    }
    if (run.phase().equals("SETTINGS")) {
      return new Request(VendorMethod.YANDEX_BUSINESS_SETTINGS, "{}", null, "", 1, null);
    }
    if (run.phase().equals("WAREHOUSES")) {
      return new Request(
          warehouseModel(run).equals("WAREHOUSE")
              ? VendorMethod.YANDEX_PARTNER_WAREHOUSES
              : VendorMethod.YANDEX_WAREHOUSES,
          "{}",
          null,
          "result.warehouses",
          30,
          null);
    }
    if (run.phase().equals("BASIC")) {
      return new Request(
          VendorMethod.OZON_STOCKS,
          json.encode(
              Map.of(
                  "filter",
                  Map.of("visibility", "ALL"),
                  "limit",
                  100,
                  "cursor",
                  run.cursor() == null ? "" : run.cursor())),
          null,
          "items",
          100,
          null);
    }
    if (ozon) {
      if (run.phase().equals("FBS")) {
        List<String> offers =
            jdbc.sql(
                    """
                    SELECT sku FROM marketplace_offer WHERE NOT archived
                      AND sku>COALESCE((SELECT batch_after FROM marketplace_sync_run WHERE id=:run),'')
                    ORDER BY sku LIMIT 100
                    """)
                .param("run", run.id())
                .query(String.class)
                .list();
        if (offers.isEmpty()) {
          jdbc.sql("UPDATE marketplace_sync_run SET phase='FBO',batch_after=NULL WHERE id=:run")
              .param("run", run.id())
              .update();
          return null;
        }
        return new Request(
            VendorMethod.OZON_FBS_STOCKS,
            json.encode(
                Map.of(
                    "offer_id",
                    offers,
                    "limit",
                    100,
                    "cursor",
                    run.cursor() == null ? "" : run.cursor())),
            null,
            "products",
            offers.size(),
            offers.getLast());
      }
      List<String> skus =
          jdbc.sql(
                  """
                  SELECT DISTINCT vendor_sku FROM marketplace_offer WHERE NOT archived AND vendor_sku IS NOT NULL
                    AND vendor_sku>COALESCE((SELECT batch_after FROM marketplace_sync_run WHERE id=:run),'')
                  ORDER BY vendor_sku LIMIT 100
                  """)
              .param("run", run.id())
              .query(String.class)
              .list();
      if (skus.isEmpty()) {
        return null;
      }
      return new Request(
          VendorMethod.OZON_FBO_AVAILABLE,
          json.encode(Map.of("skus", skus)),
          null,
          "items",
          skus.size(),
          skus.getLast());
    }
    if (Set.of("PLACEMENTS", "HIDDEN").contains(run.phase())) {
      String completedColumn = run.phase().equals("HIDDEN") ? "hidden_run" : "placements_run";
      var placementCampaign =
          jdbc.sql(
                  """
                  SELECT external_id,availability,placement_type FROM marketplace_campaign WHERE discovered_run=:run
                    AND %s IS DISTINCT FROM :run ORDER BY external_id LIMIT 1
                  """
                      .formatted(completedColumn))
              .param("run", run.id())
              .query(
                  (row, index) ->
                      new Campaign(row.getString(1), row.getString(2), row.getString(3)))
              .optional();
      if (placementCampaign.isPresent()) {
        Campaign selected = placementCampaign.orElseThrow();
        if (!selected.availability().equals("AVAILABLE")) {
          throw new BusinessException("CAMPAIGN_UNAVAILABLE", 422, "Область магазина недоступна");
        }
        return new Request(
            run.phase().equals("HIDDEN")
                ? VendorMethod.YANDEX_HIDDEN_OFFERS
                : VendorMethod.YANDEX_PLACEMENTS,
            "{}",
            selected.id(),
            run.phase().equals("HIDDEN") ? "result.hiddenOffers" : "result.offers",
            100,
            null);
      }
      jdbc.sql("UPDATE marketplace_sync_run SET phase=:phase WHERE id=:run")
          .param("phase", run.phase().equals("HIDDEN") ? "STOCKS" : "HIDDEN")
          .param("run", run.id())
          .update();
      return null;
    }
    boolean unified = warehouseModel(run).equals("WAREHOUSE");
    var campaign =
        jdbc.sql(
                """
                SELECT external_id,availability,placement_type FROM marketplace_campaign WHERE discovered_run=:run
                  AND stocks_run IS DISTINCT FROM :run AND (NOT :unified OR placement_type IN ('FBY','LAAS'))
                  ORDER BY external_id LIMIT 1
                """)
            .param("run", run.id())
            .param("unified", unified)
            .query(
                (row, index) -> new Campaign(row.getString(1), row.getString(2), row.getString(3)))
            .optional();
    if (campaign.isEmpty()) {
      if (!unified) {
        return null;
      }
      var warehouse =
          jdbc.sql(
                  """
                  SELECT external_id,models::text FROM marketplace_warehouse WHERE discovered_run=:run
                    AND stocks_run IS DISTINCT FROM :run ORDER BY external_id LIMIT 1
                  """)
              .param("run", run.id())
              .query((row, index) -> Map.entry(row.getString(1), row.getString(2)))
              .optional();
      if (warehouse.isEmpty()) {
        return null;
      }
      var selectedWarehouse = warehouse.orElseThrow();
      JsonArray models = JsonParser.parseString(selectedWarehouse.getValue()).getAsJsonArray();
      if (models.isEmpty()
          || models.asList().stream()
              .anyMatch(
                  value ->
                      !"AVAILABLE"
                          .equals(
                              CatalogSyncService.text(
                                  value.getAsJsonObject(), "apiAvailability", true)))) {
        throw new BusinessException(
            "WAREHOUSE_UNAVAILABLE", 422, "Модель склада недоступна для полного чтения");
      }
      return new Request(
          VendorMethod.YANDEX_PARTNER_STOCKS,
          json.encode(Map.of("partnerWarehouseId", Long.parseLong(selectedWarehouse.getKey()))),
          null,
          "result.offers",
          100,
          selectedWarehouse.getKey());
    }
    Campaign selected = campaign.orElseThrow();
    if (!selected.availability().equals("AVAILABLE")) {
      throw new BusinessException(
          "CAMPAIGN_UNAVAILABLE", 422, "Один из магазинов не допускает полный обход остатков");
    }
    return new Request(
        VendorMethod.YANDEX_STOCKS, "{}", selected.id(), "result.warehouses", 100, selected.type());
  }

  private void savePlacement(
      JobContext context, CatalogSyncService.Run run, String campaign, JsonObject record) {
    String sku = CatalogSyncService.text(record, "offerId", true);
    String status = CatalogSyncService.text(record, "status", true);
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          UUID offer =
              jdbc.sql("SELECT id FROM marketplace_offer WHERE sku=:sku")
                  .param("sku", sku)
                  .query(UUID.class)
                  .optional()
                  .orElseThrow(
                      () ->
                          new BusinessException(
                              "PLACEMENT_OFFER_UNKNOWN",
                              422,
                              "Товар отсутствует в полном каталоге"));
          int changed =
              jdbc.sql(
                      """
                      INSERT INTO marketplace_placement_stage(organization_id,account_id,run_id,campaign_id,
                        offer_id,status,available,fields)
                      VALUES (:org,:account,:run,:campaign,:offer,:status,:available,CAST(:fields AS jsonb))
                      ON CONFLICT(run_id,campaign_id,offer_id) DO UPDATE SET fields=EXCLUDED.fields
                      WHERE marketplace_placement_stage.fields=EXCLUDED.fields
                      """)
                  .param("org", context.scope().organizationId())
                  .param("account", context.scope().accountId())
                  .param("run", run.id())
                  .param("campaign", campaign)
                  .param("offer", offer)
                  .param("status", status)
                  .param("available", status.equals("PUBLISHED"))
                  .param("fields", record.toString())
                  .update();
          if (changed != 1) {
            throw new BusinessException(
                "PLACEMENT_SCOPE_CONFLICT", 422, "Размещения противоречат друг другу");
          }
        });
  }

  private String warehouseModel(CatalogSyncService.Run run) {
    return jdbc.sql("SELECT warehouse_model FROM marketplace_sync_run WHERE id=:id")
        .param("id", run.id())
        .query(String.class)
        .optional()
        .orElseThrow(
            () ->
                new BusinessException(
                    "WAREHOUSE_MODEL_UNKNOWN", 422, "Модель складов не подтверждена"));
  }

  private void saveSettings(
      JobContext context, CatalogSyncService.Run run, String businessId, JsonObject result) {
    if (!businessId.equals(CatalogSyncService.text(result.getAsJsonObject("info"), "id", true))) {
      throw new BusinessException(
          "ACCOUNT_SCOPE_MISMATCH", 422, "Настройки относятся к другому кабинету");
    }
    String model =
        CatalogSyncService.text(result.getAsJsonObject("settings"), "warehouseModel", true);
    if (!Set.of("CAMPAIGN", "WAREHOUSE").contains(model)) {
      throw new BusinessException("WAREHOUSE_MODEL_UNKNOWN", 422, "Модель складов не подтверждена");
    }
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          jdbc.sql("UPDATE marketplace_sync_run SET warehouse_model=:model WHERE id=:id")
              .param("model", model)
              .param("id", run.id())
              .update();
        });
  }

  private void saveWarehouse(
      JobContext context,
      CatalogSyncService.Run run,
      VendorMethod method,
      JsonObject record,
      UUID raw) {
    String id = CatalogSyncService.text(record, "id", true);
    String name = CatalogSyncService.text(record, "name", true);
    boolean unified = method == VendorMethod.YANDEX_PARTNER_WAREHOUSES;
    String campaign = unified ? null : CatalogSyncService.text(record, "campaignId", true);
    JsonObject group =
        record.has("groupInfo") && record.get("groupInfo").isJsonObject()
            ? record.getAsJsonObject("groupInfo")
            : null;
    String groupId = group == null ? null : CatalogSyncService.text(group, "id", true);
    String groupName = group == null ? null : CatalogSyncService.text(group, "name", true);
    try {
      if (Long.parseLong(id) < 1 || (groupId != null && Long.parseLong(groupId) < 1)) {
        throw new NumberFormatException("Non-positive supplier identity");
      }
    } catch (NumberFormatException exception) {
      throw new BusinessException(
          "WAREHOUSE_IDENTITY_INVALID", 422, "Идентичность склада не подтверждена");
    }
    JsonArray models = unified ? array(record, "models") : new JsonArray();
    if (unified && (models.isEmpty() || models.size() > 3)) {
      throw new BusinessException(
          "WAREHOUSE_MODELS_UNKNOWN", 422, "Не подтверждён состав моделей склада");
    }
    for (var model : models) {
      if (!Set.of("FBS", "DBS", "EXPRESS")
          .contains(CatalogSyncService.text(model.getAsJsonObject(), "placementType", true))) {
        throw new BusinessException(
            "WAREHOUSE_MODELS_UNKNOWN", 422, "Не подтверждён состав моделей склада");
      }
      CatalogSyncService.text(model.getAsJsonObject(), "apiAvailability", true);
    }
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          if (!unified) {
            JsonObject model =
                jdbc.sql(
                        """
                        SELECT placement_type,availability FROM marketplace_campaign
                        WHERE external_id=:campaign AND discovered_run=:run
                        """)
                    .param("campaign", campaign)
                    .param("run", run.id())
                    .query(
                        (row, index) -> {
                          JsonObject value = new JsonObject();
                          value.addProperty("placementType", row.getString(1));
                          value.addProperty("apiAvailability", row.getString(2));
                          return value;
                        })
                    .optional()
                    .orElseThrow(
                        () ->
                            new BusinessException(
                                "WAREHOUSE_CAMPAIGN_SCOPE_UNKNOWN",
                                422,
                                "Склад относится к неподтверждённому магазину"));
            models.add(model);
          }
          jdbc.sql(
                  """
                  INSERT INTO marketplace_warehouse(id,organization_id,account_id,external_id,name,warehouse_model,
                    campaign_id,group_id,group_name,models,discovered_run,raw_file_id,observed_at)
                  VALUES(md5(:account::text||':warehouse:'||:model||':'||:external)::uuid,
                    :org,:account,:external,:name,:model,:campaign,:group,:groupName,CAST(:models AS jsonb),:run,:raw,:observed)
                  ON CONFLICT(organization_id,account_id,warehouse_model,external_id) DO UPDATE SET
                    name=EXCLUDED.name,campaign_id=EXCLUDED.campaign_id,group_id=EXCLUDED.group_id,group_name=EXCLUDED.group_name,
                    models=EXCLUDED.models,discovered_run=EXCLUDED.discovered_run,
                    raw_file_id=EXCLUDED.raw_file_id,observed_at=EXCLUDED.observed_at
                  """)
              .param("org", context.scope().organizationId())
              .param("account", context.scope().accountId())
              .param("external", id)
              .param("name", name)
              .param("model", unified ? "WAREHOUSE" : "CAMPAIGN")
              .param("campaign", campaign)
              .param("group", groupId)
              .param("groupName", groupName)
              .param("models", models.toString())
              .param("run", run.id())
              .param("raw", raw)
              .param("observed", Timestamp.from(run.startedAt()))
              .update();
        });
  }

  private void saveHidden(
      JobContext context, CatalogSyncService.Run run, String campaign, JsonObject record) {
    String sku = CatalogSyncService.text(record, "offerId", true);
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          jdbc.sql(
                  """
                  UPDATE marketplace_placement_stage s SET hidden=true FROM marketplace_offer o
                  WHERE s.run_id=:run AND s.campaign_id=:campaign AND s.offer_id=o.id AND o.sku=:sku
                  """)
              .param("run", run.id())
              .param("campaign", campaign)
              .param("sku", sku)
              .update();
        });
  }

  private void saveCampaign(
      JobContext context, CatalogSyncService.Run run, String businessId, JsonObject record) {
    JsonObject business = record.getAsJsonObject("business");
    if (!businessId.equals(CatalogSyncService.text(business, "id", true))) {
      throw new BusinessException(
          "ACCOUNT_SCOPE_MISMATCH", 422, "Ответ площадки относится к другому кабинету");
    }
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          jdbc.sql(
                  """
                  INSERT INTO marketplace_campaign(organization_id,account_id,external_id,placement_type,
                    availability,discovered_run)
                  VALUES (:org,:account,:id,:type,:availability,:run)
                  ON CONFLICT(organization_id,account_id,external_id) DO UPDATE SET
                    placement_type=EXCLUDED.placement_type,availability=EXCLUDED.availability,
                    discovered_run=EXCLUDED.discovered_run
                  """)
              .param("org", context.scope().organizationId())
              .param("account", context.scope().accountId())
              .param("id", CatalogSyncService.text(record, "id", true))
              .param("type", CatalogSyncService.text(record, "placementType", true))
              .param("availability", CatalogSyncService.text(record, "apiAvailability", true))
              .param("run", run.id())
              .update();
        });
  }

  private List<Pool> pools(
      JsonObject record, CatalogSyncService.Run run, Request request, boolean ozon) {
    var pools = new ArrayList<Pool>();
    if (!ozon) {
      boolean unified = request.method() == VendorMethod.YANDEX_PARTNER_STOCKS;
      String warehouse =
          unified ? request.batchAfter() : CatalogSyncService.text(record, "warehouseId", true);
      JsonArray offers;
      if (unified) {
        offers = new JsonArray();
        offers.add(record);
      } else {
        offers = array(record, "offers");
      }
      for (var value : offers) {
        JsonObject offer = value.getAsJsonObject();
        String sku = CatalogSyncService.text(offer, "offerId", true);
        BigDecimal available = null;
        BigDecimal reserved = null;
        for (var stockValue : array(offer, "stocks")) {
          JsonObject stock = stockValue.getAsJsonObject();
          String type = CatalogSyncService.text(stock, "type", true);
          if (type.equals("AVAILABLE")) {
            available = CatalogSyncService.decimal(stock, "count");
          } else if (type.equals("FREEZE")) {
            reserved = CatalogSyncService.decimal(stock, "count");
          }
        }
        String observed = CatalogSyncService.text(offer, "updatedAt", true);
        pools.add(
            new Pool(
                (unified ? "partner:" : "warehouse:") + warehouse + ":" + sku,
                sku,
                OfferIdentity.EXTERNAL,
                "Склад " + warehouse,
                unified
                    ? "YANDEX_UNIFIED"
                    : Set.of("FBY", "LAAS").contains(request.batchAfter())
                        ? "YANDEX_FBY"
                        : "YANDEX",
                available,
                reserved,
                Instant.parse(observed),
                null,
                warehouse));
      }
    } else if (request.method() == VendorMethod.OZON_FBS_STOCKS) {
      String sku = CatalogSyncService.text(record, "offer_id", true);
      String warehouse = CatalogSyncService.text(record, "warehouse_id", true);
      pools.add(
          new Pool(
              "fbs:" + warehouse + ":" + sku,
              sku,
              OfferIdentity.SELLER_SKU,
              CatalogSyncService.text(record, "warehouse_name", true),
              "FBS",
              CatalogSyncService.decimal(record, "free_stock"),
              CatalogSyncService.decimal(record, "reserved"),
              run.startedAt(),
              CatalogSyncService.decimal(record, "present"),
              warehouse));
    } else if (request.method() == VendorMethod.OZON_STOCKS) {
      String product = CatalogSyncService.text(record, "product_id", true);
      for (var value : array(record, "stocks")) {
        JsonObject stock = value.getAsJsonObject();
        String type = CatalogSyncService.text(stock, "type", true);
        if (!type.equalsIgnoreCase("fbs")) {
          continue;
        }
        String warehouse = CatalogSyncService.text(stock, "warehouse_id", false);
        String suffix = warehouse == null ? "aggregate" : warehouse;
        pools.add(
            new Pool(
                "fbs:" + suffix + ":" + product,
                product,
                OfferIdentity.EXTERNAL,
                "FBS " + suffix,
                "FBS_AGGREGATE",
                null,
                CatalogSyncService.decimal(stock, "reserved"),
                run.startedAt(),
                null,
                null));
      }
    } else {
      String sku = CatalogSyncService.text(record, "sku", true);
      String warehouse = CatalogSyncService.text(record, "warehouse_id", false);
      String suffix = warehouse == null ? "aggregate" : warehouse;
      String warehouseName = CatalogSyncService.text(record, "warehouse_name", false);
      pools.add(
          new Pool(
              "fbo:" + suffix + ":" + sku,
              sku,
              OfferIdentity.VENDOR_SKU,
              warehouseName == null ? "FBO " + suffix : warehouseName,
              "FBO",
              CatalogSyncService.decimal(record, "available_stock_count"),
              null,
              run.startedAt(),
              null,
              warehouseName == null ? null : warehouse));
    }
    return pools;
  }

  private void savePoolBatch(
      JobContext context, CatalogSyncService.Run run, UUID raw, List<Pool> pools) {
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          String identityColumn =
              switch (pools.getFirst().identity()) {
                case EXTERNAL -> "external_id";
                case VENDOR_SKU -> "vendor_sku";
                case SELLER_SKU -> "sku";
              };
          List<String> keys = pools.stream().map(Pool::offerExternal).distinct().toList();
          Map<String, UUID> offers = new HashMap<>();
          var matches =
              jdbc.sql(
                      "SELECT "
                          + identityColumn
                          + ",id FROM marketplace_offer WHERE "
                          + identityColumn
                          + " IN (:keys) ORDER BY "
                          + identityColumn
                          + ",id LIMIT 201")
                  .param("keys", keys)
                  .query((row, index) -> Map.entry(row.getString(1), row.getObject(2, UUID.class)))
                  .list();
          for (var match : matches) {
            if (offers.putIfAbsent(match.getKey(), match.getValue()) != null) {
              throw new BusinessException(
                  "STOCK_OFFER_AMBIGUOUS",
                  422,
                  "Идентификатор остатка не определяет единственный товар");
            }
          }
          if (offers.size() != keys.size()) {
            throw new BusinessException(
                "STOCK_OFFER_UNKNOWN",
                422,
                "Остаток относится к товару вне подтверждённого каталога");
          }
          saveOzonWarehouses(context.scope(), run, raw, pools);
          Map<String, Warehouse> warehouses = new HashMap<>();
          List<String> warehouseIds =
              pools.stream()
                  .map(Pool::warehouse)
                  .filter(java.util.Objects::nonNull)
                  .distinct()
                  .toList();
          if (!warehouseIds.isEmpty()) {
            jdbc.sql(
                    """
                    SELECT external_id,warehouse_model,COALESCE(group_name,name) AS name,group_id FROM marketplace_warehouse
                        WHERE discovered_run=:run AND external_id IN (:ids) ORDER BY external_id LIMIT 401
                    """)
                .param("run", run.id())
                .param("ids", warehouseIds)
                .query(
                    (row, index) ->
                        new Warehouse(
                            row.getString(1), row.getString(2), row.getString(3), row.getString(4)))
                .list()
                .forEach(value -> warehouses.put(value.model() + ":" + value.id(), value));
          }
          for (Pool pool : pools) {
            for (BigDecimal quantity :
                new BigDecimal[] {pool.free(), pool.covered(), pool.physical()}) {
              if (quantity != null
                  && (quantity.signum() < 0 || quantity.stripTrailingZeros().scale() > 0)) {
                throw new BusinessException(
                    "STOCK_QUANTITY_INVALID",
                    422,
                    "Остаток должен быть неотрицательным целым количеством");
              }
            }
            UUID offer = offers.get(pool.offerExternal());
            Warehouse warehouse =
                warehouses.get(warehouseModel(pool.type()) + ":" + pool.warehouse());
            String external =
                warehouse != null && warehouse.group() != null
                    ? "group:" + warehouse.group() + ":" + pool.offerExternal()
                    : pool.external();
            int changed =
                jdbc.sql(
                        """
                        INSERT INTO marketplace_stock_stage(organization_id,account_id,run_id,external_id,
                          offer_id,name,warehouse_type,free_quantity,obligations_covered,observed_at,complete,physical_quantity)
                        VALUES (:org,:account,:run,:id,:offer,:name,:type,:free,:covered,:observed,:complete,:physical)
                        ON CONFLICT(run_id,external_id) DO UPDATE SET observed_at=EXCLUDED.observed_at
                        WHERE marketplace_stock_stage.free_quantity IS NOT DISTINCT FROM EXCLUDED.free_quantity
                          AND marketplace_stock_stage.obligations_covered IS NOT DISTINCT FROM EXCLUDED.obligations_covered
                          AND marketplace_stock_stage.physical_quantity IS NOT DISTINCT FROM EXCLUDED.physical_quantity
                          AND marketplace_stock_stage.offer_id=EXCLUDED.offer_id
                          AND marketplace_stock_stage.warehouse_type=EXCLUDED.warehouse_type
                        """)
                    .param("org", context.scope().organizationId())
                    .param("account", context.scope().accountId())
                    .param("run", run.id())
                    .param("id", external)
                    .param("offer", offer)
                    .param("name", warehouse == null ? pool.name() : warehouse.name())
                    .param("type", pool.type())
                    .param("free", pool.free())
                    .param("covered", pool.covered())
                    .param("physical", pool.physical())
                    .param("observed", Timestamp.from(pool.observed()))
                    .param(
                        "complete",
                        pool.free() != null
                            && pool.covered() != null
                            && (!pool.type().equals("YANDEX") || warehouse != null))
                    .update();
            if (changed != 1) {
              throw new BusinessException(
                  "STOCK_POOL_CONFLICT", 422, "Один физический пул получил несовместимые значения");
            }
          }
          jobs.requireOwnership(context);
        });
  }

  private static String warehouseModel(String poolType) {
    return switch (poolType) {
      case "FBS" -> "OZON_FBS";
      case "FBO" -> "OZON_FBO";
      case "YANDEX_UNIFIED" -> "WAREHOUSE";
      default -> "CAMPAIGN";
    };
  }

  private void saveOzonWarehouses(
      Scope scope, CatalogSyncService.Run run, UUID raw, List<Pool> pools) {
    Map<String, Pool> warehouses = new TreeMap<>();
    for (Pool pool : pools) {
      if (pool.warehouse() == null || !(pool.type().equals("FBS") || pool.type().equals("FBO"))) {
        continue;
      }
      try {
        if (Long.parseLong(pool.warehouse()) <= 0) {
          throw new NumberFormatException("Non-positive supplier identity");
        }
      } catch (NumberFormatException exception) {
        throw new BusinessException(
            "WAREHOUSE_IDENTITY_INVALID", 422, "Идентичность склада не подтверждена");
      }
      String identity = warehouseModel(pool.type()) + ":" + pool.warehouse();
      Pool previous = warehouses.putIfAbsent(identity, pool);
      if (previous != null && !previous.name().equals(pool.name())) {
        throw new BusinessException(
            "WAREHOUSE_IDENTITY_CONFLICT", 422, "Один склад получил несовместимые названия");
      }
    }
    for (Pool pool : warehouses.values()) {
      int changed =
          jdbc.sql(
                  """
                  INSERT INTO marketplace_warehouse(id,organization_id,account_id,external_id,name,warehouse_model,
                    models,discovered_run,raw_file_id,observed_at)
                  VALUES(md5(:account::text||':warehouse:'||:model||':'||:external)::uuid,
                    :org,:account,:external,:name,:model,CAST(:models AS jsonb),:run,:raw,:observed)
                  ON CONFLICT(organization_id,account_id,warehouse_model,external_id) DO UPDATE SET
                    name=EXCLUDED.name,models=EXCLUDED.models,discovered_run=EXCLUDED.discovered_run,
                    raw_file_id=EXCLUDED.raw_file_id,observed_at=EXCLUDED.observed_at
                  WHERE marketplace_warehouse.observed_at<EXCLUDED.observed_at OR
                    (marketplace_warehouse.discovered_run=EXCLUDED.discovered_run
                      AND marketplace_warehouse.name=EXCLUDED.name)
                  """)
              .param("org", scope.organizationId())
              .param("account", scope.requireAccount())
              .param("external", pool.warehouse())
              .param("name", pool.name())
              .param("model", warehouseModel(pool.type()))
              .param("models", json.encode(List.of(Map.of("placementType", pool.type()))))
              .param("run", run.id())
              .param("raw", raw)
              .param("observed", Timestamp.from(run.startedAt()))
              .update();
      if (changed != 1) {
        throw new BusinessException(
            "WAREHOUSE_OBSERVATION_CONFLICT",
            409,
            "Обход не может заменить более новое или несовместимое наблюдение склада");
      }
    }
  }

  private void advance(
      JobContext context,
      CatalogSyncService.Run run,
      Request request,
      VendorJsonReader.PageInfo info,
      boolean ozon) {
    String phase = run.phase();
    String next = info.cursor();
    boolean lastFbsPage =
        request.method() == VendorMethod.OZON_FBS_STOCKS && Boolean.FALSE.equals(info.hasNext());
    if (!lastFbsPage && next != null && !next.isBlank() && next.equals(run.cursor())) {
      throw new BusinessException("CURSOR_STALLED", 422, "Курсор площадки не продвигается");
    }
    if (ozon && phase.equals("FBS")) {
      if (info.hasNext() == null || (info.hasNext() && (next == null || next.isBlank()))) {
        throw new BusinessException(
            "STOCK_PAGINATION_UNKNOWN", 422, "Не подтверждена полнота складских остатков");
      }
      if (!info.hasNext()) {
        jdbc.sql("UPDATE marketplace_sync_run SET batch_after=:after WHERE id=:id")
            .param("after", request.batchAfter())
            .param("id", run.id())
            .update();
        next = null;
      }
    } else if (ozon && phase.equals("FBO")) {
      jdbc.sql("UPDATE marketplace_sync_run SET batch_after=:after WHERE id=:id")
          .param("after", request.batchAfter())
          .param("id", run.id())
          .update();
      next = null;
    } else if (next == null || next.isBlank()) {
      next = null;
      if (phase.equals("DISCOVERY")) {
        phase = "SETTINGS";
      } else if (phase.equals("SETTINGS")) {
        phase = "WAREHOUSES";
      } else if (phase.equals("WAREHOUSES")) {
        phase = "PLACEMENTS";
      } else if (phase.equals("BASIC")) {
        phase = "FBS";
      } else if (!ozon) {
        String completed =
            switch (request.method()) {
              case YANDEX_PLACEMENTS -> "placements_run";
              case YANDEX_HIDDEN_OFFERS -> "hidden_run";
              default -> "stocks_run";
            };
        boolean unified = request.method() == VendorMethod.YANDEX_PARTNER_STOCKS;
        jdbc.sql(
                "UPDATE "
                    + (unified ? "marketplace_warehouse" : "marketplace_campaign")
                    + " SET "
                    + completed
                    + "=:run WHERE external_id=:id AND discovered_run=:run")
            .param("run", run.id())
            .param("id", unified ? request.batchAfter() : request.campaign())
            .update();
      }
    }
    jdbc.sql(
            """
            UPDATE marketplace_raw_page SET parsed=true,item_count=:count,next_cursor=:next
            WHERE run_id=:run AND page_number=:page
            """)
        .param("count", info.records())
        .param("next", next)
        .param("run", run.id())
        .param("page", run.page())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_sync_run SET phase=:phase,next_cursor=:next,page_number=page_number+1,
              parsed_count=parsed_count+:count,revision=revision+1 WHERE id=:id
            """)
        .param("phase", phase)
        .param("next", next)
        .param("count", info.records())
        .param("id", run.id())
        .update();
  }

  private void phase(JobContext context, CatalogSyncService.Run run, String phase, String cursor) {
    transactions.run(
        context.scope(),
        () ->
            jdbc.sql(
                    """
                    UPDATE marketplace_sync_run SET phase=:phase,next_cursor=:cursor WHERE id=:id
                    """)
                .param("phase", phase)
                .param("cursor", cursor)
                .param("id", run.id())
                .update());
  }

  private JobOutcome publish(JobContext context, CatalogSyncService.Run run, boolean ozon) {
    jobs.requireOwnership(context);
    jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
        .param("id", context.scope().accountId())
        .query(UUID.class)
        .single();
    boolean late =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_stock_pool WHERE observed_at>:observed)
                """)
            .param("observed", Timestamp.from(run.startedAt()))
            .query(Boolean.class)
            .single();
    if (late) {
      throw new BusinessException(
          "SOURCE_OBSERVATION_SUPERSEDED",
          409,
          "Поздний обход не может заменить более новое наблюдение");
    }
    boolean overlapping =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_stock_stage WHERE run_id=:run
                  AND warehouse_type<>'FBS_AGGREGATE'
                  GROUP BY offer_id,warehouse_type HAVING count(*)>1 AND bool_or(
                    external_id LIKE 'fbs:aggregate:%' OR external_id LIKE 'fbo:aggregate:%'))
                """)
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    if (overlapping) {
      throw new BusinessException(
          "STOCK_POOL_OVERLAP",
          422,
          "Общий остаток и остатки его складов нельзя учитывать как независимые пулы");
    }
    if (!ozon) {
      jdbc.sql(
              """
              INSERT INTO marketplace_placement(id,organization_id,account_id,offer_id,external_id,
                model,status,available,fields,publication_id,observed_at,valid_until)
              SELECT md5(s.account_id::text||':placement:'||s.campaign_id||':'||s.offer_id::text)::uuid,
                s.organization_id,s.account_id,s.offer_id,s.campaign_id,c.placement_type,s.status,
                s.available AND NOT s.hidden,s.fields,:run,clock_timestamp(),clock_timestamp()+interval '15 minutes'
              FROM marketplace_placement_stage s JOIN marketplace_campaign c
                ON c.account_id=s.account_id AND c.external_id=s.campaign_id WHERE s.run_id=:run
              ON CONFLICT(organization_id,account_id,offer_id,external_id) DO UPDATE SET
                model=EXCLUDED.model,status=EXCLUDED.status,available=EXCLUDED.available,
                fields=EXCLUDED.fields,publication_id=EXCLUDED.publication_id,
                observed_at=EXCLUDED.observed_at,valid_until=EXCLUDED.valid_until,
                revision=marketplace_placement.revision+1
              """)
          .param("run", run.id())
          .update();
      jdbc.sql(
              """
              UPDATE marketplace_placement SET available=false,valid_until=clock_timestamp()
              WHERE publication_id<>:run
              """)
          .param("run", run.id())
          .update();
    }
    boolean incomplete =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM marketplace_stock_stage WHERE run_id=:run
                  AND warehouse_type<>'FBS_AGGREGATE' AND NOT complete)
                """)
            .param("run", run.id())
            .query(Boolean.class)
            .single();
    boolean missingFbsCoverage = false;
    if (ozon) {
      // The aggregate proves this offer has FBS inventory, but cannot identify its physical pools.
      // An empty detailed response is missing coverage, not a confirmed zero quantity.
      missingFbsCoverage =
          jdbc.sql(
                  """
                  SELECT EXISTS(SELECT 1 FROM marketplace_stock_stage aggregate_stock
                    WHERE aggregate_stock.run_id=:run AND aggregate_stock.warehouse_type='FBS_AGGREGATE'
                      AND NOT EXISTS(SELECT 1 FROM marketplace_stock_stage detail
                        WHERE detail.run_id=aggregate_stock.run_id AND detail.offer_id=aggregate_stock.offer_id
                          AND detail.warehouse_type='FBS'))
                  """)
              .param("run", run.id())
              .query(Boolean.class)
              .single();
      incomplete |= missingFbsCoverage;
      incomplete |=
          jdbc.sql(
                  """
                  SELECT EXISTS(SELECT 1 FROM marketplace_offer WHERE NOT archived AND vendor_sku IS NULL)
                  """)
              .query(Boolean.class)
              .single();
    }
    String reason =
        missingFbsCoverage ? "FBS_WAREHOUSE_COVERAGE_UNKNOWN" : "AVAILABLE_OR_RESERVATION_UNKNOWN";
    jdbc.sql(
            """
            UPDATE marketplace_stock_pool SET complete=false WHERE NOT EXISTS(
              SELECT 1 FROM marketplace_stock_stage s WHERE s.run_id=:run
                AND s.warehouse_type<>'FBS_AGGREGATE'
                AND s.external_id=marketplace_stock_pool.external_id)
            """)
        .param("run", run.id())
        .update();
    jdbc.sql(
            """
            INSERT INTO marketplace_stock_pool(id,organization_id,account_id,external_id,offer_id,
              name,warehouse_type,free_quantity,obligations_covered,observed_at,valid_until,complete,physical_quantity)
            SELECT md5(account_id::text||':'||external_id)::uuid,organization_id,account_id,external_id,
              offer_id,name,warehouse_type,free_quantity,obligations_covered,observed_at,
              observed_at+interval '15 minutes',complete,physical_quantity FROM marketplace_stock_stage
              WHERE run_id=:run AND warehouse_type<>'FBS_AGGREGATE'
            ON CONFLICT(organization_id,account_id,external_id) DO UPDATE SET
              free_quantity=EXCLUDED.free_quantity,obligations_covered=EXCLUDED.obligations_covered,
              physical_quantity=EXCLUDED.physical_quantity,name=EXCLUDED.name,
              observed_at=EXCLUDED.observed_at,valid_until=EXCLUDED.valid_until,
              complete=EXCLUDED.complete,revision=marketplace_stock_pool.revision+CASE WHEN
                (marketplace_stock_pool.free_quantity,marketplace_stock_pool.obligations_covered,
                  marketplace_stock_pool.complete,marketplace_stock_pool.physical_quantity)
                IS DISTINCT FROM (EXCLUDED.free_quantity,EXCLUDED.obligations_covered,EXCLUDED.complete,EXCLUDED.physical_quantity)
                THEN 1 ELSE 0 END
            """)
        .param("run", run.id())
        .update();
    jdbc.sql(
            """
            INSERT INTO marketplace_stock_observation(organization_id,account_id,pool_id,source_run_id,
              observed_at,free_quantity,obligations_covered,complete,semantic_revision,physical_quantity)
            SELECT p.organization_id,p.account_id,p.id,:run,s.observed_at,s.free_quantity,
              s.obligations_covered,s.complete,p.revision,s.physical_quantity FROM marketplace_stock_stage s
            JOIN marketplace_stock_pool p ON p.account_id=s.account_id AND p.external_id=s.external_id
            WHERE s.run_id=:run AND s.warehouse_type<>'FBS_AGGREGATE'
            ON CONFLICT(pool_id,source_run_id) DO NOTHING
            """)
        .param("run", run.id())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_sync_run SET state=:state,completed_at=clock_timestamp(),
              reason=:reason,revision=revision+1 WHERE id=:id
            """)
        .param("state", incomplete ? "INCOMPLETE" : "PUBLISHED")
        .param("reason", incomplete ? reason : null)
        .param("id", run.id())
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_source SET status=:status,last_success_at=:observed,
              publication_id=:run,revision=revision+1,reason=:reason WHERE source_type='STOCKS'
            """)
        .param("status", incomplete ? "INCOMPLETE" : "READY")
        .param("observed", Timestamp.from(run.startedAt()))
        .param("run", run.id())
        .param("reason", incomplete ? reason : null)
        .update();
    outbox.emit(
        context.scope(),
        "stock:" + run.id(),
        "stocks.changed",
        new OutboxService.EntityChange("sources", run.id(), run.page() + 1L));
    snapshots.refresh(context.scope(), run.id());
    return incomplete ? JobOutcome.blocked(reason) : JobOutcome.succeeded("{}");
  }

  private JobOutcome waiting() {
    return JobOutcome.waiting("NEXT_PAGE", clock.instant().plusMillis(100));
  }

  private static JsonArray array(JsonObject object, String field) {
    if (!object.has(field)
        || !object.get(field).isJsonArray()
        || object.getAsJsonArray(field).size() > 1000) {
      throw new BusinessException("INVALID_STOCK_SCOPE", 422, "Не подтверждён состав остатков");
    }
    return object.getAsJsonArray(field);
  }

  private record Request(
      VendorMethod method,
      String body,
      String campaign,
      String recordsPath,
      int elements,
      String batchAfter) {}

  private record Campaign(String id, String availability, String type) {}

  private record RawPage(UUID id, String encoding) {}

  private record Pool(
      String external,
      String offerExternal,
      OfferIdentity identity,
      String name,
      String type,
      BigDecimal free,
      BigDecimal covered,
      Instant observed,
      BigDecimal physical,
      String warehouse) {}

  private enum OfferIdentity {
    EXTERNAL,
    VENDOR_SKU,
    SELLER_SKU
  }

  private record Warehouse(String id, String model, String name, String group) {}
}
