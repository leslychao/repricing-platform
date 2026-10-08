--liquibase formatted sql

--changeset economics:pace-refresh-1
CREATE TABLE economics_pace_refresh (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, id uuid NOT NULL,
  next_page integer NOT NULL DEFAULT 0 CHECK(next_page>=0),
  generation bigint NOT NULL DEFAULT 1, observed_generation bigint NOT NULL DEFAULT 1,
  state text NOT NULL DEFAULT 'RUNNING' CHECK(state IN ('RUNNING','DONE')),
  PRIMARY KEY(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,id)
    REFERENCES platform_job(organization_id,account_id,id)
);
CREATE UNIQUE INDEX economics_pace_refresh_active ON economics_pace_refresh
  (organization_id,account_id) WHERE state='RUNNING';
CREATE INDEX economics_sales_pace_latest ON economics_sales_pace
  (organization_id,account_id,placement_id,created_at DESC,id);
COMMENT ON TABLE economics_pace_refresh IS 'owner=economics.SalesPaceService; grain=one coalesced refresh job per account; business_key=job id; repeat=job request key plus checkpoint; history=completed work retained; aggregation=none; retention=with job';
ALTER TABLE economics_pace_refresh ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_pace_refresh FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_pace_refresh_scope ON economics_pace_refresh USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
GRANT SELECT,INSERT ON economics_pace_refresh TO repricer_api,repricer_worker;
GRANT UPDATE(next_page,generation,observed_generation,state) ON economics_pace_refresh
  TO repricer_api,repricer_worker;

--changeset economics:pace-refresh-metadata-2
COMMENT ON TABLE economics_pace_refresh IS 'owner=economics.SalesPaceService; scope=account; grain=one coalesced refresh job per account; business_key=organization,account,job id; repeat=job request key plus checkpoint; history=completed work retained; aggregation=none; retention=with job';
