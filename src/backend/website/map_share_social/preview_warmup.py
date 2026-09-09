"""
Background warmup for social share preview PNGs.
"""
import threading
import traceback

from geo_lib.logging.console import get_tagged_logger
from geo_lib.utils.db_connection import ensure_db_connection_cleanup
from website.map_share_social.preview_cache import acquire_preview_lock, release_preview_lock
from website.map_share_social.preview_service import warmup_preview_png

_logger = get_tagged_logger("sharing")


def trigger_social_preview_warmup_async(share_id: str) -> None:
    """
    Trigger social preview image generation in a background thread, unless a render
    for this share_id is already running (same lock as the HTTP preview path).
    """
    if not acquire_preview_lock(share_id):
        _logger.debug("Skipping social preview warmup for share_id=%s: already in flight", share_id)
        return
    release_preview_lock(share_id)

    @ensure_db_connection_cleanup
    def _warmup():
        try:
            warmup_preview_png(share_id)
        except Exception:
            _logger.error(
                "Failed background social preview warmup for share_id=%s:\n%s",
                share_id,
                traceback.format_exc(),
            )

    threading.Thread(target=_warmup, daemon=True, name=f"share-preview-warmup-{share_id}").start()
