#!/usr/bin/env bash
# PBF import for areas server.
# Usage:
#   ./import_pbf.sh [options] <path-to.pbf> [database_url]
#   ./import_pbf.sh --append [options] <path-to.pbf> [database_url]
# Options: --cache MB, --processes N, --database URL. Schema is hard-coded as is_in.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FLEX_CONFIG="${SERVER_DIR}/flex_config/areas.lua"
SCHEMA="is_in"

APPEND=false
CACHE_MB=""
PROCESSES=""
DB=""
POSITIONALS=()

# Parse options and positionals (options can appear in any order)
while [[ $# -gt 0 ]]; do
  case "$1" in
    --append)    APPEND=true; shift ;;
    --cache)     CACHE_MB="$2"; shift 2 ;;
    --processes) PROCESSES="$2"; shift 2 ;;
    --database)  DB="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [--append] [--cache MB] [--processes N] [--database URL] <path-to.osm.pbf> [database_url]" >&2
      echo "  Options can appear before or after the PBF path." >&2
      echo "  --append     add this PBF to existing data (first import without --append)" >&2
      echo "  --cache      node cache size in MB (passed to osm2pgsql -C)" >&2
      echo "  --processes  parallel threads (default: nproc)" >&2
      echo "  --database   connection URL (or pass as second positional argument)" >&2
      exit 0
      ;;
    --) shift; POSITIONALS+=( "$@" ); break ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *)  POSITIONALS+=( "$1" ); shift ;;
  esac
done

if [[ ${#POSITIONALS[@]} -lt 1 ]]; then
  echo "Usage: $0 [--append] [--cache MB] [--processes N] [--database URL] <path-to.osm.pbf> [database_url]" >&2
  exit 1
fi

PBF_PATH="${POSITIONALS[0]}"
if [[ ! -f "$PBF_PATH" ]]; then
  echo "PBF file not found: $PBF_PATH" >&2
  exit 1
fi

DB="${DB:-${POSITIONALS[1]:-}}"
if [[ -z "$DB" ]]; then
  echo "Database not set. Use --database or pass database_url as second argument." >&2
  exit 1
fi

OSM2PGSQL="${OSM2PGSQL:-osm2pgsql}"

EXTRA_OSM2PGSQL=()
[[ -n "$CACHE_MB" ]] && EXTRA_OSM2PGSQL+=(-C "$CACHE_MB")
if [[ -n "$PROCESSES" ]]; then
  EXTRA_OSM2PGSQL+=(--number-processes "$PROCESSES")
elif command -v nproc >/dev/null 2>&1; then
  EXTRA_OSM2PGSQL+=(--number-processes "$(nproc)")
fi

if [[ "$APPEND" == true ]]; then
  echo "Appending $PBF_PATH to existing database (schema=$SCHEMA)..."
  "$OSM2PGSQL" -a -d "$DB" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -s -x "${EXTRA_OSM2PGSQL[@]}" "$PBF_PATH"
else
  psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE SCHEMA IF NOT EXISTS \"$SCHEMA\";"
  echo "Importing $PBF_PATH into database (schema=$SCHEMA) with osm2pgsql flex (create mode)..."
  "$OSM2PGSQL" -c -d "$DB" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -s -x "${EXTRA_OSM2PGSQL[@]}" "$PBF_PATH"
  echo "Import finished. To add another region: $0 --append <other.pbf>"
  echo "To enable incremental updates, run: scripts/update.sh init"
fi
