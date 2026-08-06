#!/bin/sh
# Creates Keycloak's database and its own role.
#
# Runs once, when the data directory is empty. An existing deployment will not re-run it — see
# docs/DEPLOYMENT.md for what to do by hand in that case.
#
# Keycloak gets its own database and its own role deliberately. It holds credentials and session
# state for every tenant; a compromise of the application database should not hand somebody the
# identity provider as well, and the reverse.
set -eu

: "${KEYCLOAK_DB:?KEYCLOAK_DB must be set}"
: "${KEYCLOAK_DB_USER:?KEYCLOAK_DB_USER must be set}"
: "${KEYCLOAK_DB_PASSWORD:?KEYCLOAK_DB_PASSWORD must be set}"

# Passed on stdin rather than interpolated into the SQL text, so the password does not reach the
# process list or the server log.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     --set ON_ERROR_STOP=1 \
     --set kc_user="$KEYCLOAK_DB_USER" \
     --set kc_password="$KEYCLOAK_DB_PASSWORD" \
     --set kc_db="$KEYCLOAK_DB" <<'SQL'
CREATE ROLE :"kc_user" WITH LOGIN PASSWORD :'kc_password';
CREATE DATABASE :"kc_db" OWNER :"kc_user";

-- Nothing else needs to read it.
REVOKE ALL ON DATABASE :"kc_db" FROM PUBLIC;
SQL

echo "Created database ${KEYCLOAK_DB} owned by ${KEYCLOAK_DB_USER}"
