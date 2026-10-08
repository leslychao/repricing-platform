--liquibase formatted sql
--changeset repricer:marketplace-001
CREATE TABLE marketplace_account (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES access_organization(id),
  marketplace text NOT NULL CHECK(marketplace IN ('OZON','YANDEX')),
  external_id text NOT NULL CHECK(length(external_id) BETWEEN 1 AND 100),
  name text NOT NULL CHECK(length(name) BETWEEN 1 AND 160),
  timezone text NOT NULL CHECK(length(timezone) BETWEEN 1 AND 100),
  status text NOT NULL DEFAULT 'NOT_CONNECTED',
  revision bigint NOT NULL DEFAULT 1,
  capabilities_revision bigint NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(marketplace,external_id),
  UNIQUE(organization_id,id)
);
COMMENT ON TABLE marketplace_account IS 'owner=marketplace; scope=organization with explicit account discovery; grain=permanent vendor seller identity; repeat=global marketplace/external id; history=monotonic revision';
ALTER TABLE marketplace_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_account FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_account_scope ON marketplace_account USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND (id=NULLIF(current_setting('app.account_id',true),'')::uuid OR
      (NULLIF(current_setting('app.account_id',true),'') IS NULL AND
       (EXISTS(SELECT 1 FROM access_membership m WHERE m.organization_id=marketplace_account.organization_id
          AND m.subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid AND m.active AND m.role='OWNER')
        OR EXISTS(SELECT 1 FROM access_account_permission p WHERE p.organization_id=marketplace_account.organization_id
          AND p.account_id=marketplace_account.id AND p.subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid AND p.active)))))
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND (id=NULLIF(current_setting('app.account_id',true),'')::uuid OR
      (NULLIF(current_setting('app.account_id',true),'') IS NULL AND
       (EXISTS(SELECT 1 FROM access_membership m WHERE m.organization_id=marketplace_account.organization_id
          AND m.subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid AND m.active AND m.role='OWNER')
        OR EXISTS(SELECT 1 FROM access_account_permission p WHERE p.organization_id=marketplace_account.organization_id
          AND p.account_id=marketplace_account.id AND p.subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid AND p.active)))));

CREATE TABLE marketplace_connection (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  secret_path text NOT NULL,
  secret_version bigint NOT NULL,
  client_id text,
  quota_credential_hash char(64) NOT NULL,
  state text NOT NULL CHECK(state IN ('UNCHECKED','ACTIVE','INVALID','DISABLED')),
  read_only boolean NOT NULL DEFAULT true,
  revision bigint NOT NULL DEFAULT 1,
  credential_generation bigint NOT NULL DEFAULT 1,
  checked_at timestamptz,
  UNIQUE(organization_id,account_id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE marketplace_connection IS 'owner=marketplace; scope=exact account; grain=current credential reference, never key material; repeat=account CAS; history=Vault versions and audit';
ALTER TABLE marketplace_connection ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_connection FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_connection_scope ON marketplace_connection USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid)
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid);

CREATE TABLE marketplace_connection_candidate (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  secret_path text NOT NULL,
  client_id text,
  quota_credential_hash char(64) NOT NULL,
  read_only boolean NOT NULL,
  expected_revision bigint NOT NULL CHECK(expected_revision>=0),
  state text NOT NULL CHECK(state IN ('PENDING','APPLIED','INVALID','CANCELLED')),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
CREATE UNIQUE INDEX marketplace_connection_candidate_pending
  ON marketplace_connection_candidate(organization_id,account_id) WHERE state='PENDING';
COMMENT ON TABLE marketplace_connection_candidate IS 'owner=marketplace; scope=exact account; grain=one credential verification; repeat=idempotency request; history=terminal verification result without secret';
ALTER TABLE marketplace_connection_candidate ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_connection_candidate FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_connection_candidate_scope ON marketplace_connection_candidate
USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true')
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true');

CREATE TABLE marketplace_offer (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  external_id text NOT NULL,
  sku text NOT NULL,
  vendor_sku text,
  name text NOT NULL,
  image_url text,
  seller_price numeric(38,12),
  buyer_price numeric(38,12),
  currency text NOT NULL DEFAULT 'RUB',
  archived boolean NOT NULL DEFAULT false,
  info_complete boolean NOT NULL DEFAULT false,
  commercial_fields jsonb CHECK(octet_length(commercial_fields::text)<=1048576),
  revision bigint NOT NULL DEFAULT 1,
  observed_at timestamptz NOT NULL,
  publication_id uuid NOT NULL,
  category_path uuid[] CHECK(cardinality(category_path)<=32),
  UNIQUE(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,external_id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE marketplace_offer IS 'owner=marketplace; scope=exact account; grain=one vendor offer; repeat=external id; history=publication and immutable raw evidence';
ALTER TABLE marketplace_offer ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_offer FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_offer_scope ON marketplace_offer USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid)
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid);

CREATE TABLE marketplace_source (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  source_type text NOT NULL,
  status text NOT NULL DEFAULT 'MISSING',
  last_success_at timestamptz,
  publication_id uuid,
  revision bigint NOT NULL DEFAULT 0,
  reason text,
  UNIQUE(organization_id,account_id,source_type),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE marketplace_source IS 'owner=marketplace; scope=exact account; grain=one source publication cursor; repeat=source; history=sync publications';
ALTER TABLE marketplace_source ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_source FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_source_scope ON marketplace_source USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid)
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid);

CREATE TABLE marketplace_profile (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  method text NOT NULL,
  revision bigint NOT NULL,
  status text NOT NULL CHECK(status IN ('UNCONFIRMED','CONFIRMED','REVOKED')),
  confirmed_at timestamptz,
  expires_at timestamptz,
  contract_uri text NOT NULL,
  evidence_file_id uuid REFERENCES platform_file(id),
  constraints jsonb NOT NULL DEFAULT '{}',
  reason text NOT NULL,
  UNIQUE(organization_id,account_id,method,revision),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE marketplace_profile IS 'owner=marketplace; scope=exact account; grain=immutable tested method capability revision; repeat=method+revision; history=append only';
ALTER TABLE marketplace_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_profile FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_profile_scope ON marketplace_profile USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid)
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid);

CREATE TABLE marketplace_sync_run (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  job_id uuid REFERENCES platform_job(id),
  source_type text NOT NULL,
  state text NOT NULL CHECK(state IN ('FETCHING','PARSING','VALIDATING','PUBLISHED','INCOMPLETE','FAILED')),
  page_number integer NOT NULL DEFAULT 0,
  next_cursor text,
  phase text NOT NULL DEFAULT 'CATALOG',
  batch_after text,
  range_from date,
  range_until date,
  range_cursor date,
  total_count bigint,
  parsed_count bigint NOT NULL DEFAULT 0,
  started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  reason text,
  revision bigint NOT NULL DEFAULT 1,
  UNIQUE(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE marketplace_sync_run IS 'owner=marketplace; scope=exact account; grain=one bounded source traversal and publication; repeat=job; history=retained result';
ALTER TABLE marketplace_sync_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_sync_run FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_sync_run_scope ON marketplace_sync_run USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid)
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid);

CREATE TABLE marketplace_raw_page (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  run_id uuid NOT NULL,
  page_number integer NOT NULL,
  file_id uuid NOT NULL REFERENCES platform_file(id),
  cursor text,
  next_cursor text,
  item_count integer,
  method text,
  content_encoding text NOT NULL DEFAULT 'identity',
  parsed boolean NOT NULL DEFAULT false,
  PRIMARY KEY(run_id,page_number),
  FOREIGN KEY(organization_id,account_id,run_id) REFERENCES marketplace_sync_run(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_raw_page IS 'owner=marketplace; scope=exact account; grain=exact raw page version; repeat=run+page; history=immutable source evidence';
ALTER TABLE marketplace_raw_page ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_raw_page FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_raw_page_scope ON marketplace_raw_page USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid)
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid);

CREATE TABLE marketplace_offer_stage (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  run_id uuid NOT NULL,
  external_id text NOT NULL,
  sku text NOT NULL,
  vendor_sku text,
  name text NOT NULL,
  image_url text,
  seller_price numeric(38,12),
  buyer_price numeric(38,12),
  archived boolean NOT NULL DEFAULT false,
  info_complete boolean NOT NULL DEFAULT false,
  commercial_fields jsonb CHECK(octet_length(commercial_fields::text)<=1048576),
  PRIMARY KEY(run_id,external_id),
  FOREIGN KEY(organization_id,account_id,run_id) REFERENCES marketplace_sync_run(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_offer_stage IS 'owner=marketplace; scope=exact account; grain=one validated candidate offer; repeat=run+external id; history=until publication';
ALTER TABLE marketplace_offer_stage ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_offer_stage FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_offer_stage_scope ON marketplace_offer_stage USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid)
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid);

CREATE TABLE marketplace_stock_pool (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  external_id text NOT NULL,
  offer_id uuid NOT NULL,
  name text NOT NULL,
  warehouse_type text NOT NULL,
  free_quantity numeric(38,12),
  obligations_covered numeric(38,12),
  revision bigint NOT NULL DEFAULT 1,
  observed_at timestamptz NOT NULL,
  valid_until timestamptz NOT NULL,
  complete boolean NOT NULL DEFAULT false,
  UNIQUE(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,external_id),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_stock_pool IS 'owner=marketplace; scope=exact account; grain=one non-overlapping stock pool; repeat=external stock identity; history=observed revision; unknown is null';
ALTER TABLE marketplace_stock_pool ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_stock_pool FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_stock_pool_scope ON marketplace_stock_pool USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid)
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid);

CREATE TABLE marketplace_external_call (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  method text NOT NULL,
  semantic_kind text NOT NULL CHECK(semantic_kind IN ('READ','WRITE')),
  admitted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  status_code integer,
  raw_file_id uuid REFERENCES platform_file(id),
  request_digest char(64),
  connection_revision bigint NOT NULL,
  outcome text NOT NULL CHECK(outcome IN ('ADMITTED','RESPONSE','UNKNOWN','REJECTED')),
  transport_started_at timestamptz,
  content_encoding text NOT NULL DEFAULT 'identity',
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE marketplace_external_call IS 'owner=marketplace; scope=exact account; grain=one transport attempt; repeat=never resend writes; history=immutable attempt evidence';
ALTER TABLE marketplace_external_call ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_external_call FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_external_call_scope ON marketplace_external_call USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid)
WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid);

ALTER TABLE access_account_permission ADD CONSTRAINT access_permission_account_fk
FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id);
CREATE INDEX marketplace_offer_search ON marketplace_offer(organization_id,account_id,sku,id);

