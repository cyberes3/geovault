"""Tests for SSRF protection (is_url_safe_for_fetch) and icon fetch integration."""

import pytest
from unittest.mock import MagicMock, patch

from geo_lib.security.ssrf import is_url_safe_for_fetch


class TestSSRFBlockedSchemes:
    """Reject disallowed URL schemes."""

    def test_reject_file_scheme(self):
        assert is_url_safe_for_fetch("file:///etc/passwd") is False

    def test_reject_gopher_scheme(self):
        assert is_url_safe_for_fetch("gopher://localhost/") is False

    def test_reject_data_scheme(self):
        assert is_url_safe_for_fetch("data:text/plain,evil") is False

    def test_reject_dict_scheme(self):
        assert is_url_safe_for_fetch("dict://localhost:11211/") is False


class TestSSRFBlockedInputs:
    """Reject empty or invalid inputs."""

    def test_reject_empty(self):
        assert is_url_safe_for_fetch("") is False
        assert is_url_safe_for_fetch("   ") is False

    def test_reject_no_netloc(self):
        assert is_url_safe_for_fetch("http://") is False
        assert is_url_safe_for_fetch("https://") is False


class TestSSRFBlockedIPRanges:
    """Reject private, loopback, link-local, reserved IPs."""

    def test_reject_loopback(self):
        assert is_url_safe_for_fetch("http://127.0.0.1/") is False
        assert is_url_safe_for_fetch("http://localhost/") is False

    def test_reject_private_rfc1918(self):
        assert is_url_safe_for_fetch("http://10.0.0.1/") is False
        assert is_url_safe_for_fetch("http://192.168.1.1/") is False
        assert is_url_safe_for_fetch("http://172.16.0.1/") is False

    def test_reject_link_local(self):
        assert is_url_safe_for_fetch("http://169.254.169.254/") is False

    def test_reject_zero_address(self):
        assert is_url_safe_for_fetch("http://0.0.0.0/") is False

    def test_reject_ipv6_loopback(self):
        assert is_url_safe_for_fetch("http://[::1]/") is False


class TestSSRFAccepted:
    """Accept safe public URLs."""

    def test_accept_public_https(self):
        assert is_url_safe_for_fetch("https://example.com/icon.png") is True

    def test_accept_public_http(self):
        assert is_url_safe_for_fetch("http://example.com/icon.png") is True

    def test_accept_with_port(self):
        assert is_url_safe_for_fetch("https://example.com:443/path") is True


class TestFetchRemoteIconSSRF:
    """fetch_remote_icon must not perform request when URL is SSRF-unsafe."""

    def test_ssrf_url_returns_none_and_logs(self):
        from geo_lib.processing.logging import ImportLog, DatabaseLogLevel
        from geo_lib.processing.icons.get import fetch_remote_icon

        import_log = ImportLog()
        result = fetch_remote_icon("http://127.0.0.1/icon.png", 5.0, import_log)
        assert result is None
        logs = import_log.get()
        assert any("not allowed" in log.msg.lower() or "security" in log.msg.lower() for log in logs)

    def test_ssrf_url_does_not_open_connection(self):
        """Ensure no socket/opener is used when URL fails SSRF check."""
        from geo_lib.processing.logging import ImportLog
        from geo_lib.processing.icons.get import fetch_remote_icon

        import_log = ImportLog()
        with patch("geo_lib.processing.icons.get.build_opener") as mock_build:
            result = fetch_remote_icon("http://169.254.169.254/metadata", 5.0, import_log)
        assert result is None
        mock_build.assert_not_called()


