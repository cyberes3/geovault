#!/usr/bin/env bash
# From one or more OSM PBFs: build grouped major rivers and canals per PBF,
# merge the GeoJSONs, store in --local-dir, and load into Postgres (waterways.major_waterways).
# With --load, skip building and import from existing merged GeoJSON in local-dir.
#
# Requires: osm-lump-ways (cargo install osm-lump-ways), ogr2ogr (GDAL), jq, psql
#
# Usage:
#   ./import-major-waterways.sh DATABASE_URL <path-to.osm.pbf> [path-to.osm.pbf ...] [options]
#   ./import-major-waterways.sh DATABASE_URL /srv/downloads/europe/*-latest.osm.pbf /srv/downloads/north-america-latest.osm.pbf
#   ./import-major-waterways.sh DATABASE_URL --load [--local-dir /srv/downloads]
#
# Options:
#   --local-dir DIR   Directory for per-PBF and merged GeoJSON (default: /srv/downloads)
#   --load            Skip build; load from existing merged GeoJSON in --local-dir
#   --min-upstream-km N   Keep only systems with total length >= N km (default: 50)
#   --sort-pbf        Pre-sort each PBF with osmium sort -s multipass (rarely needed)
#   --rewrite-pbf     No-op (rewrite is now always applied; kept for backwards compatibility)
#
# Preprocessing: Matches waterwaymap.org tag filter, then rewrites PBF with pbf_dense_nodes=false
# so osm-lump-ways (osmio) can read it (osmio only reads the first PrimitiveGroup per block).
# Required: osmium-tool.
#
# Output: one GeoJSON per PBF in <local-dir>/major-waterways_<basename>.geojson,
#         merged <local-dir>/major-waterways.geojson; table waterways.major_waterways.
#
# GeoJSON merge: concatenates each file's .features[] into one FeatureCollection.
# No spatial or attribute deduplication (same river in two extracts => two features).

set -euo pipefail

# So cargo-installed osm-lump-ways-down is on PATH
[[ -f "${HOME:-}/.cargo/env" ]] && . "${HOME:-}/.cargo/env"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GEOJSON_MERGED_NAME="major-waterways.geojson"
GEOJSON_PER_PBF_PREFIX="major-waterways_"
MIN_UPSTREAM_M=50000   # 50 km default
LOCAL_DIR="/srv/downloads"
DO_LOAD=false
DO_SORT_PBF=false
DO_REWRITE_PBF=false

DATABASE_URL=""
PBF_PATHS=()
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
    --sort-pbf)
      DO_SORT_PBF=true
      shift
      ;;
    --rewrite-pbf)
      DO_REWRITE_PBF=true
      shift
      ;;
    --min-upstream-km)
      shift
      [[ $# -gt 0 ]] || { echo "Missing value for --min-upstream-km" >&2; exit 1; }
      MIN_UPSTREAM_M=$(echo "$1" | awk '{ printf "%.0f", $0 * 1000 }')
      shift
      ;;
    -h|--help)
      echo "Usage: $0 DATABASE_URL [path-to.osm.pbf ...] [--local-dir DIR] [--load] [--sort-pbf] [--rewrite-pbf] [--min-upstream-km N]" >&2
      echo "  Builds major waterways from each PBF, merges GeoJSONs, loads into waterways.major_waterways." >&2
      exit 0
      ;;
    *)
      if [[ -z "$DATABASE_URL" ]]; then
        DATABASE_URL="$1"
      elif [[ "$1" == *.pbf ]]; then
        PBF_PATHS+=("$1")
      else
        echo "Unexpected argument (expected DATABASE_URL or .pbf file): $1" >&2
        exit 1
      fi
      shift
      ;;
  esac
done

if [[ -z "$DATABASE_URL" ]]; then
  echo "Usage: $0 DATABASE_URL [path-to.osm.pbf ...] [--local-dir DIR] [--load] [--sort-pbf] [--rewrite-pbf] [--min-upstream-km N]" >&2
  exit 1
fi

