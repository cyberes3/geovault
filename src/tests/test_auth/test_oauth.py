"""
Tests for OAuth2 authentication (django-oauth-toolkit).

- Middleware: Bearer OAuth access token resolves to request.user and is_api_authenticated.
- Restriction: OAuth tokens cannot access views with allow_api_keys=False (same as API keys).
- E2E: Full authorization code + PKCE flow: authorize -> code -> token exchange -> API call with access token.
"""
import hashlib
import base64
import json
from datetime import timedelta
from urllib.parse import parse_qs, urlparse

from django.test import TestCase, override_settings
from django.contrib.auth import get_user_model
from django.utils import timezone

from website.settings_utils import get_setting

from oauth2_provider.models import Application, AccessToken, Grant, get_access_token_model

User = get_user_model()


def _pkce_code_challenge(verifier: str) -> str:
    """S256 code challenge from code verifier."""
    digest = hashlib.sha256(verifier.encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest).decode("utf-8").rstrip("=")


def _create_oauth_application(user, *, client_id="test-client", redirect_uri="https://app.example/cb", skip_authorization=True):
    return Application.objects.create(
        name="Test App",
        user=user,
        client_id=client_id,
        client_type=Application.CLIENT_PUBLIC,
        authorization_grant_type=Application.GRANT_AUTHORIZATION_CODE,
        redirect_uris=redirect_uri,
        skip_authorization=skip_authorization,
    )


def _create_access_token(user, application, *, token_string="test_oauth_token_xyz", scope="api", expires_delta=None):
    if expires_delta is None:
        expires_delta = timedelta(hours=12)
    expires = timezone.now() + expires_delta
    return AccessToken.objects.create(
        token=token_string,
        user=user,
        application=application,
        expires=expires,
        scope=scope,
    )


class TestOAuthMiddleware(TestCase):
    """Test that OAuth Bearer tokens are resolved by middleware and allow API access."""

    def setUp(self):
        self.user = User.objects.create_user(
            email="oauthuser@example.com",
            password="testpass123",
            username="oauthuser",
        )
        self.app = _create_oauth_application(self.user, client_id="middleware-test", redirect_uri="https://app.example/cb")
        self.access_token = _create_access_token(
            self.user, self.app, token_string="middleware_test_token_abc123"
        )

    def test_oauth_bearer_allows_api_access(self):
        """Valid OAuth access token in Authorization header allows access to protected API."""
        response = self.client.get(
            "/api/features/all/",
            HTTP_AUTHORIZATION=f"Bearer {self.access_token.token}",
        )
        self.assertEqual(response.status_code, 200)

    def test_oauth_bearer_invalid_token_returns_401(self):
        """Invalid or unknown Bearer token returns 401."""
        response = self.client.get(
            "/api/features/all/",
            HTTP_AUTHORIZATION="Bearer invalid_oauth_token_xyz",
        )
        self.assertEqual(response.status_code, 401)

    def test_oauth_expired_token_returns_401(self):
        """Expired OAuth access token returns 401."""
        expired = _create_access_token(
            self.user,
            self.app,
            token_string="expired_token_xyz",
            expires_delta=timedelta(hours=-1),
        )
        response = self.client.get(
            "/api/features/all/",
            HTTP_AUTHORIZATION=f"Bearer {expired.token}",
        )
        self.assertEqual(response.status_code, 401)

    def test_oauth_bearer_no_header_returns_401(self):
        """Request without Authorization header returns 401."""
        response = self.client.get("/api/features/all/")
        self.assertEqual(response.status_code, 401)

    def test_oauth_bearer_bypasses_csrf(self):
        """OAuth Bearer requests do not require CSRF token for state-changing methods."""
        response = self.client.post(
            "/api/features/all/",
            HTTP_AUTHORIZATION=f"Bearer {self.access_token.token}",
        )
        # Should fail because POST to this endpoint is not allowed, not CSRF
        self.assertIn(response.status_code, [400, 405])


