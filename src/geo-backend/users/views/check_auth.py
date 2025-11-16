from django.http import JsonResponse
from api.models import FeatureStore
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags


def check_auth(request):
    if request.user.is_authenticated:
        # Count the number of features for this user
        feature_count = FeatureStore.objects.filter(user=request.user).count()
        
        # Collect all unique tags from user's features (excluding protected tags)
        features = FeatureStore.objects.filter(user=request.user).values_list('geojson', flat=True)
        all_tags = set()
        for geojson in features:
            if isinstance(geojson, dict) and 'properties' in geojson:
                properties = geojson.get('properties', {})
                tags = properties.get('tags', [])
                if isinstance(tags, list):
                    # Filter out protected tags before adding to set
                    filtered_tags = filter_protected_tags(tags, CONST_INTERNAL_TAGS)
                    # Add all tags to the set (automatically handles uniqueness)
                    all_tags.update(tag for tag in filtered_tags if isinstance(tag, str))
        
        # Convert to sorted list for consistent ordering
        sorted_tags = sorted(list(all_tags))
        
        data = {
            'authorized': True,
            'username': request.user.username,
            'id': request.user.id,
            'featureCount': feature_count,
            'tags': sorted_tags
        }
    else:
        data = {
            'authorized': False,
            'username': None,
            'id': None,
            'featureCount': 0,
            'tags': []
        }
    return JsonResponse(data)
