from django.conf import settings


def public_base_url() -> str:
    protocol = "https" if not settings.DEBUG else "http"
    domain = getattr(settings, "SITE_DOMAIN", "").strip() or "localhost"
    return f"{protocol}://{domain}"
