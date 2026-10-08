--liquibase formatted sql

--changeset repricer:recognition-upgrade-2
-- Reuse the existing bounded financial rebuild handler for retained source publications.
-- The author remains the source-file subject; the job uses its declared service permissions.
WITH affected AS (
  SELECT DISTINCT ON (p.organization_id,p.account_id)
    p.organization_id,p.account_id,f.subject_id,COALESCE(b.revision,0) AS revision
  FROM economics_ledger_publication p
  JOIN platform_file f ON f.organization_id=p.organization_id
    AND f.account_id=p.account_id AND f.id=p.raw_file_id
  LEFT JOIN economics_accounting_basis b ON b.organization_id=p.organization_id
    AND b.account_id=p.account_id
  WHERE p.recognition_version<2 AND p.state IN ('PREPARING','PUBLISHED')
  ORDER BY p.organization_id,p.account_id,p.generated_at DESC,p.id
)
INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,
  business_key,payload,state,due_at)
SELECT gen_random_uuid(),organization_id,account_id,subject_id,'FINANCIAL_REBUILD',
  'canonicalization',revision::text||':recognition:2',jsonb_build_object('revision',revision),
  'READY',clock_timestamp()
FROM affected
ON CONFLICT(scope_key,job_type,business_key) DO NOTHING;

--changeset repricer:recognition-upgrade-3
-- Evidence certainty is independent of economic completeness; old events remain immutable.
WITH affected AS (
  SELECT DISTINCT ON (p.organization_id,p.account_id)
    p.organization_id,p.account_id,f.subject_id,COALESCE(b.revision,0) AS revision
  FROM economics_ledger_publication p
  JOIN platform_file f ON f.organization_id=p.organization_id
    AND f.account_id=p.account_id AND f.id=p.raw_file_id
  LEFT JOIN economics_accounting_basis b ON b.organization_id=p.organization_id
    AND b.account_id=p.account_id
  WHERE p.recognition_version<3 AND p.state IN ('PREPARING','PUBLISHED')
  ORDER BY p.organization_id,p.account_id,p.generated_at DESC,p.id
)
INSERT INTO platform_job(id,organization_id,account_id,subject_id,job_type,lane,
  business_key,payload,state,due_at)
SELECT gen_random_uuid(),organization_id,account_id,subject_id,'FINANCIAL_REBUILD',
  'canonicalization',revision::text||':recognition:3',jsonb_build_object('revision',revision),
  'READY',clock_timestamp()
FROM affected
ON CONFLICT(scope_key,job_type,business_key) DO NOTHING;
