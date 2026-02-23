"""
Configuration for the areas server.
Load from environment; no Django or main backend dependency.
"""
import os
from typing import Optional

# PostgreSQL connection: conninfo string or database name
DATABASE_URL: Optional[str] = os.environ.get("AREAS_SERVER_DATABASE")

# Schema where admin_areas, protected_areas, and water_bodies tables live (hard-coded)
SCHEMA: str = "is_in"

# Max points in a batch request
MAX_BATCH_SIZE: int = int(os.environ.get("AREAS_SERVER_MAX_BATCH_SIZE", "100"))

# Response cache TTL for single-point GET (seconds); 0 = disabled. Default 1 day.
CACHE_TTL_SECONDS: int = int(os.environ.get("AREAS_SERVER_CACHE_TTL", "86400"))

# Coordinate rounding for cache key (decimal places); 4 ≈ 11 m
CACHE_COORD_DECIMALS: int = int(os.environ.get("AREAS_SERVER_CACHE_COORD_DECIMALS", "4"))

# Connection pool max size (3 conns per request; default 10 allows 2–3 concurrent requests)
POOL_MAX_SIZE: int = int(os.environ.get("AREAS_SERVER_POOL_MAX_SIZE", "10"))

# Redis for response cache (shared across Gunicorn workers). Use a separate DB from core server (core uses 1, 2).
REDIS_URL: str = os.environ.get("AREAS_SERVER_REDIS_URL", "redis://127.0.0.1:6379/3")

# Session work_mem for PostGIS queries (sorts, distance ops). Default 128MB; increase if queries are slow.
WORK_MEM: str = os.environ.get("AREAS_SERVER_WORK_MEM", "128MB")


def get_conninfo() -> str:
    if not DATABASE_URL or not DATABASE_URL.strip():
        raise ValueError("AREAS_SERVER_DATABASE must be set")
    return DATABASE_URL.strip()