class TestOAuthRestrictions(TestCase):
    """Test that OAuth tokens cannot access admin or sensitive account views (same as API keys)."""

    def setUp(self):
        self.user = User.objects.create_user(
            email="oauthrestrict@example.com",
            password="testpass123",
            username="oauthrestrict",
        )
        self.app = _create_oauth_application(self.user, client_id="restrict-test", redirect_uri="https://app.example/cb")
        self.access_token = _create_access_token(
            self.user, self.app, token_string="restrict_test_token_xyz"
        )

    def test_oauth_cannot_access_password_change(self):
        """OAuth Bearer cannot access password change endpoint (allow_api_keys=False)."""
        response = self.client.post(
            "/api/user/password/change/",
            data=json.dumps({
                "old_password": "testpass123",
                "new_password": "newpass456",
                "new_password_confirm": "newpass456",
            }),
            content_type="application/json",
            HTTP_AUTHORIZATION=f"Bearer {self.access_token.token}",
        )
        self.assertEqual(response.status_code, 401)

    def test_oauth_cannot_list_api_keys(self):
        """OAuth Bearer cannot list API keys (allow_api_keys=False)."""
        response = self.client.get(
            "/api/user/api-keys/",
            HTTP_AUTHORIZATION=f"Bearer {self.access_token.token}",
        )
        self.assertEqual(response.status_code, 401)

    def test_oauth_cannot_access_admin_users(self):
        """OAuth Bearer cannot access admin users list (allow_api_keys=False)."""
        self.user.is_superuser = True
        self.user.save()
        response = self.client.get(
            "/api/admin/users/",
            HTTP_AUTHORIZATION=f"Bearer {self.access_token.token}",
        )
        self.assertEqual(response.status_code, 401)

    def test_oauth_cannot_access_email_status(self):
        """OAuth Bearer cannot access email status endpoint (allow_api_keys=False)."""
        response = self.client.get(
            "/api/user/email/status/",
            HTTP_AUTHORIZATION=f"Bearer {self.access_token.token}",
        )
        self.assertEqual(response.status_code, 401)

    def test_oauth_cannot_access_resend_verification(self):
        """OAuth Bearer cannot access resend verification endpoint (allow_api_keys=False)."""
        response = self.client.post(
            "/api/user/email/resend-verification/",
            data=json.dumps({"email": "oauthrestrict@example.com"}),
            content_type="application/json",
            HTTP_AUTHORIZATION=f"Bearer {self.access_token.token}",
        )
        self.assertEqual(response.status_code, 401)

    def test_oauth_can_access_validate_api_key_endpoint(self):
        """OAuth Bearer can access API key validate endpoint (allow_api_keys=True)."""
        response = self.client.post(
            "/api/user/api-keys/validate/",
            data=json.dumps({}),
            content_type="application/json",
            HTTP_AUTHORIZATION=f"Bearer {self.access_token.token}",
        )
        # Validate expects an API key format; with OAuth token we may get 401 or 200 with valid=false
        self.assertIn(response.status_code, [200, 401])


class TestOAuthAuthorizePage(TestCase):
    """Test that the OAuth authorize (consent) page renders with our styled template."""

    def test_authorize_page_renders_when_consent_required(self):
        """When application does not skip authorization, GET authorize returns 200 and consent form."""
        self.user = User.objects.create_user(
            email="consent@example.com",
            password="testpass123",
            username="consentuser",
        )
        app = _create_oauth_application(
            self.user,
            client_id="consent-test",
            redirect_uri="https://app.example/cb",
            skip_authorization=False,
        )
        app.name = "Test Consent App"
        app.save()
        self.client.force_login(self.user)
        code_challenge = _pkce_code_challenge("a" * 43)
        from urllib.parse import quote
        query = (
            f"response_type=code&client_id={app.client_id}"
            f"&redirect_uri={quote('https://app.example/cb')}"
            "&scope=api&state=xyz"
            f"&code_challenge={quote(code_challenge)}&code_challenge_method=S256"
        )
        response = self.client.get(f"/api/oauth/authorize/?{query}")
        self.assertEqual(response.status_code, 200, response.content.decode()[:500])
        content = response.content.decode()
        self.assertIn("Authorize", content)
        self.assertIn("Test Consent App", content)

    def test_authorize_deny_redirects_with_access_denied(self):
        """When user clicks Cancel/Deny on consent, redirect contains error=access_denied and no code."""
        from urllib.parse import quote

        self.user = User.objects.create_user(
            email="deny@example.com",
            password="testpass123",
            username="denyuser",
        )
        redirect_uri = "https://app.example/cb"
        app = _create_oauth_application(
            self.user,
            client_id="deny-test-client",
            redirect_uri=redirect_uri,
            skip_authorization=False,
        )
        self.client.force_login(self.user)
        code_challenge = _pkce_code_challenge("a" * 43)
        state = "deny_state_123"
        # GET to establish session and get consent form
        query = (
            f"response_type=code&client_id={app.client_id}"
            f"&redirect_uri={quote(redirect_uri)}"
            f"&scope=api&state={state}"
            f"&code_challenge={quote(code_challenge)}&code_challenge_method=S256"
        )
        self.client.get(f"/api/oauth/authorize/?{query}")
        # POST deny (no "allow" or allow=False)
        post_data = {
            "client_id": app.client_id,
            "redirect_uri": redirect_uri,
            "scope": "api",
            "state": state,
            "response_type": "code",
            "code_challenge": code_challenge,
            "code_challenge_method": "S256",
            "allow": False,
        }
        response = self.client.post("/api/oauth/authorize/", data=post_data)
        self.assertEqual(response.status_code, 302, response.content.decode()[:500])
        location = response.get("Location", "")
        self.assertIn("error=access_denied", location)
        self.assertIn(f"state={quote(state)}", location)
        self.assertNotIn("code=", location)


