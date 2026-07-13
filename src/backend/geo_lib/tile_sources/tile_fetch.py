"""
Upstream tile fetching: pure HTTP-client logic (via `requests`) for retrieving one tile from
an external tile server. No Django response/caching-header concerns — those live in
`api.views.tiles.tile_proxy`, which orchestrates this alongside the disk cache in
`tile_sources.tile_cache`.
"""
from dataclasses import dataclass

import requests

from geo_lib.tile_upstream import build_tile_upstream_headers

_UPSTREAM_TIMEOUT_SECONDS = 10

_CONTENT_TYPE_TO_EXTENSION = (
    ('application/x-protobuf', 'pbf'),
    ('application/vnd.mapbox-vector-tile', 'pbf'),
    ('image/webp', 'webp'),
    ('image/png', 'png'),
    ('image/jpeg', 'jpg'),
    ('image/jpg', 'jpg'),
)


class UpstreamTileError(Exception):
    """Raised when the upstream tile server returns a non-200 response."""

    def __init__(self, status_code: int):
        self.status_code = status_code
        super().__init__(f'Upstream error: {status_code}')


@dataclass
class UpstreamTileResult:
    data: bytes
    content_type: str
    extension: str


def _extension_from_content_type(content_type: str, fallback: str) -> str:
    for needle, extension in _CONTENT_TYPE_TO_EXTENSION:
        if needle in content_type:
            return extension
    return fallback


def fetch_tile_from_upstream(
    service: str,
    tile_url: str,
    request,
    proxy_config: dict,
    fallback_extension: str = 'tile',
) -> UpstreamTileResult:
    """
    Fetch one tile from its upstream server, streaming the response.

    Raises `UpstreamTileError` if the upstream server returns a non-200 status.
    """
    headers = build_tile_upstream_headers(service, request, proxy_config)
    response = requests.get(tile_url, headers=headers, stream=True, timeout=_UPSTREAM_TIMEOUT_SECONDS)

    if response.status_code != 200:
        raise UpstreamTileError(response.status_code)

    raw_content_type = response.headers.get('Content-Type', '')
    content_type = raw_content_type.split(';')[0].strip() if raw_content_type else 'image/png'
    extension = _extension_from_content_type(content_type, fallback_extension)
    return UpstreamTileResult(data=response.content, content_type=content_type, extension=extension)
