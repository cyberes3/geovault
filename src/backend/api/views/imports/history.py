"""Import history list and raw-file download."""
import math

from django.http import HttpResponse
from django.views.decorators.http import require_http_methods

from api.models import ImportQueue
from api.utils.authorization import get_object_or_404_for_user
from api.utils.responses import error_response, handle_404, list_response
from geo_lib.contracts.pagination import PageQueryError, parse_page_query
from website.auth_decorators import api_or_login_required_401


@api_or_login_required_401()
@require_http_methods(["GET"])
def list_import_history(request):
    try:
        page_query = parse_page_query(request.GET, default_page_size=10, max_page_size=100)
    except PageQueryError as exc:
        return error_response(exc.message, code=400)
    page = page_query.page
    page_size = page_query.page_size

    queryset = ImportQueue.objects.filter(
        user=request.user,
        imported=True,
        replacement__isnull=True
    ).order_by('-timestamp')

    total_items = queryset.count()
    total_pages = math.ceil(total_items / page_size) if total_items > 0 else 0

    if page > total_pages and total_pages > 0:
        return error_response(f'Page {page} does not exist. Total pages: {total_pages}', code=400)

    start = (page - 1) * page_size
    end = start + page_size
    items = queryset.values('id', 'original_filename', 'timestamp')[start:end]

    items_list = []
    for item in items:
        items_list.append({
            'id': item['id'],
            'original_filename': item['original_filename'],
            'timestamp': item['timestamp'].isoformat() if item['timestamp'] else None
        })

    return list_response(items_list, page, page_size, total_items)


@api_or_login_required_401()
@handle_404
def fetch_import_history_item(request, item_id: int):
    item = get_object_or_404_for_user(ImportQueue, request.user, id=item_id)
    response = HttpResponse(item.raw_file, content_type='application/octet-stream')
    response['Content-Disposition'] = 'attachment; filename="%s"' % item.original_filename
    return response
