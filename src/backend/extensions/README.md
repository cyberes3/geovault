# Building a GeoVault Extension

This guide gives a high-level overview of how to build your own extension. Use the **`example_extension`** in this folder as the reference implementation; **caltopo** and **exif_geotagger** show other patterns (hooks, tools, settings).

## What an extension is

Extensions add backend (Django) and/or frontend (Vue.js) features to GeoVault. A few things the platform handles for you:

- **Scoping** — Your API paths and frontend routes are prefixed (e.g. `/api/extensions/my_extension/`, `/extensions/my-extension/`) so they don’t clash with others.
- **Settings** — Configuration lives in the user’s profile under a dedicated JSON section (`extensions.<name>.*`). No migrations for new settings.
- **Custom tables** — You can define Django models and run your own migrations; tables are namespaced to your extension.
- **Shared UI** — You use the same Vue app, router, store, and core components as the main app.

## Project layout

```
my_extension/
├── manifest.py              # name, version, description, icon, enabled_by_default
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
        └── vite.config.js  # library build + externals
```

## Backend

### Manifest (`manifest.py`)

The platform discovers extensions by scanning for `manifest.py`. Required: `name` (snake_case), `version`. Optional: `description`, `icon` (Heroicon name, `"icon.svg"`, or inline SVG), `enabled_by_default`.

### API routes (`urls.py`)

Paths you define are prefixed with `/api/extensions/<name>/`. Example: `path('items/', views.item_list)` → `/api/extensions/my_extension/items/`.

### AppConfig and `extension_ready()` (`apps.py`)

Create an `apps.py` that subclasses `ExtensionAppConfig` and set `name`, `label`, `verbose_name`. Implement **`extension_ready()`** — it runs after Django is up and is where you should:

- Register hooks (e.g. import hooks)
- Validate config or run startup checks

Do not rely on DB or start threads here; use signals or Celery for background work.

### Models

Define models as usual; set `app_label` to your extension’s label. Run migrations from your extension app (e.g. `python manage.py migrate` with the app in `INSTALLED_APPS` via the extension loader).

### Views

Normal Django views. Use `@api_or_login_required_401()` (from `geo_lib.website.auth`) for auth. Use `api.utils.responses.success_response` / `error_response` and `api.utils.authorization.get_object_or_404_for_user` when working with platform features (e.g. `FeatureStore`). The example_extension views show simple CRUD and feature create/modify/delete.

## Frontend

### Setup function (`main.js`)

Your frontend entry **must** export a single **default** async function named `setup`:

```javascript
async function setup({ app, router, store, registry, api, utils, toast, metadata }) {
  // register routes, nav, tools, settings; call api; etc.
}
export default setup;
```

The platform calls it and passes:

- **`app`** — Vue 3 app (e.g. for `app.provide()`).
- **`router`** — `addRoute(route)`, `navigate(path)`; paths are scoped under `/extensions/<kebab-name>/`.
- **`store`** — Vuex store.
- **`registry`** — `registerNavLink(link)`, `registerTool(tool)`, `registerSettingsTab(tab)`, `registerRoutes(routes)`. Use **nav links** for main nav; use **registerTool** for items in the Tools dropdown (e.g. exif_geotagger).
- **`api`** — `ExtensionApi`: `get/post/put/patch/delete(url, data?)` with CSRF and URL scoping. Use `api.toastError(error, fallback)` for user-facing errors, or `api.handleError(error)` for inline error UI.
- **`utils`** — `updateUserSetting(key, value)`, `loadSettingsFromStore()`, `keyValueToNested`, `getNestedValue`. Settings keys must start with `extensions.<name>.`.
- **`toast`** — `toast.success()`, `toast.error()`, etc.
- **`metadata`** — `name`, `version`, `description`, `kebabName`, `icon`.

### Calling your API

Use the injected `api` so the platform can add CSRF and scope URLs:

```javascript
const res = await api.get('/items/');
await api.post('/items/', { name: 'New' });
// On error: api.toastError(err, 'Failed to save item');
```

### Settings tab

Register a tab with `registry.registerSettingsTab({ id, label, component, icon })`. In the component, use `utils.loadSettingsFromStore(config, store)` and `utils.updateUserSetting(key, value)` with keys like `extensions.my_extension.setting_key`. The example_extension’s **ExampleSettings.vue** uses `SettingsInput` and the shared `keyValueToNested` / store pattern.

