"""Shared utilities for feature updates"""
from api.utils.responses import error_response
from geo_lib.tags.const_strings import CONST_INTERNAL_TAGS, is_protected_tag
from geo_lib.validation.geojson.geojson_whitelist import validate_and_normalize_geojson_feature
from website.settings_utils import get_required_setting


def _validate_tags(tags):
    """
    Validate a list of tags.
    
    Args:
        tags: List of tags to validate
        
    Returns:
        Tuple of (is_valid, error_response) where error_response is None if valid
    """
    if not isinstance(tags, list):
        return False, error_response('tags must be an array', 400)

    for tag in tags:
        if not isinstance(tag, str):
            return False, error_response('all tags must be strings', 400)

        # Check if tag is a system tag (protected tag)
        if is_protected_tag(tag, CONST_INTERNAL_TAGS):
            return False, error_response(
                'System tags (type, import-year, import-month, feature-year, feature-month, source-file, track, elevation, reverse reverse_geocoding) cannot be added as user tags',
                400
            )

        # Validate tag length
        tag_max_length = get_required_setting('TAG_MAX_LENGTH')
        if len(tag) > tag_max_length:
            return False, error_response(
                f'Tag "{tag[:50]}..." exceeds maximum length of {tag_max_length} characters',
                400
            )

        # Validate tag is not empty after stripping
        if not tag.strip():
            return False, error_response('Tags cannot be empty or contain only whitespace', 400)

        # Validate tag format: no control characters
        if any(ord(c) < 32 and c not in '\t\n\r' for c in tag):
            return False, error_response('Tags cannot contain control characters', 400)

    return True, None


def extract_system_tags(feature: dict) -> list:
    """
    Extract and normalize system_tags from a feature dictionary.
    
    Args:
        feature: Feature dictionary (can be full feature or just properties)
        
    Returns:
        List of system_tags (empty list if not present or invalid)
    """
    if isinstance(feature, dict):
        properties = feature.get('properties', feature)
        system_tags = properties.get('system_tags', [])
        if isinstance(system_tags, list):
            return system_tags
    return []


def _validate_and_preserve_feature(feature: dict) -> dict:
    """
    Validate and normalize a feature, preserving system_tags and geojson_hash.
    
    Args:
        feature: GeoJSON Feature dictionary

    Returns:
        Validated and normalized feature dictionary
        
    Raises:
        GeometryValidationError: If validation fails
    """
    # Extract system_tags before validation
    system_tags = extract_system_tags(feature)

    # Validate and normalize
    normalized_feature = validate_and_normalize_geojson_feature(
        feature,
        preserve_system_tags=system_tags,
        preserve_geojson_hash=True
    )

    # Ensure system_tags are preserved after normalization
    normalized_feature['properties']['system_tags'] = system_tags

    return normalized_feature


def _validate_and_preserve_system_tags(properties_dict, original_system_tags):
    """
    Validate that system_tags are not being modified and return preserved system_tags.
    Silently discards any received system_tags and replaces them with originals from the DB.
    
    Args:
        properties_dict: Dictionary containing properties (may include system_tags)
        original_system_tags: Original system_tags from the feature
        
    Returns:
        Tuple of (is_valid, error_response, preserved_system_tags) where error_response is None if valid
    """
    # Ensure original_system_tags is a list
    if not isinstance(original_system_tags, list):
        original_system_tags = []

    # Silently discard any received system_tags and replace with originals from DB
    if 'system_tags' in properties_dict:
        del properties_dict['system_tags']

    return True, None, original_system_tags
