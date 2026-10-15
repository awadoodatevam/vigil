#!/usr/bin/env bash
# Creates the roles vigil_owner and vigil_app and the extension vector at the first start of
# vigil-db (AR-U-02, AR-P-06). Run by the image's entrypoint as the superuser postgres.
# The passwords are read inside psql from the secret files in ${VIGIL_SECRETS_DIR:-/run/secrets/vigil},
# so none is on a command line, in the environment or in a log.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username postgres --dbname vigil <<'SQL'
SET log_min_error_statement = panic;
SET log_statement = 'none';
\set owner_pw `cat "${VIGIL_SECRETS_DIR:-/run/secrets/vigil}/vigil-db-owner"`
\set app_pw `cat "${VIGIL_SECRETS_DIR:-/run/secrets/vigil}/vigil-db-app"`
CREATE ROLE vigil_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE vigil_app LOGIN PASSWORD :'app_pw';
GRANT CONNECT, CREATE ON DATABASE vigil TO vigil_owner;
GRANT CONNECT ON DATABASE vigil TO vigil_app;
CREATE EXTENSION IF NOT EXISTS vector;
SQL
