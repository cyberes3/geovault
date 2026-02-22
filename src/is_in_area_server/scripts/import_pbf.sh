#!/usr/bin/env bash
# Full PBF import for is_in area server.
# Usage: ./import_pbf.sh <path-to.osm.pbf> [database_url]
# Env: IS_IN_DATABASE or DATABASE_URL (used if second arg not given), IS_IN_SCHEMA (default is_in).
# Use slim mode (-s). Omit --drop so incremental updates can be used later.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$SERVER_DIR/../.." && pwd)"
FLEX_CONFIG="${SERVER_DIR}/flex_config/areas.lua"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <path-to.osm.pbf> [database_url]" >&2
  echo "  database_url can also be set via IS_IN_DATABASE or DATABASE_URL" >&2
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

# Ensure schema exists (use pgpass or PGPASSWORD for auth if needed)
psql "$DB" -v ON_ERROR_STOP=1 -c "CREATE SCHEMA IF NOT EXISTS \"$SCHEMA\";"

echo "Importing $PBF_PATH into database (schema=$SCHEMA) with osm2pgsql flex..."
"$OSM2PGSQL" -c -d "$DB" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -s "$PBF_PATH"

echo "Import finished. To enable incremental updates, run: scripts/update.sh init"
