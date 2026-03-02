#!/usr/bin/env bash
# Remove rows with null/empty tag_group_value from waterways.major_waterways.
#
# Usage:
#   ./fix-waterways-empty-names.sh [DATABASE_URL]
#   AREAS_SERVER_DATABASE=postgresql://... ./fix-waterways-empty-names.sh
#
# If DATABASE_URL is not passed, uses AREAS_SERVER_DATABASE from the environment
# (e.g. set in tests/.env).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATABASE_URL="${1:-${AREAS_SERVER_DATABASE:-}}"

if [[ -z "$DATABASE_URL" ]]; then
  echo "Usage: $0 DATABASE_URL" >&2
  echo "  or set AREAS_SERVER_DATABASE (e.g. from tests/.env)" >&2
  exit 1
fi

echo "Removing rows with null/empty name from waterways.major_waterways..."
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -c "
  DELETE FROM waterways.major_waterways
  WHERE tag_group_value IS NULL OR trim(tag_group_value) = '';
"
echo "Done."
