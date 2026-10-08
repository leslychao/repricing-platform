--liquibase formatted sql
--changeset marketplace:competitors-1
CREATE TABLE marketplace_competitor_observation (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,revision bigint NOT NULL,
  offer_id uuid NOT NULL,source_id uuid NOT NULL,seller_name varchar(200) NOT NULL,
  segment varchar(1000) NOT NULL,observed_at timestamptz NOT NULL,in_stock boolean,
  price numeric(38,12),delivery numeric(38,12),own_offer boolean NOT NULL,
  revoked boolean NOT NULL,current_revision boolean NOT NULL,author_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,id,revision),
  FOREIGN KEY(organization_id,account_id,offer_id)
    REFERENCES marketplace_offer(organization_id,account_id,id),
  CHECK(price IS NULL OR price>0),CHECK(delivery IS NULL OR delivery>=0)
);
CREATE UNIQUE INDEX marketplace_competitor_current ON marketplace_competitor_observation
  (organization_id,account_id,id) WHERE current_revision;
COMMENT ON TABLE marketplace_competitor_observation IS 'owner=marketplace; scope=account; grain=one declared competitor observation revision; business_key=organization,account,id,revision; repeat=input command not content hash; history=immutable observed time and corrections; aggregation=one latest observation per source and segment; retention=while decisions referenced';

--changeset marketplace:competitors-2
ALTER TABLE marketplace_competitor_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_competitor_observation FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_competitor_observation_scope ON marketplace_competitor_observation USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);


--changeset marketplace:competitors-3
CREATE TABLE marketplace_competitor_import (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,state varchar(16) NOT NULL,
 author_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),applied_at timestamptz,
 PRIMARY KEY(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id),
 CHECK(state IN ('STAGING','PREPARED','APPLIED'))
);
COMMENT ON TABLE marketplace_competitor_import IS 'owner=marketplace; scope=account; grain=one entire manual observation import; business_key=organization,account,importId; repeat=stable input identity; history=whole publish state; aggregation=none; retention=decision evidence';
CREATE TABLE marketplace_competitor_import_row (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,import_id uuid NOT NULL,row_number integer NOT NULL,
 observation_id uuid NOT NULL,offer_id uuid NOT NULL,source_id uuid NOT NULL,seller_name varchar(200) NOT NULL,
 segment varchar(1000) NOT NULL,observed_at timestamptz NOT NULL,in_stock boolean,
 price numeric(38,12),delivery numeric(38,12),own_offer boolean NOT NULL,body_hash char(64) NOT NULL,
 PRIMARY KEY(organization_id,account_id,import_id,row_number),
 UNIQUE(organization_id,account_id,observation_id),
 FOREIGN KEY(organization_id,account_id,import_id) REFERENCES marketplace_competitor_import(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id),
 CHECK(price IS NULL OR price>0),CHECK(delivery IS NULL OR delivery>=0)
);
CREATE INDEX marketplace_competitor_import_hash ON marketplace_competitor_import_row(organization_id,account_id,import_id,body_hash);
COMMENT ON TABLE marketplace_competitor_import_row IS 'owner=marketplace; scope=account; grain=one validated unpublished observation source row; business_key=organization,account,import,rowNumber; repeat=stable id and typed digest; history=immutable observed time; aggregation=none; retention=import evidence';
ALTER TABLE marketplace_competitor_import ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_competitor_import FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_competitor_import_scope ON marketplace_competitor_import USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE marketplace_competitor_import_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_competitor_import_row FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_competitor_import_row_scope ON marketplace_competitor_import_row USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

