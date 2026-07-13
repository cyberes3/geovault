"""
Background warmup for social share preview PNGs.

Lives under website (not api.views.sharing.utils) so imports do not cycle:
public_share imports sharing.utils; map_share_social.views imports public_share.
"""
import threading
import traceback

from django.core.cache import cache
from django.test import RequestFactory

from geo_lib.logging.console import get_tagged_logger
from website.map_share_social.views import map_share_social_preview_image

_logger = get_tagged_logger("sharing")

# Coalescing guard: skip spawning a new warmup thread if one for this share is already running,
# rather than letting an unbounded number of raw threads pile up (e.g. a share hit repeatedly
# while its first preview render is still in flight). Same "SET NX EX" debounce idiom used by
# live_track's flush scheduling (see extensions/live_track/src/backend/helpers.py).
_WARMUP_IN_FLIGHT_KEY_PREFIX = "map_share_social:preview_warmup_in_flight:"
_WARMUP_IN_FLIGHT_LOCK_SECONDS = 60


def trigger_social_preview_warmup_async(share_id: str) -> None:
    """
    Trigger social preview image generation in a background thread, unless a warmup for this
    share_id is already running (coalesced rather than spawning a duplicate thread).

    Calls the same view as the public preview route so cache warming matches HTTP behavior.
    """
    lock_key = f"{_WARMUP_IN_FLIGHT_KEY_PREFIX}{share_id}"
    acquired = cache.add(lock_key, True, timeout=_WARMUP_IN_FLIGHT_LOCK_SECONDS)
    if not acquired:
        _logger.debug("Skipping social preview warmup for share_id=%s: already in flight", share_id)
        return

    def _warmup():
        try:
            request = RequestFactory().get(f"/share/map/{share_id}/preview.png")
            map_share_social_preview_image(request, share_id)
        except Exception:
            _logger.error(
                "Failed background social preview warmup for share_id=%s: %s",
                share_id,
                traceback.format_exc(),
            )
        finally:
            cache.delete(lock_key)

    threading.Thread(target=_warmup, daemon=True, name=f"share-preview-warmup-{share_id}").start()
