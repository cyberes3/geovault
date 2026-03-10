"""List users for sharing (e.g. share track with specific people). Returns id and email."""

from django.contrib.auth import get_user_model
from django.http import JsonResponse
from django.views.decorators.http import require_http_methods

from geo_lib.website.auth import api_or_login_required_401

User = get_user_model()


@api_or_login_required_401()
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
