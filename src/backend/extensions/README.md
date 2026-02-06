# GeoVault Extension Developer Guide

This guide and the accompanying `example_extension` example code provides everything you need to build your own features
into the GeoVault platform.

## Architecture Overview

GeoVault extensions contain both backend (Django) and frontend (Vue.js) code.

### Key Concepts

- **Scoping**: All your URLs, tables, and routes are automatically prefixed to prevent collisions.
- **Unified Settings**: Extensions store their configuration in a dedicated JSON section of the user's primary profile.
  No migrations required for new settings.
- **Custom Tables**: Extensions can define their own Django models and generate independent migrations. These tables are
  fully managed by the extension, allowing for complex data storage needs beyond simple settings.
- **Shared UI**: Extensions can use the platform's core components to maintain a premium, consistent look.

---

## Project Structure

```text
my_extension/
├── manifest.py           # Metadata (name, version, description)
└── src/
    ├── backend/          # Django code
    │   ├── models.py     # Database schemas
    │   ├── views.py      # API logic
    │   └── urls.py       # Scoped routing
    └── frontend/         # Vue.js code
        ├── src/
        │   ├── main.js   # Frontend entry point (setup function)
        │   ├── assets/   # Custom CSS/images
        │   └── ...       # Vue components
        └── vite.config.js # Library-mode build config
```

---

## Backend Implementation

### 1. The Manifest

The platform discovers your extension via `manifest.py`.

```python
name = "my_extension"
version = "1.0.0"
description = "Adds revolutionary spatial analysis tools."
```

### 2. API Routing

URLs in `urls.py` are automatically prefixed with `/api/extensions/<name>/`.

```python
urlpatterns = [
    path('data/', views.get_data),  # Accessible at /api/extensions/my_extension/data/
]
```

### 3. AppConfig and Extension Lifecycle

Extensions should create an `apps.py` file that inherits from `ExtensionAppConfig`:

```python
from website.extensions.extension_base import ExtensionAppConfig


class MyExtensionConfig(ExtensionAppConfig):
  default_auto_field = 'django.db.models.BigAutoField'
  name = 'my_extension.src.backend'
  label = 'my_extension'
  verbose_name = 'My Extension'

  def extension_ready(self):
    # Initialize your extension here
    # Register hooks, validate config, etc.
    pass
```

The `extension_ready()` method is called after Django is fully initialized and is the recommended place to:
- Register hooks
- Validate configuration
- Initialize logging
- Perform startup checks

