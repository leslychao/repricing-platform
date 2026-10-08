--liquibase formatted sql

--changeset repricer:marketplace-yandex-warehouses
ALTER TABLE marketplace_sync_run ADD COLUMN warehouse_model text
  CHECK(warehouse_model IN ('CAMPAIGN','WAREHOUSE'));
ALTER TABLE marketplace_campaign ADD COLUMN hidden_run uuid;
ALTER TABLE marketplace_campaign ADD CONSTRAINT marketplace_campaign_hidden_scope
  FOREIGN KEY(organization_id,account_id,hidden_run)
  REFERENCES marketplace_sync_run(organization_id,account_id,id);
ALTER TABLE marketplace_placement_stage ADD COLUMN hidden boolean NOT NULL DEFAULT false;

CREATE TABLE marketplace_warehouse (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  external_id text NOT NULL,
  name text NOT NULL,
  warehouse_model text NOT NULL CHECK(warehouse_model IN ('CAMPAIGN','WAREHOUSE')),
  campaign_id text,
  group_id text,
  models jsonb NOT NULL,
  discovered_run uuid NOT NULL,
  stocks_run uuid,
  raw_file_id uuid NOT NULL,
  observed_at timestamptz NOT NULL,
  UNIQUE(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,warehouse_model,external_id),
  CHECK((warehouse_model='CAMPAIGN')=(campaign_id IS NOT NULL)),
  CHECK(warehouse_model='CAMPAIGN' OR group_id IS NULL),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id),
  FOREIGN KEY(organization_id,account_id,campaign_id) REFERENCES marketplace_campaign(organization_id,account_id,external_id),
  FOREIGN KEY(organization_id,account_id,discovered_run) REFERENCES marketplace_sync_run(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,stocks_run) REFERENCES marketplace_sync_run(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,raw_file_id) REFERENCES platform_file(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_warehouse IS 'owner=marketplace; scope=exact account; grain=one supplier warehouse identity and verified grouping; key=account/model/external id; repeat=discovery run; history=latest discovery with retained raw';
ALTER TABLE marketplace_warehouse ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_warehouse FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_warehouse_scope ON marketplace_warehouse USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

--changeset repricer:marketplace-ozon-warehouses
ALTER TABLE marketplace_warehouse DROP CONSTRAINT marketplace_warehouse_warehouse_model_check;
ALTER TABLE marketplace_warehouse ADD CONSTRAINT marketplace_warehouse_warehouse_model_check
  CHECK(warehouse_model IN ('CAMPAIGN','WAREHOUSE','OZON_FBS','OZON_FBO'));
COMMENT ON TABLE marketplace_warehouse IS 'owner=marketplace; scope=exact account; grain=one source-confirmed supplier warehouse identity within its documented model and verified grouping; key=account/model/external id; repeat=discovery run, identical raw identity; history=latest observed identity with retained raw; aggregation=Ozon FBS/FBO namespaces are not merged; retention=while source and stock identities are retained';
