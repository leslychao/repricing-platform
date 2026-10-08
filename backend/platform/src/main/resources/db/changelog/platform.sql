--liquibase formatted sql
--changeset repricer:platform-001
CREATE TABLE platform_job (
  id uuid PRIMARY KEY,
  organization_id uuid,
  account_id uuid,
  subject_id uuid NOT NULL,
  scope_key text GENERATED ALWAYS AS
    (COALESCE(organization_id::text,'user:'||subject_id::text)||':'||COALESCE(account_id::text,'organization')) STORED,
  job_type text NOT NULL,
  lane text NOT NULL CHECK(lane IN ('fetch','canonicalization','calculation','execution','service')),
  business_key text NOT NULL CHECK(length(business_key)<=512),
  payload jsonb NOT NULL CHECK(octet_length(payload::text)<=65536),
  state text NOT NULL CHECK(state IN ('READY','RUNNING','WAITING','BLOCKED','SUCCEEDED','DEAD','CANCELLED')),
  due_at timestamptz NOT NULL,
  lease_owner uuid,
  lease_until timestamptz,
  fence bigint NOT NULL DEFAULT 0,
  attempt integer NOT NULL DEFAULT 0 CHECK(attempt BETWEEN 0 AND 7),
  first_failure_at timestamptz,
  checkpoint jsonb,
  result jsonb CHECK(octet_length(result::text)<=65536),
  reason text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(scope_key,job_type,business_key),
  CHECK(account_id IS NULL OR organization_id IS NOT NULL)
);
COMMENT ON TABLE platform_job IS 'owner=platform; scope=explicit user/organization/account; grain=one logical work; key=scope,type,business_key; repeat=return same work; history=mutable execution checkpoint';
CREATE INDEX platform_job_due ON platform_job(lane,due_at) WHERE state IN ('READY','WAITING','RUNNING');
ALTER TABLE platform_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_job FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_job_scope ON platform_job USING (
  (organization_id IS NULL AND subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid)
  OR (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true')
) WITH CHECK (
  (organization_id IS NULL AND subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid)
  OR (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true')
);

CREATE TABLE platform_request (
  organization_id uuid,
  account_id uuid,
  subject_id uuid NOT NULL,
  scope_key text GENERATED ALWAYS AS
    (COALESCE(organization_id::text,'user:'||subject_id::text)||':'||COALESCE(account_id::text,'organization')) STORED,
  operation text NOT NULL,
  request_id uuid NOT NULL,
  body_hash char(64) NOT NULL,
  result jsonb NOT NULL CHECK(octet_length(result::text)<=65536),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(scope_key,subject_id,operation,request_id)
);
COMMENT ON TABLE platform_request IS 'owner=platform; scope=user/organization/account+subject; grain=one accepted request; key=scope,subject,operation,request_id; repeat=same hash and result; history=30 days except business-retained operations';
ALTER TABLE platform_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_request FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_request_scope ON platform_request USING (
  subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid
  AND organization_id IS NOT DISTINCT FROM NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid
  AND organization_id IS NOT DISTINCT FROM NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
);

CREATE TABLE platform_outbox (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid,
  subject_id uuid NOT NULL,
  producer_event_id uuid NOT NULL,
  recipient text NOT NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL CHECK(octet_length(payload::text)<=65536),
  delivered_at timestamptz,
  due_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  attempt integer NOT NULL DEFAULT 0,
  reason text,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(producer_event_id,recipient)
);
COMMENT ON TABLE platform_outbox IS 'owner=platform; scope=organization/account; grain=producer event per recipient; key=event,recipient; repeat=atomic recipient result and receipt; history=undelivered until resolved';
CREATE INDEX platform_outbox_pending ON platform_outbox(due_at) WHERE delivered_at IS NULL;
ALTER TABLE platform_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_outbox_scope ON platform_outbox USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

CREATE TABLE platform_file (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid,
  subject_id uuid NOT NULL,
  kind text NOT NULL,
  object_key text NOT NULL UNIQUE,
  object_version text,
  media_type text NOT NULL,
  original_name text,
  state text NOT NULL CHECK(state IN ('UPLOADING','STORED','READY','FAILED','DELETING','DELETED')),
  upload_id text,
  byte_count bigint CHECK(byte_count>=0),
  sha256 char(64),
  eof_confirmed boolean NOT NULL DEFAULT false,
  source_observed_at timestamptz,
  expires_at timestamptz,
  deletion_after timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(organization_id,account_id,id)
);
COMMENT ON TABLE platform_file IS 'owner=platform; scope=organization/account; grain=exact stored object version; key=file UUID,object key; repeat=same upload and EOF evidence; history=immutable contents plus upload lifecycle';
CREATE TABLE platform_file_part (
  file_id uuid NOT NULL REFERENCES platform_file(id),
  organization_id uuid NOT NULL,
  account_id uuid,
  part_number integer NOT NULL CHECK(part_number BETWEEN 1 AND 10000),
  etag text NOT NULL,
  PRIMARY KEY(file_id,part_number)
);
COMMENT ON TABLE platform_file_part IS 'owner=platform; scope=organization/account; grain=completed multipart part; key=file,part number; repeat=exact part evidence; history=until multipart completed';
CREATE TABLE platform_file_reference (
  file_id uuid NOT NULL REFERENCES platform_file(id),
  organization_id uuid NOT NULL,
  account_id uuid,
  owner_type text NOT NULL,
  owner_id uuid NOT NULL,
  PRIMARY KEY(file_id,owner_type,owner_id)
);
COMMENT ON TABLE platform_file_reference IS 'owner=platform; scope=organization/account; grain=active immutable object reference; key=file,owner type,owner id; repeat=unique link; history=until owner releases';
ALTER TABLE platform_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_file FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_file_scope ON platform_file USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
ALTER TABLE platform_file_part ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_file_part FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_file_part_scope ON platform_file_part USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
ALTER TABLE platform_file_reference ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_file_reference FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_file_reference_scope ON platform_file_reference USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

--changeset repricer:platform-queue-functions splitStatements:false
CREATE FUNCTION repricer_claim_job(p_worker uuid,p_lane text,p_slots integer)
RETURNS TABLE(id uuid,organization_id uuid,account_id uuid,subject_id uuid,job_type text,fence bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE chosen uuid; allowed integer;
BEGIN
  allowed := CASE p_lane WHEN 'fetch' THEN 4 WHEN 'canonicalization' THEN 2
    WHEN 'calculation' THEN 2 WHEN 'execution' THEN 2 WHEN 'service' THEN 1 ELSE 0 END;
  IF allowed=0 OR p_slots<>allowed THEN RAISE EXCEPTION 'invalid lane'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('repricer:claim:'||p_lane,0));
  IF (SELECT count(*) FROM public.platform_job j WHERE j.lane=p_lane
      AND j.state='RUNNING' AND j.lease_until>clock_timestamp())>=allowed THEN RETURN; END IF;
  SELECT j.id INTO chosen FROM public.platform_job j
    WHERE j.lane=p_lane AND ((j.state IN ('READY','WAITING') AND j.due_at<=clock_timestamp())
      OR (j.state='RUNNING' AND j.lease_until<clock_timestamp()))
    ORDER BY (SELECT max(j2.updated_at) FROM public.platform_job j2
      WHERE j2.account_id IS NOT DISTINCT FROM j.account_id AND j2.fence>0) NULLS FIRST,
      j.due_at,j.id LIMIT 1 FOR UPDATE SKIP LOCKED;
  IF chosen IS NULL THEN RETURN; END IF;
  RETURN QUERY UPDATE public.platform_job j SET state='RUNNING',lease_owner=p_worker,
    lease_until=clock_timestamp()+interval '60 seconds',fence=j.fence+1,updated_at=clock_timestamp()
    WHERE j.id=chosen RETURNING j.id,j.organization_id,j.account_id,j.subject_id,j.job_type,j.fence;
END;
$$;
REVOKE ALL ON FUNCTION repricer_claim_job(uuid,text,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_claim_job(uuid,text,integer) TO repricer_worker;

--changeset repricer:platform-file-upload-claim
ALTER TABLE platform_file ADD COLUMN write_started_at timestamptz;
ALTER TABLE platform_file ADD COLUMN write_deadline timestamptz;
COMMENT ON COLUMN platform_file.write_started_at IS 'Single persistent claim: an interrupted writer never shares or reuses its multipart with a second writer';
--changeset repricer:platform-job-scope-key
ALTER TABLE platform_job ADD UNIQUE(organization_id,account_id,id);

--changeset repricer:platform-file-work splitStatements:false
CREATE TABLE platform_file_work (
  slot smallint PRIMARY KEY CHECK(slot BETWEEN 1 AND 2),
  token uuid UNIQUE,
  lease_until timestamptz
);
COMMENT ON TABLE platform_file_work IS 'owner=platform; scope=private global scheduler metadata; grain=file-work slot; key=slot; repeat=opaque lease token; history=current, no tenant contents';
INSERT INTO platform_file_work(slot) VALUES (1),(2);
REVOKE ALL ON platform_file_work FROM PUBLIC,repricer_api,repricer_worker;
CREATE FUNCTION repricer_file_work(p_token uuid,p_action text) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE chosen smallint;
BEGIN
  IF p_token IS NULL THEN RETURN false; END IF;
  IF p_action='ACQUIRE' THEN
    SELECT slot INTO chosen FROM public.platform_file_work
      WHERE token IS NULL OR lease_until<clock_timestamp()
      ORDER BY slot LIMIT 1 FOR UPDATE SKIP LOCKED;
    IF chosen IS NULL THEN RETURN false; END IF;
    UPDATE public.platform_file_work SET token=p_token,
      lease_until=clock_timestamp()+interval '120 seconds' WHERE slot=chosen;
    RETURN true;
  ELSIF p_action='RENEW' THEN
    UPDATE public.platform_file_work SET lease_until=clock_timestamp()+interval '120 seconds'
      WHERE token=p_token AND lease_until>clock_timestamp();
    RETURN FOUND;
  ELSIF p_action='RELEASE' THEN
    UPDATE public.platform_file_work SET token=NULL,lease_until=NULL WHERE token=p_token;
    RETURN FOUND;
  END IF;
  RAISE EXCEPTION 'invalid file work action';
END;
$$;
REVOKE ALL ON FUNCTION repricer_file_work(uuid,text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_file_work(uuid,text) TO repricer_api,repricer_worker;
--changeset repricer:platform-queue-fairness splitStatements:false
CREATE SEQUENCE platform_job_turn;
CREATE TABLE platform_job_organization_turn (
  lane text NOT NULL,
  organization_key uuid NOT NULL,
  last_turn bigint NOT NULL,
  PRIMARY KEY(lane,organization_key)
);
CREATE TABLE platform_job_account_turn (
  lane text NOT NULL,
  organization_key uuid NOT NULL,
  account_key uuid NOT NULL,
  last_turn bigint NOT NULL,
  PRIMARY KEY(lane,organization_key,account_key)
);
COMMENT ON TABLE platform_job_organization_turn IS 'owner=platform; scope=private global scheduler metadata; grain=lane/company turn; key=lane/company; repeat=claim transaction; history=current scheduler cursor';
COMMENT ON TABLE platform_job_account_turn IS 'owner=platform; scope=private global scheduler metadata; grain=lane/company/account turn; key=lane/company/account; repeat=claim transaction; history=current scheduler cursor';
REVOKE ALL ON platform_job_organization_turn,platform_job_account_turn FROM PUBLIC,repricer_api,repricer_worker;
CREATE OR REPLACE FUNCTION repricer_claim_job(p_worker uuid,p_lane text,p_slots integer)
RETURNS TABLE(id uuid,organization_id uuid,account_id uuid,subject_id uuid,job_type text,fence bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE chosen uuid; chosen_org uuid; chosen_account uuid; allowed integer; turn_number bigint;
BEGIN
  allowed := CASE p_lane WHEN 'fetch' THEN 4 WHEN 'canonicalization' THEN 2
    WHEN 'calculation' THEN 2 WHEN 'execution' THEN 2 WHEN 'service' THEN 1 ELSE 0 END;
  IF allowed=0 OR p_slots<>allowed THEN RAISE EXCEPTION 'invalid lane'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('repricer:claim:'||p_lane,0));
  IF (SELECT count(*) FROM public.platform_job j WHERE j.lane=p_lane
      AND j.state='RUNNING' AND j.lease_until>clock_timestamp())>=allowed THEN RETURN; END IF;
  SELECT j.id,COALESCE(j.organization_id,'00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(j.account_id,'00000000-0000-0000-0000-000000000000'::uuid)
    INTO chosen,chosen_org,chosen_account
  FROM public.platform_job j
  LEFT JOIN public.platform_job_organization_turn ot ON ot.lane=j.lane
    AND ot.organization_key=COALESCE(j.organization_id,'00000000-0000-0000-0000-000000000000'::uuid)
  LEFT JOIN public.platform_job_account_turn act ON act.lane=j.lane
    AND act.organization_key=COALESCE(j.organization_id,'00000000-0000-0000-0000-000000000000'::uuid)
    AND act.account_key=COALESCE(j.account_id,'00000000-0000-0000-0000-000000000000'::uuid)
  WHERE j.lane=p_lane AND ((j.state IN ('READY','WAITING') AND j.due_at<=clock_timestamp())
      OR (j.state='RUNNING' AND j.lease_until<clock_timestamp()))
  ORDER BY ot.last_turn NULLS FIRST,act.last_turn NULLS FIRST,j.due_at,j.id
  LIMIT 1 FOR UPDATE OF j SKIP LOCKED;
  IF chosen IS NULL THEN RETURN; END IF;
  turn_number := nextval('public.platform_job_turn');
  INSERT INTO public.platform_job_organization_turn(lane,organization_key,last_turn)
    VALUES(p_lane,chosen_org,turn_number)
    ON CONFLICT(lane,organization_key) DO UPDATE SET last_turn=EXCLUDED.last_turn;
  INSERT INTO public.platform_job_account_turn(lane,organization_key,account_key,last_turn)
    VALUES(p_lane,chosen_org,chosen_account,turn_number)
    ON CONFLICT(lane,organization_key,account_key) DO UPDATE SET last_turn=EXCLUDED.last_turn;
  RETURN QUERY UPDATE public.platform_job j SET state='RUNNING',lease_owner=p_worker,
    lease_until=clock_timestamp()+interval '60 seconds',fence=j.fence+1,updated_at=clock_timestamp()
    WHERE j.id=chosen RETURNING j.id,j.organization_id,j.account_id,j.subject_id,j.job_type,j.fence;
END;
$$;
--changeset repricer:platform-file-retention-scope-001
ALTER TABLE platform_file ADD COLUMN scope_key text GENERATED ALWAYS AS
  (COALESCE(account_id::text,'organization')) STORED;
ALTER TABLE platform_file ADD UNIQUE(organization_id,scope_key,id);
ALTER TABLE platform_file_part ADD COLUMN scope_key text GENERATED ALWAYS AS
  (COALESCE(account_id::text,'organization')) STORED;
ALTER TABLE platform_file_reference ADD COLUMN scope_key text GENERATED ALWAYS AS
  (COALESCE(account_id::text,'organization')) STORED;
ALTER TABLE platform_file_part DROP CONSTRAINT platform_file_part_file_id_fkey;
ALTER TABLE platform_file_reference DROP CONSTRAINT platform_file_reference_file_id_fkey;
ALTER TABLE platform_file_part ADD FOREIGN KEY(organization_id,scope_key,file_id)
  REFERENCES platform_file(organization_id,scope_key,id);
ALTER TABLE platform_file_reference ADD FOREIGN KEY(organization_id,scope_key,file_id)
  REFERENCES platform_file(organization_id,scope_key,id);
UPDATE platform_file SET expires_at=created_at+CASE kind WHEN 'RAW' THEN interval '90 days'
  WHEN 'IMPORT' THEN interval '7 days' WHEN 'REPORT' THEN interval '30 days'
  ELSE interval '12 months' END WHERE expires_at IS NULL;
CREATE TABLE platform_file_read_pin (
  id uuid PRIMARY KEY,
  file_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  account_id uuid,
  scope_key text GENERATED ALWAYS AS(COALESCE(account_id::text,'organization')) STORED,
  lease_until timestamptz NOT NULL,
  FOREIGN KEY(organization_id,scope_key,file_id)
    REFERENCES platform_file(organization_id,scope_key,id)
);
COMMENT ON TABLE platform_file_read_pin IS 'owner=platform; scope=exact organization/account; grain=one bounded active stream; key=random read id; repeat=close deletes exact id; history=at most read deadline plus network allowance';
CREATE INDEX platform_file_read_pin_file ON platform_file_read_pin(file_id,lease_until);
CREATE INDEX platform_file_retention_due ON platform_file(expires_at)
  WHERE state IN ('STORED','READY','FAILED','UPLOADING');
CREATE INDEX platform_file_delete_due ON platform_file(deletion_after) WHERE state='DELETING';
ALTER TABLE platform_file_read_pin ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_file_read_pin FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_file_read_pin_scope ON platform_file_read_pin USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
GRANT SELECT,INSERT,DELETE ON platform_file_read_pin TO repricer_api,repricer_worker;
--changeset repricer:platform-file-retention-candidates-001 splitStatements:false
CREATE FUNCTION repricer_file_retention_scopes()
RETURNS TABLE(organization_id uuid,account_id uuid,subject_id uuid)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
  SELECT DISTINCT ON (c.organization_id,c.account_id)
    c.organization_id,c.account_id,c.subject_id
  FROM (
    SELECT f.organization_id,f.account_id,f.subject_id,f.created_at,f.id
    FROM public.platform_file f
    WHERE (f.state='DELETING' AND f.deletion_after<=clock_timestamp())
      OR (((f.state IN ('STORED','READY') AND f.expires_at<=clock_timestamp())
          OR (f.state IN ('UPLOADING','FAILED') AND NOT f.eof_confirmed
            AND f.created_at<clock_timestamp()-interval '24 hours'
            AND COALESCE(f.write_deadline,f.created_at+interval '30 minutes')
              <clock_timestamp()-interval '120 seconds'))
        AND NOT EXISTS(SELECT 1 FROM public.platform_file_reference r WHERE r.file_id=f.id)
        AND NOT EXISTS(SELECT 1 FROM public.platform_file_read_pin p WHERE p.file_id=f.id
          AND p.lease_until>clock_timestamp()))
    ORDER BY f.created_at,f.id LIMIT 500
  ) c
  ORDER BY c.organization_id,c.account_id,c.created_at,c.id LIMIT 100;
$$;
REVOKE ALL ON FUNCTION repricer_file_retention_scopes() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_file_retention_scopes() TO repricer_worker;

--changeset repricer:platform-job-reclaim-001 splitStatements:false
CREATE OR REPLACE FUNCTION repricer_claim_job(p_worker uuid,p_lane text,p_slots integer)
RETURNS TABLE(id uuid,organization_id uuid,account_id uuid,subject_id uuid,job_type text,fence bigint)
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE chosen uuid; chosen_org uuid; chosen_account uuid; allowed integer; turn_number bigint;
BEGIN
  allowed := CASE p_lane WHEN 'fetch' THEN 4 WHEN 'canonicalization' THEN 2
    WHEN 'calculation' THEN 2 WHEN 'execution' THEN 2 WHEN 'service' THEN 1 ELSE 0 END;
  IF allowed=0 OR p_slots<>allowed THEN RAISE EXCEPTION 'invalid lane'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('repricer:claim:'||p_lane,0));
  IF (SELECT count(*) FROM public.platform_job j WHERE j.lane=p_lane
      AND j.state='RUNNING' AND j.lease_until>clock_timestamp())>=allowed THEN RETURN; END IF;
  SELECT j.id,COALESCE(j.organization_id,'00000000-0000-0000-0000-000000000000'::uuid),
    COALESCE(j.account_id,'00000000-0000-0000-0000-000000000000'::uuid)
    INTO chosen,chosen_org,chosen_account
  FROM public.platform_job j
  LEFT JOIN public.platform_job_organization_turn ot ON ot.lane=j.lane
    AND ot.organization_key=COALESCE(j.organization_id,'00000000-0000-0000-0000-000000000000'::uuid)
  LEFT JOIN public.platform_job_account_turn act ON act.lane=j.lane
    AND act.organization_key=COALESCE(j.organization_id,'00000000-0000-0000-0000-000000000000'::uuid)
    AND act.account_key=COALESCE(j.account_id,'00000000-0000-0000-0000-000000000000'::uuid)
  WHERE j.lane=p_lane AND ((j.state IN ('READY','WAITING') AND j.due_at<=clock_timestamp())
      OR (j.state='RUNNING' AND j.lease_until<clock_timestamp()))
  ORDER BY ot.last_turn NULLS FIRST,act.last_turn NULLS FIRST,j.due_at,j.id
  LIMIT 1 FOR UPDATE OF j SKIP LOCKED;
  IF chosen IS NULL THEN RETURN; END IF;
  turn_number := nextval('public.platform_job_turn');
  INSERT INTO public.platform_job_organization_turn(lane,organization_key,last_turn)
    VALUES(p_lane,chosen_org,turn_number)
    ON CONFLICT(lane,organization_key) DO UPDATE SET last_turn=EXCLUDED.last_turn;
  INSERT INTO public.platform_job_account_turn(lane,organization_key,account_key,last_turn)
    VALUES(p_lane,chosen_org,chosen_account,turn_number)
    ON CONFLICT(lane,organization_key,account_key) DO UPDATE SET last_turn=EXCLUDED.last_turn;
  RETURN QUERY UPDATE public.platform_job j SET
    attempt=CASE WHEN j.first_failure_at<clock_timestamp()-interval '30 minutes' THEN 7
      WHEN j.state='RUNNING' THEN LEAST(7,j.attempt+1) ELSE j.attempt END,
    first_failure_at=CASE WHEN j.state='RUNNING'
      THEN COALESCE(j.first_failure_at,j.updated_at) ELSE j.first_failure_at END,
    state='RUNNING',lease_owner=p_worker,
    lease_until=clock_timestamp()+interval '60 seconds',fence=j.fence+1,updated_at=clock_timestamp()
    WHERE j.id=chosen RETURNING j.id,j.organization_id,j.account_id,j.subject_id,j.job_type,j.fence;
END;
$$;
