import math
import traceback
from io import BytesIO

import requests
from PIL import Image

from geo_lib.logging.console import get_tagged_logger
from geo_lib.tile_sources.registry import get_tile_source
from geo_lib.tile_upstream import build_tile_upstream_headers
from website.config_loader import get_config_loader
from website.public_url import public_base_url

SOCIAL_PREVIEW_CACHE_SECONDS = 60 * 60 * 24 * 30
_SOCIAL_PREVIEW_WIDTH = 1200
_SOCIAL_PREVIEW_HEIGHT = 630
_SOCIAL_PREVIEW_BACKGROUND = (238, 241, 245)
_MAX_PREVIEW_ZOOM = 17
_MIN_PREVIEW_ZOOM = 1
_TILE_SIZE = 256
_logger = get_tagged_logger("social_preview")


def get_social_preview_source_id() -> str:
    source_id = get_config_loader().get_str("tilesources.social_preview_raster_source", "osm").strip()
    return source_id or "osm"


def tile_url_template_has_placeholders(template: str) -> bool:
    return (
        isinstance(template, str)
        and "{z}" in template
        and "{x}" in template
        and "{y}" in template
    )


def resolve_social_preview_raster_source():
    """
    Resolve raster tile source from the internal registry (same as tile proxy / startup checks).

    Server-side preview fetches must use url_template + proxy_config so behavior matches
    tilesources.proxy_osm and upstream header requirements, not only the client-facing URL.
    """
    source_id = get_social_preview_source_id()
    cfg = get_tile_source(source_id)
    if not cfg:
        return None
    if cfg.get("type") != "xyz":
        return None
    client_config = cfg.get("client_config", {})
    if client_config.get("type") != "xyz":
        return None
    url_template = cfg.get("url_template") or ""
    client_url = client_config.get("url", "")
    if not tile_url_template_has_placeholders(url_template) and not tile_url_template_has_placeholders(client_url):
        return None
    return cfg


def normalize_extent(extent):
    if not extent:
        return None
    min_lon, min_lat, max_lon, max_lat = extent
    if min_lon is None or min_lat is None or max_lon is None or max_lat is None:
        return None
    return (
        float(min_lon),
        _clamp_lat(float(min_lat)),
        float(max_lon),
        _clamp_lat(float(max_lat)),
    )


def _clamp_lat(lat: float) -> float:
    return max(min(lat, 85.05112878), -85.05112878)


def _lon_to_pixel_x(lon: float, zoom: int) -> float:
    world = _TILE_SIZE * (2 ** zoom)
    return ((lon + 180.0) / 360.0) * world


def _lat_to_pixel_y(lat: float, zoom: int) -> float:
    clamped = _clamp_lat(lat)
    lat_rad = math.radians(clamped)
    world = _TILE_SIZE * (2 ** zoom)
    merc_n = math.log(math.tan((math.pi / 4.0) + (lat_rad / 2.0)))
    return (1.0 - (merc_n / math.pi)) / 2.0 * world


def _pick_zoom_for_extent(extent):
    min_lon, min_lat, max_lon, max_lat = extent
    if min_lon == max_lon and min_lat == max_lat:
        return 14

    for zoom in range(_MAX_PREVIEW_ZOOM, _MIN_PREVIEW_ZOOM - 1, -1):
        span_x = abs(_lon_to_pixel_x(max_lon, zoom) - _lon_to_pixel_x(min_lon, zoom))
        span_y = abs(_lat_to_pixel_y(min_lat, zoom) - _lat_to_pixel_y(max_lat, zoom))
        if span_x <= (_SOCIAL_PREVIEW_WIDTH * 0.85) and span_y <= (_SOCIAL_PREVIEW_HEIGHT * 0.85):
            return zoom
    return _MIN_PREVIEW_ZOOM


def _download_tile(request, tile_source_config: dict, z: int, x: int, y: int):
    service_id = tile_source_config.get("id", "")
    proxy_config = tile_source_config.get("proxy_config")
    headers = build_tile_upstream_headers(service_id, request, proxy_config)

    url_template = tile_source_config.get("url_template")
    if tile_url_template_has_placeholders(url_template):
        tile_url = url_template.format(z=z, x=x, y=y)
    else:
        client_url = tile_source_config.get("client_config", {}).get("url", "")
        tile_url = client_url.format(z=z, x=x, y=y)
        if tile_url.startswith("/"):
            tile_url = f"{public_base_url()}{tile_url}"

    response = requests.get(tile_url, headers=headers, timeout=10)
    if response.status_code != 200:
        return None
    try:
        with Image.open(BytesIO(response.content)) as image:
            return image.convert("RGB")
    except Exception:
        _logger.error(
            "Failed to decode social preview tile image from tile_url=%s: %s",
            tile_url,
            traceback.format_exc(),
        )
        return None


def render_social_preview_png(request, extent, tile_source_config: dict) -> bytes:
    min_lon, min_lat, max_lon, max_lat = extent
    zoom = _pick_zoom_for_extent(extent)

    center_lon = (min_lon + max_lon) / 2.0
    center_lat = (min_lat + max_lat) / 2.0
    center_x = _lon_to_pixel_x(center_lon, zoom)
    center_y = _lat_to_pixel_y(center_lat, zoom)

    out_w = _SOCIAL_PREVIEW_WIDTH
    out_h = _SOCIAL_PREVIEW_HEIGHT
    left_px = center_x - (out_w / 2.0)
    top_px = center_y - (out_h / 2.0)
    right_px = left_px + out_w
    bottom_px = top_px + out_h

    min_tile_x = math.floor(left_px / _TILE_SIZE)
    max_tile_x = math.floor((right_px - 1) / _TILE_SIZE)
    min_tile_y = math.floor(top_px / _TILE_SIZE)
    max_tile_y = math.floor((bottom_px - 1) / _TILE_SIZE)
    tiles_per_axis = 2 ** zoom

    stitched_w = (max_tile_x - min_tile_x + 1) * _TILE_SIZE
    stitched_h = (max_tile_y - min_tile_y + 1) * _TILE_SIZE
    stitched = Image.new("RGB", (stitched_w, stitched_h), _SOCIAL_PREVIEW_BACKGROUND)

    for tile_x in range(min_tile_x, max_tile_x + 1):
        wrapped_x = tile_x % tiles_per_axis
        for tile_y in range(min_tile_y, max_tile_y + 1):
            if tile_y < 0 or tile_y >= tiles_per_axis:
                continue
            tile = _download_tile(request, tile_source_config, zoom, wrapped_x, tile_y)
            if tile is None:
                continue
            paste_x = (tile_x - min_tile_x) * _TILE_SIZE
            paste_y = (tile_y - min_tile_y) * _TILE_SIZE
            stitched.paste(tile, (paste_x, paste_y))

    crop_left = int(left_px - (min_tile_x * _TILE_SIZE))
    crop_top = int(top_px - (min_tile_y * _TILE_SIZE))
    cropped = stitched.crop((crop_left, crop_top, crop_left + out_w, crop_top + out_h))
    output = BytesIO()
    cropped.save(output, format="PNG")
    return output.getvalue()
