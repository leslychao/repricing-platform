--liquibase formatted sql

--changeset economics:1
CREATE TABLE economics_cost_head (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, offer_id uuid NOT NULL,
  revision bigint NOT NULL CHECK(revision>=0),
  PRIMARY KEY(organization_id,account_id,offer_id),
  FOREIGN KEY(organization_id,account_id,offer_id)
    REFERENCES marketplace_offer(organization_id,account_id,id)
);
COMMENT ON TABLE economics_cost_head IS 'owner=economics; scope=account; grain=one current cost scale per Offer; business_key=organization,account,offer; repeat=expectedRevision; history=immutable interval editions; aggregation=none; retention=while referenced';
CREATE TABLE economics_cost_interval (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, offer_id uuid NOT NULL,
  revision bigint NOT NULL CHECK(revision>0), valid_from date NOT NULL, valid_until date,
  amount numeric(38,12) NOT NULL CHECK(amount>=0),
  extra_expense numeric(38,12) NOT NULL CHECK(extra_expense>=0), author_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,offer_id,revision,valid_from),
  FOREIGN KEY(organization_id,account_id,offer_id)
    REFERENCES economics_cost_head(organization_id,account_id,offer_id),
  CHECK(valid_until IS NULL OR valid_until>valid_from)
);
COMMENT ON TABLE economics_cost_interval IS 'owner=economics; scope=account; grain=one interval of one immutable Offer cost scale; business_key=organization,account,offer,revision,start; repeat=scale publication; history=immutable; aggregation=not additive; retention=while financial basis referenced';
CREATE TABLE economics_tax_head (
  organization_id uuid NOT NULL, account_id uuid NOT NULL,
  revision bigint NOT NULL CHECK(revision>=0), PRIMARY KEY(organization_id,account_id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE economics_tax_head IS 'owner=economics; scope=account; grain=one current tax scale; business_key=organization,account; repeat=expectedRevision; history=immutable scales; aggregation=none; retention=account lifetime';
CREATE TABLE economics_tax_interval (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, revision bigint NOT NULL,
  valid_from date NOT NULL, valid_until date, rate numeric(38,18) NOT NULL,
  author_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,revision,valid_from),
  FOREIGN KEY(organization_id,account_id) REFERENCES economics_tax_head(organization_id,account_id),
  CHECK(rate>=0 AND rate<1), CHECK(valid_until IS NULL OR valid_until>valid_from)
);
COMMENT ON TABLE economics_tax_interval IS 'owner=economics; scope=account; grain=one interval in tax scale; business_key=organization,account,revision,start; repeat=scale publication; history=immutable; aggregation=none; retention=while source sales referenced';
CREATE TABLE economics_input_receipt (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, id uuid NOT NULL,
  author_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE economics_input_receipt IS 'owner=economics; scope=account; grain=one atomically published input set; business_key=organization,account,inputSetId; repeat=return receipt; history=immutable; aggregation=none; retention=while input history retained';
CREATE TABLE economics_recognition_guard (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, source_id uuid NOT NULL,
  component varchar(128) NOT NULL, PRIMARY KEY(organization_id,account_id,source_id,component),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE economics_recognition_guard IS 'owner=economics; scope=account; grain=one canonical source component guard; business_key=organization,account,source,component; repeat=same guard; history=none; aggregation=none; retention=source lifetime';
CREATE TABLE economics_financial_event (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, id uuid NOT NULL,
  source_id uuid NOT NULL, component varchar(128) NOT NULL, source_revision bigint NOT NULL,
  source_digest varchar(128) NOT NULL, offer_id uuid, accounting_date date NOT NULL,
  income numeric(38,12) NOT NULL, cost numeric(38,12) NOT NULL,
  expenses numeric(38,12) NOT NULL, tax numeric(38,12) NOT NULL, profit numeric(38,12) NOT NULL,
  complete boolean NOT NULL, current_revision boolean NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,source_id,component,source_revision),
  FOREIGN KEY(organization_id,account_id,source_id,component)
    REFERENCES economics_recognition_guard(organization_id,account_id,source_id,component),
  FOREIGN KEY(organization_id,account_id,offer_id)
    REFERENCES marketplace_offer(organization_id,account_id,id)
);
CREATE UNIQUE INDEX economics_financial_current ON economics_financial_event
  (organization_id,account_id,source_id,component) WHERE current_revision;
CREATE INDEX economics_financial_month ON economics_financial_event
  (organization_id,account_id,accounting_date) WHERE current_revision;
COMMENT ON TABLE economics_financial_event IS 'owner=economics; scope=account; grain=one recognized source component revision; business_key=organization,account,source,component,revision; repeat=same digest otherwise conflict; history=immutable amounts current pointer; aggregation=current edition by date and offer without joins; retention=financial history';

CREATE TABLE economics_safety_envelope (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, revision bigint NOT NULL,
  settings jsonb NOT NULL CHECK(jsonb_typeof(settings)='object'),
  current_revision boolean NOT NULL, author_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,revision),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
CREATE UNIQUE INDEX economics_safety_current ON economics_safety_envelope
  (organization_id,account_id) WHERE current_revision;
COMMENT ON TABLE economics_safety_envelope IS 'owner=economics; scope=account; grain=one validated Envelope publication; business_key=organization,account,revision; repeat=expectedRevision; history=immutable typed settings; aggregation=none; retention=while decisions referenced';
CREATE TABLE economics_risk_permission (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, id uuid NOT NULL, run_id uuid NOT NULL,
  revision bigint NOT NULL, starts_at timestamptz NOT NULL, ends_at timestamptz NOT NULL,
  kept_profit_floor numeric(38,12) NOT NULL, loss_budget numeric(38,12),
  bounded_quantity numeric(38,12), external_boundary_evidence text, scope_digest varchar(128) NOT NULL,
  status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','REVOKED','CLOSED')),
  author_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id),
  CHECK(ends_at>starts_at), CHECK(kept_profit_floor>=0 OR
    (loss_budget IS NOT NULL AND loss_budget>0 AND bounded_quantity IS NOT NULL
     AND bounded_quantity>0 AND external_boundary_evidence IS NOT NULL))
);
CREATE UNIQUE INDEX economics_risk_one_active ON economics_risk_permission
  (organization_id,account_id,run_id) WHERE status='ACTIVE';
COMMENT ON TABLE economics_risk_permission IS 'owner=economics; scope=account; grain=one exact temporary economic permission; business_key=organization,account,permissionId; repeat=idempotent caller command; history=terms immutable status/revision mutable; aggregation=no pooling; retention=while episode risk exists';
CREATE TABLE economics_risk_usage (
  organization_id uuid NOT NULL, account_id uuid NOT NULL, permission_id uuid NOT NULL,
  obligation_id uuid NOT NULL, recognized_loss numeric(38,12) NOT NULL CHECK(recognized_loss>=0),
  potential_loss numeric(38,12) NOT NULL CHECK(potential_loss>=0), revision bigint NOT NULL,
  PRIMARY KEY(organization_id,account_id,permission_id,obligation_id),
  FOREIGN KEY(organization_id,account_id,permission_id)
    REFERENCES economics_risk_permission(organization_id,account_id,id)
);
COMMENT ON TABLE economics_risk_usage IS 'owner=economics; scope=account; grain=one nonduplicated liability portion; business_key=organization,account,permission,obligation; repeat=same obligation same amount; history=revision checked reconciliation; aggregation=recognized plus remaining once per permission; retention=financial history';

CREATE TABLE economics_resource_guard (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,pool_id uuid NOT NULL,
  PRIMARY KEY(organization_id,account_id,pool_id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE economics_resource_guard IS 'owner=economics; scope=account; grain=one quantity planning lock per stock pool; business_key=organization,account,pool; repeat=same guard; history=none; aggregation=none; retention=pool lifetime';
CREATE TABLE economics_resource_hold (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,command_id uuid NOT NULL,pool_id uuid NOT NULL,
  quantity numeric(38,12) NOT NULL CHECK(quantity>0),state varchar(16) NOT NULL,
  stock_revision bigint NOT NULL,PRIMARY KEY(organization_id,account_id,command_id,pool_id),
  FOREIGN KEY(organization_id,account_id,pool_id)
    REFERENCES economics_resource_guard(organization_id,account_id,pool_id),
  CHECK(state IN ('HELD','CONVERTED','RELEASED'))
);
COMMENT ON TABLE economics_resource_hold IS 'owner=economics; scope=account; grain=one command quantity purpose per stock pool; business_key=organization,account,command,pool; repeat=same quantity; history=explicit state; aggregation=HELD once; retention=command history';
CREATE TABLE economics_resource_obligation (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,pool_id uuid NOT NULL,
  source_command_id uuid NOT NULL,quantity numeric(38,12) NOT NULL CHECK(quantity>0),
  state varchar(16) NOT NULL,reflected_in_stock boolean NOT NULL,
  PRIMARY KEY(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,source_command_id,pool_id),
  FOREIGN KEY(organization_id,account_id,pool_id)
    REFERENCES economics_resource_guard(organization_id,account_id,pool_id),
  CHECK(state IN ('EXPECTED','CONFIRMED','DISPUTED','SETTLED','CANCELLED'))
);
COMMENT ON TABLE economics_resource_obligation IS 'owner=economics; scope=account; grain=one proven external quantity obligation; business_key=organization,account,id; repeat=source command and pool; history=explicit state; aggregation=unsettled unreflected quantities once; retention=obligation history';

--changeset economics:2
ALTER TABLE economics_cost_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_cost_head FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_cost_head_scope ON economics_cost_head USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_cost_interval ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_cost_interval FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_cost_interval_scope ON economics_cost_interval USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_tax_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_tax_head FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_tax_head_scope ON economics_tax_head USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_tax_interval ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_tax_interval FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_tax_interval_scope ON economics_tax_interval USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_input_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_input_receipt FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_input_receipt_scope ON economics_input_receipt USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_recognition_guard ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_recognition_guard FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_recognition_guard_scope ON economics_recognition_guard USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_financial_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_financial_event FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_financial_event_scope ON economics_financial_event USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_safety_envelope ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_safety_envelope FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_safety_envelope_scope ON economics_safety_envelope USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_risk_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_risk_permission FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_risk_permission_scope ON economics_risk_permission USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_risk_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_risk_usage FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_risk_usage_scope ON economics_risk_usage USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_resource_guard ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_resource_guard FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_resource_guard_scope ON economics_resource_guard USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_resource_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_resource_hold FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_resource_hold_scope ON economics_resource_hold USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE economics_resource_obligation ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_resource_obligation FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_resource_obligation_scope ON economics_resource_obligation USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);


--changeset economics:3
CREATE TABLE economics_import (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,
 kind varchar(24) NOT NULL CHECK(kind IN ('COSTS','SELLER_COSTS','TAX')),
 state varchar(16) NOT NULL CHECK(state IN ('STAGING','PREPARING','PREPARED','APPLIED')),
 author_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),applied_at timestamptz,
 PRIMARY KEY(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE economics_import IS 'owner=economics; scope=account; grain=one atomic typed accounting input set; business_key=organization,account,importId; repeat=same id and kind; history=immutable preparation then one publish; aggregation=none; retention=source history';
CREATE TABLE economics_import_row (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,import_id uuid NOT NULL,target_id uuid NOT NULL,
 valid_from date NOT NULL,valid_until date,amount numeric(38,12),extra_expense numeric(38,12),
 rate numeric(38,18),expected_revision bigint NOT NULL,body_hash char(64) NOT NULL,
 PRIMARY KEY(organization_id,account_id,import_id,target_id,valid_from),
 FOREIGN KEY(organization_id,account_id,import_id) REFERENCES economics_import(organization_id,account_id,id),
 CHECK(valid_until IS NULL OR valid_until>valid_from),
 CHECK(amount IS NULL OR amount>=0),CHECK(extra_expense IS NULL OR extra_expense>=0),
 CHECK(rate IS NULL OR (rate>=0 AND rate<1))
);
CREATE INDEX economics_import_row_hash ON economics_import_row(organization_id,account_id,import_id,body_hash);
COMMENT ON TABLE economics_import_row IS 'owner=economics; scope=account; grain=one declared dated import change; business_key=organization,account,import,target,start; repeat=same bounded typed digest; history=immutable; aggregation=none; retention=import evidence';
CREATE TABLE economics_import_prepared (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,import_id uuid NOT NULL,target_id uuid NOT NULL,
 valid_from date NOT NULL,valid_until date,amount numeric(38,12),extra_expense numeric(38,12),rate numeric(38,18),
 PRIMARY KEY(organization_id,account_id,import_id,target_id,valid_from),
 FOREIGN KEY(organization_id,account_id,import_id) REFERENCES economics_import(organization_id,account_id,id),
 CHECK(valid_until IS NULL OR valid_until>valid_from)
);
COMMENT ON TABLE economics_import_prepared IS 'owner=economics; scope=account; grain=one interval in prepared unpublished scale; business_key=organization,account,import,target,start; repeat=prepared target marker; history=immutable; aggregation=none; retention=import evidence';
CREATE TABLE economics_import_target (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,import_id uuid NOT NULL,target_id uuid NOT NULL,
 expected_revision bigint NOT NULL,changed boolean NOT NULL,
 PRIMARY KEY(organization_id,account_id,import_id,target_id),
 FOREIGN KEY(organization_id,account_id,import_id) REFERENCES economics_import(organization_id,account_id,id)
);
COMMENT ON TABLE economics_import_target IS 'owner=economics; scope=account; grain=one prepared target with captured edition; business_key=organization,account,import,target; repeat=one immutable preparation; history=immutable; aggregation=publication CAS all targets; retention=import evidence';
ALTER TABLE economics_import ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_import FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_import_scope ON economics_import USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE economics_import_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_import_row FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_import_row_scope ON economics_import_row USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE economics_import_prepared ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_import_prepared FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_import_prepared_scope ON economics_import_prepared USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE economics_import_target ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_import_target FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_import_target_scope ON economics_import_target USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);


--changeset economics:4
ALTER TABLE economics_import DROP CONSTRAINT economics_import_kind_check;
ALTER TABLE economics_import ADD CONSTRAINT economics_import_kind_check CHECK(kind IN ('COSTS','SELLER_COSTS','TAX','PRICE_PARAMETERS'));
CREATE TABLE economics_offer_price_bounds (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,offer_id uuid NOT NULL,revision bigint NOT NULL,
 enabled boolean NOT NULL,minimum_price numeric(38,12),maximum_price numeric(38,12),
 current_revision boolean NOT NULL,author_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(organization_id,account_id,offer_id,revision),
 FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id),
 CHECK(NOT enabled OR minimum_price IS NOT NULL OR maximum_price IS NOT NULL),
 CHECK(minimum_price IS NULL OR minimum_price>0),CHECK(maximum_price IS NULL OR maximum_price>0),
 CHECK(minimum_price IS NULL OR maximum_price IS NULL OR minimum_price<=maximum_price)
);
CREATE UNIQUE INDEX economics_offer_price_current ON economics_offer_price_bounds(organization_id,account_id,offer_id) WHERE current_revision;
COMMENT ON TABLE economics_offer_price_bounds IS 'owner=economics; scope=account; grain=one explicit additional Offer price bound edition; business_key=organization,account,offer,revision; repeat=expectedRevision; history=immutable values; aggregation=intersection with Account envelope; retention=decision evidence';
CREATE TABLE economics_price_import_row (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,import_id uuid NOT NULL,offer_id uuid NOT NULL,
 expected_revision bigint NOT NULL,enabled boolean NOT NULL,minimum_price numeric(38,12),maximum_price numeric(38,12),body_hash char(64) NOT NULL,
 PRIMARY KEY(organization_id,account_id,import_id,offer_id),
 FOREIGN KEY(organization_id,account_id,import_id) REFERENCES economics_import(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
CREATE INDEX economics_price_import_hash ON economics_price_import_row(organization_id,account_id,import_id,body_hash);
COMMENT ON TABLE economics_price_import_row IS 'owner=economics; scope=account; grain=one verified unpublished Offer bound; business_key=organization,account,import,offer; repeat=typed digest preserving captured revision; history=immutable; aggregation=none; retention=import evidence';
ALTER TABLE economics_offer_price_bounds ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_offer_price_bounds FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_offer_price_bounds_scope ON economics_offer_price_bounds USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE economics_price_import_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_price_import_row FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_price_import_row_scope ON economics_price_import_row USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

--changeset economics:5
ALTER TABLE economics_risk_permission ADD COLUMN allowed_operations jsonb NOT NULL
  DEFAULT '{"values":[]}'::jsonb;
ALTER TABLE economics_risk_permission ADD CONSTRAINT economics_risk_permission_operations
  CHECK(jsonb_typeof(allowed_operations->'values')='array');
--changeset economics:6
ALTER TABLE economics_financial_event ADD COLUMN recognized_units numeric(38,12);
ALTER TABLE economics_financial_event ADD CONSTRAINT economics_financial_event_units
  CHECK(recognized_units IS NULL OR recognized_units>0);
--changeset economics:7
ALTER TABLE economics_financial_event ADD COLUMN unit_income numeric(44,18);
ALTER TABLE economics_financial_event ADD COLUMN unit_cost numeric(44,18);
ALTER TABLE economics_financial_event ADD COLUMN unit_profit numeric(44,18);
--changeset economics:8
CREATE TABLE economics_ledger_publication (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,
 cohort_key varchar(160) NOT NULL,generated_at timestamptz NOT NULL,source_digest varchar(128) NOT NULL,
 from_day date NOT NULL,until_day date NOT NULL,expected_rows bigint NOT NULL CHECK(expected_rows>=0),
 processed_rows bigint NOT NULL CHECK(processed_rows>=0),state varchar(16) NOT NULL,
 raw_file_id uuid NOT NULL,
 PRIMARY KEY(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id),
 FOREIGN KEY(organization_id,account_id,raw_file_id) REFERENCES platform_file(organization_id,account_id,id),
 CHECK(until_day>from_day AND processed_rows<=expected_rows),
 CHECK(state IN ('PREPARING','PUBLISHED','SUPERSEDED','OBSOLETE'))
);
CREATE UNIQUE INDEX economics_ledger_cohort ON economics_ledger_publication(organization_id,account_id,cohort_key) WHERE state='PUBLISHED';
COMMENT ON TABLE economics_ledger_publication IS 'owner=economics; scope=account; grain=immutable whole financial source cohort plus publication cursor; business_key=upstream publication; repeat=source digest and row cursor; history=all editions; aggregation=one current cohort; retention=financial evidence';
ALTER TABLE economics_ledger_publication ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_ledger_publication FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_ledger_publication_scope ON economics_ledger_publication USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);
ALTER TABLE economics_financial_event ADD COLUMN publication_id uuid;
ALTER TABLE economics_financial_event ADD CONSTRAINT economics_financial_publication_fk
 FOREIGN KEY(organization_id,account_id,publication_id) REFERENCES economics_ledger_publication(organization_id,account_id,id);
CREATE INDEX economics_financial_publication ON economics_financial_event(organization_id,account_id,publication_id);
--changeset economics:9
ALTER TABLE economics_risk_permission ADD COLUMN issuer_membership_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE economics_risk_permission ADD COLUMN issuer_account_revision bigint NOT NULL DEFAULT 0;

--changeset economics:10
CREATE TABLE economics_accounting_basis (
 organization_id uuid NOT NULL, account_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>0),
 PRIMARY KEY(organization_id,account_id),
 FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE economics_accounting_basis IS 'owner=economics; scope=account; grain=declaration generation; business_key=account; repeat=changed publications only; history=immutable generations referenced by ledger editions; aggregation=none; retention=account lifetime';
ALTER TABLE economics_accounting_basis ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_accounting_basis FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_accounting_basis_scope ON economics_accounting_basis USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);

--changeset economics:11 splitStatements:false
ALTER TABLE economics_ledger_publication ADD COLUMN source_publication_id uuid;
UPDATE economics_ledger_publication SET source_publication_id=id;
ALTER TABLE economics_ledger_publication ALTER COLUMN source_publication_id SET NOT NULL;
ALTER TABLE economics_ledger_publication ADD COLUMN accounting_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE economics_ledger_publication ADD COLUMN component_coverage jsonb NOT NULL DEFAULT '{}';
COMMENT ON COLUMN economics_ledger_publication.source_publication_id IS 'Immutable marketplace financial publication identity; separate from the economic edition rebuilt after declaration changes';
CREATE INDEX economics_ledger_source ON economics_ledger_publication(organization_id,account_id,source_publication_id);
ALTER TABLE economics_financial_event ALTER COLUMN income DROP NOT NULL;
ALTER TABLE economics_financial_event ALTER COLUMN cost DROP NOT NULL;
ALTER TABLE economics_financial_event ALTER COLUMN expenses DROP NOT NULL;
ALTER TABLE economics_financial_event ALTER COLUMN tax DROP NOT NULL;
ALTER TABLE economics_financial_event ALTER COLUMN profit DROP NOT NULL;
ALTER TABLE economics_financial_event ADD CONSTRAINT economics_complete_amounts
 CHECK(NOT complete OR (income IS NOT NULL AND cost IS NOT NULL AND expenses IS NOT NULL AND tax IS NOT NULL AND profit IS NOT NULL));
ALTER TABLE economics_financial_event ADD COLUMN accounting_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE economics_financial_event ADD COLUMN recognition_basis jsonb;
ALTER TABLE economics_financial_event ADD COLUMN original_order_line_id uuid;
ALTER TABLE economics_financial_event ADD COLUMN payment_amount numeric(38,12);
COMMENT ON COLUMN economics_financial_event.recognition_basis IS 'Typed immutable RecognitionBasis: canonical source, exact declared cost and tax editions and amounts; never user supplied arbitrary JSON';
COMMENT ON COLUMN economics_financial_event.original_order_line_id IS 'Canonical marketplace original composition identity when positively established, never reconstructed from SKU alone';
COMMENT ON COLUMN economics_financial_event.payment_amount IS 'Cash movement only; PAYMENT never enters income or profit';
DO $$ DECLARE constraint_name text; BEGIN
 SELECT conname INTO constraint_name FROM pg_constraint WHERE conrelid='economics_financial_event'::regclass AND contype='u';
 EXECUTE format('ALTER TABLE economics_financial_event DROP CONSTRAINT %I',constraint_name);
END $$;
ALTER TABLE economics_financial_event ADD CONSTRAINT economics_financial_source_edition
 UNIQUE(organization_id,account_id,source_id,component,source_revision,accounting_revision);
CREATE INDEX economics_financial_original_line ON economics_financial_event(organization_id,account_id,original_order_line_id) WHERE current_revision;

--changeset economics:12
ALTER TABLE economics_resource_obligation ADD COLUMN reflected_stock_revision bigint;
CREATE TABLE economics_resource_evidence (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,command_id uuid NOT NULL,pool_id uuid NOT NULL,
 source_revision bigint NOT NULL CHECK(source_revision>0),external_identity text NOT NULL,
 remaining_quantity numeric(38,12) NOT NULL CHECK(remaining_quantity>=0),
 admission_closed boolean NOT NULL,reflected_in_stock boolean NOT NULL,observed_at timestamptz NOT NULL,
 raw_file_id uuid NOT NULL,
 PRIMARY KEY(organization_id,account_id,command_id,pool_id,source_revision),
 FOREIGN KEY(organization_id,account_id,command_id,pool_id)
   REFERENCES economics_resource_hold(organization_id,account_id,command_id,pool_id),
 FOREIGN KEY(organization_id,account_id,raw_file_id) REFERENCES platform_file(organization_id,account_id,id)
);
COMMENT ON TABLE economics_resource_evidence IS 'owner=economics; scope=account; grain=exact external allocation observation; business_key=command,pool,source revision; repeat=identical evidence only; history=immutable proof; aggregation=latest complete exact scope; retention=obligation evidence';
ALTER TABLE economics_resource_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_resource_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_resource_evidence_scope ON economics_resource_evidence USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);
GRANT SELECT,INSERT ON economics_resource_evidence TO repricer_api,repricer_worker;