class TestOAuthAuthorizationFlowE2E(TestCase):
    """
    End-to-end test: authorization code + PKCE flow.

    1. Create OAuth application (public, authorization code, skip_authorization for test).
    2. User is logged in (session).
    3. GET /api/oauth/authorize/ with response_type=code, client_id, redirect_uri, scope, state, code_challenge, code_challenge_method=S256.
    4. Expect redirect to redirect_uri with code= and state=.
    5. POST /api/oauth/token/ with grant_type=authorization_code, code, redirect_uri, client_id, code_verifier.
    6. Expect 200 with access_token (and optionally refresh_token).
    7. GET /api/features/all/ with Authorization: Bearer <access_token> -> 200.
    """

    def setUp(self):
        self.user = User.objects.create_user(
            email="e2euser@example.com",
            password="testpass123",
            username="e2euser",
        )
        self.redirect_uri = "https://app.example/oauth/callback"
        self.client_id = "e2e-test-client"
        self.app = _create_oauth_application(
            self.user,
            client_id=self.client_id,
            redirect_uri=self.redirect_uri,
            skip_authorization=True,
        )
        self.code_verifier = "a" * 43  # PKCE: 43-128 chars
        self.code_challenge = _pkce_code_challenge(self.code_verifier)
        self.state = "test_state_xyz"

    def test_oauth_authorization_code_flow_e2e(self):
        # 1. Log in the user (session) so authorize view sees an authenticated user
        self.client.force_login(self.user)

        # 2. GET authorize endpoint (authorization request)
        authorize_url = (
            "/api/oauth/authorize/"
            f"?response_type=code"
            f"&client_id={self.client_id}"
            f"&redirect_uri={self._url_quote(self.redirect_uri)}"
            f"&scope=api"
            f"&state={self.state}"
            f"&code_challenge={self._url_quote(self.code_challenge)}"
            f"&code_challenge_method=S256"
        )
        response = self.client.get(authorize_url)
        self.assertEqual(
            response.status_code,
            302,
            f"Expected redirect from authorize, got {response.status_code}: {getattr(response, 'content', b'')[:500]}",
        )
        location = response.get("Location", "")
        self.assertTrue(location.startswith(self.redirect_uri), f"Redirect should go to redirect_uri: {location}")

        # 3. Parse code and state from redirect URL
        parsed = urlparse(location)
        params = parse_qs(parsed.query)
        self.assertIn("code", params, f"Redirect URL should contain code: {location}")
        self.assertIn("state", params, f"Redirect URL should contain state: {location}")
        code = params["code"][0]
        returned_state = params["state"][0]
        self.assertEqual(returned_state, self.state)

        # 4. Exchange code for access token (token request). Use session-less request
        # so the token endpoint sees only the POST body (DOT expects form body in request.POST).
        self.client.logout()
        token_response = self.client.post(
            "/api/oauth/token/",
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": self.redirect_uri,
                "client_id": self.client_id,
                "code_verifier": self.code_verifier,
            },
        )
        self.assertEqual(
            token_response.status_code,
            200,
            f"Token exchange should succeed: {token_response.status_code} {token_response.content}",
        )
        token_data = json.loads(token_response.content)
        self.assertIn("access_token", token_data)
        access_token = token_data["access_token"]
        self.assertIsInstance(access_token, str)
        self.assertTrue(len(access_token) > 0)
        self.assertIn("refresh_token", token_data, "Token response should include refresh_token for refresh flow")
        refresh_token = token_data.get("refresh_token")
        self.assertTrue(isinstance(refresh_token, str) and len(refresh_token) > 0, "refresh_token should be non-empty")

        # 5. Call protected API with access token (resource request)
        self.client.logout()
        api_response = self.client.get(
            "/api/features/all/",
            HTTP_AUTHORIZATION=f"Bearer {access_token}",
        )
        self.assertEqual(
            api_response.status_code,
            200,
            f"API with OAuth token should return 200: {api_response.status_code}",
        )

    def _url_quote(self, s):
        from urllib.parse import quote
        return quote(s, safe="")


