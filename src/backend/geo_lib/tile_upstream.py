"""
Build headers for upstream tile and style requests made by the tile proxy.
"""

from geo_lib.http.outbound import USER_AGENT
from website.public_url import public_base_url

_OSM_FAMILY_SERVICES = frozenset({"osm", "opentopomap", "openhikingmap"})


def build_tile_upstream_headers(service_id: str, request, proxy_config: dict | None = None) -> dict:
    """
    Merge per-source proxy headers with the standard outbound User-Agent and OSM-family Referer rules.
    """
    config = proxy_config or {}
    headers = dict(config.get("headers", {}))
    headers["User-Agent"] = USER_AGENT

    if service_id in _OSM_FAMILY_SERVICES:
        referer = request.META.get("HTTP_REFERER", "").strip()
        if not referer:
            referer = f"{public_base_url()}/"
        if referer:
            headers["Referer"] = referer

    return headers
