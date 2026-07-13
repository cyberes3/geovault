"""Django security settings: secret key, hosts, CORS/CSRF, cookies, HSTS."""
from website.config.loader import get_config
from website.settings.app_config import EXTENSIONS_CONFIG
from website.secret_key_validation import require_secret_key

_config = get_config()

# SECURITY WARNING: keep the secret key used in production secret!
# No default value: require_secret_key() raises ImproperlyConfigured (aborting startup) if
# this is missing or a known placeholder, rather than silently falling back to an insecure,
# publicly-known key.
SECRET_KEY = require_secret_key(_config.security.secret_key)

# SECURITY WARNING: don't run with debug turned on in production!
DEBUG = _config.security.debug

# Site Framework Configuration (also used in email templates)
SITE_DOMAIN = _config.site.domain.strip()
SITE_NAME = _config.site.name

# Allowed hosts (required when DEBUG is False). The primary host is always SITE_DOMAIN; extra
# domains/proxy IPs come from security.additional_allowed_hosts. The Hauk-compatible API host
# (extensions.live_track.hauk_domain), if set, is automatically added too.
_additional_allowed_hosts = [h.strip() for h in _config.security.additional_allowed_hosts if h.strip()]
ALLOWED_HOSTS = [SITE_DOMAIN] + _additional_allowed_hosts
_hauk_domain = (EXTENSIONS_CONFIG.get('live_track', {}).get('hauk_domain') or '').strip()
if _hauk_domain and _hauk_domain not in ALLOWED_HOSTS:
    ALLOWED_HOSTS.append(_hauk_domain)

# Additional CORS origins from config (user-specified). The system automatically allows the
# site's own domain and external tile source origins on top of this.
ADDITIONAL_CORS_ORIGINS = _config.security.additional_cors_origins

# django.middleware.security.SecurityMiddleware sends the Strict-Transport-Security header
# itself based on these settings, whenever request.is_secure() is True -- which requires nginx
# to forward X-Forwarded-Proto correctly (see SECURE_PROXY_SSL_HEADER below).
SECURE_BROWSER_XSS_FILTER = True
SECURE_CONTENT_TYPE_NOSNIFF = True
X_FRAME_OPTIONS = 'DENY'
SECURE_HSTS_SECONDS = 31536000  # 1 year
SECURE_HSTS_INCLUDE_SUBDOMAINS = True
SECURE_HSTS_PRELOAD = True

# When behind a reverse proxy, Django should use the X-Forwarded-Host header to determine the
# correct host for building absolute URLs (e.g., in emails)
USE_X_FORWARDED_HOST = True
# When true, request.is_secure() respects X-Forwarded-Proto (set nginx:
# proxy_set_header X-Forwarded-Proto $scheme;). Only use when clients cannot reach Django
# directly with a spoofed header. Default: on when not DEBUG (typical production behind nginx).
_trust_x_forwarded_proto = _config.security.trust_x_forwarded_proto
if _trust_x_forwarded_proto is None:
    _trust_x_forwarded_proto = not DEBUG
# None matches Django's own default (no proxy header trusted) when this is disabled.
SECURE_PROXY_SSL_HEADER = ('HTTP_X_FORWARDED_PROTO', 'https') if _trust_x_forwarded_proto else None

# CSRF_TRUSTED_ORIGINS must include the scheme+host (+ non-default port) browsers use
# (Django 4.0+). Derive from ALLOWED_HOSTS using http vs https from security.secure_cookies
# (defaults to HTTPS-only cookies when not DEBUG — fine behind TLS; use secure_cookies: false
# for plain-HTTP bootstrap, e.g. http://server:8000).
_secure_cookies = _config.security.secure_cookies
if _secure_cookies is None:
    _secure_cookies = not DEBUG
CSRF_COOKIE_SECURE = _secure_cookies
SESSION_COOKIE_SECURE = _secure_cookies

_csrf_schemes = ('https',) if _secure_cookies else ('http',)
CSRF_TRUSTED_ORIGINS = [
    f"{scheme}://{host}"
    for scheme in _csrf_schemes
    for host in ALLOWED_HOSTS
    if host != '*'
]
CSRF_TRUSTED_ORIGINS += [
    o.strip() for o in _config.security.additional_csrf_trusted_origins if o.strip()
]
# In DEBUG, also trust Vite dev server origin (host:5173) so accessing via http://HOST:5173 works
if DEBUG:
    CSRF_TRUSTED_ORIGINS += [f"http://{host}:5173" for host in ALLOWED_HOSTS if host != '*']

# SameSite prevents CSRF attacks while allowing normal usage
CSRF_COOKIE_SAMESITE = 'Lax'
SESSION_COOKIE_SAMESITE = 'Lax'

# Session cookie expires after 60 days of inactivity
SESSION_COOKIE_AGE = 60 * 24 * 60 * 60  # 5,184,000 seconds = 60 days
# Save session on every request to track last activity and extend expiration
SESSION_SAVE_EVERY_REQUEST = True

# File Upload Security Settings
# Note: File type configurations are centralized in geo_lib.processing.file_types
FILE_UPLOAD_MAX_MEMORY_SIZE = 5 * 1024 * 1024  # 5MB
DATA_UPLOAD_MAX_MEMORY_SIZE = 5 * 1024 * 1024  # 5MB
