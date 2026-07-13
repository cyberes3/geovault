"""Payload models for the import-queue-to-featurestore endpoints."""

from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field


class ImportToFeaturestorePayload(BaseModel):
    """Pydantic model for import_to_featurestore request body."""
    model_config = ConfigDict(extra='forbid')

    import_custom_icons: Optional[bool] = Field(default=True, description="Whether to import custom icons")
    skipped_feature_ids: Optional[List[str]] = Field(default_factory=list, description="Feature IDs to skip during import")


class SkipStatePayload(BaseModel):
    """Pydantic model for save_skip_state request body."""
    model_config = ConfigDict(extra='forbid')

    skipped_feature_ids: List[str] = Field(default_factory=list, description="List of feature IDs that are skipped")
