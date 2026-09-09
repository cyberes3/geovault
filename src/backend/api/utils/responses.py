"""HTTP helpers on geo_lib.contracts envelopes."""

from functools import wraps
from typing import Any, Mapping, Sequence

from django.http import Http404, JsonResponse

from geo_lib.contracts.envelope import ListPage, error_body


def error_response(
        error_message: str,
        code: int = 400,
        details: Mapping[str, Any] | None = None
) -> JsonResponse:
    return JsonResponse(error_body(error_message, code, details), status=code)


def success_response(
        data: Mapping[str, Any] | None = None,
        status: int = 200
) -> JsonResponse:
    if data is None:
        return JsonResponse({}, status=status)
    if not isinstance(data, dict):
        raise TypeError("success_response requires a dict body")
    return JsonResponse(dict(data), status=status)


def list_response(
        items: Sequence[Any],
        page: int,
        page_size: int,
        total_items: int,
        status: int = 200,
) -> JsonResponse:
    return JsonResponse(ListPage.of(items, page, page_size, total_items).as_dict(), status=status)


def not_found_response(message: str = "Resource not found") -> JsonResponse:
    return error_response(message, code=404)


def unauthorized_response(message: str = "Unauthorized") -> JsonResponse:
    return error_response(message, code=401)


def forbidden_response(message: str = "Forbidden") -> JsonResponse:
    return error_response(message, code=403)


def server_error_response(message: str = "Internal server error") -> JsonResponse:
    return error_response(message, code=500)


def handle_404(view_func):
    @wraps(view_func)
    def wrapper(request, *args, **kwargs):
        try:
            return view_func(request, *args, **kwargs)
        except Http404:
            return not_found_response("Resource not found")

    return wrapper
