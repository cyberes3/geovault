"""
Shared app-level WebSocket ping/pong helper.

App-level ping/pong -- a JSON message round-tripped through a consumer's own `receive()`/`send()`
-- is distinct from the WebSocket protocol's own ping/pong control frames, which are handled
automatically by the ASGI server (Daphne) below the application layer. A client answering a
protocol-level ping only proves the raw socket is alive; it does *not* prove this specific
consumer instance's event loop is still accepting and running application code, since a hung or
zombied consumer task can still have its protocol-level pings answered by the server underneath
it. App-level ping/pong closes that gap: a client's liveness watchdog can trust "I got a pong"
to mean the actual message-handling code for this connection is still alive and responsive.

Every consumer that wants this guarantee calls `is_ping_message` at the top of its own `receive()`
and replies with `pong_payload(...)`. This module only centralizes that check and reply-building
logic -- each consumer keeps its own established wire shape (with or without a `module` field) so
already-deployed clients keep working unmodified. See `RealtimeConsumer`, `ProcessStatusConsumer`,
and `LiveTrackOnlyConsumer` for the three current call sites.
"""
import json
from typing import Optional


def is_ping_message(payload: dict) -> bool:
    """True if a parsed JSON WebSocket message is an app-level liveness ping."""
    return isinstance(payload, dict) and payload.get("type") == "ping"


def pong_payload(module: Optional[str] = None) -> str:
    """
    Builds the JSON-encoded pong reply.

    `module` is optional and only included to match a specific consumer's existing wire shape
    (e.g. `LiveTrackOnlyConsumer` tags every message with `module: "live_track"`); omit it for
    consumers whose ping/pong messages never carried a `module` field.
    """
    payload = {"type": "pong", "data": {}}
    if module is not None:
        payload["module"] = module
    return json.dumps(payload)
