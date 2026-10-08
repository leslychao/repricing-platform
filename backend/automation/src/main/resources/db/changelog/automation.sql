--liquibase formatted sql

--changeset automation:1
CREATE TABLE automation_policy (
  organization_id uuid NOT NULL,id uuid NOT NULL,name varchar(120) NOT NULL,
  description varchar(2000) NOT NULL,status varchar(16) NOT NULL,
  version bigint NOT NULL CHECK(version>=0),revision bigint NOT NULL CHECK(revision>0),
  draft jsonb NOT NULL CHECK(jsonb_typeof(draft)='object'),author_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,id),CHECK(status IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED'))
);
COMMENT ON TABLE automation_policy IS 'owner=automation; scope=organization; grain=one policy current pointer and draft; business_key=organization,policyId; repeat=client request and expectedRevision; history=immutable publications; aggregation=none; retention=while history referenced';
CREATE TABLE automation_policy_publication (
  organization_id uuid NOT NULL,policy_id uuid NOT NULL,version bigint NOT NULL,
  settings jsonb NOT NULL CHECK(jsonb_typeof(settings)='object'),author_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,policy_id,version),
  FOREIGN KEY(organization_id,policy_id) REFERENCES automation_policy(organization_id,id)
);
COMMENT ON TABLE automation_policy_publication IS 'owner=automation; scope=organization; grain=one validated immutable PolicySettings; business_key=organization,policy,version; repeat=no new version for same settings; history=immutable; aggregation=none; retention=while decisions or grants referenced';
CREATE TABLE automation_assignment (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,policy_id uuid NOT NULL,
  scope_kind varchar(16) NOT NULL CHECK(scope_kind IN ('ACCOUNT','CATEGORY','OFFER')),
  target_id uuid NOT NULL,mode varchar(16) NOT NULL CHECK(mode IN ('PREVIEW','MANUAL','AUTO')),
  enabled boolean NOT NULL,paused boolean NOT NULL,revision bigint NOT NULL CHECK(revision>0),
  PRIMARY KEY(organization_id,account_id,id),
  UNIQUE(organization_id,account_id,scope_kind,target_id),
  FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id),
  FOREIGN KEY(organization_id,policy_id) REFERENCES automation_policy(organization_id,id)
);
COMMENT ON TABLE automation_assignment IS 'owner=automation; scope=account; grain=one assignment per canonical scope specificity including paused; business_key=organization,account,scopeKind,targetId; repeat=expectedRevision; history=audited revisions; aggregation=none; retention=while active or history referenced';

CREATE TABLE automation_decision (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,offer_id uuid NOT NULL,
  policy_id uuid,policy_version bigint NOT NULL,assignment_id uuid,assignment_revision bigint NOT NULL,
  offer_revision bigint NOT NULL,cost_revision bigint NOT NULL,tax_revision bigint NOT NULL,
  safety_revision bigint NOT NULL,state varchar(20) NOT NULL,reason varchar(128) NOT NULL,
  target_achieved boolean NOT NULL,requires_confirmation boolean NOT NULL,
  selected_candidate uuid,calculated_at timestamptz NOT NULL,valid_until timestamptz NOT NULL,
  maximum_age_seconds integer NOT NULL CHECK(maximum_age_seconds BETWEEN 1 AND 300),
  executable boolean NOT NULL,snapshot_file_id uuid NOT NULL,revision bigint NOT NULL,
  author_id uuid NOT NULL,approved_by uuid,approved_at timestamptz,approval_job_id uuid,
  PRIMARY KEY(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,offer_id)
    REFERENCES marketplace_offer(organization_id,account_id,id),
  FOREIGN KEY(organization_id,policy_id) REFERENCES automation_policy(organization_id,id),
  FOREIGN KEY(organization_id,account_id,assignment_id)
    REFERENCES automation_assignment(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,snapshot_file_id)
    REFERENCES platform_file(organization_id,account_id,id),
  CHECK(state IN ('CALCULATED','APPROVED','EXECUTING','COMPLETED','STALE','CANCELLED'))
);
CREATE INDEX automation_decision_list ON automation_decision
  (organization_id,account_id,calculated_at DESC,id);
