"""Import-queue feature read and search."""

from django.db.models import Q
from django.views.decorators.http import require_http_methods

from api.models import ImportDraftFeature, ImportQueue
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, handle_404, success_response
from geo_lib.importing.draft_store import draft_to_wire_feature
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
@handle_404
def get_import_queue_item_features(request, item_id: int):
    item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    drafts = ImportDraftFeature.objects.filter(queue=item).order_by('spatial_index', 'id')
    return success_response({
        'items': [draft_to_wire_feature(draft) for draft in drafts],
        'original_filename': item.original_filename,
        'imported': item.imported,
        'replacement': item.replacement
    })


@api_or_login_required_401()
@require_http_methods(["GET"])
@handle_404
def search_import_item_features(request, item_id: int):
    query = request.GET.get('query', '').strip()
    if not query:
        return error_response('query parameter is required', code=400)

    item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)

    page_size = 50
    max_results = 150
    drafts = ImportDraftFeature.objects.filter(queue=item).filter(
        Q(name__icontains=query) | Q(geojson__properties__description__icontains=query)
    ).order_by('spatial_index', 'id')
    total_matches = drafts.count()
    matches = []
    for draft in drafts[:max_results]:
        matches.append({
            'feature_index': draft.spatial_index,
            'page': (draft.spatial_index // page_size) + 1,
            'feature': draft_to_wire_feature(draft),
        })
    return success_response({
        'matches': matches,
        'total_matches': total_matches
    })
