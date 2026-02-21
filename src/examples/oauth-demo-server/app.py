"""
Standalone OAuth demo server: demonstrates how a third-party website can access
a GeoVault server using the authorization code flow with manual application registration.

1. Run: flask --app app run --port 8765
2. Open http://localhost:8765 and enter your GeoVault URL and OAuth client credentials
   (from manual registration at {GEOVAULT_URL}/api/oauth/applications/register/).
3. Click "Connect to GeoVault" to run the OAuth flow.
"""
import base64
import hashlib
import json
import os
import secrets
import time
from urllib.parse import urlencode

import requests
from flask import Flask, redirect, request, session, url_for

app = Flask(__name__)
# Use a fixed default so the session survives server restarts during the OAuth redirect flow.
# Set FLASK_SECRET_KEY in the environment if you need a custom secret.
app.secret_key = os.environ.get("FLASK_SECRET_KEY", "geovault-oauth-demo-dev-secret")

SCOPE = "api"
DEFAULT_REDIRECT_URI = "http://localhost:8765/callback"
PENDING_STATE_TTL_SECONDS = 600  # 10 minutes

# Server-side store for in-progress authorizations so the callback does not depend on the session
# cookie (which can be dropped when redirecting across ports localhost:8000 -> localhost:8765).
_pending_states = {}


def get_config():
    """Return (geovault_url, client_id, client_secret, redirect_uri) from session or (None, None, None, default)."""
    url = (session.get("geovault_url") or "").strip().rstrip("/")
    cid = (session.get("client_id") or "").strip()
    secret = (session.get("client_secret") or "").strip()
    redirect_uri = (session.get("redirect_uri") or DEFAULT_REDIRECT_URI).strip()
    return url, cid, secret, redirect_uri


def pkce_code_challenge(verifier: str) -> str:
    digest = hashlib.sha256(verifier.encode("utf-8")).digest()
    return base64.urlsafe_b64encode(digest).decode("utf-8").rstrip("=")


@app.route("/", methods=["GET", "POST"])
def index():
    if request.method == "POST":
        session["geovault_url"] = (request.form.get("geovault_url") or "").strip().rstrip("/")
        session["client_id"] = (request.form.get("client_id") or "").strip()
        session["client_secret"] = (request.form.get("client_secret") or "").strip()
        session["redirect_uri"] = (request.form.get("redirect_uri") or DEFAULT_REDIRECT_URI).strip()
        return redirect(url_for("index"))

    geovault_url, client_id, client_secret, redirect_uri = get_config()

    if not geovault_url or not client_id or not client_secret:
        return """
        <!DOCTYPE html>
        <html><head><title>GeoVault OAuth Demo</title></head><body>
        <h1>GeoVault OAuth Demo</h1>
        <p>Enter your GeoVault server and OAuth application credentials. Get these by
        <a href="#" id="reg-link">registering an application</a> on your GeoVault server (log in first).</p>
        <p>When registering, set <strong>Client type</strong> to <strong>Confidential</strong> and
        <strong>Authorization grant type</strong> to <strong>Authorization code</strong>.</p>
        <form method="post" action="/">
            <p><label>GeoVault server URL <input type="url" name="geovault_url" placeholder="http://localhost:8000" size="40" required></label></p>
            <p><label>Client ID <input type="text" name="client_id" placeholder="your client id" size="40" required></label></p>
            <p><label>Client secret <input type="password" name="client_secret" placeholder="your client secret" size="40" required></label></p>
            <p><label>Redirect URI <input type="url" name="redirect_uri" value="{redirect_uri}" size="50"></label></p>
            <p><button type="submit">Save and continue</button></p>
        </form>
        <script>
        function updateRegLink() {{
            var base = document.querySelector('input[name=geovault_url]').value.trim().replace(/\\/$/, '') || 'http://localhost:8000';
            document.getElementById('reg-link').href = base + '/api/oauth/applications/register/';
        }}
        updateRegLink();
        document.querySelector('input[name=geovault_url]').addEventListener('input', updateRegLink);
        </script>
        </body></html>
        """.format(
            redirect_uri=redirect_uri or DEFAULT_REDIRECT_URI,
        )

    if session.get("access_token"):
        return """
        <!DOCTYPE html>
        <html><head><title>GeoVault OAuth Demo</title></head><body>
        <h1>GeoVault OAuth Demo</h1>
        <p>You are connected. <a href="/data">View your GeoVault data</a> or <a href="/logout">Disconnect</a>.</p>
        <p><a href="/refresh">Refresh token</a> — get a new access token using the refresh token (no re-authorization).</p>
        <p><a href="/config">Change server or credentials</a></p>
        </body></html>
        """
    return """
    <!DOCTYPE html>
    <html><head><title>GeoVault OAuth Demo</title></head><body>
    <h1>GeoVault OAuth Demo</h1>
    <p>This demo shows how a third-party site can access your GeoVault server via OAuth.</p>
    <p><a href="/login">Connect to GeoVault</a></p>
    <p><a href="/config">Change server or credentials</a></p>
    </body></html>
    """