COMMENT ON TABLE automation_decision IS 'owner=automation; scope=account; grain=one immutable calculated basis and owned lifecycle; business_key=organization,account,decisionId; repeat=job/business key; history=basis immutable status audited; aggregation=none; retention=while effects/history referenced';
CREATE TABLE automation_command (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,
  decision_id uuid NOT NULL,step integer NOT NULL CHECK(step BETWEEN 0 AND 7),
  state varchar(24) NOT NULL,prepared jsonb NOT NULL CHECK(jsonb_typeof(prepared)='object'),
  journal_file_id uuid,checked_at timestamptz NOT NULL,send_deadline timestamptz NOT NULL,
  admitted_at timestamptz,reason varchar(256),revision bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,id),UNIQUE(organization_id,account_id,decision_id,step),
  FOREIGN KEY(organization_id,account_id,decision_id)
    REFERENCES automation_decision(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,journal_file_id)
    REFERENCES platform_file(organization_id,account_id,id),
  CHECK(state IN ('PENDING','SENT','APPLIED','PARTIALLY_APPLIED','FAILED','UNKNOWN','CANCELLED')),
  CHECK(send_deadline>checked_at)
);
COMMENT ON TABLE automation_command IS 'owner=automation; scope=account; grain=one absolute external step of a decision; business_key=organization,account,decision,step; repeat=same prepared digest; history=immutable request owned state; aggregation=known effects only; retention=execution history';
CREATE TABLE automation_send_attempt (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,command_id uuid NOT NULL,
  admitted_at timestamptz NOT NULL,latest_start timestamptz NOT NULL,state varchar(32) NOT NULL,
  raw_response_file_id uuid,PRIMARY KEY(organization_id,account_id,command_id),
  FOREIGN KEY(organization_id,account_id,command_id)
    REFERENCES automation_command(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,raw_response_file_id)
    REFERENCES platform_file(organization_id,account_id,id),
  CHECK(state IN ('ADMITTED','NOT_SENT','ACCEPTED','REJECTED','UNKNOWN'))
);
COMMENT ON TABLE automation_send_attempt IS 'owner=automation; scope=account; grain=the unique SEND attempt for a command; business_key=organization,account,command; repeat=never resend; history=immutable admission outcome refinement; aggregation=one possible effect; retention=command history';
CREATE TABLE automation_readback (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,command_id uuid NOT NULL,
  evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,command_id)
    REFERENCES automation_command(organization_id,account_id,id)
);
COMMENT ON TABLE automation_readback IS 'owner=automation; scope=account; grain=one bounded canonical readback attempt; business_key=organization,account,id; repeat=source raw keys canonicalized; history=immutable; aggregation=not additive; retention=command evidence';
CREATE TABLE automation_field_claim (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,target_id uuid NOT NULL,
  field varchar(128) NOT NULL,command_id uuid NOT NULL,
  PRIMARY KEY(organization_id,account_id,target_id,field),
  FOREIGN KEY(organization_id,account_id,command_id)
    REFERENCES automation_command(organization_id,account_id,id)
);
COMMENT ON TABLE automation_field_claim IS 'owner=automation; scope=account; grain=one conflicting canonical field claim; business_key=organization,account,target,field; repeat=command retains claim; history=command lifecycle; aggregation=none; retention=until proven command completion';

--changeset automation:2
ALTER TABLE automation_assignment ADD COLUMN regular_references jsonb NOT NULL DEFAULT '{"selectedPromos":[],"protectedPromos":[]}';
ALTER TABLE automation_assignment ADD COLUMN temporary_references jsonb NOT NULL DEFAULT '{"selectedPromos":[],"protectedPromos":[]}';
ALTER TABLE automation_policy ADD CONSTRAINT automation_policy_organization_fk FOREIGN KEY(organization_id) REFERENCES access_organization(id);
ALTER TABLE automation_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_policy_scope ON automation_policy USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
ALTER TABLE automation_policy_publication ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_policy_publication FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_policy_publication_scope ON automation_policy_publication USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
ALTER TABLE automation_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_assignment_scope ON automation_assignment USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND (account_id=NULLIF(current_setting('app.account_id',true),'')::uuid OR NULLIF(current_setting('app.account_id',true),'') IS NULL)
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);
ALTER TABLE automation_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_decision_scope ON automation_decision USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE automation_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_command FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_command_scope ON automation_command USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE automation_send_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_send_attempt FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_send_attempt_scope ON automation_send_attempt USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE automation_readback ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_readback FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_readback_scope ON automation_readback USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);

