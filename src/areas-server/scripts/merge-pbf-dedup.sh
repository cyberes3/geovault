#!/usr/bin/env bash
# Merge two OSM PBF files with deduplication. osmium merge with exactly 2 inputs
# does not deduplicate; with 3+ inputs it does. We pass FILE1, FILE2, FILE1 so
# osmium sees 3 inputs—no third region or extra file needed. Use this when a
# single deduplicated PBF is required (e.g. for osm-lump-ways-down).
#
# Usage: ./merge-pbf-dedup.sh FILE1.pbf FILE2.pbf -o OUTPUT.pbf

set -euo pipefail

if [[ $# -lt 4 ]]; then
  echo "Usage: $0 FILE1.pbf FILE2.pbf -o OUTPUT.pbf" >&2
  exit 1
fi

F1="$1"
F2="$2"
shift 2
OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) OUT="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$OUT" ]]; then
  echo "Missing -o OUTPUT.pbf" >&2
  exit 1
fi
for f in "$F1" "$F2"; do
  if [[ ! -f "$f" ]]; then
    echo "File not found: $f" >&2
    exit 1
  fi
done

exec osmium merge "$F1" "$F2" "$F1" -o "$OUT" --overwrite
