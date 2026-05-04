"""
Configuration for the areas server.
Load from environment; no Django or main backend dependency.
"""
import os
import re
from typing import Optional


def _database_url_from_env() -> Optional[str]:
    """Current AREAS_SERVER_DATABASE value (read each time so env changes are visible)."""
    raw = os.environ.get("AREAS_SERVER_DATABASE")
    if raw is None:
        return None
    s = raw.strip()
    return s or None


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

# work_mem must be a literal in SET (PostgreSQL does not accept bound params for SET); only allow safe tokens.
WORK_MEM_SAFE_RE = re.compile(r"^\d+(MB|GB|kB)?$", re.IGNORECASE)


def get_conninfo() -> str:
    url = _database_url_from_env()
    if not url:
        raise ValueError(
            "AREAS_SERVER_DATABASE must be set to a non-empty PostgreSQL URI or libpq conninfo string "
            "(export it or set it in the service EnvironmentFile before starting the areas server)."
        )
    return url


def validate_required_environment() -> None:
    """
    Fail fast at process startup if required settings are missing or invalid.
    Call once when loading the Flask app (e.g. gunicorn worker import).
    """
    get_conninfo()
    if not WORK_MEM_SAFE_RE.match(WORK_MEM):
        raise ValueError(
            f"Invalid AREAS_SERVER_WORK_MEM: {WORK_MEM!r}; use a size token such as 128MB, 256MB, or 1GB."
        )
