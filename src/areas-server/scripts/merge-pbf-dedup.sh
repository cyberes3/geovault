#!/usr/bin/env bash
# Merge OSM PBF files with deduplication, then sort the result.
# osmium merge requires inputs sorted by type, ID, and version; Geofabrik
# regional extracts are not guaranteed to be sorted, so merging them directly
# can produce empty or undefined output. We merge then run osmium sort so the
# output is valid for downstream tools (e.g. osm-lump-ways-down).
# osmium merge with exactly 2 inputs does not deduplicate; with 3+ it does.
# For 2 files we pass F1, F2, F1. For 1 file we pass F, F, F to deduplicate.
#
# Usage: ./merge-pbf-dedup.sh [OPTIONS] FILE1.pbf [FILE2.pbf ...] -o OUTPUT.pbf
#   --tmp-dir DIR   Directory for merge temporary file (default: same as output)
# Example: ./merge-pbf-dedup.sh /srv/downloads/europe/*-latest.osm.pbf /srv/downloads/north-america-latest.osm.pbf -o /srv/downloads/merged.osm.pbf

set -euo pipefail

OUT=""
TMP_DIR=""
FILES=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) OUT="$2"; shift 2 ;;
    --tmp-dir) TMP_DIR="$2"; shift 2 ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *)  FILES+=( "$1" ); shift ;;
  esac
done

if [[ -z "$OUT" ]]; then
  echo "Usage: $0 [--tmp-dir DIR] FILE.pbf [FILE2.pbf ...] -o OUTPUT.pbf" >&2
  exit 1
fi
# Default temp directory to output directory
[[ -z "$TMP_DIR" ]] && TMP_DIR="$(dirname "$OUT")"
if [[ ! -d "$TMP_DIR" ]]; then
  echo "Temp directory does not exist: $TMP_DIR" >&2
  exit 1
fi
if [[ ${#FILES[@]} -lt 1 ]]; then
  echo "At least one input file required." >&2
  exit 1
fi
for f in "${FILES[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "File not found: $f" >&2
    exit 1
  fi
done

MERGE_TMP=""
cleanup() {
  [[ -n "$MERGE_TMP" && -f "$MERGE_TMP" ]] && rm -f "$MERGE_TMP"
}
trap cleanup EXIT

MERGE_TMP="$(mktemp -u -p "$TMP_DIR" "osmium-merge.XXXXXX.pbf")"

case ${#FILES[@]} in
  1) osmium merge "${FILES[0]}" "${FILES[0]}" "${FILES[0]}" -o "$MERGE_TMP" --overwrite ;;
  2) osmium merge "${FILES[0]}" "${FILES[1]}" "${FILES[0]}" -o "$MERGE_TMP" --overwrite ;;
  *) osmium merge "${FILES[@]}" -o "$MERGE_TMP" --overwrite ;;
esac

# Sort so output is type,id,version ordered (required by osm-lump-ways-down and others).
# Use multipass strategy to avoid OOM: one pass per type (nodes, ways, relations) instead of loading all in memory.
osmium sort -s multipass "$MERGE_TMP" -o "$OUT" --overwrite
