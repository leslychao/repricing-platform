--liquibase formatted sql

--changeset repricer:marketplace-catalog-evidence-001 splitStatements:true
ALTER TABLE marketplace_offer ADD COLUMN catalog_fields jsonb;
ALTER TABLE marketplace_offer ADD COLUMN supplier_price_data jsonb;
ALTER TABLE marketplace_offer_stage ADD COLUMN catalog_fields jsonb;
ALTER TABLE marketplace_offer_stage ADD COLUMN supplier_price_data jsonb;
ALTER TABLE marketplace_offer_stage ADD COLUMN attributes_complete boolean NOT NULL DEFAULT false;
ALTER TABLE marketplace_offer_observation ADD COLUMN catalog_fields jsonb;
ALTER TABLE marketplace_offer_observation ADD COLUMN supplier_price_data jsonb;
ALTER TABLE marketplace_offer ADD CONSTRAINT marketplace_offer_catalog_size
  CHECK(catalog_fields IS NULL OR octet_length(catalog_fields::text)<=1048576);
ALTER TABLE marketplace_offer ADD CONSTRAINT marketplace_offer_price_size
  CHECK(supplier_price_data IS NULL OR octet_length(supplier_price_data::text)<=1048576);
ALTER TABLE marketplace_offer_stage ADD CONSTRAINT marketplace_offer_stage_catalog_size
  CHECK(catalog_fields IS NULL OR octet_length(catalog_fields::text)<=1048576);
ALTER TABLE marketplace_offer_stage ADD CONSTRAINT marketplace_offer_stage_price_size
  CHECK(supplier_price_data IS NULL OR octet_length(supplier_price_data::text)<=1048576);
COMMENT ON COLUMN marketplace_offer.catalog_fields IS 'Bounded supplier catalog evidence: Ozon attributes or Yandex offer; no inferred dimensions or category ancestry';
COMMENT ON COLUMN marketplace_offer.supplier_price_data IS 'Bounded source price record including supplier indexes and fees; source observations are not complete economic terms';

--changeset repricer:marketplace-catalog-evidence-002 splitStatements:true
ALTER TABLE marketplace_sync_run ADD COLUMN phase_total bigint CHECK(phase_total>=0);
ALTER TABLE marketplace_offer_stage ADD COLUMN catalog_phase text;
ALTER TABLE marketplace_offer_stage ADD COLUMN price_phase text;
COMMENT ON COLUMN marketplace_sync_run.phase_total IS 'Supplier declared total for the current catalog or price traversal; reset at each phase boundary';
COMMENT ON COLUMN marketplace_offer_stage.price_phase IS 'Last price feed phase that actually observed this offer in this run; copied old prices do not satisfy completeness';
