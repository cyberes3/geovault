"""
Monkeypatch django-oauth-toolkit so that "custom" in ALLOWED_REDIRECT_URI_SCHEMES
allows any reverse-DNS-style scheme (e.g. com.thirdparty.app) for third-party Android apps.

Import this module before any oauth2_provider views are used (e.g. at top of oauth/urls.py).
"""
from urllib.parse import urlparse, urlsplit

from django.core.exceptions import DisallowedRedirect
from django.utils.encoding import force_str
from oauth2_provider import http as dot_http
from oauth2_provider import validators as dot_validators
from oauth2_provider.settings import oauth2_settings
from oauth2_provider.views import oidc as oidc_module

CUSTOM_APP_SCHEME = "custom"


def _is_custom_app_scheme(scheme):
    """True if scheme looks like a reverse-DNS app scheme (e.g. com.thirdparty.app)."""
    if not scheme or "." not in scheme:
        return False
    s = scheme.lower()
    return len(s) > 1 and s[0].isalpha() and all(c.isalnum() or c in ".-" for c in s)


def _scheme_allowed(scheme, allowed_sequence):
    """True if scheme is allowed: exact match or 'custom' in list and scheme is reverse-DNS."""
    schemes_lower = [s.lower() for s in allowed_sequence]
    return scheme.lower() in schemes_lower or (
        CUSTOM_APP_SCHEME in schemes_lower and _is_custom_app_scheme(scheme)
    )


def _patch_validators():
    _original_call = dot_validators.AllowedURIValidator.__call__

    def __call__(self, value):
        value = force_str(value)
        try:
            scheme, netloc, path, query, fragment = urlsplit(value)
        except ValueError:
            return _original_call(self, value)
        if _scheme_allowed(scheme, self.schemes) and scheme.lower() not in (s.lower() for s in self.schemes):
            # Temporarily allow this scheme so the original __call__ passes the scheme check
            old = self.schemes
            self.schemes = set(old) | {scheme.lower()} if isinstance(old, set) else list(old) + [scheme.lower()]
            try:
                return _original_call(self, value)
            finally:
                self.schemes = old
        return _original_call(self, value)

    dot_validators.AllowedURIValidator.__call__ = __call__


def _patch_http():
    def validate_redirect(self, redirect_to):
        parsed = urlparse(str(redirect_to))
        if not parsed.scheme:
            raise DisallowedRedirect("OAuth2 redirects require a URI scheme.")
        if not _scheme_allowed(parsed.scheme, self.allowed_schemes):
            raise DisallowedRedirect("Redirect to scheme {!r} is not permitted".format(parsed.scheme))

    dot_http.OAuth2ResponseRedirect.validate_redirect = validate_redirect


def _patch_oidc():
    def validate_post_logout_redirect_uri(self, application, post_logout_redirect_uri):
        if not post_logout_redirect_uri:
            return
        if not application:
            raise oidc_module.InvalidOIDCClientError()
        scheme = urlparse(post_logout_redirect_uri)[0]
        if not scheme:
            raise oidc_module.InvalidOIDCRedirectURIError("A Scheme is required for the redirect URI.")
        if oauth2_settings.OIDC_RP_INITIATED_LOGOUT_STRICT_REDIRECT_URIS and (
            scheme == "http" and application.client_type != "confidential"
        ):
            raise oidc_module.InvalidOIDCRedirectURIError("http is only allowed with confidential clients.")
        if not _scheme_allowed(scheme, application.get_allowed_schemes()):
            raise oidc_module.InvalidOIDCRedirectURIError(
                'Redirect to scheme "{}" is not permitted.'.format(scheme)
            )
        if not application.post_logout_redirect_uri_allowed(post_logout_redirect_uri):
            raise oidc_module.InvalidOIDCRedirectURIError(
                "This client does not have this redirect uri registered."
            )

    oidc_module.RPInitiatedLogoutView.validate_post_logout_redirect_uri = validate_post_logout_redirect_uri


def apply_patches():
    _patch_validators()
    _patch_http()
    _patch_oidc()


apply_patches()
