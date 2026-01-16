"""
Tests for icon processing logging behavior.
Verifies that errors and warnings are properly passed to import_log.
"""
from unittest.mock import Mock, patch, MagicMock
from urllib.error import HTTPError, URLError
import pytest

from geo_lib.processing.icons.icon_manager import (
    store_icon,
    process_icon_href,
    process_geojson_icons,
    _process_single_icon_href,
    _process_properties_icons
)
from geo_lib.processing.icons.get import extract_icon_from_kmz, fetch_remote_icon
from geo_lib.processing.logging import ImportLog, DatabaseLogLevel


class TestIconLoggingBehavior:
    """Test that icon processing errors and warnings are logged to import_log."""

    def test_store_icon_size_limit_logs_warning(self):
        """Test that icon size limit exceeded is logged to import_log."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        # Create icon data that exceeds size limit
        large_icon_data = b'x' * (10 * 1024 * 1024)  # 10MB
        
        with patch('geo_lib.processing.icons.icon_manager.settings') as mock_settings:
            mock_settings.ICON_MAX_SIZE_BYTES = 1024 * 1024  # 1MB limit
            
            result = store_icon(large_icon_data, 'test.png', import_log, stats)
        
        assert result is None
        assert stats['failed'] == 1
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('exceeds size limit' in log.msg.lower() for log in logs)
        assert any(log.level == DatabaseLogLevel.WARNING for log in logs)

    def test_store_icon_invalid_extension_logs_warning(self):
        """Test that invalid icon extension is logged to import_log."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        icon_data = b'fake_image_data'
        
        result = store_icon(icon_data, 'test.txt', import_log, stats)
        
        assert result is None
        assert stats['failed'] == 1
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('invalid icon extension' in log.msg.lower() for log in logs)
        assert any(log.level == DatabaseLogLevel.WARNING for log in logs)

    def test_store_icon_storage_failure_logs_error(self):
        """Test that storage failure is logged to import_log."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        icon_data = b'\x89PNG\r\n\x1a\n' + b'x' * 100  # Minimal PNG header
        
        with patch('geo_lib.processing.icons.icon_manager._get_icon_extension') as mock_ext:
            mock_ext.return_value = '.png'
            with patch('geo_lib.processing.icons.icon_manager._get_storage_path') as mock_path:
                mock_storage_path = Mock()
                mock_storage_path.exists.return_value = False
                mock_storage_path.write_bytes.side_effect = Exception("Disk full")
                mock_path.return_value = mock_storage_path
                
                result = store_icon(icon_data, 'test.png', import_log, stats)
        
        assert result is None
        assert stats['failed'] == 1
        
        # Check that error was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('failed to store icon' in log.msg.lower() for log in logs)
        assert any(log.level == DatabaseLogLevel.ERROR for log in logs)

    def test_store_icon_success_increments_stats(self):
        """Test that successful icon storage increments successful count."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        icon_data = b'\x89PNG\r\n\x1a\n' + b'x' * 100
        
        with patch('geo_lib.processing.icons.icon_manager._get_icon_extension') as mock_ext:
            mock_ext.return_value = '.png'
            with patch('geo_lib.processing.icons.icon_manager._get_storage_path') as mock_path:
                mock_storage_path = Mock()
                mock_storage_path.exists.return_value = False
                mock_path.return_value = mock_storage_path
                
                with patch('geo_lib.processing.icons.icon_manager.hashlib') as mock_hashlib:
                    mock_hash = Mock()
                    mock_hash.hexdigest.return_value = 'abc123'
                    mock_hashlib.sha256.return_value = mock_hash
                    
                    result = store_icon(icon_data, 'test.png', import_log, stats)
        
        assert result == '/api/icons/user/abc123.png'
        assert stats['successful'] == 1
        assert stats['failed'] == 0

    def test_extract_icon_from_kmz_not_found_logs_warning(self):
        """Test that icon not found in KMZ logs warning."""
        import_log = ImportLog()
        
        # Empty KMZ data (minimal valid ZIP)
        kmz_data = b'PK\x05\x06' + b'\x00' * 18
        
        result = extract_icon_from_kmz(kmz_data, 'missing_icon.png', import_log)
        
        assert result is None
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('not found in kmz' in log.msg.lower() for log in logs)
        assert any(log.level == DatabaseLogLevel.WARNING for log in logs)

    def test_extract_icon_from_kmz_bad_zip_logs_warning(self):
        """Test that bad ZIP file logs warning."""
        import_log = ImportLog()
        
        # Invalid ZIP data
        kmz_data = b'not a zip file'
        
        result = extract_icon_from_kmz(kmz_data, 'icon.png', import_log)
        
        assert result is None
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('invalid kmz archive' in log.msg.lower() for log in logs)
        assert any(log.level == DatabaseLogLevel.WARNING for log in logs)

    def test_fetch_remote_icon_size_limit_logs_warning(self):
        """Test that remote icon size limit logs warning."""
        import_log = ImportLog()
        
        with patch('geo_lib.processing.icons.get.urlopen') as mock_urlopen:
            mock_response = MagicMock()
            mock_response.__enter__.return_value = mock_response
            mock_response.headers.get.return_value = '10485760'  # 10MB
            mock_urlopen.return_value = mock_response
            
            with patch('geo_lib.processing.icons.get.settings') as mock_settings:
                mock_settings.ICON_MAX_SIZE_BYTES = 1024 * 1024  # 1MB limit
                
                result = fetch_remote_icon('http://example.com/icon.png', 5.0, import_log)
        
        assert result is None
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('exceeds size limit' in log.msg.lower() for log in logs)
        assert any(log.level == DatabaseLogLevel.WARNING for log in logs)

    def test_fetch_remote_icon_http_error_logs_warning(self):
        """Test that HTTP error logs warning."""
        import_log = ImportLog()
        
        with patch('geo_lib.processing.icons.get.urlopen') as mock_urlopen:
            mock_urlopen.side_effect = HTTPError('http://example.com/icon.png', 404, 'Not Found', {}, None)
            
            result = fetch_remote_icon('http://example.com/icon.png', 5.0, import_log)
        
        assert result is None
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('http error' in log.msg.lower() for log in logs)
        assert any(log.level == DatabaseLogLevel.WARNING for log in logs)

    def test_fetch_remote_icon_url_error_logs_warning(self):
        """Test that URL error logs warning."""
        import_log = ImportLog()
        
        with patch('geo_lib.processing.icons.get.urlopen') as mock_urlopen:
            mock_urlopen.side_effect = URLError('Connection refused')
            
            result = fetch_remote_icon('http://example.com/icon.png', 5.0, import_log)
        
        assert result is None
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('url error' in log.msg.lower() for log in logs)
        assert any(log.level == DatabaseLogLevel.WARNING for log in logs)

    def test_fetch_remote_icon_timeout_logs_warning(self):
        """Test that timeout logs warning."""
        import_log = ImportLog()
        
        with patch('geo_lib.processing.icons.get.urlopen') as mock_urlopen:
            mock_urlopen.side_effect = TimeoutError('Connection timed out')
            
            result = fetch_remote_icon('http://example.com/icon.png', 5.0, import_log)
        
        assert result is None
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('timeout' in log.msg.lower() for log in logs)
        assert any(log.level == DatabaseLogLevel.WARNING for log in logs)

    def test_process_single_icon_href_caltopo_extracts_color(self):
        """Test that CalTopo icons extract color correctly."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        # CalTopo point icon
        href = 'http://caltopo.com/icon.png?cfg=point%2CFF0000'
        
        new_href, color = _process_single_icon_href(href, 'kml', import_log, stats)
        
        assert new_href is None  # Point icons not fetched
        assert color == '#FF0000'

    def test_process_single_icon_href_caltopo_failure_logs_warning(self):
        """Test that CalTopo icon fetch failure logs warning."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        # CalTopo non-point icon with color
        href = 'http://caltopo.com/icon.png?cfg=campfire%2CFF0000'
        
        # Mock the actual processing functions that would be called
        with patch('geo_lib.processing.icons.icon_manager.extract_icon_from_kmz') as mock_extract:
            mock_extract.return_value = None
            with patch('geo_lib.processing.icons.icon_manager.fetch_remote_icon') as mock_fetch:
                mock_fetch.return_value = None  # Fetch fails
                
                new_href, color = _process_single_icon_href(href, 'kmz', import_log, stats, file_data=b'fake')
        
        assert new_href is None
        assert color == '#FF0000'  # Still have color from URL
        assert stats['failed'] == 1
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('failed to load icon' in log.msg.lower() for log in logs)
        assert any('extracted color' in log.msg.lower() for log in logs)

    def test_process_single_icon_href_non_caltopo_failure_logs_warning(self):
        """Test that non-CalTopo icon failure logs warning."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        href = 'http://example.com/icon.png'
        
        # Mock the actual fetch function that would be called
        with patch('geo_lib.processing.icons.icon_manager.fetch_remote_icon') as mock_fetch:
            mock_fetch.return_value = None  # Fetch fails
            
            new_href, color = _process_single_icon_href(href, 'kml', import_log, stats, file_data=None)
        
        assert new_href is None
        assert color is None
        assert stats['failed'] == 1
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('failed to load icon' in log.msg.lower() for log in logs)
        assert any('default red icon' in log.msg.lower() for log in logs)

    def test_process_properties_icons_caltopo_extraction_failure_logs_warning(self):
        """Test that CalTopo extraction failure logs warning."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        properties = {
            'icon': 'http://caltopo.com/icon.png?cfg=invalid'
        }
        
        # Let it run naturally - the CalTopo detection should work
        with patch('geo_lib.processing.icons.icon_manager.fetch_remote_icon') as mock_fetch:
            mock_fetch.return_value = None  # Fetch fails
            
            _process_properties_icons(
                properties, 'kml', import_log, stats, 
                file_data=None, href_mapping=None, is_point=True
            )
        
        # Should count as failed (caltopo detected but not a valid icon type)
        assert stats['failed'] >= 1
        
        # Check that warning was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('caltopo url detected' in log.msg.lower() and 'color extraction failed' in log.msg.lower() for log in logs)

    def test_process_geojson_icons_logs_summary(self):
        """Test that process_geojson_icons logs summary statistics."""
        import_log = ImportLog()
        
        geojson_data = {
            'type': 'FeatureCollection',
            'features': [
                {
                    'type': 'Feature',
                    'geometry': {'type': 'Point', 'coordinates': [0, 0]},
                    'properties': {
                        'name': 'CalTopo Point',
                        'icon': 'http://caltopo.com/icon.png?cfg=point%2CFF0000'
                    }
                },
                {
                    'type': 'Feature',
                    'geometry': {'type': 'Point', 'coordinates': [1, 1]},
                    'properties': {
                        'name': 'Regular Icon',
                        'icon': 'http://example.com/icon.png'
                    }
                }
            ]
        }
        
        with patch('geo_lib.processing.icons.icon_manager.settings') as mock_settings:
            mock_settings.ICON_PROCESSING_ENABLED = True
            
            with patch('geo_lib.processing.icons.icon_manager.process_icon_href') as mock_fetch:
                mock_fetch.return_value = '/api/icons/user/abc123.png'
                
                result = process_geojson_icons(geojson_data, 'kml', import_log)
        
        # Check that summary was logged
        logs = import_log.get()
        assert len(logs) > 0
        assert any('icon processing complete' in log.msg.lower() for log in logs)
        # Should mention counts: successful, failed
        summary_logs = [log for log in logs if 'icon processing complete' in log.msg.lower()]
        assert len(summary_logs) == 1
        summary_msg = summary_logs[0].msg.lower()
        assert 'successfully extracted' in summary_msg
        assert 'failed' in summary_msg

    def test_process_geojson_icons_no_icons_no_log(self):
        """Test that no summary is logged when no icons are processed."""
        import_log = ImportLog()
        
        geojson_data = {
            'type': 'FeatureCollection',
            'features': [
                {
                    'type': 'Feature',
                    'geometry': {'type': 'Point', 'coordinates': [0, 0]},
                    'properties': {'name': 'No Icon'}
                }
            ]
        }
        
        with patch('geo_lib.processing.icons.icon_manager.settings') as mock_settings:
            mock_settings.ICON_PROCESSING_ENABLED = True
            
            result = process_geojson_icons(geojson_data, 'kml', import_log)
        
        # Check that no summary was logged (no icons processed)
        logs = import_log.get()
        assert not any('icon processing complete' in log.msg.lower() for log in logs)

    def test_all_error_sources_use_import_log(self):
        """Integration test: verify all error sources use import_log."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        # Test various error scenarios
        scenarios = [
            # Size limit
            (lambda: store_icon(b'x' * 20000000, 'test.png', import_log, stats), 'size limit'),
            # Invalid extension
            (lambda: store_icon(b'data', 'test.txt', import_log, stats), 'extension'),
        ]
        
        initial_log_count = len(import_log.get())
        
        for scenario_func, expected_keyword in scenarios:
            before_count = len(import_log.get())
            scenario_func()
            after_count = len(import_log.get())
            
            # Each scenario should add at least one log entry
            assert after_count > before_count, f"No log added for scenario: {expected_keyword}"
            
            # Check that the expected keyword is in recent logs
            recent_logs = import_log.get()[before_count:]
            assert any(expected_keyword in log.msg.lower() for log in recent_logs), \
                f"Expected keyword '{expected_keyword}' not found in logs"


class TestIconStatisticsTracking:
    """Test that icon statistics are tracked correctly."""

    def test_stats_tracking_successful(self):
        """Test that successful icon storage increments correct counter."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        icon_data = b'\x89PNG\r\n\x1a\n' + b'x' * 100
        
        with patch('geo_lib.processing.icons.icon_manager._get_icon_extension') as mock_ext:
            mock_ext.return_value = '.png'
            with patch('geo_lib.processing.icons.icon_manager._get_storage_path') as mock_path:
                mock_storage_path = Mock()
                mock_storage_path.exists.return_value = False
                mock_path.return_value = mock_storage_path
                with patch('geo_lib.processing.icons.icon_manager.hashlib') as mock_hashlib:
                    mock_hash = Mock()
                    mock_hash.hexdigest.return_value = 'abc123'
                    mock_hashlib.sha256.return_value = mock_hash
                    
                    store_icon(icon_data, 'test.png', import_log, stats)
        
        assert stats['successful'] == 1
        assert stats['failed'] == 0

    def test_stats_tracking_failed(self):
        """Test that failed icon storage increments correct counter."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        # Invalid extension
        store_icon(b'data', 'test.txt', import_log, stats)
        
        assert stats['successful'] == 0
        assert stats['failed'] == 1

    def test_stats_tracking_caltopo_point_icon(self):
        """Test that CalTopo point icons don't increment counters (not fetched)."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        href = 'http://caltopo.com/icon.png?cfg=point%2CFF0000'
        
        new_href, color = _process_single_icon_href(href, 'kml', import_log, stats)
        
        assert new_href is None  # Point icons not fetched
        assert color == '#FF0000'
        assert stats['successful'] == 0
        assert stats['failed'] == 0

    def test_stats_no_double_counting(self):
        """Test that failures are not double-counted in the call chain."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        # Non-CalTopo icon that fails
        href = 'http://example.com/icon.png'
        
        # Mock the fetch to fail
        with patch('geo_lib.processing.icons.icon_manager.fetch_remote_icon') as mock_remote:
            mock_remote.return_value = None
            
            _process_single_icon_href(href, 'kml', import_log, stats, file_data=None)
        
        # Should only count failure once, not multiple times
        assert stats['failed'] == 1

    def test_stats_multiple_icons_mixed_results(self):
        """Test stats tracking with multiple icons having different outcomes."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        # Simulate different outcomes directly
        # CalTopo point icon - doesn't increment any counter (not fetched)
        _process_single_icon_href('http://caltopo.com/icon.png?cfg=point%2CFF0000', 'kml', import_log, stats)
        assert stats['successful'] == 0
        assert stats['failed'] == 0
        
        # Successful icon - increments successful
        with patch('geo_lib.processing.icons.icon_manager.fetch_remote_icon') as mock_fetch:
            mock_fetch.return_value = b'icon_data'
            with patch('geo_lib.processing.icons.icon_manager._get_icon_extension') as mock_ext:
                mock_ext.return_value = '.png'
                with patch('geo_lib.processing.icons.icon_manager._get_storage_path') as mock_path:
                    mock_storage = Mock()
                    mock_storage.exists.return_value = False
                    mock_path.return_value = mock_storage
                    with patch('geo_lib.processing.icons.icon_manager.hashlib') as mock_hashlib:
                        mock_hash = Mock()
                        mock_hash.hexdigest.return_value = 'hash1'
                        mock_hashlib.sha256.return_value = mock_hash
                        _process_single_icon_href('http://example.com/icon.png', 'kml', import_log, stats)
        
        assert stats['successful'] == 1
        assert stats['failed'] == 0
        
        # Failed icon - increments failed
        with patch('geo_lib.processing.icons.icon_manager.fetch_remote_icon') as mock_fetch:
            mock_fetch.return_value = None
            _process_single_icon_href('http://example.com/bad_icon.png', 'kml', import_log, stats)
        
        assert stats['successful'] == 1
        assert stats['failed'] == 1
        
        # CalTopo non-point that succeeds - increments successful
        with patch('geo_lib.processing.icons.icon_manager.fetch_remote_icon') as mock_fetch:
            mock_fetch.return_value = b'icon_data2'
            with patch('geo_lib.processing.icons.icon_manager._get_icon_extension') as mock_ext:
                mock_ext.return_value = '.png'
                with patch('geo_lib.processing.icons.icon_manager._get_storage_path') as mock_path:
                    mock_storage = Mock()
                    mock_storage.exists.return_value = False
                    mock_path.return_value = mock_storage
                    with patch('geo_lib.processing.icons.icon_manager.hashlib') as mock_hashlib:
                        mock_hash = Mock()
                        mock_hash.hexdigest.return_value = 'hash2'
                        mock_hashlib.sha256.return_value = mock_hash
                        _process_single_icon_href('http://caltopo.com/icon.png?cfg=campfire%2C00FF00', 'kmz', import_log, stats, file_data=b'fake')
        
        # Final tally: 2 successful, 1 failed
        assert stats['successful'] == 2
        assert stats['failed'] == 1

    def test_stats_summary_format(self):
        """Test that the summary message has correct format."""
        import_log = ImportLog()
        
        geojson_data = {
            'type': 'FeatureCollection',
            'features': [
                {
                    'type': 'Feature',
                    'geometry': {'type': 'Point', 'coordinates': [0, 0]},
                    'properties': {
                        'icon': 'http://caltopo.com/icon.png?cfg=point%2CFF0000'
                    }
                }
            ]
        }
        
        with patch('geo_lib.processing.icons.icon_manager.settings') as mock_settings:
            mock_settings.ICON_PROCESSING_ENABLED = True
            
            process_geojson_icons(geojson_data, 'kml', import_log)
        
        logs = import_log.get()
        summary_logs = [log for log in logs if 'icon processing complete' in log.msg.lower()]
        assert len(summary_logs) == 1
        
        # Verify format: "Icon processing complete: X successfully extracted, Z failed"
        summary = summary_logs[0].msg
        assert 'icon processing complete:' in summary.lower()
        assert 'successfully extracted' in summary.lower()
        assert 'failed' in summary.lower()
        
        # Verify it's an INFO level message
        assert summary_logs[0].level == DatabaseLogLevel.INFO
        assert summary_logs[0].source == "Icon Processing"