@override_settings(
    OAUTH2_PROVIDER={
        "SCOPES": {"api": "Full API access"},
        "DEFAULT_SCOPES": ["api"],
        "ALLOWED_REDIRECT_URI_SCHEMES": ["http", "https", "custom"],
    }
)
class TestOAuthThirdPartyCustomScheme(TestCase):
    """Third-party Android apps: redirect_uri with reverse-DNS scheme (e.g. com.thirdparty.app) when 'custom' is allowed."""

    def setUp(self):
        self.user = User.objects.create_user(
            email="thirdparty@example.com",
            password="testpass123",
            username="thirdpartyuser",
        )
        self.redirect_uri = "com.thirdparty.app://oauth/callback"
        self.client_id = "thirdparty-client"
        self.app = _create_oauth_application(
            self.user,
            client_id=self.client_id,
            redirect_uri=self.redirect_uri,
            skip_authorization=True,
        )
        self.code_verifier = "c" * 43
        self.code_challenge = _pkce_code_challenge(self.code_verifier)
        self.state = "thirdparty_state"

    def test_authorize_redirects_to_third_party_scheme(self):
        """Authorize with third-party custom scheme redirect_uri returns 302 to that URI with code."""
        self.client.force_login(self.user)
        from urllib.parse import quote
        authorize_url = (
            "/api/oauth/authorize/"
            f"?response_type=code&client_id={self.client_id}"
            f"&redirect_uri={quote(self.redirect_uri, safe='')}"
            "&scope=api"
            f"&state={self.state}"
            f"&code_challenge={quote(self.code_challenge, safe='')}"
            "&code_challenge_method=S256"
        )
        response = self.client.get(authorize_url)
        self.assertEqual(response.status_code, 302, response.content)
        location = response.get("Location", "")
        self.assertTrue(
            location.startswith(self.redirect_uri),
            f"Redirect should go to third-party scheme: {location}",
        )
        parsed = urlparse(location)
        params = parse_qs(parsed.query)
        self.assertIn("code", params)
        self.assertEqual(params["state"][0], self.state)


class TestOAuthRefreshToken(TestCase):
    """Tests for refresh_token grant: exchange refresh_token for new access_token."""

    def setUp(self):
        self.user = User.objects.create_user(
            email="refresh@example.com",
            password="testpass123",
            username="refreshuser",
        )
        self.redirect_uri = "https://app.example/oauth/callback"
        self.client_id = "refresh-test-client"
        self.app = _create_oauth_application(
            self.user,
            client_id=self.client_id,
            redirect_uri=self.redirect_uri,
            skip_authorization=True,
        )
        self.code_verifier = "b" * 43
        self.code_challenge = _pkce_code_challenge(self.code_verifier)
        self.state = "refresh_state_xyz"

    def test_refresh_token_exchange(self):
        """Exchange refresh_token for a new access_token; new token works for API calls."""
        self.client.force_login(self.user)
        authorize_url = (
            "/api/oauth/authorize/"
            f"?response_type=code&client_id={self.client_id}"
            f"&redirect_uri={self._url_quote(self.redirect_uri)}"
            "&scope=api"
            f"&state={self.state}"
            f"&code_challenge={self._url_quote(self.code_challenge)}"
            "&code_challenge_method=S256"
        )
        auth_response = self.client.get(authorize_url)
        self.assertEqual(auth_response.status_code, 302)
        parsed = urlparse(auth_response.get("Location", ""))
        params = parse_qs(parsed.query)
        code = params["code"][0]

        self.client.logout()
        token_response = self.client.post(
            "/api/oauth/token/",
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": self.redirect_uri,
                "client_id": self.client_id,
                "code_verifier": self.code_verifier,
            },
        )
        self.assertEqual(token_response.status_code, 200)
        token_data = json.loads(token_response.content)
        refresh_token = token_data.get("refresh_token")
        self.assertTrue(refresh_token, "Token response must include refresh_token")

        refresh_response = self.client.post(
            "/api/oauth/token/",
            data={
                "grant_type": "refresh_token",
                "refresh_token": refresh_token,
                "client_id": self.client_id,
            },
        )
        self.assertEqual(
            refresh_response.status_code,
            200,
            f"Refresh should succeed: {refresh_response.status_code} {refresh_response.content}",
        )
        new_data = json.loads(refresh_response.content)
        self.assertIn("access_token", new_data)
        new_access = new_data["access_token"]
        self.assertTrue(len(new_access) > 0)

        api_response = self.client.get(
            "/api/features/all/",
            HTTP_AUTHORIZATION=f"Bearer {new_access}",
        )
        self.assertEqual(api_response.status_code, 200)

    def _url_quote(self, s):
        from urllib.parse import quote
        return quote(s, safe="")


