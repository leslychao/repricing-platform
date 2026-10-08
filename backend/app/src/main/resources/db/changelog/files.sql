--liquibase formatted sql
--changeset repricer:app-files-001
CREATE TABLE app_import (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  subject_id uuid NOT NULL,
  kind text NOT NULL,
  name text NOT NULL CHECK(length(name)<=160),
  file_id uuid NOT NULL,
  content_hash char(64) NOT NULL,
  options jsonb NOT NULL CHECK(octet_length(options::text)<=4096),
  options_hash char(64) NOT NULL,
  state text NOT NULL CHECK(state IN ('DRAFT','VALIDATED','PREPARING','COMMITTED','INVALID','FAILED','CANCELLED')),
  revision bigint NOT NULL DEFAULT 1,
  parsed boolean NOT NULL DEFAULT false,
  staged_through integer NOT NULL DEFAULT 0,
  total_rows integer NOT NULL DEFAULT 0 CHECK(total_rows BETWEEN 0 AND 100000),
  valid_rows integer NOT NULL DEFAULT 0,
  error_rows integer NOT NULL DEFAULT 0,
  validation_job_id uuid,
  apply_job_id uuid,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  committed_at timestamptz,
  UNIQUE(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,kind,content_hash,options_hash),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id),
  FOREIGN KEY(organization_id,account_id,file_id) REFERENCES platform_file(organization_id,account_id,id)
);
COMMENT ON TABLE app_import IS 'owner=app.files; scope=account; grain=immutable bytes and options submitted to one business owner; key=scope,kind,content hash,options hash; repeat=previous set; history=durable lifecycle and preserved publication receipt';
CREATE TABLE app_import_row (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  import_id uuid NOT NULL,
  row_number integer NOT NULL CHECK(row_number BETWEEN 0 AND 100001),
  values jsonb NOT NULL CHECK(octet_length(values::text)<=65536),
  errors jsonb NOT NULL CHECK(octet_length(errors::text)<=1024),
  valid boolean NOT NULL,
  PRIMARY KEY(import_id,row_number),
  FOREIGN KEY(organization_id,account_id,import_id)
    REFERENCES app_import(organization_id,account_id,id)
);
COMMENT ON TABLE app_import_row IS 'owner=app.files; scope=account; grain=one immutable parsed source row; key=import,row ordinal; repeat=identical raw bytes; history=staged until whole-set validation, no business effect';
CREATE INDEX app_import_list ON app_import(organization_id,account_id,created_at DESC,id);
--changeset repricer:app-files-rls splitStatements:false
DO $$
DECLARE target text;
BEGIN
  FOREACH target IN ARRAY ARRAY['app_import','app_import_row'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',target);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',target);
    EXECUTE format($policy$CREATE POLICY tenant_scope ON %I USING (
      organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
      AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
      AND current_setting('app.authorized',true)='true') WITH CHECK (
      organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
      AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
      AND current_setting('app.authorized',true)='true')$policy$,target);
  END LOOP;
