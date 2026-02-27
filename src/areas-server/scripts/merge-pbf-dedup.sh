#!/usr/bin/env bash
# Deduplicate or merge OSM PBF files (osmium merge with 3 inputs deduplicates; 2 inputs does not).
# One file:  ./merge-pbf-dedup.sh FILE.pbf -o DEDUPED.pbf
# Two files: ./merge-pbf-dedup.sh FILE1.pbf FILE2.pbf -o MERGED.pbf

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
  echo "Usage: $0 FILE.pbf -o OUTPUT.pbf   OR   $0 FILE1.pbf FILE2.pbf -o OUTPUT.pbf" >&2
  exit 1
fi
if [[ ${#FILES[@]} -lt 1 || ${#FILES[@]} -gt 2 ]]; then
  echo "Usage: $0 FILE.pbf -o OUTPUT.pbf   OR   $0 FILE1.pbf FILE2.pbf -o OUTPUT.pbf" >&2
  exit 1
fi
for f in "${FILES[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "File not found: $f" >&2
    exit 1
  fi
done

# Osmium merge with 2 inputs does not deduplicate; 3+ inputs does. Pass each file twice to deduplicate within and across files in one pass.
if [[ ${#FILES[@]} -eq 1 ]]; then
  exec osmium merge "${FILES[0]}" "${FILES[0]}" "${FILES[0]}" -o "$OUT" --overwrite
else
  exec osmium merge "${FILES[0]}" "${FILES[1]}" "${FILES[0]}" "${FILES[1]}" -o "$OUT" --overwrite
fi