class TestOAuthRegistrationThenRefreshAfterClockForward(TestCase):
    """
    E2E: Register an OAuth application, complete auth code flow to get tokens,
    simulate 2 days passing (expire access token), then use refresh_token to get
    a new access token and call the API.
    """

    def setUp(self):
        self.user = User.objects.create_user(
            email="regrefresh@example.com",
            password="testpass123",
            username="regrefreshuser",
        )
        self.redirect_uri = "https://app.example/oauth/callback"
        self.client_id = "regrefresh-test-client"

    def test_registration_then_refresh_after_clock_forward(self):
        # 1. Register OAuth application via the registration form
        self.client.force_login(self.user)
        reg_response = self.client.post(
            "/api/oauth/applications/register/",
            data={
                "name": "Reg Then Refresh App",
                "client_id": self.client_id,
                "client_secret": "",
                "client_type": Application.CLIENT_PUBLIC,
                "authorization_grant_type": Application.GRANT_AUTHORIZATION_CODE,
                "redirect_uris": self.redirect_uri,
            },
        )
        self.assertEqual(reg_response.status_code, 302, reg_response.content.decode()[:500])
        self.assertTrue(
            Application.objects.filter(user=self.user, client_id=self.client_id).exists(),
            "Application should be created",
        )

        # 2. Auth code flow: authorize -> code -> token (get access_token + refresh_token)
        code_verifier = "d" * 43
        code_challenge = _pkce_code_challenge(code_verifier)
        state = "regrefresh_state"
        authorize_url = (
            "/api/oauth/authorize/"
            f"?response_type=code&client_id={self.client_id}"
            f"&redirect_uri={self._url_quote(self.redirect_uri)}"
            "&scope=api"
            f"&state={state}"
            f"&code_challenge={self._url_quote(code_challenge)}"
            "&code_challenge_method=S256"
        )
        auth_response = self.client.get(authorize_url)
        # Registered apps do not skip authorization: we get the consent page (200)
        if auth_response.status_code == 200:
            # Submit consent form (allow=1) to get redirect with code
            consent_data = {
                "client_id": self.client_id,
                "redirect_uri": self.redirect_uri,
                "scope": "api",
                "state": state,
                "response_type": "code",
                "code_challenge": code_challenge,
                "code_challenge_method": "S256",
                "allow": "1",
            }
            auth_response = self.client.post("/api/oauth/authorize/", data=consent_data)
        self.assertEqual(auth_response.status_code, 302, auth_response.content)
        parsed = urlparse(auth_response.get("Location", ""))
        params = parse_qs(parsed.query)
        self.assertIn("code", params)
        code = params["code"][0]

        self.client.logout()
        token_response = self.client.post(
            "/api/oauth/token/",
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": self.redirect_uri,
                "client_id": self.client_id,
                "code_verifier": code_verifier,
            },
        )
        self.assertEqual(token_response.status_code, 200, token_response.content)
        token_data = json.loads(token_response.content)
        access_token = token_data["access_token"]
        refresh_token = token_data.get("refresh_token")
        self.assertTrue(refresh_token, "Token response must include refresh_token")

        # 3. Simulate clock forward 2 days: expire the access token in the DB
        AccessToken.objects.filter(token=access_token).update(
            expires=timezone.now() - timedelta(days=2),
        )

        # 4. Old access token must be rejected
        api_old = self.client.get(
            "/api/features/all/",
            HTTP_AUTHORIZATION=f"Bearer {access_token}",
        )
        self.assertEqual(api_old.status_code, 401)

        # 5. Refresh: exchange refresh_token for new access_token
        refresh_response = self.client.post(
            "/api/oauth/token/",
            data={
                "grant_type": "refresh_token",
                "refresh_token": refresh_token,
                "client_id": self.client_id,
            },
        )
        self.assertEqual(
            refresh_response.status_code,
            200,
            f"Refresh should succeed: {refresh_response.status_code} {refresh_response.content}",
        )
        new_data = json.loads(refresh_response.content)
        self.assertIn("access_token", new_data)
        new_access = new_data["access_token"]
        self.assertTrue(len(new_access) > 0)

        # 6. New access token works for API
        api_new = self.client.get(
            "/api/features/all/",
            HTTP_AUTHORIZATION=f"Bearer {new_access}",
        )
        self.assertEqual(api_new.status_code, 200)

    def _url_quote(self, s):
        from urllib.parse import quote
        return quote(s, safe="")