ALTER TABLE automation_field_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_field_claim FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_field_claim_scope ON automation_field_claim USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
);


--changeset automation:3
CREATE TABLE automation_stop (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,scope_id uuid NOT NULL,offer_id uuid,
 paused boolean NOT NULL,revision bigint NOT NULL,
 PRIMARY KEY(organization_id,account_id,scope_id),
 FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id),
 FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id),
 CHECK(scope_id=COALESCE(offer_id,account_id))
);
COMMENT ON TABLE automation_stop IS 'owner=automation; scope=account; grain=one explicit stop at account or offer; business_key=organization,account,scopeId; repeat=expectedRevision; history=audit; aggregation=any matching pause; retention=until explicit resume';
CREATE TABLE automation_runtime (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,offer_id uuid NOT NULL,target_id uuid NOT NULL,
 stock_state varchar(24) NOT NULL,episode_base_price numeric(38,12),consecutive_decreases integer NOT NULL,
 last_admission timestamptz,revision bigint NOT NULL,
 PRIMARY KEY(organization_id,account_id,offer_id,target_id),
 FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id),
 CHECK(stock_state IN ('NORMAL','LOW_STOCK','NO_STOCK','STOCK_UNKNOWN'))
);
COMMENT ON TABLE automation_runtime IS 'owner=automation; scope=account; grain=one canonical offer target runtime; business_key=organization,account,offer,target; repeat=locked transitions; history=movement ledger plus audit; aggregation=window counters; retention=active target';
CREATE TABLE automation_movement (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,command_id uuid NOT NULL,
 offer_id uuid NOT NULL,target_id uuid NOT NULL,old_price numeric(38,12) NOT NULL,
 new_price numeric(38,12) NOT NULL,movement numeric(38,12) NOT NULL CHECK(movement>=0),
 admitted_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,account_id,command_id,target_id),
 FOREIGN KEY(organization_id,account_id,command_id) REFERENCES automation_command(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id,offer_id,target_id) REFERENCES automation_runtime(organization_id,account_id,offer_id,target_id)
);
CREATE INDEX automation_movement_window ON automation_movement(organization_id,account_id,offer_id,target_id,admitted_at);
COMMENT ON TABLE automation_movement IS 'owner=automation; scope=account; grain=one admitted target movement; business_key=organization,account,command,target; repeat=unique command; history=immutable possible writes; aggregation=sum absolute movement and count inside window; retention=execution history';
ALTER TABLE automation_stop ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_stop FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_stop_scope ON automation_stop USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE automation_runtime ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_runtime FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_runtime_scope ON automation_runtime USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE automation_movement ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_movement FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_movement_scope ON automation_movement USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);


--changeset automation:4
ALTER TABLE automation_decision ADD COLUMN offer_price_revision bigint NOT NULL DEFAULT 0;


