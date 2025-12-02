"""
Pydantic models for duplicate tracking during import operations.

This module provides type-safe models for tracking and reporting duplicate features
during the import process.
"""

from typing import List, Optional
from enum import Enum
from pydantic import BaseModel, Field, ConfigDict


class DuplicateSource(str, Enum):
    """Enum for duplicate source location."""
    CROSS_QUEUE = "cross_queue"
    FEATURE_STORE = "feature_store"


class DuplicateMatchType(str, Enum):
    """Enum for how a duplicate was matched."""
    GEOMETRY = "geometry"
    HASH = "hash"


class SkippedDuplicateFeature(BaseModel):
    """Model for a single skipped duplicate feature."""
    model_config = ConfigDict(extra='forbid')
    
    name: str = Field(description="Name of the skipped feature")
    hash: str = Field(description="Feature hash/ID")
    queue_item_id: Optional[int] = Field(default=None, description="ID of the queue item containing the duplicate (for cross-queue duplicates)")
    queue_item_filename: Optional[str] = Field(default=None, description="Filename of the queue item containing the duplicate (for cross-queue duplicates)")


class SkippedDuplicates(BaseModel):
    """Model for tracking skipped duplicates by type."""
    model_config = ConfigDict(extra='forbid')
    
    hash: List[SkippedDuplicateFeature] = Field(default_factory=list, description="Hash-based duplicates (exact matches, automatically blocked)")
    geometry: List[SkippedDuplicateFeature] = Field(default_factory=list, description="Geometry-based duplicates (same location, user-controllable)")


class DuplicateInfo(BaseModel):
    """Model for duplicate information in feature data."""
    model_config = ConfigDict(extra='forbid')
    
    source: DuplicateSource = Field(description="Source of duplicate: cross_queue or feature_store")
    match_type: DuplicateMatchType = Field(description="How duplicate was matched: geometry or hash")
    existing_features: Optional[List[dict]] = Field(default=None, description="List of existing features that match this duplicate")

