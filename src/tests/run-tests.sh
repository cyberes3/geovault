#!/bin/bash
# Simple entrypoint script to run all GeoVault backend tests using pytest
# This takes 30+ minutes to run.
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

# Load .env if present (e.g. AREAS_SERVER_DATABASE for areas-server DB tests)
if [ -f "$SCRIPT_DIR/.env" ]; then
  set -a
  # shellcheck source=/dev/null
  . "$SCRIPT_DIR/.env"
  set +a
fi

# Add to PYTHONPATH
export PYTHONPATH="$BACKEND_DIR:$SCRIPT_DIR/..:$PYTHONPATH"
export DJANGO_SETTINGS_MODULE=website.settings

# example_extension ships disabled by default (it's a demo extension) but has a real
# test suite that exercises its live endpoints, so force it on for test runs regardless
# of the developer's local config.yaml.
export GEOVAULT_FORCE_ENABLED_EXTENSIONS="example_extension"

# Prepare test database: drop all tables and let migrations recreate them
echo "Preparing test database (dropping all tables)..."
"$VENV_PYTHON" "$SCRIPT_DIR/prepare_test_db.py" || exit 1

# Run pytest using venv python
# Note: --reuse-db keeps the database but we drop tables manually above
# This ensures a clean state while avoiding Django 6.0a1 migration bugs
"$VENV_PYTHON" -m pytest --reuse-db -v "$@"

