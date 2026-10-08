--liquibase formatted sql

--changeset economics:sales-pace-1
CREATE TABLE economics_sales_pace (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, id uuid NOT NULL,
  offer_id uuid NOT NULL, placement_id uuid NOT NULL,
  basis jsonb NOT NULL, snapshot jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,offer_id)
    REFERENCES marketplace_offer(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,placement_id)
    REFERENCES marketplace_placement(organization_id,account_id,id),
  CHECK(pg_column_size(basis)<=65536), CHECK(pg_column_size(snapshot)<=65536)
);
COMMENT ON TABLE economics_sales_pace IS 'owner=economics.AnalyticsEngine; scope=account; grain=one immutable comparable placement observation and local day; business_key=hash of complete canonical inputs and local day; repeat=same basis id; history=immutable; aggregation=not additive across snapshots or overlapping placements; retention=while analytic history is referenced';
ALTER TABLE economics_sales_pace ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_sales_pace FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_sales_pace_scope ON economics_sales_pace USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
