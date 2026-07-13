import ExamplePage from './ExamplePage.vue';
import ExampleSettings from './ExampleSettings.vue';
import './assets/main.css';

/**
 * ==============================================================================
 * Extension Frontend Setup
 * ==============================================================================
 * This 'setup' function is the main entry point for your frontend extension.
 * 
 * IMPORTANT: This function MUST be exported as the default export:
 *   async function setup({ ... }) { ... }
 *   export default setup
 * 
 * The platform calls this function and injects several core services:
 * 
 * @type {import('platform/types/geovault').ExtensionSetup}
 */
async function setup({ router, registry, api, platformState, metadata }) {
    // 1. Wrap Components
    // `createRouteWrapper` gives every route/settings-tab component its own scoped `inject`
    // (api via 'extensionApi', router via 'extensionRouter', platformState via 'platformState'),
    // an error boundary that contains a crash to this extension instead of taking down the whole
    // app, and a `.gv-ext-<name>` CSS scoping class. Always wrap route and settings-tab
    // components with it.
    const createRouteWrapper = window.gv_core.createRouteWrapper;
    const wrap = (component) => createRouteWrapper(component, { api, router, platformState });

    // 2. Register Navigation Link
    // Adds a link to the main top-level navigation bar.
    // Paths are relative to the extension's root (/extensions/<name>/)
    registry.registerNavLink({
        label: 'Example Ext',
        path: '/page'
    });

    // 3. Register User Settings Tab
    // Adds a custom section to the 'User Settings' page.
    // The icon is automatically resolved from metadata.icon if specified in manifest.py
    registry.registerSettingsTab({
        id: 'example-extension',
        label: 'Example Extension',
        component: wrap(ExampleSettings),
        icon: metadata.icon  // Icon from manifest (heroicon, SVG file, or inline SVG)
    });

    // 4. Register Routes
    // Maps URL paths to your Vue components.
    // Like nav links, these are automatically scoped under /extensions/example-extension/
    router.addRoute({
        path: '/page',
        name: 'example-extension-page',
        meta: { title: 'Example Extension' },
        component: wrap(ExamplePage)
    });

    // Example: Using the enhanced API
    // api.get('/items/') - GET request with automatic CSRF token
    // api.post('/items/', { name: 'Test' }) - POST request
    // On error: api.toastError(error, 'Failed to save item')
}

export default setup