--changeset economics:13
ALTER TABLE economics_risk_usage ADD COLUMN admitted_maximum numeric(38,12);
UPDATE economics_risk_usage SET admitted_maximum=recognized_loss+potential_loss;
ALTER TABLE economics_risk_usage ALTER COLUMN admitted_maximum SET NOT NULL;
ALTER TABLE economics_risk_usage ADD CONSTRAINT economics_risk_admitted_nonnegative CHECK(admitted_maximum>=0);
COMMENT ON COLUMN economics_risk_usage.admitted_maximum IS 'Largest bound already admitted for the same immutable liability portion; recognizing loss does not erase its existing coverage';

--changeset economics:14
CREATE TABLE economics_risk_command (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,command_id uuid NOT NULL,
 permission_id uuid NOT NULL,obligation_id uuid NOT NULL,maximum_loss numeric(38,12) NOT NULL CHECK(maximum_loss>=0),
 state varchar(16) NOT NULL CHECK(state IN ('POSSIBLE','ABSENT')),
 PRIMARY KEY(organization_id,account_id,command_id),
 FOREIGN KEY(organization_id,account_id,permission_id,obligation_id)
   REFERENCES economics_risk_usage(organization_id,account_id,permission_id,obligation_id)
);
COMMENT ON TABLE economics_risk_command IS 'owner=economics; scope=account; grain=one admitted command within one exact liability portion; business_key=command; repeat=immutable maximum; history=possible until positively absent; aggregation=shared portion once; retention=liability evidence';
ALTER TABLE economics_risk_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_risk_command FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_risk_command_scope ON economics_risk_command USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);
GRANT SELECT,INSERT ON economics_risk_command TO repricer_api,repricer_worker;
GRANT UPDATE(state) ON economics_risk_command TO repricer_api,repricer_worker;

