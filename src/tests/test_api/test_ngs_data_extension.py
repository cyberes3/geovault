"""
Tests for the ngs_data extension (NGS per-region SQLite download).
"""
import json
import os
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

from django.contrib.auth import get_user_model
from django.core.cache import cache
from django.test import TestCase


def _patch_ngs_data_enabled() -> object:
    def mock_get_bool(key, default=False):
        if key == "extensions.ngs_data.enabled":
            return True
        return default

    mock_config = MagicMock()
    mock_config.get_bool.side_effect = mock_get_bool
    return patch("website.extensions.extension_loader.get_config_loader", return_value=mock_config)


@patch.dict(os.environ, {}, clear=False)
class TestNgsDataExtensionAPI(TestCase):
    """nominally requires ngs_data extension on disk and enabled in tests."""

    def setUp(self):
        User = get_user_model()
        self.user = User.objects.create_user(
            email="ngs-test@example.com",
            password="testpass123",
            username="ngs-test",
        )
        self.client.force_login(self.user)

    def test_unauthenticated_401(self):
        self.client.logout()
        with _patch_ngs_data_enabled():
            response = self.client.get(
                "/api/extensions/ngs-data/download/",
                {"region": "CA"},
            )
        self.assertEqual(response.status_code, 401)

    def test_missing_region_400(self):
        with _patch_ngs_data_enabled():
            response = self.client.get(
                "/api/extensions/ngs-data/download/",
            )
        self.assertEqual(response.status_code, 400)
        self.assertIn(b"region", response.content)

    def test_unknown_region_404(self):
        with _patch_ngs_data_enabled():
            response = self.client.get(
                "/api/extensions/ngs-data/download/",
                {"region": "ZZ"},
            )
        self.assertEqual(response.status_code, 404)

    def test_download_serves_file_200(self):
        from extensions.ngs_data.src.backend import views as ngs_views

        with tempfile.TemporaryDirectory() as tmp:
            fpath = os.path.join(tmp, "CA.sqlite")
            with open(fpath, "wb") as f:
                f.write(b"test-sqlite-payload")
            with _patch_ngs_data_enabled(), patch.object(
                ngs_views, "_ngs_data_dir", return_value=Path(tmp)
            ):
                response = self.client.get(
                    "/api/extensions/ngs-data/download/",
                    {"region": "ca"},
                )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            b"".join(response.streaming_content),
            b"test-sqlite-payload",
        )
        self.assertIn("attachment", response.get("Content-Disposition", ""))
        self.assertEqual(
            response["CDN-Cache-Control"],
            f"public, max-age={ngs_views._NGS_DOWNLOAD_CDN_CACHE_MAX_AGE_SECONDS}",
        )
        self.assertEqual(response["Cache-Control"], "private, max-age=0")
        self.assertEqual(response["Vary"], "Authorization, Cookie")

    def test_missing_on_disk_404(self):
        with tempfile.TemporaryDirectory() as tmp, _patch_ngs_data_enabled(), patch(
            "extensions.ngs_data.src.backend.views._ngs_data_dir",
            return_value=__import__("pathlib").Path(tmp),
        ):
            response = self.client.get(
                "/api/extensions/ngs-data/download/",
                {"region": "CA"},
            )
        self.assertEqual(response.status_code, 404)

    def test_catalog_unauthenticated_401(self):
        self.client.logout()
        with _patch_ngs_data_enabled():
            response = self.client.get("/api/extensions/ngs-data/catalog/")
        self.assertEqual(response.status_code, 401)

    def test_catalog_empty_directory(self):
        from extensions.ngs_data.src.backend import views as ngs_views

        with tempfile.TemporaryDirectory() as tmp, _patch_ngs_data_enabled(), patch.object(
            ngs_views, "_ngs_data_dir", return_value=Path(tmp)
        ):
            response = self.client.get("/api/extensions/ngs-data/catalog/")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        self.assertEqual(data.get("databases"), [])

    def test_catalog_lists_file_with_metadata(self):
        from extensions.ngs_data.src.backend import views as ngs_views

        payload = b"sqlite-bytes-here"
        with tempfile.TemporaryDirectory() as tmp:
            fpath = os.path.join(tmp, "CA.sqlite")
            with open(fpath, "wb") as f:
                f.write(payload)
            with _patch_ngs_data_enabled(), patch.object(
                ngs_views, "_ngs_data_dir", return_value=Path(tmp)
            ):
                response = self.client.get("/api/extensions/ngs-data/catalog/")
        self.assertEqual(response.status_code, 200)
        data = json.loads(response.content)
        dbs = data.get("databases")
        self.assertEqual(len(dbs), 1)
        self.assertEqual(
            dbs[0],
            {
                "id": "CA",
                "display_name": "California",
                "size_bytes": len(payload),
            },
        )
        self.assertEqual(
            response["CDN-Cache-Control"],
            f"public, max-age={ngs_views._NGS_CATALOG_CDN_CACHE_MAX_AGE_SECONDS}",
        )
        self.assertEqual(response["Cache-Control"], "private, max-age=0")
        self.assertEqual(response["Vary"], "Authorization, Cookie")

    def test_catalog_second_request_uses_server_cache(self):
        from extensions.ngs_data.src.backend import views as ngs_views

        payload = b"x"
        with tempfile.TemporaryDirectory() as tmp:
            fpath = os.path.join(tmp, "CA.sqlite")
            with open(fpath, "wb") as f:
                f.write(payload)
            p = Path(tmp)
            cache_key = f"{ngs_views._NGS_CATALOG_CACHE_PREFIX}:{p.resolve()}"
            cache.delete(cache_key)
            try:
                with _patch_ngs_data_enabled(), patch.object(
                    ngs_views, "_ngs_data_dir", return_value=p
                ):
                    r1 = self.client.get("/api/extensions/ngs-data/catalog/")
                    self.assertIsNotNone(cache.get(cache_key))
                    r2 = self.client.get("/api/extensions/ngs-data/catalog/")
            finally:
                cache.delete(cache_key)

        self.assertEqual(r1.status_code, 200)
        self.assertEqual(r2.status_code, 200)
        self.assertEqual(json.loads(r1.content), json.loads(r2.content))
