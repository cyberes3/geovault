"""
Single feature export functionality.
"""

from .geojson_to_kmz import KMZOptions


def prepare_kmz_options_for_feature(feature_name: str, base_dir: str) -> KMZOptions:
    """
    Prepare KMZOptions for single feature export.

    Args:
        feature_name: Name of the feature
        base_dir: Base directory path (e.g., Django BASE_DIR)

    Returns:
        KMZOptions configured for feature export
    """
    return KMZOptions(
        document_name=feature_name,
        embed_local_icons=True,
        icon_base_path=str(base_dir),
    )
