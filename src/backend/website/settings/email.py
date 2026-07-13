"""Outbound email (SMTP backend, sender identity, subject prefix)."""
from website.config.loader import get_config

_config = get_config()
_email = _config.email

EMAIL_BACKEND = 'django.core.mail.backends.smtp.EmailBackend'
EMAIL_HOST = _email.smtp.host
EMAIL_PORT = _email.smtp.port
EMAIL_USE_TLS = _email.smtp.use_tls
EMAIL_USE_SSL = _email.smtp.use_ssl
EMAIL_HOST_USER = _email.smtp.username
EMAIL_HOST_PASSWORD = _email.smtp.password

_from_email = _email.from_email
_from_name = _email.from_name

# Format "From" email with optional name
DEFAULT_FROM_EMAIL = f'{_from_name} <{_from_email}>' if _from_name else _from_email
SERVER_EMAIL = _from_email  # Used for error notifications to admins (email only, no name)

EMAIL_SUBJECT_PREFIX = _email.subject_prefix
