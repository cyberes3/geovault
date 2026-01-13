import json
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods
from django.views.decorators.csrf import csrf_exempt
from .models import ExampleItem

# ==============================================================================
# Extension Views (API Endpoints)
# ==============================================================================
# Views handle the business logic for extension-specific functionality.
# These will be automatically scoped under /api/extensions/<name>/

@require_http_methods(["GET", "POST"])
@csrf_exempt # For simplicity in this demo. For production, the platform provides CSRF utilities.
def item_list_create(request):
    """
    Handle fetching all items or creating a new one.
    """
    if request.method == "GET":
        # Standard Django QuerySet logic
        items = list(ExampleItem.objects.all().values('id', 'name', 'description'))
        return JsonResponse(items, safe=False)
    
    elif request.method == "POST":
        try:
            # Typical JSON request parsing
            data = json.loads(request.body)
            item = ExampleItem.objects.create(
                name=data.get('name', 'Unnamed Item'),
                description=data.get('description', '')
            )
            return JsonResponse({
                'id': item.id,
                'name': item.name,
                'description': item.description
            }, status=201)
        except Exception as e:
            return JsonResponse({'error': str(e)}, status=400)

@require_http_methods(["DELETE"])
@csrf_exempt
def item_delete(request, item_id):
    """
    Delete a specific item by ID.
    """
    try:
        item = ExampleItem.objects.get(id=item_id)
        item.delete()
        # 204 No Content is the standard response for successful deletion
        return JsonResponse({'message': 'Deleted'}, status=204)
    except ExampleItem.DoesNotExist:
        return JsonResponse({'error': 'Not found'}, status=404)
