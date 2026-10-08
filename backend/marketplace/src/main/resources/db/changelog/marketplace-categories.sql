--liquibase formatted sql

--changeset marketplace:categories-1
CREATE TABLE marketplace_category (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, id uuid NOT NULL,
  external_id varchar(80) NOT NULL, kind varchar(16) NOT NULL CHECK(kind IN ('CATEGORY','TYPE')),
  name varchar(512) NOT NULL, parent_id uuid, ancestors uuid[] NOT NULL,
  disabled boolean NOT NULL, current boolean NOT NULL, revision bigint NOT NULL CHECK(revision>0),
  publication_id uuid NOT NULL, raw_file_id uuid NOT NULL, observed_at timestamptz NOT NULL,
  valid_until timestamptz NOT NULL,
  PRIMARY KEY(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,kind,external_id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id),
  FOREIGN KEY(organization_id,account_id,parent_id) REFERENCES marketplace_category(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,publication_id) REFERENCES marketplace_sync_run(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,raw_file_id) REFERENCES platform_file(organization_id,account_id,id),
  CHECK(cardinality(ancestors)<=63)
);
COMMENT ON TABLE marketplace_category IS 'owner=marketplace; scope=exact account; grain=one official category or type identity with confirmed ancestors; key=account/kind/external id; repeat=complete publication run, semantic revision; history=retained raw tree and last confirmed identity; aggregation=none; retention=while assignments or catalog reference identity';
CREATE TABLE marketplace_category_stage (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, run_id uuid NOT NULL,
  sequence integer NOT NULL, parent_sequence integer, external_id varchar(80) NOT NULL,
  kind varchar(16) NOT NULL CHECK(kind IN ('CATEGORY','TYPE')), name varchar(512) NOT NULL,
  disabled boolean NOT NULL,
  PRIMARY KEY(organization_id,account_id,run_id,sequence),
  FOREIGN KEY(organization_id,account_id,run_id) REFERENCES marketplace_sync_run(organization_id,account_id,id),
  CHECK(sequence BETWEEN 1 AND 1000000),CHECK(parent_sequence IS NULL OR parent_sequence<sequence)
);
COMMENT ON TABLE marketplace_category_stage IS 'owner=marketplace; scope=exact account and sync run; grain=one bounded parsed category node; key=run/sequence; repeat=clear unpublished staging then reparse retained raw; history=temporary staging only, publication retains raw; aggregation=none; retention=cleanup after terminal traversal';
ALTER TABLE marketplace_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_category FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_category_scope ON marketplace_category USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
ALTER TABLE marketplace_category_stage ENABLE ROW LEVEL SECURITY;
ALTER TABLE marketplace_category_stage FORCE ROW LEVEL SECURITY;
CREATE POLICY marketplace_category_stage_scope ON marketplace_category_stage USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

--changeset marketplace:categories-stage-retention-contract
COMMENT ON TABLE marketplace_category_stage IS 'owner=marketplace; scope=exact account and sync run; grain=one bounded parsed official node; key=run/sequence; repeat=immutable raw reparse inserts identical nodes only; history=temporary staging, publication retains raw; aggregation=none; retention=terminal source staging older than seven days, bounded cleanup';
