from typing import Optional, Tuple

from django.http import JsonResponse

from api.sharing.models import ShareLink
from api.sharing.public_resolver import PublicShareResolver
from api.utils.responses import error_response
from geo_lib.sharing.errors import InvalidShareLink, ShareForbidden, ShareUnauthorized


def lookup_and_validate_share(share_id: str) -> Tuple[Optional[ShareLink], Optional[JsonResponse]]:
    """
    Resolve a map share and require KMZ download capability.

    Returns (link, error). error is a JsonResponse the caller should return as-is.
    """
    try:
        return PublicShareResolver.assert_download_allowed(share_id), None
    except InvalidShareLink:
        return None, error_response("Invalid share link", code=404)
    except ShareUnauthorized as exc:
        return None, error_response(exc.message, code=exc.code)
    except ShareForbidden as exc:
        return None, error_response(exc.message, code=exc.code)
