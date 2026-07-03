"""
Reject the weak PKCE "plain" code_challenge_method, allowing only "S256".

django-oauth-toolkit already requires PKCE (`PKCE_REQUIRED = True`, the default), but the
underlying oauthlib `AuthorizationCodeGrant` still accepts `code_challenge_method=plain` and,
worse, silently defaults an authorization request to `plain` when the client omits the parameter
entirely (see `oauthlib.oauth2.rfc6749.grant_types.authorization_code`). Under `plain`, the
code_verifier sent at the authorization step equals the code_challenge sent at the token-exchange
step, so PKCE provides no protection against interception of the authorization code — it's
"required" in name only. RFC 7636 recommends S256 be used whenever possible.

Dropping "plain" from the class-level challenge-method registry closes both the explicit
(`code_challenge_method=plain`) and implicit (omitted parameter, defaults to "plain") paths,
since both are validated against this same dict at the authorization and token-exchange steps.

Import this before any oauth2_provider views are used (e.g. at top of oauth_urls), same pattern
as oauth_custom_scheme.py.
"""
from oauthlib.oauth2.rfc6749.grant_types.authorization_code import AuthorizationCodeGrant

AuthorizationCodeGrant._code_challenge_methods = {
    method: verify_fn
    for method, verify_fn in AuthorizationCodeGrant._code_challenge_methods.items()
    if method != "plain"
}
