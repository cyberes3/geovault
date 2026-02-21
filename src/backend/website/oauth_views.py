"""
OAuth2 application views that restrict access to protected (shared) applications.
Applications whose client_id is in OAUTH2_PROVIDER['PROTECTED_CLIENT_IDS'] cannot be
viewed, edited, or deleted by users; only exclusively-owned applications can be accessed.
Protected applications are excluded from the queryset so they do not appear in the list
and direct URL access returns 404.

OAuth management (applications, authorized tokens) is session-only: API keys and
OAuth Bearer tokens cannot access these views.
"""
from django.conf import settings
from django.forms.models import modelform_factory
from django.http import JsonResponse
from oauth2_provider import views as dot_views
from oauth2_provider.models import get_application_model


class SessionOnlyMixin:
    """Reject API key and OAuth Bearer auth; only session auth is allowed."""

    def dispatch(self, request, *args, **kwargs):
        if getattr(request, "is_api_authenticated", False):
            return JsonResponse({"error": "Unauthorized"}, status=401)
        return super().dispatch(request, *args, **kwargs)


def _protected_client_ids():
    ids = settings.OAUTH2_PROVIDER.get("PROTECTED_CLIENT_IDS") or []
    return [c.strip() for c in ids if c]


def _exclude_protected(queryset):
    """Exclude applications whose client_id is in PROTECTED_CLIENT_IDS."""
    protected = _protected_client_ids()
    if not protected:
        return queryset
    return queryset.exclude(client_id__in=protected)


APPLICATION_FORM_FIELDS = (
    "name",
    "client_id",
    "client_secret",
    "hash_client_secret",
    "client_type",
    "authorization_grant_type",
    "redirect_uris",
    "post_logout_redirect_uris",
    "allowed_origins",
    "algorithm",
)


def _get_application_form_class():
    """Return the application model form with name required."""
    form_class = modelform_factory(get_application_model(), fields=APPLICATION_FORM_FIELDS)
    form_class.base_fields["name"].required = True
    return form_class


class ApplicationList(SessionOnlyMixin, dot_views.ApplicationList):
    """List view that excludes protected (shared) applications. Session-only."""

    def get_queryset(self):
        return _exclude_protected(super().get_queryset())


class ApplicationRegistration(SessionOnlyMixin, dot_views.ApplicationRegistration):
    """Registration view that requires application name. Session-only."""

    def get_form_class(self):
        return _get_application_form_class()


class ApplicationDetail(SessionOnlyMixin, dot_views.ApplicationDetail):
    """Detail view that excludes protected applications (404 if requested by URL). Session-only."""

    def get_queryset(self):
        return _exclude_protected(super().get_queryset())


class ApplicationUpdate(SessionOnlyMixin, dot_views.ApplicationUpdate):
    """Update view that excludes protected applications and requires application name. Session-only."""

    def get_queryset(self):
        return _exclude_protected(super().get_queryset())

    def get_form_class(self):
        return _get_application_form_class()


class ApplicationDelete(SessionOnlyMixin, dot_views.ApplicationDelete):
    """Delete view that excludes protected applications. Session-only."""

    def get_queryset(self):
        return _exclude_protected(super().get_queryset())


class AuthorizedTokensListView(SessionOnlyMixin, dot_views.AuthorizedTokensListView):
    """List authorized OAuth tokens. Session-only (API keys and OAuth tokens cannot access)."""


class AuthorizedTokenDeleteView(SessionOnlyMixin, dot_views.AuthorizedTokenDeleteView):
    """Revoke an authorized OAuth token. Session-only (API keys and OAuth tokens cannot access)."""
