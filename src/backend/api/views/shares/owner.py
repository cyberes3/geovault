from math import ceil

from django.http import Http404
from django.views.decorators.http import require_http_methods

from api.sharing.serialize import serialize_owner_links
from api.sharing.service import ShareService
from api.utils.responses import error_response, handle_404, list_response, success_response
from api.validation.decorators import validate_payload
from geo_lib.contracts.pagination import PageQueryError, parse_page_query
from geo_lib.sharing.contracts import CreateSharePayloadModel, UpdateSharePayload
from geo_lib.sharing.errors import ShareError
from website.auth_decorators import api_or_login_required_401


def _share_error(exc: ShareError):
    return error_response(exc.message, code=exc.code)


@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
def shares_collection(request):
    if request.method == "GET":
        return list_shares(request)
    return create_share(request)


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(CreateSharePayloadModel)
def create_share(request, validated_data):
    try:
        link = ShareService.create(request.user, validated_data)
    except Http404:
        return error_response("Resource not found", code=404)
    except ShareError as exc:
        return _share_error(exc)
    return success_response(serialize_owner_links([link])[0])


@api_or_login_required_401()
@require_http_methods(["GET"])
def list_shares(request):
    try:
        page_query = parse_page_query(request.GET, default_page_size=10, max_page_size=100)
        items, total_items = ShareService.list_owned(request.user, page_query, request.GET)
    except PageQueryError as exc:
        return error_response(exc.message, code=400)
    except (ShareError, ValueError) as exc:
        message = exc.message if isinstance(exc, ShareError) else "Invalid list filter"
        code = exc.code if isinstance(exc, ShareError) else 400
        return error_response(message, code=code)
    total_pages = ceil(total_items / page_query.page_size) if page_query.page_size > 0 and total_items else 0
    if page_query.page > total_pages and total_pages > 0:
        return error_response(f"Page {page_query.page} does not exist. Total pages: {total_pages}", code=400)
    return list_response(items, page_query.page, page_query.page_size, total_items)


@api_or_login_required_401()
@require_http_methods(["GET", "PATCH", "DELETE"])
@handle_404
def share_detail(request, share_id):
    if request.method == "GET":
        link = ShareService.get_owned(request.user, share_id)
        return success_response(serialize_owner_links([link])[0])
    if request.method == "DELETE":
        ShareService.delete(request.user, share_id)
        return success_response({"msg": "Share deleted successfully"})
    return patch_share(request, share_id)


@api_or_login_required_401()
@require_http_methods(["PATCH"])
@handle_404
@validate_payload(UpdateSharePayload)
def patch_share(request, share_id, validated_data):
    try:
        link = ShareService.update(request.user, share_id, validated_data)
    except ShareError as exc:
        return _share_error(exc)
    return success_response(serialize_owner_links([link])[0])