class TestSSRFIntegrationKML:
    """Integration: real KML with remote icon href; SSRF URL must not be fetched."""

    def test_kml_with_ssrf_icon_href_does_not_fetch(self):
        """Process GeoJSON from KML that references an SSRF icon URL; no request to internal host."""
        from geo_lib.processing.icons.icon_manager import process_geojson_icons
        from geo_lib.processing.logging import ImportLog

        # GeoJSON with a Point that has an icon href pointing at loopback (SSRF)
        geojson = {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {"type": "Point", "coordinates": [-122.0, 37.0]},
                    "properties": {
                        "name": "Test",
                        "icon": "http://127.0.0.1/evil.png",
                    },
                }
            ],
        }
        import_log = ImportLog()
        with patch("geo_lib.processing.icons.get.build_opener") as mock_build:
            result = process_geojson_icons(geojson, "kml", import_log)
        # Opener should never be built for that href because SSRF check fails before fetch
        mock_build.assert_not_called()
        logs = import_log.get()
        assert any("not allowed" in log.msg.lower() or "security" in log.msg.lower() for log in logs)

    def test_kml_with_public_icon_href_allowed(self):
        """Process GeoJSON from KML with a public icon URL; SSRF check passes and fetch is attempted."""
        from geo_lib.processing.icons.icon_manager import process_geojson_icons
        from geo_lib.processing.logging import ImportLog

        geojson = {
            "type": "FeatureCollection",
            "features": [
                {
                    "type": "Feature",
                    "geometry": {"type": "Point", "coordinates": [-122.0, 37.0]},
                    "properties": {
                        "name": "Test",
                        "icon": "https://example.com/icon.png",
                    },
                }
            ],
        }
        import_log = ImportLog()
        mock_opener = MagicMock()
        mock_resp = MagicMock()
        mock_resp.read.side_effect = [b"\x89PNG\r\n\x1a\n", b""]
        mock_resp.headers = {}
        mock_resp.__enter__ = lambda self: self
        mock_resp.__exit__ = lambda self, *a: None
        mock_opener.open.return_value = mock_resp
        with patch("geo_lib.processing.icons.get.build_opener", return_value=mock_opener):
            result = process_geojson_icons(geojson, "kml", import_log)
        mock_opener.open.assert_called()
        assert result is not None
        assert "features" in result


class TestSSRFIntegrationRealKML:
    """Integration: real KML through full processor; SSRF icon URL must not be fetched."""

    def test_real_kml_with_ssrf_icon_href_no_fetch(self):
        """Full KML with Icon href to loopback: convert_to_geojson runs, no outbound fetch to internal host."""
        from geo_lib.processing.processors import get_processor

        kml_with_ssrf_icon = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>SSRF test placemark</name>
      <Style>
        <IconStyle>
          <Icon>
            <href>http://127.0.0.1/evil.png</href>
          </Icon>
        </IconStyle>
      </Style>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        with patch("geo_lib.processing.icons.get.build_opener") as mock_build:
            processor = get_processor(kml_with_ssrf_icon.encode("utf-8"), "ssrf_test.kml")
            result = processor.convert_to_geojson()
        assert result["type"] == "FeatureCollection"
        assert len(result["features"]) >= 1
        mock_build.assert_not_called()

    def test_real_kml_with_safe_icon_href_fetch_attempted(self):
        """Full KML with Icon href to public URL: fetch is attempted (mocked)."""
        from geo_lib.processing.processors import get_processor

        kml_with_public_icon = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">
  <Document>
    <Placemark>
      <name>Public icon placemark</name>
      <Style>
        <IconStyle>
          <Icon>
            <href>https://example.com/icon.png</href>
          </Icon>
        </IconStyle>
      </Style>
      <Point>
        <coordinates>-122.4194,37.7749,0</coordinates>
      </Point>
    </Placemark>
  </Document>
</kml>"""
        mock_opener = MagicMock()
        mock_resp = MagicMock()
        mock_resp.read.side_effect = [b"\x89PNG\r\n\x1a\n", b""]
        mock_resp.headers = {}
        mock_resp.__enter__ = lambda self: self
        mock_resp.__exit__ = lambda self, *a: None
        mock_opener.open.return_value = mock_resp
        with patch("geo_lib.processing.icons.get.build_opener", return_value=mock_opener):
            processor = get_processor(kml_with_public_icon.encode("utf-8"), "public_icon_test.kml")
            result = processor.convert_to_geojson()
        assert result["type"] == "FeatureCollection"
        assert len(result["features"]) >= 1
        mock_opener.open.assert_called()
