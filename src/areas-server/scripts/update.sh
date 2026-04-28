#!/usr/bin/env bash
# Launcher for osm2pgsql-replication: schema is_in, daily planet, flex areas.lua.
# Usage: ./update.sh DATABASE_URL [--cache MB] [--processes N] [--max-diff-size MB] [--once] init|update

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SCHEMA="is_in"
DAY_URL="https://planet.openstreetmap.org/replication/day"
FLEX="${AREAS_SERVER_FLEX_CONFIG:-$SERVER_DIR/flex_config/areas.lua}"
REP="${OSM2PGSQL_REPLICATION:-osm2pgsql-replication}"

CACHE_MB=""
PROCESSES=""
MAX_DIFF_MB="1048576"
ONCE=false
POS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cache)         CACHE_MB="$2"; shift 2 ;;
    --processes)     PROCESSES="$2"; shift 2 ;;
    --max-diff-size) MAX_DIFF_MB="$2"; shift 2 ;;
    --once)          ONCE=true; shift ;;
    -h|--help)
      echo "Usage: $0 DATABASE_URL [--cache MB] [--processes N] [--max-diff-size MB] [--once] init|update" >&2
      exit 0
      ;;
    --) shift; POS+=("$@"); break ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *)  POS+=("$1"); shift ;;
  esac
done

[[ ${#POS[@]} -eq 2 ]] || { echo "Usage: $0 DATABASE_URL … init|update" >&2; exit 1; }
DB="${POS[0]}"
cmd="${POS[1]}"
[[ "$cmd" == init || "$cmd" == update ]] || { echo "Last argument must be init or update." >&2; exit 1; }

[[ -f "$FLEX" ]] || { echo "Missing flex config: $FLEX" >&2; exit 1; }

osm2pgsql_extra=()
[[ -n "$CACHE_MB" ]] && osm2pgsql_extra+=(-C "$CACHE_MB")
[[ -n "$PROCESSES" ]] && osm2pgsql_extra+=(--number-processes "$PROCESSES")

if [[ "$cmd" == init ]]; then
  exec "$REP" init -d "$DB" --schema "$SCHEMA" --middle-schema "$SCHEMA" --server "$DAY_URL"
fi

# update
rep_args=(-d "$DB" --schema "$SCHEMA" --middle-schema "$SCHEMA" --max-diff-size "$MAX_DIFF_MB")
[[ "$ONCE" == true ]] && rep_args+=(--once)

if [[ -f "$SCRIPT_DIR/post-analyze.sh" ]]; then
  export AREAS_SERVER_REPLICATION_DATABASE_URL="$DB"
  exec "$REP" update "${rep_args[@]}" --post-processing "$SCRIPT_DIR/post-analyze.sh" -- \
    "${osm2pgsql_extra[@]}" -O flex -S "$FLEX" --schema "$SCHEMA" -x
else
  exec "$REP" update "${rep_args[@]}" -- \
    "${osm2pgsql_extra[@]}" -O flex -S "$FLEX" --schema "$SCHEMA" -x
fi
