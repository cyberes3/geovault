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

# Collect extension frontends and do one initial build so dist/ exists when app loads
EXT_NAMES=("Django" "Vite")
EXT_CMDS=("cd $SCRIPT_DIR/src/backend && ./server-dev.sh" "cd $SCRIPT_DIR/src/frontend && npm run dev")
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

echo "Starting Django and Vite dev servers..."
echo "Django will run on: http://0.0.0.0:8000"
echo "Vite will run on: http://0.0.0.0:5173 (or next available port)"
if [ ${#EXT_NAMES[@]} -gt 2 ]; then
    echo "Extension frontends: build --watch enabled (changes will trigger full reload)"
fi
echo ""
echo "Press Ctrl+C to stop all"
echo ""

# Run Django, Vite, and each extension's build --watch with concurrently.
# If any process exits with non-zero status (e.g. extension build fails), kill all others so errors aren't silent.
CONCURRENTLY_NAMES=$(IFS=,; echo "${EXT_NAMES[*]}")
cd "$SCRIPT_DIR/src/frontend"
npx concurrently -n "$CONCURRENTLY_NAMES" -c "blue,green,yellow" --kill-others-on-fail "${EXT_CMDS[@]}"