--changeset automation:5
CREATE TABLE automation_scenario (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,assignment_id uuid NOT NULL,
 assignment_revision bigint NOT NULL,policy_id uuid NOT NULL,policy_version bigint NOT NULL,
 state varchar(16) NOT NULL CHECK(state IN ('READY','RUNNING','FINISHING','FINISHED')),
 starts_at timestamptz NOT NULL,ends_at timestamptz NOT NULL,maximum_accepted_quantity numeric(38,12),
 minimum_remaining_stock numeric(38,12),accepted_quantity numeric(38,12) NOT NULL,
 scope_digest char(64) NOT NULL,settings jsonb NOT NULL,reason varchar(128) NOT NULL,revision bigint NOT NULL,
 author_id uuid NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id,assignment_id) REFERENCES automation_assignment(organization_id,account_id,id),
 FOREIGN KEY(organization_id,policy_id,policy_version) REFERENCES automation_policy_publication(organization_id,policy_id,version),
 CHECK(ends_at>starts_at),CHECK(accepted_quantity>=0)
);
COMMENT ON TABLE automation_scenario IS 'owner=automation; scope=account; grain=one explicitly activated immutable episode basis and lifecycle; business_key=organization,account,runId; repeat=activation job identity; history=frozen publication period and settings; aggregation=accepted demand once; retention=decision and risk evidence';
CREATE TABLE automation_scenario_scope (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,run_id uuid NOT NULL,offer_id uuid NOT NULL,target_id uuid NOT NULL,
 initial_price numeric(38,12) NOT NULL,initial_state jsonb NOT NULL,completed boolean NOT NULL,
 PRIMARY KEY(organization_id,account_id,run_id,offer_id,target_id),
 FOREIGN KEY(organization_id,account_id,run_id) REFERENCES automation_scenario(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id,offer_id) REFERENCES marketplace_offer(organization_id,account_id,id)
);
COMMENT ON TABLE automation_scenario_scope IS 'owner=automation; scope=account; grain=one frozen offer target of an episode; business_key=organization,account,run,offer,target; repeat=immutable membership; history=initial verified state and confirmed completion; aggregation=all targets complete; retention=episode evidence';
CREATE TABLE automation_scenario_claim (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,target_id uuid NOT NULL,run_id uuid NOT NULL,
 PRIMARY KEY(organization_id,account_id,target_id),
 FOREIGN KEY(organization_id,account_id,run_id) REFERENCES automation_scenario(organization_id,account_id,id)
);
COMMENT ON TABLE automation_scenario_claim IS 'owner=automation; scope=account; grain=one live episode per canonical target; business_key=organization,account,target; repeat=same episode; history=episode lifecycle; aggregation=none; retention=until confirmed finish';
CREATE TABLE automation_scenario_command (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,run_id uuid NOT NULL,command_id uuid NOT NULL,
 PRIMARY KEY(organization_id,account_id,run_id,command_id),
 FOREIGN KEY(organization_id,account_id,run_id) REFERENCES automation_scenario(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id,command_id) REFERENCES automation_command(organization_id,account_id,id)
);
COMMENT ON TABLE automation_scenario_command IS 'owner=automation; scope=account; grain=one possible episode external effect; business_key=organization,account,run,command; repeat=unique admission; history=immutable; aggregation=all pending and unknown retained; retention=episode evidence';
CREATE TABLE automation_scenario_demand (
 organization_id uuid NOT NULL,account_id uuid NOT NULL,run_id uuid NOT NULL,demand_id uuid NOT NULL,
 quantity numeric(38,12) NOT NULL CHECK(quantity>=0),
 PRIMARY KEY(organization_id,account_id,run_id,demand_id),
 FOREIGN KEY(organization_id,account_id,run_id) REFERENCES automation_scenario(organization_id,account_id,id)
);
COMMENT ON TABLE automation_scenario_demand IS 'owner=automation; scope=account; grain=one canonical accepted demand quantity; business_key=organization,account,run,canonicalDemandId; repeat=monotonic maximum prevents cancellation extending run; history=source canonical history; aggregation=sum stable demand identities; retention=episode evidence';
ALTER TABLE automation_decision ADD COLUMN scenario_id uuid;
ALTER TABLE automation_decision ADD COLUMN scenario_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE automation_decision ADD CONSTRAINT automation_decision_scenario_fk FOREIGN KEY(organization_id,account_id,scenario_id) REFERENCES automation_scenario(organization_id,account_id,id);
ALTER TABLE automation_scenario ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_scenario FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_scenario_scope ON automation_scenario USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE automation_scenario_scope ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_scenario_scope FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_scenario_scope_scope ON automation_scenario_scope USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE automation_scenario_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_scenario_claim FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_scenario_claim_scope ON automation_scenario_claim USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE automation_scenario_command ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_scenario_command FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_scenario_command_scope ON automation_scenario_command USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

ALTER TABLE automation_scenario_demand ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_scenario_demand FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_scenario_demand_scope ON automation_scenario_demand USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

