# GeoVault Extension Developer Guide

Welcome to the GeoVault extension ecosystem! This guide (and the accompanying `example_extension`) provides everything
you need to build your own features into the GeoVault platform.

## 🏗Architecture Overview

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

### 3. Dynamic Settings

You can store arbitrary data in the user's profile without writing Python code for the database. Simply use the
`extensions` section in the settings API:
`extensions.my_extension.my_key` maps directly to your extension's private JSON storage.

---

## Frontend Implementation

### 1. The Setup Function

Your `main.js` must export an `async function setup()`. This is where you plug into the platform.

```javascript
export async function setup({app, router, store, registry, api}) {
    // Register a link in the top nav
    registry.registerNavLink({label: 'My Tool', path: '/my-tool'});

    // Register a settings tab
    registry.registerSettingsTab({
        id: 'my-ext-settings',
        label: 'My Extension',
        component: MySettings
    });

    // Add scoped routes
    router.addRoute({path: '/my-tool', component: MyToolPage});
}
```

### 2. Shared Utilities

Never bundle redundant libraries. Use the `window.GeoVault` bridge:

- **`GeoVault.toast`**: Show success/error notifications.
- **`GeoVault.utils.updateUserSetting`**: Save settings to the cloud.
- **`GeoVault.utils.keyValueToNested`**: Format settings for the API.

### 3. Global Components

These are available everywhere in your extension without importing:

- `<BaseButton>`: Standard themed buttons.
- `<SettingsInput>`: Managed form fields with success feedback.
- `<Loader>`: Brand-compatible progress indicators.
- `<ToggleButton>`: Sleek switches for settings.