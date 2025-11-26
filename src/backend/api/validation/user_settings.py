"""
Pydantic validation models for user settings.

This module provides validation for user settings using unified Pydantic models.
Settings are structured as nested JSON objects.
"""

from enum import Enum
from typing import Any, Dict, Optional

from pydantic import BaseModel, Field, ValidationError, ConfigDict


class ElevationProfileSource(str, Enum):
    """Valid values for map.elevation_profile_source setting."""
    GPS = 'gps'
    API = 'api'


class MapSettings(BaseModel):
    """Pydantic model for map settings section."""
    model_config = ConfigDict(extra='ignore')
    
    elevation_profile_source: Optional[ElevationProfileSource] = Field(
        default=None,
        description="Elevation profile data source: 'gps' or 'api'"
    )


class UserSettingsModel(BaseModel):
    """Unified Pydantic model for all user settings."""
    model_config = ConfigDict(extra='ignore')
    
    map: Optional[MapSettings] = Field(default=None)


def validate_settings(settings: Dict[str, Any]) -> tuple[bool, Optional[str], Optional[Dict[str, Any]], Optional[Dict[str, Any]]]:
    """
    Validate user settings dictionary using unified Pydantic model.
    
    Args:
        settings: Dictionary of nested settings (e.g., {"map": {"elevation_profile_source": "api"}})
        
    Returns:
        Tuple of (is_valid, error_message, error_details, validated_dict)
        - is_valid: True if validation passes
        - error_message: Human-readable error message if validation fails
        - error_details: Detailed error information (field-level errors) if validation fails
        - validated_dict: Validated and normalized settings dictionary (or None if validation fails)
    """
    if not settings:
        return True, None, None, {}
    
    try:
        # Validate using the unified model
        validated_model = UserSettingsModel.model_validate(settings)
        # Convert back to dict, excluding None values
        validated_dict = validated_model.model_dump(exclude_none=True)
        return True, None, None, validated_dict
    except ValidationError as e:
        # Extract detailed error information
        error_details = {}
        error_messages = []
        
        for error in e.errors():
            field_path = '.'.join(str(loc) for loc in error['loc'])
            error_msg = error['msg']
            
            error_details[field_path] = error_msg
            error_messages.append(f"{field_path}: {error_msg}")
        
        # Create a human-readable error message
        if error_messages:
            error_message = '; '.join(error_messages)
        else:
            error_message = 'Validation failed'
        
        return False, error_message, error_details, None