END;
$$;
--changeset repricer:app-exports-001
CREATE TABLE app_selection (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  subject_id uuid NOT NULL,
  resource text NOT NULL,
  query jsonb NOT NULL CHECK(octet_length(query::text)<=65536),
  columns jsonb NOT NULL CHECK(octet_length(columns::text)<=32768),
  permissions jsonb NOT NULL CHECK(octet_length(permissions::text)<=4096),
  total_rows bigint NOT NULL DEFAULT 0 CHECK(total_rows BETWEEN 0 AND 1000000),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz NOT NULL,
  UNIQUE(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE app_selection IS 'owner=app.files; scope=account; grain=frozen authorized selection query and statement snapshot; key=request UUID; repeat=existing set; history=seven days or while report/bulk operation references it';
CREATE TABLE app_selection_row (
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  selection_id uuid NOT NULL,
  ordinal bigint NOT NULL CHECK(ordinal BETWEEN 1 AND 1000001),
  canonical_id uuid NOT NULL,
  cells jsonb NOT NULL CHECK(jsonb_typeof(cells)='array' AND jsonb_array_length(cells)<=100
    AND octet_length(cells::text)<=33554432),
  PRIMARY KEY(selection_id,ordinal),
  UNIQUE(selection_id,canonical_id),
  FOREIGN KEY(organization_id,account_id,selection_id)
    REFERENCES app_selection(organization_id,account_id,id)
);
COMMENT ON TABLE app_selection_row IS 'owner=app.files; scope=account; grain=one immutable selected identity and exact projected values; key=selection,canonical ID; repeat=whole creation transaction; history=parent selection';
CREATE TABLE app_report (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  subject_id uuid NOT NULL,
  selection_id uuid NOT NULL,
  kind text NOT NULL,
  format text NOT NULL CHECK(format IN ('CSV','XLSX')),
  state text NOT NULL CHECK(state IN ('PENDING','PREPARING','READY','FAILED')),
  file_id uuid,
  operation_id uuid,
  revision bigint NOT NULL DEFAULT 1,
  reason text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  expires_at timestamptz,
  UNIQUE(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,selection_id)
    REFERENCES app_selection(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,file_id)
    REFERENCES platform_file(organization_id,account_id,id)
);
COMMENT ON TABLE app_report IS 'owner=app.files; scope=account; grain=one requested artifact over immutable selection; key=request UUID; repeat=one report; history=metadata and basis retained, ready artifact 30 days';
CREATE INDEX app_report_list ON app_report(organization_id,account_id,created_at DESC,id);
--changeset repricer:app-exports-rls splitStatements:false
DO $$
DECLARE target text;
BEGIN
  FOREACH target IN ARRAY ARRAY['app_selection','app_selection_row','app_report'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',target);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',target);
    EXECUTE format($policy$CREATE POLICY tenant_scope ON %I USING (
      organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
      AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
      AND current_setting('app.authorized',true)='true') WITH CHECK (
      organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
      AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
      AND current_setting('app.authorized',true)='true')$policy$,target);
    EXECUTE format('GRANT SELECT,INSERT ON %I TO repricer_api,repricer_worker',target);
  END LOOP;
END;
$$;
--changeset repricer:app-exports-grants
GRANT UPDATE(total_rows) ON app_selection TO repricer_api,repricer_worker;
GRANT UPDATE(state,file_id,operation_id,revision,reason,expires_at) ON app_report TO repricer_api,repricer_worker;
--changeset repricer:app-exports-coverage
ALTER TABLE app_selection ADD COLUMN data_status text NOT NULL DEFAULT 'AS_PUBLISHED'
  CHECK(data_status IN ('COMPLETE','INCOMPLETE','AS_PUBLISHED'));

--changeset repricer:app-import-validation-editions-001
ALTER TABLE app_import DROP CONSTRAINT app_import_state_check;
ALTER TABLE app_import ADD CONSTRAINT app_import_state_check
  CHECK(state IN ('DRAFT','VALIDATED','PREPARING','COMMITTED','INVALID','FAILED','STALE','CANCELLED'));
ALTER TABLE app_import ADD COLUMN reason text CHECK(length(reason)<=128);
ALTER TABLE app_import ADD COLUMN validation_id uuid;
ALTER TABLE app_import ADD COLUMN validation_edition integer NOT NULL DEFAULT 1
  CHECK(validation_edition>0);
CREATE TABLE app_import_validation (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  import_id uuid NOT NULL,
  edition integer NOT NULL CHECK(edition>0),
  state text NOT NULL CHECK(state IN
    ('DRAFT','VALIDATED','PREPARING','COMMITTED','INVALID','FAILED','STALE','CANCELLED')),
  reason text CHECK(length(reason)<=128),
  total_rows integer NOT NULL DEFAULT 0,
  valid_rows integer NOT NULL DEFAULT 0,
  error_rows integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz,
  UNIQUE(organization_id,account_id,id),
  UNIQUE(import_id,edition),
  FOREIGN KEY(organization_id,account_id,import_id)
    REFERENCES app_import(organization_id,account_id,id)
);
INSERT INTO app_import_validation(id,organization_id,account_id,import_id,edition,state,
  total_rows,valid_rows,error_rows,created_at,completed_at)
SELECT id,organization_id,account_id,id,1,state,total_rows,valid_rows,error_rows,created_at,
  CASE WHEN state NOT IN ('DRAFT','VALIDATED','PREPARING') THEN clock_timestamp() END
FROM app_import;
UPDATE app_import SET validation_id=id;
ALTER TABLE app_import ALTER COLUMN validation_id SET NOT NULL;
ALTER TABLE app_import ADD FOREIGN KEY(organization_id,account_id,validation_id)
  REFERENCES app_import_validation(organization_id,account_id,id) DEFERRABLE INITIALLY DEFERRED;
COMMENT ON TABLE app_import_validation IS 'owner=app.files; scope=account; grain=one validation/publication edition over immutable parsed import rows; key=import,edition; repeat=same owner target UUID; history=old terminal editions and business staging remain unchanged';
ALTER TABLE app_import_validation ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_import_validation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON app_import_validation USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true') WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true');
GRANT SELECT,INSERT ON app_import_validation TO repricer_api,repricer_worker;
GRANT UPDATE(state,reason,total_rows,valid_rows,error_rows,completed_at)
  ON app_import_validation TO repricer_api,repricer_worker;
--changeset repricer:app-import-temporary-expiry-001
ALTER TABLE app_import ADD COLUMN temporary_expired_at timestamptz;
GRANT DELETE ON app_import_row TO repricer_api,repricer_worker;