### Shared platform APIs (`window.gv_core`)

All shared platform resources live on **`window.gv_core`** only. Use `window.gv_core.*` in your extension code; do not use Vue provide/inject for platform store, toast, or utils.

- **`window.gv_core.GeoVault`** — `registry`, `utils` (e.g. `updateUserSetting`, `loadSettingsFromStore`, `keyValueToNested`, `getNestedValue`, `getCurrentPosition`, `checkGeolocationPermission`, `parseCoordinates`, `looksLikeCoordinates`, `validateCoordinates`), `toast`
- **`window.gv_core.store`** — Vuex store (set after the app mounts)
- **`window.gv_core.Vue`**, **`window.gv_core.VueRouter`**, **`window.gv_core.Vuex`**, **`window.gv_core.axios`** — Vue ecosystem
- **`window.gv_core.HeroiconsOutline`**, **`window.gv_core.HeroiconsSolid`** — Heroicons
- **`window.gv_core.ol`** — OpenLayers (map, source, layer, proj, geom, style, interaction, Feature)
- **`window.gv_core.maplibre`** — MapLibre GL JS (for map components)
- **`window.gv_core.createRouteWrapper`** — helper to wrap a route component so `extensionApi` (and optional `extensionRouter`) are provided per-route; use for `router.addRoute()` so child components can `inject('extensionApi')` without overwriting app-level provide (see below)
- **`window.gv_core.Loader`** — shared Loader component

The same values are also exposed at top level (`window.Vue`, `window.ol`, etc.) so UMD builds that externalize these dependencies keep working. Prefer `window.gv_core.*` in your source.

### Registering routes with extensionApi

If your route components use `inject('extensionApi')` (or `inject('extensionRouter')`), wrap the component with **`gv_core.createRouteWrapper`** so each extension gets its own provide and Vue’s render function works correctly (runtime-only build):

```javascript
const createRouteWrapper = window.gv_core?.createRouteWrapper;
router.addRoute({
  path: '',
  component: createRouteWrapper ? createRouteWrapper(MyView, { api }) : MyView
});
// With router: createRouteWrapper(MyView, { api, router })
```

See **live_track** and **places** extensions for examples.

### Vite and shared libraries

Build as a library (see `example_extension`’s `vite.config.js`). Mark Vue, Vue Router, Vuex, axios (and if you use them: `ol`, `@heroicons/vue/24/outline`, `@heroicons/vue/24/solid`) as **externals** and map them to the global names the platform provides at top level (`Vue`, `VueRouter`, `Vuex`, `axios`, etc.). Otherwise you get duplicate instances and broken reactivity. You can put `ol` and Heroicons in devDependencies.

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

Hook IDs are prefixed with your extension name. **Import** hooks run after a successful import; use them to update extension tables or trigger follow-up work (see caltopo’s `handle_import`).

## Quick reference

| Topic | Where to look |
|-------|----------------|
| Manifest options | `example_extension/manifest.py`, `caltopo/manifest.py` |
| Backend URLs, views, models | `example_extension/src/backend/` |
| AppConfig + import hook | `example_extension/src/backend/apps.py`, `caltopo/src/backend/apps.py` |
| Frontend setup, nav, settings, API | `example_extension/src/frontend/src/main.js`, ExampleSettings.vue |
| Tool instead of nav link | `exif_geotagger/src/frontend/src/main.js` |
| Vite library + externals | `example_extension/src/frontend/vite.config.js` |

## Troubleshooting

- **Extension not loading** — Check `extensions.<name>.enabled` in config; ensure `manifest.py` has `name` and `version` and `src/backend/` exists.
- **“No valid setup function”** — Use `export default setup` (default export); build must output a format the platform can load (e.g. UMD with externals).
- **Hook error “outside of extension context”** — Register hooks only inside `extension_ready()` on an `ExtensionAppConfig` subclass.
- **API 403 / wrong URL** — Use the injected `api` (e.g. `api.get('/path/')`); don’t build extension API URLs by hand. Ensure the route exists in your backend `urls.py`.
- **Settings not saving** — Use keys starting with `extensions.<name>.` and `utils.updateUserSetting`; ensure the user is authenticated and the backend profile/settings API is used.

For more detail, read the code in **example_extension** and the other extensions in this directory.
