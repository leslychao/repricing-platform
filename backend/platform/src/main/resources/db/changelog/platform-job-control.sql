--liquibase formatted sql
--changeset repricer:platform-job-control-001 splitStatements:false
CREATE FUNCTION repricer_renew_job_lease(p_id uuid,p_fence bigint,p_owner uuid)
RETURNS boolean LANGUAGE plpgsql SECURITY DEFINER
SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE changed integer;
BEGIN
  UPDATE public.platform_job SET lease_until=clock_timestamp()+interval '60 seconds'
    WHERE id=p_id AND fence=p_fence AND lease_owner=p_owner
      AND state='RUNNING' AND lease_until>clock_timestamp();
  GET DIAGNOSTICS changed=ROW_COUNT;
  RETURN changed=1;
END;
$$;
REVOKE ALL ON FUNCTION repricer_renew_job_lease(uuid,bigint,uuid) FROM PUBLIC,repricer_api;
GRANT EXECUTE ON FUNCTION repricer_renew_job_lease(uuid,bigint,uuid) TO repricer_worker;