--changeset automation:6
CREATE TABLE automation_auto_activation (
 organization_id uuid NOT NULL, account_id uuid NOT NULL, grant_id uuid NOT NULL,
 assignment_id uuid NOT NULL, issuer_id uuid NOT NULL, binding jsonb NOT NULL,
 operation_id uuid NOT NULL, grant_revision bigint NOT NULL CHECK(grant_revision>0),
 PRIMARY KEY(organization_id,account_id,grant_id),
 UNIQUE(organization_id,account_id,operation_id),
 UNIQUE(organization_id,account_id,assignment_id,grant_revision),
 FOREIGN KEY(organization_id,account_id,assignment_id)
   REFERENCES automation_assignment(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id,grant_id)
   REFERENCES access_automation_grant(organization_id,account_id,id),
 CHECK(octet_length(binding::text)<=65536)
);
COMMENT ON TABLE automation_auto_activation IS 'owner=automation; scope=account; grain=validated complete AUTO delegation; business_key=assignment,grant revision; repeat=operation id; history=immutable; aggregation=none; retention=execution evidence';
CREATE TABLE automation_generation (
 organization_id uuid NOT NULL, account_id uuid NOT NULL, grant_id uuid NOT NULL,
 offer_id uuid NOT NULL, target_id uuid NOT NULL, requested_generation bigint NOT NULL,
 completed_generation bigint NOT NULL, cycle bigint NOT NULL,
 first_event_at timestamptz NOT NULL, due_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,account_id,grant_id,offer_id,target_id),
 FOREIGN KEY(organization_id,account_id,grant_id)
   REFERENCES automation_auto_activation(organization_id,account_id,grant_id),
 FOREIGN KEY(organization_id,account_id,offer_id)
   REFERENCES marketplace_offer(organization_id,account_id,id),
 CHECK(completed_generation>=0 AND completed_generation<=requested_generation),
 CHECK(cycle>0 AND cycle<=requested_generation)
);
COMMENT ON TABLE automation_generation IS 'owner=automation; scope=account; grain=latest source generation per delegated target; business_key=grant,offer,target; repeat=Outbox receipt; history=decision captures generation; aggregation=none; retention=active and unresolved authority';
CREATE TABLE automation_decision_auto (
 organization_id uuid NOT NULL, account_id uuid NOT NULL, decision_id uuid NOT NULL,
 grant_id uuid NOT NULL, offer_id uuid NOT NULL, target_id uuid NOT NULL,
 generation bigint NOT NULL CHECK(generation>0),
 PRIMARY KEY(organization_id,account_id,decision_id),
 FOREIGN KEY(organization_id,account_id,decision_id)
   REFERENCES automation_decision(organization_id,account_id,id),
 FOREIGN KEY(organization_id,account_id,grant_id,offer_id,target_id)
   REFERENCES automation_generation(organization_id,account_id,grant_id,offer_id,target_id)
);
COMMENT ON TABLE automation_decision_auto IS 'owner=automation; scope=account; grain=automatic approval authority and source generation; business_key=decision; repeat=stable decision id; history=immutable; aggregation=none; retention=execution evidence';
ALTER TABLE automation_auto_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_auto_activation FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_auto_activation_scope ON automation_auto_activation USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);
ALTER TABLE automation_generation ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_generation FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_generation_scope ON automation_generation USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);
ALTER TABLE automation_decision_auto ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_decision_auto FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_decision_auto_scope ON automation_decision_auto USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);
--changeset automation:7
CREATE TABLE automation_source_batch (
 organization_id uuid NOT NULL, account_id uuid NOT NULL, job_id uuid NOT NULL,
 PRIMARY KEY(organization_id,account_id,job_id),
 FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE automation_source_batch IS 'owner=automation; scope=account; grain=processed bounded source fanout batch; business_key=job; repeat=receipt and generation changes in one transaction; history=immutable; aggregation=none; retention=source job lifetime';
ALTER TABLE automation_source_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_source_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_source_batch_scope ON automation_source_batch USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);

--changeset automation:8
ALTER TABLE automation_scenario_command ADD COLUMN before_revision bigint;
ALTER TABLE automation_scenario_command ADD COLUMN after_revision bigint;
ALTER TABLE automation_scenario_command ADD CONSTRAINT automation_scenario_admission_revision
  CHECK ((before_revision IS NULL AND after_revision IS NULL)
    OR (before_revision>0 AND after_revision BETWEEN before_revision AND before_revision+1));
COMMENT ON COLUMN automation_scenario_command.before_revision IS 'Captured under the scenario lock; null historical rows cannot prove an own transition';

--changeset automation:9
CREATE TABLE automation_sweep (
 organization_id uuid NOT NULL, account_id uuid NOT NULL, cycle bigint NOT NULL CHECK(cycle>0),
 PRIMARY KEY(organization_id,account_id),
 FOREIGN KEY(organization_id,account_id) REFERENCES marketplace_account(organization_id,id)
);
COMMENT ON TABLE automation_sweep IS 'owner=automation; scope=account; grain=nonoverlapping periodic sweep cursor cycle; business_key=account; repeat=job receipt; history=runtime; aggregation=none; retention=account';
ALTER TABLE automation_sweep ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_sweep FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_sweep_scope ON automation_sweep USING (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
) WITH CHECK (
 organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
 AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
 AND current_setting('app.authorized',true)='true'
);

