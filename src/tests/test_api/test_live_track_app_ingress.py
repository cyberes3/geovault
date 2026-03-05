import base64
import json
import struct
from unittest.mock import MagicMock, patch

from django.contrib.auth import get_user_model
from django.test import TestCase

from extensions.live_track.src.backend.models import LiveTrack


def _patch_live_track_enabled():
    """Return a context manager that mocks config so the live_track extension is considered enabled."""

    def mock_get_bool(key, default=False):
        if key == "extensions.live_track.enabled":
            return True
        return default

    def mock_get_int(key, default=0):
        if key == "extensions.live_track.max_points":
            return 1000
        return default

    mock_config = MagicMock()
    mock_config.get_bool.side_effect = mock_get_bool
    mock_config.get_int.side_effect = mock_get_int
    return patch("website.extensions.extension_loader.get_config_loader", return_value=mock_config)


def _basic_auth_header(username: str, password: str) -> str:
    """Build HTTP Basic Auth header value."""
    credentials = f"{username}:{password}"
    encoded = base64.b64encode(credentials.encode()).decode()
    return f"Basic {encoded}"


def encode_binary_payload(points):
    """
    Encode a list of points into the binary format.
    Header: 'GVLT' (4 bytes), version 0x01 (1 byte)
    Points: 
        flag (1 byte): bit 0 determines if extended data present
        timestamp (8 bytes, long long)
        lat (8 bytes, double)
        lon (8 bytes, double)
        [if flag & 1]:
            alt (4 bytes, float)
            speed (4 bytes, float)
            bearing (4 bytes, float)
            accuracy (4 bytes, float)
    """
    payload = bytearray(b'GVLT\x01')
    for p in points:
        has_extended = bool('alt' in p and 'acc' in p and 'spd_kph' in p and 'bearing' in p)
        flag = 1 if has_extended else 0
        
        # Multiply timestamp by 1000 if it's float seconds, to get ms integer
        ts_ms = int(p['timestamp'] * 1000) if isinstance(p['timestamp'], float) else int(p['timestamp'])
        
        payload.extend(struct.pack('>Bqdd', flag, ts_ms, p['lat'], p['lon']))
        if has_extended:
            payload.extend(struct.pack('>ffff', p['alt'], p['spd_kph'], p['bearing'], p['acc']))
    return bytes(payload)


