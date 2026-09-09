"""Payload models for the import-queue-to-featurestore endpoints."""

from typing import List, Optional

from pydantic import BaseModel, ConfigDict, Field


class ImportToFeaturestorePayload(BaseModel):
    """Pydantic model for import_to_featurestore request body."""
    model_config = ConfigDict(extra='forbid')

    import_custom_icons: Optional[bool] = Field(default=True, description="Whether to import custom icons")
    skipped: Optional[List[str]] = Field(default_factory=list, description="SkipIntent skipped hashes")
    restored: Optional[List[str]] = Field(default_factory=list, description="SkipIntent restored geometry hashes")


class RecheckDuplicatesPayload(BaseModel):
    """Pydantic model for recheck_duplicates request body."""
    model_config = ConfigDict(extra='forbid')

    page: Optional[int] = Field(default=1, description="Current process-status page to keep after recheck")


class SkipStatePayload(BaseModel):
    """Pydantic model for save_skip_state request body."""
    model_config = ConfigDict(extra='forbid')

    skipped: List[str] = Field(default_factory=list, description="SkipIntent skipped hashes")
    restored: List[str] = Field(default_factory=list, description="SkipIntent restored geometry hashes")
