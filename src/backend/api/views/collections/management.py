"""Collection CRUD. Membership is resolved live; views stay thin."""

from django.views.decorators.http import require_http_methods

from api.services.collection_service import CollectionService
from api.utils.responses import error_response, handle_404, list_response, success_response
from api.validation.decorators import validate_payload
from api.validation.payloads.collections import CollectionCreatePayload, CollectionUpdatePayload
from geo_lib.collections.definition import CollectionEditorInput
from geo_lib.collections.editor import CollectionEditorError
from geo_lib.contracts.pagination import PageQueryError, parse_page_query
from website.auth_decorators import api_or_login_required_401


def _editor_from_payload(validated_data: dict, *, name_required: bool) -> CollectionEditorInput:
    name = validated_data.get('name')
    if name_required and (not name or not str(name).strip()):
        raise CollectionEditorError('name cannot be empty')
    return CollectionEditorInput(
        name=name,
        description=validated_data.get('description'),
        description_provided='description' in validated_data,
        tags=validated_data.get('tags'),
        feature_ids=validated_data.get('feature_ids'),
    )


@api_or_login_required_401()
@require_http_methods(["GET", "POST"])
def collections_collection(request):
    if request.method == 'GET':
        return list_collections(request)
    return create_collection(request)


@api_or_login_required_401()
@require_http_methods(["GET"])
def list_collections(request):
    try:
        page_query = parse_page_query(request.GET, default_page_size=50, max_page_size=100)
    except PageQueryError as exc:
        return error_response(exc.message, code=400)
    page = CollectionService.list_owned(request.user, page_query)
    return list_response(page.items, page.page, page.page_size, page.total_items)


@api_or_login_required_401()
@require_http_methods(["POST"])
@validate_payload(CollectionCreatePayload)
def create_collection(request, validated_data):
    try:
        editor = _editor_from_payload(validated_data, name_required=True)
        collection = CollectionService.create(request.user, editor)
    except CollectionEditorError as exc:
        return error_response(exc.message, code=400)
    return success_response({
        'collection': CollectionService.serialize(collection)
    }, status=201)


@api_or_login_required_401()
@require_http_methods(["GET", "PATCH", "DELETE"])
@handle_404
def collection_detail(request, collection_id):
    if request.method == 'GET':
        return get_collection(request, collection_id)
    if request.method == 'PATCH':
        return update_collection(request, collection_id)
    return delete_collection(request, collection_id)


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
def get_collection(request, collection_id):
    collection = CollectionService.get_owned_or_404(request.user, collection_id)
    return success_response({
        'collection': CollectionService.serialize(collection)
    })


@api_or_login_required_401()
@require_http_methods(["PATCH"])
@validate_payload(CollectionUpdatePayload, exclude_unset=True)
@handle_404
def update_collection(request, collection_id, validated_data):
    collection = CollectionService.get_owned_or_404(request.user, collection_id)
    try:
        editor = _editor_from_payload(validated_data, name_required=False)
        collection = CollectionService.update(collection, editor)
    except CollectionEditorError as exc:
        return error_response(exc.message, code=400)
    return success_response({
        'collection': CollectionService.serialize(collection)
    })


@api_or_login_required_401()
@require_http_methods(["DELETE"])
@handle_404
def delete_collection(request, collection_id):
    collection = CollectionService.get_owned_or_404(request.user, collection_id)
    CollectionService.delete(collection)
    return success_response({'msg': 'Collection deleted successfully'})
