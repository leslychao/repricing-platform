--liquibase formatted sql
--changeset repricer:app-job-references-001
ALTER TABLE platform_job ADD CONSTRAINT platform_job_exact_scope
  UNIQUE(organization_id,scope_key,id);
ALTER TABLE app_import ADD CONSTRAINT app_import_validation_job_scope
  FOREIGN KEY(organization_id,account_id,validation_job_id)
  REFERENCES platform_job(organization_id,account_id,id);
ALTER TABLE app_import ADD CONSTRAINT app_import_apply_job_scope
  FOREIGN KEY(organization_id,account_id,apply_job_id)
  REFERENCES platform_job(organization_id,account_id,id);
ALTER TABLE app_report ADD COLUMN job_scope_key text GENERATED ALWAYS AS
  (organization_id::text||':'||COALESCE(account_id::text,'organization')) STORED;
ALTER TABLE app_report ADD CONSTRAINT app_report_operation_scope
  FOREIGN KEY(organization_id,job_scope_key,operation_id)
  REFERENCES platform_job(organization_id,scope_key,id);
