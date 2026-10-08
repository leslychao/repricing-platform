--liquibase formatted sql

--changeset automation:quantity-comparison-1
CREATE TABLE automation_quantity_comparison (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, id uuid NOT NULL,
  decision_id uuid NOT NULL, created_at timestamptz NOT NULL, result jsonb NOT NULL,
  PRIMARY KEY(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,decision_id)
    REFERENCES automation_decision(organization_id,account_id,id),
  CHECK(pg_column_size(result)<=16384)
);
CREATE INDEX automation_quantity_decision ON automation_quantity_comparison
  (organization_id,account_id,decision_id,created_at DESC,id);
COMMENT ON TABLE automation_quantity_comparison IS 'owner=automation.QuantityComparisonService; grain=one conditional kept-purchase comparison; business_key=id; repeat=decision default or request idempotency; history=immutable result and referenced decision inputs; aggregation=never additive; retention=with referenced decision';
ALTER TABLE automation_quantity_comparison ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_quantity_comparison FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_quantity_scope ON automation_quantity_comparison USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
GRANT SELECT,INSERT ON automation_quantity_comparison TO repricer_api,repricer_worker;

--changeset automation:quantity-comparison-metadata-2
COMMENT ON TABLE automation_quantity_comparison IS 'owner=automation.QuantityComparisonService; scope=account; grain=one conditional kept-purchase comparison; business_key=organization,account,id; repeat=decision default or request idempotency; history=immutable result and referenced decision inputs; aggregation=never additive; retention=with referenced decision';
