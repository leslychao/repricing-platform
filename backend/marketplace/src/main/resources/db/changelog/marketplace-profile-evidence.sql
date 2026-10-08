--liquibase formatted sql

--changeset repricer:marketplace-profile-evidence-001 splitStatements:true
CREATE INDEX marketplace_external_call_raw_scope
  ON marketplace_external_call(organization_id,account_id,raw_file_id,method)
  WHERE raw_file_id IS NOT NULL;
CREATE INDEX marketplace_profile_evidence_scope
  ON marketplace_profile(organization_id,account_id,method,evidence_file_id)
  WHERE evidence_file_id IS NOT NULL;
