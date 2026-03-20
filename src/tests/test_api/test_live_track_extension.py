"""
Tests for the Live Track extension API and ingress.
"""
import base64
import json
import time
from unittest.mock import MagicMock, patch

from django.contrib.auth import get_user_model
from django.test import TestCase

from extensions.live_track.src.backend.helpers import DEFAULT_TRACK_COLOR
from extensions.live_track.src.backend.models import (
    LiveTrack,
    LiveTrackGroup,
    LiveTrackGroupMember,
    LiveTrackGroupShare,
    LiveTrackGroupSubscription,
    LiveTrackGroupWorldShare,
    LiveTrackWorldShare,
    LiveTrackShare,
    LiveTrackSubscription,
)
from extensions.live_track.src.backend.validation import get_ingress_body_template


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
        """POST without color uses default (blue-400)."""
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "NoColorTrack"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 201)
        self.assertEqual(response.json()["color"], DEFAULT_TRACK_COLOR)

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

    def test_list_returns_is_owner_for_owned_tracks(self):
        """GET trackers/ includes is_owner true and no owner_email for owned tracks."""
        with _patch_live_track_enabled():
            self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Mine"}),
                content_type="application/json",
            )
            response = self.client.get("/api/extensions/live-track/trackers/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(len(data), 1)
        self.assertTrue(data[0]["is_owner"])
        self.assertNotIn("owner_email", data[0])

    def test_subscribe_success(self):
        """POST trackers/<id>/subscribe/ returns 201 when track is public and user does not own it."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Public Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        track.visibility = "public"
        track.save(update_fields=["visibility"])
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/subscribe/",
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 201)
        self.assertTrue(LiveTrackSubscription.objects.filter(user=self.other_user, track=track).exists())
        self.client.force_login(self.user)

    def test_subscribe_own_track_returns_400(self):
        """POST subscribe to own track returns 400."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Own"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/subscribe/",
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_unsubscribe_removes_from_list(self):
        """DELETE trackers/<id>/subscribe/ removes subscription."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Shared"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        track.visibility = "public"
        track.save(update_fields=["visibility"])
        LiveTrackSubscription.objects.create(user=self.other_user, track=track)
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.delete(f"/api/extensions/live-track/trackers/{track_id}/subscribe/")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(LiveTrackSubscription.objects.filter(user=self.other_user, track=track).exists())
        self.client.force_login(self.user)

    def test_available_to_add_returns_public_and_shared(self):
        """GET trackers/available-to-add/ returns public, shared_with_me, and public_groups sections."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Public One"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        LiveTrack.objects.filter(id=track_id).update(visibility="public")
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/available-to-add/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("public", data)
        self.assertIn("shared_with_me", data)
        self.assertIn("public_groups", data)
        self.assertIn("shared_with_me_groups", data)
        self.assertEqual(len(data["public"]), 1)
        self.assertEqual(data["public"][0]["id"], track_id)
        self.client.force_login(self.user)

    def test_post_settings_visibility_and_share_params(self):
        """POST settings can update visibility and share_params_with_recipients."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Vis"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "public",
                    "share_params_with_recipients": True,
                }),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["visibility"], "public")
        self.assertTrue(data["share_params_with_recipients"])

    def test_post_settings_shared_with_emails_sync(self):
        """POST settings with visibility=shared and shared_with_emails syncs LiveTrackShare."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Shared Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.assertTrue(LiveTrackShare.objects.filter(track=track, shared_with=self.other_user).exists())
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [],
                }),
                content_type="application/json",
            )
        self.assertFalse(LiveTrackShare.objects.filter(track=track).exists())

    def test_post_settings_shared_with_emails_invalid_emails_400(self):
        """POST settings with visibility=shared and unknown emails returns 400 with invalid_emails."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Shared Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": ["nonexistent@example.com", "another@example.com"],
                }),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertIn("invalid_emails", data)
        self.assertEqual(set(data["invalid_emails"]), {"nonexistent@example.com", "another@example.com"})

    def test_post_settings_shared_with_emails_when_not_shared_400(self):
        """POST settings with shared_with_emails when visibility is not shared returns 400."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Private Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "private",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_available_to_add_includes_shared_with_me_track(self):
        """GET trackers/available-to-add/ returns track in shared_with_me when shared with user."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Shared With Other"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/available-to-add/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("shared_with_me", data)
        shared_ids = [t["id"] for t in data["shared_with_me"]]
        self.assertIn(track_id, shared_ids)
        self.client.force_login(self.user)

    def test_leave_share_removes_share_and_subscription(self):
        """DELETE trackers/<id>/share-with-me/ removes LiveTrackShare and subscription."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "To Leave"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        LiveTrackSubscription.objects.create(user=self.other_user, track=track)
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.delete(
                f"/api/extensions/live-track/trackers/{track_id}/share-with-me/"
            )
        self.assertEqual(response.status_code, 204)
        self.assertFalse(LiveTrackShare.objects.filter(track=track, shared_with=self.other_user).exists())
        self.assertFalse(LiveTrackSubscription.objects.filter(user=self.other_user, track=track).exists())
        self.client.force_login(self.user)

    def test_leave_share_400_for_owner(self):
        """DELETE share-with-me/ as owner returns 400."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Own Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.delete(
                f"/api/extensions/live-track/trackers/{track_id}/share-with-me/"
            )
        self.assertEqual(response.status_code, 400)

    def test_leave_share_404_when_not_shared_with_you(self):
        """DELETE share-with-me/ when track is not shared with you returns 404."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Private Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.delete(
                f"/api/extensions/live-track/trackers/{track_id}/share-with-me/"
            )
        self.assertEqual(response.status_code, 404)
        self.client.force_login(self.user)

    def test_available_to_add_excludes_tracks_you_already_have(self):
        """GET trackers/available-to-add/ excludes a track from shared_with_me after subscribing."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "To Exclude"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/available-to-add/")
        self.assertEqual(response.status_code, 200)
        shared_ids = [t["id"] for t in response.json()["shared_with_me"]]
        self.assertIn(track_id, shared_ids)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/subscribe/",
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/available-to-add/")
        self.assertEqual(response.status_code, 200)
        shared_ids = [t["id"] for t in response.json()["shared_with_me"]]
        self.assertNotIn(track_id, shared_ids)
        self.client.force_login(self.user)

    def test_leave_share_removes_live_track_group_member(self):
        """DELETE share-with-me/ removes LiveTrackGroupMember for groups owned by the leaving user."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "In Group"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                    "allow_group_reshare": True,
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/subscribe/",
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "My Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
        self.assertTrue(
            LiveTrackGroupMember.objects.filter(
                group__user=self.other_user, track=track
            ).exists()
        )
        with _patch_live_track_enabled():
            response = self.client.delete(
                f"/api/extensions/live-track/trackers/{track_id}/share-with-me/"
            )
        self.assertEqual(response.status_code, 204)
        self.assertFalse(
            LiveTrackGroupMember.objects.filter(
                group__user=self.other_user, track=track
            ).exists()
        )
        self.assertFalse(LiveTrackShare.objects.filter(track=track, shared_with=self.other_user).exists())
        self.client.force_login(self.user)

    def test_subscribers_owner_gets_200_and_list(self):
        """GET trackers/<id>/subscribers/ as owner returns 200 with subscribers list; owner not in list."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Public For Subs"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        track.visibility = "public"
        track.save(update_fields=["visibility"])
        LiveTrackSubscription.objects.create(user=self.other_user, track=track)
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/subscribers/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("subscribers", data)
        subs = data["subscribers"]
        self.assertEqual(len(subs), 1)
        self.assertEqual(subs[0]["email"], self.other_user.email)
        self.assertEqual(subs[0]["id"], str(self.other_user.id))

    def test_subscribers_non_owner_gets_404(self):
        """GET trackers/<id>/subscribers/ as non-owner returns 404 (get_object_or_404_for_user excludes non-owners)."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Other Sub"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        track.visibility = "public"
        track.save(update_fields=["visibility"])
        LiveTrackSubscription.objects.create(user=self.other_user, track=track)
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/subscribers/"
            )
        self.assertEqual(response.status_code, 404)
        self.client.force_login(self.user)

    def test_available_to_add_includes_shared_with_me_groups(self):
        """GET trackers/available-to-add/ returns pending shared groups without per-track IDs until accepted."""
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Track In Group"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Shared Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/available-to-add/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("shared_with_me_groups", data)
        self.assertEqual(len(data["shared_with_me_groups"]), 1)
        self.assertEqual(data["shared_with_me_groups"][0]["id"], group_id)
        self.assertEqual(data["shared_with_me_groups"][0]["track_ids"], [])
        self.client.force_login(self.user)

    def test_available_to_add_includes_public_groups(self):
        """GET trackers/available-to-add/ returns public_groups when group is public and has addable track."""
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Public Track"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        LiveTrack.objects.filter(id=track_id).update(visibility="public")
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Public Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"visibility": "public"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/available-to-add/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("public_groups", data)
        self.assertEqual(len(data["public_groups"]), 1)
        self.assertEqual(data["public_groups"][0]["id"], group_id)
        self.assertIn(track_id, data["public_groups"][0]["track_ids"])
        self.client.force_login(self.user)

    def test_available_to_add_shared_with_me_groups_via_group_share(self):
        """GET trackers/available-to-add/ includes pending shared groups but hides group track IDs until acceptance."""
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Track In Shared Group"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Shared Via GroupShare"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/available-to-add/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("shared_with_me_groups", data)
        self.assertEqual(len(data["shared_with_me_groups"]), 1)
        self.assertEqual(data["shared_with_me_groups"][0]["id"], group_id)
        self.assertEqual(data["shared_with_me_groups"][0]["track_ids"], [])
        self.client.force_login(self.user)

    def test_available_to_add_includes_shared_group_even_when_track_already_owned(self):
        """Incoming shared groups are listed even when track_ids are not addable for user (group accept is group-level)."""
        with _patch_live_track_enabled():
            owner_track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Owned By Recipient"}),
                content_type="application/json",
            )
        owner_track_id = owner_track_resp.json()["id"]

        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Re-share To Owner"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": owner_track_id}),
                content_type="application/json",
            )
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.user.email],
                }),
                content_type="application/json",
            )

        self.client.force_login(self.user)
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/available-to-add/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        group_ids = [g["id"] for g in data.get("shared_with_me_groups", [])]
        self.assertIn(group_id, group_ids)
        self.client.force_login(self.other_user)

    def test_group_share_does_not_grant_access_before_accept(self):
        """Recipient of a shared group cannot access group tracks until explicit group accept."""
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Group Shared Track"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Invite Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )

        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            groups_response = self.client.get("/api/extensions/live-track/groups/")
            denied = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/")
            accepted = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/accept-share/",
                content_type="application/json",
            )
            allowed = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/")
        self.assertEqual(groups_response.status_code, 200)
        self.assertIn(group_id, [g["id"] for g in groups_response.json()])
        self.assertEqual(denied.status_code, 404)
        self.assertEqual(accepted.status_code, 201)
        self.assertEqual(allowed.status_code, 200)
        self.client.force_login(self.user)

    def test_group_owner_can_get_group_member_track_without_subscription(self):
        """Group owner can still fetch a track in their group when subscription row is missing."""
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Owner Missing Sub"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.user)
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Owned Group Missing Sub"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        group = LiveTrackGroup.objects.get(id=group_id)
        track = LiveTrack.objects.get(id=track_id)
        LiveTrackGroupMember.objects.get_or_create(group=group, track=track)
        LiveTrackSubscription.objects.filter(user=self.user, track_id=track_id).delete()
        with _patch_live_track_enabled():
            get_resp = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/")
        self.assertEqual(get_resp.status_code, 200)
        self.assertEqual(get_resp.json()["id"], track_id)
        self.client.force_login(self.other_user)

    def test_cross_user_reshare_group_stays_unaccepted_until_group_accept(self):
        """Cross-user re-share path (dummy-like) does not auto-accept for recipients."""
        third_user = get_user_model().objects.create_user(
            email="third@example.com",
            password="thirdpass123",
            username="thirduser",
        )
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Cross Reshare Track"}),
                content_type="application/json",
            )
        track = LiveTrack.objects.get(id=track_resp.json()["id"])
        track.visibility = "shared"
        track.settings = {**(track.settings or {}), "allow_group_reshare": True}
        track.save(update_fields=["visibility", "settings"])

        # Owner shares track with other_user.
        LiveTrackShare.objects.get_or_create(track=track, shared_with=self.other_user)

        # other_user adds shared track to their group and shares that group with third_user.
        reshare_group = LiveTrackGroup.objects.create(
            name="Cross Reshare Group",
            user=self.other_user,
            visibility="shared",
        )
        LiveTrackGroupMember.objects.get_or_create(group=reshare_group, track=track)
        LiveTrackGroupShare.objects.get_or_create(group=reshare_group, shared_with=third_user)

        self.client.force_login(third_user)
        with _patch_live_track_enabled():
            denied = self.client.get(f"/api/extensions/live-track/trackers/{track.id}/")
            accepted = self.client.post(
                f"/api/extensions/live-track/groups/{reshare_group.id}/accept-share/",
                content_type="application/json",
            )
            allowed = self.client.get(f"/api/extensions/live-track/trackers/{track.id}/")
        self.assertEqual(denied.status_code, 404)
        self.assertEqual(accepted.status_code, 201)
        self.assertEqual(allowed.status_code, 200)
        self.client.force_login(self.user)

    def test_available_to_add_excludes_public_group_if_no_addable_tracks(self):
        """Public group with only private tracks (not shared with other user) does not appear in public_groups."""
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Private Only"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Public But Private Tracks"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"visibility": "public"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/available-to-add/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        public_group_ids = [g["id"] for g in data.get("public_groups", [])]
        self.assertNotIn(group_id, public_group_ids)
        self.client.force_login(self.user)

    def test_non_owner_does_not_see_world_share_id_or_shared_with_emails(self):
        """GET trackers/<id>/ as shared-with user does not include world_share_id, world_share_url, shared_with_emails."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "World And Shared"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "world_share_enabled": True,
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/subscribe/",
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertNotIn("world_share_id", data)
        self.assertNotIn("world_share_url", data)
        self.assertNotIn("shared_with_emails", data)
        self.client.force_login(self.user)

    def test_post_settings_shared_with_emails_mixed_valid_invalid_returns_400(self):
        """POST settings with visibility=shared and mix of valid/invalid emails returns 400 with only invalid in list."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Mixed Emails"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email, "nonexistent@example.com"],
                }),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)
        data = response.json()
        self.assertIn("invalid_emails", data)
        self.assertEqual(set(data["invalid_emails"]), {"nonexistent@example.com"})
        self.assertFalse(LiveTrackShare.objects.filter(track=track).exists())

    def test_post_settings_shared_with_emails_with_visibility_public_400(self):
        """POST settings with visibility=public and shared_with_emails returns 400."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Public Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "public",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_non_owner_never_receives_ser(self):
        """GET trackers/<id>/geometry/ for subscribed track never includes 'ser' in point_params."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "WithSer"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        track.visibility = "public"
        track.share_params_with_recipients = True
        track.point_params = [{"ser": "device-123", "acc": 5.0}]
        track.save(update_fields=["visibility", "share_params_with_recipients", "point_params"])
        LiveTrackSubscription.objects.create(user=self.other_user, track=track)
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/geometry/")
        self.assertEqual(response.status_code, 200)
        params = response.json().get("point_params", [])
        self.assertTrue(all("ser" not in p for p in params), "ser must never be sent to non-owner")
        self.client.force_login(self.user)

    def test_get_track(self):
        """GET trackers/<id>/ returns 200 with metadata, latest params, and latest 100 coordinates as geometry."""
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
        self.assertEqual(data["geometry"]["type"], "LineString")
        self.assertEqual(len(data["geometry"]["coordinates"]), 0)
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

    def test_geometry_filtered_by_recent_data_window(self):
        """GET geometry with recent_data_window setting returns only points within the window."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Filtered Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        now_sec = int(time.time())
        old_ts = now_sec - 7200  # 2 hours ago
        recent_ts = now_sec - 300  # 5 minutes ago
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({"lat": 37.0, "lon": -122.0, "timestamp": old_ts}),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({"lat": 38.0, "lon": -121.0, "timestamp": recent_ts}),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "1h"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/geometry/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        coords = data["geometry"].get("coordinates", [])
        self.assertEqual(len(coords), 1, "Only the point within 1h should be returned")
        self.assertEqual(coords[0][0], -121.0)
        self.assertEqual(coords[0][1], 38.0)
        self.assertEqual(len(data.get("point_params", [])), 1)

    def test_geometry_filtered_by_recent_data_window_session_uses_latest_starttimestamp(self):
        """GET geometry with recent_data_window=session returns only points from latest starttimestamp."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Session Filtered Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        now_sec = int(time.time())
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {
                            "lat": 37.0,
                            "lon": -122.0,
                            "timestamp": now_sec - 7200,
                            "starttimestamp": now_sec - 7500,
                        }
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {
                            "lat": 37.5,
                            "lon": -121.5,
                            "timestamp": now_sec - 7100,
                            "starttimestamp": now_sec - 7500,
                        }
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {
                            "lat": 38.0,
                            "lon": -121.0,
                            "timestamp": now_sec - 300,
                            "starttimestamp": now_sec - 600,
                        }
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "session"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/geometry/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        coords = data["geometry"].get("coordinates", [])
        params = data.get("point_params", [])
        self.assertEqual(len(coords), 1, "Only the latest session point should be returned")
        self.assertEqual(len(params), 1)
        self.assertEqual(coords[0][0], -121.0)
        self.assertEqual(coords[0][1], 38.0)
        self.assertEqual(params[0].get("starttimestamp"), now_sec - 600)

    def test_geometry_filtered_by_recent_data_window_session_without_starttimestamp_falls_back_to_all(self):
        """GET geometry with recent_data_window=session returns all points when starttimestamp is missing."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Session Fallback Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        now_sec = int(time.time())
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({"lat": 37.0, "lon": -122.0, "timestamp": now_sec - 7200}),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({"lat": 38.0, "lon": -121.0, "timestamp": now_sec - 300}),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "session"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/geometry/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        coords = data["geometry"].get("coordinates", [])
        params = data.get("point_params", [])
        self.assertEqual(len(coords), 2, "Missing starttimestamp should fall back to all points")
        self.assertEqual(len(params), 2)

    def test_geometry_filtered_by_recent_data_window_session_mixed_starttimestamp_units(self):
        """GET geometry with recent_data_window=session handles mixed seconds/milliseconds starttimestamp."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Session Mixed Units"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        now_sec = int(time.time())
        older_start_sec = now_sec - 1800
        newer_start_ms = (now_sec - 600) * 1000
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {
                            "lat": 37.0,
                            "lon": -122.0,
                            "timestamp": now_sec - 1500,
                            "starttimestamp": older_start_sec,
                        }
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {
                            "lat": 38.0,
                            "lon": -121.0,
                            "timestamp": now_sec - 300,
                            "starttimestamp": newer_start_ms,
                        }
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "session"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/geometry/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        coords = data["geometry"].get("coordinates", [])
        self.assertEqual(len(coords), 1, "Latest session should be selected even with mixed units")
        self.assertEqual(coords[0][0], -121.0)
        self.assertEqual(coords[0][1], 38.0)

    def test_geometry_all_true_bypasses_recent_filter_session(self):
        """GET geometry?all=true bypasses recent_data_window=session."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Session All True"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        now_sec = int(time.time())
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {"lat": 37.0, "lon": -122.0, "timestamp": now_sec - 3600, "starttimestamp": now_sec - 4000}
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {"lat": 38.0, "lon": -121.0, "timestamp": now_sec - 300, "starttimestamp": now_sec - 600}
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "session"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/geometry/",
                {"all": "true"},
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        coords = data["geometry"].get("coordinates", [])
        params = data.get("point_params", [])
        self.assertEqual(len(coords), 2)
        self.assertEqual(len(params), 2)

    def test_geometry_filtered_by_recent_data_window_session_ignores_point_order(self):
        """GET geometry with recent_data_window=session uses latest starttimestamp even when points are out of order."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Session Out Of Order"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        now_sec = int(time.time())
        latest_session_start = now_sec - 400
        older_session_start = now_sec - 2000
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                # Newest session point arrives first.
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {"lat": 38.0, "lon": -121.0, "timestamp": now_sec - 300, "starttimestamp": latest_session_start}
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                # Older session point arrives later (out of chronological session order in storage).
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {"lat": 37.0, "lon": -122.0, "timestamp": now_sec - 100, "starttimestamp": older_session_start}
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "session"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/geometry/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        coords = data["geometry"].get("coordinates", [])
        params = data.get("point_params", [])
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0][0], -121.0)
        self.assertEqual(coords[0][1], 38.0)
        self.assertEqual(params[0].get("starttimestamp"), latest_session_start)

    def test_geometry_filtered_by_recent_data_window_session_ignores_invalid_starttimestamp_values(self):
        """GET geometry with recent_data_window=session ignores invalid starttimestamp and still picks latest valid session."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Session Invalid Starttimestamp"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        now_sec = int(time.time())
        valid_old = now_sec - 5000
        valid_new = now_sec - 800
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {"lat": 37.0, "lon": -122.0, "timestamp": now_sec - 4900, "starttimestamp": valid_old}
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {"lat": 37.5, "lon": -121.5, "timestamp": now_sec - 4700, "starttimestamp": "not-a-number"}
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps(
                        {"lat": 38.0, "lon": -121.0, "timestamp": now_sec - 300, "starttimestamp": valid_new}
                    ),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "session"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/geometry/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        coords = data["geometry"].get("coordinates", [])
        params = data.get("point_params", [])
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0][0], -121.0)
        self.assertEqual(coords[0][1], 38.0)
        self.assertEqual(params[0].get("starttimestamp"), valid_new)

    def test_metadata_filtered_by_recent_data_window(self):
        """GET trackers/ returns bbox and last_point for only points within the window."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Filtered Metadata"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        now_sec = int(time.time())
        old_ts = now_sec - 7200  # 2 hours ago
        recent_ts = now_sec - 300  # 5 minutes ago
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({"lat": 37.0, "lon": -122.0, "timestamp": old_ts}),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({"lat": 38.0, "lon": -121.0, "timestamp": recent_ts}),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "1h"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/trackers/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(len(data), 1)
        track = data[0]
        # Should only include properties of the recent (-121.0, 38.0) point, not the old (-122.0, 37.0) point
        self.assertListEqual(track["bbox"], [-121.0, 38.0, -121.0, 38.0])
        self.assertEqual(track["last_point"][0], -121.0)
        self.assertEqual(track["last_point"][1], 38.0)

    def test_geometry_all_true_bypasses_recent_filter(self):
        """GET geometry?all=true returns full geometry ignoring recent_data_window."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "AllData Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        tracker_secret = create_resp.json()["tracker_secret"]
        auth = _basic_auth_header("trackuser@example.com", tracker_secret)
        now_sec = int(time.time())
        old_ts = now_sec - 7200
        recent_ts = now_sec - 300
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({"lat": 37.0, "lon": -122.0, "timestamp": old_ts}),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
                self.client.post(
                    "/api/extensions/live-track/ingress/",
                    data=json.dumps({"lat": 38.0, "lon": -121.0, "timestamp": recent_ts}),
                    content_type="application/json",
                    HTTP_AUTHORIZATION=auth,
                )
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "1h"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/trackers/{track_id}/geometry/",
                {"all": "true"},
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        coords = data["geometry"].get("coordinates", [])
        self.assertEqual(len(coords), 2, "?all=true should return both points")
        self.assertEqual(len(data.get("point_params", [])), 2)

    def test_geometry_respects_response_size_limit_without_all_true(self):
        """GET geometry (without all=true) trims payload to configured byte limit."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Limited Geometry"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        coords = [[-122.0 + i * 0.001, 37.0 + i * 0.001, 1705312800000 + i] for i in range(20)]
        params = [{"desc": "x" * 120, "acc": 5.0} for _ in range(20)]
        track.geometry = {"type": "LineString", "coordinates": coords}
        track.point_params = params
        track.save(update_fields=["geometry", "point_params", "updated_at"])

        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.tracker_views.get_config_loader") as mock_cfg:
                mock_cfg.return_value.get_int.return_value = 1200
                response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/geometry/")
        self.assertEqual(response.status_code, 200)
        self.assertLessEqual(len(response.content), 1200)
        data = response.json()
        returned_coords = data["geometry"].get("coordinates", [])
        returned_params = data.get("point_params", [])
        self.assertLess(len(returned_coords), len(coords))
        self.assertEqual(len(returned_coords), len(returned_params))
        if returned_coords:
            self.assertEqual(returned_coords[-1][2], coords[-1][2], "Newest point should be retained")

    def test_geometry_all_true_bypasses_response_size_limit(self):
        """GET geometry?all=true returns full history regardless of configured byte limit."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Unlimited Geometry"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        coords = [[-122.0 + i * 0.001, 37.0 + i * 0.001, 1705312800000 + i] for i in range(20)]
        params = [{"desc": "x" * 120, "acc": 5.0} for _ in range(20)]
        track.geometry = {"type": "LineString", "coordinates": coords}
        track.point_params = params
        track.save(update_fields=["geometry", "point_params", "updated_at"])

        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.tracker_views.get_config_loader") as mock_cfg:
                mock_cfg.return_value.get_int.return_value = 1200
                response = self.client.get(
                    f"/api/extensions/live-track/trackers/{track_id}/geometry/",
                    {"all": "true"},
                )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        returned_coords = data["geometry"].get("coordinates", [])
        returned_params = data.get("point_params", [])
        self.assertEqual(len(returned_coords), len(coords))
        self.assertEqual(len(returned_params), len(params))

    def test_geometry_size_fit_performance_100k_points(self):
        """GET geometry with 100k points is reduced to 1MB within a practical threshold."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Perf 100k"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        base_ts = 1705312800000
        coords = [[-122.0 + i * 0.00001, 37.0 + i * 0.00001, base_ts + i] for i in range(100000)]
        params = [{"desc": "x" * 120, "acc": 5.0, "spd_kph": 3.1} for _ in range(100000)]
        track.geometry = {"type": "LineString", "coordinates": coords}
        track.point_params = params
        track.save(update_fields=["geometry", "point_params", "updated_at"])

        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.tracker_views.get_config_loader") as mock_cfg:
                mock_cfg.return_value.get_int.return_value = 1048576
                started = time.perf_counter()
                response = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/geometry/")
                elapsed = time.perf_counter() - started
        self.assertEqual(response.status_code, 200)
        self.assertLessEqual(elapsed, 5.0, f"Geometry size fit took too long: {elapsed:.3f}s")
        self.assertLessEqual(len(response.content), 1048576)
        data = response.json()
        returned_coords = data["geometry"].get("coordinates", [])
        returned_params = data.get("point_params", [])
        self.assertLess(len(returned_coords), len(coords))
        self.assertEqual(len(returned_coords), len(returned_params))
        if returned_coords:
            self.assertEqual(returned_coords[-1][2], coords[-1][2], "Newest point should be retained")

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

    def test_patch_track_not_allowed(self):
        """PATCH trackers/<id>/ is not allowed; returns 405."""
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
                data=json.dumps({"name": "Updated"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 405)

    def test_post_settings_updates_name_and_color(self):
        """POST trackers/<id>/settings/ updates name (column) and color/recent_data_window (in settings)."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Original"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"name": "Updated", "color": "#00ff00", "recent_data_window": "1h"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["name"], "Updated")
        self.assertEqual(data["color"], "#00ff00")
        self.assertEqual(data["settings"]["color"], "#00ff00")
        self.assertEqual(data["settings"]["recent_data_window"], "1h")

    def test_post_settings_recent_data_window_null_clears_setting(self):
        """POST settings with recent_data_window=null clears it (show all); reopen shows All."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "ClearRecent"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "1h"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"name": "ClearRecent", "recent_data_window": None}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertNotIn("recent_data_window", data["settings"])
        with _patch_live_track_enabled():
            get_resp = self.client.get(f"/api/extensions/live-track/trackers/{track_id}/")
        self.assertEqual(get_resp.status_code, 200)
        self.assertNotIn("recent_data_window", get_resp.json().get("settings", {}))

    def test_post_settings_empty_name_rejected(self):
        """POST settings with empty name returns 400."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Rename Me"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"name": "  "}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_post_settings_404_not_found(self):
        """POST settings with non-existent UUID returns 404."""
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/trackers/00000000-0000-0000-0000-000000000000/settings/",
                data=json.dumps({"name": "New"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 404)

    def test_post_settings_invalid_json(self):
        """POST settings with invalid JSON body returns 400."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "SettingsMe"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data="not json",
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_post_settings_invalid_recent_data_window_returns_400(self):
        """POST settings with invalid recent_data_window value returns 400."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "2h"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_post_settings_recent_data_window_session_is_accepted(self):
        """POST settings with recent_data_window=session returns 200 and persists value."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"recent_data_window": "session"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["settings"]["recent_data_window"], "session")

    def test_post_settings_409_duplicate_name(self):
        """POST settings with name that another track of same user has returns 409."""
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
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_b_id}/settings/",
                data=json.dumps({"name": "First"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 409)

    def test_post_settings_unauthenticated_returns_401(self):
        """POST settings without auth returns 401."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Track"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"color": "#ff0000"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 401)

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

    def test_regenerate_tokens_rotates_api_and_hauk_tokens(self):
        """POST regenerate-tokens rotates tracker_secret and hauk_password for owner."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Rotate Tokens"}),
                content_type="application/json",
            )
        self.assertEqual(create_resp.status_code, 201)
        track_id = create_resp.json()["id"]
        old_tracker_secret = create_resp.json()["tracker_secret"]
        old_hauk_password = create_resp.json()["hauk_password"]

        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/regenerate-tokens/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("tracker_secret", data)
        self.assertIn("hauk_password", data)
        self.assertNotEqual(data["tracker_secret"], old_tracker_secret)
        self.assertNotEqual(data["hauk_password"], old_hauk_password)

        track = LiveTrack.objects.get(id=track_id)
        self.assertEqual(track.tracker_secret, data["tracker_secret"])
        self.assertEqual(track.hauk_password, data["hauk_password"])

        with _patch_live_track_enabled():
            old_secret_check = self.client.post(
                "/api/extensions/live-track/tracker-check/",
                data=json.dumps({"tracker_id": track_id, "password": old_tracker_secret}),
                content_type="application/json",
            )
        self.assertEqual(old_secret_check.status_code, 200)
        self.assertFalse(old_secret_check.json()["valid"])

        with _patch_live_track_enabled():
            new_secret_check = self.client.post(
                "/api/extensions/live-track/tracker-check/",
                data=json.dumps({"tracker_id": track_id, "password": data["tracker_secret"]}),
                content_type="application/json",
            )
        self.assertEqual(new_secret_check.status_code, 200)
        self.assertTrue(new_secret_check.json()["valid"])

    def test_regenerate_tokens_other_user_cannot_rotate(self):
        """POST regenerate-tokens for another user's tracker returns 404."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Owner Track"}),
                content_type="application/json",
            )
        self.assertEqual(create_resp.status_code, 201)
        track_id = create_resp.json()["id"]
        old_tracker_secret = create_resp.json()["tracker_secret"]
        old_hauk_password = create_resp.json()["hauk_password"]

        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/regenerate-tokens/"
            )
        self.assertEqual(response.status_code, 404)

        track = LiveTrack.objects.get(id=track_id)
        self.assertEqual(track.tracker_secret, old_tracker_secret)
        self.assertEqual(track.hauk_password, old_hauk_password)

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

    def test_list_returns_metadata_only(self):
        """GET trackers/ returns metadata-only (last_point, bbox); no geometry."""
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
        self.assertNotIn("geometry", tracks[0])
        self.assertIn("last_point", tracks[0])
        self.assertEqual(tracks[0]["last_point"][0], -121.0)
        self.assertEqual(tracks[0]["last_point"][1], 38.0)
        self.assertIn("bbox", tracks[0])
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

    def test_hauk_config_returns_domain(self):
        """GET hauk-config/ returns 200 and JSON with hauk_domain (empty when not configured)."""
        mock_loader = MagicMock()
        mock_loader.get_str.side_effect = lambda key, default="": default
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.tracker_views.get_config_loader", return_value=mock_loader):
                response = self.client.get("/api/extensions/live-track/hauk-config/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("hauk_domain", data)
        self.assertEqual(data["hauk_domain"], "")

    def test_hauk_config_returns_configured_domain(self):
        """GET hauk-config/ returns hauk_domain from config when set."""
        mock_loader = MagicMock()
        mock_loader.get_str.side_effect = (
            lambda key, default="": "hauk.example.com" if key == "extensions.live_track.hauk_domain" else default
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.tracker_views.get_config_loader", return_value=mock_loader):
                response = self.client.get("/api/extensions/live-track/hauk-config/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["hauk_domain"], "hauk.example.com")

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

    def test_visibility_to_private_cleans_all_non_owner_data(self):
        """Changing visibility to private removes all non-owner shares, subscriptions, and group memberships."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Was Public"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        track.visibility = "public"
        track.save(update_fields=["visibility"])
        LiveTrackSubscription.objects.create(user=self.other_user, track=track)
        other_group = LiveTrackGroup.objects.create(user=self.other_user, name="Other G")
        LiveTrackGroupMember.objects.create(group=other_group, track=track)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"visibility": "private"}),
                content_type="application/json",
            )
        self.assertFalse(LiveTrackSubscription.objects.filter(track=track, user=self.other_user).exists())
        self.assertFalse(LiveTrackGroupMember.objects.filter(track=track, group=other_group).exists())
        self.assertFalse(LiveTrackShare.objects.filter(track=track).exists())

    def test_visibility_to_public_keeps_subscriptions(self):
        """Changing visibility from shared to public removes LiveTrackShare but keeps subscriptions."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Was Shared"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        LiveTrackSubscription.objects.create(user=self.other_user, track=track)
        self.assertTrue(LiveTrackShare.objects.filter(track=track, shared_with=self.other_user).exists())
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({"visibility": "public"}),
                content_type="application/json",
            )
        self.assertFalse(LiveTrackShare.objects.filter(track=track).exists())
        self.assertTrue(LiveTrackSubscription.objects.filter(track=track, user=self.other_user).exists())

    def test_visibility_public_to_shared_cleans_non_recipients(self):
        """Changing visibility from public to shared removes subscriptions and group memberships for non-recipients."""
        User = get_user_model()
        third_user = User.objects.create_user(
            email="third@example.com", password="thirdpass", username="thirduser",
        )
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Pub to Shared"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        track.visibility = "public"
        track.save(update_fields=["visibility"])
        LiveTrackSubscription.objects.create(user=self.other_user, track=track)
        LiveTrackSubscription.objects.create(user=third_user, track=track)
        third_group = LiveTrackGroup.objects.create(user=third_user, name="Third G")
        LiveTrackGroupMember.objects.create(group=third_group, track=track)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.assertTrue(LiveTrackSubscription.objects.filter(track=track, user=self.other_user).exists())
        self.assertFalse(LiveTrackSubscription.objects.filter(track=track, user=third_user).exists())
        self.assertFalse(LiveTrackGroupMember.objects.filter(track=track, group=third_group).exists())

    def test_unshare_via_emails_cleans_group_and_subscription(self):
        """Removing a user from shared_with_emails removes their group membership and subscription."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Unshare Test"}),
                content_type="application/json",
            )
        track_id = create_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        LiveTrackSubscription.objects.create(user=self.other_user, track=track)
        other_group = LiveTrackGroup.objects.create(user=self.other_user, name="Other G")
        LiveTrackGroupMember.objects.create(group=other_group, track=track)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [],
                }),
                content_type="application/json",
            )
        self.assertFalse(LiveTrackShare.objects.filter(track=track).exists())
        self.assertFalse(LiveTrackSubscription.objects.filter(track=track, user=self.other_user).exists())
        self.assertFalse(LiveTrackGroupMember.objects.filter(track=track, group=other_group).exists())


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

    def test_ingress_gpslogger_form_all_strings_invalid_bearing(self):
        """POST with GPSLogger-style form body (all string values, invalid bearing) returns 200."""
        form_body = (
            "lat=39.12176081&lon=-104.88864222&sat=10&desc=&alt=2253.0859375"
            "&acc=8.452844619750977&bearing=ARING&prov=gps&spd_kph=0.0"
            "&timestamp=1773445312&starttimestamp=1773445310&batt=72.0"
            "&ischarging=true&ser=852210c6e27f72b8&dist=0"
        )
        with _patch_live_track_enabled():
            with patch("extensions.live_track.src.backend.ingress_views.settings") as mock_settings:
                mock_settings.CACHES = {"default": {"BACKEND": "django.core.cache.backends.dummy.DummyCache"}}
                response = self.client.post(
                    self.ingress_url,
                    data=form_body,
                    content_type="application/x-www-form-urlencoded",
                    HTTP_AUTHORIZATION=self.auth_header,
                )
        self.assertEqual(response.status_code, 200, response.content)
        track = LiveTrack.objects.get(id=self.track_id)
        coords = (track.geometry or {}).get("coordinates", [])
        self.assertEqual(len(coords), 1)
        self.assertEqual(coords[0][1], 39.12176081)
        self.assertEqual(coords[0][0], -104.88864222)
        params = track.point_params or []
        self.assertEqual(len(params), 1)
        self.assertIsNone(params[0].get("bearing"))
        self.assertEqual(params[0].get("alt"), 2253.0859375)
        self.assertEqual(params[0].get("batt"), 72.0)
        self.assertIs(params[0].get("ischarging"), True)

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

    def test_ingress_insert_at_start_keeps_full_history(self):
        """Out-of-order insertion at start keeps all points with no trimming."""
        with _patch_live_track_enabled():
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
        self.assertEqual(len(coords), 3)
        self.assertEqual(len(params), 3)
        self.assertEqual([c[2] for c in coords], [100000, 200000, 300000])

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

    def test_ingress_keeps_all_points(self):
        """Ingress retains all received points; point-count trimming is not applied."""
        with _patch_live_track_enabled():
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
        self.assertEqual(len(coords), 4)
        self.assertEqual(len(params), 4)

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


