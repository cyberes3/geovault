#!/usr/bin/env bash
# Build "grouped waterways" (one feature per river system: main stem + tributaries like
# North Platte + South Platte + Platte) from an OSM PBF and import into PostgreSQL.
#
# Requires: osm-lump-ways (install with: cargo install osm-lump-ways)
#           osmium (e.g. apt install osmium-tool), ogr2ogr (GDAL), psql
#
# Usage:
#   ./build-grouped-waterways.sh DATABASE_URL <path-to.osm.pbf>
#
# Output: table waterways.grouped_waterways with columns including:
#   - tag_group_value   name of the river system (e.g. "Platte River")
#   - length_m          main-stem length (metres)
#   - max_upstream_m    total length including all tributaries (metres) — use this to filter by "length including tributaries"
#   - geom              MultiLineString (WGS84)
#
# Example filter by total length (e.g. at least 500 km including tributaries):
#   SELECT tag_group_value, length_m, max_upstream_m
#   FROM waterways.grouped_waterways
#   WHERE max_upstream_m >= 500000
#   ORDER BY max_upstream_m DESC;

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SCHEMA="waterways"
TABLE="grouped_waterways"

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 DATABASE_URL <path-to.osm.pbf>" >&2
  echo "  Requires: osm-lump-ways (cargo install osm-lump-ways), osmium, ogr2ogr, psql" >&2
  exit 1
fi

DATABASE_URL="$1"
PBF_PATH="$2"
if [[ ! -f "$PBF_PATH" ]]; then
  echo "PBF file not found: $PBF_PATH" >&2
  exit 1
fi

if ! command -v osm-lump-ways-down &>/dev/null; then
  echo "osm-lump-ways-down not found. Install with: cargo install osm-lump-ways" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# Filter to river + canal only (same as waterways.lua). Optional: pre-extract with osmium to speed up lumping.
FILTERED_PBF="${WORK_DIR}/waterways.osm.pbf"
echo "Filtering PBF to rivers and canals..."
osmium tags-filter "$PBF_PATH" -o "$FILTERED_PBF" -O waterway=river waterway=canal --overwrite

echo "Building grouped waterways (main stem + tributaries)..."
# --flow-follows-tag name: connect ways by topology; group by name for reporting.
# Output: one feature per named group with length_m (main stem) and max_upstream_m (total including tributaries).
osm-lump-ways-down \
  -i "$FILTERED_PBF" \
  -f "waterway=river" -f "waterway=canal" \
  --min-upstream-m 100 \
  --flow-follows-tag name \
  --grouped-waterways "${WORK_DIR}/grouped_waterways.geojson"

if [[ ! -s "${WORK_DIR}/grouped_waterways.geojson" ]]; then
  echo "No grouped waterways produced." >&2
  exit 1
fi

# Parse DATABASE_URL for ogr2ogr (PG uses PGHOST, PGUSER, PGPASSWORD, PGDATABASE)
# Format: postgresql://[user[:password]@][host][:port][/dbname]
if [[ "$DATABASE_URL" =~ ^postgresql:// ]]; then
  # Remove postgresql://
  REST="${DATABASE_URL#postgresql://}"
  # Optional user:password@
  if [[ "$REST" =~ ^([^@]+)@(.*)$ ]]; then
    USERPART="${BASH_REMATCH[1]}"
    REST="${BASH_REMATCH[2]}"
    if [[ "$USERPART" =~ ^([^:]+):(.*)$ ]]; then
      export PGUSER="${BASH_REMATCH[1]}"
      export PGPASSWORD="${BASH_REMATCH[2]}"
    else
      export PGUSER="$USERPART"
    fi
  fi
  # host:port/dbname or /dbname
  if [[ "$REST" =~ ^([^/]+)/(.+)$ ]]; then
    HOSTPORT="${BASH_REMATCH[1]}"
    export PGDATABASE="${BASH_REMATCH[2]}"
    if [[ "$HOSTPORT" =~ ^([^:]+):([0-9]+)$ ]]; then
      export PGHOST="${BASH_REMATCH[1]}"
      export PGPORT="${BASH_REMATCH[2]}"
    else
      export PGHOST="$HOSTPORT"
    fi
  else
    export PGDATABASE="$REST"
  fi
fi

psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE SCHEMA IF NOT EXISTS \"$SCHEMA\";"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "DROP TABLE IF EXISTS \"$SCHEMA\".\"$TABLE\" CASCADE;"

echo "Importing into $SCHEMA.$TABLE ..."
# Load into schema.table (ogr2ogr uses PG env: PGHOST, PGUSER, PGPASSWORD, PGDATABASE, PGPORT)
ogr2ogr -f PostgreSQL PG: "${WORK_DIR}/grouped_waterways.geojson" \
  -nln "${SCHEMA}.${TABLE}" -nlt MULTILINESTRING -unsetFid \
  -oo ARRAY_AS_STRING=YES -t_srs EPSG:4326 -lco GEOMETRY_NAME=geom

psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS \"${TABLE}_tag_group_value\" ON \"$SCHEMA\".\"$TABLE\" (tag_group_value);"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS \"${TABLE}_length_m\" ON \"$SCHEMA\".\"$TABLE\" (length_m);"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS \"${TABLE}_max_upstream_m\" ON \"$SCHEMA\".\"$TABLE\" (max_upstream_m);"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS \"${TABLE}_geom\" ON \"$SCHEMA\".\"$TABLE\" USING GIST (geom);"

echo "Done. Table: $SCHEMA.$TABLE"
echo "  Filter by length including tributaries: WHERE max_upstream_m >= <metres>"
echo "  Main stem only: WHERE length_m >= <metres>"
