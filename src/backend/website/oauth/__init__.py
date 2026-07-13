"""
OAuth2 provider integration (django-oauth-toolkit), consolidated by concern:
- urls: URLconf mounted at /api/oauth/
- views: application/token management views restricting access to protected (shared) apps
- custom_scheme: monkeypatch allowing reverse-DNS custom redirect schemes (native apps)
- pkce: monkeypatch rejecting the weak PKCE "plain" challenge method

Templates live in oauth/templates/oauth2_provider/ (registered in
website.settings.app_config.TEMPLATES['DIRS']).
"""