--changeset economics:15
ALTER TABLE economics_ledger_publication ADD COLUMN recognition_after uuid;
ALTER TABLE economics_ledger_publication ADD COLUMN recognition_complete boolean NOT NULL DEFAULT false;
ALTER TABLE economics_financial_event ADD COLUMN preparation_id uuid;
UPDATE economics_financial_event SET preparation_id=publication_id;
ALTER TABLE economics_financial_event ADD CONSTRAINT economics_financial_preparation_fk
 FOREIGN KEY(organization_id,account_id,preparation_id) REFERENCES economics_ledger_publication(organization_id,account_id,id);
ALTER TABLE economics_financial_event DROP CONSTRAINT economics_financial_source_edition;
ALTER TABLE economics_financial_event ADD CONSTRAINT economics_financial_prepared_source
 UNIQUE(organization_id,account_id,preparation_id,source_id,component);
CREATE INDEX economics_financial_preparation ON economics_financial_event(organization_id,account_id,preparation_id);
COMMENT ON COLUMN economics_financial_event.preparation_id IS 'One immutable connected recognition set; publication_id retains the exact source cohort even when a related return rebuilds the sale';
ALTER TABLE economics_financial_event ALTER COLUMN income TYPE numeric(62,36);
ALTER TABLE economics_financial_event ALTER COLUMN cost TYPE numeric(62,36);
ALTER TABLE economics_financial_event ALTER COLUMN expenses TYPE numeric(62,36);
ALTER TABLE economics_financial_event ALTER COLUMN tax TYPE numeric(62,36);
ALTER TABLE economics_financial_event ALTER COLUMN profit TYPE numeric(62,36);
ALTER TABLE economics_financial_event ALTER COLUMN unit_income TYPE numeric(62,36);
ALTER TABLE economics_financial_event ALTER COLUMN unit_cost TYPE numeric(62,36);
ALTER TABLE economics_financial_event ALTER COLUMN unit_profit TYPE numeric(62,36);

