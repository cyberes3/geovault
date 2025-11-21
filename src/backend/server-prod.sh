#!/bin/bash
# Production server startup script for GeoVault
# Uses Daphne (ASGI server) to support WebSockets

set -e  # Exit on error

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
cd "$SCRIPT_DIR"

source "$SCRIPT_DIR"/venv/bin/activate

# Configuration
HOST="0.0.0.0"
PORT="8000"

# Note: Daphne is single-process. For multiple workers, run multiple instances
# or use a process manager like supervisord/systemd.
# --access-log /dev/null disables Daphne's access logging (we use our own unified format)
exec daphne \
    --bind "$HOST" \
    --port "$PORT" \
    --access-log /dev/null \
    website.asgi:application

