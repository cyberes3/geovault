"""
Pydantic validation models for user settings.

This module provides validation for user settings using pydantic models.
Each setting key has a corresponding validator that ensures type safety and value constraints.
"""

from enum import Enum
from typing import Any, Dict, Optional

from pydantic import BaseModel, Field, ValidationError, ConfigDict


class ElevationProfileSource(str, Enum):
    """Valid values for map.elevation_profile_source setting."""
    GPS = 'gps'
    API = 'api'


class MapElevationProfileSourceSetting(BaseModel):
    """Pydantic model for map.elevation_profile_source setting."""
    model_config = ConfigDict(extra='forbid')
    
    value: ElevationProfileSource = Field(
        ...,
        description="Elevation profile data source: 'gps' or 'api'"
    )


# Registry of setting validators by key
SETTING_VALIDATORS: Dict[str, type[BaseModel]] = {
    'map.elevation_profile_source': MapElevationProfileSourceSetting,
}


def validate_setting(key: str, value: Any, strict: bool = True) -> tuple[bool, Optional[str], Optional[Dict[str, Any]]]:
    """
    Validate a user setting key-value pair.
    
    Args:
        key: The setting key (e.g., 'map.elevation_profile_source')
        value: The setting value to validate
        strict: If True, reject unknown keys. If False, only validate known keys.
        
    Returns:
        Tuple of (is_valid, error_message, error_details)
        - is_valid: True if validation passes
        - error_message: Human-readable error message if validation fails
        - error_details: Detailed error information (field-level errors) if validation fails
    """
    # Check if key is in registry
    if key not in SETTING_VALIDATORS:
        if strict:
            return False, f"Unknown setting key: '{key}'", None
        else:
            # In non-strict mode, unknown keys are allowed (for reading from DB)
            return True, None, None
    
    # Get the validator class
    validator_class = SETTING_VALIDATORS[key]
    
    try:
        # Validate the value using the pydantic model
        # Wrap value in a dict since our models expect {'value': ...}
        validator_class(value=value)
        return True, None, None
    except ValidationError as e:
        # Extract detailed error information
        error_details = {}
        error_messages = []
        
        for error in e.errors():
            field_path = '.'.join(str(loc) for loc in error['loc'])
            error_msg = error['msg']
            error_type = error['type']
            
            error_details[field_path] = error_msg
            error_messages.append(f"{field_path}: {error_msg}")
        
        # Create a human-readable error message
        if error_messages:
            error_message = '; '.join(error_messages)
        else:
            error_message = 'Validation failed'
        
        return False, error_message, error_details


def validate_settings_dict(settings: Dict[str, Any]) -> tuple[Dict[str, Any], list[str]]:
    """
    Validate all settings in a dictionary, filtering out invalid ones.
    
    This function is used when reading settings from the database to ensure
    data integrity. Invalid settings are filtered out and logged, but valid
    settings are still returned.
    
    Args:
        settings: Dictionary of setting key-value pairs
        
    Returns:
        Tuple of (validated_settings, invalid_keys)
        - validated_settings: Dictionary containing only valid settings
        - invalid_keys: List of keys that were invalid and filtered out
    """
    if not settings:
        return {}, []
    
    validated_settings = {}
    invalid_keys = []
    
    for key, value in settings.items():
        # Use non-strict mode when reading from DB (allow unknown keys)
        is_valid, error_message, _ = validate_setting(key, value, strict=False)
        
        if is_valid:
            validated_settings[key] = value
        else:
            invalid_keys.append(key)
    
    return validated_settings, invalid_keys

