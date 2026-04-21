#!/usr/bin/env bash
# Align is_in flex output tables with flex_config/areas.lua (osm2pgsql flex).
#
# Use when replication (update.sh update) or append import fails because columns
# are missing — e.g. DB imported under an older areas.lua during development.
#
# Only touches the four tables defined in areas.lua: admin_areas, protected_areas,
# water_bodies, place_nodes. Does not modify oceans, ski_resorts, waterways, or
# other custom tables.
#
# If any of those four tables is missing entirely, re-run a full PBF import instead;
# this script only ALTERs existing tables.
#
# Usage:
#   ./fix-is-in-flex-schema.sh DATABASE_URL [schema]
#   schema defaults to is_in (same as update.sh / import-pbf.sh).
#
# After success, re-run replication update and/or run post-analyze.sh for indexes.
#
# For when your DB is fucked from fucking around during development.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 DATABASE_URL [schema]" >&2
  echo "  Aligns flex tables with flex_config/areas.lua (idempotent ADD COLUMN)." >&2
  exit 1
fi

DB="$1"
SCHEMA="${2:-is_in}"

if ! command -v psql >/dev/null 2>&1; then
  echo "psql not found on PATH." >&2
  exit 1
fi

psql "$DB" -v ON_ERROR_STOP=1 <<SQL
-- admin_areas (define_relation_table in areas.lua)
ALTER TABLE "${SCHEMA}".admin_areas ADD COLUMN IF NOT EXISTS osm_id bigint;
ALTER TABLE "${SCHEMA}".admin_areas ADD COLUMN IF NOT EXISTS admin_level smallint;
ALTER TABLE "${SCHEMA}".admin_areas ADD COLUMN IF NOT EXISTS name text;
ALTER TABLE "${SCHEMA}".admin_areas ADD COLUMN IF NOT EXISTS tags jsonb;
ALTER TABLE "${SCHEMA}".admin_areas ADD COLUMN IF NOT EXISTS geom geometry(Polygon,4326);
ALTER TABLE "${SCHEMA}".admin_areas ADD COLUMN IF NOT EXISTS created timestamptz;

-- protected_areas (define_area_table)
ALTER TABLE "${SCHEMA}".protected_areas ADD COLUMN IF NOT EXISTS osm_id bigint;
ALTER TABLE "${SCHEMA}".protected_areas ADD COLUMN IF NOT EXISTS name text;
ALTER TABLE "${SCHEMA}".protected_areas ADD COLUMN IF NOT EXISTS tags jsonb;
ALTER TABLE "${SCHEMA}".protected_areas ADD COLUMN IF NOT EXISTS geom geometry(Geometry,4326);
ALTER TABLE "${SCHEMA}".protected_areas ADD COLUMN IF NOT EXISTS created timestamptz;

-- water_bodies (define_area_table)
ALTER TABLE "${SCHEMA}".water_bodies ADD COLUMN IF NOT EXISTS osm_id bigint;
ALTER TABLE "${SCHEMA}".water_bodies ADD COLUMN IF NOT EXISTS name text;
ALTER TABLE "${SCHEMA}".water_bodies ADD COLUMN IF NOT EXISTS water_type text;
ALTER TABLE "${SCHEMA}".water_bodies ADD COLUMN IF NOT EXISTS tags jsonb;
ALTER TABLE "${SCHEMA}".water_bodies ADD COLUMN IF NOT EXISTS geom geometry(Geometry,4326);
ALTER TABLE "${SCHEMA}".water_bodies ADD COLUMN IF NOT EXISTS created timestamptz;

-- place_nodes (define_node_table)
ALTER TABLE "${SCHEMA}".place_nodes ADD COLUMN IF NOT EXISTS osm_id bigint;
ALTER TABLE "${SCHEMA}".place_nodes ADD COLUMN IF NOT EXISTS name text;
ALTER TABLE "${SCHEMA}".place_nodes ADD COLUMN IF NOT EXISTS place_type text;
ALTER TABLE "${SCHEMA}".place_nodes ADD COLUMN IF NOT EXISTS geom geometry(Point,4326);
ALTER TABLE "${SCHEMA}".place_nodes ADD COLUMN IF NOT EXISTS created timestamptz;

UPDATE "${SCHEMA}".water_bodies SET water_type = 'water' WHERE water_type IS NULL;

ANALYZE "${SCHEMA}".admin_areas;
ANALYZE "${SCHEMA}".protected_areas;
ANALYZE "${SCHEMA}".water_bodies;
ANALYZE "${SCHEMA}".place_nodes;
SQL

echo "Flex schema aligned for schema \"${SCHEMA}\". For GIST/geography indexes run: \"${SCRIPT_DIR}/post-analyze.sh\" \"$DB\"" >&2
