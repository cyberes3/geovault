from django.http import JsonResponse

from geo_lib.website.auth import login_required_401
from api.models import FeatureStore


@login_required_401
def dashboard(request):
    # Count the number of features for this user
    feature_count = FeatureStore.objects.filter(user=request.user).count()
    
    data = {
        "username": request.user.username,
        "id": request.user.id,
        "featureCount": feature_count
    }
    return JsonResponse(data)