class TestLiveTrackGroups(TestCase):
    """Test group CRUD, add/remove tracks, sharing, leave (self-unshare)."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="groupuser@example.com",
            password="testpass123",
            username="groupuser",
        )
        self.other_user = User.objects.create_user(
            email="othergroup@example.com",
            password="otherpass123",
            username="othergroup",
        )
        self.client.force_login(self.user)

    def test_group_list_create(self):
        """GET groups/ returns empty list; POST creates group."""
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/groups/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), [])
        with _patch_live_track_enabled():
            response = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "My Group"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 201)
        data = response.json()
        self.assertIn("id", data)
        self.assertEqual(data["name"], "My Group")
        self.assertTrue(data["is_owner"])
        self.assertIn("track_ids", data)

    def test_group_get_patch_delete(self):
        """GET/PATCH/DELETE groups/<id>/ for owner."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Edit Me"}),
                content_type="application/json",
            )
        group_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/groups/{group_id}/")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["name"], "Edit Me")
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"name": "Updated"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["name"], "Updated")
        with _patch_live_track_enabled():
            response = self.client.delete(f"/api/extensions/live-track/groups/{group_id}/")
        self.assertEqual(response.status_code, 204)
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/groups/{group_id}/")
        self.assertEqual(response.status_code, 404)

    def test_group_add_remove_track(self):
        """POST groups/<id>/tracks/ and DELETE groups/<id>/tracks/<track_id>/."""
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Track In Group"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "With Tracks"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        self.assertIn(track_id, response.json()["track_ids"])
        with _patch_live_track_enabled():
            response = self.client.delete(
                f"/api/extensions/live-track/groups/{group_id}/tracks/{track_id}/"
            )
        self.assertEqual(response.status_code, 204)
        with _patch_live_track_enabled():
            data = self.client.get(f"/api/extensions/live-track/groups/{group_id}/").json()
        self.assertNotIn(track_id, data["track_ids"])

    def test_group_leave_self_unshare(self):
        """Non-owner shared with group can leave via DELETE groups/<id>/leave/ (removes their LiveTrackGroupShare)."""
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Invite Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        group = LiveTrackGroup.objects.get(id=group_id)
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.assertTrue(
            LiveTrackGroupShare.objects.filter(group=group, shared_with=self.other_user).exists()
        )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.delete(f"/api/extensions/live-track/groups/{group_id}/leave/")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(
            LiveTrackGroupShare.objects.filter(group=group, shared_with=self.other_user).exists()
        )
        self.client.force_login(self.user)

    def test_group_leave_owner_gets_400(self):
        """Owner cannot leave; DELETE groups/<id>/leave/ returns 400."""
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Owned"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.delete(f"/api/extensions/live-track/groups/{group_id}/leave/")
        self.assertEqual(response.status_code, 400)

    def test_group_accept_share_success_and_idempotent(self):
        """Recipient can accept shared group; repeated accept remains successful."""
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Accept Me"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            first = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/accept-share/",
                content_type="application/json",
            )
            second = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/accept-share/",
                content_type="application/json",
            )
        self.assertEqual(first.status_code, 201)
        self.assertEqual(second.status_code, 201)
        self.assertTrue(first.json()["is_accepted"])
        self.assertTrue(second.json()["is_accepted"])
        self.assertEqual(
            LiveTrackGroupSubscription.objects.filter(
                user=self.other_user,
                group_id=group_id,
            ).count(),
            1,
        )
        self.client.force_login(self.user)

    def test_group_accept_share_requires_shared_invite(self):
        """Accept-share rejects owner or non-recipient users."""
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Not Shared"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            owner_response = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/accept-share/",
                content_type="application/json",
            )
        self.assertEqual(owner_response.status_code, 400)
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            other_response = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/accept-share/",
                content_type="application/json",
            )
        self.assertEqual(other_response.status_code, 404)
        self.client.force_login(self.user)

    def test_group_leave_non_recipient_gets_404(self):
        """User not shared with group gets 404 on DELETE groups/<id>/leave/."""
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Not Shared With Other"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"visibility": "public"}),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.delete(f"/api/extensions/live-track/groups/{group_id}/leave/")
        self.assertEqual(response.status_code, 404)
        self.client.force_login(self.user)

    def test_group_list_includes_shared_with_me(self):
        """GET groups/ returns groups I own and groups shared with me (LiveTrackGroupShare)."""
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Owned"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/groups/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["name"], "Owned")
        self.assertFalse(data[0]["is_owner"])
        self.assertFalse(data[0]["is_accepted"])
        self.assertIn("owner_email", data[0])
        with _patch_live_track_enabled():
            accept_response = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/accept-share/",
                content_type="application/json",
            )
        self.assertEqual(accept_response.status_code, 201)
        with _patch_live_track_enabled():
            accepted_list = self.client.get("/api/extensions/live-track/groups/")
        self.assertEqual(accepted_list.status_code, 200)
        self.assertTrue(accepted_list.json()[0]["is_accepted"])
        self.client.force_login(self.user)

    def test_group_track_ids_hidden_until_share_accepted(self):
        """Pending shared groups do not expose track_ids; accepted groups do."""
        with _patch_live_track_enabled():
            tracker_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Shared Group Tracker"}),
                content_type="application/json",
            )
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Pending Group"}),
                content_type="application/json",
            )
        tracker_id = tracker_resp.json()["id"]
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            add_resp = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": tracker_id}),
                content_type="application/json",
            )
        self.assertEqual(add_resp.status_code, 200)
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )

        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            pending_resp = self.client.get(f"/api/extensions/live-track/groups/{group_id}/")
        self.assertEqual(pending_resp.status_code, 200)
        self.assertEqual(pending_resp.json().get("track_ids"), [])
        self.assertFalse(pending_resp.json().get("is_accepted"))

        with _patch_live_track_enabled():
            accept_resp = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/accept-share/",
                content_type="application/json",
            )
        self.assertEqual(accept_resp.status_code, 201)
        with _patch_live_track_enabled():
            accepted_resp = self.client.get(f"/api/extensions/live-track/groups/{group_id}/")
        self.assertEqual(accepted_resp.status_code, 200)
        self.assertIn(str(tracker_id), [str(tid) for tid in accepted_resp.json().get("track_ids", [])])
        self.assertTrue(accepted_resp.json().get("is_accepted"))
        self.client.force_login(self.user)

    def test_group_patch_visibility(self):
        """Owner PATCHes group visibility; GET returns it; non-owner can GET when public/shared."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Vis Group"}),
                content_type="application/json",
            )
        group_id = create_resp.json()["id"]
        for vis in ("public", "shared", "private"):
            with _patch_live_track_enabled():
                response = self.client.patch(
                    f"/api/extensions/live-track/groups/{group_id}/",
                    data=json.dumps({"visibility": vis}),
                    content_type="application/json",
                )
            self.assertEqual(response.status_code, 200, f"visibility={vis}")
            self.assertEqual(response.json()["visibility"], vis)
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/groups/{group_id}/")
        self.assertEqual(response.status_code, 404, "other user cannot see group when it is private")
        self.client.force_login(self.user)
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"visibility": "public"}),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/groups/{group_id}/")
        self.assertEqual(response.status_code, 200, "other user can GET group when public")
        self.assertEqual(response.json()["visibility"], "public")
        self.client.force_login(self.user)

    def test_group_patch_shared_with_emails(self):
        """Owner sets visibility=shared and shared_with_emails; GET returns them; empty list removes share."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Shared Group"}),
                content_type="application/json",
            )
        group_id = create_resp.json()["id"]
        group = LiveTrackGroup.objects.get(id=group_id)
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("shared_with_emails", data)
        self.assertEqual(data["shared_with_emails"], [self.other_user.email])
        self.assertTrue(LiveTrackGroupShare.objects.filter(group=group, shared_with=self.other_user).exists())
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"shared_with_emails": []}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        self.assertFalse(LiveTrackGroupShare.objects.filter(group=group).exists())

    def test_group_patch_world_share_enabled(self):
        """Owner PATCHes world_share_enabled true; GET returns world_share_id/url; false removes them."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "World Group"}),
                content_type="application/json",
            )
        group_id = create_resp.json()["id"]
        group = LiveTrackGroup.objects.get(id=group_id)
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"world_share_enabled": True}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("world_share_id", data)
        self.assertIn("world_share_url", data)
        self.assertTrue(LiveTrackGroupWorldShare.objects.filter(group=group).exists())
        share_id = data["world_share_id"]
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"world_share_enabled": False}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertNotIn("world_share_id", data)
        self.assertNotIn("world_share_url", data)
        self.assertFalse(LiveTrackGroupWorldShare.objects.filter(group=group).exists())

    def test_group_visibility_required_for_shared_with_emails(self):
        """PATCH with shared_with_emails when visibility is not shared returns 400."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Private Group"}),
                content_type="application/json",
            )
        group_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"shared_with_emails": [self.other_user.email]}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 400)

    def test_group_list_includes_public_and_shared_with_me(self):
        """GET groups/ as non-owner returns public groups and groups shared with user."""
        with _patch_live_track_enabled():
            pub_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Public List Group"}),
                content_type="application/json",
            )
        pub_id = pub_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{pub_id}/",
                data=json.dumps({"visibility": "public"}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            shared_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Shared List Group"}),
                content_type="application/json",
            )
        shared_id = shared_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{shared_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get("/api/extensions/live-track/groups/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        names = [g["name"] for g in data]
        self.assertIn("Public List Group", names)
        self.assertIn("Shared List Group", names)
        self.client.force_login(self.user)

    def test_group_non_owner_cannot_patch_visibility_or_world_share(self):
        """User shared with group (non-owner) PATCHes visibility or world_share_enabled; expect 403."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Shared Edit Group"}),
                content_type="application/json",
            )
        group_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"visibility": "public"}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 403)
        with _patch_live_track_enabled():
            response = self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"world_share_enabled": True}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 403)
        self.client.force_login(self.user)

    def test_group_owner_sees_shared_with_emails_non_owner_does_not(self):
        """GET group as owner includes shared_with_emails and world_share; non-owner does not."""
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Secret Group"}),
                content_type="application/json",
            )
        group_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                    "world_share_enabled": True,
                }),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/groups/{group_id}/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("shared_with_emails", data)
        self.assertIn("world_share_id", data)
        self.assertIn("world_share_url", data)
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            response = self.client.get(f"/api/extensions/live-track/groups/{group_id}/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertNotIn("shared_with_emails", data)
        self.assertNotIn("world_share_id", data)
        self.assertNotIn("world_share_url", data)
        self.client.force_login(self.user)

    def test_group_add_shared_track_blocked_when_allow_group_reshare_false(self):
        """Adding a track shared with requester (non-owner) to a group returns 403 when allow_group_reshare is false."""
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Shared Track"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/subscribe/",
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "My Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 403)
        self.client.force_login(self.user)

    def test_group_add_shared_track_allowed_when_allow_group_reshare_true(self):
        """Adding a track shared with requester (non-owner) to a group succeeds when allow_group_reshare is true."""
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Reshare Track"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/settings/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                    "allow_group_reshare": True,
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{track_id}/subscribe/",
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Other Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        self.assertIn(track_id, response.json()["track_ids"])
        self.client.force_login(self.user)

    def test_group_patch_shared_with_emails_malformed_400(self):
        """PATCH group with shared_with_emails not a list of strings returns 400."""
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Malformed"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"visibility": "shared"}),
                content_type="application/json",
            )
        for bad_value in (123, "not-a-list", {"a": 1}, [123], [None]):
            with _patch_live_track_enabled():
                response = self.client.patch(
                    f"/api/extensions/live-track/groups/{group_id}/",
                    data=json.dumps({"shared_with_emails": bad_value}),
                    content_type="application/json",
                )
            self.assertEqual(response.status_code, 400, f"shared_with_emails={bad_value!r}")

    def test_group_leave_cleans_group_subscription(self):
        """Leaving a shared group removes accepted group subscription only."""
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Group Track"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        track = LiveTrack.objects.get(id=track_id)
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Leave Sub Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.client.force_login(self.other_user)
        with _patch_live_track_enabled():
            accept_response = self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/accept-share/",
                content_type="application/json",
            )
        self.assertEqual(accept_response.status_code, 201)
        self.assertTrue(
            LiveTrackGroupSubscription.objects.filter(
                user=self.other_user,
                group_id=group_id,
            ).exists()
        )
        with _patch_live_track_enabled():
            response = self.client.delete(f"/api/extensions/live-track/groups/{group_id}/leave/")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(
            LiveTrackGroupSubscription.objects.filter(
                user=self.other_user,
                group_id=group_id,
            ).exists()
        )
        self.client.force_login(self.user)

    def test_group_visibility_to_private_cleans_shares(self):
        """Setting group visibility to private deletes all LiveTrackGroupShare entries."""
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Priv Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        group = LiveTrackGroup.objects.get(id=group_id)
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                }),
                content_type="application/json",
            )
        self.assertTrue(LiveTrackGroupShare.objects.filter(group=group).exists())
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"visibility": "private"}),
                content_type="application/json",
            )
        self.assertFalse(LiveTrackGroupShare.objects.filter(group=group).exists())

    def test_group_delete_cascades_shares_and_members(self):
        """Deleting a group cascades to LiveTrackGroupShare, LiveTrackGroupMember, and LiveTrackGroupWorldShare."""
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Cascade Track"}),
                content_type="application/json",
            )
        track_id = track_resp.json()["id"]
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Cascade Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        group = LiveTrackGroup.objects.get(id=group_id)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": track_id}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({
                    "visibility": "shared",
                    "shared_with_emails": [self.other_user.email],
                    "world_share_enabled": True,
                }),
                content_type="application/json",
            )
        self.assertTrue(LiveTrackGroupMember.objects.filter(group=group).exists())
        self.assertTrue(LiveTrackGroupShare.objects.filter(group=group).exists())
        self.assertTrue(LiveTrackGroupWorldShare.objects.filter(group=group).exists())
        with _patch_live_track_enabled():
            response = self.client.delete(f"/api/extensions/live-track/groups/{group_id}/")
        self.assertEqual(response.status_code, 204)
        self.assertFalse(LiveTrackGroupMember.objects.filter(group=group).exists())
        self.assertFalse(LiveTrackGroupShare.objects.filter(group=group).exists())
        self.assertFalse(LiveTrackGroupWorldShare.objects.filter(group=group).exists())


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
    """Test that broadcast_track_updated sends to owner and subscriber live_track channels."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="broadcast@example.com",
            password="testpass123",
            username="broadcastuser",
        )

    def test_broadcast_track_updated_sends_to_live_track_group(self):
        from extensions.live_track.src.backend.helpers import broadcast_track_updated

        with _patch_live_track_enabled():
            track = LiveTrack.objects.create(
                name="Broadcast Track",
                user=self.user,
                tracker_secret="secret123",
            )
        sent = []

        async def mock_group_send(group, message):
            sent.append((group, message))

        mock_layer = MagicMock()
        mock_layer.group_send = mock_group_send

        with patch("extensions.live_track.src.backend.helpers.get_channel_layer", return_value=mock_layer):
            broadcast_track_updated(
                track,
                point=[10.0, 45.0, 1700000000000],
                props={"accuracy": 10},
                index=0,
            )

        self.assertGreaterEqual(len(sent), 1, "group_send called at least for owner")
        owner_channel = f"live_track_{self.user.id}"
        owner_sends = [s for s in sent if s[0] == owner_channel]
        self.assertEqual(len(owner_sends), 1)
        message = owner_sends[0][1]
        self.assertEqual(message["type"], "live_track_track_updated")
        self.assertEqual(message["data"]["track_id"], str(track.id))
        self.assertEqual(message["data"]["point"], [10.0, 45.0, 1700000000000])
        self.assertEqual(message["data"]["index"], 0)


class TestLiveTrackWorldShare(TestCase):
    """Test world (unauthenticated) share endpoints: GET world/share/<id>/info/ and GET world/share/<id>/."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="worldshare@example.com",
            password="testpass123",
            username="worldshare",
        )
        self.client.force_login(self.user)
        with _patch_live_track_enabled():
            create_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "World Shared Track"}),
                content_type="application/json",
            )
        self.assertEqual(create_resp.status_code, 201)
        self.track_id = create_resp.json()["id"]
        with _patch_live_track_enabled():
            settings_resp = self.client.post(
                f"/api/extensions/live-track/trackers/{self.track_id}/settings/",
                data=json.dumps({"world_share_enabled": True}),
                content_type="application/json",
            )
        data = settings_resp.json()
        self.share_id = data.get("world_share_id")
        self.assertIsNotNone(self.share_id, "world_share_id returned when world share enabled")

    def test_world_share_info_200(self):
        """GET world/share/<share_id>/info/ without auth returns 200 with share_type, track_name, track_id."""
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/world/share/{self.share_id}/info/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["share_type"], "live_track")
        self.assertEqual(data["track_name"], "World Shared Track")
        self.assertEqual(data["track_id"], self.track_id)
        self.assertIn("created_at", data)

    def test_world_share_data_200(self):
        """GET world/share/<share_id>/ without auth returns 200 with track name, geometry."""
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/world/share/{self.share_id}/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["name"], "World Shared Track")
        self.assertIn("geometry", data)
        self.assertIn("point_params", data)
        self.assertNotIn("tracker_secret", data)

    def test_world_share_info_404_invalid_id(self):
        """GET world/share/<invalid_id>/info/ returns 404."""
        self.client.logout()
        invalid_id = "00000000-0000-0000-4000-000000000000"
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/world/share/{invalid_id}/info/"
            )
        self.assertEqual(response.status_code, 404)

    def test_world_share_data_404_invalid_id(self):
        """GET world/share/<invalid_id>/ returns 404."""
        self.client.logout()
        invalid_id = "00000000-0000-0000-4000-000000000000"
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/world/share/{invalid_id}/"
            )
        self.assertEqual(response.status_code, 404)

    def test_public_share_redirects_to_world_share(self):
        """GET public/share/<id>/ redirects to world/share/<id>/ for backward compatibility."""
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/public/share/{self.share_id}/info/",
                follow=False,
            )
        self.assertEqual(response.status_code, 302)
        self.assertIn("world/share", response["Location"])
        self.assertIn(self.share_id, response["Location"])

    def test_disable_world_share_removes_link(self):
        """POST settings with world_share_enabled: false removes LiveTrackWorldShare and response has no world_share_id."""
        self.assertTrue(LiveTrackWorldShare.objects.filter(track_id=self.track_id).exists())
        with _patch_live_track_enabled():
            response = self.client.post(
                f"/api/extensions/live-track/trackers/{self.track_id}/settings/",
                data=json.dumps({"world_share_enabled": False}),
                content_type="application/json",
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertNotIn("world_share_id", data)
        self.assertNotIn("world_share_url", data)
        self.assertFalse(LiveTrackWorldShare.objects.filter(track_id=self.track_id).exists())

    def test_world_share_data_respects_share_params_with_world(self):
        """GET world/share/<id>/ returns point_params when share_params_with_world True, empty when False."""
        track = LiveTrack.objects.get(id=self.track_id)
        track.point_params = [{"acc": 5.0, "alt": 100}]
        track.share_params_with_world = True
        track.save(update_fields=["point_params", "share_params_with_world"])
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/world/share/{self.share_id}/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertIn("point_params", data)
        self.assertGreater(len(data["point_params"]), 0, "point_params visible when share_params_with_world True")

        self.client.force_login(self.user)
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/trackers/{self.track_id}/settings/",
                data=json.dumps({"share_params_with_world": False}),
                content_type="application/json",
            )
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/world/share/{self.share_id}/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["point_params"], [], "point_params hidden when share_params_with_world False")

    def test_world_share_info_404_malformed_share_id(self):
        """GET world/share/<malformed>/info/ returns 404 for non-UUID share_id."""
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                "/api/extensions/live-track/world/share/not-a-uuid/info/"
            )
        self.assertEqual(response.status_code, 404)

    def test_world_share_data_404_malformed_share_id(self):
        """GET world/share/<malformed>/ returns 404 for non-UUID share_id."""
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                "/api/extensions/live-track/world/share/not-a-uuid/"
            )
        self.assertEqual(response.status_code, 404)

    def test_world_share_id_precedence_track_first(self):
        """When the same share_id exists for both track and group, lookup returns live_track (deterministic contract)."""
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "Collision Group"}),
                content_type="application/json",
            )
        group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{group_id}/tracks/",
                data=json.dumps({"track_id": self.track_id}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            self.client.patch(
                f"/api/extensions/live-track/groups/{group_id}/",
                data=json.dumps({"world_share_enabled": True}),
                content_type="application/json",
            )
        group_ws = LiveTrackGroupWorldShare.objects.get(group_id=group_id)
        group_ws.share_id = self.share_id
        group_ws.save(update_fields=["share_id"])
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/world/share/{self.share_id}/info/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["share_type"], "live_track", "track takes precedence over group when IDs collide")


class TestLiveTrackGroupWorldShare(TestCase):
    """Test world share endpoints for groups: info and data return share_type live_track_group."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="groupworld@example.com",
            password="testpass123",
            username="groupworld",
        )
        self.client.force_login(self.user)
        with _patch_live_track_enabled():
            track_resp = self.client.post(
                "/api/extensions/live-track/trackers/",
                data=json.dumps({"name": "Track In Group"}),
                content_type="application/json",
            )
        self.track_id = track_resp.json()["id"]
        with _patch_live_track_enabled():
            group_resp = self.client.post(
                "/api/extensions/live-track/groups/",
                data=json.dumps({"name": "World Shared Group"}),
                content_type="application/json",
            )
        self.group_id = group_resp.json()["id"]
        with _patch_live_track_enabled():
            self.client.post(
                f"/api/extensions/live-track/groups/{self.group_id}/tracks/",
                data=json.dumps({"track_id": self.track_id}),
                content_type="application/json",
            )
        with _patch_live_track_enabled():
            patch_resp = self.client.patch(
                f"/api/extensions/live-track/groups/{self.group_id}/",
                data=json.dumps({"world_share_enabled": True}),
                content_type="application/json",
            )
        self.group_share_id = patch_resp.json().get("world_share_id")
        self.assertIsNotNone(self.group_share_id)

    def test_group_world_share_info_200(self):
        """GET world/share/<group_share_id>/info/ without auth returns 200 with share_type live_track_group."""
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/world/share/{self.group_share_id}/info/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["share_type"], "live_track_group")
        self.assertEqual(data["group_id"], self.group_id)
        self.assertEqual(data["group_name"], "World Shared Group")
        self.assertIn("created_at", data)

    def test_group_world_share_data_200(self):
        """GET world/share/<group_share_id>/ without auth returns 200 with share_type, group_name, tracks."""
        self.client.logout()
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/world/share/{self.group_share_id}/"
            )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["share_type"], "live_track_group")
        self.assertEqual(data["group_name"], "World Shared Group")
        self.assertIn("tracks", data)
        self.assertEqual(len(data["tracks"]), 1)
        self.assertEqual(data["tracks"][0]["name"], "Track In Group")
        self.assertNotIn("tracker_secret", data["tracks"][0])

    def test_group_world_share_info_404(self):
        """GET world/share/<valid-uuid-no-record>/info/ returns 404."""
        self.client.logout()
        invalid_id = "00000000-0000-0000-4000-000000000000"
        with _patch_live_track_enabled():
            response = self.client.get(
                f"/api/extensions/live-track/world/share/{invalid_id}/info/"
            )
        self.assertEqual(response.status_code, 404)
