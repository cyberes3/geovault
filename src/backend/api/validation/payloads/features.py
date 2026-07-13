"""Payload models for single/bulk feature metadata update and deletion endpoints."""

from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator

from api.validation.payloads.base import BaseMetadataFields


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


class BulkDeleteByTagPayload(BaseModel):
    """Pydantic model for bulk_delete_features_by_tag request body."""
    model_config = ConfigDict(extra='forbid')

    tag: str = Field(description="The tag to search for and delete features with")

    @field_validator('tag')
    @classmethod
    def validate_tag_not_empty(cls, v: str) -> str:
        if not v:
            raise ValueError('Tag parameter is required')
        return v
