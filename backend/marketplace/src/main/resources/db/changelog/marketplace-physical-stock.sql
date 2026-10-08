--liquibase formatted sql

--changeset repricer:marketplace-physical-stock
ALTER TABLE marketplace_stock_pool ADD COLUMN physical_quantity numeric(38,12) CHECK(physical_quantity>=0);
ALTER TABLE marketplace_stock_stage ADD COLUMN physical_quantity numeric(38,12) CHECK(physical_quantity>=0);
ALTER TABLE marketplace_stock_observation ADD COLUMN physical_quantity numeric(38,12) CHECK(physical_quantity>=0);
ALTER TABLE marketplace_warehouse ADD COLUMN group_name text;
