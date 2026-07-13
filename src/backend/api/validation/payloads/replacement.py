"""Payload model for applying a replacement upload's geometry to an existing feature."""

from typing import Optional

from pydantic import BaseModel, ConfigDict, Field


class ReplacementGeometryPayload(BaseModel):
    """Pydantic model for applying replacement geometry."""
    model_config = ConfigDict(extra='forbid')

    import_queue_id: int = Field(description="ImportQueue ID containing replacement features")
    feature_index: int = Field(description="Index of feature in ImportQueue to use")
    regenerate_tags: Optional[bool] = Field(default=False, description="Whether to regenerate tags")
