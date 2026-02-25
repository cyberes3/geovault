#!/usr/bin/env bash
# PBF import for areas server.
# Usage:
#   ./import_pbf.sh [options] <path-to.pbf> [database_url]
#   ./import_pbf.sh --append [options] <path-to.pbf> [database_url]
# Options: --processes N, --database URL. Schema is hard-coded as is_in.
# Node cache is set to 0 (passed to osm2pgsql -C). Use --processes to limit parallelism.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FLEX_CONFIG="${SERVER_DIR}/flex_config/areas.lua"
SCHEMA="is_in"

APPEND=false
PROCESSES=""
DB=""
POSITIONALS=()

# Parse options and positionals (options can appear in any order)
while [[ $# -gt 0 ]]; do
  case "$1" in
    --append)    APPEND=true; shift ;;
    --processes) PROCESSES="$2"; shift 2 ;;
    --database)  DB="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 [--append] [--processes N] [--database URL] <path-to.osm.pbf> [database_url]" >&2
      echo "  Options can appear before or after the PBF path." >&2
      echo "  --append     add this PBF to existing data (first import without --append)" >&2
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
  echo "Usage: $0 [--append] [--processes N] [--database URL] <path-to.osm.pbf> [database_url]" >&2
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

# Prefer repo's local osm2pgsql build if present; override with OSM2PGSQL env if needed.
REPO_ROOT="$(cd "$SERVER_DIR/../.." && pwd)"
LOCAL_OSM2PGSQL="${REPO_ROOT}/osm2pgsql/build/osm2pgsql"
if [[ -z "${OSM2PGSQL:-}" && -x "$LOCAL_OSM2PGSQL" ]]; then
  OSM2PGSQL="$LOCAL_OSM2PGSQL"
elif [[ -z "${OSM2PGSQL:-}" ]]; then
  OSM2PGSQL=osm2pgsql
fi

EXTRA_OSM2PGSQL=()
EXTRA_OSM2PGSQL+=(-C 0)
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