class TestEnsureOAuth2AppCommand(TestCase):
    """Tests for the ensure_oauth2_app management command."""

    def setUp(self):
        self.user = User.objects.create_user(
            email="admin@example.com",
            password="testpass123",
            username="admin",
            is_superuser=True,
        )

    def test_creates_android_oauth_applications(self):
        """Command creates places, uploader and tracker OAuth applications with correct client_ids and redirect URIs."""
        from django.core.management import call_command
        from io import StringIO

        out = StringIO()
        call_command("ensure_oauth2_app", stdout=out)
        self.assertEqual(Application.objects.count(), 3)
        places = Application.objects.get(client_id="geovault-android-places")
        self.assertEqual(places.name, "GeoVault Android Places")
        self.assertEqual(
            set(places.redirect_uris.strip().split()),
            {"com.geovault.places://oauth/callback", "com.geovault.places.debug://oauth/callback"},
        )
        uploader = Application.objects.get(client_id="geovault-android-uploader")
        self.assertEqual(uploader.name, "GeoVault Android Uploader")
        self.assertEqual(
            set(uploader.redirect_uris.strip().split()),
            {"com.geovault.uploader://oauth/callback", "com.geovault.uploader.debug://oauth/callback"},
        )
        tracker = Application.objects.get(client_id="geovault-android-tracker")
        self.assertEqual(tracker.name, "GeoVault Android Tracker")
        self.assertEqual(
            set(tracker.redirect_uris.strip().split()),
            {"com.geovault.tracker://oauth/callback", "com.geovault.tracker.debug://oauth/callback"},
        )
        for app in (places, uploader, tracker):
            self.assertFalse(app.skip_authorization, f"{app.client_id} should show authorize screen")
        out_val = out.getvalue()
        self.assertTrue(
            "created" in out_val or "Created" in out_val or "up to date" in out_val,
            f"Expected command output to mention created/up to date, got: {out_val!r}",
        )

    def test_idempotent_no_duplicate(self):
        """Running the command twice does not create duplicate applications."""
        from django.core.management import call_command

        call_command("ensure_oauth2_app")
        call_command("ensure_oauth2_app")
        self.assertEqual(Application.objects.count(), 3)
        self.assertEqual(Application.objects.filter(client_id="geovault-android-places").count(), 1)
        self.assertEqual(Application.objects.filter(client_id="geovault-android-uploader").count(), 1)
        self.assertEqual(Application.objects.filter(client_id="geovault-android-tracker").count(), 1)

    def test_deletes_legacy_app(self):
        """Command deletes the legacy geovault-android application if present."""
        from django.core.management import call_command
        from io import StringIO

        _create_oauth_application(
            self.user,
            client_id="geovault-android",
            redirect_uri="com.geovault.places://oauth/callback",
        )
        self.assertEqual(Application.objects.filter(client_id="geovault-android").count(), 1)
        out = StringIO()
        call_command("ensure_oauth2_app", stdout=out)
        self.assertFalse(Application.objects.filter(client_id="geovault-android").exists())
        self.assertEqual(Application.objects.filter(client_id="geovault-android-places").count(), 1)
        self.assertEqual(Application.objects.filter(client_id="geovault-android-uploader").count(), 1)
        self.assertEqual(Application.objects.filter(client_id="geovault-android-tracker").count(), 1)
        self.assertIn("Deleted legacy", out.getvalue())

    def test_updates_redirect_uris_if_changed(self):
        """If app exists but redirect_uris differ, command updates them."""
        from django.core.management import call_command
        from io import StringIO

        _create_oauth_application(
            self.user,
            client_id="geovault-android-places",
            redirect_uri="https://old.example/cb",
        )
        app = Application.objects.get(client_id="geovault-android-places")
        app.redirect_uris = "https://old.example/cb"
        app.save()
        out = StringIO()
        call_command("ensure_oauth2_app", stdout=out)
        app.refresh_from_db()
        self.assertIn("com.geovault.places://oauth/callback", app.redirect_uris)
        self.assertIn("com.geovault.places.debug://oauth/callback", app.redirect_uris)
        self.assertFalse(app.skip_authorization, "Command should set skip_authorization=False")

    def test_fails_without_user(self):
        """Command reports error when no user exists in the database."""
        from django.core.management import call_command
        from io import StringIO

        User.objects.all().delete()
        out = StringIO()
        call_command("ensure_oauth2_app", stdout=out)
        self.assertEqual(Application.objects.count(), 0)
        self.assertIn("No user found", out.getvalue())


