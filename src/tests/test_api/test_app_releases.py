"""
Tests for app-releases API endpoint.

Uses real Gitea API calls by default to validate integration with git.evulid.cc.
"""
import json
from unittest.mock import patch

import requests
from django.contrib.auth import get_user_model
from django.core.cache import cache
from django.test import TestCase

from api.views.app_releases import APP_RELEASES_CACHE_KEY_PREFIX, DEFAULT_RELEASES_API_URL

User = get_user_model()

# Hardcoded expected URLs (match view default)
TEST_RELEASES_API_URL = "https://git.evulid.cc/api/v1/repos/cyberes/geovault-app-release/releases"
TEST_RELEASES_PAGE_URL = "https://git.evulid.cc/cyberes/geovault-app-release/releases"


class TestAppReleasesAPI(TestCase):
    """Test /api/app-releases/ endpoint."""

    def setUp(self):
        cache.delete(f"{APP_RELEASES_CACHE_KEY_PREFIX}:{DEFAULT_RELEASES_API_URL}")
        self.user = User.objects.create_user(
            email="test@example.com",
            password="testpass123",
            username="testuser",
        )
        self.client.force_login(self.user)

    def test_app_releases_requires_auth(self):
        """Unauthenticated request returns 401."""
        self.client.logout()
        response = self.client.get("/api/app-releases/")
        self.assertEqual(response.status_code, 401)

    def test_app_releases_real_gitea_response(self):
        """
        Hit the real Gitea API and assert response shape and cache header.
        URLs are hardcoded to git.evulid.cc.
        """
        response = self.client.get("/api/app-releases/")
        self.assertEqual(response.status_code, 200, response.content.decode())

        data = json.loads(response.content)
        self.assertIn("releases_page_url", data)
        self.assertEqual(data["releases_page_url"], TEST_RELEASES_PAGE_URL)
        self.assertIn("uploader_url", data)
        self.assertIn("places_url", data)
        # At least uploader APK should be present from real releases
        self.assertIsNotNone(
            data["uploader_url"],
            "Expected latest release to include Uploader APK asset",
        )
        self.assertTrue(
            data["uploader_url"].startswith("http") and data["uploader_url"].endswith(".apk"),
            "uploader_url should be a direct APK download URL",
        )
        # Server caches 30 min; browser must not cache (private, no-store)
        cache_control = response.get("Cache-Control", "")
        self.assertIn("private", cache_control)
        self.assertIn("no-store", cache_control)

    def test_app_releases_unsafe_url_returns_fallback(self):
        """When releases API URL fails SSRF check, return fallback only and still cache 30m."""
        with patch("api.views.app_releases.is_url_safe_for_fetch", return_value=False):
            response = self.client.get("/api/app-releases/")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIsNone(data["uploader_url"])
        self.assertIsNone(data["places_url"])
        self.assertEqual(data["releases_page_url"], TEST_RELEASES_PAGE_URL)
        self.assertIn("no-store", response.get("Cache-Control", ""))

    def test_app_releases_api_error_returns_fallback(self):
        """When Gitea request fails, return fallback and cache 30m."""
        real_get = requests.get

        def mock_get(url, *args, **kwargs):
            if "geovault-app-release/releases" in str(url):
                raise requests.ConnectionError("Connection error")
            return real_get(url, *args, **kwargs)

        with patch("api.views.app_releases.requests.get", side_effect=mock_get):
            response = self.client.get("/api/app-releases/")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIsNone(data["uploader_url"])
        self.assertIsNone(data["places_url"])
        self.assertEqual(data["releases_page_url"], TEST_RELEASES_PAGE_URL)
        self.assertIn("no-store", response.get("Cache-Control", ""))

    def test_app_releases_server_cache_used(self):
        """Second request within cache TTL returns cached response without calling Gitea."""
        call_count = 0
        real_get = requests.get

        def count_get(url, *args, **kwargs):
            nonlocal call_count
            call_count += 1
            return real_get(url, *args, **kwargs)

        with patch("api.views.app_releases.requests.get", side_effect=count_get):
            r1 = self.client.get("/api/app-releases/")
            r2 = self.client.get("/api/app-releases/")
        self.assertEqual(r1.status_code, 200)
        self.assertEqual(r2.status_code, 200)
        self.assertEqual(json.loads(r1.content), json.loads(r2.content))
        self.assertEqual(call_count, 1, "Gitea should be called once; second response from server cache")
