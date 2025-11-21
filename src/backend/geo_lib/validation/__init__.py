"""
Validation utilities for GeoJSON geometry and features.
"""

from geo_lib.validation.geometry_validation import (
    validate_geometry,
    validate_feature_geometry,
    normalize_and_validate_feature_update,
    is_valid_geometry_type,
    get_valid_geometry_types,
    GeometryValidationError
)

__all__ = [
    'validate_geometry',
    'validate_feature_geometry',
    'normalize_and_validate_feature_update',
    'is_valid_geometry_type',
    'get_valid_geometry_types',
    'GeometryValidationError'
]

