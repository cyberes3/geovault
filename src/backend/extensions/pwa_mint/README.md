# PWA Minting Extension

This extension provides a backend service to "mint" (generate) a standalone Android APK for GeoVault using Trusted Web
Activities. This allows a native-like experience (App Drawer icon, full-screen without address bar) on devices
like GrapheneOS where standard Chrome WebAPK generation is disabled.

When you "Install" a PWA in standard Google Chrome, Google's servers "mint" a WebAPK for you. However, to protect your
privacy and remove Google dependencies, GrapheneOS and its browser Vanadium disables this service.

Without a WebAPK, you are limited to a "Homescreen Shortcut".

This extension solves this by letting you self-host the minting process. It's really silly and way over-engineered but I
was bored one night.

## Build Docker Image

1. `git clone https://github.com/pwa-builder/PWABuilder.git`
2. `cd PWABuilder/apps/pwabuilder-google-play/`
3. `npm install`. You may have to do `npm install typescript -g` too.
4. `npm run docker:build`
5. Go back to `geovault/src/backend/extensions/pwa_mint` and run `docker compose up -d`

## How it works

On first startup, the extension generates a secure PKCS12 keystore in `backend/data/pwa_mint/` (`keystore.p12` and
`info.json`). This is used to sign your APKs.

**Do not delete your signing key.** Keep `backend/data/pwa_mint/` backed up. If you delete the keystore, the extension
will generate a new one on next APK build. Consequences: the new APK will have a different certificate, so the SHA256
fingerprint in `/.well-known/assetlinks.json` will no longer match, TWA verification will fail, the browser address bar
will appear in the app, and existing installs will see the new build as a different app.

A background worker thread automatically:

- Checks on startup if the APK is missing or older than 1 day and regenerates it
- Regenerates the APK every 24 hours to keep it fresh
- Auto-restarts if it crashes, with proper error logging

When you trigger generation (manually or via the worker), GeoVault sends your site manifest and your private signing key
to the local PWABuilder container. The container builds the APK and sends it back.

**NOTE:** a background worker is used for the generation and really needs to be moved to Celery when that's added.

## Usage

If the extension is enabled, a small button will appear on the dashboard in the bottom of
the `Android Apps` section titled `Compiled Webview APK`. Tap it to download the APK.

On first startup or if the APK is being regenerated, you may see a "generating" message.
APK generation takes 2-5 minutes on first run but only about 30 seconds subsequently. The background worker handles this automatically.

Manual API endpoints (authenticated):

- Download APK: `GET /api/extensions/pwa-mint/download/`
- Admin force regeneration: `POST /api/extensions/pwa-mint/admin/force-regenerate/` (staff or superuser only). May time
  out due to builds taking around 100 seconds.

  ```bash
  curl -X POST https://YOUR_DOMAIN/api/extensions/pwa-mint/admin/force-regenerate/ \
    -H "Authorization: Bearer YOUR_API_KEY" \
    -H "Content-Type: application/json"
  ```

The APK is cached in `backend/data/pwa_mint/` (same directory as the keystore) so it persists across restarts and is on
the mounted volume when running in Docker. The worker regenerates it every 24 hours.

## Package Name

The app will be generated with the package name: `com.geovault.webview.[your-domain]`
Example: `com.geovault.webview.geovault_example_com`