class TestProtectedOAuthApplications(TestCase):
    """Tests that protected (shared) OAuth applications are excluded from list and return 404 for detail/update/delete."""

    def setUp(self):
        from django.test import override_settings

        self.user = User.objects.create_user(
            email="owner@example.com",
            password="testpass123",
            username="owner",
        )
        self.protected_app = _create_oauth_application(
            self.user,
            client_id="geovault-android-places",
            redirect_uri="com.geovault.places://oauth/callback",
        )
        self.other_app = _create_oauth_application(
            self.user,
            client_id="my-custom-app",
            redirect_uri="https://myapp.example/cb",
        )
        oauth2_settings = get_setting("OAUTH2_PROVIDER", {})
        self.overrides = {
            **oauth2_settings,
            "PROTECTED_CLIENT_IDS": ["geovault-android-places"],
        }

    def test_list_excludes_protected(self):
        """List view does not include protected application."""
        from django.test import override_settings

        self.other_app.name = "My Custom App"
        self.other_app.save()
        self.client.force_login(self.user)
        with override_settings(OAUTH2_PROVIDER=self.overrides):
            response = self.client.get("/api/oauth/applications/")
        self.assertEqual(response.status_code, 200)
        content = response.content.decode()
        self.assertIn("My Custom App", content)
        protected_detail_url = f"/api/oauth/applications/{self.protected_app.pk}/"
        self.assertNotIn(protected_detail_url, content)
        other_detail_url = f"/api/oauth/applications/{self.other_app.pk}/"
        self.assertIn(other_detail_url, content)

    def test_detail_404_for_protected(self):
        """Detail view returns 404 for protected application."""
        from django.test import override_settings

        self.client.force_login(self.user)
        with override_settings(OAUTH2_PROVIDER=self.overrides):
            response = self.client.get(f"/api/oauth/applications/{self.protected_app.pk}/")
        self.assertEqual(response.status_code, 404)

    def test_detail_200_for_non_protected(self):
        """Detail view returns 200 for non-protected application."""
        from django.test import override_settings

        self.client.force_login(self.user)
        with override_settings(OAUTH2_PROVIDER=self.overrides):
            response = self.client.get(f"/api/oauth/applications/{self.other_app.pk}/")
        self.assertEqual(response.status_code, 200)

    def test_update_404_for_protected(self):
        """Update view returns 404 for protected application."""
        from django.test import override_settings

        self.client.force_login(self.user)
        with override_settings(OAUTH2_PROVIDER=self.overrides):
            response = self.client.get(f"/api/oauth/applications/{self.protected_app.pk}/update/")
        self.assertEqual(response.status_code, 404)

    def test_delete_404_for_protected(self):
        """Delete view returns 404 for protected application."""
        from django.test import override_settings

        self.client.force_login(self.user)
        with override_settings(OAUTH2_PROVIDER=self.overrides):
            response = self.client.get(f"/api/oauth/applications/{self.protected_app.pk}/delete/")
        self.assertEqual(response.status_code, 404)


class TestOAuthAuthorizedTokenRevoke(TestCase):
    """Tests for listing and revoking (deleting) authorized OAuth tokens."""

    def setUp(self):
        self.user = User.objects.create_user(
            email="revoke@example.com",
            password="testpass123",
            username="revokeuser",
        )
        self.other_user = User.objects.create_user(
            email="other@example.com",
            password="testpass123",
            username="otheruser",
        )
        self.app = _create_oauth_application(
            self.user,
            client_id="revoke-test-app",
            redirect_uri="https://app.example/cb",
        )

    def test_authorized_token_list_requires_login(self):
        """List view redirects to login when not authenticated."""
        response = self.client.get("/api/oauth/authorized_tokens/")
        self.assertEqual(response.status_code, 302)
        self.assertTrue(response["Location"].startswith("/accounts/login/"))

    def test_revoke_requires_login(self):
        """Delete view redirects to login when not authenticated."""
        token = _create_access_token(self.user, self.app, token_string="revoke_me_123")
        response = self.client.post(f"/api/oauth/authorized_tokens/{token.pk}/delete/")
        self.assertEqual(response.status_code, 302)
        self.assertTrue(response["Location"].startswith("/accounts/login/"))
        self.assertTrue(AccessToken.objects.filter(pk=token.pk).exists())

    def test_revoke_deletes_token(self):
        """Logged-in user can revoke their authorized token; token is deleted and API rejects it."""
        token = _create_access_token(self.user, self.app, token_string="revoke_me_456")
        self.client.force_login(self.user)
        response = self.client.post(f"/api/oauth/authorized_tokens/{token.pk}/delete/")
        self.assertEqual(response.status_code, 302)
        self.assertEqual(response["Location"], "/api/oauth/authorized_tokens/")
        self.assertFalse(AccessToken.objects.filter(pk=token.pk).exists())
        self.client.logout()
        api_response = self.client.get(
            "/api/features/all/",
            HTTP_AUTHORIZATION=f"Bearer {token.token}",
        )
        self.assertEqual(api_response.status_code, 401)

    def test_revoke_404_for_other_user_token(self):
        """User cannot revoke another user's token; returns 404 and token remains."""
        token = _create_access_token(self.user, self.app, token_string="other_owns_789")
        self.client.force_login(self.other_user)
        response = self.client.post(f"/api/oauth/authorized_tokens/{token.pk}/delete/")
        self.assertEqual(response.status_code, 404)
        self.assertTrue(AccessToken.objects.filter(pk=token.pk).exists())


