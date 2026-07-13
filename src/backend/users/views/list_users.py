"""List users for sharing (e.g. share track with specific people). Returns id and email."""

from django.contrib.auth import get_user_model
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from api.utils.rate_limiting import rate_limited
from geo_lib.security.rate_limit import RedisRateLimiter
from website.auth_decorators import api_or_login_required_401

User = get_user_model()

# This enumerates other users' emails; generous per-user limit deters scripted
# enumeration while staying well above any normal UI usage pattern.
_list_users_rate_limiter = RedisRateLimiter(name='list_users', limit=20, window_seconds=60.0)


@api_or_login_required_401()
@rate_limited(_list_users_rate_limiter)
@require_http_methods(["GET"])
def list_users(request):
    """
    GET /api/users/ — list users available for sharing (e.g. live track share).
    Returns users with an email set on the User model, excluding the current user.
    Matches how live_track sharing resolves emails (User.objects.filter(email__iexact=...)).
    Response: { "users": [ { "id": int, "email": str }, ... ] } ordered by email.
    """
    qs = (
        User.objects.exclude(id=request.user.id)
        .filter(email__isnull=False)
        .exclude(email="")
        .order_by("email")
        .values("id", "email")
    )
    users = [{"id": u["id"], "email": (u["email"] or "").strip()} for u in qs if (u.get("email") or "").strip()]
    return JsonResponse({"users": users})
