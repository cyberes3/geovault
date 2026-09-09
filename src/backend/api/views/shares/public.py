from django.views.decorators.http import require_http_methods

from api.sharing.public_resolver import PublicShareResolver
from api.utils.responses import error_response, success_response
from geo_lib.sharing.constants import INVALID_SHARE_LINK
from geo_lib.sharing.errors import InvalidShareLink, ShareError, ShareForbidden, ShareUnauthorized


def _public_error(exc) -> object:
    if isinstance(exc, InvalidShareLink):
        return error_response(INVALID_SHARE_LINK, code=404)
    if isinstance(exc, (ShareUnauthorized, ShareForbidden, ShareError)):
        return error_response(exc.message, code=exc.code)
    raise exc


@require_http_methods(["GET"])
def share_info(request, share_id):
    try:
        return success_response(PublicShareResolver.info(share_id, request.user))
    except (InvalidShareLink, ShareUnauthorized, ShareForbidden, ShareError) as exc:
        return _public_error(exc)


@require_http_methods(["GET"])
def share_features(request, share_id):
    try:
        return PublicShareResolver.features(request, share_id)
    except (InvalidShareLink, ShareUnauthorized, ShareForbidden, ShareError) as exc:
        return _public_error(exc)


@require_http_methods(["GET"])
def share_elevations(request, share_id):
    try:
        return success_response(
            PublicShareResolver.elevations(share_id, request.GET.get("feature_ref"), request.user)
        )
    except (InvalidShareLink, ShareUnauthorized, ShareForbidden, ShareError) as exc:
        return _public_error(exc)


@require_http_methods(["GET"])
def share_track(request, share_id):
    try:
        return success_response(PublicShareResolver.track(share_id, request.user))
    except (InvalidShareLink, ShareUnauthorized, ShareForbidden, ShareError) as exc:
        return _public_error(exc)
