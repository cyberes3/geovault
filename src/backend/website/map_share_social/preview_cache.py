import time
import traceback

from geo_lib.logging.console import get_tagged_logger
from geo_lib.perf.shared_cache import get_shared_cache
from geo_lib.sharing.social_preview import (
    PREVIEW_LOCK_SECONDS,
    SOCIAL_PREVIEW_CACHE_SECONDS,
    PreviewKey,
)

_logger = get_tagged_logger("social_preview")


def preview_cache():
    return get_shared_cache()


def get_cached_png(cache_key: str):
    try:
        return preview_cache().get(cache_key)
    except Exception:
        _logger.error("Social preview cache get failed:\n%s", traceback.format_exc())
        return None


def store_cached_png(token: str, cache_key: str, image_bytes: bytes) -> None:
    cache = preview_cache()
    try:
        cache.set(cache_key, image_bytes, timeout=SOCIAL_PREVIEW_CACHE_SECONDS)
        index_key = PreviewKey.index_key(token)
        keys = cache.get(index_key) or []
        if cache_key not in keys:
            keys = [*keys, cache_key]
            cache.set(index_key, keys, timeout=SOCIAL_PREVIEW_CACHE_SECONDS)
    except Exception:
        _logger.error("Social preview cache set failed:\n%s", traceback.format_exc())


def invalidate_token(token: str) -> None:
    cache = preview_cache()
    try:
        index_key = PreviewKey.index_key(token)
        keys = cache.get(index_key) or []
        for key in keys:
            cache.delete(key)
        cache.delete(index_key)
        cache.delete(PreviewKey.lock_key(token))
    except Exception:
        _logger.error("Social preview cache invalidate failed:\n%s", traceback.format_exc())


def acquire_preview_lock(token: str) -> bool:
    try:
        return bool(preview_cache().add(PreviewKey.lock_key(token), True, timeout=PREVIEW_LOCK_SECONDS))
    except Exception:
        _logger.error("Social preview lock acquire failed:\n%s", traceback.format_exc())
        return True


def release_preview_lock(token: str) -> None:
    try:
        preview_cache().delete(PreviewKey.lock_key(token))
    except Exception:
        _logger.error("Social preview lock release failed:\n%s", traceback.format_exc())


def wait_for_cached_png(cache_key: str, *, attempts: int = 30, interval_seconds: float = 0.2):
    for _ in range(attempts):
        cached = get_cached_png(cache_key)
        if cached:
            return cached
        time.sleep(interval_seconds)
    return None
