#!/usr/bin/env bash
# PBF import for areas server.
# Usage:
#   ./import-pbf.sh DATABASE_URL [--append] <path-to.pbf> [path-to.pbf ...]
# Schema is hard-coded as is_in.
# osm2pgsql merges multiple input files and ignores duplicates.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FLEX_CONFIG="${SERVER_DIR}/flex_config/areas.lua"
SCHEMA="is_in"

APPEND=false
DB=""
POSITIONALS=()

# Parse options and positionals (options can appear in any order)
while [[ $# -gt 0 ]]; do
  case "$1" in
    --append)    APPEND=true; shift ;;
    -h|--help)
      echo "Usage: $0 DATABASE_URL [--append] <path-to.osm.pbf> [path-to.osm.pbf ...]" >&2
      echo "  DATABASE_URL - connection URL (required, first positional)" >&2
      echo "  --append     add PBF(s) to existing data (first import without --append)" >&2
      echo "  Multiple PBFs are merged by osm2pgsql." >&2
      exit 0
      ;;
    --) shift; POSITIONALS+=( "$@" ); break ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *)  POSITIONALS+=( "$1" ); shift ;;
  esac
done

if [[ ${#POSITIONALS[@]} -lt 2 ]]; then
  echo "Usage: $0 DATABASE_URL [--append] <path-to.osm.pbf> [path-to.osm.pbf ...]" >&2
  echo "  DATABASE_URL and at least one PBF path are required." >&2
  exit 1
fi

DB="${POSITIONALS[0]}"
PBF_PATHS=( "${POSITIONALS[@]:1}" )
for p in "${PBF_PATHS[@]}"; do
  if [[ ! -f "$p" ]]; then
    echo "PBF file not found: $p" >&2
    exit 1
  fi
done

# Prefer repo's local osm2pgsql build if present; override with OSM2PGSQL env if needed.
REPO_ROOT="$(cd "$SERVER_DIR/../.." && pwd)"
LOCAL_OSM2PGSQL="${REPO_ROOT}/osm2pgsql/build/osm2pgsql"
if [[ -z "${OSM2PGSQL:-}" && -x "$LOCAL_OSM2PGSQL" ]]; then
  OSM2PGSQL="$LOCAL_OSM2PGSQL"
elif [[ -z "${OSM2PGSQL:-}" ]]; then
  OSM2PGSQL=osm2pgsql
fi

if [[ "$APPEND" == true ]]; then
  echo "Appending ${PBF_PATHS[*]} to existing database (schema=$SCHEMA)..."
  "$OSM2PGSQL" --disable-parallel-indexing -C 0 --number-processes 4 -a -d "$DB" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -x -s "${PBF_PATHS[@]}"
else
  psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE SCHEMA IF NOT EXISTS \"$SCHEMA\";"
  echo "Importing ${PBF_PATHS[*]} into database (schema=$SCHEMA) with osm2pgsql flex (create mode)..."
  "$OSM2PGSQL" --disable-parallel-indexing -C 0 --number-processes 4 -c -d "$DB" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -x -s "${PBF_PATHS[@]}"
  echo "Import finished. To add another region: $0 DATABASE_URL --append <other.pbf>"
  echo "To enable incremental updates, run: scripts/update.sh DATABASE_URL init"
fi
