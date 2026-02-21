"""
Tests for Overpass API retry logic with timeout handling.

These tests verify that the Overpass API retry mechanism works correctly,
including exponential backoff, maximum retry limits, and proper error handling.
"""
import pytest
import time
import json
from unittest.mock import patch, MagicMock, Mock
import requests

from geo_lib.reverse_geocoding.overpass_api import query_overpass
from fixtures.geocoding_responses import RETRY_TEST_SUCCESS_RESPONSE, EMPTY_RESPONSE


class TestOverpassAPIRetry:
    """Test Overpass API retry logic."""

    def test_retry_on_timeout_with_exponential_backoff(self):
        """Test that timeouts trigger retries with exponential backoff."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        sleep_times = []
        
        def mock_sleep(seconds):
            """Track sleep times."""
            sleep_times.append(seconds)
        
        def mock_post_timeout(*args, **kwargs):
            """Mock requests.post to timeout on first two attempts, succeed on third."""
            call_count[0] += 1
            if call_count[0] <= 2:
                raise requests.exceptions.Timeout("Read timed out")
            # Success on third attempt - use real response fixture
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.text = json.dumps(RETRY_TEST_SUCCESS_RESPONSE)
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_timeout), \
             patch('geo_lib.reverse_geocoding.overpass_api.time.sleep', side_effect=mock_sleep), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have retried 3 times (initial + 2 retries)
        assert call_count[0] == 3
        # Should have slept twice (before retry 2 and retry 3)
        assert len(sleep_times) == 2
        # First sleep should be 10s (max(10, 2^0) = 10)
        assert sleep_times[0] == 10
        # Second sleep should be 10s (max(10, 2^1) = 10)
        assert sleep_times[1] == 10
        # Should return successful result
        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None

    def test_timeout_max_retries_exceeded(self):
        """Test that exceeding max retries returns (None, error_message)."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        
        def mock_post_always_timeout(*args, **kwargs):
            """Mock requests.post to always timeout."""
            call_count[0] += 1
            raise requests.exceptions.Timeout("Read timed out")
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_always_timeout), \
             patch('geo_lib.reverse_geocoding.overpass_api.time.sleep'), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have tried 3 times (max_retries = 3)
        assert call_count[0] == 3
        # Should return (None, error_message) after all retries exhausted
        assert result is None
        assert error is not None
        assert "timeout" in error.lower()

    def test_timeout_success_after_one_retry(self):
        """Test successful response after one timeout retry."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        sleep_times = []
        
        def mock_sleep(seconds):
            """Track sleep times."""
            sleep_times.append(seconds)
        
        def mock_post_timeout_then_success(*args, **kwargs):
            """Mock requests.post to timeout once, then succeed."""
            call_count[0] += 1
            if call_count[0] == 1:
                raise requests.exceptions.Timeout("Read timed out")
            # Success on second attempt
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.text = json.dumps(RETRY_TEST_SUCCESS_RESPONSE)
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_timeout_then_success), \
             patch('geo_lib.reverse_geocoding.overpass_api.time.sleep', side_effect=mock_sleep), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have tried twice (initial + 1 retry)
        assert call_count[0] == 2
        # Should have slept once (before retry)
        assert len(sleep_times) == 1
        # Sleep should be 10s (max(10, 2^0) = 10)
        assert sleep_times[0] == 10
        # Should return successful result
        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None

    def test_rate_limit_429_handling(self):
        """Test 429 rate limit handling with 60s wait time."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        sleep_times = []
        
        def mock_sleep(seconds):
            """Track sleep times."""
            sleep_times.append(seconds)
        
        def mock_post_429_then_success(*args, **kwargs):
            """Mock requests.post to return 429 on first attempt, succeed on second."""
            call_count[0] += 1
            if call_count[0] == 1:
                mock_response = MagicMock()
                mock_response.status_code = 429
                mock_response.headers = {'content-type': 'application/json'}
                mock_response.content = b'Rate limited'
                mock_response.text = 'Rate limited'
                return mock_response
            # Success on second attempt
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.text = json.dumps(RETRY_TEST_SUCCESS_RESPONSE)
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_429_then_success), \
             patch('geo_lib.reverse_geocoding.overpass_api.time.sleep', side_effect=mock_sleep), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have tried twice (initial + 1 retry)
        assert call_count[0] == 2
        # Should have slept once with 60s wait for rate limit
        assert len(sleep_times) == 1
        assert sleep_times[0] == 60
        # Should return successful result
        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None

    def test_gateway_timeout_504_handling(self):
        """Test 504 gateway timeout with 10s wait time."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        sleep_times = []
        
        def mock_sleep(seconds):
            """Track sleep times."""
            sleep_times.append(seconds)
        
        def mock_post_504_then_success(*args, **kwargs):
            """Mock requests.post to return 504 on first attempt, succeed on second."""
            call_count[0] += 1
            if call_count[0] == 1:
                mock_response = MagicMock()
                mock_response.status_code = 504
                mock_response.headers = {'content-type': 'application/json'}
                mock_response.content = b'Gateway timeout'
                mock_response.text = 'Gateway timeout'
                return mock_response
            # Success on second attempt
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.text = json.dumps(RETRY_TEST_SUCCESS_RESPONSE)
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_504_then_success), \
             patch('geo_lib.reverse_geocoding.overpass_api.time.sleep', side_effect=mock_sleep), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have tried twice (initial + 1 retry)
        assert call_count[0] == 2
        # Should have slept once with 10s wait for gateway timeout
        assert len(sleep_times) == 1
        assert sleep_times[0] == 10
        # Should return successful result
        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None

    def test_http_500_retry_with_exponential_backoff(self):
        """Test other HTTP errors (500, 503, etc.) with exponential backoff."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        sleep_times = []
        
        def mock_sleep(seconds):
            """Track sleep times."""
            sleep_times.append(seconds)
        
        def mock_post_500_then_success(*args, **kwargs):
            """Mock requests.post to return 500 on first two attempts, succeed on third."""
            call_count[0] += 1
            if call_count[0] <= 2:
                mock_response = MagicMock()
                mock_response.status_code = 500
                mock_response.headers = {'content-type': 'application/json'}
                mock_response.content = b'Internal server error'
                mock_response.text = 'Internal server error'
                return mock_response
            # Success on third attempt
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_500_then_success), \
             patch('geo_lib.reverse_geocoding.overpass_api.time.sleep', side_effect=mock_sleep), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have tried 3 times (initial + 2 retries)
        assert call_count[0] == 3
        # Should have slept twice with exponential backoff
        assert len(sleep_times) == 2
        # First sleep: 10 * (2^0) = 10s
        assert sleep_times[0] == 10
        # Second sleep: 10 * (2^1) = 20s
        assert sleep_times[1] == 20
        # Should return successful result
        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None

    def test_http_error_max_retries_exceeded(self):
        """Test that exceeding max retries for HTTP errors returns (None, error_message)."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        
        def mock_post_always_500(*args, **kwargs):
            """Mock requests.post to always return 500."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 500
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = b'Internal server error'
            mock_response.text = 'Internal server error'
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_always_500), \
             patch('geo_lib.reverse_geocoding.overpass_api.time.sleep'), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have tried 3 times (max_retries = 3)
        assert call_count[0] == 3
        # Should return (None, error_message) after all retries exhausted
        assert result is None
        assert error is not None
        assert "500" in error or "HTTP" in error

    def test_empty_response_retry(self):
        """Test retry logic when status 200 but response is empty."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        sleep_times = []
        
        def mock_sleep(seconds):
            """Track sleep times."""
            sleep_times.append(seconds)
        
        def mock_post_empty_then_success(*args, **kwargs):
            """Mock requests.post to return empty response on first attempt, succeed on second."""
            call_count[0] += 1
            if call_count[0] == 1:
                mock_response = MagicMock()
                mock_response.status_code = 200
                mock_response.headers = {'content-type': 'application/json'}
                mock_response.content = b''  # Empty response
                mock_response.text = ''
                return mock_response
            # Success on second attempt
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.text = json.dumps(RETRY_TEST_SUCCESS_RESPONSE)
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_empty_then_success), \
             patch('geo_lib.reverse_geocoding.overpass_api.time.sleep', side_effect=mock_sleep), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have tried twice (initial + 1 retry)
        assert call_count[0] == 2
        # Should have slept once with 10s wait
        assert len(sleep_times) == 1
        assert sleep_times[0] == 10
        # Should return successful result
        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None

    def test_html_xml_error_page_retry(self):
        """Test retry when status 200 but content-type is HTML/XML."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        sleep_times = []
        
        def mock_sleep(seconds):
            """Track sleep times."""
            sleep_times.append(seconds)
        
        def mock_post_html_then_success(*args, **kwargs):
            """Mock requests.post to return HTML on first attempt, succeed on second."""
            call_count[0] += 1
            if call_count[0] == 1:
                mock_response = MagicMock()
                mock_response.status_code = 200
                mock_response.headers = {'content-type': 'text/html'}
                mock_response.content = b'<html><body>Error</body></html>'
                mock_response.text = '<html><body>Error</body></html>'
                return mock_response
            # Success on second attempt
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.text = json.dumps(RETRY_TEST_SUCCESS_RESPONSE)
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_html_then_success), \
             patch('geo_lib.reverse_geocoding.overpass_api.time.sleep', side_effect=mock_sleep), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have tried twice (initial + 1 retry)
        assert call_count[0] == 2
        # Should have slept once with 10s wait
        assert len(sleep_times) == 1
        assert sleep_times[0] == 10
        # Should return successful result
        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None

    def test_invalid_json_no_retry(self):
        """Test that invalid JSON returns immediately (no retry) with proper error message."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        
        def mock_post_invalid_json(*args, **kwargs):
            """Mock requests.post to return invalid JSON."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = b'{"invalid": json}'  # Invalid JSON
            mock_response.text = '{"invalid": json}'
            # Make json() raise JSONDecodeError
            mock_response.json.side_effect = json.JSONDecodeError("Expecting value", "", 0)
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_invalid_json), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should only call once (no retries for invalid JSON)
        assert call_count[0] == 1
        # Should return (None, error_message) immediately
        assert result is None
        assert error is not None
        assert "invalid JSON" in error.lower() or "json" in error.lower()

    def test_successful_request_no_retries(self):
        """Test that successful requests don't trigger retries."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        
        def mock_post_success(*args, **kwargs):
            """Mock requests.post to succeed immediately."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_success), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should only call once (no retries needed)
        assert call_count[0] == 1
        # Should return successful result
        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None

    def test_successful_request_caching(self):
        """Test that successful responses with elements are cached."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        
        def mock_post_success(*args, **kwargs):
            """Mock requests.post to succeed immediately."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_success), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            mock_cache.get.return_value = None  # Cache miss
            mock_cache.set = Mock()  # Track cache.set calls
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have called API once
        assert call_count[0] == 1
        # Should have cached the response (has elements)
        assert mock_cache.set.called
        # Should return successful result
        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None

    def test_empty_elements_not_cached(self):
        """Test that responses with empty elements array are not cached."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        empty_response = {"elements": []}
        
        def mock_post_empty(*args, **kwargs):
            """Mock requests.post to return empty elements."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(empty_response).encode('utf-8')
            mock_response.text = json.dumps(empty_response)
            mock_response.json.return_value = empty_response
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_empty), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            mock_cache.get.return_value = None  # Cache miss
            mock_cache.set = Mock()  # Track cache.set calls
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have called API once
        assert call_count[0] == 1
        # Should NOT have cached the response (empty elements)
        assert not mock_cache.set.called
        # Should return empty response
        assert result == empty_response
        assert error is None

    def test_connection_error_no_retry(self):
        """Test that ConnectionError exceptions return immediately (no retry)."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        
        def mock_post_connection_error(*args, **kwargs):
            """Mock requests.post to raise ConnectionError."""
            call_count[0] += 1
            raise requests.exceptions.ConnectionError("Connection failed")
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_connection_error), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should only call once (no retries for ConnectionError)
        assert call_count[0] == 1
        # Should return (None, error_message) immediately
        assert result is None
        assert error is not None
        assert "failed" in error.lower() or "connection" in error.lower()

    def test_general_exception_no_retry(self):
        """Test that general exceptions return immediately (no retry)."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        
        def mock_post_general_error(*args, **kwargs):
            """Mock requests.post to raise general exception."""
            call_count[0] += 1
            raise ValueError("Unexpected error")
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_general_error), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should only call once (no retries for general exceptions)
        assert call_count[0] == 1
        # Should return (None, error_message) immediately
        assert result is None
        assert error is not None
        assert "failed" in error.lower() or "error" in error.lower()

    def test_cache_hit_no_api_call(self):
        """Test that cached responses bypass API calls entirely."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        
        def mock_post(*args, **kwargs):
            """Mock requests.post - should not be called."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            # Cache hit - return cached response
            mock_cache.get.return_value = RETRY_TEST_SUCCESS_RESPONSE
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should NOT have called API (cache hit)
        assert call_count[0] == 0
        # Should return cached result
        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None

    def test_cache_not_used_on_failure(self):
        """Test that failed responses are not cached."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        
        call_count = [0]
        
        def mock_post_always_500(*args, **kwargs):
            """Mock requests.post to always return 500."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 500
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = b'Internal server error'
            mock_response.text = 'Internal server error'
            return mock_response
        
        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_always_500), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('django.conf.settings') as mock_settings:
            mock_cache.get.return_value = None  # Cache miss
            mock_cache.set = Mock()  # Track cache.set calls
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True
            
            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)
        
        # Should have tried 3 times (max retries)
        assert call_count[0] == 3
        # Should NOT have cached the failed response
        assert not mock_cache.set.called
        # Should return (None, error_message)
        assert result is None
        assert error is not None

    def test_verify_ssl_true_passed_to_requests(self):
        """Test that OVERPASS_API_VERIFY_SSL=True is passed as verify=True to requests.post."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        post_kwargs = []

        def mock_post_success(*args, **kwargs):
            post_kwargs.append(kwargs)
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response

        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_success), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('geo_lib.reverse_geocoding.overpass_api.settings') as mock_settings:
            mock_cache.get.return_value = None
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = True

            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)

        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None
        assert len(post_kwargs) == 1
        assert post_kwargs[0].get('verify') is True

    def test_verify_ssl_false_passed_to_requests(self):
        """Test that OVERPASS_API_VERIFY_SSL=False is passed as verify=False to requests.post."""
        query = "[out:json];node(around:1000,37.7749,-122.4194);out;"
        post_kwargs = []

        def mock_post_success(*args, **kwargs):
            post_kwargs.append(kwargs)
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.headers = {'content-type': 'application/json'}
            mock_response.content = json.dumps(RETRY_TEST_SUCCESS_RESPONSE).encode('utf-8')
            mock_response.json.return_value = RETRY_TEST_SUCCESS_RESPONSE
            return mock_response

        with patch('geo_lib.reverse_geocoding.overpass_api.requests.post', side_effect=mock_post_success), \
             patch('geo_lib.reverse_geocoding.overpass_api._REVERSE_GEOCODING_CACHE') as mock_cache, \
             patch('geo_lib.reverse_geocoding.overpass_api.settings') as mock_settings:
            mock_cache.get.return_value = None
            mock_settings.OVERPASS_API_URL = "https://overpass.private.coffee/api/interpreter"
            mock_settings.OVERPASS_API_TIMEOUT = 30
            mock_settings.OVERPASS_API_VERIFY_SSL = False

            result, error = query_overpass(query, latitude=37.7749, longitude=-122.4194)

        assert result == RETRY_TEST_SUCCESS_RESPONSE
        assert error is None
        assert len(post_kwargs) == 1
        assert post_kwargs[0].get('verify') is False
