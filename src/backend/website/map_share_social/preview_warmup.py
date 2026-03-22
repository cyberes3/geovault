"""
Background warmup for social share preview PNGs.

Lives under website (not api.views.sharing.utils) so imports do not cycle:
public_share imports sharing.utils; map_share_social.views imports public_share.
"""
import threading
import traceback

from django.test import RequestFactory

from geo_lib.logging.console import get_tagged_logger
from website.map_share_social.views import map_share_social_preview_image

_logger = get_tagged_logger("sharing")


def trigger_social_preview_warmup_async(share_id: str) -> None:
    """
    Trigger social preview image generation in a background thread.

    Calls the same view as the public preview route so cache warming matches HTTP behavior.
    """

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

    threading.Thread(target=_warmup, daemon=True, name=f"share-preview-warmup-{share_id}").start()
