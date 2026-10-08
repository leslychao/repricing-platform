--liquibase formatted sql
--changeset repricer:app-organization-exports-001 splitStatements:false
DO $$
DECLARE target text;
BEGIN
  FOREACH target IN ARRAY ARRAY['app_selection','app_selection_row','app_report'] LOOP
    EXECUTE format('ALTER TABLE %I ALTER COLUMN account_id DROP NOT NULL',target);
    EXECUTE format('ALTER TABLE %I ADD COLUMN scope_key text GENERATED ALWAYS AS
      (COALESCE(account_id::text,''organization'')) STORED',target);
    EXECUTE format('DROP POLICY tenant_scope ON %I',target);
    EXECUTE format($policy$CREATE POLICY tenant_scope ON %I USING (
      organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
      AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
      AND current_setting('app.authorized',true)='true') WITH CHECK (
      organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
      AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
      AND current_setting('app.authorized',true)='true')$policy$,target);
  END LOOP;
END;
$$;
--changeset repricer:app-organization-exports-002
ALTER TABLE app_selection ADD CONSTRAINT app_selection_scope_key UNIQUE(organization_id,scope_key,id);
ALTER TABLE app_selection ADD CONSTRAINT app_selection_organization
  FOREIGN KEY(organization_id) REFERENCES access_organization(id);
ALTER TABLE app_selection_row ADD CONSTRAINT app_selection_row_exact_scope
  FOREIGN KEY(organization_id,scope_key,selection_id)
  REFERENCES app_selection(organization_id,scope_key,id);
ALTER TABLE app_report ADD CONSTRAINT app_report_selection_exact_scope
  FOREIGN KEY(organization_id,scope_key,selection_id)
  REFERENCES app_selection(organization_id,scope_key,id);
ALTER TABLE app_report ADD CONSTRAINT app_report_file_exact_scope
  FOREIGN KEY(organization_id,scope_key,file_id)
  REFERENCES platform_file(organization_id,scope_key,id);
ALTER TABLE app_report ADD CONSTRAINT app_report_upload_exact_scope
  FOREIGN KEY(organization_id,scope_key,prepared_file_id)
  REFERENCES platform_file(organization_id,scope_key,id);
COMMENT ON TABLE app_selection IS 'owner=app.files; scope=exact organization or account; grain=frozen authorized query and statement snapshot; key=request UUID; repeat=existing set; history=seven days or while report/bulk operation references it';
COMMENT ON TABLE app_selection_row IS 'owner=app.files; scope=exact organization or account; grain=one immutable selected identity and projected values; key=selection,canonical ID; repeat=whole creation transaction; history=parent selection';
COMMENT ON TABLE app_report IS 'owner=app.files; scope=exact organization or account; grain=artifact over immutable selection; key=request UUID; repeat=one report; history=metadata and basis retained, ready artifact 30 days';
