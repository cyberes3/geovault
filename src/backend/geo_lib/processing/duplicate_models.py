"""
Pydantic models for duplicate tracking during import operations.

This module provides type-safe models for tracking and reporting duplicate features
during the import process.
"""

from typing import List, Optional
from pydantic import BaseModel, Field, ConfigDict


class SkippedDuplicateFeature(BaseModel):
    """Model for a single skipped duplicate feature."""
    model_config = ConfigDict(extra='forbid')
    
    name: str = Field(description="Name of the skipped feature")
    hash: str = Field(description="Feature hash/ID")
    queue_item_id: Optional[int] = Field(default=None, description="ID of the queue item containing the duplicate (for queue-level duplicates)")
    queue_item_filename: Optional[str] = Field(default=None, description="Filename of the queue item containing the duplicate (for queue-level duplicates)")


class SkippedDuplicates(BaseModel):
    """Model for tracking skipped duplicates by type."""
    model_config = ConfigDict(extra='forbid')
    
    hash: List[SkippedDuplicateFeature] = Field(default_factory=list, description="Hash-based duplicates (exact matches, automatically skipped)")
    coord: List[SkippedDuplicateFeature] = Field(default_factory=list, description="Coordinate-based duplicates (same location, user-controllable)")


class DuplicateInfo(BaseModel):
    """Model for duplicate information in feature data."""
    model_config = ConfigDict(extra='forbid')
    
    type: str = Field(description="Type of duplicate: 'hash' or 'coord'")
    existing_features: Optional[List[dict]] = Field(default=None, description="List of existing features that match this duplicate")

