"""
Tests that mimic the Hauk Android/iOS client: form-encoded POSTs to api/create.php,
api/post.php, api/stop.php, and stub endpoints. Assert Hauk-style newline responses
and that points end up on the track.
"""
from urllib.parse import urlencode

from django.contrib.auth import get_user_model
from django.test import TestCase

from extensions.live_track.src.backend.models import LiveTrack

from test_api.test_live_track_extension import _patch_live_track_enabled


def _hauk_create_form(usr: str, pwd: str, dur: int = 60, interval: int = 5, mod: str = "0") -> dict:
    """Form body as Hauk client sends (solo share)."""
    return {
        "usr": usr,
        "pwd": pwd,
        "dur": str(dur),
        "int": str(interval),
        "mod": mod,
    }


def _hauk_post_form(sid: str, lat: float, lon: float, time_sec: float) -> dict:
    """Form body for location update."""
    return {
        "sid": sid,
        "lat": str(lat),
        "lon": str(lon),
        "time": str(time_sec),
    }


def _hauk_stop_form(sid: str) -> dict:
    return {"sid": sid}


def _parse_hauk_lines(response) -> list[str]:
    """Parse Hauk newline response into lines."""
    text = response.content.decode("utf-8").rstrip("\n")
    return text.split("\n") if text else []


