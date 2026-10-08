--liquibase formatted sql

--changeset automation:economics-projection-1
CREATE TABLE automation_economics_projection (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, id uuid NOT NULL,
  offer_id uuid NOT NULL, calculated_at timestamptz NOT NULL, basis jsonb NOT NULL,
  selected_candidate uuid, result_status text NOT NULL, result_reason text NOT NULL,
  price numeric, cost numeric, profit numeric, margin numeric,
  current_price numeric, current_profit numeric,
  PRIMARY KEY(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,id)
    REFERENCES automation_decision(organization_id,account_id,id),
  CHECK(pg_column_size(basis)<=16384)
);
CREATE INDEX automation_economics_offer ON automation_economics_projection
  (organization_id,account_id,offer_id,calculated_at DESC,id);
COMMENT ON TABLE automation_economics_projection IS 'owner=automation.CurrentEconomicsService; grain=one saved decision; business_key=decision id; repeat=same decision transaction; history=immutable; aggregation=never additive across decisions or different commercial scopes; retention=with referenced decision';
ALTER TABLE automation_economics_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_economics_projection FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_economics_projection_scope ON automation_economics_projection USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

--changeset automation:economics-projection-metadata-2
COMMENT ON TABLE automation_economics_projection IS 'owner=automation.CurrentEconomicsService; scope=account; grain=one saved decision; business_key=organization,account,decision id; repeat=same decision transaction; history=immutable; aggregation=never additive across decisions or commercial scopes; retention=with referenced decision';

--changeset automation:economics-projection-current-margin-3
ALTER TABLE automation_economics_projection ADD COLUMN current_margin numeric;

--changeset automation:economics-projection-price-index-4
ALTER TABLE automation_economics_projection ADD COLUMN price_index numeric;
ALTER TABLE automation_economics_projection ADD COLUMN price_comparison jsonb
  CHECK(price_comparison IS NULL OR octet_length(price_comparison::text)<=32768);
COMMENT ON COLUMN automation_economics_projection.price_comparison IS 'Immutable comparable buyer-price/reference evidence; no official supplier index; public buyer inputs and whole reference freshness are independent of financial inputs';
