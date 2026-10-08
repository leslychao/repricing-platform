--liquibase formatted sql

--changeset marketplace:source-stage-retention-1
ALTER TABLE marketplace_sync_run ADD COLUMN stage_deletion_after timestamptz;
COMMENT ON COLUMN marketplace_sync_run.stage_deletion_after IS 'Temporary source staging expires immediately on mark; deletion waits at least 24 hours and terminal job/run recheck; raw and canonical evidence are unaffected';
CREATE INDEX marketplace_sync_run_stage_retention ON marketplace_sync_run(stage_deletion_after,completed_at,id)
  WHERE state IN ('PUBLISHED','INCOMPLETE','FAILED');
GRANT DELETE ON marketplace_offer_stage,marketplace_promotion_stage,marketplace_placement_stage,
  marketplace_stock_stage,marketplace_history_stage,marketplace_category_stage TO repricer_worker;

--changeset marketplace:source-stage-retention-scopes-1 splitStatements:false
CREATE FUNCTION repricer_source_stage_retention_scopes()
RETURNS TABLE(organization_id uuid,account_id uuid,subject_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
  SELECT DISTINCT ON(r.organization_id,r.account_id) r.organization_id,r.account_id,j.subject_id
  FROM public.marketplace_sync_run r JOIN public.platform_job j
    ON (j.organization_id,j.account_id,j.id)=(r.organization_id,r.account_id,r.job_id)
  WHERE r.state IN ('PUBLISHED','INCOMPLETE','FAILED')
    AND j.state IN ('SUCCEEDED','BLOCKED','DEAD','CANCELLED')
    AND GREATEST(r.completed_at,j.updated_at)<clock_timestamp()-interval '7 days'
    AND (r.stage_deletion_after IS NULL OR r.stage_deletion_after<=clock_timestamp())
    AND (EXISTS(SELECT 1 FROM public.marketplace_offer_stage s WHERE s.run_id=r.id)
      OR EXISTS(SELECT 1 FROM public.marketplace_promotion_stage s WHERE s.run_id=r.id)
      OR EXISTS(SELECT 1 FROM public.marketplace_placement_stage s WHERE s.run_id=r.id)
      OR EXISTS(SELECT 1 FROM public.marketplace_stock_stage s WHERE s.run_id=r.id)
      OR EXISTS(SELECT 1 FROM public.marketplace_history_stage s WHERE s.run_id=r.id)
      OR EXISTS(SELECT 1 FROM public.marketplace_category_stage s WHERE s.run_id=r.id))
  ORDER BY r.organization_id,r.account_id,GREATEST(r.completed_at,j.updated_at),r.id LIMIT 100
$$;
REVOKE ALL ON FUNCTION repricer_source_stage_retention_scopes() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_source_stage_retention_scopes() TO repricer_worker;

--changeset marketplace:source-stage-retention-scopes-2 splitStatements:false
CREATE OR REPLACE FUNCTION repricer_source_stage_retention_scopes()
RETURNS TABLE(organization_id uuid,account_id uuid,subject_id uuid)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
  SELECT DISTINCT ON(r.organization_id,r.account_id) r.organization_id,r.account_id,j.subject_id
  FROM public.marketplace_sync_run r JOIN public.platform_job j
    ON (j.organization_id,j.account_id,j.id)=(r.organization_id,r.account_id,r.job_id)
  WHERE r.state IN ('PUBLISHED','INCOMPLETE','FAILED')
    AND j.state IN ('SUCCEEDED','BLOCKED','DEAD','CANCELLED')
    AND GREATEST(r.completed_at,j.updated_at)<clock_timestamp()-interval '7 days'
    AND (r.stage_deletion_after IS NULL OR r.stage_deletion_after<=clock_timestamp())
    AND (EXISTS(SELECT 1 FROM public.marketplace_offer_stage s WHERE s.run_id=r.id)
      OR EXISTS(SELECT 1 FROM public.marketplace_promotion_stage s WHERE s.run_id=r.id)
      OR EXISTS(SELECT 1 FROM public.marketplace_placement_stage s WHERE s.run_id=r.id)
      OR EXISTS(SELECT 1 FROM public.marketplace_stock_stage s WHERE s.run_id=r.id)
      OR EXISTS(SELECT 1 FROM public.marketplace_history_stage s WHERE s.run_id=r.id)
      OR EXISTS(SELECT 1 FROM public.marketplace_category_stage s
        WHERE (s.organization_id,s.account_id,s.run_id)=(r.organization_id,r.account_id,r.id)))
  ORDER BY r.organization_id,r.account_id,GREATEST(r.completed_at,j.updated_at),r.id LIMIT 100
$$;

--changeset marketplace:source-stage-retention-metadata
COMMENT ON TABLE marketplace_offer_stage IS 'owner=marketplace; scope=exact account and sync run; grain=one candidate offer; key=run/external id; repeat=same traversal page; history=temporary parsed working data; aggregation=none; retention=terminal run and job older than seven days, mark then 24h recheck, bounded deletion';
COMMENT ON TABLE marketplace_promotion_stage IS 'owner=marketplace; scope=exact account and sync run; grain=one candidate promotion/SKU row; key=run/promotion/SKU; repeat=exact retained raw; history=temporary parsed working data; aggregation=none; retention=terminal run and job older than seven days, mark then 24h recheck, bounded deletion';
COMMENT ON TABLE marketplace_placement_stage IS 'owner=marketplace; scope=exact account and sync run; grain=one candidate offer placement; key=run/campaign/offer; repeat=exact retained raw; history=temporary parsed working data; aggregation=none; retention=terminal run and job older than seven days, mark then 24h recheck, bounded deletion';
COMMENT ON TABLE marketplace_stock_stage IS 'owner=marketplace; scope=exact account and sync run; grain=one observed candidate physical stock pool or explicitly unpublishable aggregate; key=run/external pool id; repeat=identical quantities only; history=temporary parsed working data; aggregation=overlapping pools never summed; retention=terminal run and job older than seven days, mark then 24h recheck, bounded deletion';
COMMENT ON TABLE marketplace_history_stage IS 'owner=marketplace; scope=exact account and sync run; grain=one bounded supplier history record; key=run/source kind/external id; repeat=exact retained raw; history=temporary parsed working data, immutable observations retained separately; aggregation=none; retention=terminal run and job older than seven days, mark then 24h recheck, bounded deletion';
COMMENT ON TABLE marketplace_category_stage IS 'owner=marketplace; scope=exact account and sync run; grain=one bounded parsed official node; key=run/sequence; repeat=immutable raw reparse inserts identical nodes only; history=temporary parsed working data, publication retains raw; aggregation=none; retention=terminal run and job older than seven days, mark then 24h recheck, bounded deletion';
