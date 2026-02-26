#!/usr/bin/env bash
# Import only rivers and canals from an OSM PBF into PostgreSQL (schema: waterways).
# Usage:
#   ./import-waterways-pbf.sh DATABASE_URL [--append] <path-to.osm.pbf>
#
# Example:
#   ./import-waterways-pbf.sh "postgresql://user:pass@localhost/dbname" /srv/downloads/north-america_western-europe_combined.osm.pbf

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FLEX_CONFIG="${SERVER_DIR}/flex_config/waterways.lua"
SCHEMA="waterways"

APPEND=false
DB=""
POSITIONALS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --append)    APPEND=true; shift ;;
    -h|--help)
      echo "Usage: $0 DATABASE_URL [--append] <path-to.osm.pbf>" >&2
      echo "  DATABASE_URL - connection URL (required, first positional)" >&2
      echo "  --append     add this PBF to existing waterways (first import without --append)" >&2
      exit 0
      ;;
    --) shift; POSITIONALS+=( "$@" ); break ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *)  POSITIONALS+=( "$1" ); shift ;;
  esac
done

if [[ ${#POSITIONALS[@]} -lt 2 ]]; then
  echo "Usage: $0 DATABASE_URL [--append] <path-to.osm.pbf>" >&2
  echo "  DATABASE_URL and PBF path are required." >&2
  exit 1
fi

DB="${POSITIONALS[0]}"
PBF_PATH="${POSITIONALS[1]}"
if [[ ! -f "$PBF_PATH" ]]; then
  echo "PBF file not found: $PBF_PATH" >&2
  exit 1
fi

REPO_ROOT="$(cd "$SERVER_DIR/../.." && pwd)"
LOCAL_OSM2PGSQL="${REPO_ROOT}/osm2pgsql/build/osm2pgsql"
if [[ -z "${OSM2PGSQL:-}" && -x "$LOCAL_OSM2PGSQL" ]]; then
  OSM2PGSQL="$LOCAL_OSM2PGSQL"
elif [[ -z "${OSM2PGSQL:-}" ]]; then
  OSM2PGSQL=osm2pgsql
fi

if [[ "$APPEND" == true ]]; then
  echo "Appending rivers/canals from $PBF_PATH to existing database (schema=$SCHEMA)..."
  "$OSM2PGSQL" --disable-parallel-indexing -C 0 --number-processes 4 -a -d "$DB" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -x -s "$PBF_PATH"
else
  psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE SCHEMA IF NOT EXISTS \"$SCHEMA\";"
  echo "Importing rivers and canals from $PBF_PATH into schema=$SCHEMA..."
  "$OSM2PGSQL" --disable-parallel-indexing -C 0 --number-processes 4 -c -d "$DB" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -x -s "$PBF_PATH"
  echo "Import finished. To add another region: $0 DATABASE_URL --append <other.pbf>"
fi