See the [Extension Lifecycle](#extension-lifecycle) section for more details.

### 4. Dynamic Settings

You can store arbitrary data in the user's profile without writing Python code for the database. Simply use the
`extensions` section in the settings API:
`extensions.my_extension.my_key` maps directly to your extension's private JSON storage.

---

## Frontend Implementation

### 1. The Setup Function

Your `main.js` **must** export the setup function as the default export. This is the only supported method.

```javascript
// ✅ CORRECT - Default export
async function setup({ app, router, store, registry, api, utils, toast, metadata }) {
    // Your extension initialization
}
export default setup;

// ❌ INCORRECT - Named export (not supported)
export async function setup() { ... }

// ❌ INCORRECT - Window-based exports are not supported
window.MyExtension = { setup: function() { ... } };
```

**Required Export Format:**
- Must use `export default setup` (the function must be exported as default)
- Must be an ES module (not a script tag or IIFE)
- The function receives an object with all platform services

**Setup Function Parameters:**

- `app` - Vue 3 application instance
- `router` - Enhanced scoped router with navigation helpers:
  - `addRoute(route)` - Register a route (automatically scoped)
  - `navigate(path)` - Navigate to a scoped path
  - `go(n)` - Navigate in history
  - `back()` - Go back
  - `forward()` - Go forward
- `store` - Vuex store instance
- `registry` - Complete UI registry:
  - `registerNavLink(link)` - Add navigation link
  - `registerTool(tool)` - Add tool to the "Tools" dropdown
  - `registerSettingsTab(tab)` - Add settings tab
  - `registerRoutes(routes)` - Register multiple routes
- `api` - ExtensionApi instance (see below)
- `utils` - Utility functions:
  - `updateUserSetting(key, value)` - Save settings
  - `loadSettingsFromStore()` - Load settings from store
  - `keyValueToNested(key, value)` - Format settings
  - `getNestedValue(obj, key)` - Get nested value
- `toast` - Toast notification function
- `metadata` - Extension metadata:
  - `name` - Extension name (snake_case)
  - `version` - Extension version
  - `description` - Extension description
  - `kebabName` - URL-friendly name

### 2. ExtensionApi Class

The `api` parameter is an `ExtensionApi` instance that provides convenient HTTP methods with automatic CSRF token handling:

```javascript
export async function setup({ api, toast, ... }) {
    try {
        // GET request
        const response = await api.get('/items/');
        const items = response.data;
        
        // POST request
        await api.post('/items/', { name: 'New Item' });
        toast.success('Item created!');
        
        // PUT request
        await api.put('/items/1/', { name: 'Updated' });
        
        // PATCH request
        await api.patch('/items/1/', { description: 'New desc' });
        
        // DELETE request
        await api.delete('/items/1/');
        toast.success('Item deleted!');
    } catch (error) {
        // Handle errors explicitly
        const errorInfo = api.handleError(error);
        toast.error(errorInfo.message);
    }
    
    // Build URL manually (if needed)
    const url = api.url('/items/'); // Returns /api/extensions/my-extension/items/
}
```

**Features:**
- Automatic CSRF token handling
- Automatic URL scoping (`/api/extensions/<name>/`)
- Standard axios response format
- `handleError()` method for extracting error information (toasts are up to you)

### 3. Shared Utilities

- **`utils.getNestedValue`**: Get nested value from object

### 4. Shared Libraries & Bundle Optimization

GeoVault provides core libraries (OpenLayers, Heroicons, Vue, etc.) globally to prevent redundant bundling. Using these shared libraries can reduce your bundle size by up to 90%.

**Available Globals:**
- `window.ol` (OpenLayers with source, layer, proj, geom, style namespaces)
- `window.HeroiconsOutline`
- `window.HeroiconsSolid`
- `window.Vue`
- `window.VueRouter`
- `window.axios`

#### Configuring Vite to use Shared Libraries

In your extension's `vite.config.js`, mark these libraries as external and map them to the platform's globals:

```javascript
export default defineConfig({
  build: {
    rollupOptions: {
      external: [
        'vue', 'vue-router', 'vuex', 'axios', 'ol', 
        '@heroicons/vue/24/outline', '@heroicons/vue/24/solid'
      ],
      output: {
        globals: {
          vue: 'Vue',
          'vue-router': 'VueRouter',
          vuex: 'Vuex',
          axios: 'axios',
          ol: 'ol',
          '@heroicons/vue/24/outline': 'HeroiconsOutline'
        }
      }
    }
  }
})
```

#### Best Practices
- Move `ol` and `@heroicons/vue` to `devDependencies` in your `package.json`.
- Use `registry.registerTool()` for auxiliary features to keep the main navigation bar clean.
- The "Tools" dropdown is automatically sorted alphabetically.

**Example:**
```javascript
export async function setup({ utils, toast }) {
    // Save a setting
    await utils.updateUserSetting('extensions.my_extension.my_key', 'value');
    
    // Show notification
    toast.success('Setting saved!');
}
```

---

## Hooks System

Extensions can register hooks to be called at specific points in the platform's lifecycle. Currently supported hook types:

- **`import`** - Called after successful feature imports
- **`processing`** - (Future) Called during file processing
- **`export`** - (Future) Called during feature export

### Registering Hooks

Hooks must be registered in your `extension_ready()` method:

```python
from website.extensions.extension_base import ExtensionAppConfig
from website.extensions.extension_hooks import register_hook


class MyExtensionConfig(ExtensionAppConfig):
  name = 'my_extension.src.backend'
  label = 'my_extension'

  def extension_ready(self):
    # Register an import hook
    register_hook('import', 'my_import_handler', self.handle_import)

  def handle_import(self, import_item, user_id, created_features):
    """
    Called after a successful import.
    
    Args:
        import_item: ImportQueue instance that was imported
        user_id: Integer ID of the user who imported
        created_features: List of FeatureStore instances that were created
    """
    # Process the imported features
    for feature in created_features:
      # Do something with each feature
      pass
```

**Important Notes:**
- Hook IDs are automatically prefixed with your extension name (e.g., `my_extension.my_import_handler`)
- Hooks registered outside of `extension_ready()` will raise an error
- Hook callbacks should not raise exceptions - errors are logged but don't fail the operation
- Multiple hooks of the same type can be registered

### Import Hooks

Import hooks are called after features are successfully imported into the database. They receive:

- `import_item` - The `ImportQueue` object that was imported
- `user_id` - Integer ID of the user who performed the import
- `created_features` - List of `FeatureStore` objects that were created (may be empty)

**Example Use Cases:**
- Update extension-specific tables with imported feature references
- Send notifications about new imports
- Trigger background processing
- Update analytics or statistics

---

## Extension Lifecycle

Extensions follow a specific initialization lifecycle:

### 1. Discovery Phase

During Django startup, the platform:
1. Scans the `extensions/` directory
2. Reads `manifest.py` files
3. Validates extension metadata
4. Checks if extension is enabled in config

### 2. AppConfig Initialization

If enabled, the extension's AppConfig is loaded:

```python
# If you have apps.py:
class MyExtensionConfig(ExtensionAppConfig):
    # Your config

# If you don't have apps.py:
# The platform automatically creates one inheriting from ExtensionAppConfig
```

### 3. ready() Method

Django calls `AppConfig.ready()` after all apps are loaded. The `ExtensionAppConfig` base class:

1. Handles RUN_MAIN check (prevents duplicate initialization in dev mode)
2. Sets extension context for hook registration
3. Calls `extension_ready()` if implemented
4. Clears extension context

### 4. extension_ready() Method

This is where you should perform initialization:

```python
def extension_ready(self):
    # ✅ DO: Register hooks
    register_hook('import', 'my_hook', self.my_callback)
    
    # ✅ DO: Validate configuration
    if not self.validate_config():
        logger.warning("Invalid configuration")
    
    # ✅ DO: Initialize logging
    logger.info("Extension initialized")
    
    # ❌ DON'T: Access database models (may not be ready)
    # ❌ DON'T: Start background threads (use proper Django signals)
    # ❌ DON'T: Make external API calls (use Celery tasks)
```

### 5. Frontend Loading

After the backend is ready, the frontend:
1. Fetches extension metadata from `/api/extensions/`
2. Dynamically imports extension JavaScript bundles
3. Calls the `setup()` function with platform services

---

## Troubleshooting

### Extension Not Loading

**Check:**
1. Extension is enabled in config: `extensions.<name>.enabled = true`
2. `manifest.py` exists and has valid `name` and `version`
3. `src/backend/` directory exists
4. Check server logs for errors during discovery

### Frontend Setup Function Not Found

**Error:** `Extension <name> has no valid setup function`

**Solution:**
- Ensure you're using default export: `export default setup` (where `setup` is your async function)
- Check that your build outputs ES modules (not IIFE or UMD)
- Verify the `frontend_entry` path in extension metadata is correct

### Hook Registration Errors

**Error:** `Cannot register hook outside of extension context`

**Solution:**
- Hooks must be registered in `extension_ready()` method
- Ensure your AppConfig inherits from `ExtensionAppConfig`
- Don't register hooks at module level or in views

### API Requests Failing

**Check:**
1. CSRF token is being sent (ExtensionApi handles this automatically)
2. URL is correctly scoped (use `api.get('/path/')` not full URLs)
3. Backend endpoint exists and is properly registered in `urls.py`
4. Check browser console and network tab for detailed errors

### Extension Not Appearing in Navigation

**Check:**
1. `registry.registerNavLink()` was called in setup function
2. Route was registered with `router.addRoute()`
3. Component is properly imported and exported
4. Check browser console for JavaScript errors

### Settings Not Saving

**Check:**
1. Using `utils.updateUserSetting()` with correct key format
2. Key starts with `extensions.<name>.`
3. User is authenticated
4. Check network requests for API errors

---

## Import Process

Extensions can hook into the import process flow using import hooks (see [Hooks System](#hooks-system) section).

**When Import Hooks Are Called:**
- After features are successfully imported into the database
- Before WebSocket notifications are sent
- After all processing is complete

**Hook Signature:**
```python
def my_import_hook(import_item, user_id, created_features):
    # import_item: ImportQueue instance
    # user_id: Integer user ID
    # created_features: List[FeatureStore] instances
    pass
```

**Future Hook Types:**
- Custom taggers (during processing)
- Background process triggers
- External source importers