GEOJSON_MERGED_PATH="${LOCAL_DIR}/${GEOJSON_MERGED_NAME}"

if [[ "$DO_LOAD" == true ]]; then
  if [[ ! -s "$GEOJSON_MERGED_PATH" ]]; then
    echo "Merged GeoJSON not found: $GEOJSON_MERGED_PATH" >&2
    exit 1
  fi
  echo "=== Load from existing GeoJSON (skip build) ==="
else
  if [[ ${#PBF_PATHS[@]} -lt 1 ]]; then
    echo "At least one PBF path required when not using --load." >&2
    exit 1
  fi
  for f in "${PBF_PATHS[@]}"; do
    if [[ ! -f "$f" ]]; then
      echo "PBF not found: $f" >&2
      exit 1
    fi
  done
  if ! command -v osm-lump-ways-down &>/dev/null; then
    echo "osm-lump-ways-down not found. Install with: cargo install osm-lump-ways" >&2
    exit 1
  fi
  if ! command -v jq &>/dev/null; then
    echo "jq not found. Install jq to merge GeoJSON files." >&2
    exit 1
  fi
  if ! command -v osmium &>/dev/null; then
    echo "osmium not found. Install osmium-tool (e.g. apt install osmium-tool). Required for tags-filter so osm-lump-ways can read the PBF." >&2
    exit 1
  fi

  # Process smallest files first (glob order is undefined; sort by size for predictable progress)
  mapfile -t PBF_PATHS < <(
    for f in "${PBF_PATHS[@]}"; do
      printf '%d\t%s\n' "$(stat -c %s "$f" 2>/dev/null || echo 0)" "$f"
    done | sort -n -t$'\t' -k1 | cut -f2-
  )

  mkdir -p "$LOCAL_DIR"
  PER_FILE_PATHS=()
  WORK_DIR=""
  trap 'rm -rf "${WORK_DIR:-}" 2>/dev/null; rm -f "${GEOJSON_MERGED_PATH}.tmp" 2>/dev/null' EXIT

  for i in "${!PBF_PATHS[@]}"; do
    PBF_PATH="${PBF_PATHS[$i]}"
    BASE=$(basename "$PBF_PATH" .osm.pbf)
    [[ "$BASE" == "$(basename "$PBF_PATH")" ]] && BASE=$(basename "$PBF_PATH" .pbf)
    PER_FILE_JSON="${LOCAL_DIR}/${GEOJSON_PER_PBF_PREFIX}${BASE}.geojson"

    WORK_DIR="$(mktemp -d)"
    PBF_TO_USE="$PBF_PATH"

    # Same tag filter as waterwaymap.org; then rewrite so osmio can read (single PrimitiveGroup per block).
    TAG_FILTER="waterway natural=coastline natural=water canoe portage"
    echo "=== [$((i+1))/${#PBF_PATHS[@]}] Tags-filter ($TAG_FILTER): $PBF_PATH ==="
    osmium tags-filter "$PBF_PATH" -o "${WORK_DIR}/waterway.osm.pbf" --overwrite $TAG_FILTER
    echo "=== [$((i+1))/${#PBF_PATHS[@]}] Rewrite PBF (pbf_dense_nodes=false for osmio): $PBF_PATH ==="
    osmium cat "${WORK_DIR}/waterway.osm.pbf" -o "${WORK_DIR}/waterway_flat.osm.pbf" -f pbf,pbf_dense_nodes=false --overwrite
    PBF_TO_USE="${WORK_DIR}/waterway_flat.osm.pbf"
    if [[ "$DO_SORT_PBF" == true ]]; then
      echo "=== [$((i+1))/${#PBF_PATHS[@]}] Sort PBF: $PBF_TO_USE ==="
      osmium sort -s multipass "$PBF_TO_USE" -o "${WORK_DIR}/sorted.osm.pbf" --overwrite
      PBF_TO_USE="${WORK_DIR}/sorted.osm.pbf"
    fi

    echo "=== [$((i+1))/${#PBF_PATHS[@]}] Group waterways: $PBF_PATH ==="
    osm-lump-ways-down \
      -i "$PBF_TO_USE" \
      -f "waterway=river" -f "waterway=canal" \
      --min-upstream-m 1000 \
      --flow-follows-tag name \
      --grouped-waterways "${WORK_DIR}/grouped.geojson"

    if [[ ! -s "${WORK_DIR}/grouped.geojson" ]]; then
      echo "No grouped waterways for $PBF_PATH, skipping."
      if [[ -s "$PBF_TO_USE" ]]; then
        echo "  Filtered PBF (input to osm-lump-ways-down):"
        osmium fileinfo --no-progress -e "$PBF_TO_USE" 2>/dev/null | sed 's/^/    /' || true
      fi
      echo "  (Filtered PBF is rewritten with pbf_dense_nodes=false; if still 0 ways, see github.com/amandasaurus/osmio)"
      rm -rf "$WORK_DIR"
      continue
    fi

    echo "=== Keep only major (max_upstream_m >= ${MIN_UPSTREAM_M} m) ==="
    ogr2ogr -f GeoJSON "${WORK_DIR}/major.geojson" "${WORK_DIR}/grouped.geojson" \
      -where "max_upstream_m >= ${MIN_UPSTREAM_M}"

    if [[ ! -s "${WORK_DIR}/major.geojson" ]]; then
      echo "No features passed threshold for $PBF_PATH, skipping."
      rm -rf "$WORK_DIR"
      continue
    fi

    cp "${WORK_DIR}/major.geojson" "$PER_FILE_JSON"
    rm -rf "$WORK_DIR"
    PER_FILE_PATHS+=("$PER_FILE_JSON")
    echo "Stored: $PER_FILE_JSON ($(du -h "$PER_FILE_JSON" | cut -f1))"
  done

  if [[ ${#PER_FILE_PATHS[@]} -eq 0 ]]; then
    echo "No GeoJSON files produced from any PBF." >&2
    exit 1
  fi

  echo "=== Merge ${#PER_FILE_PATHS[@]} GeoJSON files -> $GEOJSON_MERGED_PATH ==="
  # Each input file is one FeatureCollection; concatenate all .features into one.
  # .features[]? yields nothing if .features is missing/null (no error).
  if ! jq -n '[inputs | .features[]?] | {type: "FeatureCollection", features: .}' "${PER_FILE_PATHS[@]}" > "${GEOJSON_MERGED_PATH}.tmp"; then
    echo "jq merge failed." >&2
    exit 1
  fi
  if ! jq -e '.type == "FeatureCollection" and (.features | type == "array")' "${GEOJSON_MERGED_PATH}.tmp" >/dev/null; then
    echo "Merged GeoJSON invalid." >&2
    exit 1
  fi
  mv "${GEOJSON_MERGED_PATH}.tmp" "$GEOJSON_MERGED_PATH"
  echo "Merged: $GEOJSON_MERGED_PATH ($(du -h "$GEOJSON_MERGED_PATH" | cut -f1), $(jq -r '.features | length' "$GEOJSON_MERGED_PATH") features)"
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

psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE SCHEMA IF NOT EXISTS waterways;"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "DROP TABLE IF EXISTS waterways.major_waterways CASCADE;"

ogr2ogr -f PostgreSQL PG: "$GEOJSON_MERGED_PATH" \
  -nln "waterways.major_waterways" -nlt MULTILINESTRING -unsetFid \
  -oo ARRAY_AS_STRING=YES -t_srs EPSG:4326 -lco GEOMETRY_NAME=geom

psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS major_waterways_tag_group_value ON waterways.major_waterways (tag_group_value);"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS major_waterways_length_m ON waterways.major_waterways (length_m);"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS major_waterways_max_upstream_m ON waterways.major_waterways (max_upstream_m);"
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "CREATE INDEX IF NOT EXISTS major_waterways_geom ON waterways.major_waterways USING GIST (geom);"

echo "Done. Table: waterways.major_waterways"
