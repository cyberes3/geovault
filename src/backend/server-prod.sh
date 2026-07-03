#!/bin/bash
# Production server startup script for GeoVault

set -e

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd "$SCRIPT_DIR"

source "$SCRIPT_DIR"/venv/bin/activate

HOST="0.0.0.0"
PORT="8000"

WEBSOCKET_MAX_SIZE=$((10 * 1024 * 1024))
exec daphne \
    --bind "$HOST" \
    --port "$PORT" \
    --access-log /dev/null \
    --websocket-max-message-size "$WEBSOCKET_MAX_SIZE" \
    --websocket-max-frame-size "$WEBSOCKET_MAX_SIZE" \
    website.asgi:application
