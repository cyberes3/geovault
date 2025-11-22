from django.http import JsonResponse

from geo_lib.website.auth import login_required_401
from api.models import FeatureStore
from allauth.account.models import EmailAddress


@login_required_401
def dashboard(request):
    # Count the number of features for this user
    feature_count = FeatureStore.objects.filter(user=request.user).count()
    
    # Get primary email address
    primary_email = None
    try:
        email_address = EmailAddress.objects.filter(user=request.user, primary=True).first()
        if email_address:
            primary_email = email_address.email
        else:
            # Fallback to first email if no primary is set
            email_address = EmailAddress.objects.filter(user=request.user).first()
            if email_address:
                primary_email = email_address.email
    except Exception:
        pass
    
    data = {
        "email": primary_email,
        "id": request.user.id,
        "featureCount": feature_count
    }
    return JsonResponse(data)
