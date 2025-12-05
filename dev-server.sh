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

echo "Starting both Django and Vite dev servers..."
echo "Django will run on: http://127.0.0.1:8000"
echo "Vite will run on: http://localhost:5173 (or next available port)"
echo ""
echo "Press Ctrl+C to stop both servers"
echo ""

# Run both servers using concurrently
cd src/frontend
npm run dev:full

