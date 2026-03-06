"""
App-ingress GVLT binary format tests.
Run this file alone if needed: ./run-tests.sh test_api/test_live_track_app_ingress.py
If a run hangs, use a timeout (e.g. pytest --timeout=60) or run a single test.
"""
import gzip
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


def _encode_point_gvlt(p):
    """Encode one point: base 17 bytes (Bqff) + extended (no starttimestamp, no ser)."""
    ts_ms = int(p["timestamp"] * 1000) if isinstance(p["timestamp"], float) else int(p["timestamp"])
    lat = float(p["lat"])
    lon = float(p["lon"])
    out = bytearray()
    out.extend(struct.pack(">Bqff", 0, ts_ms, lat, lon))
    sat = p.get("sat", 0)
    alt = p.get("alt", 0.0)
    spd_kph = p.get("spd_kph", 0.0)
    bearing = p.get("bearing", 0.0)
    acc = p.get("acc", 0.0)
    batt = p.get("batt", 0)
    ischarging = p.get("ischarging", False)
    dist_m = p.get("dist", 0.0)
    out.extend(struct.pack(">H", sat & 0xFFFF))
    out.extend(struct.pack(">ffff", alt, spd_kph, bearing, acc))
    out.extend(struct.pack(">Bb", batt, 1 if ischarging else 0))
    out.extend(struct.pack(">f", dist_m))
    prov = (p.get("prov") or "").encode("utf-8")[:64]
    out.append(len(prov))
    out.extend(prov)
    desc = (p.get("desc") or "").encode("utf-8")[:256]
    out.extend(struct.pack(">H", len(desc) & 0xFFFF))
    out.extend(desc)
    return bytes(out)


def encode_gvlt_payload(tracker_id_uuid, points, starttimestamp_ms=0, ser=""):
    """
    GVLT format: magic (4) + tracker_id (16) + batch block (starttimestamp 8 + ser_len 1 + ser)
    + points. Each point: base 17 bytes (float32 lat/lon) + extended (batt/ischarging per-point).
    """
    ser_bytes = ser.encode("utf-8")[:64]
    out = bytearray(b"GVLT")
    out.extend(tracker_id_uuid.bytes)
    out.extend(struct.pack(">q", starttimestamp_ms))
    out.append(len(ser_bytes))
    out.extend(ser_bytes)
    for p in points:
        out.extend(_encode_point_gvlt(p))
    return bytes(out)


def encode_gvlm_minimal_payload(tracker_id_uuid, points):
    """
    GVLM minimal format: magic "GVLM" (4) + tracker_id (16) + points.
    Each point: 17 bytes (flag 1 + time 8 + lat float32 4 + lon float32 4). No extended data.
    """
    out = bytearray(b"GVLM")
    out.extend(tracker_id_uuid.bytes)
    for p in points:
        ts_ms = int(p["timestamp"] * 1000) if isinstance(p["timestamp"], float) else int(p["timestamp"])
        lat, lon = float(p["lat"]), float(p["lon"])
        out.extend(struct.pack(">Bqff", 0, ts_ms, lat, lon))
    return bytes(out)


