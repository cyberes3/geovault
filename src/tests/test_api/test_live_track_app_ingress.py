import json
import struct
import uuid
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


def _encode_points(points):
    """Encode points only (no header) for GVLT."""
    out = bytearray()
    for p in points:
        has_extended = bool("alt" in p and "acc" in p and "spd_kph" in p and "bearing" in p)
        flag = 1 if has_extended else 0
        ts_ms = int(p["timestamp"] * 1000) if isinstance(p["timestamp"], float) else int(p["timestamp"])
        out.extend(struct.pack(">Bqdd", flag, ts_ms, p["lat"], p["lon"]))
        if has_extended:
            out.extend(struct.pack(">ffff", p["alt"], p["spd_kph"], p["bearing"], p["acc"]))
    return bytes(out)


def encode_binary_payload_v2(tracker_id_uuid, points):
    """
    GVLT v2: magic (4) + version 0x02 (1) + tracker_id (16 bytes UUID) + points.
    """
    return b"GVLT\x02" + tracker_id_uuid.bytes + _encode_points(points)


class TestLiveTrackAppIngress(TestCase):
    """Test app_ingress endpoint (POST only, OAuth + GVLT v2 binary)."""

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
        self.tracker_uuid = uuid.UUID(self.track_id)
        self.ingress_url = "/api/extensions/live-track/app-ingress/"

    def _ingress_post(self, data):
        return self.client.post(
            self.ingress_url,
            data=data,
            content_type="application/octet-stream",
        )

    def test_app_ingress_401_unauthenticated(self):
        """POST without auth returns 401."""
        payload = encode_binary_payload_v2(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        self.client.logout()
        with _patch_live_track_enabled():
            response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 401)

    def test_app_ingress_method_post_only(self):
        """Only POST allowed; GET returns 405."""
        with _patch_live_track_enabled():
            response = self.client.get(self.ingress_url)
        self.assertEqual(response.status_code, 405)

    def test_app_ingress_success_basic_point(self):
        """POST with valid v2 binary single basic point returns 200 and appends point."""
        payload = encode_binary_payload_v2(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
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

        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertEqual(params[0], {})

    def test_app_ingress_success_extended_point(self):
        """POST with valid v2 binary extended point returns 200 and stores point params."""
        payload = encode_binary_payload_v2(
            self.tracker_uuid,
            [{
                "lat": 38.0, "lon": -121.0, "timestamp": 1705312800000,
                "alt": 100.5, "acc": 10.0, "spd_kph": 5.0, "bearing": 180.0,
            }],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(payload)

        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []

        self.assertEqual(len(params), 1)
        self.assertAlmostEqual(params[0].get("alt"), 100.5, places=1)
        self.assertAlmostEqual(params[0].get("acc"), 10.0, places=1)
        self.assertAlmostEqual(params[0].get("spd_kph"), 5.0, places=1)
        self.assertAlmostEqual(params[0].get("bearing"), 180.0, places=1)

    def test_app_ingress_success_multiple_points(self):
        """POST with multiple points in one v2 binary payload works."""
        payload = encode_binary_payload_v2(
            self.tracker_uuid,
            [
                {"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000},
                {"lat": 37.1, "lon": -121.9, "timestamp": 1705312810000, "alt": 50.0, "acc": 5.0, "spd_kph": 10.0, "bearing": 90.0},
                {"lat": 37.2, "lon": -121.8, "timestamp": 1705312820000},
            ],
        )
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

    def test_app_ingress_404_wrong_user(self):
        """POST with another user's tracker ID returns 404."""
        User = get_user_model()
        other = User.objects.create_user(email="other@example.com", password="x", username="other")
        self.client.force_login(other)
        payload = encode_binary_payload_v2(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        with _patch_live_track_enabled():
            response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 404)

    def test_app_ingress_400_invalid_magic_bytes(self):
        """POST with wrong magic bytes returns 400."""
        payload = bytearray(b"BADV\x02")
        payload.extend(b"\x00" * 16)
        payload.extend(struct.pack(">Bqdd", 0, 1705312800000, 37.0, -122.0))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Invalid magic bytes", response.content.decode())

    def test_app_ingress_400_invalid_version(self):
        """POST with version 0x01 returns 400 Unsupported version."""
        payload = bytearray(b"GVLT\x01")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">Bqdd", 0, 1705312800000, 37.0, -122.0))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Unsupported version", response.content.decode())

    def test_app_ingress_400_missing_tracker_id(self):
        """POST with body shorter than 21 bytes returns 400 Missing tracker ID."""
        with _patch_live_track_enabled():
            response = self._ingress_post(b"GVLT\x02")
        self.assertEqual(response.status_code, 400)
        self.assertIn("Missing tracker ID", response.content.decode())

    def test_app_ingress_400_truncated_payload(self):
        """POST with truncated point data returns 400."""
        payload = bytearray(b"GVLT\x02")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">Bqd", 0, 1705312800000, 37.0))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_400_truncated_extended_payload(self):
        """POST with extended flag but truncated extended data returns 400."""
        payload = bytearray(b"GVLT\x02")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">Bqdd", 1, 1705312800000, 37.0, -122.0))
        payload.extend(struct.pack(">ff", 100.5, 10.0))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_max_points_trimmed(self):
        """App-ingress trims to max_points (config); oldest points removed."""
        payload = encode_binary_payload_v2(
            self.tracker_uuid,
            [
                {"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000},
                {"lat": 37.01, "lon": -122.0, "timestamp": 1705312860000},
                {"lat": 37.02, "lon": -122.0, "timestamp": 1705312920000},
                {"lat": 37.03, "lon": -122.0, "timestamp": 1705312980000},
            ],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.get_config_loader") as mock_cfg:
                mock_cfg.return_value.get_int.return_value = 2
                with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                    mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                    response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        params = track.point_params or []
        self.assertEqual(len(coords), 2)
        self.assertEqual(len(params), 2)
        self.assertEqual(coords[0][2], 1705312920000)
        self.assertEqual(coords[1][2], 1705312980000)
