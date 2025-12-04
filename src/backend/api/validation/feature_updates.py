"""
Pydantic validation models for feature update endpoints.

This module provides validation for feature update payloads using Pydantic models.
Validates structure and content of feature update requests before processing.
"""

from datetime import datetime
from typing import Any, Dict, List, Optional
import uuid

from pydantic import BaseModel, Field, ValidationError, ConfigDict, field_validator

from api.utils.responses import error_response
from geo_lib.validation.styling_validation import is_valid_hex_color, is_valid_icon_url


# ============================================================================
# Base Models and Mixins
# ============================================================================

class BaseMetadataFields(BaseModel):
    """Base model with common metadata fields for features."""
    model_config = ConfigDict(extra='forbid')

    name: Optional[str] = Field(default=None, description="Feature name")
    description: Optional[str] = Field(default=None, description="Feature description")
    tags: Optional[List[str]] = Field(default=None, description="Feature tags")
    created: Optional[str] = Field(default=None, description="Feature creation date (ISO format)")

    @field_validator('created')
    @classmethod
    def validate_created(cls, v: Any) -> Optional[str]:
        """Validate that created field is a valid ISO datetime string."""
        if v is None:
            return None
        try:
            datetime.fromisoformat(v.replace('Z', '+00:00'))
        except (ValueError, AttributeError):
            raise ValueError('created must be a valid ISO datetime string')
        return v


# ============================================================================
# Feature Update Models
# ============================================================================

class FeatureUpdateProperties(BaseMetadataFields):
    """Pydantic model for updatable feature properties (import_item endpoint)."""
    geojson_hash: Optional[str] = Field(default=None, description="GeoJSON hash (used for matching, not updated)")


class FeatureUpdate(BaseModel):
    """Pydantic model for feature update structure in import_item endpoint."""
    model_config = ConfigDict(extra='forbid')

    properties: FeatureUpdateProperties = Field(description="Feature properties to update")


class FeatureUpdatePayload(BaseModel):
    """Pydantic model for update_import_item request body."""
    model_config = ConfigDict(extra='forbid')

    features: List[FeatureUpdate] = Field(
        description="List of features to update (required)"
    )


class BulkFeatureUpdate(BaseMetadataFields):
    """Pydantic model for individual feature update in bulk_update_features_metadata."""
    feature_id: int = Field(description="ID of the feature to update")


class BulkFeatureUpdatePayload(BaseModel):
    """Pydantic model for bulk_update_features_metadata request body."""
    model_config = ConfigDict(extra='forbid')

    updates: List[BulkFeatureUpdate] = Field(
        min_length=1,
        description="List of feature updates"
    )


class FeatureMetadataUpdate(BaseMetadataFields):
    """Pydantic model for single feature metadata update (update_feature_metadata endpoint)."""
    pass  # Inherits all fields from BaseMetadataFields


# ============================================================================
# Bulk Operations Models
# ============================================================================


class BulkOperationsPayload(BaseModel):
    """Pydantic model for bulk operations (styling/tagging)."""
    model_config = ConfigDict(extra='forbid')
    
    tags: Optional[List[str]] = Field(default=None, description="Tags to apply")
    pointColor: Optional[str] = Field(default=None, description="Point color (hex)")
    pointIcon: Optional[str] = Field(default=None, description="Point icon URL")
    lineColor: Optional[str] = Field(default=None, description="Line color (hex)")
    polyColor: Optional[str] = Field(default=None, description="Polygon color (hex)")
    
    @field_validator('pointColor', 'lineColor', 'polyColor')
    @classmethod
    def validate_color(cls, v: Any) -> Optional[str]:
        if v is None:
            return None
        if not is_valid_hex_color(v):
            raise ValueError('Invalid hex color')
        return v
    
    @field_validator('pointIcon')
    @classmethod
    def validate_icon(cls, v: Any) -> Optional[str]:
        if v is None:
            return None
        if not is_valid_icon_url(v):
            raise ValueError('Invalid icon URL')
        return v


# ============================================================================
# Collection Models
# ============================================================================

class CollectionBaseFields(BaseModel):
    """Base model for collection operations."""
    model_config = ConfigDict(extra='forbid')

    name: Optional[str] = Field(default=None, description="Collection name")
    description: Optional[str] = Field(default=None, description="Collection description")
    tags: Optional[List[str]] = Field(default=None, description="Collection tags")
    feature_ids: Optional[List[int]] = Field(default=None, description="Feature IDs in collection")


class CollectionCreatePayload(CollectionBaseFields):
    """Pydantic model for creating a collection (name is required)."""
    name: str = Field(description="Collection name (required)")

    @field_validator('name')
    @classmethod
    def validate_name_not_empty(cls, v: str) -> str:
        """Ensure name is not empty or whitespace only."""
        if not v or not v.strip():
            raise ValueError('name cannot be empty')
        return v


class CollectionUpdatePayload(CollectionBaseFields):
    """Pydantic model for updating a collection (all fields optional)."""
    pass  # Inherits all fields as optional from CollectionBaseFields


# ============================================================================
# Share Models
# ============================================================================

