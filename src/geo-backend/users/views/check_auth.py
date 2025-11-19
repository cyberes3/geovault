from django.http import JsonResponse
from api.models import FeatureStore
from geo_lib.const_strings import CONST_INTERNAL_TAGS, filter_protected_tags


def check_auth(request):
    if request.user.is_authenticated:
        # Count the number of features for this user
        feature_count = FeatureStore.objects.filter(user=request.user).count()
        
        # Count features per tag (excluding protected tags)
        features = FeatureStore.objects.filter(user=request.user).values_list('geojson', flat=True)
        tag_counts = {}
        for geojson in features:
            if isinstance(geojson, dict) and 'properties' in geojson:
                properties = geojson.get('properties', {})
                tags = properties.get('tags', [])
                if isinstance(tags, list):
                    # Filter out protected tags before counting
                    filtered_tags = filter_protected_tags(tags, CONST_INTERNAL_TAGS)
                    # Count occurrences of each tag
                    for tag in filtered_tags:
                        if isinstance(tag, str):
                            tag_counts[tag] = tag_counts.get(tag, 0) + 1
        
        # Sort tags by count (descending) and take top 5
        top_tags = sorted(tag_counts.items(), key=lambda x: x[1], reverse=True)[:5]
        
        # Convert to list of objects with tag and count
        top_tags_list = [{'tag': tag, 'count': count} for tag, count in top_tags]
        
        data = {
            'authorized': True,
            'username': request.user.username,
            'id': request.user.id,
            'featureCount': feature_count,
            'tags': top_tags_list
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