@app.route("/config", methods=["GET", "POST"])
def config():
    if request.method == "POST":
        session["geovault_url"] = (request.form.get("geovault_url") or "").strip().rstrip("/")
        session["client_id"] = (request.form.get("client_id") or "").strip()
        new_secret = (request.form.get("client_secret") or "").strip()
        if new_secret:
            session["client_secret"] = new_secret
        session["redirect_uri"] = (request.form.get("redirect_uri") or DEFAULT_REDIRECT_URI).strip()
        session.pop("access_token", None)
        session.pop("refresh_token", None)
        return redirect(url_for("index"))
    geovault_url, client_id, client_secret, redirect_uri = get_config()
    return """
    <!DOCTYPE html>
    <html><head><title>Configure</title></head><body>
    <h1>Server &amp; credentials</h1>
    <form method="post" action="/config">
        <p><label>GeoVault server URL <input type="url" name="geovault_url" value="{geovault_url}" placeholder="http://localhost:8000" size="40"></label></p>
        <p><label>Client ID <input type="text" name="client_id" value="{client_id}" size="40"></label></p>
        <p><label>Client secret <input type="password" name="client_secret" value="{client_secret}" size="40" placeholder="leave blank to keep current"></label></p>
        <p><label>Redirect URI <input type="url" name="redirect_uri" value="{redirect_uri}" size="50"></label></p>
        <p><button type="submit">Save</button> <a href="/">Cancel</a></p>
    </form>
    </body></html>
    """.format(
        geovault_url=geovault_url or "",
        client_id=client_id or "",
        client_secret="",  # never pre-fill secret
        redirect_uri=redirect_uri or DEFAULT_REDIRECT_URI,
    )


def _cleanup_expired_pending():
    """Remove pending states older than TTL."""
    now = time.time()
    expired = [s for s, d in _pending_states.items() if now - d["created"] > PENDING_STATE_TTL_SECONDS]
    for s in expired:
        del _pending_states[s]


@app.route("/login")
def login():
    geovault_url, client_id, client_secret, redirect_uri = get_config()
    if not geovault_url or not client_id or not client_secret:
        return redirect(url_for("index"))

    code_verifier = secrets.token_urlsafe(32)
    state = secrets.token_urlsafe(16)
    _cleanup_expired_pending()
    _pending_states[state] = {
        "code_verifier": code_verifier,
        "geovault_url": geovault_url,
        "client_id": client_id,
        "client_secret": client_secret,
        "redirect_uri": redirect_uri,
        "created": time.time(),
    }
    code_challenge = pkce_code_challenge(code_verifier)
    params = {
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "scope": SCOPE,
        "state": state,
        "code_challenge": code_challenge,
        "code_challenge_method": "S256",
    }
    authorize_url = f"{geovault_url}/api/oauth/authorize/?{urlencode(params)}"
    return redirect(authorize_url)


