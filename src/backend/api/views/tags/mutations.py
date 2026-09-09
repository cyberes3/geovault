"""Rename, remove, and per-feature user-tag replace."""

from django.views.decorators.http import require_http_methods

from api.services.feature_service import FeatureService
from api.services.tag_service import TagService
from api.utils.responses import error_response, handle_404, success_response
from api.validation.decorators import validate_payload
from api.validation.payloads.tags import FeatureTagsPayload, TagRenamePayload
from geo_lib.tags.protected import TagValidationError, is_protected_tag
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["PATCH", "DELETE"])
def tag_detail(request, name: str):
    if request.method == 'DELETE':
        return delete_tag(request, name)
    return rename_tag(request, name)


@api_or_login_required_401()
@require_http_methods(["PATCH"])
@validate_payload(TagRenamePayload)
def rename_tag(request, name: str, validated_data):
    if is_protected_tag(name):
        return error_response('System tags cannot be renamed', code=400)
    if not TagService.exists(request.user.id, name):
        return error_response('Tag not found', code=404)
    try:
        updated = TagService.rename_user_tag(request.user, name, validated_data['new_name'])
    except TagValidationError as exc:
        return error_response(exc.message, code=400)
    return success_response({
        'updated_count': updated,
        'old_name': name,
        'new_name': validated_data['new_name'],
    })


@api_or_login_required_401()
@require_http_methods(["DELETE"])
def delete_tag(request, name: str):
    delete_features = request.GET.get('delete_features', '').lower() in ('1', 'true', 'yes')
    if delete_features:
        deleted_count = TagService.delete_features_with_tag(request.user, name)
        return success_response({
            'deleted_count': deleted_count,
            'tag': name,
        })
    if is_protected_tag(name):
        return error_response('System tags cannot be removed from features', code=400)
    try:
        updated = TagService.remove_user_tag(request.user, name)
    except TagValidationError as exc:
        return error_response(exc.message, code=400)
    return success_response({
        'updated_count': updated,
        'tag': name,
    })


@api_or_login_required_401()
@require_http_methods(["PUT"])
@handle_404
@validate_payload(FeatureTagsPayload)
def replace_feature_tags(request, feature_id: int, validated_data):
    feature = FeatureService.get_owned_feature_or_404(request.user, feature_id)
    try:
        TagService.set_user_tags(feature, validated_data['tags'])
    except TagValidationError as exc:
        return error_response(exc.message, code=400)
    return success_response({
        'feature_id': feature.id,
        'tags': list(feature.geojson.get('properties', {}).get('tags', [])),
    })
