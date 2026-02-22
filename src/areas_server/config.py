"""
Configuration for the is_in area server.
Load from environment; no Django or main backend dependency.
"""
import os
from typing import Optional

# PostgreSQL connection: conninfo string or database name
DATABASE_URL: Optional[str] = os.environ.get("IS_IN_DATABASE") or os.environ.get("DATABASE_URL")

# Schema where admin_areas and protected_areas tables live
SCHEMA: str = os.environ.get("IS_IN_SCHEMA", "is_in")

# Max points in a batch request
MAX_BATCH_SIZE: int = int(os.environ.get("IS_IN_MAX_BATCH_SIZE", "500"))

# Optional response cache TTL (seconds); 0 = disabled
CACHE_TTL_SECONDS: int = int(os.environ.get("IS_IN_CACHE_TTL", "0"))

# Coordinate rounding for cache key (decimal places); 4 ≈ 11 m
CACHE_COORD_DECIMALS: int = int(os.environ.get("IS_IN_CACHE_COORD_DECIMALS", "4"))


def get_conninfo() -> str:
    if not DATABASE_URL or not DATABASE_URL.strip():
        raise ValueError("IS_IN_DATABASE or DATABASE_URL must be set")
    return DATABASE_URL.strip()
