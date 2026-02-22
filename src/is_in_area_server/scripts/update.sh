#!/usr/bin/env bash
# Incremental update for is_in area server (osm2pgsql-replication).
# Usage:
#   ./update.sh          # run update (download and apply diffs)
#   ./update.sh init     # init replication state (run once after first import)
#   ./update.sh init --osm-file /path/to/file.pbf   # init from PBF replication metadata
# Env: IS_IN_DATABASE or DATABASE_URL, IS_IN_SCHEMA (default is_in),
#      IS_IN_FLEX_CONFIG (path to areas.lua), OSM2PGSQL_REPLICATION, OSM2PGSQL.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

DB="${IS_IN_DATABASE:-${DATABASE_URL:-}}"
if [[ -z "$DB" ]]; then
  echo "IS_IN_DATABASE or DATABASE_URL not set" >&2
  exit 1
fi

SCHEMA="${IS_IN_SCHEMA:-is_in}"
FLEX_CONFIG="${IS_IN_FLEX_CONFIG:-${SERVER_DIR}/flex_config/areas.lua}"
if [[ ! -f "$FLEX_CONFIG" ]]; then
  echo "Flex config not found: $FLEX_CONFIG (set IS_IN_FLEX_CONFIG if needed)" >&2
  exit 1
fi

REPLICATION_SCRIPT="${OSM2PGSQL_REPLICATION:-osm2pgsql-replication}"

# Run osm2pgsql-replication: subcommand must come first (argparse subparsers).
run_replication() {
  local subcmd="$1"
  shift
  local cmd=("$REPLICATION_SCRIPT" "$subcmd" -d "$DB" --schema "$SCHEMA" "$@")
  echo "Running: ${cmd[*]}" >&2
  "${cmd[@]}"
}

SUBCOMMAND="${1:-update}"
shift || true

case "$SUBCOMMAND" in
  init)
    run_replication init "$@"
    echo "Replication initialised. Run ./update.sh periodically (e.g. via cron) to apply updates." >&2
    ;;
  update)
    POST_SCRIPT=""
    if [[ -f "$SCRIPT_DIR/post_analyze.sh" ]]; then
      POST_SCRIPT="$SCRIPT_DIR/post_analyze.sh"
    fi
    if [[ -n "$POST_SCRIPT" ]]; then
      run_replication update --post-processing "$POST_SCRIPT" -- -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -x
    else
      run_replication update -- -O flex -S "$FLEX_CONFIG" --schema "$SCHEMA" -x
    fi
    ;;
  *)
    echo "Usage: $0 [init|update] [init args...]" >&2
    echo "  init    - initialise replication (run once after first PBF import)" >&2
    echo "  update  - download and apply incremental diffs (default)" >&2
    exit 1
    ;;
esac