--changeset repricer:marketplace-002
CREATE TABLE marketplace_economic_terms (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  offer_id uuid NOT NULL,
  placement_id uuid NOT NULL,
  revision bigint NOT NULL,
  terms jsonb NOT NULL CHECK(octet_length(terms::text)<=65536),
  current boolean NOT NULL,
  raw_file_id uuid NOT NULL REFERENCES platform_file(id),
  PRIMARY KEY(organization_id,account_id,placement_id,revision),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_economic_terms IS 'owner=marketplace; scope=exact account; grain=immutable source snapshot; key=target,revision; repeat=same raw publication; history=append only content with current projection';
ALTER TABLE marketplace_economic_terms ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_economic_terms FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_economic_terms_scope ON marketplace_economic_terms USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');
CREATE TABLE marketplace_commercial_state (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  offer_id uuid NOT NULL,
  revision bigint NOT NULL,
  snapshot jsonb NOT NULL CHECK(octet_length(snapshot::text)<=65536),
  current boolean NOT NULL,
  raw_file_id uuid NOT NULL REFERENCES platform_file(id),
  PRIMARY KEY(organization_id,account_id,offer_id,revision),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_commercial_state IS 'owner=marketplace; scope=exact account; grain=immutable source snapshot; key=target,revision; repeat=same raw publication; history=append only content with current projection';
ALTER TABLE marketplace_commercial_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_commercial_state FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_commercial_state_scope ON marketplace_commercial_state USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');
CREATE UNIQUE INDEX marketplace_terms_current ON marketplace_economic_terms(organization_id,account_id,placement_id) WHERE current;
CREATE UNIQUE INDEX marketplace_commercial_current ON marketplace_commercial_state(organization_id,account_id,offer_id) WHERE current;

CREATE TABLE marketplace_promotion (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  external_id text NOT NULL,
  name text NOT NULL,
  promotion_type text NOT NULL,
  starts_at timestamptz,
  ends_at timestamptz,
  processing boolean,
  constraints jsonb NOT NULL,
  revision bigint NOT NULL DEFAULT 1,
  publication_id uuid NOT NULL,
  observed_at timestamptz NOT NULL,
  valid_until timestamptz NOT NULL,
  UNIQUE(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,external_id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
CREATE TABLE marketplace_promotion_offer (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  promotion_id uuid NOT NULL,
  offer_id uuid NOT NULL,
  external_id text NOT NULL,
  promotion_type text NOT NULL,
  status text NOT NULL,
  base_price numeric(38,12),
  promotion_price numeric(38,12),
  minimum_price numeric(38,12),
  maximum_price numeric(38,12),
  participation_scope jsonb NOT NULL,
  complete boolean NOT NULL,
  revision bigint NOT NULL DEFAULT 1,
  publication_id uuid NOT NULL,
  valid_until timestamptz NOT NULL,
  PRIMARY KEY(organization_id,account_id,promotion_id,offer_id),
  FOREIGN KEY(organization_id,account_id,promotion_id) REFERENCES marketplace_promotion(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
CREATE TABLE marketplace_promotion_stage (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  run_id uuid NOT NULL,
  external_id text NOT NULL,
  sku text NOT NULL,
  data jsonb NOT NULL CHECK(octet_length(data::text)<=1048576),
  PRIMARY KEY(run_id,external_id,sku),
  FOREIGN KEY(organization_id,account_id,run_id) REFERENCES marketplace_sync_run(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_promotion IS 'owner=marketplace; scope=exact account; grain=one observed promotion; repeat=vendor promotion identity; history=raw publication';
COMMENT ON TABLE marketplace_promotion_offer IS 'owner=marketplace; scope=exact account; grain=one offer in one promotion including eligibility; repeat=promotion+offer; history=raw publication';
COMMENT ON TABLE marketplace_promotion_stage IS 'owner=marketplace; scope=exact account; grain=bounded candidate promotion row; repeat=run+promotion+SKU; history=staging until validated publication';
ALTER TABLE marketplace_promotion ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_promotion FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_promotion_offer ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_promotion_offer FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_promotion_stage ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_promotion_stage FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_promotion_scope ON marketplace_promotion USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');
CREATE POLICY marketplace_promotion_offer_scope ON marketplace_promotion_offer USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');
CREATE POLICY marketplace_promotion_stage_scope ON marketplace_promotion_stage USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');

--changeset repricer:marketplace-stock-traversal
CREATE TABLE marketplace_campaign (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  external_id text NOT NULL,
  placement_type text NOT NULL,
  availability text NOT NULL,
  discovered_run uuid NOT NULL,
  stocks_run uuid,
  placements_run uuid,
  PRIMARY KEY(organization_id,account_id,external_id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE marketplace_campaign IS 'owner=marketplace; scope=exact business account; grain=one verified vendor campaign; key=account+campaign; repeat=discovery run; history=latest complete discovery';
ALTER TABLE marketplace_campaign ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_campaign FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_campaign_scope ON marketplace_campaign USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true');
CREATE TABLE marketplace_stock_stage (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  run_id uuid NOT NULL,
  external_id text NOT NULL,
  offer_id uuid NOT NULL,
  name text NOT NULL,
  warehouse_type text NOT NULL,
  free_quantity numeric(38,12),
  obligations_covered numeric(38,12),
  observed_at timestamptz NOT NULL,
  complete boolean NOT NULL,
  PRIMARY KEY(run_id,external_id),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_stock_stage IS 'owner=marketplace; scope=exact account; grain=one non-overlapping candidate stock pool; key=run+physical pool; repeat=stable pool values; history=until checked publication';
ALTER TABLE marketplace_stock_stage ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_stock_stage FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_stock_stage_scope ON marketplace_stock_stage USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true');

--changeset repricer:marketplace-quota-runtime
CREATE TABLE marketplace_quota_bucket (
  subject_hash char(64) NOT NULL,
  method_group text NOT NULL,
  next_allowed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  blocked_until timestamptz,
  PRIMARY KEY(subject_hash,method_group)
);
COMMENT ON TABLE marketplace_quota_bucket IS 'owner=marketplace; scope=private admission metadata; grain=one real supplier quota subject+method group; key=opaque SHA256+group; repeat=shared across tenants and workers; contains no tenant payload or credentials';
REVOKE ALL ON marketplace_quota_bucket FROM PUBLIC;

--changeset repricer:marketplace-quota-functions splitStatements:false
CREATE FUNCTION repricer_reserve_quota(p_subjects text[],p_group text,p_spacing_ms integer)
RETURNS timestamptz LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE subject text; next_time timestamptz; now_time timestamptz:=clock_timestamp();
BEGIN
  IF cardinality(p_subjects) NOT BETWEEN 1 AND 3 OR p_spacing_ms NOT BETWEEN 1 AND 3600000
    OR length(p_group)>100 THEN RAISE EXCEPTION 'invalid quota admission'; END IF;
  FOR subject IN SELECT DISTINCT unnest(p_subjects) ORDER BY 1 LOOP
    IF subject!~'^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'invalid quota subject'; END IF;
    INSERT INTO public.marketplace_quota_bucket(subject_hash,method_group,next_allowed_at)
      VALUES(subject,p_group,now_time) ON CONFLICT DO NOTHING;
    PERFORM 1 FROM public.marketplace_quota_bucket
      WHERE subject_hash=subject AND method_group=p_group FOR UPDATE;
  END LOOP;
  SELECT max(GREATEST(next_allowed_at,blocked_until)) INTO next_time
    FROM public.marketplace_quota_bucket WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  IF next_time>now_time THEN RETURN next_time; END IF;
  UPDATE public.marketplace_quota_bucket SET next_allowed_at=now_time+p_spacing_ms*interval '1 millisecond'
    WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION repricer_reserve_quota(text[],text,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_reserve_quota(text[],text,integer) TO repricer_api,repricer_worker;

--changeset repricer:marketplace-quota-pause splitStatements:false
CREATE FUNCTION repricer_pause_quota(p_subjects text[],p_group text,p_until timestamptz)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public SET row_security=off AS $$
BEGIN
  IF cardinality(p_subjects) NOT BETWEEN 1 AND 3 OR length(p_group)>100
    OR p_until>clock_timestamp()+interval '7 days' THEN RAISE EXCEPTION 'invalid quota pause'; END IF;
  UPDATE public.marketplace_quota_bucket SET blocked_until=GREATEST(blocked_until,p_until)
    WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
END;
$$;
REVOKE ALL ON FUNCTION repricer_pause_quota(text[],text,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_pause_quota(text[],text,timestamptz) TO repricer_api,repricer_worker;

--changeset repricer:marketplace-placements splitStatements:true
CREATE TABLE marketplace_placement (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  offer_id uuid NOT NULL,
  external_id text NOT NULL,
  model text NOT NULL,
  status text NOT NULL,
  available boolean NOT NULL,
  fields jsonb NOT NULL CHECK(octet_length(fields::text)<=1048576),
  publication_id uuid NOT NULL,
  revision bigint NOT NULL DEFAULT 1,
  observed_at timestamptz NOT NULL,
  valid_until timestamptz NOT NULL,
  UNIQUE(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,offer_id,external_id),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
CREATE TABLE marketplace_placement_stage (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  run_id uuid NOT NULL,
  campaign_id text NOT NULL,
  offer_id uuid NOT NULL,
  status text NOT NULL,
  available boolean NOT NULL,
  fields jsonb NOT NULL CHECK(octet_length(fields::text)<=1048576),
  PRIMARY KEY(run_id,campaign_id,offer_id),
  FOREIGN KEY(organization_id,account_id,run_id) REFERENCES marketplace_sync_run(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_placement IS 'owner=marketplace; scope=exact account; grain=vendor placement of one Offer independent from a stock pool; repeat=offer+external placement; history=raw publication';
COMMENT ON TABLE marketplace_placement_stage IS 'owner=marketplace; scope=exact account; grain=one candidate placement; repeat=run+campaign+offer; history=until complete publication';
ALTER TABLE marketplace_placement ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_placement FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_placement_stage ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_placement_stage FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_placement_scope ON marketplace_placement USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');
CREATE POLICY marketplace_placement_stage_scope ON marketplace_placement_stage USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');

--changeset repricer:marketplace-history splitStatements:true
CREATE TABLE marketplace_payment (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  external_id text NOT NULL,
  occurred_at timestamptz NOT NULL,
  amount numeric(38,12) NOT NULL,
  currency text NOT NULL,
  payment_scope text NOT NULL,
  source_revision text NOT NULL,
  content_hash char(64) NOT NULL,
  raw_file_id uuid NOT NULL REFERENCES platform_file(id),
  revision bigint NOT NULL DEFAULT 1,
  UNIQUE(organization_id,account_id,external_id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
CREATE TABLE marketplace_return (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  external_id text NOT NULL,
  offer_id uuid NOT NULL,
  line_id text NOT NULL,
  returned_quantity numeric(38,12) NOT NULL CHECK(returned_quantity>0),
  returned_at timestamptz NOT NULL,
  refund_confirmed boolean,
  refunded_amount numeric(38,12),
  physical_receipt_confirmed boolean,
  resalable boolean,
  stock_confirmed boolean,
  source_revision text NOT NULL,
  content_hash char(64) NOT NULL,
  raw_file_id uuid NOT NULL REFERENCES platform_file(id),
  revision bigint NOT NULL DEFAULT 1,
  UNIQUE(organization_id,account_id,external_id),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
CREATE TABLE marketplace_order_item (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  order_id text NOT NULL,
  line_id text NOT NULL,
  campaign_id text,
  offer_id uuid NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  current_quantity numeric(38,12) NOT NULL CHECK(current_quantity>=0),
  original_quantity numeric(38,12) CHECK(original_quantity>0),
  original_composition_confirmed boolean NOT NULL DEFAULT false,
  source_revision text NOT NULL,
  content_hash char(64) NOT NULL,
  raw_file_id uuid NOT NULL REFERENCES platform_file(id),
  revision bigint NOT NULL DEFAULT 1,
  UNIQUE(organization_id,account_id,order_id,line_id),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
CREATE TABLE marketplace_history_day (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  placement_id uuid NOT NULL,
  day date NOT NULL,
  ordered_units numeric(38,12) NOT NULL CHECK(ordered_units>=0),
  orders_complete boolean NOT NULL DEFAULT false,
  stock_continuous boolean NOT NULL DEFAULT false,
  regime text NOT NULL,
  sources_valid_until timestamptz,
  known_future_change timestamptz,
  raw_file_id uuid NOT NULL REFERENCES platform_file(id),
  PRIMARY KEY(organization_id,account_id,placement_id,day),
  FOREIGN KEY(organization_id,account_id,placement_id) REFERENCES marketplace_placement(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_payment IS 'owner=marketplace; scope=exact account; grain=one identified supplier payment; repeat=external payment id plus proven source revision; history=raw evidence; aggregation=settlements only never revenue';
COMMENT ON TABLE marketplace_return IS 'owner=marketplace; scope=exact account; grain=one identified return line; repeat=external return identity plus source revision; history=raw evidence; aggregation=physical and monetary proof remain separate';
COMMENT ON TABLE marketplace_order_item IS 'owner=marketplace; scope=exact account; grain=original order line independent from child shipment; repeat=order and line id plus source revision; history=raw evidence; aggregation=demand only from confirmed original composition';
COMMENT ON TABLE marketplace_history_day IS 'owner=marketplace; scope=exact account; grain=one complete source day per placement; repeat=day and placement; history=raw evidence; aggregation=original demand, no stock continuity inferred from snapshots';
ALTER TABLE marketplace_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_payment FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_return ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_return FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_order_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_order_item FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_history_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_history_day FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_payment_scope ON marketplace_payment USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');
CREATE POLICY marketplace_return_scope ON marketplace_return USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');
CREATE POLICY marketplace_order_item_scope ON marketplace_order_item USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');
CREATE POLICY marketplace_history_day_scope ON marketplace_history_day USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');

CREATE TABLE marketplace_history_stage (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  run_id uuid NOT NULL,
  source_kind text NOT NULL,
  external_id text NOT NULL,
  campaign_id text,
  data jsonb NOT NULL CHECK(octet_length(data::text)<=1048576),
  raw_file_id uuid NOT NULL REFERENCES platform_file(id),
  PRIMARY KEY(run_id,source_kind,external_id),
  FOREIGN KEY(organization_id,account_id,run_id) REFERENCES marketplace_sync_run(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_history_stage IS 'owner=marketplace; scope=exact account; grain=one bounded original supplier history record; repeat=run+method+source identity; history=staging until complete canonical publication';
ALTER TABLE marketplace_history_stage ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_history_stage FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_history_stage_scope ON marketplace_history_stage USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');

--changeset repricer:marketplace-reports-001 splitStatements:true
CREATE TABLE marketplace_report (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  job_id uuid,
  kind text NOT NULL CHECK(kind IN ('PAYMENTS','RETURNS','SERVICES')),
  date_from date NOT NULL,
  date_until date NOT NULL CHECK(date_until>=date_from AND date_until<date_from+31),
  state text NOT NULL CHECK(state IN ('QUEUED','GENERATING','WAITING','STORED','PUBLISHED','UNKNOWN','BLOCKED')),
  generation_call_id uuid NOT NULL UNIQUE,
  supplier_report_id text,
  raw_file_id uuid REFERENCES platform_file(id),
  generation_finished_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  revision bigint NOT NULL DEFAULT 1,
  reason text,
  UNIQUE(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
CREATE UNIQUE INDEX marketplace_one_active_report ON marketplace_report(account_id)
  WHERE state IN ('GENERATING','WAITING','UNKNOWN');
CREATE TABLE marketplace_report_row (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  report_id uuid NOT NULL,
  sheet text NOT NULL,
  row_number bigint NOT NULL CHECK(row_number BETWEEN 1 AND 1000000),
  data jsonb NOT NULL CHECK(octet_length(data::text)<=1048576),
  content_hash char(64) NOT NULL,
  PRIMARY KEY(report_id,sheet,row_number),
  FOREIGN KEY(organization_id,account_id,report_id) REFERENCES marketplace_report(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_report IS 'owner=marketplace; scope=exact account; grain=one requested supplier report for a period; repeat=client request then fixed generation call and supplier report id; history=raw working file; ambiguous generation retains active quota slot';
COMMENT ON TABLE marketplace_report_row IS 'owner=marketplace; scope=exact account; grain=one bounded row in one report sheet; repeat=report sheet row plus content hash; history=immutable supplier evidence, never a second business event';
ALTER TABLE marketplace_report ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_report FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_report_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_report_row FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_report_scope ON marketplace_report USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');
CREATE POLICY marketplace_report_row_scope ON marketplace_report_row USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');

--changeset repricer:marketplace-observations-001 splitStatements:true
CREATE TABLE marketplace_offer_observation (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  offer_id uuid NOT NULL,
  source_run_id uuid NOT NULL,
  observed_at timestamptz NOT NULL,
  seller_price numeric(38,12),
  buyer_price numeric(38,12),
  archived boolean NOT NULL,
  commercial_fields jsonb CHECK(octet_length(commercial_fields::text)<=1048576),
  semantic_revision bigint NOT NULL,
  PRIMARY KEY(offer_id,source_run_id),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,source_run_id) REFERENCES marketplace_sync_run(organization_id,account_id,id)
);
CREATE TABLE marketplace_stock_observation (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  pool_id uuid NOT NULL,
  source_run_id uuid NOT NULL,
  observed_at timestamptz NOT NULL,
  free_quantity numeric(38,12),
  obligations_covered numeric(38,12),
  complete boolean NOT NULL,
  semantic_revision bigint NOT NULL,
  PRIMARY KEY(pool_id,source_run_id),
  FOREIGN KEY(organization_id,account_id,pool_id) REFERENCES marketplace_stock_pool(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,source_run_id) REFERENCES marketplace_sync_run(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_offer_observation IS 'owner=marketplace; scope=exact account; grain=offer observed during one complete source traversal; repeat=offer+run; history=append only including equal values in distinct observations; semantics=revision changes only when meaningful values change';
COMMENT ON TABLE marketplace_stock_observation IS 'owner=marketplace; scope=exact account; grain=physical stock pool observed during one complete traversal; repeat=pool+run; history=append only including 10 to 7 to 10; raw evidence=source run pages; no inferred arrivals';
ALTER TABLE marketplace_offer_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_offer_observation FORCE ROW LEVEL SECURITY;
ALTER TABLE marketplace_stock_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_stock_observation FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_offer_observation_scope ON marketplace_offer_observation USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');
CREATE POLICY marketplace_stock_observation_scope ON marketplace_stock_observation USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');

--changeset repricer:marketplace-financial-publication-001 splitStatements:true
ALTER TABLE marketplace_report ADD COLUMN row_count bigint NOT NULL DEFAULT 0 CHECK(row_count>=0);
ALTER TABLE marketplace_report ADD COLUMN financial_complete boolean NOT NULL DEFAULT false;
ALTER TABLE marketplace_report_row ADD COLUMN ordinal bigint CHECK(ordinal>0);
ALTER TABLE marketplace_report_row ADD COLUMN canonical_fact jsonb CHECK(octet_length(canonical_fact::text)<=65536);
CREATE UNIQUE INDEX marketplace_report_row_ordinal ON marketplace_report_row(report_id,ordinal);
CREATE INDEX marketplace_report_publication_period ON marketplace_report(account_id,kind,date_from,date_until,generation_finished_at DESC) WHERE state='PUBLISHED';
COMMENT ON COLUMN marketplace_report_row.canonical_fact IS 'owner=marketplace; immutable normalized service row in a whole monthly publication; ordinal scoped to publication; consumers replace period cohort, never sum editions';

--changeset repricer:marketplace-quota-fairness-001 splitStatements:true
ALTER TABLE marketplace_quota_bucket ADD COLUMN non_sync_grants integer NOT NULL DEFAULT 0 CHECK(non_sync_grants BETWEEN 0 AND 9);
CREATE TABLE marketplace_quota_waiter (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, method_group text NOT NULL,
  purpose text NOT NULL CHECK(purpose IN ('SYNC','RECONCILE','WRITE','CHECK')),
  subjects text[] NOT NULL, expires_at timestamptz NOT NULL,
  resource_group text NOT NULL,request_cost integer NOT NULL,
  PRIMARY KEY(organization_id,account_id,method_group,purpose)
);
CREATE TABLE marketplace_quota_turn (
  subject_hash char(64) NOT NULL, method_group text NOT NULL, organization_id uuid NOT NULL,
  account_id uuid NOT NULL, last_grant_at timestamptz NOT NULL,
  PRIMARY KEY(subject_hash,method_group,organization_id,account_id)
);
CREATE TABLE marketplace_quota_grant (
  id uuid PRIMARY KEY, subjects text[] NOT NULL, method_group text NOT NULL,
  created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, released_at timestamptz
);
CREATE INDEX marketplace_quota_grant_active ON marketplace_quota_grant(method_group,expires_at) WHERE released_at IS NULL;
CREATE TABLE marketplace_quota_resource (
  subject_hash char(64) NOT NULL,resource_group text NOT NULL,
  window_until timestamptz NOT NULL,confirmed_limit bigint NOT NULL CHECK(confirmed_limit>0),
  remaining bigint NOT NULL CHECK(remaining>=0),
  PRIMARY KEY(subject_hash,resource_group)
);
CREATE TABLE marketplace_quota_report (
  id uuid PRIMARY KEY,subjects text[] NOT NULL,created_at timestamptz NOT NULL,
  finished_at timestamptz
);
REVOKE ALL ON marketplace_quota_resource,marketplace_quota_report FROM PUBLIC,repricer_api,repricer_worker;
REVOKE ALL ON marketplace_quota_waiter,marketplace_quota_turn,marketplace_quota_grant FROM PUBLIC,repricer_api,repricer_worker;
COMMENT ON TABLE marketplace_quota_waiter IS 'owner=marketplace.QuotaManager; closed scheduling metadata; one pending contender per organization/account/method purpose; no tenant payload; bounded expiry prevents abandoned requests holding admission';
COMMENT ON TABLE marketplace_quota_turn IS 'owner=marketplace.QuotaManager; closed fairness metadata for the actual shared external subject; organization first then account';
COMMENT ON TABLE marketplace_quota_grant IS 'owner=marketplace.QuotaManager; one technical admission identity; расход and concurrency are separate; abandoned HTTP holds a conservative 180 second lease';
DROP FUNCTION repricer_reserve_quota(text[],text,integer);

--changeset repricer:marketplace-quota-fairness-function-001 splitStatements:false
CREATE FUNCTION repricer_reserve_quota(p_id uuid,p_organization uuid,p_account uuid,p_purpose text,
  p_subjects text[],p_group text,p_spacing_ms integer,p_resource text,p_cost integer)
RETURNS timestamptz LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE subject text; next_time timestamptz; now_time timestamptz:=clock_timestamp();
  chosen record; force_sync boolean;
BEGIN
  IF p_id IS NULL OR p_organization IS NULL OR p_account IS NULL
    OR p_purpose NOT IN ('SYNC','RECONCILE','WRITE','CHECK')
    OR cardinality(p_subjects) NOT BETWEEN 1 AND 4 OR p_spacing_ms NOT BETWEEN 1 AND 3600000
    OR length(p_group)>100 OR length(p_resource)>100 OR p_cost NOT BETWEEN 1 AND 1000
    THEN RAISE EXCEPTION 'invalid quota admission'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('marketplace-quota:'||p_group,0));
  IF EXISTS(SELECT 1 FROM public.marketplace_quota_grant WHERE id=p_id) THEN
    IF EXISTS(SELECT 1 FROM public.marketplace_quota_grant WHERE id=p_id
      AND method_group=p_group AND subjects=p_subjects AND released_at IS NULL AND expires_at>now_time)
      THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'expired or conflicting admission identity';
  END IF;
  DELETE FROM public.marketplace_quota_waiter WHERE method_group=p_group AND expires_at<=now_time;
  DELETE FROM public.marketplace_quota_grant WHERE method_group=p_group AND expires_at<now_time-interval '7 days';
  FOR subject IN SELECT DISTINCT unnest(p_subjects) ORDER BY 1 LOOP
    IF subject!~'^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'invalid quota subject'; END IF;
    INSERT INTO public.marketplace_quota_bucket(subject_hash,method_group,next_allowed_at)
      VALUES(subject,p_group,now_time) ON CONFLICT DO NOTHING;
    PERFORM 1 FROM public.marketplace_quota_bucket
      WHERE subject_hash=subject AND method_group=p_group FOR UPDATE;
  END LOOP;
  SELECT max(GREATEST(next_allowed_at,blocked_until)) INTO next_time
    FROM public.marketplace_quota_bucket WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  SELECT GREATEST(next_time,LEAST(max(expires_at),now_time+interval '1 second')) INTO next_time FROM public.marketplace_quota_grant
    WHERE method_group=p_group AND subjects&&p_subjects AND released_at IS NULL AND expires_at>now_time;
  SELECT GREATEST(next_time,max(window_until)) INTO next_time FROM public.marketplace_quota_resource
    WHERE subject_hash=ANY(p_subjects) AND resource_group=p_resource AND window_until>now_time
      AND remaining-CEIL(confirmed_limit*0.1)<p_cost;
  IF p_group LIKE '%:report-generation' AND EXISTS(SELECT 1 FROM public.marketplace_quota_report
    WHERE subjects&&p_subjects AND finished_at IS NULL)
    THEN next_time:=GREATEST(next_time,now_time+interval '30 seconds'); END IF;
  INSERT INTO public.marketplace_quota_waiter(organization_id,account_id,method_group,purpose,subjects,expires_at,resource_group,request_cost)
    VALUES(p_organization,p_account,p_group,p_purpose,p_subjects,GREATEST(now_time,next_time)+interval '30 seconds',p_resource,p_cost)
    ON CONFLICT(organization_id,account_id,method_group,purpose) DO UPDATE SET
      subjects=EXCLUDED.subjects,expires_at=EXCLUDED.expires_at,
      resource_group=EXCLUDED.resource_group,request_cost=EXCLUDED.request_cost;
  IF next_time>now_time THEN RETURN next_time; END IF;
  SELECT COALESCE(max(non_sync_grants),0)>=9 INTO force_sync FROM public.marketplace_quota_bucket
    WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  SELECT w.organization_id,w.account_id,w.purpose INTO chosen
    FROM public.marketplace_quota_waiter w
    WHERE w.method_group=p_group AND w.subjects&&p_subjects AND w.expires_at>now_time
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_bucket b
        WHERE b.method_group=p_group AND b.subject_hash=ANY(w.subjects)
          AND GREATEST(b.next_allowed_at,b.blocked_until)>now_time)
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_grant g
        WHERE g.method_group=p_group AND g.subjects&&w.subjects
          AND g.released_at IS NULL AND g.expires_at>now_time)
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_resource r
        WHERE r.subject_hash=ANY(w.subjects) AND r.resource_group=w.resource_group
          AND r.window_until>now_time AND r.remaining-CEIL(r.confirmed_limit*0.1)<w.request_cost)
    ORDER BY CASE WHEN force_sync AND w.purpose='SYNC' THEN 0 ELSE 1 END,
      COALESCE((SELECT max(t.last_grant_at) FROM public.marketplace_quota_turn t
        WHERE t.method_group=p_group AND t.subject_hash=ANY(p_subjects)
          AND t.organization_id=w.organization_id),'-infinity'::timestamptz),
      w.organization_id,
      COALESCE((SELECT max(t.last_grant_at) FROM public.marketplace_quota_turn t
        WHERE t.method_group=p_group AND t.subject_hash=ANY(p_subjects)
          AND t.organization_id=w.organization_id AND t.account_id=w.account_id),'-infinity'::timestamptz),
      w.account_id,CASE w.purpose WHEN 'RECONCILE' THEN 0 WHEN 'WRITE' THEN 1 WHEN 'CHECK' THEN 2 ELSE 3 END
    LIMIT 1;
  IF chosen.organization_id IS DISTINCT FROM p_organization OR chosen.account_id IS DISTINCT FROM p_account
    OR chosen.purpose IS DISTINCT FROM p_purpose THEN RETURN now_time+interval '1 second'; END IF;
  UPDATE public.marketplace_quota_bucket SET next_allowed_at=now_time+p_spacing_ms*interval '1 millisecond',
    non_sync_grants=CASE WHEN p_purpose='SYNC' THEN 0 ELSE LEAST(non_sync_grants+1,9) END
    WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  UPDATE public.marketplace_quota_resource SET remaining=remaining-p_cost
    WHERE subject_hash=ANY(p_subjects) AND resource_group=p_resource AND window_until>now_time;
  FOR subject IN SELECT DISTINCT unnest(p_subjects) LOOP
    INSERT INTO public.marketplace_quota_turn(subject_hash,method_group,organization_id,account_id,last_grant_at)
      VALUES(subject,p_group,p_organization,p_account,now_time)
      ON CONFLICT(subject_hash,method_group,organization_id,account_id)
      DO UPDATE SET last_grant_at=EXCLUDED.last_grant_at;
  END LOOP;
  DELETE FROM public.marketplace_quota_waiter WHERE organization_id=p_organization AND account_id=p_account
    AND method_group=p_group AND purpose=p_purpose;
  INSERT INTO public.marketplace_quota_grant(id,subjects,method_group,created_at,expires_at)
    VALUES(p_id,p_subjects,p_group,now_time,now_time+interval '180 seconds');
  IF p_group LIKE '%:report-generation' THEN
    INSERT INTO public.marketplace_quota_report(id,subjects,created_at) VALUES(p_id,p_subjects,now_time);
  END IF;
  RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION repricer_reserve_quota(uuid,uuid,uuid,text,text[],text,integer,text,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_reserve_quota(uuid,uuid,uuid,text,text[],text,integer,text,integer) TO repricer_api,repricer_worker;

--changeset repricer:marketplace-quota-release-001 splitStatements:false
CREATE FUNCTION repricer_release_quota(p_id uuid)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
  UPDATE public.marketplace_quota_grant SET released_at=clock_timestamp() WHERE id=p_id AND released_at IS NULL;
$$;
REVOKE ALL ON FUNCTION repricer_release_quota(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_release_quota(uuid) TO repricer_api,repricer_worker;

--changeset repricer:marketplace-quota-report-release-001 splitStatements:false
CREATE FUNCTION repricer_finish_report_quota(p_id uuid)
RETURNS void LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
  UPDATE public.marketplace_quota_report SET finished_at=clock_timestamp() WHERE id=p_id AND finished_at IS NULL;
$$;
REVOKE ALL ON FUNCTION repricer_finish_report_quota(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_finish_report_quota(uuid) TO repricer_api,repricer_worker;

--changeset repricer:marketplace-quota-resource-observation-001 splitStatements:false
CREATE FUNCTION repricer_observe_resource(p_subjects text[],p_group text,p_resource text,
  p_started timestamptz,p_until timestamptz,p_limit bigint,p_remaining bigint)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE subject text; now_time timestamptz:=clock_timestamp();
BEGIN
  IF cardinality(p_subjects) NOT BETWEEN 1 AND 4 OR length(p_group)>100 OR length(p_resource)>100
    OR p_limit NOT BETWEEN 1 AND 1000000000 OR p_remaining NOT BETWEEN 0 AND p_limit
    OR p_started>now_time OR p_until<=now_time OR p_until>now_time+interval '32 days'
    THEN RETURN; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('marketplace-quota:'||p_group,0));
  FOR subject IN SELECT DISTINCT unnest(p_subjects) ORDER BY 1 LOOP
    IF subject!~'^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'invalid quota subject'; END IF;
    INSERT INTO public.marketplace_quota_resource(subject_hash,resource_group,window_until,confirmed_limit,remaining)
      VALUES(subject,p_resource,p_until,p_limit,p_remaining)
      ON CONFLICT(subject_hash,resource_group) DO UPDATE SET
        confirmed_limit=LEAST(marketplace_quota_resource.confirmed_limit,p_limit),
        remaining=CASE WHEN marketplace_quota_resource.window_until=p_until
          THEN LEAST(marketplace_quota_resource.remaining,p_remaining)
          ELSE LEAST(marketplace_quota_resource.confirmed_limit,p_limit,p_remaining) END,
        window_until=p_until
      WHERE marketplace_quota_resource.window_until=p_until
        OR (marketplace_quota_resource.window_until<p_until AND marketplace_quota_resource.window_until<=p_started);
  END LOOP;
END;
$$;
REVOKE ALL ON FUNCTION repricer_observe_resource(text[],text,text,timestamptz,timestamptz,bigint,bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_observe_resource(text[],text,text,timestamptz,timestamptz,bigint,bigint) TO repricer_api,repricer_worker;

--changeset repricer:marketplace-quota-penalty-001 splitStatements:true
ALTER TABLE marketplace_quota_bucket ADD COLUMN rate_failures integer NOT NULL DEFAULT 0 CHECK(rate_failures BETWEEN 0 AND 5);
ALTER TABLE marketplace_quota_bucket ADD COLUMN last_limited_at timestamptz;

--changeset repricer:marketplace-quota-penalty-function-001 splitStatements:false
CREATE OR REPLACE FUNCTION repricer_pause_quota(p_subjects text[],p_group text,p_until timestamptz)
RETURNS void LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE subject text; now_time timestamptz:=clock_timestamp();
BEGIN
  IF cardinality(p_subjects) NOT BETWEEN 1 AND 4 OR length(p_group)>100
    OR p_until>now_time+interval '7 days' THEN RAISE EXCEPTION 'invalid quota pause'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('marketplace-quota:'||p_group,0));
  FOR subject IN SELECT DISTINCT unnest(p_subjects) ORDER BY 1 LOOP
    IF subject!~'^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'invalid quota subject'; END IF;
    UPDATE public.marketplace_quota_bucket SET
      blocked_until=GREATEST(blocked_until,p_until,now_time+
        CASE WHEN last_limited_at IS NULL OR last_limited_at<now_time-interval '30 minutes'
          THEN 60 ELSE LEAST(900,60*(1<<LEAST(rate_failures,4))) END*interval '1 second'),
      rate_failures=CASE WHEN last_limited_at IS NULL OR last_limited_at<now_time-interval '30 minutes'
        THEN 1 ELSE LEAST(5,rate_failures+1) END,last_limited_at=now_time
      WHERE subject_hash=subject AND method_group=p_group;
  END LOOP;
END;
$$;

--changeset repricer:marketplace-demand-publication-001 splitStatements:true
CREATE TABLE marketplace_demand_observation (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,publication_id uuid NOT NULL,
  demand_id uuid NOT NULL,offer_id uuid NOT NULL,accepted_at timestamptz NOT NULL,
  original_quantity numeric(38,12) NOT NULL CHECK(original_quantity>0),
  raw_file_id uuid NOT NULL REFERENCES platform_file(id),
  PRIMARY KEY(publication_id,demand_id),
  FOREIGN KEY(organization_id,account_id,publication_id) REFERENCES marketplace_sync_run(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_demand_observation IS 'owner=marketplace; immutable original order line in one complete history publication; stable demand_id independent of cancellation/child shipment; consumers preserve monotonic episode demand';
ALTER TABLE marketplace_demand_observation ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_demand_observation FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_demand_observation_scope ON marketplace_demand_observation USING (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true') WITH CHECK (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid AND current_setting('app.authorized',true)='true');

--changeset repricer:marketplace-quota-idle-admission-001 splitStatements:false
CREATE OR REPLACE FUNCTION repricer_reserve_quota(p_id uuid,p_organization uuid,p_account uuid,p_purpose text,
  p_subjects text[],p_group text,p_spacing_ms integer,p_resource text,p_cost integer)
RETURNS timestamptz LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE subject text; next_time timestamptz; now_time timestamptz:=clock_timestamp();
  chosen record; force_sync boolean;
BEGIN
  IF p_id IS NULL OR p_organization IS NULL OR p_account IS NULL
    OR p_purpose NOT IN ('SYNC','RECONCILE','WRITE','CHECK')
    OR cardinality(p_subjects) NOT BETWEEN 1 AND 4 OR p_spacing_ms NOT BETWEEN 1 AND 3600000
    OR length(p_group)>100 OR length(p_resource)>100 OR p_cost NOT BETWEEN 1 AND 1000
    THEN RAISE EXCEPTION 'invalid quota admission'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('marketplace-quota:'||p_group,0));
  IF EXISTS(SELECT 1 FROM public.marketplace_quota_grant WHERE id=p_id) THEN
    IF EXISTS(SELECT 1 FROM public.marketplace_quota_grant WHERE id=p_id
      AND method_group=p_group AND subjects=p_subjects AND released_at IS NULL AND expires_at>now_time)
      THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'expired or conflicting admission identity';
  END IF;
  DELETE FROM public.marketplace_quota_waiter WHERE method_group=p_group AND expires_at<=now_time;
  DELETE FROM public.marketplace_quota_grant WHERE method_group=p_group AND expires_at<now_time-interval '7 days';
  FOR subject IN SELECT DISTINCT unnest(p_subjects) ORDER BY 1 LOOP
    IF subject!~'^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'invalid quota subject'; END IF;
    INSERT INTO public.marketplace_quota_bucket(subject_hash,method_group,next_allowed_at)
      VALUES(subject,p_group,now_time) ON CONFLICT DO NOTHING;
    PERFORM 1 FROM public.marketplace_quota_bucket
      WHERE subject_hash=subject AND method_group=p_group FOR UPDATE;
  END LOOP;
  SELECT max(GREATEST(next_allowed_at,blocked_until)) INTO next_time
    FROM public.marketplace_quota_bucket WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  SELECT GREATEST(next_time,CASE WHEN count(*)>0 THEN LEAST(max(expires_at),now_time+interval '1 second') END) INTO next_time FROM public.marketplace_quota_grant
    WHERE method_group=p_group AND subjects&&p_subjects AND released_at IS NULL AND expires_at>now_time;
  SELECT GREATEST(next_time,max(window_until)) INTO next_time FROM public.marketplace_quota_resource
    WHERE subject_hash=ANY(p_subjects) AND resource_group=p_resource AND window_until>now_time
      AND remaining-CEIL(confirmed_limit*0.1)<p_cost;
  IF p_group LIKE '%:report-generation' AND EXISTS(SELECT 1 FROM public.marketplace_quota_report
    WHERE subjects&&p_subjects AND finished_at IS NULL)
    THEN next_time:=GREATEST(next_time,now_time+interval '30 seconds'); END IF;
  INSERT INTO public.marketplace_quota_waiter(organization_id,account_id,method_group,purpose,subjects,expires_at,resource_group,request_cost)
    VALUES(p_organization,p_account,p_group,p_purpose,p_subjects,GREATEST(now_time,next_time)+interval '30 seconds',p_resource,p_cost)
    ON CONFLICT(organization_id,account_id,method_group,purpose) DO UPDATE SET
      subjects=EXCLUDED.subjects,expires_at=EXCLUDED.expires_at,
      resource_group=EXCLUDED.resource_group,request_cost=EXCLUDED.request_cost;
  IF next_time>now_time THEN RETURN next_time; END IF;
  SELECT COALESCE(max(non_sync_grants),0)>=9 INTO force_sync FROM public.marketplace_quota_bucket
    WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  SELECT w.organization_id,w.account_id,w.purpose INTO chosen
    FROM public.marketplace_quota_waiter w
    WHERE w.method_group=p_group AND w.subjects&&p_subjects AND w.expires_at>now_time
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_bucket b
        WHERE b.method_group=p_group AND b.subject_hash=ANY(w.subjects)
          AND GREATEST(b.next_allowed_at,b.blocked_until)>now_time)
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_grant g
        WHERE g.method_group=p_group AND g.subjects&&w.subjects
          AND g.released_at IS NULL AND g.expires_at>now_time)
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_resource r
        WHERE r.subject_hash=ANY(w.subjects) AND r.resource_group=w.resource_group
          AND r.window_until>now_time AND r.remaining-CEIL(r.confirmed_limit*0.1)<w.request_cost)
    ORDER BY CASE WHEN force_sync AND w.purpose='SYNC' THEN 0 ELSE 1 END,
      COALESCE((SELECT max(t.last_grant_at) FROM public.marketplace_quota_turn t
        WHERE t.method_group=p_group AND t.subject_hash=ANY(p_subjects)
          AND t.organization_id=w.organization_id),'-infinity'::timestamptz),
      w.organization_id,
      COALESCE((SELECT max(t.last_grant_at) FROM public.marketplace_quota_turn t
        WHERE t.method_group=p_group AND t.subject_hash=ANY(p_subjects)
          AND t.organization_id=w.organization_id AND t.account_id=w.account_id),'-infinity'::timestamptz),
      w.account_id,CASE w.purpose WHEN 'RECONCILE' THEN 0 WHEN 'WRITE' THEN 1 WHEN 'CHECK' THEN 2 ELSE 3 END
    LIMIT 1;
  IF chosen.organization_id IS DISTINCT FROM p_organization OR chosen.account_id IS DISTINCT FROM p_account
    OR chosen.purpose IS DISTINCT FROM p_purpose THEN RETURN now_time+interval '1 second'; END IF;
  UPDATE public.marketplace_quota_bucket SET next_allowed_at=now_time+p_spacing_ms*interval '1 millisecond',
    non_sync_grants=CASE WHEN p_purpose='SYNC' THEN 0 ELSE LEAST(non_sync_grants+1,9) END
    WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  UPDATE public.marketplace_quota_resource SET remaining=remaining-p_cost
    WHERE subject_hash=ANY(p_subjects) AND resource_group=p_resource AND window_until>now_time;
  FOR subject IN SELECT DISTINCT unnest(p_subjects) LOOP
    INSERT INTO public.marketplace_quota_turn(subject_hash,method_group,organization_id,account_id,last_grant_at)
      VALUES(subject,p_group,p_organization,p_account,now_time)
      ON CONFLICT(subject_hash,method_group,organization_id,account_id)
      DO UPDATE SET last_grant_at=EXCLUDED.last_grant_at;
  END LOOP;
  DELETE FROM public.marketplace_quota_waiter WHERE organization_id=p_organization AND account_id=p_account
    AND method_group=p_group AND purpose=p_purpose;
  INSERT INTO public.marketplace_quota_grant(id,subjects,method_group,created_at,expires_at)
    VALUES(p_id,p_subjects,p_group,now_time,now_time+interval '180 seconds');
  IF p_group LIKE '%:report-generation' THEN
    INSERT INTO public.marketplace_quota_report(id,subjects,created_at) VALUES(p_id,p_subjects,now_time);
  END IF;
  RETURN NULL;
END;
$$;

--changeset repricer:marketplace-raw-scope-links-001 splitStatements:false
DO $$
DECLARE link record;
BEGIN
  FOR link IN SELECT * FROM (VALUES
    ('marketplace_profile','evidence_file_id'),
    ('marketplace_raw_page','file_id'),
    ('marketplace_external_call','raw_file_id'),
    ('marketplace_economic_terms','raw_file_id'),
    ('marketplace_commercial_state','raw_file_id'),
    ('marketplace_payment','raw_file_id'),
    ('marketplace_return','raw_file_id'),
    ('marketplace_order_item','raw_file_id'),
    ('marketplace_history_day','raw_file_id'),
    ('marketplace_history_stage','raw_file_id'),
    ('marketplace_report','raw_file_id'),
    ('marketplace_demand_observation','raw_file_id')
  ) AS links(table_name,column_name) LOOP
    EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I',link.table_name,
      link.table_name||'_'||link.column_name||'_fkey');
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY(organization_id,account_id,%I) REFERENCES platform_file(organization_id,account_id,id)',
      link.table_name,link.table_name||'_raw_scope',link.column_name);
  END LOOP;
END;
$$;
--changeset repricer:marketplace-parent-scope-links-001
ALTER TABLE marketplace_stock_stage ADD CONSTRAINT marketplace_stock_stage_run_scope
  FOREIGN KEY(organization_id,account_id,run_id)
  REFERENCES marketplace_sync_run(organization_id,account_id,id);
ALTER TABLE marketplace_placement ADD CONSTRAINT marketplace_placement_offer_scope
  UNIQUE(organization_id,account_id,id,offer_id);
ALTER TABLE marketplace_economic_terms ADD CONSTRAINT marketplace_terms_placement_scope
  FOREIGN KEY(organization_id,account_id,placement_id,offer_id)
  REFERENCES marketplace_placement(organization_id,account_id,id,offer_id);
ALTER TABLE marketplace_sync_run DROP CONSTRAINT marketplace_sync_run_job_id_fkey;
ALTER TABLE marketplace_sync_run ADD CONSTRAINT marketplace_sync_run_job_scope
  FOREIGN KEY(organization_id,account_id,job_id)
  REFERENCES platform_job(organization_id,account_id,id);
ALTER TABLE marketplace_report ADD CONSTRAINT marketplace_report_job_scope
  FOREIGN KEY(organization_id,account_id,job_id)
  REFERENCES platform_job(organization_id,account_id,id);
ALTER TABLE marketplace_offer ADD CONSTRAINT marketplace_offer_publication_scope
  FOREIGN KEY(organization_id,account_id,publication_id)
  REFERENCES marketplace_sync_run(organization_id,account_id,id);
ALTER TABLE marketplace_placement ADD CONSTRAINT marketplace_placement_publication_scope
  FOREIGN KEY(organization_id,account_id,publication_id)
  REFERENCES marketplace_sync_run(organization_id,account_id,id);
ALTER TABLE marketplace_promotion ADD CONSTRAINT marketplace_promotion_publication_scope
  FOREIGN KEY(organization_id,account_id,publication_id)
  REFERENCES marketplace_sync_run(organization_id,account_id,id);
ALTER TABLE marketplace_promotion_offer ADD CONSTRAINT marketplace_promotion_offer_publication_scope
  FOREIGN KEY(organization_id,account_id,publication_id)
  REFERENCES marketplace_sync_run(organization_id,account_id,id);
ALTER TABLE marketplace_campaign ADD CONSTRAINT marketplace_campaign_discovery_scope
  FOREIGN KEY(organization_id,account_id,discovered_run)
  REFERENCES marketplace_sync_run(organization_id,account_id,id);
ALTER TABLE marketplace_campaign ADD CONSTRAINT marketplace_campaign_stocks_scope
  FOREIGN KEY(organization_id,account_id,stocks_run)
  REFERENCES marketplace_sync_run(organization_id,account_id,id);
ALTER TABLE marketplace_campaign ADD CONSTRAINT marketplace_campaign_placements_scope
  FOREIGN KEY(organization_id,account_id,placements_run)
  REFERENCES marketplace_sync_run(organization_id,account_id,id);
--changeset repricer:marketplace-page-admission-001
CREATE TABLE marketplace_page_queue (
  id uuid PRIMARY KEY,organization_id uuid NOT NULL,account_id uuid NOT NULL,
  run_id uuid NOT NULL,page_number integer NOT NULL CHECK(page_number BETWEEN 0 AND 9999),
  raw_file_id uuid,method text,cursor text CHECK(length(cursor)<=4096),
  connection_revision bigint,status_code integer,content_encoding text,
  byte_count bigint CHECK(byte_count BETWEEN 0 AND 41943040),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),closed_at timestamptz,
  FOREIGN KEY(organization_id,account_id,run_id)
    REFERENCES marketplace_sync_run(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,raw_file_id)
    REFERENCES platform_file(organization_id,account_id,id)
);
COMMENT ON TABLE marketplace_page_queue IS 'owner=marketplace.PageAdmissionService; scope=organization/account; grain=one admitted source page attempt; key=attempt UUID; repeat=resume raw before another HTTP; history=90d raw metadata, live reservation until parsed or terminal run';
CREATE UNIQUE INDEX marketplace_page_queue_current ON marketplace_page_queue(run_id,page_number)
  WHERE closed_at IS NULL;
ALTER TABLE marketplace_page_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_page_queue FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_page_queue_scope ON marketplace_page_queue USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
CREATE TABLE marketplace_page_pressure (
  scope_key text PRIMARY KEY,paused boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE marketplace_page_pressure IS 'owner=marketplace.PageAdmissionService; private global/account high-low hysteresis, no tenant payload';
REVOKE ALL ON marketplace_page_pressure FROM PUBLIC,repricer_api,repricer_worker;
GRANT SELECT,INSERT,UPDATE ON marketplace_page_queue TO repricer_api,repricer_worker;
--changeset repricer:marketplace-page-admission-function-001 splitStatements:false
CREATE FUNCTION repricer_admit_page(p_id uuid,p_org uuid,p_account uuid,p_run uuid,p_page integer,p_heavy_allowed boolean)
RETURNS TABLE(admission_id uuid,fresh boolean)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE current_id uuid; total_bytes bigint; total_pages bigint; account_bytes bigint;
  account_pages bigint; global_paused boolean; account_paused boolean; account_key text;
BEGIN
  IF p_org IS DISTINCT FROM NULLIF(current_setting('app.organization_id',true),'')::uuid
    OR p_account IS DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
    OR current_setting('app.authorized',true) IS DISTINCT FROM 'true'
    OR p_id IS NULL OR p_org IS NULL OR p_account IS NULL OR p_page NOT BETWEEN 0 AND 9999
    OR NOT EXISTS(SELECT 1 FROM public.marketplace_sync_run WHERE id=p_run
      AND organization_id=p_org AND account_id=p_account AND state IN ('FETCHING','PARSING','VALIDATING'))
    THEN RAISE EXCEPTION 'invalid page admission'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('marketplace:page-pressure',0));
  UPDATE public.marketplace_page_queue SET closed_at=clock_timestamp()
    WHERE id IN (SELECT id FROM public.marketplace_page_queue WHERE raw_file_id IS NULL
      AND closed_at IS NULL AND created_at<clock_timestamp()-interval '5 minutes'
      ORDER BY created_at,id LIMIT 500);
  SELECT q.id INTO current_id FROM public.marketplace_page_queue q
    WHERE q.run_id=p_run AND q.page_number=p_page AND q.closed_at IS NULL;
  IF current_id IS NOT NULL THEN RETURN QUERY SELECT current_id,false; RETURN; END IF;
  IF p_heavy_allowed IS DISTINCT FROM true THEN RETURN QUERY SELECT NULL::uuid,false; RETURN; END IF;
  SELECT COALESCE(sum(COALESCE(q.byte_count,41943040)),0),count(*),
    COALESCE(sum(COALESCE(q.byte_count,41943040)) FILTER(WHERE q.organization_id=p_org AND q.account_id=p_account),0),
    count(*) FILTER(WHERE q.organization_id=p_org AND q.account_id=p_account)
    INTO total_bytes,total_pages,account_bytes,account_pages
    FROM public.marketplace_page_queue q JOIN public.marketplace_sync_run r ON r.id=q.run_id
    WHERE q.closed_at IS NULL AND r.state IN ('FETCHING','PARSING','VALIDATING')
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_raw_page p
        WHERE p.run_id=q.run_id AND p.page_number=q.page_number AND p.parsed);
  account_key:=p_org::text||':'||p_account::text;
  INSERT INTO public.marketplace_page_pressure(scope_key) VALUES('global'),(account_key)
    ON CONFLICT DO NOTHING;
  UPDATE public.marketplace_page_pressure SET paused=false WHERE scope_key='global'
    AND total_bytes<2147483648 AND total_pages<128;
  UPDATE public.marketplace_page_pressure SET paused=false WHERE scope_key=account_key
    AND account_bytes<134217728 AND account_pages<8;
  UPDATE public.marketplace_page_pressure SET paused=true WHERE scope_key='global'
    AND (total_bytes+41943040>4294967296 OR total_pages>=256);
  UPDATE public.marketplace_page_pressure SET paused=true WHERE scope_key=account_key
    AND (account_bytes+41943040>268435456 OR account_pages>=16);
  SELECT paused INTO global_paused FROM public.marketplace_page_pressure WHERE scope_key='global';
  SELECT paused INTO account_paused FROM public.marketplace_page_pressure WHERE scope_key=account_key;
  IF global_paused OR account_paused THEN RETURN QUERY SELECT NULL::uuid,false; RETURN; END IF;
  INSERT INTO public.marketplace_page_queue(id,organization_id,account_id,run_id,page_number)
    VALUES(p_id,p_org,p_account,p_run,p_page);
  RETURN QUERY SELECT p_id,true;
END;
$$;
REVOKE ALL ON FUNCTION repricer_admit_page(uuid,uuid,uuid,uuid,integer,boolean) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_admit_page(uuid,uuid,uuid,uuid,integer,boolean) TO repricer_api,repricer_worker;

--changeset repricer:marketplace-quota-bounded-retention-001 splitStatements:false
CREATE OR REPLACE FUNCTION repricer_reserve_quota(p_id uuid,p_organization uuid,p_account uuid,p_purpose text,
  p_subjects text[],p_group text,p_spacing_ms integer,p_resource text,p_cost integer)
RETURNS timestamptz LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE subject text; next_time timestamptz; now_time timestamptz:=clock_timestamp();
  chosen record; force_sync boolean;
BEGIN
  IF p_id IS NULL OR p_organization IS NULL OR p_account IS NULL
    OR p_purpose NOT IN ('SYNC','RECONCILE','WRITE','CHECK')
    OR cardinality(p_subjects) NOT BETWEEN 1 AND 4 OR p_spacing_ms NOT BETWEEN 1 AND 3600000
    OR length(p_group)>100 OR length(p_resource)>100 OR p_cost NOT BETWEEN 1 AND 1000
    THEN RAISE EXCEPTION 'invalid quota admission'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('marketplace-quota:'||p_group,0));
  IF EXISTS(SELECT 1 FROM public.marketplace_quota_grant WHERE id=p_id) THEN
    IF EXISTS(SELECT 1 FROM public.marketplace_quota_grant WHERE id=p_id
      AND method_group=p_group AND subjects=p_subjects AND released_at IS NULL AND expires_at>now_time)
      THEN RETURN NULL; END IF;
    RAISE EXCEPTION 'expired or conflicting admission identity';
  END IF;
  DELETE FROM public.marketplace_quota_waiter WHERE (organization_id,account_id,method_group,purpose) IN (
    SELECT organization_id,account_id,method_group,purpose FROM public.marketplace_quota_waiter
      WHERE method_group=p_group AND expires_at<=now_time ORDER BY expires_at LIMIT 500);

  FOR subject IN SELECT DISTINCT unnest(p_subjects) ORDER BY 1 LOOP
    IF subject!~'^[a-f0-9]{64}$' THEN RAISE EXCEPTION 'invalid quota subject'; END IF;
    INSERT INTO public.marketplace_quota_bucket(subject_hash,method_group,next_allowed_at)
      VALUES(subject,p_group,now_time) ON CONFLICT DO NOTHING;
    PERFORM 1 FROM public.marketplace_quota_bucket
      WHERE subject_hash=subject AND method_group=p_group FOR UPDATE;
  END LOOP;
  SELECT max(GREATEST(next_allowed_at,blocked_until)) INTO next_time
    FROM public.marketplace_quota_bucket WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  SELECT GREATEST(next_time,CASE WHEN count(*)>0 THEN LEAST(max(expires_at),now_time+interval '1 second') END) INTO next_time FROM public.marketplace_quota_grant
    WHERE method_group=p_group AND subjects&&p_subjects AND released_at IS NULL AND expires_at>now_time;
  SELECT GREATEST(next_time,max(window_until)) INTO next_time FROM public.marketplace_quota_resource
    WHERE subject_hash=ANY(p_subjects) AND resource_group=p_resource AND window_until>now_time
      AND remaining-CEIL(confirmed_limit*0.1)<p_cost;
  IF p_group LIKE '%:report-generation' AND EXISTS(SELECT 1 FROM public.marketplace_quota_report
    WHERE subjects&&p_subjects AND finished_at IS NULL)
    THEN next_time:=GREATEST(next_time,now_time+interval '30 seconds'); END IF;
  INSERT INTO public.marketplace_quota_waiter(organization_id,account_id,method_group,purpose,subjects,expires_at,resource_group,request_cost)
    VALUES(p_organization,p_account,p_group,p_purpose,p_subjects,GREATEST(now_time,next_time)+interval '30 seconds',p_resource,p_cost)
    ON CONFLICT(organization_id,account_id,method_group,purpose) DO UPDATE SET
      subjects=EXCLUDED.subjects,expires_at=EXCLUDED.expires_at,
      resource_group=EXCLUDED.resource_group,request_cost=EXCLUDED.request_cost;
  IF next_time>now_time THEN RETURN next_time; END IF;
  SELECT COALESCE(max(non_sync_grants),0)>=9 INTO force_sync FROM public.marketplace_quota_bucket
    WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  SELECT w.organization_id,w.account_id,w.purpose INTO chosen
    FROM public.marketplace_quota_waiter w
    WHERE w.method_group=p_group AND w.subjects&&p_subjects AND w.expires_at>now_time
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_bucket b
        WHERE b.method_group=p_group AND b.subject_hash=ANY(w.subjects)
          AND GREATEST(b.next_allowed_at,b.blocked_until)>now_time)
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_grant g
        WHERE g.method_group=p_group AND g.subjects&&w.subjects
          AND g.released_at IS NULL AND g.expires_at>now_time)
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_resource r
        WHERE r.subject_hash=ANY(w.subjects) AND r.resource_group=w.resource_group
          AND r.window_until>now_time AND r.remaining-CEIL(r.confirmed_limit*0.1)<w.request_cost)
    ORDER BY CASE WHEN force_sync AND w.purpose='SYNC' THEN 0 ELSE 1 END,
      COALESCE((SELECT max(t.last_grant_at) FROM public.marketplace_quota_turn t
        WHERE t.method_group=p_group AND t.subject_hash=ANY(p_subjects)
          AND t.organization_id=w.organization_id),'-infinity'::timestamptz),
      w.organization_id,
      COALESCE((SELECT max(t.last_grant_at) FROM public.marketplace_quota_turn t
        WHERE t.method_group=p_group AND t.subject_hash=ANY(p_subjects)
          AND t.organization_id=w.organization_id AND t.account_id=w.account_id),'-infinity'::timestamptz),
      w.account_id,CASE w.purpose WHEN 'RECONCILE' THEN 0 WHEN 'WRITE' THEN 1 WHEN 'CHECK' THEN 2 ELSE 3 END
    LIMIT 1;
  IF chosen.organization_id IS DISTINCT FROM p_organization OR chosen.account_id IS DISTINCT FROM p_account
    OR chosen.purpose IS DISTINCT FROM p_purpose THEN RETURN now_time+interval '1 second'; END IF;
  UPDATE public.marketplace_quota_bucket SET next_allowed_at=now_time+p_spacing_ms*interval '1 millisecond',
    non_sync_grants=CASE WHEN p_purpose='SYNC' THEN 0 ELSE LEAST(non_sync_grants+1,9) END
    WHERE subject_hash=ANY(p_subjects) AND method_group=p_group;
  UPDATE public.marketplace_quota_resource SET remaining=remaining-p_cost
    WHERE subject_hash=ANY(p_subjects) AND resource_group=p_resource AND window_until>now_time;
  FOR subject IN SELECT DISTINCT unnest(p_subjects) LOOP
    INSERT INTO public.marketplace_quota_turn(subject_hash,method_group,organization_id,account_id,last_grant_at)
      VALUES(subject,p_group,p_organization,p_account,now_time)
      ON CONFLICT(subject_hash,method_group,organization_id,account_id)
      DO UPDATE SET last_grant_at=EXCLUDED.last_grant_at;
  END LOOP;
  DELETE FROM public.marketplace_quota_waiter WHERE organization_id=p_organization AND account_id=p_account
    AND method_group=p_group AND purpose=p_purpose;
  INSERT INTO public.marketplace_quota_grant(id,subjects,method_group,created_at,expires_at)
    VALUES(p_id,p_subjects,p_group,now_time,now_time+interval '180 seconds');
  IF p_group LIKE '%:report-generation' THEN
    INSERT INTO public.marketplace_quota_report(id,subjects,created_at) VALUES(p_id,p_subjects,now_time);
  END IF;
  RETURN NULL;
END;
$$;
--changeset repricer:marketplace-quota-retention-function-001 splitStatements:false
CREATE FUNCTION repricer_cleanup_quota() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE remaining_rows integer:=500; removed integer;
BEGIN
  PERFORM pg_advisory_xact_lock(hashtextextended('marketplace:quota-cleanup',0));
  DELETE FROM public.marketplace_quota_grant WHERE id IN (
    SELECT g.id FROM public.marketplace_quota_grant g
    WHERE GREATEST(g.expires_at,g.released_at)<clock_timestamp()-interval '24 hours'
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_report r WHERE r.id=g.id AND r.finished_at IS NULL)
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_resource r
        WHERE r.subject_hash=ANY(g.subjects) AND r.window_until>clock_timestamp()-interval '24 hours')
    ORDER BY g.expires_at,g.id LIMIT remaining_rows);
  GET DIAGNOSTICS removed=ROW_COUNT; remaining_rows:=remaining_rows-removed;
  DELETE FROM public.marketplace_quota_report WHERE id IN (
    SELECT id FROM public.marketplace_quota_report WHERE finished_at<clock_timestamp()-interval '24 hours'
    ORDER BY finished_at,id LIMIT remaining_rows);
  GET DIAGNOSTICS removed=ROW_COUNT; remaining_rows:=remaining_rows-removed;
  DELETE FROM public.marketplace_quota_resource WHERE (subject_hash,resource_group) IN (
    SELECT r.subject_hash,r.resource_group FROM public.marketplace_quota_resource r
    WHERE r.window_until<clock_timestamp()-interval '24 hours'
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_grant g WHERE r.subject_hash=ANY(g.subjects)
        AND g.released_at IS NULL AND g.expires_at>clock_timestamp())
    ORDER BY r.window_until LIMIT remaining_rows);
  GET DIAGNOSTICS removed=ROW_COUNT; remaining_rows:=remaining_rows-removed;
  DELETE FROM public.marketplace_quota_waiter WHERE (organization_id,account_id,method_group,purpose) IN (
    SELECT organization_id,account_id,method_group,purpose FROM public.marketplace_quota_waiter
    WHERE expires_at<clock_timestamp()-interval '24 hours' ORDER BY expires_at LIMIT remaining_rows);
  GET DIAGNOSTICS removed=ROW_COUNT; remaining_rows:=remaining_rows-removed;
  DELETE FROM public.marketplace_quota_turn WHERE (subject_hash,method_group,organization_id,account_id) IN (
    SELECT t.subject_hash,t.method_group,t.organization_id,t.account_id FROM public.marketplace_quota_turn t
    WHERE t.last_grant_at<clock_timestamp()-interval '24 hours'
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_waiter w WHERE w.method_group=t.method_group
        AND w.organization_id=t.organization_id AND w.account_id=t.account_id)
    ORDER BY t.last_grant_at LIMIT remaining_rows);
  GET DIAGNOSTICS removed=ROW_COUNT; remaining_rows:=remaining_rows-removed;
  DELETE FROM public.marketplace_quota_bucket WHERE (subject_hash,method_group) IN (
    SELECT b.subject_hash,b.method_group FROM public.marketplace_quota_bucket b
    WHERE GREATEST(b.next_allowed_at,b.blocked_until)<clock_timestamp()-interval '24 hours'
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_grant g WHERE g.method_group=b.method_group
        AND b.subject_hash=ANY(g.subjects))
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_waiter w WHERE w.method_group=b.method_group
        AND b.subject_hash=ANY(w.subjects))
      AND NOT EXISTS(SELECT 1 FROM public.marketplace_quota_resource r WHERE r.subject_hash=b.subject_hash)
    ORDER BY b.next_allowed_at LIMIT remaining_rows);
  GET DIAGNOSTICS removed=ROW_COUNT; remaining_rows:=remaining_rows-removed;
  RETURN 500-remaining_rows;
END;
$$;
REVOKE ALL ON FUNCTION repricer_cleanup_quota() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_cleanup_quota() TO repricer_worker;

--changeset repricer:marketplace-table-contracts-001
COMMENT ON TABLE marketplace_account IS 'owner=marketplace; scope=organization with explicit account discovery; grain=permanent vendor seller identity; repeat=global marketplace/external id; history=monotonic revision; key=UUID; vendor and external identity globally unique';
COMMENT ON TABLE marketplace_connection IS 'owner=marketplace; scope=exact account; grain=current credential reference, never key material; repeat=account CAS; history=Vault versions and audit; key=UUID and organization/account';
COMMENT ON TABLE marketplace_connection_candidate IS 'owner=marketplace; scope=exact account; grain=one credential verification; repeat=idempotency request; history=terminal verification result without secret; key=verification UUID';
COMMENT ON TABLE marketplace_external_call IS 'owner=marketplace; scope=exact account; grain=one transport attempt; repeat=never resend writes; history=immutable attempt evidence; key=transport attempt UUID';
COMMENT ON TABLE marketplace_history_day IS 'owner=marketplace; scope=exact account; grain=one complete source day per placement; repeat=day and placement; history=raw evidence; aggregation=original demand, no stock continuity inferred from snapshots; key=organization/account/placement/day';
COMMENT ON TABLE marketplace_history_stage IS 'owner=marketplace; scope=exact account; grain=one bounded original supplier history record; repeat=run+method+source identity; history=staging until complete canonical publication; key=run id/source kind/external id';
COMMENT ON TABLE marketplace_offer IS 'owner=marketplace; scope=exact account; grain=one vendor offer; repeat=external id; history=publication and immutable raw evidence; key=UUID and organization/account/external id';
COMMENT ON TABLE marketplace_offer_observation IS 'owner=marketplace; scope=exact account; grain=offer observed during one complete source traversal; repeat=offer+run; history=append only including equal values in distinct observations; semantics=revision changes only when meaningful values change; key=offer id/source run id';
COMMENT ON TABLE marketplace_offer_stage IS 'owner=marketplace; scope=exact account; grain=one validated candidate offer; repeat=run+external id; history=until publication; key=run id/external offer id';
COMMENT ON TABLE marketplace_order_item IS 'owner=marketplace; scope=exact account; grain=original order line independent from child shipment; repeat=order and line id plus source revision; history=raw evidence; aggregation=demand only from confirmed original composition; key=UUID and organization/account/order/line';
COMMENT ON TABLE marketplace_payment IS 'owner=marketplace; scope=exact account; grain=one identified supplier payment; repeat=external payment id plus proven source revision; history=raw evidence; aggregation=settlements only never revenue; key=UUID and organization/account/external payment id';
COMMENT ON TABLE marketplace_placement IS 'owner=marketplace; scope=exact account; grain=vendor placement of one Offer independent from a stock pool; repeat=offer+external placement; history=raw publication; key=UUID and organization/account/offer/external placement id';
COMMENT ON TABLE marketplace_placement_stage IS 'owner=marketplace; scope=exact account; grain=one candidate placement; repeat=run+campaign+offer; history=until complete publication; key=run id/campaign id/offer id';
COMMENT ON TABLE marketplace_profile IS 'owner=marketplace; scope=exact account; grain=immutable tested method capability revision; repeat=method+revision; history=append only; key=UUID and organization/account/method/revision';
COMMENT ON TABLE marketplace_promotion IS 'owner=marketplace; scope=exact account; grain=one observed promotion; repeat=vendor promotion identity; history=raw publication; key=UUID and organization/account/vendor promotion id';
COMMENT ON TABLE marketplace_promotion_offer IS 'owner=marketplace; scope=exact account; grain=one offer in one promotion including eligibility; repeat=promotion+offer; history=raw publication; key=organization/account/promotion/offer';
COMMENT ON TABLE marketplace_promotion_stage IS 'owner=marketplace; scope=exact account; grain=bounded candidate promotion row; repeat=run+promotion+SKU; history=staging until validated publication; key=run id/promotion id/SKU';
COMMENT ON TABLE marketplace_raw_page IS 'owner=marketplace; scope=exact account; grain=exact raw page version; repeat=run+page; history=immutable source evidence; key=run id/page number';
COMMENT ON TABLE marketplace_report IS 'owner=marketplace; scope=exact account; grain=one requested supplier report for a period; repeat=client request then fixed generation call and supplier report id; history=raw working file; ambiguous generation retains active quota slot; key=report UUID and unique generation call';
COMMENT ON TABLE marketplace_report_row IS 'owner=marketplace; scope=exact account; grain=one bounded row in one report sheet; repeat=report sheet row plus content hash; history=immutable supplier evidence, never a second business event; key=report id/sheet/row number';

COMMENT ON TABLE marketplace_return IS 'owner=marketplace; scope=exact account; grain=one identified return line; repeat=external return identity plus source revision; history=raw evidence; aggregation=physical and monetary proof remain separate; key=UUID and organization/account/external return line id';
COMMENT ON TABLE marketplace_source IS 'owner=marketplace; scope=exact account; grain=one source publication cursor; repeat=source; history=sync publications; key=UUID and organization/account/source type';
COMMENT ON TABLE marketplace_stock_observation IS 'owner=marketplace; scope=exact account; grain=physical stock pool observed during one complete traversal; repeat=pool+run; history=append only including 10 to 7 to 10; raw evidence=source run pages; no inferred arrivals; key=pool id/source run id';
COMMENT ON TABLE marketplace_stock_pool IS 'owner=marketplace; scope=exact account; grain=one non-overlapping stock pool; repeat=external stock identity; history=observed revision; unknown is null; key=UUID and organization/account/external physical pool id';
COMMENT ON TABLE marketplace_sync_run IS 'owner=marketplace; scope=exact account; grain=one bounded source traversal and publication; repeat=job; history=retained result; key=run UUID with scoped job id';
COMMENT ON TABLE marketplace_quota_bucket IS 'owner=marketplace.QuotaManager; scope=private global quota subject; grain=one supplier subject and method group; key=opaque subject hash/method group; repeat=serialized admission; history=current window and bounded 24h cleanup';
COMMENT ON TABLE marketplace_quota_waiter IS 'owner=marketplace.QuotaManager; scope=private admission metadata; grain=one contender; key=organization/account/method group/purpose; repeat=replace pending deadline; history=bounded expiry, no tenant payload';
COMMENT ON TABLE marketplace_quota_turn IS 'owner=marketplace.QuotaManager; scope=private shared supplier quota; grain=one company/account turn; key=subject hash/method group/organization/account; repeat=serialized admission; history=current fairness cursor, bounded idle cleanup';
COMMENT ON TABLE marketplace_quota_grant IS 'owner=marketplace.QuotaManager; scope=private admission metadata; grain=one reserved transport attempt; key=attempt UUID; repeat=one admission per attempt; history=conservative lease then 24h, unresolved reports retain grant';
COMMENT ON TABLE marketplace_quota_resource IS 'owner=marketplace.QuotaManager; scope=private supplier resource window; grain=one confirmed quota remainder; key=subject hash/resource group; repeat=atomic consumption; history=until window expiry plus 24h without active grants';
COMMENT ON TABLE marketplace_quota_report IS 'owner=marketplace.QuotaManager; scope=private report concurrency metadata; grain=one external report admission; key=attempt UUID; repeat=one generation admission; history=UNKNOWN holds indefinitely, finished plus 24h';
COMMENT ON TABLE marketplace_demand_observation IS 'owner=marketplace; scope=exact account; grain=original order line in complete source publication; key=publication id/stable demand id; repeat=immutable observation, consumer monotonic cumulative quantity; history=retained original demand evidence, cancellation does not subtract';
COMMENT ON TABLE marketplace_page_pressure IS 'owner=marketplace.PageAdmissionService; scope=private global/account admission metadata; grain=one HIGH/LOW state; key=global or scoped account key; repeat=serialized page admission; history=current hysteresis without tenant payload';

--changeset repricer:marketplace-realization-facts-001 splitStatements:true
ALTER TABLE marketplace_report DROP CONSTRAINT marketplace_report_kind_check;
ALTER TABLE marketplace_report ADD CONSTRAINT marketplace_report_kind_check
  CHECK(kind IN ('PAYMENTS','RETURNS','SERVICES','REALIZATION'));
ALTER TABLE marketplace_report ADD COLUMN campaign_id text;
ALTER TABLE marketplace_report ADD CONSTRAINT marketplace_report_campaign_scope
  FOREIGN KEY(organization_id,account_id,campaign_id)
  REFERENCES marketplace_campaign(organization_id,account_id,external_id);
ALTER TABLE marketplace_report ADD CONSTRAINT marketplace_report_realization_campaign
  CHECK((kind='REALIZATION')=(campaign_id IS NOT NULL));
ALTER TABLE marketplace_report_row ADD COLUMN fact_start bigint;
ALTER TABLE marketplace_report_row ADD COLUMN fact_end bigint;
UPDATE marketplace_report_row SET fact_start=ordinal,fact_end=ordinal,
  canonical_fact=jsonb_build_array(canonical_fact) WHERE canonical_fact IS NOT NULL;
ALTER TABLE marketplace_report_row ADD CONSTRAINT marketplace_report_fact_bounds CHECK(
  (canonical_fact IS NULL AND fact_start IS NULL AND fact_end IS NULL) OR
  (jsonb_typeof(canonical_fact)='array' AND jsonb_array_length(canonical_fact) BETWEEN 1 AND 4
    AND fact_start>0 AND fact_end>=fact_start AND fact_end-fact_start+1=jsonb_array_length(canonical_fact)));
CREATE INDEX marketplace_report_fact_page ON marketplace_report_row(report_id,fact_end)
  WHERE canonical_fact IS NOT NULL;
COMMENT ON COLUMN marketplace_report_row.canonical_fact IS 'owner=marketplace; up to four immutable independently dated financial facts per source row; contiguous publication fact numbers; editions replace the same cohort';

--changeset repricer:marketplace-realization-facts-002 splitStatements:true
ALTER TABLE marketplace_report_row DROP CONSTRAINT marketplace_report_fact_bounds;
ALTER TABLE marketplace_report_row ADD CONSTRAINT marketplace_report_fact_bounds CHECK(
  (canonical_fact IS NULL AND fact_start IS NULL AND fact_end IS NULL) OR
  (canonical_fact IS NOT NULL AND fact_start IS NOT NULL AND fact_end IS NOT NULL
    AND jsonb_typeof(canonical_fact)='array' AND jsonb_array_length(canonical_fact) BETWEEN 1 AND 4
    AND fact_start>0 AND fact_end>=fact_start AND fact_end-fact_start+1=jsonb_array_length(canonical_fact)));
