#!/usr/bin/env bash
# Incremental update for areas server (osm2pgsql-replication). Uses daily diffs only.
# Usage:
#   ./update.sh DATABASE_URL [options] init [init args...]
#   ./update.sh DATABASE_URL [options] update
# Options: --cache MB, --processes N. Schema is hard-coded as is_in.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SCHEMA="is_in"

DB=""
CACHE_MB=""
PROCESSES=""
POSITIONALS=()

# Parse options and positionals (options can appear in any order)
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cache)     CACHE_MB="$2"; shift 2 ;;
    --processes) PROCESSES="$2"; shift 2 ;;
    -h|--help)
      echo "Usage: $0 DATABASE_URL [--cache MB] [--processes N] init|update [init args...]" >&2
      echo "  DATABASE_URL - connection URL (required, first positional)" >&2
      echo "  init    - initialise replication (run once after first PBF import); uses daily diffs" >&2
      echo "  update  - download and apply incremental diffs (default)" >&2
      echo "  --cache      node cache size in MB" >&2
      echo "  --processes  parallel threads" >&2
      exit 0
      ;;
    --) shift; POSITIONALS+=( "$@" ); break ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *)  POSITIONALS+=( "$1" ); shift ;;
  esac
done

if [[ ${#POSITIONALS[@]} -lt 1 ]]; then
  echo "Usage: $0 DATABASE_URL [--cache MB] [--processes N] init|update [init args...]" >&2
  echo "  DATABASE_URL is required as first argument." >&2
  exit 1
fi

DB="${POSITIONALS[0]}"
SUBCOMMAND="${POSITIONALS[1]:-update}"
INIT_ARGS=( "${POSITIONALS[@]:2}" )

FLEX_CONFIG="${AREAS_SERVER_FLEX_CONFIG:-${SERVER_DIR}/flex_config/areas.lua}"
if [[ ! -f "$FLEX_CONFIG" ]]; then
  echo "Flex config not found: $FLEX_CONFIG (set AREAS_SERVER_FLEX_CONFIG if needed)" >&2
  exit 1
fi

REPLICATION_SCRIPT="${OSM2PGSQL_REPLICATION:-osm2pgsql-replication}"
REPLICATION_DAY="https://planet.openstreetmap.org/replication/day"

run_replication() {
  local subcmd="$1"
  shift
  local cmd=("$REPLICATION_SCRIPT" "$subcmd" -d "$DB" --schema "$SCHEMA" "$@")
  "${cmd[@]}"
}

case "$SUBCOMMAND" in
  init)
    run_replication init --server "$REPLICATION_DAY" "${INIT_ARGS[@]}"
    echo "Replication initialised. Run ./update.sh update periodically (e.g. via cron)." >&2
    ;;
  update)
    POST_SCRIPT=""
    if [[ -f "$SCRIPT_DIR/post-analyze.sh" ]]; then
      POST_SCRIPT="bash -c '\"$SCRIPT_DIR/post-analyze.sh\" \"$DB\" \"\$@\"'"
    fi
    EXTRA_OSM2PGSQL=()
    [[ -n "$CACHE_MB" ]] && EXTRA_OSM2PGSQL+=(-C "$CACHE_MB")
    [[ -n "$PROCESSES" ]] && EXTRA_OSM2PGSQL+=(--number-processes "$PROCESSES")
    if [[ -n "$POST_SCRIPT" ]]; then
      run_replication update --post-processing "$POST_SCRIPT" -- "${EXTRA_OSM2PGSQL[@]}" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -x
    else
      run_replication update -- "${EXTRA_OSM2PGSQL[@]}" -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -x
    fi
    ;;
  *)
    echo "Usage: $0 DATABASE_URL [--cache MB] [--processes N] init|update [init args...]" >&2
    echo "  init    - initialise replication (run once after first PBF import)" >&2
    echo "  update  - download and apply incremental diffs (default)" >&2
    exit 1
    ;;
esac
