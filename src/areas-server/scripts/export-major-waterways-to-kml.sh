#!/usr/bin/env bash
# Export waterways.major_waterways from Postgres to KML for verification.
#
# Requires: ogr2ogr (GDAL)
#
# Usage:
#   ./export-major-waterways-to-kml.sh DATABASE_URL [output.kml]
#
# If output path is omitted, writes major-waterways.kml in the current directory.

set -euo pipefail

SCHEMA="waterways"
TABLE="major_waterways"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 DATABASE_URL [output.kml]" >&2
  echo "  Exports ${SCHEMA}.${TABLE} to KML." >&2
  exit 1
fi

DATABASE_URL="$1"
KML_PATH="${2:-major-waterways.kml}"

# ogr2ogr for PG expects connection string; postgresql:// works with GDAL 2+
ogr2ogr -f KML "$KML_PATH" "PG:${DATABASE_URL}" -sql "SELECT tag_group_value, length_m, max_upstream_m, geom FROM waterways.${TABLE} ORDER BY max_upstream_m DESC" -t_srs EPSG:4326

echo "Wrote: $KML_PATH ($(du -h "$KML_PATH" | cut -f1))"