class TestLiveTrackAppIngress(TestCase):
    """Test app_ingress endpoint (POST only, custom binary parsing)."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="appingress@example.com",
            password="testpass123",
            username="appingressuser",
        )
        self.client.force_login(self.user)
        
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "App Ingress Track"}),
                content_type="application/json",
            )
        self.track_id = create_resp.json()["id"]
        self.tracker_secret = create_resp.json()["tracker_secret"]
        # URL uses the tracker_secret directly in the path for app-ingress
        self.ingress_url = f"/api/extensions/live-track/app-ingress/{self.tracker_secret}/"
        # Since it's path-based auth, we don't necessarily need the basic auth header, but standard live_track uses tracker_secret in URL for the new endpoint as planned

    def _ingress_post(self, data, is_binary=True):
        content_type = "application/octet-stream" if is_binary else "application/json"
        
        # If testing missing tracker case
        url = getattr(self, "current_url", self.ingress_url)
        
        return self.client.post(url, data=data, content_type=content_type)

    def test_app_ingress_method_post_only(self):
        """Only POST allowed; GET returns 405."""
        with _patch_live_track_enabled():
            response = self.client.get(self.ingress_url)
        self.assertEqual(response.status_code, 405)

    def test_app_ingress_success_basic_point(self):
        """POST with valid binary single basic point returns 200 and appends point."""
        payload = encode_binary_payload([{
            "lat": 37.0, "lon": -122.0, "timestamp": 1705312800000
        }])
        
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(payload)
                
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        geom = track.geometry or {}
        coords = geom.get("coordinates", [])
        
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0][0], -122.0)
        self.assertEqual(coords[0][1], 37.0)
        self.assertEqual(coords[0][2], 1705312800000)
        
        # Shouldn't have any point params because extended data was zero
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertEqual(params[0], {})

    def test_app_ingress_success_extended_point(self):
        """POST with valid binary extended point returns 200 and stores point params."""
        payload = encode_binary_payload([{
            "lat": 38.0, "lon": -121.0, "timestamp": 1705312800000,
            "alt": 100.5, "acc": 10.0, "spd_kph": 5.0, "bearing": 180.0
        }])
        
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(payload)
                
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        
        self.assertEqual(len(params), 1)
        # Using AlmostEqual for float precision
        self.assertAlmostEqual(params[0].get("alt"), 100.5, places=1)
        self.assertAlmostEqual(params[0].get("acc"), 10.0, places=1)
        self.assertAlmostEqual(params[0].get("spd_kph"), 5.0, places=1)
        self.assertAlmostEqual(params[0].get("bearing"), 180.0, places=1)

    def test_app_ingress_success_multiple_points(self):
        """POST with multiple points in one binary payload works."""
        payload = encode_binary_payload([
            {"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000},
            {"lat": 37.1, "lon": -121.9, "timestamp": 1705312810000, "alt": 50.0, "acc": 5.0, "spd_kph": 10.0, "bearing": 90.0},
            {"lat": 37.2, "lon": -121.8, "timestamp": 1705312820000}
        ])
        
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(payload)
                
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        params = track.point_params or []
        
        self.assertEqual(len(coords), 3)
        self.assertEqual(len(params), 3)
        
        self.assertEqual(coords[0][2], 1705312800000)
        self.assertEqual(coords[1][2], 1705312810000)
        self.assertEqual(coords[2][2], 1705312820000)
        
        self.assertEqual(params[0], {})
        self.assertAlmostEqual(params[1].get("alt"), 50.0, places=1)
        self.assertEqual(params[2], {})

    def test_app_ingress_404_invalid_tracker(self):
        """POST to app-ingress with unknown tracker secret returns 404."""
        self.current_url = "/api/extensions/live-track/app-ingress/unknown-secret/"
        payload = encode_binary_payload([{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}])
        
        with _patch_live_track_enabled():
            response = self._ingress_post(payload)
            
        self.assertEqual(response.status_code, 404)
        delattr(self, "current_url")

    def test_app_ingress_400_invalid_magic_bytes(self):
        """POST with wrong magic bytes returns 400."""
        payload = bytearray(b'BADV\x01')
        payload.extend(struct.pack('>Bqdd', 0, 1705312800000, 37.0, -122.0))
        
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
            
        self.assertEqual(response.status_code, 400)
        self.assertIn("Invalid magic bytes", response.content.decode())

    def test_app_ingress_400_invalid_version(self):
        """POST with unsupported version returns 400."""
        payload = bytearray(b'GVLT\x02') # Version 2
        payload.extend(struct.pack('>Bqdd', 0, 1705312800000, 37.0, -122.0))
        
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
            
        self.assertEqual(response.status_code, 400)
        self.assertIn("Unsupported version", response.content.decode())

    def test_app_ingress_400_truncated_payload(self):
        """POST with truncated payload data returns 400."""
        # Valid header but missing part of the point data (20 bytes instead of 25)
        payload = bytearray(b'GVLT\x01')
        payload.extend(struct.pack('>Bqd', 0, 1705312800000, 37.0)) # Missing longitude
        
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
            
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_400_truncated_extended_payload(self):
        """POST with extended flag but truncated extended data returns 400."""
        payload = bytearray(b'GVLT\x01')
        # Point has flag=1 (expects 25+16=41 bytes)
        payload.extend(struct.pack('>Bqdd', 1, 1705312800000, 37.0, -122.0))
        # Only provide 8 of the 16 extended bytes
        payload.extend(struct.pack('>ff', 100.5, 10.0))
        
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
            
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())
