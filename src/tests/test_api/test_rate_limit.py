"""
Tests for the shared Redis rate limiting primitive (geo_lib.security.rate_limit.RedisRateLimiter).

Exercises the decorator directly against a throwaway view, rather than through any
particular extension, since this is a generic primitive used across the codebase.
"""
import json
import time

from django.contrib.auth.models import AnonymousUser
from django.contrib.auth import get_user_model
from django.core.cache import caches
from django.test import TestCase, override_settings, RequestFactory

from api.utils.responses import success_response
from geo_lib.security.rate_limit import RedisRateLimiter

User = get_user_model()

_test_limiter = RedisRateLimiter(name='test_rate_limit', limit=1, window_seconds=1.0)
_consumer_test_limiter = RedisRateLimiter(name='test_consumer_rate_limit', limit=1, window_seconds=1.0)


class _FakeUser:
    """Minimal stand-in for a Django user, exposing only what identity resolution needs."""

    def __init__(self, id, is_authenticated=True):
        self.id = id
        self.is_authenticated = is_authenticated


class _FakeConsumer:
    """Minimal stand-in for an AsyncWebsocketConsumer, exposing only what
    RedisRateLimiter.for_consumer() and its decorated method need: `user`, `channel_name`,
    and an async `send()`."""

    def __init__(self, user=None, channel_name='test-channel'):
        self.user = user
        self.channel_name = channel_name
        self.sent = []
        self.calls = 0

    async def send(self, text_data=None, bytes_data=None):
        self.sent.append(text_data)

    @_consumer_test_limiter.for_consumer()
    async def receive(self, text_data=None):
        self.calls += 1


@_test_limiter()
def rate_limit_test_view(request):
    """Test view function for rate limiting."""
    return success_response({'message': 'success'})


@_test_limiter()
def different_route_view(request):
    """Second view sharing the same limiter instance, to test per-route buckets."""
    return success_response({'message': 'different'})


class TestRateLimitDecorator(TestCase):
    """Test rate limiting decorator functionality."""

    def setUp(self):
        """Set up test fixtures."""
        self.factory = RequestFactory()
        self.user1 = User.objects.create_user(
            email='user1@example.com',
            password='testpass123',
            username='user1'
        )
        self.user2 = User.objects.create_user(
            email='user2@example.com',
            password='testpass123',
            username='user2'
        )

        # Clear cache before each test
        cache = caches['rate_limiting']
        cache.clear()

    def test_rate_limit_allows_first_request(self):
        """Test that first request is allowed."""
        request = self.factory.get('/')
        request.user = self.user1

        response = rate_limit_test_view(request)
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data['message'], 'success')

    def test_rate_limit_blocks_second_request_within_same_second(self):
        """Test that second request within same second is blocked."""
        request = self.factory.get('/')
        request.user = self.user1

        # First request should succeed
        response1 = rate_limit_test_view(request)
        self.assertEqual(response1.status_code, 200)

        # Second request immediately after should be blocked
        response2 = rate_limit_test_view(request)
        self.assertEqual(response2.status_code, 429)
        data = json.loads(response2.content)
        self.assertIn('Rate limit exceeded', data['error'])

    def test_rate_limit_allows_request_after_window_expires(self):
        """Test that request is allowed after 1 second window expires."""
        request = self.factory.get('/')
        request.user = self.user1

        # First request
        response1 = rate_limit_test_view(request)
        self.assertEqual(response1.status_code, 200)

        # Wait for window to expire (1 second + small buffer)
        time.sleep(1.1)

        # Second request should now be allowed
        response2 = rate_limit_test_view(request)
        self.assertEqual(response2.status_code, 200)

    def test_rate_limit_is_per_user(self):
        """Test that different users can make requests simultaneously."""
        request1 = self.factory.get('/')
        request1.user = self.user1

        request2 = self.factory.get('/')
        request2.user = self.user2

        # Both users should be able to make requests
        response1 = rate_limit_test_view(request1)
        response2 = rate_limit_test_view(request2)

        self.assertEqual(response1.status_code, 200)
        self.assertEqual(response2.status_code, 200)

    def test_rate_limit_is_per_route(self):
        """Test that different views decorated with the same limiter instance get
        separate buckets (keyed by module + qualname), not a shared one."""
        request = self.factory.get('/')
        request.user = self.user1

        # Make request to first route
        response1 = rate_limit_test_view(request)
        self.assertEqual(response1.status_code, 200)

        # Make request to different route (should be allowed, separate bucket)
        response2 = different_route_view(request)
        self.assertEqual(response2.status_code, 200)

        # But second request to first route should be blocked
        response3 = rate_limit_test_view(request)
        self.assertEqual(response3.status_code, 429)

    def test_rate_limit_returns_429_with_message(self):
        """Test that rate limit returns HTTP 429 with appropriate message."""
        request = self.factory.get('/')
        request.user = self.user1

        # First request
        rate_limit_test_view(request)

        # Second request should return 429
        response = rate_limit_test_view(request)
        self.assertEqual(response.status_code, 429)
        data = json.loads(response.content)
        self.assertIn('Rate limit exceeded', data['error'])

    @override_settings(CACHES={
        'rate_limiting': {
            'BACKEND': 'django.core.cache.backends.dummy.DummyCache',
        }
    })
    def test_rate_limit_fail_open_behavior(self):
        """Test that rate limit allows request if Redis unavailable."""
        request = self.factory.get('/')
        request.user = self.user1

        # Should allow request even if cache fails
        response = rate_limit_test_view(request)
        self.assertEqual(response.status_code, 200)

    def test_rate_limit_falls_back_to_ip_for_unauthenticated_users(self):
        """Unauthenticated requests are rate-limited by IP rather than rejected outright
        (auth enforcement is a separate concern, handled by auth decorators upstream)."""
        request = self.factory.get('/')
        request.user = AnonymousUser()

        response = rate_limit_test_view(request)
        self.assertEqual(response.status_code, 200)

        # Second request from the same (anonymous) IP within the window is blocked.
        response2 = rate_limit_test_view(request)
        self.assertEqual(response2.status_code, 429)

    def test_concurrent_requests_race_condition_handling(self):
        """Test that concurrent requests from same user/route handle race conditions."""
        request = self.factory.get('/')
        request.user = self.user1

        # Simulate race condition: two requests check cache at same time
        # Both see count=0, both try to add
        cache = caches['rate_limiting']
        cache.clear()

        # First request
        response1 = rate_limit_test_view(request)
        self.assertEqual(response1.status_code, 200)

        # Simulate second request checking immediately
        # The add() operation should be atomic, so only one should succeed
        response2 = rate_limit_test_view(request)
        # Should be blocked because first request already set the key
        self.assertEqual(response2.status_code, 429)


