"""
Tests for the Live Track extension API and ingress.
"""
import base64
import json
from unittest.mock import MagicMock, patch

from django.contrib.auth import get_user_model
from django.test import TestCase

from extensions.live_track.src.backend.models import LiveTrack
from extensions.live_track.src.backend.validation import get_ingress_body_template


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
        """GET trackers/<id>/ returns 200 with metadata and latest params only (no geometry)."""
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
        self.assertNotIn("geometry", data)
        self.assertIn("point_params", data)
        self.assertIn("last_point", data)
        self.assertIn("created_at", data)
        self.assertIn("updated_at", data)

    def test_get_track_geometry(self):
        """GET trackers/<id>/geometry/ returns 200 with full geometry and point_params."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Full Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/geometry/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["id"], track_id)
        self.assertEqual(data["name"], "Full Track")
        self.assertIn("geometry", data)
        self.assertEqual(data["geometry"].get("type"), "LineString")
        self.assertIn("coordinates", data["geometry"])
        self.assertIn("point_params", data)
        self.assertNotIn("tracker_secret", data)

    def test_get_track_coordinates_geometry(self):
        """GET trackers/<id>/coordinates/ returns latest 100 coordinates with correct geometry."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Coords Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                for i in range(3):
                    self.client.post(
                        "/api/extensions/live-track/ingress/",
                        data=json.dumps({
                            "lat": 37.0 + i * 0.1,
                            "lon": -122.0 - i * 0.1,
                            "timestamp": 1705312800 + i,
                        }),
                        content_type="application/json",
                        HTTP_AUTHORIZATION=auth,
                    )
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/coordinates/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("coordinates", data)
        self.assertIn("point_params", data)
        coords = data["coordinates"]
        self.assertEqual(len(coords), 3)
        self.assertEqual(coords[0], [-122.0, 37.0, 1705312800000])
        self.assertEqual(coords[1], [-122.1, 37.1, 1705312801000])
        self.assertEqual(coords[2], [-122.2, 37.2, 1705312802000])

    def test_get_track_coordinates_params(self):
        """GET trackers/<id>/coordinates/ returns point_params aligned with coordinates."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Params Track"}),
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
                    data=json.dumps({
                        "lat": 38.0,
                        "lon": -121.0,
                        "timestamp": 1705312800,
                        "alt": 100.5,
                        "acc": 10,
                    }),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({
                        "lat": 39.0,
                        "lon": -120.0,
                        "timestamp": 1705312801,
                        "alt": 200.0,
                        "spd_kph": 5.0,
                    }),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/coordinates/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        coords = data["coordinates"]
        params = data.get("point_params") or []
        self.assertEqual(len(params), len(coords), "point_params length must match coordinates")
        self.assertEqual(len(coords), 2)
        self.assertEqual(params[0].get("alt"), 100.5)
        self.assertEqual(params[0].get("acc"), 10)
        self.assertEqual(params[1].get("alt"), 200.0)
        self.assertEqual(params[1].get("spd_kph"), 5.0)

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

    def test_clear_history(self):
        """POST trackers/<id>/clear-history/ keeps only the latest point."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Clear Me"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        track.geometry = {
            "type": "LineString",
            "coordinates": [
                [-122.0, 37.0, 1705312800000],
                [-122.1, 37.1, 1705312801000],
                [-122.2, 37.2, 1705312802000],
            ],
        }
        track.point_params = [{"speed": 1}, {"speed": 2}, {"speed": 3}]
        track.save(update_fields=["geometry", "point_params", "updated_at"])
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/clear-history/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["id"], track_id)
        self.assertIn("last_point", data)
        self.assertEqual(data["last_point"], [-122.2, 37.2, 1705312802000])
        track.refresh_from_db()
        coords = (track.geometry or {}).get("coordinates", [])
        params = track.point_params or []
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0], [-122.2, 37.2, 1705312802000])
        self.assertEqual(len(params), 1)
        self.assertEqual(params[0], {"speed": 3})

    def test_clear_history_empty_track(self):
        """POST clear-history/ on track with no points leaves it empty."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Empty Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/clear-history/"
            )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=track_id)
        self.assertEqual((track.geometry or {}).get("coordinates", []), [])
        self.assertEqual(track.point_params or [], [])

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

    def test_list_returns_geometry_last_point_not_deduped_fields(self):
        """GET trackers/ returns geometry with coordinates; list omits last_position, last_timestamp_ms, tracker_secret."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "WithPoint"}),
                content_type="application/json",
            )
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
        coords = tracks[0].get("geometry", {}).get("coordinates", [])
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0][0], -121.0)
        self.assertEqual(coords[0][1], 38.0)
        self.assertEqual(coords[0][2], 1705312800000)
        self.assertNotIn("last_position", tracks[0])
        self.assertNotIn("last_timestamp_ms", tracks[0])
        self.assertNotIn("tracker_secret", tracks[0])

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

    def test_profile_properties_owner_returns_200_and_content(self):
        """GET profile.properties as owner returns 200 and body contains all required keys."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "ProfileTrack"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/profile.properties"
            )
        self.assertEqual(response.status_code, 200)
        self.assertIn("application/x-gpslogger-properties", response.get("Content-Type", ""))
        body = response.content.decode("utf-8")
        self.assertIn("current_profile_name=GeoVault ProfileTrack", body)
        self.assertIn("log_customurl_enabled=true", body)
        self.assertIn("log_customurl_url=", body)
        self.assertIn("/ingress/", body)
        expected_body_template = get_ingress_body_template()
        self.assertIn(f"log_customurl_body={expected_body_template}", body)
        self.assertIn("log_customurl_method=POST", body)
        self.assertIn("log_customurl_basicauth_username=", body)
        self.assertIn(self.user.email, body)
        self.assertIn("log_customurl_basicauth_password=", body)
        self.assertIn(tracker_secret, body)
        self.assertIn("log_customurl_discard_offline_locations_enabled=true", body)
        self.assertIn("autocustomurl_enabled=true", body)
        self.assertIn("hide_notification_from_lock_screen=true", body)
        self.assertIn("log_satellite_locations=true", body)
        self.assertIn("log_network_locations=true", body)
        self.assertIn("new_file_creation=everystart", body)
        self.assertIn("time_before_logging=15", body)
        self.assertIn("distance_before_logging=10", body)
        self.assertNotIn("only_log_if_significant_motion", body)
        self.assertIn("Content-Disposition", response)

    def test_profile_properties_404_other_user(self):
        """GET profile.properties for another user's track returns 404 and does not leak secret."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Mine"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/profile.properties"
            )
        self.assertEqual(response.status_code, 404)
        self.assertNotIn(tracker_secret, response.content.decode("utf-8"))

    def test_profile_properties_200_unauthenticated_with_correct_secret(self):
        """GET profile.properties with correct ?secret= returns 200 without session (QR / From URL flow)."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "SecretTrack"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/profile.properties",
                {"secret": tracker_secret},
            )
        self.assertEqual(response.status_code, 200)
        body = response.content.decode("utf-8")
        self.assertIn("log_customurl_enabled=true", body)

    def test_profile_properties_404_unauthenticated_wrong_or_missing_secret(self):
        """GET profile.properties without secret or with wrong secret returns 404."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "NoLeak"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        self.client.logout()
        with _patch_live_track_enabled():
            r_no_secret = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/profile.properties"
            )
            r_wrong_secret = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/profile.properties",
                {"secret": "wrong"},
            )
        self.assertEqual(r_no_secret.status_code, 404)
        self.assertEqual(r_wrong_secret.status_code, 404)

    def test_profile_properties_404_not_found(self):
        """GET profile.properties with non-existent track id returns 404."""
        with _patch_live_track_enabled():
            response = self.client.get(
                "/api/extensions/live-track/trackers/00000000-0000-0000-0000-000000000000/profile.properties"
            )
        self.assertEqual(response.status_code, 404)

    def test_profile_properties_ingress_url_absolute(self):
        """Profile body log_customurl_url is an absolute URL containing /ingress/."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "UrlTrack"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/profile.properties"
            )
        self.assertEqual(response.status_code, 200)
        body = response.content.decode("utf-8")
        for line in body.splitlines():
            if line.startswith("log_customurl_url="):
                url = line.split("=", 1)[1].strip()
                self.assertTrue(
                    url.startswith("http://") or url.startswith("https://"),
                    f"Expected absolute URL, got {url!r}",
                )
                self.assertIn("/ingress/", url)
                break
        else:
            self.fail("log_customurl_url not found in profile body")

    def test_profile_properties_optional_accuracy(self):
        """Profile body contains accuracy_before_logging=50."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "AccTrack"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/profile.properties"
            )
        self.assertEqual(response.status_code, 200)
        self.assertIn("accuracy_before_logging=50", response.content.decode("utf-8"))

    def test_profile_properties_method_get_only(self):
        """POST to profile.properties returns 405."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "PostTrack"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/profile.properties",
                data=json.dumps({}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 405)


class TestTrackerCheck(TestCase):
    """Test POST tracker-check/ (session, API key, OAuth); validate tracker ID and optional password."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="checkuser@example.com",
            password="testpass123",
            username="checkuser",
        )
        self.other_user = User.objects.create_user(
            email="othercheck@example.com",
            password="otherpass123",
            username="othercheck",
        )
        self.client.force_login(self.user)

    def _post_check(self, tracker_id, password=None, **kwargs):
        body = {"tracker_id": tracker_id}
        if password is not None:
            body["password"] = password
        return self.client.post(
            "/api/extensions/live-track/tracker-check/",
            data=json.dumps(body),
            content_type="application/json",
            **kwargs,
        )

    def test_tracker_check_tracker_id_only_valid_when_owned(self):
        """POST tracker-check/ with only tracker_id (no password): returns valid=True and name when user owns the tracker."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "My Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self._post_check(track_id)  # no password
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data["valid"])
        self.assertEqual(data["name"], "My Track")

    def test_tracker_check_tracker_id_and_password_valid_when_both_match(self):
        """POST tracker-check/ with tracker_id and password: returns valid=True when both match the tracker."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Secret Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        secret = create_resp.json()["tracker_secret"]
        with _patch_live_track_enabled():
            response = self._post_check(track_id, password=secret)
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data["valid"])
        self.assertEqual(data["name"], "Secret Track")

    def test_tracker_check_invalid_wrong_password(self):
        """POST tracker-check/ with correct tracker_id but wrong password returns valid=False."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self._post_check(track_id, password="wrong-secret")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertFalse(data["valid"])
        self.assertIsNone(data.get("name"))

    def test_tracker_check_invalid_other_user_tracker(self):
        """POST tracker-check/ with another user's tracker_id returns valid=False."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Mine"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self._post_check(track_id)
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertFalse(data["valid"])

    def test_tracker_check_invalid_unknown_tracker_id(self):
        """POST tracker-check/ with non-existent tracker_id returns valid=False."""
        with _patch_live_track_enabled():
            response = self._post_check("00000000-0000-0000-0000-000000000000")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertFalse(data["valid"])

    def test_tracker_check_invalid_tracker_id_returns_400(self):
        """POST tracker-check/ with malformed tracker_id returns 400."""
        with _patch_live_track_enabled():
            response = self._post_check("not-a-uuid")
        self.assertEqual(response.status_code, 400)

    def test_tracker_check_missing_tracker_id_returns_400(self):
        """POST tracker-check/ without tracker_id returns 400."""
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/tracker-check/",
                data=json.dumps({}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_tracker_check_unauthenticated_returns_401(self):
        """POST tracker-check/ without auth returns 401."""
        self.client.logout()
        with _patch_live_track_enabled():
            response = self._post_check("00000000-0000-0000-0000-000000000000")
        self.assertEqual(response.status_code, 401)

    def test_tracker_check_with_api_key_tracker_id_only(self):
        """POST tracker-check/ with API key and only tracker_id returns valid when tracker belongs to key owner."""
        from users.api_keys import create_user_api_key

        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "API Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        key_obj, raw_key = create_user_api_key(self.user, "Check Key")
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/tracker-check/",
                data=json.dumps({"tracker_id": track_id}),
                content_type="application/json",
                HTTP_AUTHORIZATION=f"Bearer {raw_key}",
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data["valid"])
        self.assertEqual(data["name"], "API Track")

    def test_tracker_check_with_api_key_tracker_id_and_password(self):
        """POST tracker-check/ with API key, tracker_id and password returns valid when both match."""
        from users.api_keys import create_user_api_key

        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "API Secret Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        secret = create_resp.json()["tracker_secret"]
        key_obj, raw_key = create_user_api_key(self.user, "Check Key")
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/tracker-check/",
                data=json.dumps({"tracker_id": track_id, "password": secret}),
                content_type="application/json",
                HTTP_AUTHORIZATION=f"Bearer {raw_key}",
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertTrue(data["valid"])
        self.assertEqual(data["name"], "API Secret Track")

    def test_tracker_check_get_not_allowed(self):
        """GET tracker-check/ returns 405."""
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/tracker-check/")
        self.assertEqual(response.status_code, 405)


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

    def test_ingress_same_timestamp_inserted_after(self):
        """POST with timestamp equal to last point is accepted; point is inserted after (same ts)."""
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
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 2)
        self.assertEqual(coords[0][2], 1705312800000)
        self.assertEqual(coords[1][2], 1705312800000)
        self.assertEqual(coords[0][:2], [-122.0, 37.0])
        self.assertEqual(coords[1][:2], [-121.9, 37.1])

    def test_ingress_older_timestamp_inserted_in_order(self):
        """POST with timestamp older than last is accepted; point is inserted at correct index."""
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
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 2)
        self.assertEqual(coords[0][2], 1705309200000)
        self.assertEqual(coords[1][2], 1705312800000)

    def test_ingress_insert_in_middle(self):
        """Out-of-order: A (ts=100), B (ts=300), C (ts=200) -> order A, C, B."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self._ingress_post(
                    data={"lat": 37.0, "lon": -122.0, "timestamp": 100},
                    auth_header=self.auth_header,
                )
                self._ingress_post(
                    data={"lat": 38.0, "lon": -121.0, "timestamp": 300},
                    auth_header=self.auth_header,
                )
                response = self._ingress_post(
                    data={"lat": 37.5, "lon": -121.5, "timestamp": 200, "alt": 50},
                    auth_header=self.auth_header,
                )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        params = track.point_params or []
        self.assertEqual(len(coords), 3)
        self.assertEqual(len(params), 3)
        self.assertEqual(coords[0][2], 100000)
        self.assertEqual(coords[1][2], 200000)
        self.assertEqual(coords[2][2], 300000)
        self.assertEqual(coords[1][:2], [-121.5, 37.5])
        self.assertEqual(params[1].get("alt"), 50)

    def test_ingress_insert_at_start(self):
        """Out-of-order: A (ts=200), B (ts=100) -> order B, A."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self._ingress_post(
                    data={"lat": 37.0, "lon": -122.0, "timestamp": 200},
                    auth_header=self.auth_header,
                )
                response = self._ingress_post(
                    data={"lat": 36.0, "lon": -123.0, "timestamp": 100},
                    auth_header=self.auth_header,
                )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 2)
        self.assertEqual(coords[0][2], 100000)
        self.assertEqual(coords[1][2], 200000)

    def test_ingress_multiple_out_of_order(self):
        """Send timestamps 500, 100, 300, 200, 400 -> final order 100, 200, 300, 400, 500."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                for ts in (500, 100, 300, 200, 400):
                    response = self._ingress_post(
                        data={"lat": 37.0, "lon": -122.0, "timestamp": ts},
                        auth_header=self.auth_header,
                    )
                    self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 5)
        self.assertEqual([c[2] for c in coords], [100000, 200000, 300000, 400000, 500000])

    def test_ingress_trim_after_insert_at_start(self):
        """With max_points=2, send A(200), B(300), C(100) -> order [C,A,B]; trim drops oldest C -> [A, B] remain."""
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.get_config_loader") as mock_cfg:
                mock_cfg.return_value.get_int.return_value = 2
                with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                    mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                    self._ingress_post(
                        data={"lat": 37.0, "lon": -122.0, "timestamp": 200},
                        auth_header=self.auth_header,
                    )
                    self._ingress_post(
                        data={"lat": 38.0, "lon": -121.0, "timestamp": 300},
                        auth_header=self.auth_header,
                    )
                    response = self._ingress_post(
                        data={"lat": 36.0, "lon": -123.0, "timestamp": 100},
                        auth_header=self.auth_header,
                    )
        self.assertEqual(response.status_code, 200)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        params = track.point_params or []
        self.assertEqual(len(coords), 2)
        self.assertEqual(len(params), 2)
        self.assertEqual(coords[0][2], 200000)
        self.assertEqual(coords[1][2], 300000)

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
    """Test app-ingress requires auth."""

    def test_app_ingress_401_unauthenticated(self):
        """POST app-ingress/ without auth returns 401."""
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/app-ingress/",
                data=b"",
                content_type="application/octet-stream",
            )
        self.assertEqual(response.status_code, 401)


class TestBroadcastTrackUpdated(TestCase):
    """Test that broadcast_track_updated sends to both realtime and live_track channel groups."""

    def test_broadcast_track_updated_sends_to_realtime_and_live_track_groups(self):
        from extensions.live_track.src.backend.helpers import broadcast_track_updated

        sent = []

        async def mock_group_send(group, message):
            sent.append((group, message))

        mock_layer = MagicMock()
        mock_layer.group_send = mock_group_send

        with patch("extensions.live_track.src.backend.helpers.get_channel_layer", return_value=mock_layer):
            broadcast_track_updated(
                user_id=42,
                track_id="track-123",
                point=[10.0, 45.0, 1700000000000],
                props={"accuracy": 10},
                index=0,
            )

        self.assertEqual(len(sent), 2, "group_send should be called twice (realtime and live_track)")
        groups = {s[0] for s in sent}
        self.assertIn("realtime_42", groups)
        self.assertIn("live_track_42", groups)
        for _group, message in sent:
            self.assertEqual(message["type"], "live_track_track_updated")
            self.assertEqual(message["data"]["track_id"], "track-123")
            self.assertEqual(message["data"]["point"], [10.0, 45.0, 1700000000000])
            self.assertEqual(message["data"]["index"], 0)
