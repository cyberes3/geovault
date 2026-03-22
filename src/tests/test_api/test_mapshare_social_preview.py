"""Tests for map share social preview pages and preview images."""
from io import BytesIO
from unittest.mock import patch, MagicMock
import uuid

from django.contrib.auth import get_user_model
from django.contrib.gis.geos import Point
from django.test import TestCase
from PIL import Image

from api.models import FeatureShare, FeatureStore
from geo_lib.feature_id import generate_geojson_hash


class TestMapshareSocialPreview(TestCase):
    """Validate social share HTML and preview image behavior."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="test@example.com",
            password="testpass123",
            username="testuser",
        )
        feature_data = {
            "type": "Feature",
            "geometry": {
                "type": "Point",
                "coordinates": [-122.4194, 37.7749, 0.0],
            },
            "properties": {
                "name": "Social Preview Point",
            },
        }
        feature = FeatureStore.objects.create(
            user=self.user,
            geojson=feature_data,
            geometry=Point(-122.4194, 37.7749, 0.0),
            geojson_hash=generate_geojson_hash(feature_data),
        )
        self.share = FeatureShare.objects.create(
            share_id=str(uuid.uuid4()),
            feature=feature,
            user=self.user,
            allow_downloads=True,
        )

    def _build_png_bytes(self):
        image = Image.new("RGB", (256, 256), (120, 140, 160))
        output = BytesIO()
        image.save(output, format="PNG")
        return output.getvalue()

    def test_social_page_redirects_humans_to_hash_mapshare(self):
        response = self.client.get(f"/share/map/{self.share.share_id}/")
        self.assertEqual(response.status_code, 301)
        self.assertEqual(response["Location"], f"/#/mapshare?id={self.share.share_id}")

    def test_social_page_returns_og_tags_for_crawlers(self):
        response = self.client.get(
            f"/share/map/{self.share.share_id}/",
            HTTP_USER_AGENT="Slackbot-LinkExpanding 1.0",
        )
        self.assertEqual(response.status_code, 200)
        content = response.content.decode("utf-8")
        self.assertIn('property="og:title"', content)
        self.assertIn('property="og:image"', content)
        self.assertIn("Social Preview Point", content)

    def test_social_page_returns_og_tags_for_known_bot_user_agents(self):
        known_bot_uas = [
            "Twitterbot/1.0",
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko; compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
        ]

        for user_agent in known_bot_uas:
            with self.subTest(user_agent=user_agent):
                response = self.client.get(
                    f"/share/map/{self.share.share_id}/",
                    HTTP_USER_AGENT=user_agent,
                )
                self.assertEqual(response.status_code, 200)
                content = response.content.decode("utf-8")
                self.assertIn('property="og:title"', content)
                self.assertIn('property="og:image"', content)

    def test_social_page_redirects_for_normal_browser_user_agent(self):
        browser_ua = (
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        )
        response = self.client.get(
            f"/share/map/{self.share.share_id}/",
            HTTP_USER_AGENT=browser_ua,
        )
        self.assertEqual(response.status_code, 301)
        self.assertEqual(response["Location"], f"/#/mapshare?id={self.share.share_id}")

    @patch("website.map_share_social.views._crawler_detect")
    def test_social_page_uses_crawlerdetect_instance(self, mock_crawler_detect):
        mock_crawler_detect.is_crawler.return_value = True

        response = self.client.get(
            f"/share/map/{self.share.share_id}/",
            HTTP_USER_AGENT="CustomAgent/1.0",
        )

        self.assertEqual(response.status_code, 200)
        mock_crawler_detect.is_crawler.assert_called_once_with("CustomAgent/1.0")

    def test_social_page_invalid_share_returns_404(self):
        response = self.client.get("/share/map/00000000-0000-4000-8000-000000000000/")
        self.assertEqual(response.status_code, 404)

    @patch("website.map_share_social.preview_image.requests.get")
    def test_preview_endpoint_returns_png(self, mock_requests_get):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.content = self._build_png_bytes()
        mock_requests_get.return_value = mock_response

        response = self.client.get(f"/share/map/{self.share.share_id}/preview.png")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response["Content-Type"], "image/png")
        self.assertGreater(len(response.content), 0)
        first_url = mock_requests_get.call_args_list[0][0][0]
        self.assertIn("openstreetmap.org", first_url)

    @patch("website.map_share_social.preview_image.requests.get")
    def test_preview_fetches_upstream_template_not_api_tiles_path(self, mock_requests_get):
        """Social preview must use registry url_template (direct upstream), not client /api/tiles/... URL."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.content = self._build_png_bytes()
        mock_requests_get.return_value = mock_response

        proxied_client = {
            "id": "osm",
            "type": "xyz",
            "requires_proxy": True,
            "url_template": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "proxy_config": {"headers": {"User-Agent": "GeoVaultTest/1.0"}},
            "client_config": {
                "type": "xyz",
                "url": "/api/tiles/osm/{z}/{x}/{y}",
            },
        }
        with patch("website.map_share_social.preview_image.get_tile_source", return_value=proxied_client):
            response = self.client.get(f"/share/map/{self.share.share_id}/preview.png")
        self.assertEqual(response.status_code, 200)
        for call in mock_requests_get.call_args_list:
            url = call[0][0]
            self.assertNotIn("/api/tiles/", url)
            self.assertIn("openstreetmap.org", url)

    @patch("website.map_share_social.preview_image.requests.get")
    def test_preview_endpoint_returns_404_for_invalid_share(self, mock_requests_get):
        response = self.client.get("/share/map/00000000-0000-4000-8000-000000000000/preview.png")
        self.assertEqual(response.status_code, 404)
