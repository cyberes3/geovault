"""Paginated tag catalog, names, and per-tag feature stubs."""

from django.views.decorators.http import require_http_methods

from api.services.tag_service import TagService
from api.utils.responses import error_response, list_response, success_response
from geo_lib.contracts.pagination import PageQueryError, parse_page_query
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["GET"])
def list_tags(request):
    try:
        page_query = parse_page_query(request.GET, default_page_size=10, max_page_size=100)
    except PageQueryError as exc:
        return error_response(exc.message, code=400)
    search = request.GET.get('search', '').strip()
    page = TagService.catalog(
        request.user.id,
        search=search,
        page=page_query.page,
        page_size=page_query.page_size,
    )
    return list_response(page.items, page.page, page.page_size, page.total_items)


@api_or_login_required_401()
@require_http_methods(["GET"])
def list_tag_names(request):
    prefix = request.GET.get('prefix', '').strip()
    names = TagService.names(request.user.id, prefix=prefix)
    return success_response({'items': names})


@api_or_login_required_401()
@require_http_methods(["GET"])
def list_tag_features(request, name: str):
    try:
        page_query = parse_page_query(request.GET, default_page_size=10, max_page_size=100)
    except PageQueryError as exc:
        return error_response(exc.message, code=400)
    if not TagService.exists(request.user.id, name):
        return error_response('Tag not found', code=404)
    page = TagService.features_for_tag(
        request.user.id,
        name,
        page=page_query.page,
        page_size=page_query.page_size,
    )
    return list_response(page.items, page.page, page.page_size, page.total_items)
