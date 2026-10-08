--liquibase formatted sql
--changeset repricer:platform-metadata-retention-001
ALTER TABLE platform_job ADD COLUMN deletion_after timestamptz,
  ADD COLUMN retention_checked_at timestamptz;
ALTER TABLE platform_outbox ADD COLUMN deletion_after timestamptz,
  ADD COLUMN retention_checked_at timestamptz;
CREATE INDEX platform_job_retention_due ON platform_job(retention_checked_at NULLS FIRST,updated_at,id)
  WHERE state IN ('SUCCEEDED','CANCELLED');
CREATE INDEX platform_outbox_retention_due ON platform_outbox(retention_checked_at NULLS FIRST,delivered_at,id)
  WHERE delivered_at IS NOT NULL;
--changeset repricer:platform-metadata-retention-functions-001 splitStatements:false
CREATE FUNCTION repricer_metadata_referenced(p_parent regclass,p_id uuid) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE relation record; is_used boolean;
BEGIN
  IF p_parent NOT IN ('public.platform_job'::regclass,'public.platform_outbox'::regclass)
    OR p_id IS NULL THEN RAISE EXCEPTION 'invalid metadata parent'; END IF;
  FOR relation IN
    SELECT c.conrelid::regclass AS child_table,
      string_agg(format('child.%I=parent.%I',a.attname,b.attname),' AND ' ORDER BY keys.ordinality) AS predicate
    FROM pg_constraint c
    CROSS JOIN LATERAL unnest(c.conkey,c.confkey) WITH ORDINALITY AS keys(child_key,parent_key,ordinality)
    JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=keys.child_key
    JOIN pg_attribute b ON b.attrelid=c.confrelid AND b.attnum=keys.parent_key
    WHERE c.contype='f' AND c.confrelid=p_parent
    GROUP BY c.oid,c.conrelid
  LOOP
    EXECUTE format('SELECT EXISTS(SELECT 1 FROM %s child JOIN %s parent ON %s WHERE parent.id=$1)',
      relation.child_table,p_parent,relation.predicate) INTO is_used USING p_id;
    IF is_used THEN RETURN true; END IF;
  END LOOP;
  RETURN false;
END;
$$;
REVOKE ALL ON FUNCTION repricer_metadata_referenced(regclass,uuid)
  FROM PUBLIC,repricer_api,repricer_worker;

CREATE FUNCTION repricer_cleanup_platform_metadata() RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE item record; examined integer:=0; is_used boolean;
BEGIN
  IF NOT pg_try_advisory_xact_lock(hashtextextended('repricer-platform-metadata-retention',0))
    THEN RETURN 0; END IF;
  FOR item IN
    SELECT o.id,o.deletion_after FROM public.platform_outbox o
    WHERE o.delivered_at<clock_timestamp()-interval '30 days'
      AND (o.deletion_after IS NULL OR o.deletion_after<=clock_timestamp())
    ORDER BY o.retention_checked_at NULLS FIRST,o.delivered_at,o.id
    LIMIT 250 FOR UPDATE OF o SKIP LOCKED
  LOOP
    examined:=examined+1;
    UPDATE public.platform_outbox SET retention_checked_at=clock_timestamp() WHERE id=item.id;
    SELECT EXISTS(SELECT 1 FROM public.platform_job j WHERE j.job_type='OUTBOX_DELIVERY'
      AND j.business_key=item.id::text AND j.state NOT IN ('SUCCEEDED','CANCELLED')) INTO is_used;
    IF is_used OR public.repricer_metadata_referenced('public.platform_outbox',item.id) THEN CONTINUE; END IF;
    IF item.deletion_after IS NULL THEN
      UPDATE public.platform_outbox SET deletion_after=clock_timestamp()+interval '24 hours' WHERE id=item.id;
    ELSE
      DELETE FROM public.platform_outbox WHERE id=item.id AND delivered_at IS NOT NULL
        AND pressure_bytes=0;
    END IF;
  END LOOP;
  FOR item IN
    SELECT j.id,j.deletion_after FROM public.platform_job j
    WHERE j.state IN ('SUCCEEDED','CANCELLED') AND j.updated_at<clock_timestamp()-interval '90 days'
      AND (j.lease_until IS NULL OR j.lease_until<clock_timestamp())
      AND (j.deletion_after IS NULL OR j.deletion_after<=clock_timestamp())
    ORDER BY j.retention_checked_at NULLS FIRST,j.updated_at,j.id
    LIMIT (500-examined) FOR UPDATE OF j SKIP LOCKED
  LOOP
    examined:=examined+1;
    UPDATE public.platform_job SET retention_checked_at=clock_timestamp() WHERE id=item.id;
    IF public.repricer_metadata_referenced('public.platform_job',item.id) THEN CONTINUE; END IF;
    IF item.deletion_after IS NULL THEN
      UPDATE public.platform_job SET deletion_after=clock_timestamp()+interval '24 hours' WHERE id=item.id;
    ELSE
      DELETE FROM public.platform_job WHERE id=item.id AND state IN ('SUCCEEDED','CANCELLED');
    END IF;
  END LOOP;
  RETURN examined;
END;
$$;
REVOKE ALL ON FUNCTION repricer_cleanup_platform_metadata() FROM PUBLIC,repricer_api;
GRANT EXECUTE ON FUNCTION repricer_cleanup_platform_metadata() TO repricer_worker;
--changeset repricer:platform-file-retention-owner-scopes-001 splitStatements:false
CREATE OR REPLACE FUNCTION repricer_file_retention_scopes()
RETURNS TABLE(organization_id uuid,account_id uuid,subject_id uuid)
LANGUAGE sql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
  SELECT DISTINCT ON (c.organization_id,c.account_id)
    c.organization_id,c.account_id,c.subject_id
  FROM (
    SELECT f.organization_id,f.account_id,f.subject_id,f.created_at,f.id
    FROM public.platform_file f
    WHERE ((f.state='DELETING' AND f.deletion_after<=clock_timestamp())
      OR (f.state IN ('STORED','READY') AND f.expires_at<=clock_timestamp())
      OR (f.state IN ('UPLOADING','FAILED') AND NOT f.eof_confirmed
        AND f.created_at<clock_timestamp()-interval '24 hours'
        AND COALESCE(f.write_deadline,f.created_at+interval '30 minutes')
          <clock_timestamp()-interval '120 seconds'))
      AND NOT EXISTS(SELECT 1 FROM public.platform_job j WHERE j.job_type='FILE_RETENTION'
        AND j.organization_id=f.organization_id AND j.account_id IS NOT DISTINCT FROM f.account_id
        AND j.created_at>clock_timestamp()-interval '55 minutes')
    ORDER BY f.created_at,f.id LIMIT 500
  ) c ORDER BY c.organization_id,c.account_id,c.created_at,c.id LIMIT 100;
$$;