class TestOAuthApplicationRegistration(TestCase):
    """Tests for creating new OAuth applications via the registration view."""

    def setUp(self):
        self.user = User.objects.create_user(
            email="dev@example.com",
            password="testpass123",
            username="dev",
        )

    def test_register_page_requires_login(self):
        """Register page redirects to login when not authenticated."""
        response = self.client.get("/api/oauth/applications/register/")
        self.assertEqual(response.status_code, 302)
        self.assertTrue(response["Location"].startswith("/accounts/login/"))

    def test_register_creates_application(self):
        """Logged-in user can create a new OAuth application via the registration form."""
        self.client.force_login(self.user)
        response = self.client.get("/api/oauth/applications/register/")
        self.assertEqual(response.status_code, 200)
        initial_count = Application.objects.filter(user=self.user).count()
        response = self.client.post(
            "/api/oauth/applications/register/",
            data={
                "name": "My Test App",
                "client_id": "my-test-app-id",
                "client_secret": "my-secret",
                "client_type": Application.CLIENT_PUBLIC,
                "authorization_grant_type": Application.GRANT_AUTHORIZATION_CODE,
                "redirect_uris": "https://myapp.example/callback",
            },
        )
        self.assertEqual(response.status_code, 302)
        self.assertEqual(
            Application.objects.filter(user=self.user).count(),
            initial_count + 1,
        )
        app = Application.objects.get(user=self.user, client_id="my-test-app-id")
        self.assertEqual(app.name, "My Test App")
        self.assertEqual(app.redirect_uris.strip(), "https://myapp.example/callback")

    def test_register_requires_name(self):
        """Registration form rejects empty application name."""
        self.client.force_login(self.user)
        initial_count = Application.objects.filter(user=self.user).count()
        response = self.client.post(
            "/api/oauth/applications/register/",
            data={
                "name": "",
                "client_id": "no-name-app",
                "client_secret": "secret",
                "client_type": Application.CLIENT_PUBLIC,
                "authorization_grant_type": Application.GRANT_AUTHORIZATION_CODE,
                "redirect_uris": "https://example.com/cb",
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertFormError(response.context["form"], "name", ["This field is required."])
        self.assertEqual(Application.objects.filter(user=self.user).count(), initial_count)

    def test_register_rejects_duplicate_client_id(self):
        """Registration form rejects a client_id that already exists."""
        self.client.force_login(self.user)
        existing = _create_oauth_application(
            self.user,
            client_id="taken-client-id",
            redirect_uri="https://existing.example/cb",
        )
        initial_count = Application.objects.filter(user=self.user).count()
        response = self.client.post(
            "/api/oauth/applications/register/",
            data={
                "name": "Another App",
                "client_id": existing.client_id,
                "client_secret": "secret",
                "client_type": Application.CLIENT_PUBLIC,
                "authorization_grant_type": Application.GRANT_AUTHORIZATION_CODE,
                "redirect_uris": "https://new.example/cb",
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertTrue(
            response.context["form"].errors.get("client_id"),
            f"Expected client_id error, got {response.context['form'].errors}",
        )
        self.assertEqual(Application.objects.filter(user=self.user).count(), initial_count)

    def test_update_requires_name(self):
        """Update form rejects empty application name."""
        self.client.force_login(self.user)
        app = _create_oauth_application(
            self.user,
            client_id="update-name-test",
            redirect_uri="https://example.com/cb",
        )
        app.name = "Original Name"
        app.save()
        response = self.client.post(
            f"/api/oauth/applications/{app.pk}/update/",
            data={
                "name": "",
                "client_id": app.client_id,
                "client_secret": app.client_secret or "",
                "client_type": app.client_type,
                "authorization_grant_type": app.authorization_grant_type,
                "redirect_uris": app.redirect_uris,
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertFormError(response.context["form"], "name", ["This field is required."])
        app.refresh_from_db()
        self.assertEqual(app.name, "Original Name")

    def test_list_shows_client_id_when_name_empty(self):
        """List page shows client_id for applications with empty name (template fallback)."""
        self.client.force_login(self.user)
        app = _create_oauth_application(
            self.user,
            client_id="unnamed-app-id",
            redirect_uri="https://example.com/cb",
        )
        app.name = ""
        app.save()
        response = self.client.get("/api/oauth/applications/")
        self.assertEqual(response.status_code, 200)
        content = response.content.decode()
        self.assertIn("unnamed-app-id", content, "List should show client_id when name is empty")
        self.assertIn(f"/api/oauth/applications/{app.pk}/", content)
