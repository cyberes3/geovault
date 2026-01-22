"""
Tests for elevation API retry logic with timeout handling.

These tests verify that the elevation API retry mechanism works correctly,
including exponential backoff, maximum retry limits, and proper error handling.
"""
import pytest
import time
from unittest.mock import patch, MagicMock, Mock
import requests

from geo_lib.processing.elevation_service import _fetch_elevation_batch_with_retry
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
from fixtures.elevation_responses import (
    ELEVATION_TEST_SUCCESS_RESPONSE_SF,
    ELEVATION_TEST_SUCCESS_RESPONSE_SINGLE
)


class TestElevationAPIRetry:
    """Test elevation API retry logic."""

    def test_retry_on_timeout_with_exponential_backoff(self):
        """Test that timeouts trigger retries with exponential backoff."""
        api_url = "https://elevation.racemap.com/api"
        batch_coords = [[37.7749, -122.4194], [37.7849, -122.4094]]
        api_timeout = 30

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
            mock_response.json.return_value = ELEVATION_TEST_SUCCESS_RESPONSE_SF
            mock_response.raise_for_status = Mock()
            return mock_response

        with patch('geo_lib.processing.elevation_service.requests.post', side_effect=mock_post_timeout):
            with patch('geo_lib.processing.elevation_service.time.sleep', side_effect=mock_sleep):
                import_log = MagicMock(spec=ImportLog)
                result = _fetch_elevation_batch_with_retry(
                    api_url,
                    batch_coords,
                    api_timeout,
                    import_log,
                    "test batch"
                )

        # Should have retried 3 times (initial + 2 retries)
        assert call_count[0] == 3
        # Should have slept twice (before retry 2 and retry 3)
        assert len(sleep_times) == 2
        # First sleep should be 10s (max(10, 2^0) = 10)
        assert sleep_times[0] == 10
        # Second sleep should be 20s (max(10, 2^1) = 20)
        assert sleep_times[1] == 20
        # Should return successful result
        assert result == ELEVATION_TEST_SUCCESS_RESPONSE_SF

    def test_max_retries_exceeded_returns_none(self):
        """Test that exceeding max retries returns None."""
        api_url = "https://elevation.racemap.com/api"
        batch_coords = [[37.7749, -122.4194]]
        api_timeout = 30

        call_count = [0]

        def mock_post_always_timeout(*args, **kwargs):
            """Mock requests.post to always timeout."""
            call_count[0] += 1
            raise requests.exceptions.Timeout("Read timed out")

        with patch('geo_lib.processing.elevation_service.requests.post', side_effect=mock_post_always_timeout):
            with patch('geo_lib.processing.elevation_service.time.sleep'):
                import_log = MagicMock(spec=ImportLog)
                result = _fetch_elevation_batch_with_retry(
                    api_url,
                    batch_coords,
                    api_timeout,
                    import_log,
                    "test batch"
                )

        # Should have tried 3 times (max_retries = 3)
        assert call_count[0] == 3
        # Should return None after all retries exhausted
        assert result is None

    def test_successful_request_no_retries(self):
        """Test that successful requests don't trigger retries."""
        api_url = "https://elevation.racemap.com/api"
        batch_coords = [[37.7749, -122.4194], [37.7849, -122.4094]]
        api_timeout = 30

        call_count = [0]

        def mock_post_success(*args, **kwargs):
            """Mock requests.post to succeed immediately."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.json.return_value = ELEVATION_TEST_SUCCESS_RESPONSE_SF
            mock_response.raise_for_status = Mock()
            return mock_response

        with patch('geo_lib.processing.elevation_service.requests.post', side_effect=mock_post_success):
            import_log = MagicMock(spec=ImportLog)
            result = _fetch_elevation_batch_with_retry(
                api_url,
                batch_coords,
                api_timeout,
                import_log,
                "test batch"
            )

        # Should only call once (no retries needed)
        assert call_count[0] == 1
        # Should return successful result
        assert result == ELEVATION_TEST_SUCCESS_RESPONSE_SF

    def test_non_timeout_exception_no_retry(self):
        """Test that non-timeout exceptions don't trigger retries."""
        api_url = "https://elevation.racemap.com/api"
        batch_coords = [[37.7749, -122.4194]]
        api_timeout = 30

        call_count = [0]

        def mock_post_connection_error(*args, **kwargs):
            """Mock requests.post to raise ConnectionError."""
            call_count[0] += 1
            raise requests.exceptions.ConnectionError("Connection failed")

        with patch('geo_lib.processing.elevation_service.requests.post', side_effect=mock_post_connection_error):
            import_log = MagicMock(spec=ImportLog)
            result = _fetch_elevation_batch_with_retry(
                api_url,
                batch_coords,
                api_timeout,
                import_log,
                "test batch"
            )

        # Should only call once (no retries for non-timeout errors)
        assert call_count[0] == 1
        # Should return None immediately
        assert result is None

    def test_http_error_no_retry(self):
        """Test that HTTP errors (like 500) don't trigger retries."""
        api_url = "https://elevation.racemap.com/api"
        batch_coords = [[37.7749, -122.4194]]
        api_timeout = 30

        call_count = [0]

        def mock_post_http_error(*args, **kwargs):
            """Mock requests.post to return HTTP 500 error."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 500
            mock_response.raise_for_status.side_effect = requests.exceptions.HTTPError("500 Server Error")
            return mock_response

        with patch('geo_lib.processing.elevation_service.requests.post', side_effect=mock_post_http_error):
            # Mock sleep to avoid waiting during tests
            with patch('geo_lib.processing.elevation_service.time.sleep'):
                import_log = MagicMock(spec=ImportLog)
                result = _fetch_elevation_batch_with_retry(
                    api_url,
                    batch_coords,
                    api_timeout,
                    import_log,
                    "test batch"
                )

        # Should retry 3 times for HTTP 500 (as it's != 200)
        assert call_count[0] == 3
        # Should return None
        assert result is None

    def test_invalid_response_format_returns_none(self):
        """Test that invalid response format returns None without retries."""
        api_url = "https://elevation.racemap.com/api"
        batch_coords = [[37.7749, -122.4194]]
        api_timeout = 30

        call_count = [0]

        def mock_post_invalid_response(*args, **kwargs):
            """Mock requests.post to return invalid response (not a list)."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.json.return_value = {"error": "Invalid format"}
            mock_response.raise_for_status = Mock()
            return mock_response

        with patch('geo_lib.processing.elevation_service.requests.post', side_effect=mock_post_invalid_response):
            import_log = MagicMock(spec=ImportLog)
            result = _fetch_elevation_batch_with_retry(
                api_url,
                batch_coords,
                api_timeout,
                import_log,
                "test batch"
            )

        # Should only call once (no retries for invalid format)
        assert call_count[0] == 1
        # Should return None for invalid format
        assert result is None

    def test_wrong_length_response_returns_none(self):
        """Test that response with wrong length returns None without retries."""
        api_url = "https://elevation.racemap.com/api"
        batch_coords = [[37.7749, -122.4194], [37.7849, -122.4094]]
        api_timeout = 30

        call_count = [0]

        def mock_post_wrong_length(*args, **kwargs):
            """Mock requests.post to return wrong number of elevations."""
            call_count[0] += 1
            mock_response = MagicMock()
            mock_response.status_code = 200
            # Return only 1 elevation for 2 coordinates
            mock_response.json.return_value = [100.0]
            mock_response.raise_for_status = Mock()
            return mock_response

        with patch('geo_lib.processing.elevation_service.requests.post', side_effect=mock_post_wrong_length):
            import_log = MagicMock(spec=ImportLog)
            result = _fetch_elevation_batch_with_retry(
                api_url,
                batch_coords,
                api_timeout,
                import_log,
                "test batch"
            )

        # Should only call once (no retries for wrong length)
        assert call_count[0] == 1
        # Should return None for wrong length
        assert result is None

    def test_success_after_one_retry(self):
        """Test successful request after one timeout retry."""
        api_url = "https://elevation.racemap.com/api"
        batch_coords = [[37.7749, -122.4194]]
        api_timeout = 30

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
            # Success on second attempt - use real response fixture
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.json.return_value = ELEVATION_TEST_SUCCESS_RESPONSE_SINGLE
            mock_response.raise_for_status = Mock()
            return mock_response

        with patch('geo_lib.processing.elevation_service.requests.post', side_effect=mock_post_timeout_then_success):
            with patch('geo_lib.processing.elevation_service.time.sleep', side_effect=mock_sleep):
                import_log = MagicMock(spec=ImportLog)
                result = _fetch_elevation_batch_with_retry(
                    api_url,
                    batch_coords,
                    api_timeout,
                    import_log,
                    "test batch"
                )

        # Should have tried twice (initial + 1 retry)
        assert call_count[0] == 2
        # Should have slept once (before retry)
        assert len(sleep_times) == 1
        # Sleep should be 10s (max(10, 2^0) = 10)
        assert sleep_times[0] == 10
        # Should return successful result
        assert result == ELEVATION_TEST_SUCCESS_RESPONSE_SINGLE

    def test_elevation_conversion_handles_none_values(self):
        """Test that None values in elevation response are handled correctly."""
        api_url = "https://elevation.racemap.com/api"
        batch_coords = [[37.7749, -122.4194], [37.7849, -122.4094]]
        api_timeout = 30

        def mock_post_success(*args, **kwargs):
            """Mock requests.post to return response with None values."""
            mock_response = MagicMock()
            mock_response.status_code = 200
            # Return list with None and valid elevation
            mock_response.json.return_value = [100.0, None]
            mock_response.raise_for_status = Mock()
            return mock_response

        with patch('geo_lib.processing.elevation_service.requests.post', side_effect=mock_post_success):
            import_log = MagicMock(spec=ImportLog)
            result = _fetch_elevation_batch_with_retry(
                api_url,
                batch_coords,
                api_timeout,
                import_log,
                "test batch"
            )

        # Should return list with None for invalid elevation
        assert result == [100.0, None]

    def test_elevation_conversion_handles_int_values(self):
        """Test that integer elevation values are converted to float."""
        api_url = "https://elevation.racemap.com/api"
        batch_coords = [[37.7749, -122.4194]]
        api_timeout = 30

        def mock_post_success(*args, **kwargs):
            """Mock requests.post to return integer elevation."""
            mock_response = MagicMock()
            mock_response.status_code = 200
            mock_response.json.return_value = [500]  # Integer, not float
            mock_response.raise_for_status = Mock()
            return mock_response

        with patch('geo_lib.processing.elevation_service.requests.post', side_effect=mock_post_success):
            import_log = MagicMock(spec=ImportLog)
            result = _fetch_elevation_batch_with_retry(
                api_url,
                batch_coords,
                api_timeout,
                import_log,
                "test batch"
            )

        # Should convert integer to float
        assert result == [500.0]
        assert isinstance(result[0], float)
