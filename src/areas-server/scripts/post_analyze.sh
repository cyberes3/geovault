#!/usr/bin/env bash
# Post-processing for replication update: create geography GIST indexes for water_bodies and
# place_nodes (flex tables) and run ANALYZE on all is_in tables used by the server:
# admin_areas, protected_areas, water_bodies, place_nodes, ocean_regions, oceans, ski_resorts.
# Usage: called by osm2pgsql-replication --post-processing (gets sequence and timestamp args).
# Also run once after initial PBF import (see installation/Areas Server.md). Env: AREAS_SERVER_DATABASE.

set -euo pipefail

DB="${AREAS_SERVER_DATABASE:-}"
if [[ -z "$DB" ]]; then
  echo "AREAS_SERVER_DATABASE not set" >&2
  exit 1
fi

SCHEMA="is_in"

# Geography GIST speeds up ST_DWithin(geography(geom), ...) for water/place radius lookups
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS water_bodies_geom_geog_gist ON \"$SCHEMA\".water_bodies USING GIST ((geom::geography));"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS place_nodes_geom_geog_gist ON \"$SCHEMA\".place_nodes USING GIST ((geom::geography));"
# Geometry GIST for ST_Contains on ocean/ski tables (import scripts may only add geography GIST)
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS ocean_regions_geom_gist ON \"$SCHEMA\".ocean_regions USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS oceans_geom_gist ON \"$SCHEMA\".oceans USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS ski_resorts_geom_gist ON \"$SCHEMA\".ski_resorts USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "ANALYZE \"$SCHEMA\".admin_areas; ANALYZE \"$SCHEMA\".protected_areas; ANALYZE \"$SCHEMA\".water_bodies; ANALYZE \"$SCHEMA\".place_nodes; ANALYZE \"$SCHEMA\".ocean_regions; ANALYZE \"$SCHEMA\".oceans; ANALYZE \"$SCHEMA\".ski_resorts;"