@app.route("/callback")
def callback():
    state = request.args.get("state")
    code = request.args.get("code")
    if not state or not code:
        return "Missing state or authorization code.", 400

    pending = _pending_states.pop(state, None)
    if not pending:
        return """
        <!DOCTYPE html>
        <html><head><title>Invalid state</title></head><body>
        <h1>Invalid state parameter</h1>
        <p>The OAuth callback could not verify the request. The authorization may have expired (try again within 10 minutes)
        or the demo server was restarted. Start again from <a href="/">the home page</a> and click Connect.</p>
        <p><a href="/">Back to demo</a></p>
        </body></html>
        """, 400

    if time.time() - pending["created"] > PENDING_STATE_TTL_SECONDS:
        return "Authorization expired. Please start again from the home page.", 400

    geovault_url = pending["geovault_url"]
    client_id = pending["client_id"]
    client_secret = pending["client_secret"]
    redirect_uri = pending["redirect_uri"]
    code_verifier = pending["code_verifier"]
    token_url = f"{geovault_url}/api/oauth/token/"

    data = {
        "grant_type": "authorization_code",
        "code": code,
        "redirect_uri": redirect_uri,
        "client_id": client_id,
        "client_secret": client_secret,
        "code_verifier": code_verifier,
    }

    resp = requests.post(
        token_url,
        data=data,
        headers={"Accept": "application/json"},
        timeout=10,
    )
    if resp.status_code != 200:
        return f"Token exchange failed: {resp.status_code}<pre>{resp.text}</pre>", 400

    token_data = resp.json()
    session["access_token"] = token_data.get("access_token")
    session["refresh_token"] = token_data.get("refresh_token") or ""
    return redirect(url_for("index"))


@app.route("/refresh")
def refresh():
    """Exchange refresh_token for a new access_token and redirect home."""
    refresh_token = (session.get("refresh_token") or "").strip()
    if not refresh_token:
        return (
            "No refresh token in session. Connect again from the home page to get one.",
            400,
        )
    geovault_url, client_id, client_secret, _ = get_config()
    if not geovault_url or not client_id or not client_secret:
        return "Missing server or credentials. Configure in Settings.", 400

    token_url = f"{geovault_url}/api/oauth/token/"
    data = {
        "grant_type": "refresh_token",
        "refresh_token": refresh_token,
        "client_id": client_id,
        "client_secret": client_secret,
    }
    resp = requests.post(
        token_url,
        data=data,
        headers={"Accept": "application/json"},
        timeout=10,
    )
    if resp.status_code != 200:
        return f"Refresh failed: {resp.status_code}<pre>{resp.text}</pre>", 400

    token_data = resp.json()
    session["access_token"] = token_data.get("access_token")
    if token_data.get("refresh_token"):
        session["refresh_token"] = token_data["refresh_token"]
    return redirect(url_for("index"))


@app.route("/data")
def data():
    access_token = session.get("access_token")
    if not access_token:
        return redirect(url_for("index"))

    geovault_url, _, _, _ = get_config()

    status_resp = requests.get(
        f"{geovault_url}/api/user/status/",
        headers={"Authorization": f"Bearer {access_token}"},
        timeout=10,
    )
    features_resp = requests.get(
        f"{geovault_url}/api/features/all/",
        headers={"Authorization": f"Bearer {access_token}"},
        timeout=10,
    )

    status_data = status_resp.json() if status_resp.status_code == 200 else {"error": status_resp.text}
    features_data = features_resp.json() if features_resp.status_code == 200 else {"error": features_resp.text}

    return f"""
    <!DOCTYPE html>
    <html><head><title>Your GeoVault Data</title></head><body>
    <h1>Your GeoVault Data</h1>
    <p><a href="/">Home</a> | <a href="/refresh">Refresh token</a> | <a href="/logout">Disconnect</a></p>
    <h2>User status</h2>
    <pre>{json.dumps(status_data, indent=2)}</pre>
    <h2>Features (all)</h2>
    <pre>{json.dumps(features_data, indent=2)}</pre>
    </body></html>
    """


@app.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("index"))


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=8765, debug=True)
