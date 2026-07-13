"""Authentication: django-allauth (session/browser login) and django-oauth-toolkit (API/mobile clients)."""
from website.settings.email import EMAIL_SUBJECT_PREFIX
from website.settings.security import DEBUG

LOGIN_REDIRECT_URL = '/'
LOGOUT_REDIRECT_URL = '/'
LOGIN_URL = '/accounts/login/'

AUTHENTICATION_BACKENDS = [
    'django.contrib.auth.backends.ModelBackend',
    'allauth.account.auth_backends.AuthenticationBackend',
    'oauth2_provider.backends.OAuth2Backend',
]

SITE_ID = 1  # Required by allauth

# Account settings
ACCOUNT_ADAPTER = 'users.adapters.NoUsernameAccountAdapter'  # Custom adapter to prevent username usage
ACCOUNT_LOGIN_METHODS = {'email'}  # Use email for authentication
ACCOUNT_EMAIL_VERIFICATION = 'optional'  # Require email verification
ACCOUNT_SIGNUP_FIELDS = ['email*', 'password1*', 'password2*']  # Email required, no username
ACCOUNT_SESSION_REMEMBER = True
ACCOUNT_LOGOUT_ON_GET = False
ACCOUNT_LOGOUT_REDIRECT_URL = LOGOUT_REDIRECT_URL
ACCOUNT_LOGIN_REDIRECT_URL = LOGIN_REDIRECT_URL
ACCOUNT_EMAIL_SUBJECT_PREFIX = EMAIL_SUBJECT_PREFIX
# Use https in production, http in development
ACCOUNT_DEFAULT_HTTP_PROTOCOL = 'https' if not DEBUG else 'http'

# OAuth2 Provider (django-oauth-toolkit) for mobile and API clients
# PROTECTED_CLIENT_IDS: applications with these client_ids are shared (e.g. Android Places/Uploader).
# Only the server can change them; users cannot edit or delete them in the UI.
# ALLOWED_REDIRECT_URI_SCHEMES: permit Android app custom schemes for OAuth callback redirects.
OAUTH2_PROVIDER = {
    'SCOPES': {
        'api': 'Full API access (read and write)',
    },
    'DEFAULT_SCOPES': ['api'],
    'ACCESS_TOKEN_EXPIRE_SECONDS': 3600 * 12,  # 12 hours
    'REFRESH_TOKEN_EXPIRE_SECONDS': 3600 * 24 * 365,  # 1 year
    'PROTECTED_CLIENT_IDS': ['geovault-android-places', 'geovault-android-uploader', 'geovault-android-tracker'],
    # Redirect URI schemes: only these are accepted when an app registers or uses a redirect_uri.
    # Real security is that the redirect_uri must be in the application's registered list.
    # "custom" (via oauth_custom_scheme monkeypatch) allows any reverse-DNS scheme (e.g. com.*.app).
    'ALLOWED_REDIRECT_URI_SCHEMES': ['http', 'https', 'custom'],
}
