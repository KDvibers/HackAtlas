#!/bin/sh
# Runs only during initialization of this stack's NEW PostgreSQL data volume.
set -eu

for file in postgres-admin-password migrator-password runtime-password; do
    value=$(cat "/run/secrets/$file")
    if [ "${#value}" -ne 64 ]; then
        echo "Release DB secrets must be independently generated 64-character lowercase hex values." >&2
        exit 1
    fi
    case "$value" in *[!0-9a-f]*) echo "Invalid release DB secret format." >&2; exit 1;; esac
    unset value
done

psql -X --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\set migrator_password `cat /run/secrets/migrator-password`
\set runtime_password `cat /run/secrets/runtime-password`
CREATE ROLE hackatlas_migrator LOGIN PASSWORD :'migrator_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE ROLE hackatlas_runtime LOGIN PASSWORD :'runtime_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
REVOKE ALL ON DATABASE hackatlas FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE hackatlas TO hackatlas_migrator;
GRANT CONNECT ON DATABASE hackatlas TO hackatlas_runtime;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO hackatlas_migrator;
SQL
