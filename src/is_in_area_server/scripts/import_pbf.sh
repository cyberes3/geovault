#!/usr/bin/env bash
# PBF import for is_in area server.
# Usage:
#   ./import_pbf.sh <path-to.pbf> [database_url]            # create (replaces existing data)
#   ./import_pbf.sh --append <path-to.pbf> [database_url]   # add region to existing DB
# Env: IS_IN_DATABASE or DATABASE_URL, IS_IN_SCHEMA (default is_in). Uses -s -x.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FLEX_CONFIG="${SERVER_DIR}/flex_config/areas.lua"

APPEND=false
if [[ "${1:-}" == "--append" ]]; then
  APPEND=true
  shift
fi

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 [--append] <path-to.osm.pbf> [database_url]" >&2
  echo "  --append  add this PBF to existing data (first import must be without --append)" >&2
  exit 1
fi

PBF_PATH="$1"
if [[ ! -f "$PBF_PATH" ]]; then
  echo "PBF file not found: $PBF_PATH" >&2
  exit 1
fi

DB="${2:-${IS_IN_DATABASE:-${DATABASE_URL:-}}}"
if [[ -z "$DB" ]]; then
  echo "Database not set. Use second argument or IS_IN_DATABASE or DATABASE_URL." >&2
  exit 1
fi

SCHEMA="${IS_IN_SCHEMA:-is_in}"
OSM2PGSQL="${OSM2PGSQL:-osm2pgsql}"

if [[ "$APPEND" == true ]]; then
  echo "Appending $PBF_PATH to existing database (schema=$SCHEMA)..."
  "$OSM2PGSQL" -a -d "$DB" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -s -x "$PBF_PATH"
else
  psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE SCHEMA IF NOT EXISTS \"$SCHEMA\";"
  echo "Importing $PBF_PATH into database (schema=$SCHEMA) with osm2pgsql flex (create mode)..."
  "$OSM2PGSQL" -c -d "$DB" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -s -x "$PBF_PATH"
  echo "Import finished. To add another region: $0 --append <other.pbf>"
  echo "To enable incremental updates, run: scripts/update.sh init"
fi
