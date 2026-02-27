#!/usr/bin/env bash
# Merge OSM PBF files with deduplication. Output is sorted (type, ID, version).
# osmium merge requires sorted inputs; Geofabrik regional extracts are not, so we
# sort each input with osmium sort -s multipass (one region at a time to limit RAM),
# then merge the sorted files. Merge is streaming so it never holds the full dataset.
# For 2 inputs merge does not deduplicate; we pass F1, F2, F1. For 1 file we pass F, F, F.
#
# Usage: ./merge-pbf-dedup.sh [OPTIONS] FILE1.pbf [FILE2.pbf ...] -o OUTPUT.pbf
#   --tmp-dir DIR   Directory for sorted temporary files (default: same as output)
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

SORTED_TMP=()
cleanup() {
  for f in "${SORTED_TMP[@]}"; do
    [[ -f "$f" ]] && rm -f "$f"
  done
}
trap cleanup EXIT

# Sort each input individually (multipass = lower RAM per file; never sort the full merged dataset)
for i in "${!FILES[@]}"; do
  t="$(mktemp -p "$TMP_DIR" "osmium-sorted.XXXXXX.pbf")"
  SORTED_TMP+=("$t")
  echo "Sorting ${FILES[$i]} -> temp $((i+1))/${#FILES[@]}"
  osmium sort -s multipass "${FILES[$i]}" -o "$t" --overwrite
done

# Merge sorted files (streaming, low memory). Output is sorted.
echo "Merging ${#SORTED_TMP[@]} sorted files -> $OUT"
case ${#SORTED_TMP[@]} in
  1) osmium merge "${SORTED_TMP[0]}" "${SORTED_TMP[0]}" "${SORTED_TMP[0]}" -o "$OUT" --overwrite ;;
  2) osmium merge "${SORTED_TMP[0]}" "${SORTED_TMP[1]}" "${SORTED_TMP[0]}" -o "$OUT" --overwrite ;;
  *) osmium merge "${SORTED_TMP[@]}" -o "$OUT" --overwrite ;;
esac