CREATE TABLE economics_source_fact (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,publication_id uuid NOT NULL,
 source_id uuid NOT NULL,source_revision bigint NOT NULL CHECK(source_revision>0),
 component varchar(128) NOT NULL,original_order_line_id uuid,fact jsonb NOT NULL,
 PRIMARY KEY(organization_id,account_id,publication_id,source_id),
 FOREIGN KEY(organization_id,account_id,publication_id) REFERENCES economics_ledger_publication(organization_id,account_id,id)
);
COMMENT ON TABLE economics_source_fact IS 'owner=economics; scope=account; grain=immutable normalized FinancialFact in one source edition; business_key=publication,source; repeat=source edition cursor; history=all source revisions; aggregation=published source cohorts only; retention=financial evidence';
CREATE INDEX economics_source_fact_line ON economics_source_fact(organization_id,account_id,original_order_line_id,publication_id);
INSERT INTO economics_source_fact(organization_id,account_id,publication_id,source_id,source_revision,component,original_order_line_id,fact)
 SELECT DISTINCT ON(organization_id,account_id,publication_id,source_id)
   organization_id,account_id,publication_id,source_id,source_revision,component,
   CASE WHEN component IN ('REVENUE','REFUND','RETURN_PHYSICAL') THEN original_order_line_id END,
   recognition_basis->'source'
 FROM economics_financial_event WHERE publication_id IS NOT NULL AND recognition_basis->'source' IS NOT NULL
 ORDER BY organization_id,account_id,publication_id,source_id,created_at DESC,id;