class TestHaukClientCreatePostStop(TestCase):
    """Mimic Hauk client: create session, post locations, stop session."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="haukuser@example.com",
            password="testpass123",
            username="haukuser",
        )
        self.client.force_login(self.user)

    def _create_track_with_hauk_password(self, name: str = "Hauk Track"):
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/trackers/",
                data={"name": name},
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 201, response.content)
        data = response.json()
        track_id = data["id"]
        track = LiveTrack.objects.get(id=track_id)
        hauk_password = track.hauk_password
        self.assertTrue(hauk_password, "New track must have hauk_password")
        return track_id, hauk_password

    def test_hauk_create_returns_ok_sid_view_url_share_id(self):
        """POST api/create.php with usr/pwd returns OK, sid, view_url, share_id (solo)."""
        _, hauk_password = self._create_track_with_hauk_password()
        form = _hauk_create_form(self.user.email, hauk_password, dur=120, interval=10)
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/api/create.php",
                data=urlencode(form),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(response.status_code, 200, response.content)
        self.assertEqual(response.get("X-Hauk-Version"), "1.2")
        lines = _parse_hauk_lines(response)
        self.assertGreaterEqual(len(lines), 4, lines)
        self.assertEqual(lines[0], "OK")
        sid = lines[1]
        view_url = lines[2]
        share_id = lines[3]
        self.assertIsNotNone(sid)
        self.assertIn("/extensions/live-track/share", view_url or "")
        self.assertIn(share_id, view_url or "")
        self.assertEqual(len(share_id), 36)  # UUID

    def test_hauk_create_wrong_password_returns_401(self):
        """POST api/create.php with wrong pwd returns 401 and 'Incorrect password'."""
        self._create_track_with_hauk_password()
        form = _hauk_create_form(self.user.email, "wrong-password-99", dur=60, interval=5)
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/api/create.php",
                data=urlencode(form),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(response.status_code, 401)
        lines = _parse_hauk_lines(response)
        self.assertEqual(lines[0], "Incorrect password")

    def test_hauk_create_wrong_username_returns_401(self):
        """POST api/create.php with unknown email returns 401."""
        self._create_track_with_hauk_password()
        form = _hauk_create_form("nobody@example.com", "some-password-1234", dur=60, interval=5)
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/api/create.php",
                data=urlencode(form),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(response.status_code, 401)

    def test_hauk_post_location_updates_track(self):
        """POST api/post.php with valid sid appends point to track (Hauk client flow)."""
        track_id, hauk_password = self._create_track_with_hauk_password()
        form = _hauk_create_form(self.user.email, hauk_password, dur=300, interval=5)
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/api/create.php",
                data=urlencode(form),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(create_resp.status_code, 200, create_resp.content.decode())
        lines = _parse_hauk_lines(create_resp)
        sid = lines[1]

        # Post two locations as Hauk would (time in Unix seconds)
        with _patch_live_track_enabled():
            post_resp1 = self.client.post(
                "/api/extensions/live-track/api/post.php",
                data=urlencode(_hauk_post_form(sid, 52.52, 13.405, 1705312800.0)),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(post_resp1.status_code, 200)
        self.assertEqual(_parse_hauk_lines(post_resp1)[0], "OK")

        with _patch_live_track_enabled():
            post_resp2 = self.client.post(
                "/api/extensions/live-track/api/post.php",
                data=urlencode(_hauk_post_form(sid, 52.53, 13.41, 1705312805.0)),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(post_resp2.status_code, 200)

        # Verify track has both points (coordinates are [lon, lat, timestamp_ms])
        with _patch_live_track_enabled():
            coords_resp = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/coordinates/"
            )
        self.assertEqual(coords_resp.status_code, 200)
        coords = coords_resp.json().get("coordinates", [])
        self.assertEqual(len(coords), 2)
        self.assertEqual(coords[0], [13.405, 52.52, 1705312800000])
        self.assertEqual(coords[1], [13.41, 52.53, 1705312805000])

    def test_hauk_stop_invalidates_session(self):
        """After POST api/stop.php, post.php with same sid returns Session expired."""
        track_id, hauk_password = self._create_track_with_hauk_password()
        form = _hauk_create_form(self.user.email, hauk_password, dur=300, interval=5)
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/api/create.php",
                data=urlencode(form),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(create_resp.status_code, 200, create_resp.content.decode())
        sid = _parse_hauk_lines(create_resp)[1]

        with _patch_live_track_enabled():
            stop_resp = self.client.post(
                "/api/extensions/live-track/api/stop.php",
                data=urlencode(_hauk_stop_form(sid)),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(stop_resp.status_code, 200)
        self.assertEqual(_parse_hauk_lines(stop_resp)[0], "OK")

        with _patch_live_track_enabled():
            post_after_stop = self.client.post(
                "/api/extensions/live-track/api/post.php",
                data=urlencode(_hauk_post_form(sid, 52.0, 13.0, 1705312900.0)),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(post_after_stop.status_code, 400)
        lines = _parse_hauk_lines(post_after_stop)
        self.assertEqual(lines[0], "Session expired")

    def test_hauk_post_without_sid_returns_400(self):
        """POST api/post.php without sid returns 400."""
        self._create_track_with_hauk_password()
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/api/post.php",
                data=urlencode({"lat": "52.52", "lon": "13.405", "time": "1705312800"}),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(response.status_code, 400)
        self.assertIn("Session ID missing", response.content.decode())

    def test_hauk_stub_adopt_returns_ok(self):
        """POST api/adopt.php (stub) returns 200 OK."""
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/api/adopt.php",
                data=urlencode({"sid": "x", "nic": "y", "aid": "z", "pin": "1234"}),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(response.status_code, 200)
        lines = _parse_hauk_lines(response)
        self.assertEqual(lines[0], "OK")

    def test_hauk_stub_new_link_returns_ok(self):
        """POST api/new-link.php (stub) returns 200 OK."""
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/api/new-link.php",
                data=urlencode({"sid": "x", "ado": "1"}),
                content_type="application/x-www-form-urlencoded",
            )
        self.assertEqual(response.status_code, 200)
        lines = _parse_hauk_lines(response)
        self.assertEqual(lines[0], "OK")

    def test_hauk_stub_fetch_returns_ok(self):
        """GET api/fetch.php (stub) returns 200 OK."""
        with _patch_live_track_enabled():
            response = self.client.get(
                "/api/extensions/live-track/api/fetch.php",
                data={"id": "some-share-id"},
            )
        self.assertEqual(response.status_code, 200)
        lines = _parse_hauk_lines(response)
        self.assertEqual(lines[0], "OK")
