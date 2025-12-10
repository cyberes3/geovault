from typing import Dict, Any, Optional, List

from geo_lib.validation.geometry_validation import GeometryValidationError
from geo_lib.validation.geojson.normalize import _normalize_geometry, _normalize_properties
from geo_lib.validation.geojson.models import GeometryModel


def validate_and_normalize_geojson_feature(
    feature: Dict[str, Any],
    preserve_system_tags: Optional[List[str]] = None,
    preserve_geojson_hash: Optional[bool] = False
) -> Dict[str, Any]:
    """
    Validate and normalize a GeoJSON Feature by whitelisting keys and normalizing styles.

    This function:
    - Uses Pydantic models to whitelist only explicitly allowed keys at all levels
    - Automatically removes all non-whitelisted keys via Pydantic validation
    - Performs style normalization (stroke-width, fill, fill-opacity)
    - Validates structure using Pydantic

    Note: `system_tags` and `geojson_hash` are preserved if present in the original properties.
    Use preserve_system_tags to explicitly set system_tags after normalization.

    Args:
        feature: GeoJSON Feature dictionary
        preserve_system_tags: Optional list of system_tags to preserve (will be added back after normalization)
        preserve_geojson_hash: If True, preserve the 'geojson_hash' property even if not in whitelist

    Returns:
        Validated and normalized GeoJSON Feature dictionary

    Raises:
        GeometryValidationError: If feature structure is invalid
    """
    if not isinstance(feature, dict):
        raise GeometryValidationError('Feature must be a dictionary object')

    # Extract and preserve system_tags and geojson_hash before normalization
    original_system_tags = feature.get('properties', {}).get('system_tags')
    original_geojson_hash = feature.get('properties', {}).get('geojson_hash')

    # First, whitelist top-level keys
    allowed_top_level = {'type', 'geometry', 'properties'}
    filtered_feature = {k: v for k, v in feature.items() if k in allowed_top_level}

    if filtered_feature.get('type') != 'Feature':
        raise GeometryValidationError('Feature type must be "Feature"')

    # Whitelist and normalize geometry
    if 'geometry' not in filtered_feature:
        raise GeometryValidationError('Feature must have a geometry object')

    normalized_geometry = _normalize_geometry(filtered_feature['geometry'])

    # Validate geometry structure using Pydantic
    try:
        validated_geometry = GeometryModel(**normalized_geometry)
        normalized_geometry = validated_geometry.model_dump(exclude_none=True)
    except Exception as e:
        raise GeometryValidationError(f'Geometry validation failed: {str(e)}')

    # Normalize properties (Pydantic validation happens inside _normalize_properties)
    # Pass the full feature object to detect polygon geometries in GeometryCollection
    filtered_feature['geometry'] = normalized_geometry
    normalized_properties = _normalize_properties(filtered_feature)

    # Restore preserved values (after validation)
    if preserve_system_tags is not None:
        normalized_properties['system_tags'] = preserve_system_tags
    elif original_system_tags is not None:
        # If not explicitly provided, preserve original if it exists
        normalized_properties['system_tags'] = original_system_tags

    if preserve_geojson_hash and original_geojson_hash is not None:
        normalized_properties['geojson_hash'] = original_geojson_hash

    # Build final normalized feature
    normalized = {
        'type': 'Feature',
        'geometry': normalized_geometry,
        'properties': normalized_properties
    }

    return normalized
