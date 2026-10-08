package ru.oritas.repricer.marketplace;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.Scope;

/** Publishes typed canonical evidence; absent supplier economics remains explicitly incomplete. */
@Service
public final class SourceSnapshotPublisher {
  private final JdbcClient jdbc;

  public SourceSnapshotPublisher(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void refresh(Scope scope, UUID publication) {
    ScopeTransactionRunner.requireCurrent(scope);
    UUID raw =
        jdbc.sql(
                """
                SELECT file_id FROM marketplace_raw_page WHERE run_id=:run ORDER BY page_number DESC LIMIT 1
                """)
            .param("run", publication)
            .query(UUID.class)
            .optional()
            .orElse(null);
    if (raw == null) {
      return;
    }
    jdbc.sql("SELECT id FROM marketplace_account WHERE id=:id FOR UPDATE")
        .param("id", scope.requireAccount())
        .query(UUID.class)
        .single();
    jdbc.sql("UPDATE marketplace_commercial_state SET current=false WHERE current").update();
    jdbc.sql(
            """
            INSERT INTO marketplace_commercial_state(organization_id,account_id,offer_id,revision,
              snapshot,current,raw_file_id)
            SELECT o.organization_id,o.account_id,o.id,COALESCE(previous.revision,0)+1,
              jsonb_build_object('offerId',o.id,'revision',o.revision,'profileRevision',a.capabilities_revision,
                'observedAt',o.observed_at,'validUntil',o.observed_at+interval '15 minutes',
                'complete',o.commercial_fields IS NOT NULL AND scopes.complete
                  AND COALESCE(promos.status='READY' AND promos.last_success_at>
                    clock_timestamp()-interval '15 minutes',false)
                  AND COALESCE(participations.total,0)<=100
                  AND COALESCE(participations.complete,true) AND COALESCE(options.total,0)<=100,
                'targetIds',jsonb_build_array(o.id),
                'currentParticipations',COALESCE(participations.entries,'[]'::jsonb),
                'availablePromotions',COALESCE(options.entries,'[]'::jsonb),
                'supportedOperations',COALESCE(capabilities.operations,'[]'::jsonb),
                'stockPoolId',stock.id,'verifiedQuantity',NULL,
                'externallyBoundedLoss',false,'basePriceStep','0.01',
                'confirmedPreservedFields',CASE WHEN capabilities.base_preserved
                  THEN jsonb_build_array('price') ELSE '[]'::jsonb END),true,:raw
            FROM marketplace_offer o JOIN marketplace_account a ON a.id=o.account_id
            LEFT JOIN marketplace_source promos ON promos.account_id=o.account_id AND promos.source_type='PROMOTIONS'
            LEFT JOIN LATERAL (SELECT count(*)>0 AND count(*)<=200
                AND bool_and(p.valid_until>clock_timestamp()) AS complete
              FROM marketplace_placement p WHERE p.offer_id=o.id) scopes ON true
            LEFT JOIN LATERAL (SELECT jsonb_agg(DISTINCT operation ORDER BY operation) AS operations,
              bool_or(method='YANDEX_SET_PROMO' AND scope_complete AND processing_tested) AS base_preserved,
              bool_or(method='YANDEX_LEAVE_PROMO' AND scope_complete AND processing_tested) AS manual_exit
              FROM (
              SELECT CASE method WHEN 'OZON_SET_PRICE' THEN 'SET_PRICE'
                WHEN 'YANDEX_SET_PRICE' THEN 'SET_PRICE' WHEN 'YANDEX_SET_PROMO' THEN 'JOIN_PROMO'
                WHEN 'YANDEX_LEAVE_PROMO' THEN 'LEAVE_PROMO' END AS operation,method,
                constraints->>'originalScopeComplete'='true' AS scope_complete,
                constraints->>'processingReadTested'='true' AS processing_tested
              FROM marketplace_profile p WHERE p.status='CONFIRMED' AND p.expires_at>clock_timestamp()
                AND p.constraints->>'writeTested'='true'
                AND p.method IN ('OZON_SET_PRICE','YANDEX_SET_PRICE','YANDEX_SET_PROMO','YANDEX_LEAVE_PROMO')
                AND p.revision=(SELECT max(newer.revision) FROM marketplace_profile newer WHERE newer.method=p.method)
            ) accepted) capabilities ON true
            LEFT JOIN LATERAL (SELECT max(revision) AS revision FROM marketplace_commercial_state c
              WHERE c.offer_id=o.id) previous ON true
            LEFT JOIN LATERAL (SELECT min(s.id::text)::uuid AS id,min(s.free_quantity) AS free_quantity
              FROM marketplace_stock_pool s WHERE s.offer_id=o.id AND s.complete
                AND s.valid_until>clock_timestamp() HAVING count(*)=1) stock ON true
            LEFT JOIN LATERAL (SELECT count(*) AS total,
              bool_and(p.complete AND m.processing IS NOT NULL AND p.base_price IS NOT NULL
                AND p.promotion_price IS NOT NULL) AS complete,
              CASE WHEN count(*)<=100 THEN jsonb_agg(jsonb_build_object(
                'promotionId',p.promotion_id,'targetId',o.id,'basePrice',p.base_price::text,
                'promotionPrice',p.promotion_price::text,'processing',COALESCE(m.processing,true),
                'maximumQuantity',NULL,'endsAt',m.ends_at,'exitConfirmedAvailable',
                  p.status='MANUAL' AND COALESCE(capabilities.manual_exit,false) AND NOT m.processing)
                ORDER BY p.promotion_id) END AS entries
              FROM (SELECT * FROM marketplace_promotion_offer source WHERE source.offer_id=o.id
                AND source.status IN ('AUTO','PARTIALLY_AUTO','MANUAL','RENEWED','MINIMUM_FOR_PROMOS')
                AND source.valid_until>clock_timestamp() ORDER BY source.promotion_id LIMIT 101) p
              JOIN marketplace_promotion m ON m.id=p.promotion_id) participations ON true
            LEFT JOIN LATERAL (SELECT count(*) AS total,CASE WHEN count(*)<=100 THEN jsonb_agg(jsonb_build_object(
                'promotionId',p.promotion_id,'targetId',o.id,'type',p.promotion_type,
                'minimumPrice',p.minimum_price::text,'maximumPrice',p.maximum_price::text,'priceStep','1',
                'maximumQuantity',NULL,'endsAt',m.ends_at,'compatiblePromotions','[]'::jsonb,
                'requiredPreservedFields',jsonb_build_array('price'),
                'exitConfirmedAvailable',false)
                ORDER BY p.promotion_id) END AS entries
              FROM (SELECT * FROM marketplace_promotion_offer source WHERE source.offer_id=o.id
                AND source.complete AND source.valid_until>clock_timestamp()
                AND source.minimum_price IS NOT NULL AND source.maximum_price IS NOT NULL
                ORDER BY source.promotion_id LIMIT 101) p
              JOIN marketplace_promotion m ON m.id=p.promotion_id) options ON true
            WHERE NOT o.archived
            """)
        .param("raw", raw)
        .update();
    // A generic commission estimate is not proof of mandatory tariffs or refund outcomes.
    jdbc.sql("UPDATE marketplace_economic_terms SET current=false WHERE current").update();
    jdbc.sql(
            """
            INSERT INTO marketplace_economic_terms(organization_id,account_id,offer_id,placement_id,
              revision,terms,current,raw_file_id)
            SELECT p.organization_id,p.account_id,p.offer_id,p.id,COALESCE(previous.revision,0)+1,
              jsonb_build_object('offerId',p.offer_id,'placementId',p.id,'targetId',p.offer_id,
                'segment',p.model,'revision',p.revision,'validUntil',p.valid_until,'complete',false,
                'sellerRevenueMultiplier',NULL,'sellerRevenueOffset',NULL,
                'buyerPriceMultiplier',NULL,'buyerPriceOffset',NULL,
                'sellerRevenueField',NULL,'buyerPriceField',NULL,
                'charges',jsonb_build_array(jsonb_build_object('code','MANDATORY_TARIFF_UNCONFIRMED',
                  'unit','ORDER_ITEM','fixedAmount',NULL,'revenueRate',NULL,'confirmed',false,
                  'mandatory',true,'verifiedQuantity',NULL)),
                'applicableOutcomes','[]'::jsonb,'outcomes','[]'::jsonb,'participationIds','[]'::jsonb),true,:raw
            FROM marketplace_placement p LEFT JOIN LATERAL
              (SELECT max(revision) AS revision FROM marketplace_economic_terms t WHERE t.placement_id=p.id) previous ON true
            WHERE p.valid_until>clock_timestamp()
            """)
        .param("raw", raw)
        .update();
  }
}
