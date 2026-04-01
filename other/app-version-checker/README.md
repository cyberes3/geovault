# App Version Checker

Cloudflare Worker that scans configured Gitea release repositories for GeoVault Android APKs, caches Gitea API
responses, and exposes a small HTTP API and a dashboard. Used by the Android apps to check for new releases, avoiding
having to perform the multi-HTTP call to the Gitea API themselves.

Android apps are hard-coded to use `gitea.evulid.cc`.

## Setup

```bash
npm install
npm run build
cp wrangler.example.toml wrangler.toml
npx wrangler secret put GITEA_USER_AGENT
npx wrangler deploy
```

## HTTP API

| Method | Path                 | Content-Type       | Description                                                                                         |
|--------|----------------------|--------------------|-----------------------------------------------------------------------------------------------------|
| GET    | `/` or `/index.html` | `text/html`        | Dashboard (fetches `latest` relative to the current URL so it works under `ROUTE_PREFIX`).          |
| GET    | `/latest`            | `application/json` | Cached catalog: `scannedAt`, `repos[]`, `apps[]`. With `ROUTE_PREFIX=/foo`, call `GET /foo/latest`. |
| POST   | `/check`             | `application/json` | With `ROUTE_PREFIX=/foo`, call `POST /foo/check`.                                                   |

### `GET /latest`

Returns JSON:

- `scannedAt` — ISO timestamp.
- `repos` — configured release repos as `owner/repo`.
- `apps` — array of objects:
    - `appName`, `versionLabel`, `assetName`, `latestApkUrl`, `releasePageUrl`, `releaseTag`
    - `releasesRepo`, `codeRepo` (derived: `geovault-app-release` → `owner/geovault`; survey repo maps to itself)
    - `releaseCommitSha` — full 40-char SHA when resolution succeeds
    - `error` — string when resolution or upstream failed for that row

### `POST /check`

**Headers:** `Content-Type: application/json`. If `CHECK_SECRET` is configured, also
`Authorization: Bearer <CHECK_SECRET>`.

**Body:**

```json
{
  "appName": "GeoVault Live Tracker",
  "localFullCommitSha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "releasesRepo": "cyberes/geovault-app-release"
}
```

- `appName` — required; must match the display name prefix in the APK filename (same as each app’s `EXPECTED_APP_NAME`
  in Android).
- `localFullCommitSha` — required; full 40-character lowercase hex SHA (`BuildConfig.GIT_COMMIT_SHA`).
- `releasesRepo` — optional; if omitted, the worker searches configured repos in order. If provided, must be exactly one
  of the configured release repos (`owner/repo`).

**Success (200):** JSON includes:

- `isLatest` — `true` if the installed build is up to date, `false` if a newer release commit exists per Gitea compare.
- `latestApkUrl`, `releasePageUrl`, `releaseTag`, `releaseCommitSha`, `localCommitSha`
- `appName`, `versionLabel`, `releasesRepo`, `codeRepo`

**Errors:** JSON body `{ "error": "<code>", "detail": "<message>" }` with appropriate HTTP status (e.g. `400` bad input,
`404` no matching release asset, `401` bad bearer token, `502` upstream failure, `503` missing `GITEA_USER_AGENT`).

### Example

```bash
curl -sS -X POST 'https://<your-worker>.workers.dev/check' \
  -H 'Content-Type: application/json' \
  -d '{"appName":"GeoVault Live Tracker","localFullCommitSha":"0000000000000000000000000000000000000000"}'
```
