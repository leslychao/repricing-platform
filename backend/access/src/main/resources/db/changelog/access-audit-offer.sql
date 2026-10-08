--liquibase formatted sql
--changeset access:audit-offer-001
ALTER TABLE access_audit ADD COLUMN offer_id uuid;
ALTER TABLE access_audit ADD CONSTRAINT access_audit_offer_scope
  CHECK(offer_id IS NULL OR account_id IS NOT NULL);
ALTER TABLE access_audit ADD CONSTRAINT access_audit_offer_fk
  FOREIGN KEY(organization_id,account_id,offer_id)
  REFERENCES marketplace_offer(organization_id,account_id,id);
COMMENT ON COLUMN access_audit.offer_id IS
  'Exact immutable product subject supplied by the business owner; never inferred from free text';
CREATE INDEX access_audit_offer ON access_audit
  (organization_id,account_id,offer_id,occurred_at DESC,id) WHERE offer_id IS NOT NULL;

UPDATE access_audit a SET offer_id=o.id FROM marketplace_offer o
WHERE a.organization_id=o.organization_id AND a.account_id=o.account_id AND a.target_id=o.id;
UPDATE access_audit a SET offer_id=d.offer_id FROM automation_decision d
WHERE a.organization_id=d.organization_id AND a.account_id=d.account_id AND a.target_id=d.id;
UPDATE access_audit a SET offer_id=d.offer_id
FROM automation_command c JOIN automation_decision d
  ON d.organization_id=c.organization_id AND d.account_id=c.account_id AND d.id=c.decision_id
WHERE a.organization_id=c.organization_id AND a.account_id=c.account_id AND a.target_id=c.id;
UPDATE access_audit a SET offer_id=o.offer_id FROM marketplace_competitor_observation o
WHERE a.organization_id=o.organization_id AND a.account_id=o.account_id
  AND a.target_id=o.id AND o.current_revision;
