--liquibase formatted sql

--changeset automation:assignment-batch-1
CREATE TABLE automation_assignment_batch (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,id uuid NOT NULL,
  kind text NOT NULL CHECK(kind IN ('ASSIGN','PREVIEW')),selection_id uuid,assignment_id uuid,
  assignment_revision bigint NOT NULL CHECK(assignment_revision>=0),policy_id uuid NOT NULL,
  policy_version bigint NOT NULL CHECK(policy_version>0),mode text NOT NULL,
  state text NOT NULL DEFAULT 'CAPTURING' CHECK(state IN ('CAPTURING','APPLYING','WAITING','COMPLETED')),
  total bigint NOT NULL CHECK(total>=0),captured bigint NOT NULL DEFAULT 0 CHECK(captured>=0),
  capture_cursor uuid,applied bigint NOT NULL DEFAULT 0,failed bigint NOT NULL DEFAULT 0,
  waiting bigint NOT NULL DEFAULT 0,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,id) REFERENCES platform_job(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,assignment_id)
    REFERENCES automation_assignment(organization_id,account_id,id),
  FOREIGN KEY(organization_id,policy_id,policy_version)
    REFERENCES automation_policy_publication(organization_id,policy_id,version),
  CHECK(mode IN ('PREVIEW','MANUAL')),
  CHECK((kind='ASSIGN' AND selection_id IS NOT NULL AND assignment_id IS NULL)
    OR (kind='PREVIEW' AND selection_id IS NULL AND assignment_id IS NOT NULL))
);
COMMENT ON TABLE automation_assignment_batch IS 'owner=automation.AssignmentBatchService; scope=account; grain=one captured bulk configuration or preview request; repeat=job business key; history=immutable captured basis plus explicit lifecycle; aggregation=outcome counts; retention=operation history';
CREATE TABLE automation_assignment_batch_row (
  organization_id uuid NOT NULL,account_id uuid NOT NULL,batch_id uuid NOT NULL,id uuid NOT NULL,
  offer_id uuid NOT NULL,target_id uuid,
  captured jsonb CHECK(captured IS NULL OR (jsonb_typeof(captured)='object' AND octet_length(captured::text)<=32768)),
  state text NOT NULL CHECK(state IN ('PENDING','APPLIED','STALE','FAILED','PREVIEW_PENDING','PREVIEW_READY')),
  reason text,operation_id uuid,assignment_id uuid,assignment_revision bigint NOT NULL DEFAULT 0,
  PRIMARY KEY(organization_id,account_id,batch_id,id),
  UNIQUE NULLS NOT DISTINCT(organization_id,account_id,batch_id,offer_id,target_id),
  FOREIGN KEY(organization_id,account_id,batch_id)
    REFERENCES automation_assignment_batch(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,operation_id)
    REFERENCES platform_job(organization_id,account_id,id),
  FOREIGN KEY(organization_id,account_id,assignment_id)
    REFERENCES automation_assignment(organization_id,account_id,id)
);
COMMENT ON COLUMN automation_assignment_batch_row.offer_id IS 'Historical identity captured from the authorized immutable source; retained even if source is unavailable at capture, with STALE outcome';
COMMENT ON TABLE automation_assignment_batch_row IS 'owner=automation.AssignmentBatchService; scope=account; grain=one captured source offer; repeat=batch,offer; history=captured immutable heads and explicit outcome; aggregation=once per batch; retention=batch history';
CREATE INDEX automation_assignment_batch_pending ON automation_assignment_batch_row
  (organization_id,account_id,batch_id,state,id);
ALTER TABLE automation_assignment_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_assignment_batch FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_assignment_batch_scope ON automation_assignment_batch USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
ALTER TABLE automation_assignment_batch_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE automation_assignment_batch_row FORCE ROW LEVEL SECURITY;
CREATE POLICY automation_assignment_batch_row_scope ON automation_assignment_batch_row USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
GRANT SELECT,INSERT ON automation_assignment_batch,automation_assignment_batch_row
  TO repricer_api,repricer_worker;
GRANT UPDATE(capture_cursor,captured,total,state,applied,failed,waiting)
  ON automation_assignment_batch TO repricer_api,repricer_worker;
GRANT UPDATE(state,reason,operation_id,assignment_id,assignment_revision)
  ON automation_assignment_batch_row TO repricer_api,repricer_worker;

--changeset automation:assignment-batch-metadata-2
COMMENT ON TABLE automation_assignment_batch IS 'owner=automation.AssignmentBatchService; scope=account; grain=one captured bulk configuration or preview request; business_key=organization,account,job id; repeat=job business key; history=immutable captured basis plus explicit lifecycle; aggregation=outcome counts; retention=operation history';
COMMENT ON TABLE automation_assignment_batch_row IS 'owner=automation.AssignmentBatchService; scope=account; grain=one captured offer and explicit price target, or one configuration offer; business_key=organization,account,batch,offer,target; repeat=unique batch offer target; history=captured immutable heads and explicit outcome; aggregation=once per row within batch; retention=batch history';
