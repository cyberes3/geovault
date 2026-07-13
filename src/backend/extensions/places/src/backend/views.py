from django.http import HttpResponse
from django.views.decorators.http import require_http_methods

from api.utils.responses import handle_404, success_response
from api.validation.decorators import validate_payload
from extensions.places.src.backend.constants import DEFAULT_SORT, VALID_SORT
from extensions.places.src.backend.services.place_service import (
    PlaceServiceError,
    place_service,
    place_service_error_response,
)
from extensions.places.src.backend.validation import PlaceFeaturePayload
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
def places_list(request):
    if request.method == "GET":
        sort = (request.GET.get('sort') or DEFAULT_SORT).strip().lower()
        if sort not in VALID_SORT:
            sort = DEFAULT_SORT
        features = place_service.list_places(request.user, sort=sort)
        response = success_response({
            'type': 'FeatureCollection',
            'features': features,
        })
        response['Cache-Control'] = 'no-store, no-cache, must-revalidate'
        return response

    return _handle_create_place(request)


@validate_payload(PlaceFeaturePayload)
def _handle_create_place(request, validated_data):
    try:
        feature = place_service.create_place(request.user, validated_data)
    except PlaceServiceError as exc:
        return place_service_error_response(exc)
    return success_response(feature, status=201)


@api_or_login_required_401()
@handle_404
@require_http_methods(["GET", "PUT", "DELETE"])
def place_detail(request, feature_id):
    if request.method == "GET":
        try:
            feature = place_service.get_place(request.user, feature_id)
        except PlaceServiceError as exc:
            return place_service_error_response(exc)
        return success_response(feature)

    if request.method == "PUT":
        return _handle_update_place(request, feature_id)

    try:
        place_service.delete_place(request.user, feature_id)
    except PlaceServiceError as exc:
        return place_service_error_response(exc)
    return success_response({'deleted': True})


@validate_payload(PlaceFeaturePayload)
def _handle_update_place(request, feature_id, validated_data):
    try:
        feature = place_service.update_place(request.user, feature_id, validated_data)
    except PlaceServiceError as exc:
        return place_service_error_response(exc)
    return success_response(feature)


@api_or_login_required_401()
@handle_404
@require_http_methods(["POST"])
def place_navigate(request, feature_id):
    try:
        place_service.record_navigation(request.user, feature_id)
    except PlaceServiceError as exc:
        return place_service_error_response(exc)
    return HttpResponse(status=204)
