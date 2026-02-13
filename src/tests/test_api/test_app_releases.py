"""
Tests for app-releases API endpoint.

Uses real Gitea API calls by default to validate integration with git.evulid.cc.
"""
import json
from unittest.mock import patch

import requests
from django.test import TestCase
from django.contrib.auth import get_user_model

User = get_user_model()

# Hardcoded in view (git.evulid.cc, not configurable)
GITEA_RELEASES_API = "https://git.evulid.cc/api/v1/repos/cyberes/geovault-app-release/releases"
RELEASES_PAGE_URL = "https://git.evulid.cc/cyberes/geovault-app-release/releases"
CACHE_MAX_AGE = 3600


class TestAppReleasesAPI(TestCase):
    """Test /api/app-releases/ endpoint."""

    def setUp(self):
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
        self.assertEqual(data["releases_page_url"], RELEASES_PAGE_URL)
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
        # Cache: 1 hour for server and browser
        cache_control = response.get("Cache-Control", "")
        self.assertIn("public", cache_control)
        self.assertIn(f"max-age={CACHE_MAX_AGE}", cache_control)

    def test_app_releases_unsafe_url_returns_fallback(self):
        """When releases API URL fails SSRF check, return fallback only and still cache 1h."""
        with patch("api.views.app_releases.is_url_safe_for_fetch", return_value=False):
            response = self.client.get("/api/app-releases/")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertIsNone(data["uploader_url"])
        self.assertIsNone(data["places_url"])
        self.assertEqual(data["releases_page_url"], RELEASES_PAGE_URL)
        self.assertIn(f"max-age={CACHE_MAX_AGE}", response.get("Cache-Control", ""))

    def test_app_releases_api_error_returns_fallback(self):
        """When Gitea request fails, return fallback and cache 1h."""
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
        self.assertEqual(data["releases_page_url"], RELEASES_PAGE_URL)
        self.assertIn(f"max-age={CACHE_MAX_AGE}", response.get("Cache-Control", ""))
