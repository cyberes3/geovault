"""
GeoJSON preprocessing utilities for export functionality.
"""

import copy

from .icon_resolver import resolve_icon_path


def prepare_geojson_for_kmz(geojson: dict, base_dir: str, icon_storage_dir: str) -> dict:
    """
    Pre-process GeoJSON to convert icon API URLs to filesystem paths for embedding.

    Args:
        geojson: Original GeoJSON dict
        base_dir: Base directory path (e.g., Django BASE_DIR)
        icon_storage_dir: Directory where user icons are stored

    Returns:
        Modified GeoJSON dict with icon paths converted
    """
    # Deep copy to avoid modifying the original
    geojson_copy = copy.deepcopy(geojson)

    props = geojson_copy.get("properties") or {}
    icon_keys = ["icon", "icon-href", "iconUrl", "icon_url", "marker-icon", "marker-symbol", "symbol"]

    for key in icon_keys:
        if key in props and props[key]:
            icon_url = props[key]
            # Resolve API URL to filesystem path
            icon_path = resolve_icon_path(icon_url, base_dir, icon_storage_dir)
            if icon_path:
                props[key] = icon_path

    return geojson_copy
