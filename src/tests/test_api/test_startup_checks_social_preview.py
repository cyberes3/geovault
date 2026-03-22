from unittest.mock import Mock, patch

import pytest

from website.startup_checks import check_social_preview_tilesource


@pytest.mark.django_db
class TestStartupChecksSocialPreview:
    def test_check_social_preview_tilesource_valid_xyz(self):
        config = Mock()
        config.get_str.return_value = "osm"
        tile_cfg = {
            "id": "osm",
            "type": "xyz",
            "url_template": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "client_config": {
                "type": "xyz",
                "url": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            },
        }
        with patch("website.startup_checks.get_config_loader", return_value=config), patch(
            "website.startup_checks.get_tile_source", return_value=tile_cfg
        ):
            assert check_social_preview_tilesource() is True

    def test_check_social_preview_tilesource_valid_when_client_url_is_proxy_path(self):
        """With proxy_osm, client URL is /api/tiles/... but url_template is still direct raster."""
        config = Mock()
        config.get_str.return_value = "osm"
        tile_cfg = {
            "id": "osm",
            "type": "xyz",
            "requires_proxy": True,
            "url_template": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "client_config": {
                "type": "xyz",
                "url": "/api/tiles/osm/{z}/{x}/{y}",
            },
        }
        with patch("website.startup_checks.get_config_loader", return_value=config), patch(
            "website.startup_checks.get_tile_source", return_value=tile_cfg
        ):
            assert check_social_preview_tilesource() is True

    def test_check_social_preview_tilesource_missing_source(self):
        config = Mock()
        config.get_str.return_value = "does-not-exist"
        with patch("website.startup_checks.get_config_loader", return_value=config), patch(
            "website.startup_checks.get_tile_source", return_value=None
        ):
            assert check_social_preview_tilesource() is False

    def test_check_social_preview_tilesource_non_raster_rejected(self):
        config = Mock()
        config.get_str.return_value = "maptiler-topo-v4"
        tile_cfg = {
            "id": "maptiler-topo-v4",
            "type": "maptiler",
            "client_config": {
                "type": "maptiler",
                "style_url": "https://api.maptiler.com/maps/topo-v4/style.json",
            },
        }
        with patch("website.startup_checks.get_config_loader", return_value=config), patch(
            "website.startup_checks.get_tile_source", return_value=tile_cfg
        ):
            assert check_social_preview_tilesource() is False
