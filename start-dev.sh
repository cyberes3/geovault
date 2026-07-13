#!/bin/bash
# Development server script - runs both Django and Vite dev servers together

set -e

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR"

# Check if concurrently is installed
if ! command -v npx &> /dev/null; then
    echo "Error: npx is not installed. Please install Node.js and npm."
    exit 1
fi

# Check if we're in the right directory
if [ ! -d "src/frontend" ] || [ ! -d "src/backend" ]; then
    echo "Error: This script must be run from the project root directory."
    exit 1
fi

# Celery beat schedule file location (keep sqlite state in backend data dir).
CELERY_SCHEDULE_DIR="$SCRIPT_DIR/src/backend/data/celery"
CELERY_SCHEDULE_FILE="$CELERY_SCHEDULE_DIR/celerybeat-schedule"
mkdir -p "$CELERY_SCHEDULE_DIR"

# Collect backend/frontend/dev-worker commands.
EXT_NAMES=("Django" "CeleryWorker" "CeleryBeat" "Vite")
EXT_CMDS=(
    "cd $SCRIPT_DIR/src/backend && ./server-dev.sh"
    "cd $SCRIPT_DIR/src/backend && ./venv/bin/celery -A website.celery_app worker --loglevel=info --queues=default,maintenance,extensions,live_track,imports"
    "cd $SCRIPT_DIR/src/backend && ./venv/bin/celery -A website.celery_app beat --loglevel=info --schedule \"$CELERY_SCHEDULE_FILE\""
    "cd $SCRIPT_DIR/src/frontend && npm run dev"
)
# Unoptimized extension builds in dev (no minify, with sourcemaps) for debuggable stack traces
export GEOVAULT_EXTENSION_DEV=1
for ext_dir in "$SCRIPT_DIR/src/backend/extensions"/*/; do
    if [ -f "${ext_dir}src/frontend/package.json" ]; then
        ext_name=$(basename "$ext_dir")
        EXT_NAMES+=("Ext-${ext_name}")
        # Exit non-zero on build failure so concurrently --kill-others-on-fail stops everything
        EXT_CMDS+=("cd ${ext_dir}src/frontend && npm run build -- --watch || exit 1")
    fi
done

echo "Starting Django, Celery, and Vite dev servers..."
echo "Django will run on: http://0.0.0.0:8000"
echo "Celery worker queue(s): default,maintenance,extensions,live_track,imports"
echo "Celery beat: periodic tasks scheduler"
echo "Celery beat schedule state: $CELERY_SCHEDULE_FILE"
echo "Vite will run on: http://0.0.0.0:5173 (or next available port)"
if [ ${#EXT_NAMES[@]} -gt 2 ]; then
    echo "Extension frontends: build --watch enabled (changes will trigger full reload)"
fi
echo ""
echo "Press Ctrl+C to stop all"
echo ""

# Run Django, Celery worker/beat, Vite, and each extension's build --watch with concurrently.
# If any process exits with non-zero status (e.g. extension build fails), kill all others so errors aren't silent.
CONCURRENTLY_NAMES=$(IFS=,; echo "${EXT_NAMES[*]}")
cd "$SCRIPT_DIR/src/frontend"
npx concurrently -n "$CONCURRENTLY_NAMES" -c "blue,magenta,cyan,green,yellow" --kill-others-on-fail "${EXT_CMDS[@]}"