class TestFailureMessageContent:
    """Test that failure messages contain useful information."""

    def test_size_limit_message_includes_size(self):
        """Test that size limit messages include the actual size."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        large_data = b'x' * (5 * 1024 * 1024)  # 5MB
        
        with patch('geo_lib.processing.icons.icon_manager.settings') as mock_settings:
            mock_settings.ICON_MAX_SIZE_BYTES = 1024 * 1024  # 1MB
            
            store_icon(large_data, 'large.png', import_log, stats)
        
        logs = import_log.get()
        size_logs = [log for log in logs if 'size limit' in log.msg.lower()]
        assert len(size_logs) > 0
        
        # Should include the actual size in bytes
        assert '5242880' in size_logs[0].msg or '5 mb' in size_logs[0].msg.lower()

    def test_invalid_extension_message_includes_filename(self):
        """Test that invalid extension messages include the filename."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        store_icon(b'data', 'invalid_file.txt', import_log, stats)
        
        logs = import_log.get()
        ext_logs = [log for log in logs if 'invalid icon extension' in log.msg.lower()]
        assert len(ext_logs) > 0
        assert 'invalid_file.txt' in ext_logs[0].msg

    def test_http_error_message_includes_status_code(self):
        """Test that HTTP error messages include the status code."""
        import_log = ImportLog()
        
        with patch('geo_lib.processing.icons.get.urlopen') as mock_urlopen:
            mock_urlopen.side_effect = HTTPError('http://example.com/icon.png', 404, 'Not Found', {}, None)
            
            fetch_remote_icon('http://example.com/icon.png', 5.0, import_log)
        
        logs = import_log.get()
        http_logs = [log for log in logs if 'http error' in log.msg.lower()]
        assert len(http_logs) > 0
        assert '404' in http_logs[0].msg

    def test_http_error_message_includes_url(self):
        """Test that HTTP error messages include the URL."""
        import_log = ImportLog()
        
        url = 'http://example.com/missing_icon.png'
        with patch('geo_lib.processing.icons.get.urlopen') as mock_urlopen:
            mock_urlopen.side_effect = HTTPError(url, 500, 'Server Error', {}, None)
            
            fetch_remote_icon(url, 5.0, import_log)
        
        logs = import_log.get()
        http_logs = [log for log in logs if 'http error' in log.msg.lower()]
        assert len(http_logs) > 0
        assert url in http_logs[0].msg

    def test_timeout_message_includes_url(self):
        """Test that timeout messages include the URL."""
        import_log = ImportLog()
        
        url = 'http://slow.example.com/icon.png'
        
        with patch('geo_lib.processing.icons.get.urlopen') as mock_urlopen:
            mock_urlopen.side_effect = TimeoutError('Connection timed out')
            
            fetch_remote_icon(url, 5.0, import_log)
        
        logs = import_log.get()
        timeout_logs = [log for log in logs if 'timeout' in log.msg.lower()]
        assert len(timeout_logs) > 0
        assert url in timeout_logs[0].msg

    def test_kmz_extraction_failure_includes_path(self):
        """Test that KMZ extraction failures include the icon path."""
        import_log = ImportLog()
        
        icon_path = 'files/custom_icon.png'
        kmz_data = b'PK\x05\x06' + b'\x00' * 18  # Minimal ZIP
        
        extract_icon_from_kmz(kmz_data, icon_path, import_log)
        
        logs = import_log.get()
        kmz_logs = [log for log in logs if 'not found in kmz' in log.msg.lower()]
        assert len(kmz_logs) > 0
        assert icon_path in kmz_logs[0].msg

    def test_caltopo_failure_with_color_fallback_message(self):
        """Test that CalTopo failures mention color fallback."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        href = 'http://caltopo.com/icon.png?cfg=campfire%2CFF0000'
        
        with patch('geo_lib.processing.icons.icon_manager.fetch_remote_icon') as mock_fetch:
            mock_fetch.return_value = None
            
            new_href, color = _process_single_icon_href(href, 'kml', import_log, stats, file_data=None)
        
        logs = import_log.get()
        caltopo_logs = [log for log in logs if 'failed to load icon' in log.msg.lower()]
        assert len(caltopo_logs) > 0
        # Should mention both failure and color extraction
        assert 'extracted color' in caltopo_logs[0].msg.lower()
        assert '#ff0000' in caltopo_logs[0].msg.lower()

    def test_non_caltopo_failure_mentions_default(self):
        """Test that non-CalTopo failures mention default icon."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        href = 'http://example.com/icon.png'
        
        with patch('geo_lib.processing.icons.icon_manager.fetch_remote_icon') as mock_fetch:
            mock_fetch.return_value = None
            
            new_href, color = _process_single_icon_href(href, 'kml', import_log, stats, file_data=None)
        
        logs = import_log.get()
        failure_logs = [log for log in logs if 'failed to load icon' in log.msg.lower()]
        assert len(failure_logs) > 0
        # Should mention default red icon
        assert 'default red icon' in failure_logs[0].msg.lower()

    def test_all_log_messages_have_source(self):
        """Test that all log messages have 'Icon Processing' as source."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        # Generate various errors
        store_icon(b'data', 'test.txt', import_log, stats)  # Invalid extension
        
        with patch('geo_lib.processing.icons.get.urlopen') as mock_urlopen:
            mock_urlopen.side_effect = HTTPError('http://example.com/icon.png', 404, 'Not Found', {}, None)
            fetch_remote_icon('http://example.com/icon.png', 5.0, import_log)
        
        logs = import_log.get()
        assert len(logs) > 0
        
        # All logs should have "Icon Processing" as source
        for log in logs:
            assert log.source == "Icon Processing"

    def test_log_levels_are_appropriate(self):
        """Test that different errors use appropriate log levels."""
        import_log = ImportLog()
        stats = {'successful': 0, 'failed': 0}
        
        # WARNING: Size limit
        with patch('geo_lib.processing.icons.icon_manager.settings') as mock_settings:
            mock_settings.ICON_MAX_SIZE_BYTES = 1024
            store_icon(b'x' * 10000, 'large.png', import_log, stats)
        
        # WARNING: HTTP error
        with patch('geo_lib.processing.icons.get.urlopen') as mock_urlopen:
            mock_urlopen.side_effect = HTTPError('http://example.com/icon.png', 404, 'Not Found', {}, None)
            fetch_remote_icon('http://example.com/icon.png', 5.0, import_log)
        
        # ERROR: Storage failure
        with patch('geo_lib.processing.icons.icon_manager._get_icon_extension') as mock_ext:
            mock_ext.return_value = '.png'
            with patch('geo_lib.processing.icons.icon_manager._get_storage_path') as mock_path:
                mock_storage = Mock()
                mock_storage.exists.return_value = False
                mock_storage.write_bytes.side_effect = Exception("Disk error")
                mock_path.return_value = mock_storage
                store_icon(b'data', 'test.png', import_log, stats)
        
        logs = import_log.get()
        
        # Check that we have both WARNING and ERROR levels
        warning_logs = [log for log in logs if log.level == DatabaseLogLevel.WARNING]
        error_logs = [log for log in logs if log.level == DatabaseLogLevel.ERROR]
        
        assert len(warning_logs) > 0, "Should have WARNING level logs"
        assert len(error_logs) > 0, "Should have ERROR level logs"
