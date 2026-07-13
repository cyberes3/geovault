"""Payload models for collection create/update endpoints."""

from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator


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
