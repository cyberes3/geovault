"""
SECRET_KEY validation, enforced at settings-load time.

This is a self-hosted application: there is no "we'll rotate it before going to
production" safety net. A missing or placeholder SECRET_KEY must abort startup
immediately and loudly, not just log a warning that's easy to miss in server logs.
"""
from django.core.exceptions import ImproperlyConfigured

# Placeholder values that must never be used as a real SECRET_KEY: the
# config.example.yaml placeholder, and this project's own former hardcoded
# settings.py default, in case either was ever copied verbatim into a real config.yaml.
_KNOWN_INSECURE_PLACEHOLDERS = frozenset({
    'django-insecure-change-this-in-production',
    'django-insecure-f(1zo%f)wm*rl97q0^3!9exd%(s8mz92nagf4q7c2cno&bmyx=',
})


def require_secret_key(secret_key) -> str:
    """
    Validate a SECRET_KEY value read from config/environment.

    Raises:
        ImproperlyConfigured: if the value is missing or is a known insecure
            placeholder. This aborts Django startup entirely.
    """
    if not secret_key or not isinstance(secret_key, str) or not secret_key.strip():
        raise ImproperlyConfigured(
            "SECRET_KEY is not configured. Set 'security.secret_key' in config.yaml "
            "(see config.example.yaml) or the SECRET_KEY environment variable. There is "
            "no default: this is a self-hosted app and must never run with a shared, "
            "publicly-known secret key."
        )
    if secret_key in _KNOWN_INSECURE_PLACEHOLDERS:
        raise ImproperlyConfigured(
            "SECRET_KEY is set to a known placeholder value from config.example.yaml. "
            "Generate a real secret key (e.g. `python -c \"import secrets; "
            "print(secrets.token_urlsafe(50))\"`) and set it via 'security.secret_key' "
            "in config.yaml or the SECRET_KEY environment variable."
        )
    return secret_key
