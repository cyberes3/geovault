"""
Single source of truth for the site's public protocol/domain/origin, used anywhere that needs
to build an absolute URL back to GeoVault itself (email links, CORS/CSP "self" origin, WhiteNoise
CORS headers) instead of each call site re-deriving 'https unless DEBUG' + SITE_DOMAIN by hand.
"""
from django.conf import settings


def site_protocol() -> str:
    """'http' in DEBUG (local dev), 'https' otherwise."""
    return "http" if settings.DEBUG else "https"


def site_domain() -> str:
    """Configured public domain (site.domain in config.yaml), falling back to 'localhost'."""
    return getattr(settings, "SITE_DOMAIN", "").strip() or "localhost"


def public_base_url() -> str:
    """The site's own origin, e.g. 'https://geovault.example.com'."""
    return f"{site_protocol()}://{site_domain()}"


def build_public_url(path: str) -> str:
    if not path.startswith("/"):
        path = f"/{path}"
    return f"{public_base_url()}{path}"
