#!/bin/sh
set -eu
: "${API_DB_PASSWORD:?API_DB_PASSWORD required}"
: "${WORKER_DB_PASSWORD:?WORKER_DB_PASSWORD required}"
: "${MIGRATION_DB_PASSWORD:?MIGRATION_DB_PASSWORD required}"
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD required}"
psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1 \
  --set api_password="$API_DB_PASSWORD" --set worker_password="$WORKER_DB_PASSWORD" \
  --set migration_password="$MIGRATION_DB_PASSWORD" --set keycloak_password="$KEYCLOAK_DB_PASSWORD" <<'SQL'
CREATE ROLE repricer_migrator LOGIN PASSWORD :'migration_password' NOSUPERUSER BYPASSRLS;
CREATE ROLE repricer_api LOGIN PASSWORD :'api_password' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE repricer_worker LOGIN PASSWORD :'worker_password' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE keycloak LOGIN PASSWORD :'keycloak_password' NOSUPERUSER NOBYPASSRLS;
CREATE DATABASE repricer OWNER repricer_migrator;
CREATE DATABASE keycloak OWNER keycloak;
REVOKE ALL ON DATABASE repricer FROM PUBLIC;
GRANT CONNECT ON DATABASE repricer TO repricer_api, repricer_worker;
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
SQL
psql --username "$POSTGRES_USER" --dbname repricer --set ON_ERROR_STOP=1 <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO repricer_api, repricer_worker;
SQL
