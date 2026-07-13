import traceback
from allauth.account.models import EmailAddress
from django.core.cache import cache
from django.db import connection
from django.http import JsonResponse
from pydantic import BaseModel, Field

from api.models import FeatureStore, ImportQueue
from geo_lib.logging.console import get_tagged_logger
from website.auth_decorators import api_or_login_required_401

_logger = get_tagged_logger(__name__)

STORAGE_USAGE_CACHE_TIMEOUT_SECONDS = 15
STORAGE_USAGE_CACHE_KEY_PREFIX = "storage_usage"
SUPPORTED_STORAGE_TYPES = ("feature",)


class StorageUsageResponse(BaseModel):
    """Response shape for GET /api/user/storage/usage/. Same structure with or without type query."""

    by_type: dict[str, int] = Field(description="Storage bytes per type (e.g. feature)")
    total_storage_bytes: int = Field(description="Total storage in bytes")


@api_or_login_required_401()
def check_auth(request):
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


def _compute_feature_storage_bytes(user_id: int) -> int:
    """Sum FeatureStore + ImportQueue storage for the user. Uses PostgreSQL octet_length()."""
    feature_store_table = FeatureStore._meta.db_table
    import_queue_table = ImportQueue._meta.db_table
    with connection.cursor() as cursor:
        cursor.execute(f"""
            SELECT COALESCE(SUM(octet_length(geojson::text)), 0)
            FROM {feature_store_table}
            WHERE user_id = %s
        """, [user_id])
        feature_store_size = cursor.fetchone()[0] or 0
        cursor.execute(f"""
            SELECT COALESCE(SUM(octet_length(raw_file)), 0)
            FROM {import_queue_table}
            WHERE user_id = %s
        """, [user_id])
        import_queue_size = cursor.fetchone()[0] or 0
    return feature_store_size + import_queue_size


@api_or_login_required_401()
def get_user_storage(request):
    """
    Storage usage for the current user. GET /api/user/storage/usage/.

    Optional query: type=feature to compute only feature storage (performance).
    Response is always { "by_type": { ... }, "total_storage_bytes": N }. Cached 15s by user and type.
    """
    type_param = (request.GET.get("type") or "").strip().lower()
    if type_param and type_param not in SUPPORTED_STORAGE_TYPES:
        return JsonResponse(
            {"error": "Unsupported type", "supported": list(SUPPORTED_STORAGE_TYPES)},
            status=400,
        )

    type_value = type_param or "all"
    cache_key = f"{STORAGE_USAGE_CACHE_KEY_PREFIX}:{request.user.id}:{type_value}"
    cached = cache.get(cache_key)
    if cached is not None:
        return JsonResponse(cached)

    try:
        feature_bytes = _compute_feature_storage_bytes(request.user.id)
        by_type = {"feature": feature_bytes}
        total = feature_bytes
        response_data = StorageUsageResponse(by_type=by_type, total_storage_bytes=total).model_dump()
        cache.set(cache_key, response_data, timeout=STORAGE_USAGE_CACHE_TIMEOUT_SECONDS)
        return JsonResponse(response_data)
    except Exception:
        _logger.error("Error calculating storage usage for user %s:\n%s", request.user.id, traceback.format_exc())
        return JsonResponse({"error": "Failed to calculate storage usage"}, status=500)
