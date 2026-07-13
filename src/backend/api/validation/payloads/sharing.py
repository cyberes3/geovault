"""
Payload model for the unified share-creation endpoint.

Tag/collection/feature shares are created exclusively through the single unified
`POST /api/sharing/create/` endpoint (see api/views/sharing/management.py), so
UnifiedSharePayload below is the only payload model needed here.
"""

import uuid
from typing import Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class UnifiedSharePayload(BaseModel):
    """Unified Pydantic model for creating any type of share."""
    model_config = ConfigDict(extra='forbid')

    share_type: str = Field(description="Type of share: 'tag', 'collection', or 'feature' (required)")
    allow_downloads: Optional[bool] = Field(default=False, description="Allow downloads from share")

    # Tag share fields
    tag: Optional[str] = Field(default=None, description="Tag to share (required if share_type is 'tag')")

    # Collection share fields
    collection_id: Optional[str] = Field(default=None, description="Collection ID to share (required if share_type is 'collection', must be valid UUID)")
    include_tags: Optional[bool] = Field(default=False, description="Include tags in shared features (applies to tag, collection, and feature shares)")

    # Feature share fields
    feature_id: Optional[int] = Field(default=None, description="Feature ID to share (required if share_type is 'feature')")

    @field_validator('share_type')
    @classmethod
    def validate_share_type(cls, v: str) -> str:
        """Ensure share_type is one of the allowed values."""
        if v not in ['tag', 'collection', 'feature']:
            raise ValueError("share_type must be 'tag', 'collection', or 'feature'")
        return v

    @field_validator('tag')
    @classmethod
    def validate_tag(cls, v: Optional[str], info) -> Optional[str]:
        """Validate tag if share_type is 'tag'."""
        if info.data.get('share_type') == 'tag':
            if not v or not v.strip():
                raise ValueError('tag is required when share_type is "tag"')
            return v.strip()
        return v

    @field_validator('collection_id')
    @classmethod
    def validate_collection_id(cls, v: Optional[str], info) -> Optional[str]:
        """Validate collection_id if share_type is 'collection'."""
        if info.data.get('share_type') == 'collection':
            if not v:
                raise ValueError('collection_id is required when share_type is "collection"')
            try:
                uuid.UUID(v)
            except (ValueError, AttributeError):
                raise ValueError('collection_id must be a valid UUID')
        return v

    @field_validator('feature_id')
    @classmethod
    def validate_feature_id(cls, v: Optional[int], info) -> Optional[int]:
        """Validate feature_id if share_type is 'feature'."""
        if info.data.get('share_type') == 'feature':
            if v is None:
                raise ValueError('feature_id is required when share_type is "feature"')
            if not isinstance(v, int) or v <= 0:
                raise ValueError('feature_id must be a positive integer')
        return v


class UpdateFeatureSharePayload(BaseModel):
    """Pydantic model for updating a feature share's allow_downloads/include_tags settings."""
    model_config = ConfigDict(extra='forbid')

    allow_downloads: Optional[bool] = Field(default=None, description="Whether to allow downloads")
    include_tags: Optional[bool] = Field(default=None, description="Whether to include tags in the shared feature")

    @model_validator(mode='after')
    def validate_at_least_one_field(self) -> 'UpdateFeatureSharePayload':
        if self.allow_downloads is None and self.include_tags is None:
            raise ValueError('allow_downloads or include_tags is required')
        return self
