--liquibase formatted sql
--changeset repricer:app-report-upload-001
ALTER TABLE app_report ADD COLUMN prepared_file_id uuid;
ALTER TABLE app_report ADD FOREIGN KEY(organization_id,account_id,prepared_file_id)
  REFERENCES platform_file(organization_id,account_id,id);
COMMENT ON COLUMN app_report.prepared_file_id IS 'Durable multipart recovery pointer; never a downloadable file until the report is atomically published';
