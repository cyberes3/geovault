"""Payload models for tag rename and per-feature tag replace."""

from typing import List

from pydantic import BaseModel, ConfigDict, Field, field_validator


class TagRenamePayload(BaseModel):
    model_config = ConfigDict(extra='forbid')

    new_name: str = Field(description="Replacement user tag name")

    @field_validator('new_name')
    @classmethod
    def validate_new_name(cls, value: str) -> str:
        if not value or not value.strip():
            raise ValueError('new_name cannot be empty')
        return value


class FeatureTagsPayload(BaseModel):
    model_config = ConfigDict(extra='forbid')

    tags: List[str] = Field(description="Replacement user tags for the feature")
