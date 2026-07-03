"""
App-ingress GVL2 binary format tests.
Run this file alone if needed: ./run-tests.sh test_api/test_live_track_app_ingress.py
If a run hangs, use a timeout (e.g. pytest --timeout=60) or run a single test.
"""
import gzip
import json
import struct
import uuid
from datetime import timedelta
from unittest.mock import MagicMock, patch

from django.contrib.auth import get_user_model
from django.test import TestCase
from django.utils import timezone

from extensions.live_track.src.backend.models import LiveTrack


def _patch_live_track_enabled():
    """Return a context manager that mocks config so the live_track extension is considered enabled."""

    def mock_get_bool(key, default=False):
        if key == "extensions.live_track.enabled":
            return True
        return default

    def mock_get_int(key, default=0):
        if key == "extensions.live_track.geometry_max_response_bytes":
            return 1048576
        return default

    mock_config = MagicMock()
    mock_config.get_bool.side_effect = mock_get_bool
    mock_config.get_int.side_effect = mock_get_int
    return patch("website.extensions.extension_loader.get_config_loader", return_value=mock_config)


_GVL2_MAGIC = b"GVL2"
_FLAG_HAS_EXTENDED = 0x01


def _encode_extended_point(p):
    """Encode one point: base 17 bytes (Bqff) + extended fields. starttimestamp/ser come from header."""
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


def _encode_minimal_point(p):
    """Encode one minimal point: base 17 bytes only (Bqff). Used when HAS_EXTENDED=0."""
    ts_ms = int(p["timestamp"] * 1000) if isinstance(p["timestamp"], float) else int(p["timestamp"])
    lat = float(p["lat"])
    lon = float(p["lon"])
    return struct.pack(">Bqff", 0, ts_ms, lat, lon)


def _gvl2_header(tracker_id_uuid, *, has_extended, session_start_ms, ser=""):
    """Build the GVL2 header. ser is gated behind HAS_EXTENDED."""
    out = bytearray(_GVL2_MAGIC)
    out.extend(tracker_id_uuid.bytes)
    out.append(_FLAG_HAS_EXTENDED if has_extended else 0)
    out.extend(struct.pack(">q", session_start_ms))
    if has_extended:
        ser_bytes = ser.encode("utf-8")[:64]
        out.append(len(ser_bytes))
        out.extend(ser_bytes)
    return out


def encode_gvl2_extended(tracker_id_uuid, points, session_start_ms=0, ser=""):
    """GVL2 extended (HAS_EXTENDED=1) payload."""
    out = _gvl2_header(tracker_id_uuid, has_extended=True, session_start_ms=session_start_ms, ser=ser)
    for p in points:
        out.extend(_encode_extended_point(p))
    return bytes(out)


def encode_gvl2_minimal(tracker_id_uuid, points, session_start_ms=0):
    """GVL2 minimal (HAS_EXTENDED=0) payload. session_start_ms still lives in the header."""
    out = _gvl2_header(tracker_id_uuid, has_extended=False, session_start_ms=session_start_ms)
    for p in points:
        out.extend(_encode_minimal_point(p))
    return bytes(out)


