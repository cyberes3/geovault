# GeoVault OAuth Demo Server

A minimal standalone server that demonstrates how a **third-party website** can access your GeoVault server using OAuth 2.0 (authorization code flow with PKCE). It uses GeoVault’s **manual application registration**: you register the app once in the GeoVault UI, then enter the client ID and secret in this demo’s web form.

## Prerequisites

- Python 3.10+
- A running GeoVault server (e.g. `http://localhost:8000`)

## 1. Register an OAuth application (manual registration)

1. Log in to your GeoVault server in the browser.
2. Open the **application registration** page:
   - **URL:** `{GEOVAULT_URL}/api/oauth/applications/register/`
   - Example: `http://localhost:8000/api/oauth/applications/register/`
3. Create a new application with these settings:
   - **Name:** e.g. "OAuth Demo"
   - **Client type:** **Confidential** (this demo stores a client secret server-side)
   - **Authorization grant type:** **Authorization code** (required for the code + PKCE flow this demo uses)
   - **Redirect URIs:** `http://localhost:8765/callback` (one per line if you add more)
4. Save. Copy the **Client ID** and **Client secret** (you may only see the secret once).

## 2. Install and run the demo

```bash
cd src/examples/oauth-demo-server
python3 -m venv venv
./venv/bin/pip install -r requirements.txt
./venv/bin/flask --app app run --port 8765
```

Open **http://localhost:8765** in your browser.

## 3. Configure and use the demo

1. On first load you’ll see a form. Enter:
   - **GeoVault server URL** (e.g. `http://localhost:8000`)
   - **Client ID** and **Client secret** from the application you registered
   - **Redirect URI** (default `http://localhost:8765/callback`; must match the value in GeoVault)
2. Click **Save and continue**.
3. Click **Connect to GeoVault**. You’re redirected to GeoVault to log in (if needed) and authorize the app.
4. After authorizing, you’re sent back to the demo. Use **View your GeoVault data** to see your user status and features fetched with the access token.

Credentials are stored in the session only (no .env or config file). Use **Change server or credentials** to update them or switch to another GeoVault server.

If you see **"Invalid state parameter"** after authorizing, the authorization may have expired (pending states last 10 minutes) or the demo server was restarted (the in-memory store is cleared). Start again from the demo home page and click Connect. The callback does not rely on session cookies, so redirecting from GeoVault (e.g. port 8000) back to the demo (port 8765) should work even when the browser does not send the session cookie.
