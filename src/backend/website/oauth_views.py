"""
OAuth2 application views that restrict access to protected (shared) applications.
Applications whose client_id is in OAUTH2_PROVIDER['PROTECTED_CLIENT_IDS'] cannot be
viewed, edited, or deleted by users; only exclusively-owned applications can be accessed.
Protected applications are excluded from the queryset so they do not appear in the list
and direct URL access returns 404.

OAuth management (applications, authorized tokens) is session-only: API keys and
OAuth Bearer tokens cannot access these views.

When the user authorizes an app that uses a custom redirect scheme (e.g. native Android
com.geovault.uploader://), we return an HTML handoff page instead of a 302 so that PWAs
and in-app browsers can open the redirect URL (e.g. in a new tab) and hand off to the app.
"""
from django.forms.models import modelform_factory
from django.http import JsonResponse
from django.shortcuts import render
from oauth2_provider import views as dot_views
from oauth2_provider.exceptions import OAuthToolkitError
from oauth2_provider.models import get_application_model

from website.settings_utils import get_setting


class SessionOnlyMixin:
    """Reject API key and OAuth Bearer auth; only session auth is allowed."""

    def dispatch(self, request, *args, **kwargs):
        if getattr(request, "is_api_authenticated", False):
            return JsonResponse({"error": "Unauthorized"}, status=401)
        return super().dispatch(request, *args, **kwargs)


def _is_custom_scheme(url):
    """True if url uses a non-http(s) scheme (e.g. com.geovault.uploader://)."""
    if not url or not isinstance(url, str):
        return False
    u = url.strip()
    return not (u.startswith("http://") or u.startswith("https://")) and "://" in u


class AuthorizationView(dot_views.AuthorizationView):
    """
    When the redirect target is a custom scheme (native app), return an HTML handoff page
    instead of 302 so PWAs/in-app browsers can open the URL and hand off to the app.
    """

    def form_valid(self, form):
        client_id = form.cleaned_data["client_id"]
        application = get_application_model().objects.get(client_id=client_id)
        credentials = {
            "client_id": form.cleaned_data.get("client_id"),
            "redirect_uri": form.cleaned_data.get("redirect_uri"),
            "response_type": form.cleaned_data.get("response_type", None),
            "state": form.cleaned_data.get("state", None),
        }
        if form.cleaned_data.get("code_challenge", False):
            credentials["code_challenge"] = form.cleaned_data.get("code_challenge")
        if form.cleaned_data.get("code_challenge_method", False):
            credentials["code_challenge_method"] = form.cleaned_data.get("code_challenge_method")
        if form.cleaned_data.get("nonce", False):
            credentials["nonce"] = form.cleaned_data.get("nonce")
        if form.cleaned_data.get("claims", False):
            credentials["claims"] = form.cleaned_data.get("claims")

        scopes = form.cleaned_data.get("scope")
        allow = form.cleaned_data.get("allow")

        try:
            uri, headers, body, status = self.create_authorization_response(
                request=self.request, scopes=scopes, credentials=credentials, allow=allow
            )
        except OAuthToolkitError as error:
            return self.error_response(error, application)

        if allow and _is_custom_scheme(uri):
            return render(
                self.request,
                "oauth2_provider/authorize_redirect_handoff.html",
                {"redirect_url": uri},
            )
        return self.redirect(uri, application)


def _protected_client_ids():
    oauth2_provider = get_setting("OAUTH2_PROVIDER", {})
    ids = oauth2_provider.get("PROTECTED_CLIENT_IDS") or []
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
