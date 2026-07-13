# Building a GeoVault Extension

This guide gives a high-level overview of how to build your own extension. Use the **`example_extension`** in this folder as the reference implementation; **caltopo**, **exif_geotagger**, **live_track**, and **places** show other patterns (hooks, tools, settings, full-height map routes).

## What an extension is

Extensions add backend (Django) and/or frontend (Vue.js) features to GeoVault. A few things the platform handles for you:

- **Scoping** — Your API paths and frontend routes are prefixed (e.g. `/api/extensions/my-extension/`, `/extensions/my-extension/`) so they don't clash with others. The prefix is your extension's `name` with underscores turned into dashes.
- **Settings** — Configuration lives in the user's profile under a dedicated JSON section (`extensions.<name>.*`). No migrations for new settings.
- **Custom tables** — You can define Django models and run your own migrations; tables are namespaced to your extension.
- **Shared UI and platform state** — You use the same Vue app, router, and core UI components as the main app, plus a narrow, read-mostly view of user settings/identity (`platformState`) — never the raw Vuex store.
- **Isolation** — Each extension route is wrapped in an error boundary and a scoped CSS class, so a crash or style leak in one extension can't take down core or another extension.

## Project layout

```
my_extension/
├── manifest.py              # name, version, description, icon, enabled_by_default, map_route, public_share_route
└── src/
    ├── backend/             # Django
    │   ├── apps.py          # ExtensionAppConfig + extension_ready()
    │   ├── models.py        # optional
    │   ├── urls.py          # your API routes (auto-prefixed)
    │   └── views.py
    └── frontend/            # Vue + Vite
        ├── src/
        │   ├── main.js      # must export default setup
        │   └── ...          # components, assets
        ├── vite.config.js   # thin wrapper around the shared factory
        ├── eslint.config.js # thin wrapper around the shared factory
        ├── tsconfig.json     # extends the shared extension base
        └── package.json
```

## Backend

### Manifest (`manifest.py`)