CREATE TABLE economics_original_line_head (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,original_order_line_id uuid NOT NULL,
 revision bigint NOT NULL CHECK(revision>0),source_signature varchar(64) NOT NULL,
 PRIMARY KEY(organization_id,account_id,original_order_line_id),
 FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE economics_original_line_head IS 'owner=economics; scope=account; grain=connected original sale and return source signature; business_key=original line; repeat=unchanged signature; history=immutable preparation sets; aggregation=one current signature; retention=financial evidence';
CREATE TABLE economics_recognition_line (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,publication_id uuid NOT NULL,
 original_order_line_id uuid NOT NULL,expected_revision bigint NOT NULL CHECK(expected_revision>=0),
 source_signature varchar(64) NOT NULL,
 PRIMARY KEY(organization_id,account_id,publication_id,original_order_line_id),
 FOREIGN KEY(organization_id,account_id,publication_id) REFERENCES economics_ledger_publication(organization_id,account_id,id)
);
COMMENT ON TABLE economics_recognition_line IS 'owner=economics; scope=account; grain=one complete connected line captured by a preparation; business_key=publication,original line; repeat=prepared line once; history=immutable source signature and head; aggregation=none; retention=financial evidence';
ALTER TABLE economics_source_fact ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_source_fact FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_source_fact_scope ON economics_source_fact USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);
ALTER TABLE economics_original_line_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_original_line_head FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_original_line_head_scope ON economics_original_line_head USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);
ALTER TABLE economics_recognition_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE economics_recognition_line FORCE ROW LEVEL SECURITY;
CREATE POLICY economics_recognition_line_scope ON economics_recognition_line USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);
GRANT SELECT,INSERT ON economics_source_fact,economics_original_line_head,economics_recognition_line TO repricer_api,repricer_worker;
GRANT UPDATE(revision,source_signature) ON economics_original_line_head TO repricer_api,repricer_worker;
GRANT UPDATE(recognition_after,recognition_complete) ON economics_ledger_publication TO repricer_api,repricer_worker;
--changeset economics:16
ALTER TABLE economics_source_fact ADD COLUMN recognition_scope varchar(80);
UPDATE economics_source_fact SET recognition_scope='LINE:'||original_order_line_id WHERE original_order_line_id IS NOT NULL;
CREATE INDEX economics_source_fact_recognition_scope ON economics_source_fact(organization_id,account_id,recognition_scope,publication_id);
ALTER TABLE economics_original_line_head RENAME TO economics_recognition_head;
ALTER TABLE economics_recognition_head RENAME COLUMN original_order_line_id TO scope_key;
ALTER TABLE economics_recognition_head ALTER COLUMN scope_key TYPE varchar(80) USING 'LINE:'||scope_key;
ALTER TABLE economics_recognition_line RENAME TO economics_recognition_scope;
ALTER TABLE economics_recognition_scope RENAME COLUMN original_order_line_id TO scope_key;
ALTER TABLE economics_recognition_scope ALTER COLUMN scope_key TYPE varchar(80) USING 'LINE:'||scope_key;
COMMENT ON TABLE economics_recognition_head IS 'owner=economics; scope=account; grain=connected original-line or proven incurred-service source signature; business_key=typed scope key; repeat=unchanged signature; history=immutable preparation sets; aggregation=one current signature; retention=financial evidence';
COMMENT ON TABLE economics_recognition_scope IS 'owner=economics; scope=account; grain=one complete connected financial scope captured by preparation; business_key=publication,typed scope key; repeat=prepared scope once; history=immutable source signature and head; aggregation=none; retention=financial evidence';
ALTER TABLE economics_financial_event DROP CONSTRAINT economics_financial_prepared_source;
ALTER TABLE economics_financial_event ADD CONSTRAINT economics_financial_prepared_recipient
 UNIQUE NULLS NOT DISTINCT(organization_id,account_id,preparation_id,source_id,component,offer_id);
