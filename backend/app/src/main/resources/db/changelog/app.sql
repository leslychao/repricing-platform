--liquibase formatted sql
--changeset repricer:app-001
CREATE TABLE app_view_state (
  organization_id uuid,
  account_id uuid,
  subject_id uuid NOT NULL REFERENCES access_user(id),
  scope_key text GENERATED ALWAYS AS
    (COALESCE(organization_id::text,'user')||':'||COALESCE(account_id::text,'organization')) STORED,
  view_key text NOT NULL CHECK(length(view_key) BETWEEN 1 AND 160),
  revision bigint NOT NULL DEFAULT 1,
  reset_generation bigint NOT NULL DEFAULT 0,
  state jsonb NOT NULL CHECK(octet_length(state::text)<=65536),
  updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(subject_id,scope_key,view_key)
);
COMMENT ON TABLE app_view_state IS 'owner=app.preferences; scope=self and exact user/organization/account; grain=personal view; key=subject,scope,viewKey; repeat=request+revision; history=latest and reset generation';
ALTER TABLE app_view_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_view_state FORCE ROW LEVEL SECURITY;
CREATE POLICY app_view_state_scope ON app_view_state USING (
  subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid
  AND organization_id IS NOT DISTINCT FROM NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
) WITH CHECK (
  subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid
  AND organization_id IS NOT DISTINCT FROM NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
);

CREATE TABLE app_change_event (
  sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  id uuid NOT NULL UNIQUE,
  organization_id uuid NOT NULL REFERENCES access_organization(id),
  account_id uuid,
  event_type text NOT NULL,
  resource text NOT NULL,
  entity_id uuid,
  revision bigint NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE app_change_event IS 'owner=app.realtime; scope=exact organization/account; grain=committed UI invalidation event; key=producer event UUID; repeat=one recipient receipt; history=append only';
CREATE INDEX app_change_event_scope ON app_change_event(organization_id,account_id,sequence);
ALTER TABLE app_change_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_change_event FORCE ROW LEVEL SECURITY;
CREATE POLICY app_change_event_scope ON app_change_event USING (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
CREATE TABLE app_notification_read (
  event_id uuid NOT NULL REFERENCES app_change_event(id),
  subject_id uuid NOT NULL REFERENCES access_user(id),
  read_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(event_id,subject_id)
);
COMMENT ON TABLE app_notification_read IS 'owner=app.realtime; scope=self; grain=one event read by one recipient; key=event+subject; repeat=unique receipt; history=immutable read marker';
ALTER TABLE app_notification_read ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_notification_read FORCE ROW LEVEL SECURITY;
CREATE POLICY app_notification_self ON app_notification_read USING (
  subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid
) WITH CHECK (subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid
  AND EXISTS(SELECT 1 FROM app_change_event e WHERE e.id=event_id));

CREATE TABLE app_notification_cursor (
  organization_id uuid NOT NULL REFERENCES access_organization(id),
  account_id uuid,
  scope_key text GENERATED ALWAYS AS
    (organization_id::text||':'||COALESCE(account_id::text,'organization')) STORED,
  subject_id uuid NOT NULL REFERENCES access_user(id),
  resource text NOT NULL,
  read_through bigint NOT NULL CHECK(read_through>=0),
  PRIMARY KEY(subject_id,scope_key,resource)
);
COMMENT ON TABLE app_notification_cursor IS 'owner=app.realtime; scope=self and exact organization/account; grain=read watermark per authorized resource; key=subject,scope,resource; repeat=monotonic cursor; history=current';
ALTER TABLE app_notification_cursor ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_notification_cursor FORCE ROW LEVEL SECURITY;
CREATE POLICY app_notification_cursor_scope ON app_notification_cursor USING (
  subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid
  AND organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
) WITH CHECK (
  subject_id=NULLIF(current_setting('app.subject_id',true),'')::uuid
  AND organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
  AND account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
  AND current_setting('app.authorized',true)='true'
);
