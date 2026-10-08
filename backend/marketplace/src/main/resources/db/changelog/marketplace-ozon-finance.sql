--liquibase formatted sql

--changeset marketplace:ozon-financial-observations-001
CREATE TABLE marketplace_financial_observation (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  run_id uuid NOT NULL,
  kind text NOT NULL CHECK(kind IN ('ACCRUAL','RETURN','ACCRUAL_TYPE')),
  external_id text NOT NULL CHECK(length(external_id) BETWEEN 1 AND 100),
  accounting_date date,
  observed_at timestamptz NOT NULL,
  revision bigint NOT NULL CHECK(revision>0),
  content_hash char(64) NOT NULL,
  normalized jsonb NOT NULL CHECK(octet_length(normalized::text)<=262144),
  facts jsonb NOT NULL CHECK(jsonb_typeof(facts)='array'
    AND jsonb_array_length(facts)<=200 AND octet_length(facts::text)<=262144),
  raw_file_id uuid NOT NULL,
  UNIQUE(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,kind,external_id,revision),
  FOREIGN KEY(organization_id,account_id,run_id)
    REFERENCES marketplace_sync_run(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,raw_file_id)
    REFERENCES platform_file(organization_id,account_id,id),
  CHECK(kind<>'ACCRUAL' OR accounting_date IS NOT NULL)
);
CREATE INDEX marketplace_financial_observation_head ON marketplace_financial_observation
  (organization_id,account_id,kind,external_id,revision DESC);
CREATE INDEX marketplace_financial_observation_period ON marketplace_financial_observation
  (organization_id,account_id,accounting_date,kind);
COMMENT ON TABLE marketplace_financial_observation IS 'owner=marketplace; scope=exact account; grain=one immutable edition of an identified supplier accrual, return or accrual type; key=organization/account/kind/external id/revision; repeat=semantic digest under account lock; history=raw evidence retained; aggregation=one accrual cohort, never completeness of a day or month';
ALTER TABLE marketplace_financial_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_financial_observation FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_financial_observation_scope ON marketplace_financial_observation
  USING(organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true')
  WITH CHECK(organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true');
