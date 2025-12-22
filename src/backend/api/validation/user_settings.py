"""
Pydantic validation models for user settings.

This module provides validation for user settings using unified Pydantic models.
Settings are structured as nested JSON objects.
"""

from enum import Enum
from functools import lru_cache
from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, ValidationError, ConfigDict, field_validator, model_serializer

from geo_lib.tile_sources.registry import get_all_tile_sources


class ElevationProfileSource(str, Enum):
    """Valid values for map.elevation_profile_source setting."""
    GPS = 'gps'
    API = 'api'


class UnitsPreference(str, Enum):
    """Valid values for account.units setting."""
    METRIC = 'metric'
    IMPERIAL = 'imperial'


class MapSettings(BaseModel):
    """Pydantic model for map settings section."""
    model_config = ConfigDict(extra='forbid')
    
    elevation_profile_source: Optional[ElevationProfileSource] = Field(
        default=ElevationProfileSource.GPS,
        description="Elevation profile data source: 'gps' or 'api'"
    )
    
    default_basemap: Optional[str] = Field(
        default='osm',
        description="Default basemap tile source ID"
    )
    
    replace_icons_low_zoom: Optional[bool] = Field(
        default=True,
        description="Replace custom icons with colored default points when zoomed out to improve visibility"
    )
    
    enable_3d_terrain: Optional[bool] = Field(
        default=False,
        description="Default 3D terrain to on when loading the map. User can still toggle it on/off with the map control button."
    )
    
    enable_hillshade: Optional[bool] = Field(
        default=False,
        description="Default hillshade to on when loading the map. User can still toggle it on/off with the map control."
    )
    
    enable_antialias: Optional[bool] = Field(
        default=False,
        description="Enable anti-aliasing for smoother map rendering. May have a slight performance impact."
    )
    
    @field_validator('default_basemap')
    @classmethod
    def validate_default_basemap(cls, v):
        """Validate that default_basemap is a valid tile source ID."""
        if v is None:
            return 'osm'  # Default fallback
        
        # Get cached tile source IDs
        available_ids = _get_cached_tile_source_ids()
        
        # If the provided value is not in available sources, default to 'osm'
        if v not in available_ids:
            # If 'osm' is available, use it; otherwise use the first available source
            if 'osm' in available_ids:
                return 'osm'
            elif available_ids:
                return list(available_ids)[0]
            else:
                return 'osm'  # Ultimate fallback
        
        return v


class ImportSettings(BaseModel):
    """Pydantic model for import settings section."""
    model_config = ConfigDict(extra='forbid')
    
    overwrite_single_track_name_with_filename: Optional[bool] = Field(
        default=False,
        description="When enabled, overwrite single track feature name with filename (excluding extension)"
    )
    
    show_debug_logs: Optional[bool] = Field(
        default=False,
        description="When enabled, show DEBUG level logs in the import process page"
    )


class AccountSettings(BaseModel):
    """Pydantic model for account settings section."""
    model_config = ConfigDict(extra='forbid')
    
    units: Optional[UnitsPreference] = Field(
        default=UnitsPreference.IMPERIAL,
        description="Units preference: 'metric' or 'imperial'"
    )


class UserSettingsModel(BaseModel):
    """Unified Pydantic model for all user settings."""
    model_config = ConfigDict(extra='forbid')
    
    map: Optional[MapSettings] = Field(default_factory=MapSettings)
    import_: Optional[ImportSettings] = Field(default_factory=ImportSettings, alias='import')
    account: Optional[AccountSettings] = Field(default_factory=AccountSettings)


@lru_cache(maxsize=1)
def _get_cached_tile_source_ids():
    """
    Get cached set of available tile source IDs.
    Tile sources don't change at runtime, so we cache the result.
    
    Returns:
        set: Set of available tile source ID strings
    """
    # Get all available tile source IDs
    all_sources = get_all_tile_sources()
    return set(all_sources.keys())


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
        settings = {}
    
    try:
        # Validate using the unified model
        # Pydantic will use default_factory to create nested models with defaults if missing
        # With extra='forbid', any extra fields will raise a ValidationError
        validated_model = UserSettingsModel.model_validate(settings)
        # Convert back to dict, excluding None values, using aliases for field names
        # Use mode='json' to properly serialize enums without warnings
        validated_dict = validated_model.model_dump(mode='json', exclude_none=True, by_alias=True)
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


# ============================================================================
# User Settings Update Payloads
# ============================================================================

class UserSettingsUpdatePayload(BaseModel):
    """
    Pydantic model for update_user_setting request body.
    Accepts a partial nested JSON object (any settings fields can be updated).
    """
    model_config = ConfigDict(extra='allow')  # Allow any nested settings
    
    # No specific fields defined - accepts any dict structure
    # Validation is done by UserSettingsModel after merging


class BulkUpdateHiddenFeaturesPayload(BaseModel):
    """
    Pydantic model for bulk_update_hidden_features request body.
    """
    model_config = ConfigDict(extra='forbid')
    
    add: Optional[List[Any]] = Field(
        default_factory=list,
        description="List of feature IDs to add to hidden features"
    )
    remove: Optional[List[Any]] = Field(
        default_factory=list,
        description="List of feature IDs to remove from hidden features"
    )

