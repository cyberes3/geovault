from django.http import JsonResponse
from api.models import FeatureStore


def check_auth(request):
    if request.user.is_authenticated:
        # Count the number of features for this user
        feature_count = FeatureStore.objects.filter(user=request.user).count()
        
        data = {
            'authorized': True,
            'username': request.user.username,
            'id': request.user.id,
            'featureCount': feature_count,
            'tags': []
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
