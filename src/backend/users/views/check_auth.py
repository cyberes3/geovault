import traceback
from allauth.account.models import EmailAddress
from django.db import connection
from django.http import JsonResponse

from api.models import FeatureStore, ImportQueue
from geo_lib.logging.console import get_tagged_logger
from geo_lib.website.auth import api_or_login_required_401

_logger = get_tagged_logger(__name__)


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
            'tags': [],
            'is_superuser': request.user.is_superuser
        }
        return JsonResponse(data)

    # Unauthenticated: always return 401 (never 200)
    auth_header = request.META.get('HTTP_AUTHORIZATION', '')
    if auth_header.startswith('Bearer ') and auth_header[7:].strip():
        return JsonResponse({'error': 'Invalid or revoked token'}, status=401)
    return JsonResponse({'error': 'Authentication required'}, status=401)


@api_or_login_required_401()
def get_user_storage(request):
    """
    Calculate total storage usage for a user's FeatureStore and ImportQueue data.
    Returns storage in bytes.
    Uses PostgreSQL's octet_length() function for efficient calculation without loading data into memory.
    """
    try:
        # Use raw SQL for more reliable PostgreSQL function calls
        # Get table names from model meta to ensure correctness
        feature_store_table = FeatureStore._meta.db_table
        import_queue_table = ImportQueue._meta.db_table

        with connection.cursor() as cursor:
            # Calculate FeatureStore storage: sum of geojson field sizes
            # Cast JSONB to text and get byte length
            cursor.execute(f"""
                SELECT COALESCE(SUM(octet_length(geojson::text)), 0)
                FROM {feature_store_table}
                WHERE user_id = %s
            """, [request.user.id])
            feature_store_size = cursor.fetchone()[0] or 0

            # Calculate ImportQueue storage: sum of raw_file field sizes
            cursor.execute(f"""
                SELECT COALESCE(SUM(octet_length(raw_file)), 0)
                FROM {import_queue_table}
                WHERE user_id = %s
            """, [request.user.id])
            import_queue_size = cursor.fetchone()[0] or 0

        # Total storage in bytes
        total_storage_bytes = feature_store_size + import_queue_size

        return JsonResponse({
            'storage_bytes': total_storage_bytes
        })
    except Exception:
        _logger.error("Error calculating storage usage for user %s:\n%s", request.user.id, traceback.format_exc())
        return JsonResponse({
            'error': 'Failed to calculate storage usage'
        }, status=500)
