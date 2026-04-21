#!/usr/bin/env bash
# Post-processing for replication update: create geography GIST indexes for water_bodies and
# place_nodes (flex tables) and run ANALYZE on all is_in tables used by the server:
# admin_areas, protected_areas, water_bodies, place_nodes, ocean_regions, oceans, ski_resorts.
# Usage:
#   ./post-analyze.sh DATABASE_URL [sequence] [timestamp]
#   DATABASE_URL - libpq URI (postgresql:// or postgres://), optional leading/trailing whitespace.
# osm2pgsql-replication --post-processing runs: SCRIPT sequence timestamp (no URI in argv).
#   Then the DB URI is read from the environment (see below). scripts/update.sh sets it for each run.
# DB URI from environment (first match wins) when argv does not start with a URI:
#   AREAS_SERVER_REPLICATION_DATABASE_URL, then AREAS_SERVER_DATABASE (same as the Flask server).
# Also run once after initial PBF import (see installation/Areas Server.md).

set -euo pipefail

# True if value looks like a libpq connection URI (case-insensitive scheme).
_areas_is_psql_uri() {
  local v="${1:-}"
  v="${v#"${v%%[![:space:]]*}"}"
  v="${v%"${v##*[![:space:]]}"}"
  v="${v,,}"
  [[ "$v" == postgresql://* ]] || [[ "$v" == postgres://* ]]
}

_first="${1:-}"
_first="${_first#"${_first%%[![:space:]]*}"}"
_first="${_first%"${_first##*[![:space:]]}"}"

if [[ -n "$_first" ]] && _areas_is_psql_uri "$_first"; then
  DB="$_first"
  shift
else
  DB=""
  for _envname in AREAS_SERVER_REPLICATION_DATABASE_URL AREAS_SERVER_DATABASE; do
    _candidate="${!_envname:-}"
    [[ -z "$_candidate" ]] && continue
    if _areas_is_psql_uri "$_candidate"; then
      DB="$_candidate"
      break
    fi
  done
  if [[ -z "$DB" ]]; then
    echo "Usage: $0 'postgresql://…' [sequence] [timestamp]" >&2
    echo "  Or set AREAS_SERVER_REPLICATION_DATABASE_URL or AREAS_SERVER_DATABASE to that URI when" >&2
    echo "  osm2pgsql-replication calls this script (use scripts/update.sh update)." >&2
    exit 1
  fi
fi

SCHEMA="is_in"

# Geometry GIST for ST_Contains/&& on admin and protected (critical for single-point and batch latency)
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS admin_areas_geom_gist ON \"$SCHEMA\".admin_areas USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS protected_areas_geom_gist ON \"$SCHEMA\".protected_areas USING GIST (geom);"
# Geography GIST speeds up ST_DWithin(geography(geom), ...) for water/place radius lookups
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS water_bodies_geom_gist ON \"$SCHEMA\".water_bodies USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS place_nodes_geom_gist ON \"$SCHEMA\".place_nodes USING GIST (geom);"
# Geography GIST speeds up ST_DWithin(geography(geom), ...) if any remaining geography lookups are used
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS water_bodies_geom_geog_gist ON \"$SCHEMA\".water_bodies USING GIST ((geom::geography));"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS place_nodes_geom_geog_gist ON \"$SCHEMA\".place_nodes USING GIST ((geom::geography));"
# Geometry GIST for ST_Contains on ocean/ski tables (import scripts may only add geography GIST)
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS ocean_regions_geom_gist ON \"$SCHEMA\".ocean_regions USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS oceans_geom_gist ON \"$SCHEMA\".oceans USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS ski_resorts_geom_gist ON \"$SCHEMA\".ski_resorts USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "ANALYZE \"$SCHEMA\".admin_areas; ANALYZE \"$SCHEMA\".protected_areas; ANALYZE \"$SCHEMA\".water_bodies; ANALYZE \"$SCHEMA\".place_nodes; ANALYZE \"$SCHEMA\".ocean_regions; ANALYZE \"$SCHEMA\".oceans; ANALYZE \"$SCHEMA\".ski_resorts;"

# Waterways lookup requires geometry index for optimized distance/within-distance checks
# Geography index is kept for backward compatibility or direct geography queries
psql "$DB" -c "CREATE INDEX IF NOT EXISTS major_waterways_geom_gist ON waterways.major_waterways USING GIST (geom);" || true
psql "$DB" -c "CREATE INDEX IF NOT EXISTS major_waterways_geom_geog_gist ON waterways.major_waterways USING GIST ((geom::geography));" || true
psql "$DB" -c "ANALYZE waterways.major_waterways;" || true
