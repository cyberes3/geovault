"""Static file serving via WhiteNoise (CSS/JS/images, including the built frontend)."""
from website.settings.paths import BASE_DIR
from website.settings.security import DEBUG, SITE_DOMAIN

STATIC_URL = '/static/'

# Use StaticFilesStorage (not compressed) since Vite already handles file hashing and compression
STATICFILES_STORAGE = 'django.contrib.staticfiles.storage.StaticFilesStorage'

# WhiteNoise settings - serves files directly from source directories
WHITENOISE_USE_FINDERS = True  # Serve directly from STATICFILES_DIRS (no collectstatic needed!)
# Disable auto-refresh in development to improve performance (file watching is handled by runserver)
# Static files are still served, but changes won't trigger automatic reload
WHITENOISE_AUTOREFRESH = False
WHITENOISE_MANIFEST_STRICT = False  # Don't require manifest file
# WHITENOISE_ROOT serves files at the root URL (e.g., /favicon.ico, /apple-touch-icon.png)
WHITENOISE_ROOT = BASE_DIR / '../frontend/dist'

# Cache control for static files
# Vite adds content hashes to filenames (e.g., index-0MtPbVXm.js), so we can cache aggressively
WHITENOISE_MAX_AGE = 31536000  # 1 year in seconds (60*60*24*365)
# Mark files with content hashes as immutable for optimal caching
WHITENOISE_IMMUTABLE_FILE_TEST = lambda path, url: url.startswith('/static/') and '-' in url.split('/')[-1]


def _whitenoise_add_cors_headers(headers, path, url):
    """Set CORS header for WhiteNoise static files to match site domain."""
    # Use the same logic as CustomHeaderMiddleware to determine the correct origin
    protocol = 'https' if not DEBUG else 'http'
    headers['Access-Control-Allow-Origin'] = f'{protocol}://{SITE_DOMAIN}'


# WhiteNoise CORS header configuration. By default, WhiteNoise sets
# Access-Control-Allow-Origin: * for static files; override to use the site's domain instead.
WHITENOISE_ADD_HEADERS_FUNCTION = _whitenoise_add_cors_headers

STATICFILES_DIRS = [
    # WhiteNoise serves directly from these directories (WHITENOISE_USE_FINDERS = True)
    # Vite's assetsDir: 'static' puts files in dist/static/, which serves at /static/xxx.js
    BASE_DIR / '../frontend/dist/static',
    BASE_DIR / 'assets',
]
