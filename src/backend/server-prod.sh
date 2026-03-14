#!/bin/bash
# Production server startup script for GeoVault

set -e  # Exit on error

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd "$SCRIPT_DIR"

source "$SCRIPT_DIR"/venv/bin/activate

# Configuration
HOST="0.0.0.0"
PORT="8000"

# Note: Daphne is single-process. For multiple workers, run multiple instances.
# Celery worker and beat are managed by systemd units:
# - geovault-celery.service
# - geovault-celery-beat.service
exec daphne \
    --bind "$HOST" \
    --port "$PORT" \
    --access-log /dev/null \
    website.asgi:application
