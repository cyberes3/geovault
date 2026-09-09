"""Redis-backed Django cache alias for shared mutable entries (not LocMem)."""
from django.core.cache import caches

SHARED_CACHE_ALIAS = "shared"


def get_shared_cache():
    return caches[SHARED_CACHE_ALIAS]