DROP INDEX economics_financial_current;
CREATE UNIQUE INDEX economics_financial_current ON economics_financial_event
 (organization_id,account_id,source_id,component,offer_id) NULLS NOT DISTINCT WHERE current_revision;
COMMENT ON COLUMN economics_financial_event.offer_id IS 'Direct canonical recipient or a proven service-allocation recipient; null preserves an unallocated account amount';
ALTER TABLE economics_recognition_scope ADD COLUMN prepared_rows integer NOT NULL DEFAULT 0 CHECK(prepared_rows>=0);
ALTER TABLE economics_recognition_scope ADD COLUMN ready boolean NOT NULL DEFAULT true;
ALTER TABLE economics_recognition_scope ADD COLUMN coverage_complete boolean NOT NULL DEFAULT true;
ALTER TABLE economics_recognition_head ADD COLUMN coverage_complete boolean NOT NULL DEFAULT true;
COMMENT ON COLUMN economics_recognition_head.coverage_complete IS 'Explicit incurred-service scope is fully covered by actual quantities; independent known amounts remain confirmed when scope is incomplete';
GRANT UPDATE(prepared_rows,ready,coverage_complete) ON economics_recognition_scope TO repricer_api,repricer_worker;
GRANT UPDATE(coverage_complete) ON economics_recognition_head TO repricer_api,repricer_worker;

--changeset economics:17
ALTER TABLE economics_ledger_publication ADD COLUMN recognition_version integer NOT NULL DEFAULT 1 CHECK(recognition_version>0);
COMMENT ON COLUMN economics_ledger_publication.recognition_version IS 'Immutable canonical recognition contract version; old source bytes remain unchanged while their ledger edition is rebuilt';

--changeset economics:18
ALTER TABLE economics_financial_event ADD COLUMN preliminary boolean;
COMMENT ON COLUMN economics_financial_event.preliminary IS 'true=explicit estimate, false=known source amounts, null=legacy certainty not captured; independent of missing calculation inputs and period coverage';
ALTER TABLE economics_financial_event ADD CONSTRAINT economics_estimate_incomplete CHECK(preliminary IS NOT TRUE OR NOT complete);

--changeset economics:19
ALTER TABLE economics_financial_event DROP CONSTRAINT economics_estimate_incomplete;
COMMENT ON COLUMN economics_financial_event.complete IS 'All required monetary calculation inputs are known; independent of preliminary amount kind and monthly source coverage';
