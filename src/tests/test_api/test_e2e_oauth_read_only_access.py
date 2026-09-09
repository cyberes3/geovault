"""
End-to-end tests for read-only API access.

Verifies that API key (and optionally OAuth Bearer) authentication can successfully
read feature store, import queue, user settings, config, storage, collections, and
other read-only endpoints. Does not perform any write operations.
"""
import json

from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.test import TestCase

from api.models import FeatureStore, ImportQueue, UserSettings
from users.api_keys import create_user_api_key


class TestE2EOAuthReadOnlyAccess(TestCase):
    """
    E2E tests that read-only access works with API key authentication.

    Covers: feature store (list, tag names, single feature), import queue (history, jobs),
    user settings, config, user storage, collections, extensions list.
    """

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="readonly_e2e@example.com",
            password="testpass123",
            username="readonly_e2e_user",
        )
        self.api_key_obj, self.raw_key = create_user_api_key(self.user, "E2E read-only key")
        self.auth_header = {"HTTP_AUTHORIZATION": f"Bearer {self.raw_key}"}

        # Create minimal data so read endpoints return non-empty or valid structure.
        # get_all_features excludes geometry__isnull=True, so set geometry explicitly.
        self.feature = FeatureStore.objects.create(
            user=self.user,
            geojson={
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [0, 0]},
                "properties": {"name": "E2E point"},
            },
            geometry=Point(0, 0, 0),
        )
        UserSettings.objects.get_or_create(user=self.user, defaults={"settings": {}, "hidden_features": []})

    def tearDown(self):
        FeatureStore.objects.filter(user=self.user).delete()
        ImportQueue.objects.filter(user=self.user).delete()

    def _get(self, path, **kwargs):
        """GET path with API key auth. No session."""
        return self.client.get(path, **{**self.auth_header, **kwargs})

    def test_read_only_access_feature_store(self):
        """API key can read feature store: all features, tag names, single feature."""
        r = self._get("/api/features/all/")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("items", data)
        self.assertIsInstance(data["items"], list)
        self.assertGreaterEqual(len(data["items"]), 1)
        self.assertEqual(data["items"][0]["id"], self.feature.id)

        r = self._get("/api/tags/names/")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("items", data)
        self.assertIsInstance(data["items"], list)

        r = self._get(f"/api/feature/{self.feature.id}/")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("feature", data)
        self.assertEqual(data["feature"]["id"], self.feature.id)

    def test_read_only_access_import_queue(self):
        """API key can read import queue: history and jobs."""
        r = self._get("/api/item/import/history")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("items", data)
        self.assertIsInstance(data["items"], list)

        r = self._get("/api/item/import/jobs")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("jobs", data)
        self.assertIsInstance(data["jobs"], list)

    def test_read_only_access_settings_and_config(self):
        """API key can read user settings and public config."""
        r = self._get("/api/user/settings/")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("settings", data)
        self.assertIn("hidden_features", data)

        r = self._get("/api/config/")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("systemTagPrefixes", data)
        self.assertIn("tagPriorities", data)

    def test_read_only_access_user_status_and_storage(self):
        """API key can read user status and storage."""
        r = self._get("/api/user/status/")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("authorized", data)
        self.assertTrue(data["authorized"])

        r = self._get("/api/user/storage/usage/")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("total_storage_bytes", data)
        self.assertIn("by_type", data)
        self.assertIn("feature", data["by_type"])
        self.assertIsInstance(data["total_storage_bytes"], (int, float))

    def test_read_only_access_collections_and_extensions(self):
        """API key can read collections list and extensions list."""
        r = self._get("/api/collections/")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("items", data)
        self.assertIsInstance(data["items"], list)

        r = self._get("/api/extensions/")
        self.assertEqual(r.status_code, 200, r.content)
        data = json.loads(r.content)
        self.assertIn("items", data)
        self.assertIsInstance(data["items"], list)

    def test_read_only_access_import_queue_item_features_when_item_exists(self):
        """API key can read import queue item features when user has an import item."""
        # Create a minimal import queue item so we can GET its features
        item = ImportQueue.objects.create(
            user=self.user,
            original_filename="e2e_test.gpx",
            raw_file="",
        )
        try:
            r = self._get(f"/api/item/import/get/features/{item.id}")
            self.assertEqual(r.status_code, 200, r.content)
            data = json.loads(r.content)
            self.assertIn("items", data)
            self.assertIsInstance(data["items"], list)
        finally:
            item.delete()

    def test_read_only_access_full_flow_no_session(self):
        """
        Single E2E flow: no session, only API key; hit main read endpoints and assert 200.
        """
        self.client.logout()
        endpoints = [
            "/api/user/status/",
            "/api/features/all/",
            "/api/tags/names/",
            "/api/item/import/history",
            "/api/item/import/jobs",
            "/api/user/settings/",
            "/api/config/",
            "/api/user/storage/usage/",
            "/api/collections/",
            "/api/extensions/",
        ]
        for path in endpoints:
            with self.subTest(path=path):
                r = self._get(path)
                self.assertEqual(r.status_code, 200, f"{path} -> {r.status_code} {r.content[:200]}")
