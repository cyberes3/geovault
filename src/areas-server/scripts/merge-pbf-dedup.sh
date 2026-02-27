#!/usr/bin/env bash
# Merge two OSM PBF files with deduplication (same object in both files appears once).
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
if [[ ! -f "$F1" ]]; then
  echo "File not found: $F1" >&2
  exit 1
fi
if [[ ! -f "$F2" ]]; then
  echo "File not found: $F2" >&2
  exit 1
fi

# Osmium merge with 2 inputs does not deduplicate; 3 inputs does. Pass F1 twice to get dedup with 2 logical inputs.
exec osmium merge "$F1" "$F2" "$F1" -o "$OUT" --overwrite --with-history