The platform discovers extensions by scanning for `manifest.py`. Required: `name` (snake_case), `version`. Optional: `description`, `icon` (Heroicon name, `"icon.svg"`, or inline SVG), `enabled_by_default`, `map_route` (use the full-height map layout for this extension's routes — see `live_track`), `public_share_route` (treat `/extensions/<kebab-name>/share` as an unauthenticated share route — see `live_track`).

### API routes (`urls.py`)

Paths you define are prefixed with `/api/extensions/<kebab-name>/` (underscores in `name` become dashes). Example: for `name = "my_extension"`, `path('items/', views.item_list)` → `/api/extensions/my-extension/items/`.

### AppConfig and `extension_ready()` (`apps.py`)

Create an `apps.py` that subclasses `ExtensionAppConfig` and set `name`, `label`, `verbose_name`. Implement **`extension_ready()`** — it runs after Django is up and is where you should:

- Register hooks (e.g. import hooks)
- Validate config or run startup checks

Do not rely on DB or start threads here; use signals or Celery for background work.

### Models

Define models as usual; set `app_label` to your extension's label. Run migrations from your extension app (e.g. `python manage.py migrate` with the app in `INSTALLED_APPS` via the extension loader).

### Views

Normal Django views. Use `@api_or_login_required_401()` (from `website.auth_decorators`) for auth. Use `api.utils.responses.success_response` / `error_response` and `api.utils.authorization.get_object_or_404_for_user` when working with platform features (e.g. `FeatureStore`). The example_extension views show simple CRUD and feature create/modify/delete.

## Frontend

### Setup function (`main.js`)

Your frontend entry **must** export a single **default** async function named `setup`:

```javascript
async function setup({ app, router, mainRouter, registry, api, platformState, utils, toast, metadata }) {
  // register routes, nav, tools, settings; call api; etc.
}
export default setup;
```

The platform calls it and passes:

- **`app`** — the Vue 3 app instance (rarely needed; prefer `platformState`/`api` over `app.provide()`).
- **`router`** — a scoped router: `addRoute(route)`, `navigate(path)`; paths are prefixed under `/extensions/<kebab-name>/`.
- **`mainRouter`** — the real, unscoped `vue-router` instance, for the rare case you need to navigate outside your own prefix (e.g. deep-linking into `/map`).
- **`registry`** — `registerNavLink(link)`, `registerTool(tool)`, `registerSettingsTab(tab)`. Use **nav links** for main nav; use **registerTool** for items in the Tools dropdown (e.g. exif_geotagger). There is no `registerRoutes` — call `router.addRoute()` directly for each route.
- **`api`** — an `ExtensionApi` instance: `get/post/put/patch/delete(url, data?)` with CSRF and URL scoping (baseURL `/api/extensions/<kebab-name>`). Use `api.toastError(error, fallback)` for user-facing errors, or `api.handleError(error, fallback)` for inline error UI.
- **`platformState`** — the **only** way to read or write user settings/identity. See below.
- **`utils`** — stateless helpers: `updateUserSetting(update)`, `loadSettingsFromValues(config, settings)`, `keyValueToNested(key, value)`, `getNestedValue(obj, key)`, `parseCoordinates`, `looksLikeCoordinates`, `validateCoordinates`, `searchGeocoding`, `getGeocodingResultCoordinates`, `getGeocodingResultLabel`, `listUsers`.
- **`toast`** — `toast.success()`, `toast.error()`, `toast.info()`, `toast.warning()`.
- **`metadata`** — `name`, `version`, `kebabName`, `icon` (already resolved to a Vue component, or `null`).

### `platformState`: reading/writing user settings and identity

Extensions used to receive the raw Vuex `store`, which gave commit/dispatch access to every core module even though extensions only ever needed `userSettings`. That's gone. Instead you get a narrow bridge:

```typescript
interface PlatformStateBridge {
  readonly userSettings: ComputedRef<Record<string, unknown> | null>;
  readonly currentUser: ComputedRef<{ id: number; email: string; ... } | null>;
  fetchUserSettings(): Promise<void>;
  saveUserSetting(update: Record<string, unknown>): Promise<Record<string, unknown>>;
}
```

- Read settings reactively via `platformState.userSettings.value` (combine with `utils.loadSettingsFromValues(config, platformState.userSettings.value)` to fill in defaults).
- Read the signed-in user via `platformState.currentUser.value` (email, id, etc.), or `null` if signed out.
- Save a setting with `await platformState.saveUserSetting(keyValueToNested('extensions.my_extension.foo', value))` — this persists to the server **and** syncs the shared cache in one call, so every component reading `userSettings` updates automatically.

`platformState` is only available inside components that were wrapped with `createRouteWrapper` (see below) — it's provided per-route, not app-wide.

### Calling your API

Use the injected `api` so the platform can add CSRF and scope URLs:

```javascript
const res = await api.get('/items/');
await api.post('/items/', { name: 'New' });
// On error: api.toastError(err, 'Failed to save item');
```

### Wrapping route and settings-tab components

Every route component and settings-tab component must be wrapped with **`window.gv_core.createRouteWrapper`**:

```javascript
const createRouteWrapper = window.gv_core.createRouteWrapper;
const wrap = (component) => createRouteWrapper(component, { api, router, platformState });

registry.registerSettingsTab({ id: 'my-extension', label: 'My Extension', component: wrap(MySettings) });
router.addRoute({ path: '/page', name: 'my-extension-page', component: wrap(MyPage) });
```

This gives the wrapped component tree:

- Its own scoped `inject('extensionApi')` / `inject('extensionRouter')` / `inject('platformState')` / `inject('mainRouter')` (pass `mainRouter` in the options object too, if the component needs it) — scoped per-route so multiple extensions never clobber each other's provides.
- An **error boundary**: an uncaught error in the wrapped subtree is contained to that extension (shows a small error message) instead of crashing the whole app.
- A stable **`.gv-ext-<kebab-name>`** CSS scoping class on the wrapper root, so your extension's styles can be scoped without leaking into core or other extensions.

In the wrapped component: `const api = inject('extensionApi'); const platformState = inject('platformState');`.

### Settings tab

Register a tab with `registry.registerSettingsTab({ id, label, component, icon })`. In the component, use `utils.loadSettingsFromValues(config, platformState.userSettings.value)` and `platformState.saveUserSetting(keyValueToNested(key, value))` with keys like `extensions.my_extension.setting_key`. See `example_extension`'s **ExampleSettings.vue**.

### Shared platform APIs (`window.gv_core`)

All shared platform resources live on **`window.gv_core`** only. Use `window.gv_core.*` in your extension code; do not use Vue provide/inject for platform-wide singletons, and **never** import from `platform/utils/...` (see "Self-containment" below) — there is no raw store on `window.gv_core` either.

- **`window.gv_core.GeoVault`** — `{ registry, utils, toast, platformState }` (the same `utils`/`toast`/`platformState`/`registry` passed into `setup()`, for use from components that weren't given them directly)
- **`window.gv_core.createRouteWrapper`** — see above
- **`window.gv_core.Vue`**, **`VueRouter`**, **`Vuex`**, **`axios`** — Vue ecosystem (externalized so extensions share core's single instance)
- **`window.gv_core.resolveHeroiconByName(name)`** — resolves an *outline* heroicon by name, lazily. Rejects (doesn't silently return `null`) for a name that isn't a real outline heroicon. Heroicons itself is *not* externalized/shared (see below) - this is purely a convenience for the rare case where you need to look up an icon by a runtime string rather than a static `import { XIcon } from '@heroicons/vue/24/outline'`.
- **`window.gv_core.ol`** — OpenLayers (`source`, `layer`, `proj`, `geom`, `style`, `interaction`, `Feature`). Loaded lazily: `null` until you call and `await` **`window.gv_core.loadOl()`**, which resolves to (and also populates) this value.
- **`window.gv_core.maplibre`** — MapLibre GL JS. Loaded lazily: `null` until you call and `await` **`window.gv_core.loadMaplibreGl()`**, which resolves to (and also populates) this value.
- **`window.gv_core.tileSourceCatalog`** — shared, cached basemap/tile-source catalog singleton (`.load()`)
- **`window.gv_core.RasterTileUrls`**, **`OSM_TILE_SOURCE_ID`** — raster tile URL helpers
- **`window.gv_core.openLayersBasemap`** — shared OpenLayers basemap singleton
- **`window.gv_core.geolocationManager`** — shared geolocation singleton (`getCurrentPosition()`, `startTracking(onUpdate, onError)`, `stopTracking()`)
- **`window.gv_core.isValidMapLngLatPair`**, **`createUserLocationMarker`**, **`updateUserLocationMarker`**, **`removeUserLocationMarker`** — MapLibre map/marker helpers
- **`window.gv_core.setupCopyMapCoordinatesOnContextMenu(map)`** — right-click-to-copy-coordinates behavior for a MapLibre map
- **`window.gv_core.useDocumentTitle`** — composable for setting the browser tab title
- **`window.gv_core.realtimeSocket`**, **`WebSocketHeartbeat`** — the multiplexed `/ws/realtime/` connection and the ping/pong zombie-connection detector
- **`window.gv_core.BaseButton`**, **`BaseModal`**, **`Loader`**, **`LocationIcon`**, **`ScrollingSelect`**, **`SearchableCheckboxList`**, **`ToggleButton`**, **`SettingsInput`** — shared UI components (also globally registered, so you can use them in templates without importing)

The Vue-ecosystem values are also exposed at top level (`window.Vue`, `window.axios`, etc.) purely so UMD builds that externalize these dependencies keep working. `window.ol`/`window.maplibregl` are similarly exposed at top level, but (like their `gv_core` counterparts) only after `loadOl()`/`loadMaplibreGl()` has resolved at least once. Prefer `window.gv_core.*` in your source.

**Heroicons is intentionally not shared/externalized.** Add `@heroicons/vue` as your own dependency (`npm install @heroicons/vue`) and import icons the normal way (`import { MapIcon } from '@heroicons/vue/24/outline'`) - Vite tree-shakes your build down to only the icons you actually use, so there's no meaningful duplication even if another extension imports the same icon. This is the one exception to "core provides it as a shared global": eagerly loading the entire ~391KB icon library on every page load just so it could be shared wasn't worth it for a handful of nav icons.

### Self-containment: no `platform/utils/...` imports, no hardcoded extensions in core

Two rules keep extensions and core decoupled:

1. **Extensions must not import from `platform/utils/...`, `@/utils/...`, etc.** Anything an extension needs from core must come through `window.gv_core`. The extension `vite.config.js` factory (below) only resolves `platform/components/...` (shared UI components) and `platform/assets/css/...` (design-token CSS) as aliases — importing anything else under `platform/` is a build error by design. This matters because importing a core module directly bundles a **second, stale copy** of it into your extension instead of sharing core's live singleton/instance (e.g. two independent geolocation watchers, two independent tile-source caches). If you need something from core that isn't on `window.gv_core` yet, that's a signal it should be added there rather than imported directly.
2. **Core must never special-case a specific extension by name.** Extension names, IDs, or behavior must not appear in core source. If you find core code referencing an extension name directly, that's a bug — file it or fix it, don't build around it.

### Vite config

Use the shared factory instead of hand-rolling externals/aliases — this is what keeps all extensions building against the exact same list of core globals instead of drifting independently:

```javascript
import { fileURLToPath } from 'node:url'
import { createExtensionViteConfig } from '../../../../../frontend/vite.extension-shared.mjs'

export default createExtensionViteConfig({
  extensionDir: fileURLToPath(new URL('.', import.meta.url)),
  name: 'MyExtension', // UMD global name for the built bundle
  extraExternals: { 'piexifjs': 'piexif' } // only for deps NOT already provided by core
})
```

This builds your extension as a UMD library, externalizes Vue/Vue Router/Vuex/axios/MapLibre/OpenLayers/shared UI parts (so you never bundle a second copy), and aliases `@` to your own `src/`. Heroicons is deliberately *not* externalized - see above.

### Lint and type-check config

Extensions extend the same shared ESLint/TypeScript configs core uses, so the whole platform lints and type-checks consistently:

```javascript
// eslint.config.js
import { fileURLToPath } from 'url'
import { dirname } from 'path'
import { createSharedEslintConfig } from '../../../../../frontend/eslint.shared-config.mjs'

export default createSharedEslintConfig({ tsconfigRootDir: dirname(fileURLToPath(import.meta.url)) })
```

```json
// tsconfig.json
{
  "extends": "../../../../../frontend/tsconfig.extension-base.json",
  "compilerOptions": { "baseUrl": ".", "paths": { "@/*": ["src/*"] } },
  "include": ["src/**/*.ts", "src/**/*.tsx", "src/**/*.vue", "src/**/*.js"]
}
```

Add matching `lint` / `lint:check` / `type-check` scripts to `package.json` (see any extension's `package.json` for the exact scripts/devDependencies). Extensions are still primarily plain JS today — that's fine; `checkJs` is off, so `vue-tsc` won't flood you with errors on `.js` files, but the tooling is ready for when you (or core) migrate a file to TypeScript.

## Hooks (backend)

You can plug into platform lifecycle via hooks. Register them **only** inside `extension_ready()`:

```python
from website.extensions.extension_hooks import register_hook

def extension_ready(self):
    register_hook('import', 'my_handler', self.on_import)

def on_import(self, import_item, user_id, created_features):
    # import_item: ImportQueue; created_features: list of FeatureStore
    pass
```

Hook IDs are prefixed with your extension name. **Import** hooks run after a successful import; use them to update extension tables or trigger follow-up work (see caltopo's `handle_import`).

## Quick reference

| Topic | Where to look |
|-------|----------------|
| Manifest options | `example_extension/manifest.py`, `live_track/manifest.py` (map_route, public_share_route) |
| Backend URLs, views, models | `example_extension/src/backend/` |
| AppConfig + import hook | `example_extension/src/backend/apps.py`, `caltopo/src/backend/apps.py` |
| Frontend setup, nav, settings, API, platformState | `example_extension/src/frontend/src/main.js`, `ExampleSettings.vue` |
| Tool instead of nav link | `exif_geotagger/src/frontend/src/main.js` |
| Composables + throttled map updates (larger extension) | `live_track/src/frontend/src/useLiveTrackMap.js`, `useLiveTrackSocket.js` |
| Vite config factory | `frontend/vite.extension-shared.mjs`, any extension's `vite.config.js` |
| Shared ESLint/tsconfig factories | `frontend/eslint.shared-config.mjs`, `frontend/tsconfig.extension-base.json` |

## Troubleshooting

- **Extension not loading** — Check `extensions.<name>.enabled` in config; ensure `manifest.py` has `name` and `version` and `src/backend/` exists.
- **"No valid setup function"** — Use `export default setup` (default export); build must output a format the platform can load (UMD, via the shared Vite factory).
- **`inject('platformState')` / `inject('extensionApi')` is `undefined`** — The component wasn't wrapped with `createRouteWrapper`, or `platformState`/`api` wasn't passed in the options object to `wrap()`.
- **Hook error "outside of extension context"** — Register hooks only inside `extension_ready()` on an `ExtensionAppConfig` subclass.
- **API 403 / wrong URL** — Use the injected `api` (e.g. `api.get('/path/')`); don't build extension API URLs by hand. Ensure the route exists in your backend `urls.py`. Remember the URL prefix is kebab-case (`my-extension`), even though the Python `name` is snake_case (`my_extension`).
- **Settings not saving** — Use keys starting with `extensions.<name>.` and `platformState.saveUserSetting(...)`; ensure the user is authenticated.
- **Duplicate/stale singleton behavior (e.g. two location watchers, two tile-source fetches)** — You imported a core module directly instead of using its `window.gv_core` equivalent. Check your `vite.config.js` build output for warnings about unresolved `platform/...` imports — only `platform/components/...` and `platform/assets/css/...` resolve; everything else must come from `window.gv_core`.

For more detail, read the code in **example_extension** and the other extensions in this directory.
