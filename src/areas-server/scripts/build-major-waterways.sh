#!/usr/bin/env bash
# From an OSM PBF: build grouped major rivers and canals, store GeoJSON in
# --local-dir, and load into Postgres (waterways.world_major_waterways).
# With --load, skip building and import from existing GeoJSON in local-dir.
#
# Requires: osm-lump-ways (cargo install osm-lump-ways), ogr2ogr (GDAL), psql
#
# Usage:
#   ./build-major-waterways.sh DATABASE_URL <path-to.osm.pbf> [options]
#   ./build-major-waterways.sh DATABASE_URL --load [--local-dir /srv/downloads]
#
# Options:
#   --local-dir DIR   Directory for major-waterways.geojson (default: /srv/downloads)
#   --load            Skip build; load from existing GeoJSON in --local-dir
#   --min-upstream-km N   Keep only systems with total length >= N km (default: 50)
#
# Output: GeoJSON in <local-dir>/major-waterways.geojson; table waterways.world_major_waterways.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCHEMA="waterways"
TABLE="world_major_waterways"
GEOJSON_NAME="major-waterways.geojson"
MIN_UPSTREAM_M=50000   # 50 km default
LOCAL_DIR="/srv/downloads"
DO_LOAD=false

DATABASE_URL=""
PBF_PATH=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local-dir)
      shift
      [[ $# -gt 0 ]] || { echo "Missing value for --local-dir" >&2; exit 1; }
      LOCAL_DIR="$1"
      shift
      ;;
    --load)
      DO_LOAD=true
      shift
      ;;
    --min-upstream-km)
      shift
      [[ $# -gt 0 ]] || { echo "Missing value for --min-upstream-km" >&2; exit 1; }
      MIN_UPSTREAM_M=$(echo "$1" | awk '{ printf "%.0f", $0 * 1000 }')
      shift
      ;;
    -h|--help)
      echo "Usage: $0 DATABASE_URL [path-to.osm.pbf] [--local-dir DIR] [--load] [--min-upstream-km N]" >&2
      echo "  Builds major waterways from PBF (or --load from existing GeoJSON) and loads into ${SCHEMA}.${TABLE}." >&2
      exit 0
      ;;
    *)
      if [[ -z "$DATABASE_URL" ]]; then
        DATABASE_URL="$1"
      elif [[ -z "$PBF_PATH" ]]; then
        PBF_PATH="$1"
      fi
      shift
      ;;
  esac
done

if [[ -z "$DATABASE_URL" ]]; then
  echo "Usage: $0 DATABASE_URL [path-to.osm.pbf] [--local-dir DIR] [--load] [--min-upstream-km N]" >&2
  exit 1
fi

GEOJSON_PATH="${LOCAL_DIR}/${GEOJSON_NAME}"

if [[ "$DO_LOAD" == true ]]; then
  if [[ ! -s "$GEOJSON_PATH" ]]; then
    echo "GeoJSON not found: $GEOJSON_PATH" >&2
    exit 1
  fi
  echo "=== Load from existing GeoJSON (skip build) ==="
else
  if [[ -z "$PBF_PATH" ]]; then
    echo "PBF path required when not using --load." >&2
    exit 1
  fi
  if [[ ! -f "$PBF_PATH" ]]; then
    echo "PBF not found: $PBF_PATH" >&2
    exit 1
  fi
  if ! command -v osm-lump-ways-down &>/dev/null; then
    echo "osm-lump-ways-down not found. Install with: cargo install osm-lump-ways" >&2
    exit 1
  fi

  WORK_DIR="$(mktemp -d)"
  trap 'rm -rf "$WORK_DIR"' EXIT

  echo "=== Group waterways (main stem + tributaries) ==="
  # --min-upstream-m: drop segments with less upstream length (prunes small feeders); distinct from --min-upstream-km which filters whole systems by total size
  osm-lump-ways-down \
    -i "$PBF_PATH" \
    -f "waterway=river" -f "waterway=canal" \
    --min-upstream-m 1000 \
    --flow-follows-tag name \
    --grouped-waterways "${WORK_DIR}/grouped.geojson"

  if [[ ! -s "${WORK_DIR}/grouped.geojson" ]]; then
    echo "No grouped waterways produced." >&2
    exit 1
  fi

  echo "=== Keep only major (max_upstream_m >= ${MIN_UPSTREAM_M} m) ==="
  ogr2ogr -f GeoJSON "${WORK_DIR}/major.geojson" "${WORK_DIR}/grouped.geojson" \
    -where "max_upstream_m >= ${MIN_UPSTREAM_M}"

  if [[ ! -s "${WORK_DIR}/major.geojson" ]]; then
    echo "No features passed the threshold." >&2
    exit 1
  fi

  mkdir -p "$LOCAL_DIR"
  cp "${WORK_DIR}/major.geojson" "$GEOJSON_PATH"
  echo "Stored: $GEOJSON_PATH ($(du -h "$GEOJSON_PATH" | cut -f1))"
fi

echo "=== Import to Postgres ==="
if [[ "$DATABASE_URL" =~ ^postgresql:// ]]; then
  REST="${DATABASE_URL#postgresql://}"
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

ogr2ogr -f PostgreSQL PG: "$GEOJSON_PATH" \
  -nln "${SCHEMA}.${TABLE}" -nlt MULTILINESTRING -unsetFid \
  -oo ARRAY_AS_STRING=YES -t_srs EPSG:4326 -lco GEOMETRY_NAME=geom

psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS \"${TABLE}_tag_group_value\" ON \"$SCHEMA\".\"$TABLE\" (tag_group_value);"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS \"${TABLE}_length_m\" ON \"$SCHEMA\".\"$TABLE\" (length_m);"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS \"${TABLE}_max_upstream_m\" ON \"$SCHEMA\".\"$TABLE\" (max_upstream_m);"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS \"${TABLE}_geom\" ON \"$SCHEMA\".\"$TABLE\" USING GIST (geom);"

echo "Done. Table: $SCHEMA.$TABLE"
