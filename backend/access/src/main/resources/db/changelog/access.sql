--liquibase formatted sql
--changeset repricer:access-001
CREATE TABLE access_user (
  id uuid PRIMARY KEY,
  issuer text NOT NULL,
  subject text NOT NULL,
  display_name text NOT NULL,
  email text NOT NULL,
  email_verified boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(issuer,subject)
);
COMMENT ON TABLE access_user IS 'owner=access; scope=authenticated self; grain=issuer+subject identity; key=issuer,subject; repeat=update confirmed profile only; history=stable identity';
ALTER TABLE access_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_user FORCE ROW LEVEL SECURITY;
CREATE POLICY access_user_self ON access_user USING (
  id=NULLIF(current_setting('app.subject_id',true),'')::uuid
) WITH CHECK (id=NULLIF(current_setting('app.subject_id',true),'')::uuid);

CREATE TABLE access_organization (
  id uuid PRIMARY KEY,
  name text NOT NULL CHECK(length(name) BETWEEN 1 AND 160),
  revision bigint NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE access_organization IS 'owner=access; scope=organization; grain=one company; key=UUID; repeat=creation request key; history=revision';
CREATE TABLE access_membership (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES access_organization(id),
  subject_id uuid NOT NULL REFERENCES access_user(id),
  role text NOT NULL CHECK(role IN ('OWNER','MEMBER')),
  permissions text[] NOT NULL DEFAULT '{}',
  active boolean NOT NULL DEFAULT true,
  revision bigint NOT NULL DEFAULT 1,
  access_generation bigint NOT NULL DEFAULT 1 CHECK(access_generation>0),
  revoked_revision bigint,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(organization_id,subject_id),
  UNIQUE(organization_id,id)
);
CREATE UNIQUE INDEX access_one_owner ON access_membership(organization_id)
  WHERE role='OWNER' AND active;
COMMENT ON TABLE access_membership IS 'owner=access; scope=organization+self discovery; grain=one person membership in one company; key=organization,subject; repeat=unique membership; history=monotonic revision and revocation';
ALTER TABLE access_membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_membership FORCE ROW LEVEL SECURITY;
CREATE POLICY access_membership_scope ON access_membership USING (
  subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid
  OR (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true')
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true'
);
ALTER TABLE access_organization ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_organization FORCE ROW LEVEL SECURITY;
CREATE POLICY access_organization_scope ON access_organization USING (
  EXISTS(SELECT 1 FROM access_membership m WHERE m.organization_id=access_organization.id
    AND m.subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid AND m.active)
  OR (id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true')
) WITH CHECK (id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true');

CREATE TABLE access_account_permission (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  account_id uuid NOT NULL,
  subject_id uuid NOT NULL,
  role text NOT NULL CHECK(role IN ('ADMIN','MANAGER','OPERATOR','VIEWER')),
  permissions text[] NOT NULL DEFAULT '{}',
  active boolean NOT NULL DEFAULT true,
  revision bigint NOT NULL DEFAULT 1,
  membership_generation bigint NOT NULL DEFAULT 1 CHECK(membership_generation>0),
  revoked_revision bigint,
  revoked_at timestamptz,
  UNIQUE(organization_id,account_id,subject_id),
  FOREIGN KEY(organization_id,subject_id) REFERENCES access_membership(organization_id,subject_id)
);
COMMENT ON TABLE access_account_permission IS 'owner=access; scope=exact account+self discovery; grain=one direct account role; key=organization,account,subject; repeat=expected revision; history=revision and revocation';
ALTER TABLE access_account_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_account_permission FORCE ROW LEVEL SECURITY;
CREATE POLICY access_account_permission_scope ON access_account_permission USING (
  subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid
  OR (organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
    AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
    AND current_setting('app.authorized',true)='true')
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);

CREATE TABLE access_invitation (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES access_organization(id),
  account_id uuid,
  inviter_id uuid NOT NULL,
  email text NOT NULL,
  token_hash char(64) NOT NULL UNIQUE,
  token_path text NOT NULL UNIQUE,
  token_version bigint NOT NULL CHECK(token_version>0),
  issued_revision bigint NOT NULL,
  inviter_membership_revision bigint NOT NULL,
  inviter_account_revision bigint,
  role text NOT NULL CHECK(role IN ('MEMBER','ADMIN','MANAGER','OPERATOR','VIEWER')),
  permissions text[] NOT NULL DEFAULT '{}',
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  consumed_by uuid REFERENCES access_user(id),
  revoked_at timestamptz,
  mail_state text NOT NULL DEFAULT 'READY' CHECK(mail_state IN ('READY','SENDING','SENT','UNKNOWN','CANCELLED')),
  revision bigint NOT NULL DEFAULT 1,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CHECK((account_id IS NULL)=(role='MEMBER'))
);
COMMENT ON TABLE access_invitation IS 'owner=access; scope=organization or exact account; grain=one single-use grant offer; key=token hash; repeat=one consumption; history=expiry and revocation, no raw token';
ALTER TABLE access_invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_invitation FORCE ROW LEVEL SECURITY;
CREATE POLICY access_invitation_scope ON access_invitation USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
CREATE POLICY access_invitation_token_lookup ON access_invitation FOR SELECT USING (
  token_hash=NULLIF(current_setting('app.invitation_hash',true),'')
  AND EXISTS(SELECT 1 FROM access_user u
    WHERE u.id=NULLIF(current_setting('app.subject_id',true),'')::uuid
    AND u.email_verified AND lower(u.email)=lower(access_invitation.email))
);
CREATE POLICY access_user_membership_profile ON access_user FOR SELECT USING (
  current_setting('app.authorized',true)='true'
  AND EXISTS(SELECT 1 FROM access_membership m
    WHERE m.organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
      AND m.subject_id=access_user.id)
);

CREATE TABLE access_audit (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES access_organization(id),
  account_id uuid,
  subject_id uuid NOT NULL,
  action text NOT NULL,
  target_id uuid,
  details text NOT NULL CHECK(length(details)<=4096),
  occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE access_audit IS 'owner=access; scope=organization or exact account; grain=one committed business change; key=UUID in owner transaction; repeat=with deduplicated change; history=immutable at least 12 months';
CREATE INDEX access_audit_scope_time ON access_audit(organization_id,account_id,occurred_at DESC,id DESC);
ALTER TABLE access_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY access_audit_scope ON access_audit USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
--changeset repricer:access-auto-grant-001
CREATE TABLE access_automation_grant (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES access_organization(id),
  account_id uuid NOT NULL,
  assignment_id uuid NOT NULL,
  issuer_id uuid NOT NULL REFERENCES access_user(id),
  revision bigint NOT NULL CHECK(revision>0),
  binding jsonb NOT NULL CHECK(octet_length(binding::text)<=65536),
  active boolean NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(organization_id,account_id,assignment_id,revision)
);
COMMENT ON TABLE access_automation_grant IS 'owner=access; scope=exact account; grain=explicit AUTO delegation bound to assignment/publication/full scope; key=assignment,revision; repeat=caller request; history=immutable binding and revocable active flag';
CREATE UNIQUE INDEX access_automation_grant_active ON access_automation_grant(assignment_id) WHERE active;
ALTER TABLE access_automation_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_automation_grant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON access_automation_grant USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id=NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
GRANT SELECT,INSERT ON access_automation_grant TO repricer_api,repricer_worker;
GRANT UPDATE(active) ON access_automation_grant TO repricer_api,repricer_worker;
--changeset repricer:access-auto-grant-002
ALTER TABLE access_automation_grant ADD COLUMN issuer_membership_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE access_automation_grant ADD COLUMN issuer_account_revision bigint NOT NULL DEFAULT 0;
--changeset repricer:access-auto-grant-003
ALTER TABLE access_automation_grant ADD UNIQUE(organization_id,account_id,id);
