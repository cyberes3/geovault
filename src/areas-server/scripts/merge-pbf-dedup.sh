#!/usr/bin/env bash
# Merge OSM PBF files with deduplication. osmium merge with exactly 2 inputs
# does not deduplicate; with 3+ inputs it does. For 2 files we pass F1, F2, F1
# so osmium sees 3 inputs. For 1 file we pass F, F, F to deduplicate within it.
# Use when a single deduplicated PBF is required (e.g. for osm-lump-ways-down).
#
# Usage: ./merge-pbf-dedup.sh FILE1.pbf [FILE2.pbf ...] -o OUTPUT.pbf
# Example with glob: ./merge-pbf-dedup.sh /srv/downloads/europe/*-latest.osm.pbf -o /srv/downloads/western-europe.osm.pbf

set -euo pipefail

OUT=""
FILES=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -o) OUT="$2"; shift 2 ;;
    -*) echo "Unknown option: $1" >&2; exit 1 ;;
    *)  FILES+=( "$1" ); shift ;;
  esac
done

if [[ -z "$OUT" ]]; then
  echo "Usage: $0 FILE.pbf [FILE2.pbf ...] -o OUTPUT.pbf" >&2
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

case ${#FILES[@]} in
  1) exec osmium merge "${FILES[0]}" "${FILES[0]}" "${FILES[0]}" -o "$OUT" --overwrite ;;
  2) exec osmium merge "${FILES[0]}" "${FILES[1]}" "${FILES[0]}" -o "$OUT" --overwrite ;;
  *) exec osmium merge "${FILES[@]}" -o "$OUT" --overwrite ;;
esac
