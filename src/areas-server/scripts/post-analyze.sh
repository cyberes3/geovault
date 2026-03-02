#!/usr/bin/env bash
# Post-processing for replication update: create geography GIST indexes for water_bodies and
# place_nodes (flex tables) and run ANALYZE on all is_in tables used by the server:
# admin_areas, protected_areas, water_bodies, place_nodes, ocean_regions, oceans, ski_resorts.
# Usage: ./post-analyze.sh DATABASE_URL [sequence] [timestamp]
#   DATABASE_URL - required connection URL (e.g. postgresql://...).
#   When called by osm2pgsql-replication --post-processing, sequence and timestamp may be passed.
# Also run once after initial PBF import (see installation/Areas Server.md).

set -euo pipefail

DB="${1:-}"
if [[ -z "$DB" ]]; then
  echo "Usage: $0 DATABASE_URL [sequence] [timestamp]" >&2
  echo "  DATABASE_URL - required (e.g. postgresql://...)" >&2
  exit 1
fi
shift || true

SCHEMA="is_in"

# Geometry GIST for ST_Contains/&& on admin and protected (critical for single-point and batch latency)
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS admin_areas_geom_gist ON \"$SCHEMA\".admin_areas USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS protected_areas_geom_gist ON \"$SCHEMA\".protected_areas USING GIST (geom);"
# Geography GIST speeds up ST_DWithin(geography(geom), ...) for water/place radius lookups
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS water_bodies_geom_geog_gist ON \"$SCHEMA\".water_bodies USING GIST ((geom::geography));"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS place_nodes_geom_geog_gist ON \"$SCHEMA\".place_nodes USING GIST ((geom::geography));"
# Geometry GIST for ST_Contains on ocean/ski tables (import scripts may only add geography GIST)
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS ocean_regions_geom_gist ON \"$SCHEMA\".ocean_regions USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS oceans_geom_gist ON \"$SCHEMA\".oceans USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS ski_resorts_geom_gist ON \"$SCHEMA\".ski_resorts USING GIST (geom);"
psql "$DB" -v ON_ERROR_STOP=1 -c "ANALYZE \"$SCHEMA\".admin_areas; ANALYZE \"$SCHEMA\".protected_areas; ANALYZE \"$SCHEMA\".water_bodies; ANALYZE \"$SCHEMA\".place_nodes; ANALYZE \"$SCHEMA\".ocean_regions; ANALYZE \"$SCHEMA\".oceans; ANALYZE \"$SCHEMA\".ski_resorts;"