--changeset automation:10
ALTER TABLE automation_command ADD COLUMN uncertainty_incident_at timestamptz;

--changeset automation:11
ALTER TABLE automation_decision ADD COLUMN evidence_expired_at timestamptz;
ALTER TABLE automation_decision ADD COLUMN calculation_job_id uuid;
COMMENT ON COLUMN automation_decision.evidence_expired_at IS 'Owner-confirmed release of superseded, unapproved, never-executed preview evidence; immutable metadata remains';
COMMENT ON COLUMN automation_decision.calculation_job_id IS 'The actual worker operation that published this decision; a deterministic decision identity need not equal its job identity';
UPDATE automation_decision d SET calculation_job_id=j.id FROM platform_job j
 WHERE j.organization_id=d.organization_id AND j.account_id=d.account_id AND j.id=d.id
   AND j.job_type='DECISION_PREVIEW';
ALTER TABLE automation_decision ADD CONSTRAINT automation_decision_calculation_job_fk
 FOREIGN KEY(organization_id,account_id,calculation_job_id)
 REFERENCES platform_job(organization_id,account_id,id);
ALTER TABLE automation_decision ADD CONSTRAINT automation_decision_approval_job_fk
 FOREIGN KEY(organization_id,account_id,approval_job_id)
 REFERENCES platform_job(organization_id,account_id,id);
ALTER TABLE automation_auto_activation ADD CONSTRAINT automation_activation_operation_fk
 FOREIGN KEY(organization_id,account_id,operation_id)
 REFERENCES platform_job(organization_id,account_id,id);
ALTER TABLE automation_source_batch ADD CONSTRAINT automation_source_batch_job_fk
 FOREIGN KEY(organization_id,account_id,job_id)
 REFERENCES platform_job(organization_id,account_id,id);
ALTER TABLE automation_scenario ADD CONSTRAINT automation_scenario_job_fk
 FOREIGN KEY(organization_id,account_id,id)
 REFERENCES platform_job(organization_id,account_id,id);
CREATE INDEX automation_preview_retention ON automation_decision
 (organization_id,account_id,calculated_at,id)
 WHERE evidence_expired_at IS NULL AND approved_at IS NULL AND scenario_id IS NULL;

--changeset automation:12
ALTER TABLE automation_sweep ADD COLUMN cycle_started_at timestamptz;
COMMENT ON COLUMN automation_sweep.cycle_started_at IS 'First admitted page of the active cycle; the next nonoverlapping cycle is due at start plus five minutes or completion, whichever is later';

--changeset automation:13
ALTER TABLE automation_command ADD COLUMN confirmed_at timestamptz;
COMMENT ON COLUMN automation_command.confirmed_at IS 'First full immutable readback confirmation; a continuation requires canonical source started no earlier than all confirmed prefix steps';

--changeset automation:14
ALTER TABLE automation_scenario ADD COLUMN completion_review jsonb;
COMMENT ON COLUMN automation_scenario.completion_review IS 'Immutable activation-time disclosed managed fields, possible exits and permitted schedule; null only for previously published episodes';

--changeset automation:effective-decision-authorization-1
ALTER TABLE automation_decision ADD COLUMN effective_assignment boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN automation_decision.effective_assignment IS 'Immutable authority classification: true only for a calculation of the existing effective assignment without caller-supplied rule or target override; old evidence remains financial until recalculated';

--changeset automation:15
ALTER TABLE economics_risk_command ADD CONSTRAINT economics_risk_command_scope_fk
 FOREIGN KEY(organization_id,account_id,command_id)
 REFERENCES automation_command(organization_id,account_id,id);

--changeset automation:assignment-detach-1
ALTER TABLE automation_assignment ADD COLUMN detached boolean NOT NULL DEFAULT false;
ALTER TABLE automation_assignment ADD CONSTRAINT automation_assignment_detached_inactive
  CHECK(NOT detached OR (NOT enabled AND NOT paused));
COMMENT ON COLUMN automation_assignment.detached IS 'Removed binding preserved for history; excludes this scope from specificity, unlike pause. Reassignment keeps its identity and advances revision.';
