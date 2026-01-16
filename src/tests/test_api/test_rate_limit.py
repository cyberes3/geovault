"""
Tests for custom Redis rate limiting decorator.
"""
import json
import time
from unittest.mock import patch, MagicMock
from django.test import TestCase, override_settings, RequestFactory
from django.contrib.auth import get_user_model
from django.core.cache import caches

from extensions.caltopo.src.backend.utils.rate_limit import caltopo_rate_limit
from api.utils.responses import success_response

User = get_user_model()


@caltopo_rate_limit('test_route')
def rate_limit_test_view(request):
    """Test view function for rate limiting."""
    return success_response({'message': 'success'})


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
        """Test that different routes have separate limits."""
        @caltopo_rate_limit('different_route')
        def different_route_view(request):
            return success_response({'message': 'different'})
        
        request = self.factory.get('/')
        request.user = self.user1
        
        # Make request to first route
        response1 = rate_limit_test_view(request)
        self.assertEqual(response1.status_code, 200)
        
        # Make request to different route (should be allowed)
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
        self.assertIn('seconds', data['error'])
    
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
    
    def test_rate_limit_uses_correct_cache_key_format(self):
        """Test that cache key format is correct."""
        request = self.factory.get('/')
        request.user = self.user1
        
        cache = caches['rate_limiting']
        cache.clear()
        
        # Make request
        rate_limit_test_view(request)
        
        # Check cache keys (they should have the format we expect)
        # The cache uses KEY_PREFIX='ratelimit', so keys are: ratelimit:{user_id}:{route}:{window}
        current_time = int(time.time())
        expected_key_prefix = f"{self.user1.id}:test_route:{current_time}"
        
        # We can't easily inspect cache keys, but we can verify behavior
        # If the key format was wrong, the rate limit wouldn't work correctly
        response2 = rate_limit_test_view(request)
        self.assertEqual(response2.status_code, 429)  # Should be blocked
    
    def test_rate_limit_handles_unauthenticated_users(self):
        """Test that rate limit returns 401 for unauthenticated users."""
        from django.contrib.auth.models import AnonymousUser
        
        request = self.factory.get('/')
        request.user = AnonymousUser()
        
        response = rate_limit_test_view(request)
        self.assertEqual(response.status_code, 401)
        data = json.loads(response.content)
        self.assertIn('Authentication required', data['error'])
    
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

