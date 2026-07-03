"""
Tests for SessionOriginValidator (WebSocket Origin validation for the session-cookie auth path).
"""
import asyncio
from unittest.mock import AsyncMock

from django.test import TestCase, override_settings

from website.websocket_origin_validation import SessionOriginValidator


def _run_async(coro):
    return asyncio.run(coro)


@override_settings(ALLOWED_HOSTS=["geovault.example.com"])
class TestSessionOriginValidator(TestCase):
    """No bearer token -> only a matching Origin is allowed through. A bearer token -> Origin
    validation is bypassed entirely (native app path; see module docstring for the reasoning)."""

    def setUp(self):
        self.app = AsyncMock()
        self.validator = SessionOriginValidator(self.app)
        # WebsocketDenier (the ASGI consumer OriginValidator hands denied connections to) awaits a
        # real "websocket.connect" message before sending its close frame, then loops awaiting the
        # next message -- which in a real ASGI server only arrives once the client disconnects.
        # Without a "websocket.disconnect" queued up next, the mock would keep returning
        # "websocket.connect" forever and the consumer would loop indefinitely re-closing.
        self.receive = AsyncMock(
            side_effect=[{"type": "websocket.connect"}, {"type": "websocket.disconnect", "code": 1000}]
        )
        self.send = AsyncMock()

    def test_non_websocket_scope_bypasses_origin_check_entirely(self):
        """AllowedHostsOriginValidator only supports websocket scopes; non-websocket scopes must
        never reach it, even without a bearer token."""
        scope = {"type": "http", "path": "/api/"}
        _run_async(self.validator(scope, self.receive, self.send))
        self.app.assert_awaited_once_with(scope, self.receive, self.send)

    def test_bearer_token_present_bypasses_origin_check(self):
        """A bearer token means this isn't a browser ambient-credential connection, so a missing
        or mismatched Origin must not block it."""
        scope = {
            "type": "websocket",
            "headers": [(b"authorization", b"Bearer some-token")],
        }
        _run_async(self.validator(scope, self.receive, self.send))
        self.app.assert_awaited_once_with(scope, self.receive, self.send)

    def test_bearer_token_present_with_hostile_origin_still_bypasses(self):
        """Even an attacker-controlled Origin doesn't matter once a bearer token is present."""
        scope = {
            "type": "websocket",
            "headers": [
                (b"authorization", b"Bearer some-token"),
                (b"origin", b"https://evil.example.com"),
            ],
        }
        _run_async(self.validator(scope, self.receive, self.send))
        self.app.assert_awaited_once_with(scope, self.receive, self.send)

    def test_no_token_matching_origin_is_allowed(self):
        scope = {
            "type": "websocket",
            "headers": [(b"origin", b"https://geovault.example.com")],
        }
        _run_async(self.validator(scope, self.receive, self.send))
        self.app.assert_awaited_once()
        called_scope = self.app.call_args[0][0]
        self.assertEqual(called_scope["type"], "websocket")

    def test_no_token_mismatched_origin_is_denied(self):
        scope = {
            "type": "websocket",
            "headers": [(b"origin", b"https://evil.example.com")],
        }
        _run_async(self.validator(scope, self.receive, self.send))
        self.app.assert_not_awaited()
        # OriginValidator denies via a WebsocketDenier, which sends websocket.close.
        self.send.assert_awaited()
        sent_message = self.send.call_args[0][0]
        self.assertEqual(sent_message.get("type"), "websocket.close")

    def test_no_token_missing_origin_is_denied(self):
        """Browsers always send Origin on a WebSocket handshake; a missing Origin with no bearer
        token is neither a legitimate browser request nor a native client, so it's denied."""
        scope = {"type": "websocket", "headers": []}
        _run_async(self.validator(scope, self.receive, self.send))
        self.app.assert_not_awaited()
        self.send.assert_awaited()
        sent_message = self.send.call_args[0][0]
        self.assertEqual(sent_message.get("type"), "websocket.close")
