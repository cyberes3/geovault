# GeoVault Development Notes

## Android app releases (dashboard links)

The dashboard shows download links for the latest **Uploader**, **Places**, and **Tracker** APKs by calling the Gitea
releases API.
It fetches `/api/apps/releases/` (JSON with `uploader_url`, `places_url`, `tracker_url`, `releases_page_url`). App card
links point to `/api/apps/download/<name>/` (e.g. `/api/apps/download/uploader/`), which 302-redirects to the real APK
URL (or to the releases page if that app has no URL). Release URLs are **hardcoded** to `git.evulid.cc` (not
configurable). To have new releases picked up automatically, use the repo and naming below.

**Repo:** [geovault-app-release](https://git.evulid.cc/cyberes/geovault-app-release/releases)

**Asset naming (per release):**

| App      | Rule for APK filename                                                                                                                  |
|----------|----------------------------------------------------------------------------------------------------------------------------------------|
| Uploader | Name must **start with** `GeoVault-Uploader` and **end with** `.apk`. Example: `GeoVault-Uploader-2026-01-02-0168732762.apk`           |
| Places   | Name must **start with** `GeoVault-Places` and **end with** `.apk`. Example: `GeoVault-Places-2026-02-12-abc123def0.apk`               |
| Tracker  | Name must **start with** `GeoVault-Live-Tracker` and **end with** `.apk`. Example: `GeoVault-Live-Tracker-2026-03-15-453b77a645.apk`   |

Only the **latest** release (by Gitea’s order) is used. After publishing a new release, the dashboard will show the new
APK links within the cache window (30 minutes) or on the next request after cache expiry.
