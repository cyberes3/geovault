#!/bin/bash
# Simple entrypoint script to run all GeoVault backend tests using pytest
#
# Usage:
#   ./run-tests.sh              # Run all tests
#   ./run-tests.sh -v           # Run with verbose output
#   ./run-tests.sh test_api     # Run only API tests
#   ./run-tests.sh test_validation/test_geojson_whitelist.py  # Run specific test file

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BACKEND_DIR="$SCRIPT_DIR/../backend"
VENV_PYTHON="$BACKEND_DIR/venv/bin/python"

# Check if venv exists
if [ ! -f "$VENV_PYTHON" ]; then
    echo "Error: Virtual environment not found at $BACKEND_DIR/venv"
    echo "Please create and activate the venv first."
    exit 1
fi

# Change to tests directory
cd "$SCRIPT_DIR" || exit 1

# Add to PYTHONPATH
export PYTHONPATH="$BACKEND_DIR:$SCRIPT_DIR/..:$PYTHONPATH"
export DJANGO_SETTINGS_MODULE=website.settings

# Prepare test database: drop all tables and let migrations recreate them
echo "Preparing test database (dropping all tables)..."
"$VENV_PYTHON" "$SCRIPT_DIR/prepare_test_db.py" || exit 1

# Run pytest using venv python
# Note: --reuse-db keeps the database but we drop tables manually above
# This ensures a clean state while avoiding Django 6.0a1 migration bugs
"$VENV_PYTHON" -m pytest --reuse-db -v "$@"