class TestLiveTrackAppIngress(TestCase):
    """Test app_ingress endpoint (POST only, OAuth + GVL2 binary)."""

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
        payload = encode_gvl2_extended(
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
        """POST with valid GVL2 extended single point returns 200 and appends point."""
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
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
        self.assertNotIn("dir", params[0])

    def test_app_ingress_success_extended_point(self):
        """POST with valid GVL2 extended point returns 200 and stores point params (bearing, not dir)."""
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{
                "lat": 38.0, "lon": -121.0, "timestamp": 1705312800000,
                "alt": 100.5, "acc": 10.0, "spd_kph": 5.0, "bearing": 180.0,
                "sat": 8, "prov": "gps", "batt": 85, "ischarging": True, "dist": 100.5,
            }],
            session_start_ms=1705312700000,
            ser="ABC123",
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
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
        self.assertNotIn("dir", params[0])

    def test_app_ingress_success_multiple_points(self):
        """POST with multiple points in one GVL2 extended payload works."""
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [
                {"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000},
                {"lat": 37.1, "lon": -121.9, "timestamp": 1705312810000, "alt": 50.0, "acc": 5.0, "spd_kph": 10.0, "bearing": 90.0},
                {"lat": 37.2, "lon": -121.8, "timestamp": 1705312820000},
            ],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
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

    def test_app_ingress_success_minimal_stamps_starttimestamp(self):
        """GVL2 minimal payload (HAS_EXTENDED=0) still stamps starttimestamp on every point's params."""
        payload = encode_gvl2_minimal(
            self.tracker_uuid,
            [
                {"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000},
                {"lat": 37.01, "lon": -122.01, "timestamp": 1705312860000},
            ],
            session_start_ms=1705312700000,
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
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
        # Minimal mode does NOT carry ser, batt, etc, but ALWAYS carries starttimestamp.
        self.assertEqual(params[0].get("starttimestamp"), 1705312700000)
        self.assertEqual(params[1].get("starttimestamp"), 1705312700000)
        self.assertNotIn("ser", params[0])
        self.assertNotIn("batt", params[0])
        self.assertNotIn("alt", params[0])

    def test_app_ingress_legacy_gvlm_minimal_still_accepted(self):
        """Legacy GVLM minimal payload (old Android client) is still accepted."""
        out = bytearray(b"GVLM")
        out.extend(self.tracker_uuid.bytes)
        out.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        out.extend(struct.pack(">Bqff", 0, 1705312860000, 37.01, -122.01))
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        params = track.point_params or []
        self.assertEqual(len(coords), 2)
        self.assertEqual(len(params), 2)
        self.assertEqual(coords[0], [-122.0, 37.0, 1705312800000])
        # Legacy GVLM has no batch starttimestamp.
        self.assertNotIn("starttimestamp", params[0])
        self.assertNotIn("starttimestamp", params[1])

    def test_app_ingress_legacy_gvlt_extended_still_accepted(self):
        """Legacy GVLT extended payload (old Android client) is still accepted and stamps starttimestamp."""
        out = bytearray(b"GVLT")
        out.extend(self.tracker_uuid.bytes)
        out.extend(struct.pack(">q", 1705312700000))
        ser_bytes = b"build-9.9"
        out.append(len(ser_bytes))
        out.extend(ser_bytes)
        out.extend(_encode_extended_point({
            "lat": 37.0, "lon": -122.0, "timestamp": 1705312800000,
            "alt": 100.5, "spd_kph": 5.0, "bearing": 90.0, "acc": 5.0,
            "sat": 8, "prov": "gps", "batt": 75, "ischarging": True, "dist": 100.0,
        }))
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertEqual(params[0].get("starttimestamp"), 1705312700000)
        self.assertEqual(params[0].get("ser"), "build-9.9")
        self.assertEqual(params[0].get("sat"), 8)
        self.assertEqual(params[0].get("prov"), "gps")

    def test_app_ingress_404_wrong_user(self):
        """POST with another user's tracker ID returns 404."""
        User = get_user_model()
        other = User.objects.create_user(email="other@example.com", password="x", username="other")
        self.client.force_login(other)
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        with _patch_live_track_enabled():
            response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 404)

    def test_app_ingress_400_invalid_magic_bytes(self):
        """POST with wrong magic bytes returns 400."""
        out = bytearray(b"BADV")
        out.extend(self.tracker_uuid.bytes)
        out.append(_FLAG_HAS_EXTENDED)
        out.extend(struct.pack(">q", 0))
        out.append(0)
        out.extend(_encode_extended_point({"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Invalid magic bytes", response.content.decode())

    def test_app_ingress_400_truncated_header(self):
        """POST with body shorter than the GVL2 header returns 400 Invalid magic bytes."""
        with _patch_live_track_enabled():
            response = self._ingress_post(b"GVL2")
        self.assertEqual(response.status_code, 400)
        self.assertIn("Invalid magic bytes", response.content.decode())

    def test_app_ingress_400_truncated_payload(self):
        """POST with header but incomplete first point's extended block returns 400."""
        out = _gvl2_header(self.tracker_uuid, has_extended=True, session_start_ms=0, ser="")
        out.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_400_truncated_extended_payload(self):
        """POST with base point but truncated extended data returns 400."""
        out = _gvl2_header(self.tracker_uuid, has_extended=True, session_start_ms=0, ser="")
        out.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        out.extend(struct.pack(">H", 0))
        out.extend(struct.pack(">ffff", 100.5, 10.0, 90.0, 5.0))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_400_second_point_truncated_base(self):
        """POST with first point complete but second point missing base bytes returns 400."""
        full_point = _encode_extended_point({"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000})
        out = _gvl2_header(self.tracker_uuid, has_extended=True, session_start_ms=0, ser="")
        out.extend(full_point)
        out.extend(struct.pack(">Bq", 0, 1705312860000))
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_success_gzip_compressed(self):
        """POST with Content-Encoding: gzip and compressed body returns 200."""
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        compressed = gzip.compress(payload)
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
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

    def test_app_ingress_keeps_all_points(self):
        """App-ingress retains all received points; point-count trimming is not applied."""
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [
                {"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000},
                {"lat": 37.01, "lon": -122.0, "timestamp": 1705312860000},
                {"lat": 37.02, "lon": -122.0, "timestamp": 1705312920000},
                {"lat": 37.03, "lon": -122.0, "timestamp": 1705312980000},
            ],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        params = track.point_params or []
        self.assertEqual(len(coords), 4)
        self.assertEqual(len(params), 4)
        self.assertEqual(coords[0][2], 1705312800000)
        self.assertEqual(coords[-1][2], 1705312980000)

    # ---- Encoding format & structure ----

    def test_app_ingress_empty_points_batch_header_only(self):
        """POST with valid header and batch block but zero points returns 200 (no new coords)."""
        out = _gvl2_header(self.tracker_uuid, has_extended=True, session_start_ms=1705312700000, ser="")
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 0)

    def test_app_ingress_404_nonexistent_tracker_uuid(self):
        """POST with valid GVL2 but UUID that does not match any track returns 404."""
        other_uuid = uuid.uuid4()
        while other_uuid == self.tracker_uuid:
            other_uuid = uuid.uuid4()
        payload = encode_gvl2_extended(
            other_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        with _patch_live_track_enabled():
            response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 404)

    def test_app_ingress_400_incomplete_batch_block_truncated_after_uuid(self):
        """POST with body missing flags/session_start (header < base header bytes) returns 400."""
        out = bytearray(_GVL2_MAGIC)
        out.extend(self.tracker_uuid.bytes)
        self.assertEqual(len(out), 20)
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Invalid magic bytes", response.content.decode())

    def test_app_ingress_400_incomplete_batch_block_ser_claimed_too_long(self):
        """POST with ser_len claiming more bytes than present returns 400."""
        out = bytearray(_GVL2_MAGIC)
        out.extend(self.tracker_uuid.bytes)
        out.append(_FLAG_HAS_EXTENDED)
        out.extend(struct.pack(">q", 0))
        out.append(10)
        out.extend(b"only3")
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete batch block", response.content.decode())

    def test_app_ingress_400_truncated_prov_string(self):
        """POST with prov_len pointing past end of body returns 400."""
        out = _gvl2_header(self.tracker_uuid, has_extended=True, session_start_ms=0, ser="")
        out.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        out.extend(struct.pack(">H", 0))
        out.extend(struct.pack(">ffff", 0.0, 0.0, 0.0, 0.0))
        out.extend(struct.pack(">Bb", 0, 0))
        out.extend(struct.pack(">f", 0.0))
        out.append(10)
        out.extend(b"abc")
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_400_truncated_desc_string(self):
        """POST with desc_len pointing past end of body returns 400."""
        out = _gvl2_header(self.tracker_uuid, has_extended=True, session_start_ms=0, ser="")
        out.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        out.extend(struct.pack(">H", 0))
        out.extend(struct.pack(">ffff", 0.0, 0.0, 0.0, 0.0))
        out.extend(struct.pack(">Bb", 0, 0))
        out.extend(struct.pack(">f", 0.0))
        out.append(0)
        out.extend(struct.pack(">H", 5))
        out.extend(b"ab")
        with _patch_live_track_enabled():
            response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Incomplete", response.content.decode())

    def test_app_ingress_too_many_points_rejected(self):
        """Parser rejects payloads with more than _MAX_POINTS_PER_PAYLOAD points."""
        with _patch_live_track_enabled():
            with patch(
                "extensions.live_track.src.backend.ingress_views._MAX_POINTS_PER_PAYLOAD",
                2,
            ):
                min_point = _encode_extended_point({
                    "lat": 37.0, "lon": -122.0, "timestamp": 1705312800000,
                })
                out = _gvl2_header(self.tracker_uuid, has_extended=True, session_start_ms=0, ser="")
                out.extend(min_point)
                out.extend(min_point)
                out.extend(min_point)
                response = self._ingress_post(bytes(out))
        self.assertEqual(response.status_code, 400)
        self.assertIn("Too many points", response.content.decode())

    def test_app_ingress_success_sat_zero_omitted_from_params(self):
        """When sat is 0, backend does not store 'sat' in point_params."""
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000, "sat": 0}],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertNotIn("sat", params[0])

    def test_app_ingress_success_batt_ischarging_boundaries(self):
        """batt 0/100 and ischarging false/true are stored correctly."""
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [
                {"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000, "batt": 0, "ischarging": False},
                {"lat": 37.01, "lon": -122.0, "timestamp": 1705312860000, "batt": 100, "ischarging": True},
            ],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
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
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{"lat": 37.123456, "lon": -122.654321, "timestamp": 1705312800000}],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
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
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{
                "lat": 37.0, "lon": -122.0, "timestamp": 1705312800000,
                "prov": long_prov, "desc": long_desc,
            }],
            ser=long_ser,
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
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
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
            session_start_ms=0,
            ser="",
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                response = self._ingress_post(payload)
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertNotIn("ser", params[0])

    def test_app_ingress_utf8_replacement_invalid_sequences(self):
        """Invalid UTF-8 in prov or desc is decoded with replacement and stored."""
        out = _gvl2_header(self.tracker_uuid, has_extended=True, session_start_ms=0, ser="")
        out.extend(struct.pack(">Bqff", 0, 1705312800000, 37.0, -122.0))
        out.extend(struct.pack(">H", 0))
        out.extend(struct.pack(">ffff", 0.0, 0.0, 0.0, 0.0))
        out.extend(struct.pack(">Bb", 0, 0))
        out.extend(struct.pack(">f", 0.0))
        out.append(3)
        out.extend(b"\xff\xfe\xfd")
        out.extend(struct.pack(">H", 2))
        out.extend(b"\x80\x81")
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                response = self._ingress_post(bytes(out))
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
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )
        compressed = zlib.compress(payload)
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
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

    def test_app_ingress_400_gzip_decompression_bomb_rejected(self):
        """A tiny gzip body that decompresses far past the 2MB cap is rejected with 400,
        rather than being decompressed in full (decompression-bomb protection)."""
        bomb = gzip.compress(b"\x00" * (3 * 1024 * 1024))
        with _patch_live_track_enabled():
            response = self.client.post(
                self.ingress_url,
                data=bomb,
                content_type="application/octet-stream",
                HTTP_CONTENT_ENCODING="gzip",
            )
        self.assertEqual(response.status_code, 400)
        self.assertIn("Content-Encoding", response.content.decode())
        self.assertEqual((LiveTrack.objects.get(id=self.track_id).geometry or {}).get("coordinates", []), [])

    def test_app_ingress_400_deflate_decompression_bomb_rejected(self):
        """A tiny deflate body that decompresses far past the 2MB cap is rejected with 400."""
        import zlib
        bomb = zlib.compress(b"\x00" * (3 * 1024 * 1024))
        with _patch_live_track_enabled():
            response = self.client.post(
                self.ingress_url,
                data=bomb,
                content_type="application/octet-stream",
                HTTP_CONTENT_ENCODING="deflate",
            )
        self.assertEqual(response.status_code, 400)
        self.assertIn("Content-Encoding", response.content.decode())

    def test_app_ingress_dedups_identical_points_within_payload(self):
        """Incoming payload duplicates with identical lon/lat/timestamp are inserted once."""
        duplicate_ts = 1705312800000
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [
                {"lat": 37.0, "lon": -122.0, "timestamp": duplicate_ts},
                {"lat": 37.0, "lon": -122.0, "timestamp": duplicate_ts},
                {"lat": 37.1, "lon": -121.9, "timestamp": duplicate_ts + 1000},
            ],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                response = self._ingress_post(payload)

        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        params = track.point_params or []
        self.assertEqual(len(coords), 2)
        self.assertEqual(len(params), 2)
        self.assertEqual(coords[0], [-122.0, 37.0, duplicate_ts])
        self.assertAlmostEqual(coords[1][0], -121.9, places=4)
        self.assertAlmostEqual(coords[1][1], 37.1, places=4)
        self.assertEqual(coords[1][2], duplicate_ts + 1000)

    def test_app_ingress_dedups_against_existing_geometry(self):
        """Incoming point identical to existing geometry point is skipped."""
        ts = 1705312800000
        first_payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": ts}],
        )
        second_payload = encode_gvl2_extended(
            self.tracker_uuid,
            [
                {"lat": 37.0, "lon": -122.0, "timestamp": ts},
                {"lat": 37.2, "lon": -121.8, "timestamp": ts + 2000},
            ],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                response1 = self._ingress_post(first_payload)
                response2 = self._ingress_post(second_payload)

        self.assertEqual(response1.status_code, 200)
        self.assertEqual(response2.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        params = track.point_params or []
        self.assertEqual(len(coords), 2)
        self.assertEqual(len(params), 2)
        self.assertEqual(coords[0], [-122.0, 37.0, ts])
        self.assertAlmostEqual(coords[1][0], -121.8, places=4)
        self.assertAlmostEqual(coords[1][1], 37.2, places=4)
        self.assertEqual(coords[1][2], ts + 2000)

    def test_app_ingress_duplicate_only_batch_does_not_advance_updated_at(self):
        """Retrying an already-accepted batch must not make stale GPS data look active."""
        ts = 1705312800000
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": ts}],
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                first_response = self._ingress_post(payload)
        self.assertEqual(first_response.status_code, 200)

        old_updated_at = timezone.now() - timedelta(hours=2)
        LiveTrack.objects.filter(id=self.track_id).update(updated_at=old_updated_at)

        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                duplicate_response = self._ingress_post(payload)

        self.assertEqual(duplicate_response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(coords, [[-122.0, 37.0, ts]])
        self.assertEqual(track.updated_at, old_updated_at)

    def test_app_ingress_new_point_advances_updated_at(self):
        """Only real location inserts should advance tracker activity time."""
        old_updated_at = timezone.now() - timedelta(hours=2)
        LiveTrack.objects.filter(id=self.track_id).update(updated_at=old_updated_at)
        payload = encode_gvl2_extended(
            self.tracker_uuid,
            [{"lat": 37.0, "lon": -122.0, "timestamp": 1705312800000}],
        )

        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views._ingress_rate_limiter") as mock_limiter:
                mock_limiter.enforce.return_value = None
                response = self._ingress_post(payload)

        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        self.assertGreater(track.updated_at, old_updated_at)
