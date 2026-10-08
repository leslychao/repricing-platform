--liquibase formatted sql
--changeset repricer:platform-outbox-pressure-001
ALTER TABLE platform_outbox ADD COLUMN pressure_bytes bigint NOT NULL DEFAULT 0 CHECK(pressure_bytes>=0);
UPDATE platform_outbox SET pressure_bytes=octet_length(payload::text) WHERE delivered_at IS NULL;
CREATE TABLE platform_outbox_pressure (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  pending_bytes bigint NOT NULL CHECK(pending_bytes>=0),
  paused boolean NOT NULL
);
COMMENT ON TABLE platform_outbox_pressure IS 'owner=platform.outbox; scope=private global admission metadata; grain=pending delivery bytes; key=singleton; repeat=per-envelope counted bytes; history=current HIGH 1GiB LOW 512MiB hysteresis';
INSERT INTO platform_outbox_pressure(singleton,pending_bytes,paused)
  SELECT true,COALESCE(sum(pressure_bytes),0),COALESCE(sum(pressure_bytes),0)>=1073741824
  FROM platform_outbox;
REVOKE ALL ON platform_outbox_pressure FROM PUBLIC,repricer_api,repricer_worker;
--changeset repricer:platform-outbox-pressure-002 splitStatements:false
CREATE FUNCTION repricer_outbox_pressure(p_id uuid) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public SET row_security=off AS $$
DECLARE old_bytes bigint; next_bytes bigint;
BEGIN
  SELECT o.pressure_bytes,CASE WHEN o.delivered_at IS NULL THEN octet_length(o.payload::text) ELSE 0 END
    INTO old_bytes,next_bytes FROM public.platform_outbox o
    WHERE o.id=p_id AND o.organization_id=NULLIF(current_setting('app.organization_id',true),'')::uuid
      AND o.account_id IS NOT DISTINCT FROM NULLIF(current_setting('app.account_id',true),'')::uuid
      AND current_setting('app.authorized',true)='true' FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'outbox scope unavailable'; END IF;
  IF old_bytes=next_bytes THEN RETURN; END IF;
  UPDATE public.platform_outbox SET pressure_bytes=next_bytes WHERE id=p_id;
  UPDATE public.platform_outbox_pressure SET pending_bytes=pending_bytes+next_bytes-old_bytes,
    paused=CASE WHEN pending_bytes+next_bytes-old_bytes>=1073741824 THEN true
      WHEN pending_bytes+next_bytes-old_bytes<=536870912 THEN false ELSE paused END
    WHERE singleton;
END;
$$;
CREATE FUNCTION repricer_heavy_admission() RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,public AS $$
  SELECT NOT paused FROM public.platform_outbox_pressure WHERE singleton
$$;
REVOKE ALL ON FUNCTION repricer_outbox_pressure(uuid),repricer_heavy_admission() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION repricer_outbox_pressure(uuid),repricer_heavy_admission()
  TO repricer_api,repricer_worker;
