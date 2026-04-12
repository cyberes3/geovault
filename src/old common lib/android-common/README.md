# android-common

Shared Android library used by **android-uploader** and **android-places**. It provides:

- **GeovaultAuthManager**: OAuth2 (Authorization Code + PKCE) and token storage for the GeoVault API.
- **RetrofitClient**: Authenticated Retrofit instance with Bearer token and 401 retry.
- **CoordinateParser**: Parses coordinate strings (DD, DMS, DM, and variants) into decimal degrees; returns `Pair<Latitude, Longitude>` or null. Use `CoordinateParser.parse(input)` and `CoordinateParser.looksLikeCoordinates(input)`.
- **Theme and UI**: Base theme, shared styles (buttons, EditText), colors, drawables, and OAuth/settings strings so both apps look consistent.

## Setup

Each app includes this module via a relative path. In the app's `settings.gradle`:

```groovy
include ':android-common'
project(':android-common').projectDir = file("../android-common")
```

In the app's `app/build.gradle` (or `build.gradle.kts`):

```groovy
implementation project(':android-common')
```

## Required: Initialize auth before use

Apps must call **once** before using any auth APIs (e.g. in `Application.onCreate()` or before the first auth flow):

```kotlin
GeovaultAuthManager.init(context, redirectUri = "com.geovault.uploader://oauth/callback")
```

Use the app's own redirect URI (e.g. `com.geovault.places://oauth/callback` for the Places app). If you call other `GeovaultAuthManager` methods before `init()`, the library throws `IllegalStateException`.

## Threading

- **`getValidAccessToken(context)`** and **`refreshAccessToken(...)`** may perform network I/O (token refresh). Call them from a **background thread** when refresh might run; calling from the main thread can trigger StrictMode or ANR.
- Token refresh is serialized so only one thread refreshes at a time.

## Adding this module to a new app

1. Place the app in `src/` next to `android-common` (e.g. `src/my-app`).
2. In the app's `settings.gradle`: `include ':android-common'` and `project(':android-common').projectDir = file("../android-common")`.
3. In the app's app module: `implementation project(':android-common')`.
4. Call `GeovaultAuthManager.init(context, "your.scheme://oauth/callback")` at startup.
5. Optionally set the app theme to parent `gv_common_Theme.GeoVault` and use library styles/drawables for consistency.