class TestRateLimiterForConsumer(TestCase):
    """Tests for RedisRateLimiter.for_consumer(), the WebSocket-consumer analogue of the view
    decorator above, used to rate-limit inbound messages on `receive()` in the realtime and
    process-status consumers."""

    def setUp(self):
        caches['rate_limiting'].clear()

    async def test_allows_first_message(self):
        consumer = _FakeConsumer(user=_FakeUser(id=1))
        await consumer.receive(text_data='{}')
        self.assertEqual(consumer.calls, 1)
        self.assertEqual(consumer.sent, [])

    async def test_blocks_second_message_within_window_and_sends_error_frame_instead_of_closing(self):
        consumer = _FakeConsumer(user=_FakeUser(id=1))
        await consumer.receive(text_data='{}')
        await consumer.receive(text_data='{}')

        # The over-limit message is dropped (handler body never runs a second time)...
        self.assertEqual(consumer.calls, 1)
        # ...but the connection itself is left open, with an error frame sent instead.
        self.assertEqual(len(consumer.sent), 1)
        payload = json.loads(consumer.sent[0])
        self.assertEqual(payload['type'], 'error')
        self.assertEqual(payload['data']['code'], 429)

    async def test_is_per_user_not_shared_across_connections(self):
        consumer1 = _FakeConsumer(user=_FakeUser(id=1))
        consumer2 = _FakeConsumer(user=_FakeUser(id=2))

        await consumer1.receive(text_data='{}')
        await consumer2.receive(text_data='{}')

        self.assertEqual(consumer1.calls, 1)
        self.assertEqual(consumer2.calls, 1)
        self.assertEqual(consumer1.sent, [])
        self.assertEqual(consumer2.sent, [])

    async def test_unauthenticated_connections_keyed_by_channel_name_not_shared_globally(self):
        anon = _FakeUser(id=None, is_authenticated=False)
        consumer1 = _FakeConsumer(user=anon, channel_name='chan-a')
        consumer2 = _FakeConsumer(user=anon, channel_name='chan-b')

        await consumer1.receive(text_data='{}')
        await consumer2.receive(text_data='{}')

        self.assertEqual(consumer1.calls, 1)
        self.assertEqual(consumer2.calls, 1)

    async def test_allows_message_after_window_expires(self):
        consumer = _FakeConsumer(user=_FakeUser(id=1))
        await consumer.receive(text_data='{}')
        time.sleep(1.1)
        await consumer.receive(text_data='{}')
        self.assertEqual(consumer.calls, 2)
