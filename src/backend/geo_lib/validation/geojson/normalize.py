from typing import Dict, Any

from geo_lib.validation.geojson.models import PropertiesModel
from geo_lib.validation.styling_validation import normalize_feature_colors_and_styles


def _normalize_geometry(geometry: Dict[str, Any]) -> Dict[str, Any]:
    """
    Normalize geometry by whitelisting keys.

    Args:
        geometry: Geometry dictionary

    Returns:
        Normalized geometry with only whitelisted keys
    """
    geom_type = geometry.get('type', '')

    # Allowed keys for geometry
    allowed_keys = {'type', 'coordinates', 'geometries'}

    normalized = {k: v for k, v in geometry.items() if k in allowed_keys}

    # For GeometryCollection, recursively normalize each geometry
    if geom_type == 'GeometryCollection' and 'geometries' in normalized:
        normalized['geometries'] = [
            _normalize_geometry(geom) if isinstance(geom, dict) else geom
            for geom in normalized['geometries']
        ]

    return normalized


def _normalize_properties(feature: Dict[str, Any]) -> Dict[str, Any]:
    """
    Normalize properties by validating with Pydantic and applying style normalization.

    Pydantic automatically filters out any fields not defined in PropertiesModel.

    Args:
        feature: Full GeoJSON Feature dictionary (used to access both properties and geometry)

    Returns:
        Normalized properties with only whitelisted keys and normalized styles
    """
    properties = feature.get('properties', {})
    geometry = feature.get('geometry', {})

    # Validate with Pydantic - this automatically filters out extra fields
    validated_properties = PropertiesModel(**properties)
    # Use mode='json' to ensure datetime objects are serialized to ISO strings
    normalized = validated_properties.model_dump(mode='json', exclude_none=True, by_alias=True)

    # Normalize colors and apply style normalization using shared function
    normalize_feature_colors_and_styles(normalized, geometry)

    return normalized
