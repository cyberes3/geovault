# GeoVault Development Notes

## Android app releases (dashboard links)

The dashboard shows download links for the latest **Uploader** and **Places** APKs by calling the Gitea releases API.
Release URLs are **hardcoded** to `git.evulid.cc` (not configurable). To have new releases picked up automatically, use
the repo and naming below.

**Repo:** [geovault-app-release](https://git.evulid.cc/cyberes/geovault-app-release/releases)

**Asset naming (per release):**

| App      | Rule for APK filename                                                                                                    |
|----------|--------------------------------------------------------------------------------------------------------------------------|
| Uploader | Name must **contain** `Uploader` and **end with** `.apk`. Example: `GeoVault Uploader 2026-01-02 0168732762.apk`         |
| Places   | Name must **start with** `GeoVault Places ` and **end with** `.apk`. Example: `GeoVault Places 2026-02-12 abc123def.apk` |
| Tracker  | Name must **contain** `GeoVault Live Tracker` and **end with** `.apk`. Example: `GeoVault Live Tracker 2026-03-01.apk`    |

Only the **latest** release (by Gitea’s order) is used. After publishing a new release, the dashboard will show the new
APK links within the cache window (30 minutes) or on the next request after cache expiry.
