"""
Django middleware, split by concern (see website.settings.app_config.MIDDLEWARE for wiring):
logging (request/response access log), security_headers (CORS/CSP), auth (Bearer API key/OAuth
resolution), session (tile-request cookie stripping), activity (last-active tracking), host_fix
(email-URL host override).
"""