class ShareBaseFields(BaseModel):
    """Base model for share operations."""
    model_config = ConfigDict(extra='forbid')

    allow_downloads: Optional[bool] = Field(default=False, description="Allow downloads from share")


class TagSharePayload(ShareBaseFields):
    """Pydantic model for creating a tag share."""
    tag: str = Field(description="Tag to share (required)")

    @field_validator('tag')
    @classmethod
    def validate_tag_not_empty(cls, v: str) -> str:
        """Ensure tag is not empty or whitespace only."""
        if not v or not v.strip():
            raise ValueError('tag cannot be empty')
        return v


class CollectionSharePayload(ShareBaseFields):
    """Pydantic model for creating a collection share."""
    collection_id: str = Field(description="Collection ID to share (required, must be valid UUID)")
    include_tags: Optional[bool] = Field(default=False, description="Include tags in shared features")

    @field_validator('collection_id')
    @classmethod
    def validate_collection_id_uuid(cls, v: str) -> str:
        """Ensure collection_id is a valid UUID."""
        try:
            uuid.UUID(v)
        except (ValueError, AttributeError):
            raise ValueError('collection_id must be a valid UUID')
        return v


# ============================================================================
# Replacement Geometry Models
# ============================================================================

class ReplacementGeometryPayload(BaseModel):
    """Pydantic model for applying replacement geometry."""
    model_config = ConfigDict(extra='forbid')

    import_queue_id: int = Field(description="ImportQueue ID containing replacement features")
    feature_index: int = Field(description="Index of feature in ImportQueue to use")
    regenerate_tags: Optional[bool] = Field(default=False, description="Whether to regenerate tags")


# ============================================================================
# Import Models
# ============================================================================

class ImportToFeaturestorePayload(BaseModel):
    """Pydantic model for import_to_featurestore request body."""
    model_config = ConfigDict(extra='forbid')

    import_custom_icons: Optional[bool] = Field(default=True, description="Whether to import custom icons")
    skipped_feature_ids: Optional[List[str]] = Field(default_factory=list, description="Feature IDs to skip during import")


class SkipStatePayload(BaseModel):
    """Pydantic model for save_skip_state request body."""
    model_config = ConfigDict(extra='forbid')

    skipped_feature_ids: List[str] = Field(default_factory=list, description="List of feature IDs that are skipped")


# ============================================================================
# Validation Functions
# ============================================================================

import json
from functools import wraps
from typing import Type


def validate_pydantic_model(model_class: Type[BaseModel], data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Generic validation function for any Pydantic model.
    
    Args:
        model_class: The Pydantic model class to validate against
        data: Dictionary from request body to validate
        
    Returns:
        Validated and normalized dictionary
        
    Raises:
        ValidationError: If validation fails
    """
    validated_model = model_class.model_validate(data)
    return validated_model.model_dump(mode='json', exclude_none=True)


def validate_payload(model_class: Type[BaseModel], allow_empty: bool = False):
    """
    Decorator that validates request payload against a Pydantic model.
    
    Automatically handles JSON parsing and validation, injecting validated_data
    into the decorated function.
    
    Args:
        model_class: The Pydantic model class to validate against
        allow_empty: If True, allows empty request body (returns empty dict)
        
    Usage:
        @validate_payload(CollectionCreatePayload)
        def create_collection(request, validated_data):
            # validated_data is already parsed and validated
            name = validated_data['name']
            ...
            
        @validate_payload(ImportToFeaturestorePayload, allow_empty=True)
        def import_to_featurestore(request, validated_data):
            # Empty body is allowed, validated_data will be {}
            ...
    """
    def decorator(view_func):
        @wraps(view_func)
        def wrapper(request, *args, **kwargs):
            
            # Handle empty body - check for truly empty or just boundary markers
            # Django Test Client may send boundary markers even with no actual content
            body = request.body
            has_body = bool(body and len(body) > 0)
            
            # If there's a body, try to parse it as JSON
            # If it fails (e.g., it's just boundary markers), treat as empty
            if has_body:
                try:
                    data = json.loads(body)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    # Check if this looks like intentional JSON (has content-type header or starts with { or [)
                    content_type = request.META.get('CONTENT_TYPE', '')
                    body_str = body.decode('utf-8', errors='ignore') if isinstance(body, bytes) else str(body)
                    looks_like_json = 'json' in content_type.lower() or body_str.strip().startswith(('{', '['))
                    
                    if looks_like_json:
                        # Intentional JSON that failed to parse - return error
                        return error_response('Invalid JSON in request body', code=400)
                    elif allow_empty:
                        # Not JSON, treat as empty if allowed
                        kwargs['validated_data'] = {}
                        return view_func(request, *args, **kwargs)
                    else:
                        return error_response('Invalid JSON in request body', code=400)
                
                # Valid JSON - validate against Pydantic model
                try:
                    validated_data = validate_pydantic_model(model_class, data)
                    kwargs['validated_data'] = validated_data
                    return view_func(request, *args, **kwargs)
                except ValidationError:
                    return error_response('Invalid request format', code=400)
            else:
                # No body at all
                if allow_empty:
                    kwargs['validated_data'] = {}
                    return view_func(request, *args, **kwargs)
                else:
                    return error_response('Request body is required', code=400)
        
        return wrapper
    return decorator