class TestLiveTrackAppIngress(TestCase):
    """Test app_ingress endpoint (POST only, OAuth + GVLT binary, no version byte)."""

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
        payload = encode_gvlt_payload(
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
        """POST with valid GVLT binary single point returns 200 and appends point."""
        payload = encode_gvlt_payload(
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
        # Extended fields stored (bearing, not dir)
        self.assertNotIn("dir", params[0])

    def test_app_ingress_success_extended_point(self):
        """POST with valid GVLT binary extended point returns 200 and stores point params (bearing, not dir)."""
        payload = encode_gvlt_payload(
            self.tracker_uuid,
            [{
                "lat": 38.0, "lon": -121.0, "timestamp": 1705312800000,
                "alt": 100.5, "acc": 10.0, "spd_kph": 5.0, "bearing": 180.0,
                "sat": 8, "prov": "gps", "batt": 85, "ischarging": True, "dist": 100.5,
            }],
            starttimestamp_ms=1705312700000,
            ser="ABC123",
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
        self.assertEqual(params[0].get("sat"), 8)
        self.assertEqual(params[0].get("prov"), "gps")
        self.assertEqual(params[0].get("starttimestamp"), 1705312700000)
        self.assertEqual(params[0].get("batt"), 85)
        self.assertTrue(params[0].get("ischarging"))
        self.assertAlmostEqual(params[0].get("dist"), 100.5, places=1)
        self.assertEqual(params[0].get("ser"), "ABC123")
        self.assertEqual(params[0].get("starttimestamp"), 1705312700000)
        self.assertNotIn("dir", params[0])

    def test_app_ingress_success_multiple_points(self):
        """POST with multiple points in one GVLT binary payload works."""
        payload = encode_gvlt_payload(
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
        self.assertAlmostEqual(params[1].get("alt"), 50.0, places=1)
        self.assertAlmostEqual(params[1].get("bearing"), 90.0, places=1)

    def test_app_ingress_success_gvlm_minimal(self):
        """POST with GVLM minimal payload (extended params off) returns 200, stores coords only."""
        payload = encode_gvlm_minimal_payload(
            self.tracker_uuid,
            [
                {"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000},
                {"lat": 37.01, "lon": -122.01, "timestamp": 1705312860000},
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
        self.assertEqual(len(coords), 2)
        self.assertEqual(len(params), 2)
        self.assertEqual(coords[0], [-122.0, 37.0, 1705312800000])
        self.assertAlmostEqual(coords[1][0], -122.01, places=4)
        self.assertAlmostEqual(coords[1][1], 37.01, places=4)
        self.assertEqual(coords[1][2], 1705312860000)
        self.assertEqual(params[0], {})
        self.assertEqual(params[1], {})

    def test_app_ingress_404_wrong_user(self):
        """POST with another user's tracker ID returns 404."""
        User = get_user_model()
        other = User.objects.create_user(email="other@example.com", password="x", username="other")
        self.client.force_login(other)
        payload = encode_gvlt_payload(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        with _patch_live_track_enabled():
            response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 404)

    def test_app_ingress_400_invalid_magic_bytes(self):
        """POST with wrong magic bytes returns 400."""
        payload = bytearray(b"BADV")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">q", 0))
        payload.append(0)
        payload.extend(_encode_point_gvlt({"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Invalid magic bytes", response.content.decode())

    def test_app_ingress_400_truncated_header(self):
        """POST with body shorter than 20 bytes returns 400 Invalid magic bytes."""
        with _patch_live_track_enabled():
            response = self._ingress_post(b"GVLT")
        self.assertEqual(response.status_code, 400)
        self.assertIn("Invalid magic bytes", response.content.decode())

    def test_app_ingress_400_truncated_payload(self):
        """POST with batch header but incomplete first point (no extended block) returns 400."""
        payload = bytearray(b"GVLT")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">q", 0))
        payload.append(0)
        payload.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_400_truncated_extended_payload(self):
        """POST with base point but truncated extended data returns 400."""
        payload = bytearray(b"GVLT")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">q", 0))
        payload.append(0)
        payload.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        payload.extend(struct.pack(">H", 0))
        payload.extend(struct.pack(">ffff", 100.5, 10.0, 90.0, 5.0))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_400_second_point_truncated_base(self):
        """POST with first point complete but second point missing base bytes returns 400."""
        full_point = _encode_point_gvlt({"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000})
        payload = bytearray(b"GVLT")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">q", 0))
        payload.append(0)
        payload.extend(full_point)
        payload.extend(struct.pack(">Bq", 0, 1705312860000))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_success_gzip_compressed(self):
        """POST with Content-Encoding: gzip and compressed body returns 200."""
        payload = encode_gvlt_payload(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        compressed = gzip.compress(payload)
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self.client.post(
                    self.ingress_url,
                    data=compressed,
                    content_type="application/octet-stream",
                    HTTP_CONTENT_ENCODING="gzip",
                )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0][1], 37.0)
        self.assertEqual(coords[0][0], -122.0)

    def test_app_ingress_max_points_trimmed(self):
        """App-ingress trims to max_points (config); oldest points removed."""
        payload = encode_gvlt_payload(
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

    # ---- Encoding format & structure ----

    def test_app_ingress_empty_points_batch_header_only(self):
        """POST with valid header and batch block but zero points returns 200 (no new coords)."""
        payload = bytearray(b"GVLT")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">q", 1705312700000))
        payload.append(0)
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 0)

    def test_app_ingress_404_nonexistent_tracker_uuid(self):
        """POST with valid GVLT but UUID that does not match any track returns 404."""
        other_uuid = uuid.uuid4()
        while other_uuid == self.tracker_uuid:
            other_uuid = uuid.uuid4()
        payload = encode_gvlt_payload(
            other_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        with _patch_live_track_enabled():
            response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 404)

    def test_app_ingress_400_incomplete_batch_block_truncated_after_uuid(self):
        """POST with body length 20–28 bytes (missing starttimestamp/ser_len/ser) returns 400."""
        payload = bytearray(b"GVLT")
        payload.extend(self.tracker_uuid.bytes)
        self.assertEqual(len(payload), 20)
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete batch block", response.content.decode())

    def test_app_ingress_400_incomplete_batch_block_ser_claimed_too_long(self):
        """POST with ser_len claiming more bytes than present returns 400."""
        payload = bytearray(b"GVLT")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">q", 0))
        payload.append(10)
        payload.extend(b"only3")
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete batch block", response.content.decode())

    def test_app_ingress_400_truncated_prov_string(self):
        """POST with prov_len pointing past end of body returns 400."""
        payload = bytearray(b"GVLT")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">q", 0))
        payload.append(0)
        payload.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        payload.extend(struct.pack(">H", 0))
        payload.extend(struct.pack(">ffff", 0.0, 0.0, 0.0, 0.0))
        payload.extend(struct.pack(">Bb", 0, 0))
        payload.extend(struct.pack(">f", 0.0))
        payload.append(10)
        payload.extend(b"abc")
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_400_truncated_desc_string(self):
        """POST with desc_len pointing past end of body returns 400."""
        payload = bytearray(b"GVLT")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">q", 0))
        payload.append(0)
        payload.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        payload.extend(struct.pack(">H", 0))
        payload.extend(struct.pack(">ffff", 0.0, 0.0, 0.0, 0.0))
        payload.extend(struct.pack(">Bb", 0, 0))
        payload.extend(struct.pack(">f", 0.0))
        payload.append(0)
        payload.extend(struct.pack(">H", 5))
        payload.extend(b"ab")
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_too_many_points_rejected(self):
        """Parser rejects payloads with more than _MAX_POINTS_PER_PAYLOAD points."""
        with _patch_live_track_enabled():
            with patch(
                "extensions.live_track.src.backend.ingress_views._MAX_POINTS_PER_PAYLOAD",
                2,
            ):
                min_point = _encode_point_gvlt({
                    "lat": 37.0, "lon": -122.0, "timestamp": 1705312800000,
                })
                payload = bytearray(b"GVLT")
                payload.extend(self.tracker_uuid.bytes)
                payload.extend(struct.pack(">q", 0))
                payload.append(0)
                payload.extend(min_point)
                payload.extend(min_point)
                payload.extend(min_point)
                response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Too many points", response.content.decode())

    def test_app_ingress_success_sat_zero_omitted_from_params(self):
        """When sat is 0, backend does not store 'sat' in point_params."""
        payload = encode_gvlt_payload(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000, "sat": 0}],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertNotIn("sat", params[0])

    def test_app_ingress_success_batt_ischarging_boundaries(self):
        """batt 0/100 and ischarging false/true are stored correctly."""
        payload = encode_gvlt_payload(
            self.tracker_uuid,
            [
                {"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000, "batt": 0, "ischarging": False},
                {"lat": 37.01, "lon": -122.0, "timestamp": 1705312860000, "batt": 100, "ischarging": True},
            ],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 2)
        self.assertEqual(params[0].get("batt"), 0)
        self.assertFalse(params[0].get("ischarging"))
        self.assertEqual(params[1].get("batt"), 100)
        self.assertTrue(params[1].get("ischarging"))

    def test_app_ingress_success_float32_lat_lon_precision(self):
        """Float32 lat/lon are accepted and stored (precision loss is acceptable)."""
        payload = encode_gvlt_payload(
            self.tracker_uuid,
            [{"lat": 37.123456, "lon": -122.654321, "timestamp": 1705312800000}],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 1)
        self.assertAlmostEqual(coords[0][1], 37.123456, places=4)
        self.assertAlmostEqual(coords[0][0], -122.654321, places=4)

    def test_app_ingress_success_max_length_prov_ser_desc(self):
        """Max-length prov (64), batch ser (64), and desc (256) are accepted and capped on decode."""
        long_prov = "p" * 64
        long_ser = "s" * 64
        long_desc = "d" * 256
        payload = encode_gvlt_payload(
            self.tracker_uuid,
            [{
                "lat": 37.0, "lon": -122.0, "timestamp": 1705312800000,
                "prov": long_prov, "desc": long_desc,
            }],
            ser=long_ser,
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertEqual(params[0].get("prov"), long_prov)
        self.assertEqual(params[0].get("ser"), long_ser)
        self.assertEqual(params[0].get("desc"), long_desc)

    def test_app_ingress_success_empty_ser_batch_no_ser_in_params(self):
        """When batch ser is empty, point_params do not include 'ser'."""
        payload = encode_gvlt_payload(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
            starttimestamp_ms=0,
            ser="",
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertNotIn("ser", params[0])

    def test_app_ingress_utf8_replacement_invalid_sequences(self):
        """Invalid UTF-8 in prov or desc is decoded with replacement and stored."""
        payload = bytearray(b"GVLT")
        payload.extend(self.tracker_uuid.bytes)
        payload.extend(struct.pack(">q", 0))
        payload.append(0)
        payload.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        payload.extend(struct.pack(">H", 0))
        payload.extend(struct.pack(">ffff", 0.0, 0.0, 0.0, 0.0))
        payload.extend(struct.pack(">Bb", 0, 0))
        payload.extend(struct.pack(">f", 0.0))
        payload.append(3)
        payload.extend(b"\xff\xfe\xfd")
        payload.extend(struct.pack(">H", 2))
        payload.extend(b"\x80\x81")
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(bytes(payload))
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertIn("prov", params[0])
        self.assertIn("desc", params[0])

    # ---- Content-Encoding / decompression ----

    def test_app_ingress_400_invalid_gzip_body(self):
        """POST with Content-Encoding: gzip but non-gzip body returns 400."""
        with _patch_live_track_enabled():
            response = self.client.post(
                self.ingress_url,
                data=b"not gzip data",
                content_type="application/octet-stream",
                HTTP_CONTENT_ENCODING="gzip",
            )
        self.assertEqual(response.status_code, 400)
        self.assertIn("Content-Encoding", response.content.decode())

    def test_app_ingress_400_gzip_empty_body(self):
        """POST with Content-Encoding: gzip and empty body returns 400."""
        with _patch_live_track_enabled():
            response = self.client.post(
                self.ingress_url,
                data=b"",
                content_type="application/octet-stream",
                HTTP_CONTENT_ENCODING="gzip",
            )
        self.assertEqual(response.status_code, 400)

    def test_app_ingress_400_invalid_deflate_body(self):
        """POST with Content-Encoding: deflate but non-deflate body returns 400."""
        with _patch_live_track_enabled():
            response = self.client.post(
                self.ingress_url,
                data=b"not deflate data",
                content_type="application/octet-stream",
                HTTP_CONTENT_ENCODING="deflate",
            )
        self.assertEqual(response.status_code, 400)
        self.assertIn("Content-Encoding", response.content.decode())

    def test_app_ingress_success_deflate_compressed(self):
        """POST with Content-Encoding: deflate and zlib-compressed body returns 200."""
        import zlib
        payload = encode_gvlt_payload(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        compressed = zlib.compress(payload)
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self.client.post(
                    self.ingress_url,
                    data=compressed,
                    content_type="application/octet-stream",
                    HTTP_CONTENT_ENCODING="deflate",
                )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0][1], 37.0)
        self.assertEqual(coords[0][0], -122.0)
