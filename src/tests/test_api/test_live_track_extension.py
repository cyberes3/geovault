"""
Tests for the Live Track extension API and ingress.
"""
import base64
import json
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


class TestLiveTrackAPI(TestCase):
    """Test Live Track extension API endpoints (trackers list, create, get, update, delete, KML)."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="trackuser@example.com",
            password="testpass123",
            username="trackuser",
        )
        self.other_user = User.objects.create_user(
            email="other@example.com",
            password="otherpass123",
            username="otheruser",
        )
        self.client.force_login(self.user)

    def test_list_trackers_empty(self):
        """GET /api/extensions/live-track/trackers/ returns 200 and [] when user has no tracks."""
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIsInstance(data, list)
        self.assertEqual(len(data), 0)

    def test_list_trackers_unauthenticated(self):
        """GET trackers/ without auth returns 401."""
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/")
        self.assertEqual(response.status_code, 401)

    def test_create_track(self):
        """POST trackers/ with name (and optional color) returns 201 with id and tracker_secret."""
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "My Track"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertIn("id", data)
        self.assertIn("tracker_secret", data)
        self.assertEqual(data["name"], "My Track")
        self.assertIn("geometry", data)
        self.assertIn("point_params", data)
        self.assertEqual(data["geometry"].get("type"), "LineString")
        self.assertEqual(len(data["geometry"].get("coordinates", [])), 0)

    def test_create_track_with_color(self):
        """POST with color uses it."""
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Colored", "color": "#ff0000"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["color"], "#ff0000")

    def test_create_track_without_color_uses_default_blue(self):
        """POST without color uses default #3388ff."""
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "NoColorTrack"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["color"], "#3388ff")

    def test_create_track_name_required(self):
        """POST without name or with empty name returns 400."""
        with _patch_live_track_enabled():
            r = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({}),
                content_type="application/json",
            )
        self.assertEqual(r.status_code, 400)
        with _patch_live_track_enabled():
            r = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "   "}),
                content_type="application/json",
            )
        self.assertEqual(r.status_code, 400)

    def test_create_track_invalid_json(self):
        """POST with invalid JSON body returns 400."""
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/trackers/",
                data="not valid json",
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_create_track_duplicate_name(self):
        """POST with name that already exists for user returns 409."""
        with _patch_live_track_enabled():
            self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Only One"}),
                content_type="application/json",
            )
            response = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Only One"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 409)

    def test_list_trackers_sorted_by_name(self):
        """GET trackers/ returns tracks sorted alphabetically by name."""
        with _patch_live_track_enabled():
            for name in ["Charlie", "Alpha", "Bravo"]:
                self.client.post(
                    "/api/extensions/live-track/trackers/",
                    data=json.dumps({"name": name}),
                    content_type="application/json",
                )
            response = self.client.get("/api/extensions/live-track/trackers/")
        self.assertEqual(response.status_code, 200)
        names = [t["name"] for t in response.json()]
        self.assertEqual(names, ["Alpha", "Bravo", "Charlie"])

    def test_get_track(self):
        """GET trackers/<id>/ returns 200 with track, geometry, point_params."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Get Me"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["id"], track_id)
        self.assertEqual(data["name"], "Get Me")
        self.assertIn("geometry", data)
        self.assertIn("point_params", data)

    def test_get_track_404_other_user(self):
        """GET trackers/<id>/ for another user's track returns 404."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Mine"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/")
        self.assertEqual(response.status_code, 404)

    def test_get_track_404_not_found(self):
        """GET trackers/<id>/ with non-existent UUID returns 404."""
        with _patch_live_track_enabled():
            response = self.client.get(
                "/api/extensions/live-track/trackers/00000000-0000-0000-0000-000000000000/"
            )
        self.assertEqual(response.status_code, 404)

    def test_patch_track(self):
        """PATCH trackers/<id>/ updates name and color."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Original"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/trackers/{track_id}/",
                data=json.dumps({"name": "Updated", "color": "#00ff00"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["name"], "Updated")
        self.assertEqual(response.json()["color"], "#00ff00")

    def test_patch_track_empty_name_rejected(self):
        """PATCH with empty name returns 400."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Rename Me"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/trackers/{track_id}/",
                data=json.dumps({"name": "  "}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_patch_track_404_not_found(self):
        """PATCH trackers/<id>/ with non-existent UUID returns 404."""
        with _patch_live_track_enabled():
            response = self.client.patch(
                "/api/extensions/live-track/trackers/00000000-0000-0000-0000-000000000000/",
                data=json.dumps({"name": "New"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 404)

    def test_patch_track_invalid_json(self):
        """PATCH with invalid JSON body returns 400."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "PatchMe"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/trackers/{track_id}/",
                data="not json",
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_patch_track_409_duplicate_name(self):
        """PATCH with name that another track of same user has returns 409."""
        with _patch_live_track_enabled():
            self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "First"}),
                content_type="application/json",
            )
            create_b = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Second"}),
                content_type="application/json",
            )
        track_b_id = create_b.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/trackers/{track_b_id}/",
                data=json.dumps({"name": "First"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 409)

    def test_delete_track(self):
        """DELETE trackers/<id>/ returns 204 and removes track."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "To Delete"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.delete(f"/api/extensions/live-track/trackers/{track_id}/")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(LiveTrack.objects.filter(id=track_id).exists())

    def test_delete_track_404_not_found(self):
        """DELETE trackers/<id>/ with non-existent UUID returns 404."""
        with _patch_live_track_enabled():
            response = self.client.delete(
                "/api/extensions/live-track/trackers/00000000-0000-0000-0000-000000000000/"
            )
        self.assertEqual(response.status_code, 404)

    def test_delete_track_404_other_user(self):
        """DELETE another user's track returns 404."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Mine"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.delete(f"/api/extensions/live-track/trackers/{track_id}/")
        self.assertEqual(response.status_code, 404)
        self.assertTrue(LiveTrack.objects.filter(id=track_id).exists())

    def test_kml_download(self):
        """GET trackers/<id>/kml/ returns 200 with KML and Content-Disposition."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "KML Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/kml/")
        self.assertEqual(response.status_code, 200)
        self.assertIn("application/vnd.google-earth.kml", response.get("Content-Type", ""))
        self.assertIn("Content-Disposition", response)
        self.assertIn("attachment", response["Content-Disposition"])
        self.assertIn(b"<kml", response.content)
        self.assertIn(b"LineString", response.content)

    def test_kml_track_with_no_points(self):
        """KML for track with no points returns 200 and valid KML."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Empty"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/kml/")
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"<kml", response.content)

    def test_kml_track_with_points(self):
        """KML for track with points includes coordinates in LineString."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "WithPoints"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({"lat": 37.5, "lon": -122.5, "timestamp": 1705312800}),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/kml/")
        self.assertEqual(response.status_code, 200)
        self.assertIn(b"-122.5,37.5,0", response.content)

    def test_list_returns_last_position_when_has_points(self):
        """GET trackers/ includes last_position and last_timestamp_ms when track has points."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "WithPoint"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({"lat": 38.0, "lon": -121.0, "timestamp": 1705312800}),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/")
        self.assertEqual(response.status_code, 200)
        tracks = response.json()
        self.assertEqual(len(tracks), 1)
        self.assertIsNotNone(tracks[0].get("last_position"))
        self.assertEqual(tracks[0]["last_position"]["lat"], 38.0)
        self.assertEqual(tracks[0]["last_position"]["lon"], -121.0)
        self.assertIsNotNone(tracks[0].get("last_timestamp_ms"))

    def test_kml_404_other_user(self):
        """GET kml/ for another user's track returns 404."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Mine"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/kml/")
        self.assertEqual(response.status_code, 404)


class TestLiveTrackIngress(TestCase):
    """Test ingress endpoint (POST only, Basic Auth, body validation, rate limit)."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="ingress@example.com",
            password="testpass123",
            username="ingressuser",
        )
        self.client.force_login(self.user)
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Ingress Track"}),
                content_type="application/json",
            )
        self.track_id = create_resp.json()["id"]
        self.tracker_secret = create_resp.json()["tracker_secret"]
        self.ingress_url = "/api/extensions/live-track/ingress/"
        self.auth_header = _basic_auth_header("ingress@example.com", self.tracker_secret)

    def _ingress_post(self, data=None, auth_header=None, content_type="application/json"):
        if data is None:
            data = {"lat": 37.0, "lon": -122.0, "timestamp": 1705312800}
        body = json.dumps(data) if content_type == "application/json" else "&".join(f"{k}={v}" for k, v in data.items())
        headers = {}
        if auth_header:
            headers["HTTP_AUTHORIZATION"] = auth_header
        if content_type == "application/json":
            return self.client.post(self.ingress_url, data=body, content_type=content_type, **headers)
        return self.client.post(self.ingress_url, data=body, content_type=content_type, **headers)

    def test_ingress_method_post_only(self):
        """Only POST allowed; GET returns 405."""
        with _patch_live_track_enabled():
            response = self.client.get(self.ingress_url, HTTP_AUTHORIZATION=self.auth_header)
        self.assertEqual(response.status_code, 405)

    def test_ingress_success(self):
        """POST with valid Basic Auth and lat, lon, time returns 200 and appends point."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(auth_header=self.auth_header)
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        geom = track.geometry or {}
        coords = geom.get("coordinates", [])
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0][0], -122.0)
        self.assertEqual(coords[0][1], 37.0)
        self.assertEqual(len(track.point_params or []), 1)

    def test_ingress_success_form_body(self):
        """POST with application/x-www-form-urlencoded body works."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self.client.post(
                    self.ingress_url,
                    data="lat=37.5&lon=-122.5&timestamp=1705312800",
                    content_type="application/x-www-form-urlencoded",
                    HTTP_AUTHORIZATION=self.auth_header,
                )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0][1], 37.5)
        self.assertEqual(coords[0][0], -122.5)

    def test_ingress_optional_params_stored(self):
        """Optional params (alt, acc, spd_kph) are stored in point_params."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(
                    data={"lat": 38.0, "lon": -121.0, "timestamp": 1705312800, "alt": 100.5, "acc": 10, "spd_kph": 5.0},
                    auth_header=self.auth_header,
                )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertEqual(params[0].get("alt"), 100.5)
        self.assertEqual(params[0].get("acc"), 10)
        self.assertEqual(params[0].get("spd_kph"), 5.0)

    def test_ingress_401_missing_auth(self):
        """POST without Basic Auth returns 401."""
        with _patch_live_track_enabled():
            response = self._ingress_post(auth_header=None)
        self.assertEqual(response.status_code, 401)

    def test_ingress_401_wrong_password(self):
        """POST with wrong tracker password returns 401."""
        wrong_header = _basic_auth_header("ingress@example.com", "wrong-secret")
        with _patch_live_track_enabled():
            response = self._ingress_post(auth_header=wrong_header)
        self.assertEqual(response.status_code, 401)

    def test_ingress_401_unknown_user(self):
        """POST with unknown username returns 401."""
        wrong_header = _basic_auth_header("unknown@example.com", self.tracker_secret)
        with _patch_live_track_enabled():
            response = self._ingress_post(auth_header=wrong_header)
        self.assertEqual(response.status_code, 401)

    def test_ingress_400_missing_lat(self):
        """POST with missing lat returns 400."""
        with _patch_live_track_enabled():
            response = self._ingress_post(
                data={"lon": -122.0, "timestamp": 1705312800},
                auth_header=self.auth_header,
            )
        self.assertEqual(response.status_code, 400)

    def test_ingress_400_missing_lon(self):
        """POST with missing lon returns 400."""
        with _patch_live_track_enabled():
            response = self._ingress_post(
                data={"lat": 37.0, "timestamp": 1705312800},
                auth_header=self.auth_header,
            )
        self.assertEqual(response.status_code, 400)

    def test_ingress_timestamp_optional_uses_server_time(self):
        """POST without timestamp is accepted; server uses current wall clock for the point."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(
                    data={"lat": 37.0, "lon": -122.0},
                    auth_header=self.auth_header,
                )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0][0], -122.0)
        self.assertEqual(coords[0][1], 37.0)
        self.assertIsInstance(coords[0][2], (int, float))
        self.assertGreater(coords[0][2], 0)

    def test_ingress_400_invalid_json(self):
        """POST with invalid JSON body returns 400."""
        with _patch_live_track_enabled():
            response = self.client.post(
                self.ingress_url,
                data="not valid json",
                content_type="application/json",
                HTTP_AUTHORIZATION=self.auth_header,
            )
        self.assertEqual(response.status_code, 400)

    def test_ingress_400_timestamp_equal_to_last_point(self):
        """POST with timestamp equal to last point returns 400 (must be strictly after)."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self._ingress_post(
                    data={"lat": 37.0, "lon": -122.0, "timestamp": 1705312800},
                    auth_header=self.auth_header,
                )
                response = self._ingress_post(
                    data={"lat": 37.1, "lon": -121.9, "timestamp": 1705312800},
                    auth_header=self.auth_header,
                )
        self.assertEqual(response.status_code, 400)

    def test_ingress_400_timestamp_older_than_last_point(self):
        """POST with timestamp not after last point returns 400."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self._ingress_post(
                    data={"lat": 37.0, "lon": -122.0, "timestamp": 1705312800},
                    auth_header=self.auth_header,
                )
                response = self._ingress_post(
                    data={"lat": 37.1, "lon": -121.9, "timestamp": 1705309200},
                    auth_header=self.auth_header,
                )
        self.assertEqual(response.status_code, 400)

    def test_ingress_unknown_key_silently_dropped(self):
        """POST with body key not in allowed list is accepted; unknown key is dropped."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(
                    data={"lat": 37.0, "lon": -122.0, "timestamp": 1705312800, "foo": "bar"},
                    auth_header=self.auth_header,
                )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertNotIn("foo", params[0])

    def test_ingress_disallowed_param_silently_dropped(self):
        """POST with profile (disallowed) is accepted; profile is dropped and not stored."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(
                    data={"lat": 37.0, "lon": -122.0, "timestamp": 1705312800, "profile": "x"},
                    auth_header=self.auth_header,
                )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertNotIn("profile", params[0])

    def test_ingress_400_invalid_lat_type(self):
        """POST with lat=abc returns 400."""
        with _patch_live_track_enabled():
            response = self._ingress_post(
                data={"lat": "abc", "lon": -122.0, "timestamp": 1705312800},
                auth_header=self.auth_header,
            )
        self.assertEqual(response.status_code, 400)

    def test_ingress_rate_limit_429(self):
        """Two POSTs for same track within same second: first 200, second 429."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.redis.RedisCache"}}
                response1 = self._ingress_post(auth_header=self.auth_header)
                response2 = self._ingress_post(auth_header=self.auth_header)
        self.assertEqual(response1.status_code, 200)
        self.assertEqual(response2.status_code, 429)

    def test_ingress_max_points_trimmed(self):
        """Ingress trims to max_points (config); oldest points removed."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.get_config_loader") as mock_cfg:
                mock_cfg.return_value.get_int.return_value = 2
                with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                    mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                    for i in range(4):
                        self._ingress_post(
                            data={
                                "lat": 37.0 + i * 0.01,
                                "lon": -122.0,
                                "timestamp": 1705312800 + i * 60,
                            },
                            auth_header=self.auth_header,
                        )
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        params = track.point_params or []
        self.assertEqual(len(coords), 2)
        self.assertEqual(len(params), 2)

    def test_ingress_timestamp_stored_as_unix_ms(self):
        """timestamp (epoch sec) yields coordinate third value as Unix ms."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self._ingress_post(
                    data={"lat": 39.0, "lon": -120.0, "timestamp": 1705312800},
                    auth_header=self.auth_header,
                )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 1)
        self.assertIsInstance(coords[0][2], (int, float))
        self.assertEqual(coords[0][2], 1705312800000)


class TestLiveTrackAppIngress(TestCase):
    """Test app-ingress stub."""

    def test_app_ingress_501(self):
        """POST app-ingress/ returns 501 Not Implemented."""
        User = get_user_model()
        user = User.objects.create_user(email="u@example.com", password="p", username="u")
        self.client.force_login(user)
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/app-ingress/",
                data=json.dumps({"lat": 37.0, "lon": -122.0, "timestamp": 1705312800}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 501)
