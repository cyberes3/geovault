"""
Share-based export functionality.
"""

from typing import Callable, Dict

from geo_lib.processing.tagging.const_strings import strip_private_tags

from .geojson_preprocessor import prepare_geojson_for_kmz
from .geojson_to_kmz import KMZOptions


def build_share_feature_collection(
        tag_share,
        collection_share,
        share,
        build_base_query_func: Callable,
        build_collection_query_func: Callable,
        base_dir: str,
        icon_storage_dir: str,
) -> Dict:
    """
    Build a GeoJSON FeatureCollection from a share.

    Args:
        tag_share: TagShare object or None
        collection_share: CollectionShare object or None
        share: The share object (either tag_share or collection_share)
        build_base_query_func: Function to build base query for tag shares
        build_collection_query_func: Function to build collection query
        base_dir: Base directory path (e.g., Django BASE_DIR)
        icon_storage_dir: Directory where user icons are stored

    Returns:
        GeoJSON FeatureCollection dict
    """
    # Query all features matching the share criteria
    # Use the same query logic as map loading for consistency
    if tag_share:
        # For tag shares, use _build_base_query (same as map loading)
        features = build_base_query_func(share.user.id, tag=tag_share.tag)
    else:
        # For collection shares, use _build_collection_query (same as map loading)
        features = build_collection_query_func(share.user.id, collection_share.collection.id)

    # Convert all features to GeoJSON FeatureCollection
    geojson_features = []
    for feature in features:
        geojson_data = feature.geojson
        if not geojson_data or 'geometry' not in geojson_data:
            continue

        if not share.include_tags:
            # Strip tags/system_tags before they can end up in the KMZ placemark
            # description (see _apply_properties_to_placemark) - same rule the map
            # view GeoJSON endpoints apply.
            properties = dict(geojson_data.get('properties') or {})
            strip_private_tags(properties)
            geojson_data = {**geojson_data, 'properties': properties}

        # Pre-process GeoJSON for icon embedding
        geojson_for_kmz = prepare_geojson_for_kmz(geojson_data, base_dir, icon_storage_dir)
        geojson_features.append(geojson_for_kmz)

    # Create FeatureCollection
    feature_collection = {
        "type": "FeatureCollection",
        "features": geojson_features
    }

    return feature_collection


def prepare_kmz_options_for_share(share_name: str, base_dir: str) -> KMZOptions:
    """
    Prepare KMZOptions for share export.

    Args:
        share_name: Name of the share (tag name or collection name)
        base_dir: Base directory path (e.g., Django BASE_DIR)

    Returns:
        KMZOptions configured for share export
    """
    return KMZOptions(
        document_name=share_name,
        embed_local_icons=True,
        icon_base_path=str(base_dir),
    )
