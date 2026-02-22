#!/usr/bin/env bash
# Post-processing for replication update: run ANALYZE on is_in tables.
# Usage: called by osm2pgsql-replication --post-processing (gets sequence and timestamp args).
# Env: IS_IN_DATABASE or DATABASE_URL, IS_IN_SCHEMA (default is_in).

set -euo pipefail

DB="${IS_IN_DATABASE:-${DATABASE_URL:-}}"
if [[ -z "$DB" ]]; then
  echo "IS_IN_DATABASE or DATABASE_URL not set" >&2
  exit 1
fi

SCHEMA="${IS_IN_SCHEMA:-is_in}"

psql "$DB" -v ON_ERROR_STOP=1 -c "ANALYZE \"$SCHEMA\".admin_areas; ANALYZE \"$SCHEMA\".protected_areas;"
