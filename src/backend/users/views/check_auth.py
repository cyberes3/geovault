from django.http import JsonResponse
from api.models import FeatureStore
from allauth.account.models import EmailAddress


def check_auth(request):
    if request.user.is_authenticated:
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
            'authorized': True,
            'email': primary_email,
            'id': request.user.id,
            'featureCount': feature_count,
            'tags': []
        }
    else:
        data = {
            'authorized': False,
            'email': None,
            'id': None,
            'featureCount': 0,
            'tags': []
        }
    return JsonResponse(data)
