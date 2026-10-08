--liquibase formatted sql
--changeset repricer:runtime-grants-001 splitStatements:false
DO $$
DECLARE item record;
BEGIN
  FOR item IN SELECT tablename FROM pg_tables WHERE schemaname='public'
    AND tablename ~ '^(platform|access|marketplace|economics|automation|app)_'
    AND tablename NOT IN ('marketplace_quota_bucket','platform_file_work')
  LOOP
    EXECUTE format('GRANT SELECT,INSERT ON TABLE public.%I TO repricer_api,repricer_worker',item.tablename);
  END LOOP;
END;
$$;
GRANT USAGE ON SCHEMA public TO repricer_api,repricer_worker;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO repricer_api,repricer_worker;
GRANT UPDATE ON platform_job,platform_outbox,platform_file,platform_file_part,
  access_user,access_organization,access_membership,access_account_permission,access_invitation,
  marketplace_account,marketplace_connection,marketplace_connection_candidate,
  marketplace_offer,marketplace_source,
  marketplace_sync_run,marketplace_raw_page,marketplace_offer_stage,marketplace_stock_pool,
  marketplace_external_call,marketplace_campaign,marketplace_stock_stage,
  marketplace_promotion,marketplace_promotion_offer,marketplace_promotion_stage,
  marketplace_placement,marketplace_placement_stage,
  marketplace_payment,marketplace_return,marketplace_order_item,marketplace_history_day,
  marketplace_history_stage,
  economics_cost_head,economics_tax_head,
  economics_recognition_guard,economics_risk_permission,economics_risk_usage,
  economics_resource_guard,economics_resource_hold,economics_resource_obligation,
  automation_policy,automation_assignment,automation_decision,automation_command,
  app_view_state,app_notification_cursor,app_import TO repricer_api,repricer_worker;
GRANT UPDATE(current) ON marketplace_economic_terms,marketplace_commercial_state
  TO repricer_api,repricer_worker;
GRANT UPDATE(status,reason) ON marketplace_profile TO repricer_api,repricer_worker;
GRANT UPDATE(state,raw_response_file_id) ON automation_send_attempt TO repricer_api,repricer_worker;
GRANT UPDATE(stock_state,episode_base_price,consecutive_decreases,last_admission,revision)
  ON automation_runtime TO repricer_api,repricer_worker;
GRANT UPDATE(paused,revision) ON automation_stop TO repricer_api,repricer_worker;
GRANT UPDATE(state,reason,revision,accepted_quantity) ON automation_scenario TO repricer_api,repricer_worker;
GRANT UPDATE(completed) ON automation_scenario_scope TO repricer_api,repricer_worker;
GRANT UPDATE(quantity) ON automation_scenario_demand TO repricer_api,repricer_worker;
GRANT UPDATE(current_revision) ON marketplace_competitor_observation TO repricer_api,repricer_worker;
GRANT UPDATE(current_revision) ON economics_offer_price_bounds TO repricer_api,repricer_worker;
GRANT UPDATE(state,applied_at) ON economics_import TO repricer_api,repricer_worker;
GRANT UPDATE(state,applied_at) ON marketplace_competitor_import TO repricer_api,repricer_worker;
GRANT UPDATE(current_revision) ON economics_financial_event,economics_safety_envelope
  TO repricer_api,repricer_worker;
GRANT DELETE ON platform_file_part,platform_file_reference,marketplace_offer_stage,
  marketplace_stock_stage,
  marketplace_promotion_stage,
  marketplace_placement_stage,
  automation_scenario_claim,automation_field_claim TO repricer_api,repricer_worker;
--changeset repricer:runtime-grants-002
GRANT SELECT,INSERT ON marketplace_report,marketplace_report_row,
  marketplace_offer_observation,marketplace_stock_observation,
  automation_auto_activation,automation_decision_auto,automation_generation
  TO repricer_api,repricer_worker;
GRANT UPDATE ON marketplace_report TO repricer_api,repricer_worker;
GRANT UPDATE(requested_generation,completed_generation,cycle,first_event_at,due_at)
  ON automation_generation TO repricer_api,repricer_worker;
--changeset repricer:runtime-grants-003
REVOKE ALL ON platform_job_organization_turn,platform_job_account_turn
  FROM repricer_api,repricer_worker;
REVOKE ALL ON SEQUENCE platform_job_turn FROM repricer_api,repricer_worker;
GRANT SELECT,INSERT ON automation_source_batch TO repricer_api,repricer_worker;
--changeset repricer:runtime-grants-004
GRANT SELECT,INSERT ON economics_ledger_publication TO repricer_api,repricer_worker;
GRANT UPDATE(processed_rows,state) ON economics_ledger_publication TO repricer_api,repricer_worker;
--changeset repricer:runtime-grants-005
REVOKE ALL ON marketplace_quota_waiter,marketplace_quota_turn,marketplace_quota_grant,
  marketplace_quota_resource,marketplace_quota_report
  FROM repricer_api,repricer_worker;
--changeset repricer:runtime-grants-006
GRANT UPDATE(permissions) ON app_selection TO repricer_api,repricer_worker;
--changeset repricer:runtime-grants-007
GRANT SELECT,INSERT ON marketplace_demand_observation TO repricer_api,repricer_worker;
--changeset repricer:runtime-grants-008
GRANT UPDATE ON automation_sweep TO repricer_api,repricer_worker;
--changeset repricer:runtime-grants-009
REVOKE ALL ON platform_outbox_pressure FROM repricer_api,repricer_worker;
--changeset repricer:runtime-grants-010
REVOKE ALL ON marketplace_page_pressure FROM repricer_api,repricer_worker;

--changeset repricer:runtime-grants-011
GRANT SELECT,INSERT,UPDATE ON economics_accounting_basis TO repricer_api,repricer_worker;
GRANT SELECT,INSERT ON automation_economics_projection,automation_sweep
  TO repricer_api,repricer_worker;

--changeset repricer:runtime-grants-012
REVOKE UPDATE ON marketplace_profile FROM repricer_api,repricer_worker;

--changeset repricer:runtime-grants-013
REVOKE UPDATE(status,reason) ON marketplace_profile FROM repricer_api,repricer_worker;

--changeset repricer:runtime-grants-014
GRANT SELECT,INSERT ON marketplace_financial_observation TO repricer_api,repricer_worker;

--changeset repricer:runtime-grants-015
GRANT SELECT,INSERT,UPDATE ON marketplace_warehouse TO repricer_api,repricer_worker;

--changeset repricer:runtime-grants-016
GRANT SELECT,INSERT,UPDATE ON marketplace_category TO repricer_api,repricer_worker;
GRANT SELECT,INSERT,DELETE ON marketplace_category_stage TO repricer_api,repricer_worker;

--changeset repricer:runtime-grants-017
-- Idempotent staging is append-only; only worker retention removes terminal temporary rows.
REVOKE DELETE ON marketplace_category_stage,marketplace_offer_stage,marketplace_promotion_stage,
  marketplace_placement_stage,marketplace_stock_stage,marketplace_history_stage FROM repricer_api;
