"""Payload models for bulk styling/tagging operations (features, collections, and
import queue items)."""

from typing import Any, List, Optional

from pydantic import BaseModel, ConfigDict, Field, field_validator

from geo_lib.validation.styling_validation import is_valid_hex_color, is_valid_icon_url


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


class SaveBulkOperationsPayload(BaseModel):
    """Pydantic model for save_bulk_operations request body (import queue items)."""
    model_config = ConfigDict(extra='forbid')

    bulk_operations: BulkOperationsPayload = Field(
        default_factory=BulkOperationsPayload,
        description="Bulk operations (tags/styling) to save for later application at import time",
    )
