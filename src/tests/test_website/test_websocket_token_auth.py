"""
Tests for WebSocket token authentication middleware (Authorization header).
"""
import asyncio
from unittest.mock import AsyncMock, patch, MagicMock

from django.contrib.auth.models import AnonymousUser
from django.test import TestCase

from website.websocket_token_auth import (
    get_bearer_token_from_scope,
    _resolve_token_to_user_sync,
    WebSocketTokenAuthMiddleware,
)


class TestGetTokenFromScope:
    """Tests for get_bearer_token_from_scope."""

    def test_no_headers_returns_none(self):
        scope = {"headers": []}
        assert get_bearer_token_from_scope(scope) is None

    def test_missing_headers_key_returns_none(self):
        scope = {}
        assert get_bearer_token_from_scope(scope) is None

    def test_authorization_bearer_returns_token(self):
        scope = {"headers": [(b"authorization", b"Bearer my-token-123")]}
        assert get_bearer_token_from_scope(scope) == "my-token-123"

    def test_authorization_bearer_lowercase(self):
        scope = {"headers": [(b"authorization", b"bearer other-token")]}
        assert get_bearer_token_from_scope(scope) == "other-token"

    def test_authorization_non_bearer_returns_none(self):
        scope = {"headers": [(b"authorization", b"Basic dXNlcjpwYXNz")]}
        assert get_bearer_token_from_scope(scope) is None

    def test_authorization_empty_bearer_returns_none(self):
        scope = {"headers": [(b"authorization", b"Bearer ")]}
        assert get_bearer_token_from_scope(scope) is None

    def test_authorization_bearer_strips_whitespace(self):
        scope = {"headers": [(b"authorization", b"Bearer   token-value  ")]}
        assert get_bearer_token_from_scope(scope) == "token-value"

    def test_other_headers_ignored(self):
        scope = {
            "headers": [
                (b"content-type", b"application/json"),
                (b"authorization", b"Bearer the-token"),
            ]
        }
        assert get_bearer_token_from_scope(scope) == "the-token"

    def test_authorization_value_as_string_decoded(self):
        scope = {"headers": [(b"authorization", "Bearer string-token")]}
        assert get_bearer_token_from_scope(scope) == "string-token"


class TestResolveTokenToUserSync:
    """Tests for _resolve_token_to_user_sync (with mocks)."""

    def test_none_token_returns_none(self):
        assert _resolve_token_to_user_sync(None) is None

    def test_empty_token_returns_none(self):
        assert _resolve_token_to_user_sync("") is None

    @patch("website.websocket_token_auth._resolve_oauth2_access_token")
    @patch("website.websocket_token_auth.validate_api_key")
    def test_valid_oauth2_returns_user(self, mock_validate_api_key, mock_resolve_oauth):
        user = MagicMock()
        mock_resolve_oauth.return_value = (user, MagicMock())
        assert _resolve_token_to_user_sync("valid-oauth-token") is user
        mock_resolve_oauth.assert_called_once_with("valid-oauth-token")
        mock_validate_api_key.assert_not_called()

    @patch("website.websocket_token_auth._resolve_oauth2_access_token")
    @patch("website.websocket_token_auth.validate_api_key")
    def test_oauth2_miss_then_api_key_hit_returns_user(self, mock_validate_api_key, mock_resolve_oauth):
        mock_resolve_oauth.return_value = None
        user = MagicMock()
        mock_validate_api_key.return_value = (user, MagicMock())
        assert _resolve_token_to_user_sync("api-key-token") is user
        mock_resolve_oauth.assert_called_once_with("api-key-token")
        mock_validate_api_key.assert_called_once_with("api-key-token")

    @patch("website.websocket_token_auth._resolve_oauth2_access_token")
    @patch("website.websocket_token_auth.validate_api_key")
    def test_both_miss_returns_none(self, mock_validate_api_key, mock_resolve_oauth):
        mock_resolve_oauth.return_value = None
        mock_validate_api_key.return_value = None
        assert _resolve_token_to_user_sync("bad-token") is None


class TestWebSocketTokenAuthMiddleware(TestCase):
    """Tests for WebSocketTokenAuthMiddleware."""

    def _run_async(self, coro):
        return asyncio.run(coro)

    def test_http_scope_passes_through(self):
        app = AsyncMock()
        middleware = WebSocketTokenAuthMiddleware(app)
        scope = {"type": "http", "path": "/api/"}
        receive = AsyncMock()
        send = AsyncMock()
        self._run_async(middleware(scope, receive, send))
        app.assert_awaited_once_with(scope, receive, send)
        self.assertNotIn("user", scope)

    def test_websocket_scope_no_auth_passes_through(self):
        app = AsyncMock()
        middleware = WebSocketTokenAuthMiddleware(app)
        scope = {"type": "websocket", "headers": []}
        receive = AsyncMock()
        send = AsyncMock()
        self._run_async(middleware(scope, receive, send))
        app.assert_awaited_once_with(scope, receive, send)

    def test_websocket_scope_valid_token_sets_user(self):
        app = AsyncMock()
        middleware = WebSocketTokenAuthMiddleware(app)
        user = MagicMock()
        scope = {"type": "websocket", "headers": [(b"authorization", b"Bearer valid")]}
        receive = AsyncMock()
        send = AsyncMock()

        with patch("website.websocket_token_auth._resolve_token_to_user_sync", return_value=user):
            self._run_async(middleware(scope, receive, send))

        call_scope = app.call_args[0][0]
        self.assertIs(call_scope["user"], user)

    def test_websocket_scope_invalid_token_passes_through(self):
        app = AsyncMock()
        middleware = WebSocketTokenAuthMiddleware(app)
        scope = {"type": "websocket", "headers": [(b"authorization", b"Bearer invalid")]}
        receive = AsyncMock()
        send = AsyncMock()

        with patch("website.websocket_token_auth._resolve_token_to_user_sync", return_value=None):
            self._run_async(middleware(scope, receive, send))

        call_scope = app.call_args[0][0]
        self.assertIs(call_scope, scope)
        self.assertIsNone(scope.get("user"))

    def test_websocket_scope_already_authenticated_user_unchanged(self):
        """If scope already has an authenticated user, middleware does not overwrite."""
        app = AsyncMock()
        middleware = WebSocketTokenAuthMiddleware(app)
        existing_user = MagicMock()
        scope = {"type": "websocket", "user": existing_user, "headers": [(b"authorization", b"Bearer token")]}
        receive = AsyncMock()
        send = AsyncMock()

        with patch("website.websocket_token_auth._resolve_token_to_user_sync", return_value=MagicMock()) as mock_resolve:
            self._run_async(middleware(scope, receive, send))

        mock_resolve.assert_not_called()
        app.assert_awaited_once_with(scope, receive, send)
