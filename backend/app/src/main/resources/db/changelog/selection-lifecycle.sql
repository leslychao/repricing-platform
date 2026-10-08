--liquibase formatted sql
--changeset repricer:app-selection-references-001
CREATE TABLE app_selection_reference (
  organization_id uuid NOT NULL,
  account_id uuid,
  scope_key text GENERATED ALWAYS AS(COALESCE(account_id::text,'organization')) STORED,
  selection_id uuid NOT NULL,
  owner_type text NOT NULL CHECK(owner_type IN ('REPORT','ASSIGNMENT_BATCH')),
  owner_id uuid NOT NULL,
  PRIMARY KEY(selection_id,owner_type,owner_id),
  FOREIGN KEY(organization_id,scope_key,selection_id)
    REFERENCES app_selection(organization_id,scope_key,id)
);
COMMENT ON TABLE app_selection_reference IS 'owner=app.files; scope=exact organization/account; grain=unfinished consumer of immutable selected rows; key=selection,owner type,owner ID; repeat=idempotent retain/release; history=until consumer terminal';
ALTER TABLE app_selection_reference ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_selection_reference FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON app_selection_reference USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true') WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true');
GRANT SELECT,INSERT,DELETE ON app_selection_reference TO repricer_api,repricer_worker;
INSERT INTO app_selection_reference(organization_id,account_id,selection_id,owner_type,owner_id)
  SELECT organization_id,account_id,selection_id,'REPORT',id FROM app_report
  WHERE state IN ('PENDING','PREPARING');
--changeset repricer:app-selection-retention-001
ALTER TABLE app_selection ADD COLUMN rows_deletion_after timestamptz;
GRANT UPDATE(rows_deletion_after) ON app_selection TO repricer_worker;
GRANT DELETE ON app_selection_row TO repricer_worker;
--changeset repricer:app-file-metadata-retention-001 splitStatements:false
CREATE FUNCTION repricer_file_metadata_retention_scopes()
RETURNS TABLE(organization_id uuid,account_id uuid,subject_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
  SELECT DISTINCT ON (candidate.organization_id,COALESCE(candidate.account_id::text,'organization'))
    candidate.organization_id,candidate.account_id,candidate.subject_id FROM (
      SELECT organization_id,account_id,subject_id FROM public.app_selection s
        WHERE s.expires_at<=clock_timestamp() AND (s.rows_deletion_after IS NULL
          OR EXISTS(SELECT 1 FROM public.app_selection_row r WHERE r.selection_id=s.id))
      UNION ALL SELECT organization_id,account_id,subject_id FROM public.app_report r
        WHERE r.state='READY' AND r.expires_at<=clock_timestamp()
          AND EXISTS(SELECT 1 FROM public.platform_file_reference f
            WHERE f.file_id=r.file_id AND f.owner_type='REPORT' AND f.owner_id=r.id)
      UNION ALL SELECT organization_id,account_id,subject_id FROM public.app_import i
        WHERE i.created_at<clock_timestamp()-interval '7 days'
          AND i.state NOT IN ('DRAFT','PREPARING')
          AND (i.temporary_expired_at IS NULL OR EXISTS(
            SELECT 1 FROM public.app_import_row r WHERE r.import_id=i.id))
    ) candidate
  ORDER BY candidate.organization_id,COALESCE(candidate.account_id::text,'organization') LIMIT 100
$$;
REVOKE ALL ON FUNCTION repricer_file_metadata_retention_scopes() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_file_metadata_retention_scopes() TO repricer_worker;
