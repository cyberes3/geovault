"""
Single source of truth for converting `FeatureStore` rows into GeoJSON `Feature`
dicts and `FeatureCollection` responses (geojson + database_id + geojson_hash,
optionally stripping private tags for public shares).
"""
from typing import Any, Iterable, Optional

from api.models import FeatureStore
from geo_lib.processing.tagging.const_strings import strip_private_tags


def geojson_feature_from_parts(
    feature_id: int,
    geojson_data: Optional[dict],
    geojson_hash: Optional[str],
    *,
    public_safe: bool = False,
    include_tags: bool = False,
) -> Optional[dict[str, Any]]:
    """
    Build a GeoJSON `Feature` dict from a FeatureStore row's raw parts.

    Works equally for ORM instances (pass `feature.id, feature.geojson, feature.geojson_hash`)
    and raw-SQL result rows (`SELECT id, geojson, geojson_hash FROM ...`), which is why this
    takes primitives rather than a model instance -- the raw-SQL bbox/search paths exist
    specifically to avoid the overhead of materializing full ORM instances.

    Returns None if `geojson_data` has no geometry (nothing to render).
    """
    if not geojson_data or 'geometry' not in geojson_data:
        return None

    properties = dict(geojson_data.get('properties') or {})
    properties['database_id'] = feature_id

    if public_safe and not include_tags:
        strip_private_tags(properties)

    return {
        "type": "Feature",
        "geometry": geojson_data.get('geometry'),
        "properties": properties,
        "geojson_hash": geojson_hash,
    }


def geojson_feature_from_instance(
    feature: FeatureStore,
    *,
    public_safe: bool = False,
    include_tags: bool = False,
) -> Optional[dict[str, Any]]:
    """Convenience wrapper of `geojson_feature_from_parts` for an ORM `FeatureStore` instance."""
    return geojson_feature_from_parts(
        feature.id, feature.geojson, feature.geojson_hash,
        public_safe=public_safe, include_tags=include_tags,
    )


def build_feature_collection(features: Iterable[dict[str, Any]], **extra_fields: Any) -> dict[str, Any]:
    """
    Wrap a list of already-built GeoJSON `Feature` dicts into a `FeatureCollection`.

    `extra_fields` are merged into the returned dict alongside `type`/`features` (not into
    the FeatureCollection itself) -- e.g. callers commonly add `feature_count`/`tag`/`query`.
    """
    return {
        "type": "FeatureCollection",
        "features": list(features),
        **extra_fields,
    }


def build_feature_collection_from_instances(
    features: Iterable[FeatureStore],
    *,
    public_safe: bool = False,
    include_tags: bool = False,
) -> dict[str, Any]:
    """Build a `FeatureCollection` dict directly from an iterable of `FeatureStore` instances."""
    geojson_features = [
        f for f in (
            geojson_feature_from_instance(feature, public_safe=public_safe, include_tags=include_tags)
            for feature in features
        ) if f is not None
    ]
    return build_feature_collection(geojson_features)
