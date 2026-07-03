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
#
# Daphne's WebSocket message/frame size defaults to 1 MiB. Our application-level code bounds
# what it sends/expects well below that (see geo_lib/websocket/base_module.py), but we raise
# the hard transport limit to 10 MiB as extra headroom against any future oversized message.
WEBSOCKET_MAX_SIZE=$((10 * 1024 * 1024))
exec daphne \
    --bind "$HOST" \
    --port "$PORT" \
    --access-log /dev/null \
    --websocket-max-message-size "$WEBSOCKET_MAX_SIZE" \
    --websocket-max-frame-size "$WEBSOCKET_MAX_SIZE" \
    website.asgi:application
