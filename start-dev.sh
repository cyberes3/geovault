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
for ext_dir in "$SCRIPT_DIR/src/backend/extensions"/*/; do
    if [ -f "${ext_dir}src/frontend/package.json" ]; then
        ext_name=$(basename "$ext_dir")
        EXT_NAMES+=("Ext-${ext_name}")
        EXT_CMDS+=("cd ${ext_dir}src/frontend && npm run build -- --watch")
    fi
done

# One-time build for each extension so dist/ exists (then watch will keep it updated)
for ext_dir in "$SCRIPT_DIR/src/backend/extensions"/*/; do
    if [ -f "${ext_dir}src/frontend/package.json" ]; then
        ext_name=$(basename "$ext_dir")
        echo "Building extension frontend: $ext_name..."
        (cd "${ext_dir}src/frontend" && npm run build) || true
    fi
done

echo "Starting Django and Vite dev servers..."
echo "Django will run on: http://127.0.0.1:8000"
echo "Vite will run on: http://localhost:5173 (or next available port)"
if [ ${#EXT_NAMES[@]} -gt 2 ]; then
    echo "Extension frontends: build --watch enabled (changes will trigger full reload)"
fi
echo ""
echo "Press Ctrl+C to stop all"
echo ""

# Run Django, Vite, and each extension's build --watch with concurrently
CONCURRENTLY_NAMES=$(IFS=,; echo "${EXT_NAMES[*]}")
cd "$SCRIPT_DIR/src/frontend"
npx concurrently -n "$CONCURRENTLY_NAMES" -c "blue,green,yellow" "${EXT_CMDS[@]}"

