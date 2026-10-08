--liquibase formatted sql

--changeset repricer:marketplace-shipments-001 splitStatements:true
CREATE TABLE marketplace_shipment_observation (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  source_run_id uuid NOT NULL,
  model text NOT NULL CHECK(model IN ('OZON_FBS','OZON_FBO')),
  shipment_id text NOT NULL,
  order_id text NOT NULL,
  parent_shipment_id text,
  observed_at timestamptz NOT NULL,
  composition jsonb NOT NULL CHECK(octet_length(composition::text)<=1048576),
  raw_file_id uuid NOT NULL,
  PRIMARY KEY(source_run_id,model,shipment_id),
  FOREIGN KEY(organization_id,account_id,source_run_id) REFERENCES marketplace_sync_run(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,raw_file_id) REFERENCES platform_file(organization_id,account_id,id),
  CHECK(parent_shipment_id IS NULL OR parent_shipment_id<>shipment_id)
);
CREATE INDEX marketplace_shipment_order ON marketplace_shipment_observation(organization_id,account_id,order_id,observed_at DESC);
COMMENT ON TABLE marketplace_shipment_observation IS 'owner=marketplace; scope=exact account; grain=one supplier shipment composition per closed traversal; key=source run/model/shipment; repeat=same immutable source run; history=append-only raw-linked observations; aggregation=never add parents and children or infer original demand';
ALTER TABLE marketplace_shipment_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_shipment_observation FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_shipment_observation_scope ON marketplace_shipment_observation
  USING(organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true')
  WITH CHECK(organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true');
GRANT SELECT,INSERT ON marketplace_shipment_observation TO repricer_api,repricer_worker;
