#!/usr/bin/env bash
# Post-processing for replication update: run ANALYZE on is_in tables.
# Usage: called by osm2pgsql-replication --post-processing (gets sequence and timestamp args).
# Env: AREAS_SERVER_DATABASE, AREAS_SERVER_SCHEMA (default is_in).

set -euo pipefail

DB="${AREAS_SERVER_DATABASE:-}"
if [[ -z "$DB" ]]; then
  echo "AREAS_SERVER_DATABASE not set" >&2
  exit 1
fi

SCHEMA="${AREAS_SERVER_SCHEMA:-is_in}"

# Geography GIST speeds up ST_DWithin(geography(geom), ...) for water nearby-shore queries
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS water_bodies_geom_geog_gist ON \"$SCHEMA\".water_bodies USING GIST ((geom::geography));"
psql "$DB" -v ON_ERROR_STOP=1 -c "ANALYZE \"$SCHEMA\".admin_areas; ANALYZE \"$SCHEMA\".protected_areas; ANALYZE \"$SCHEMA\".water_bodies;"
