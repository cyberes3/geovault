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
async function setup({ app, router, store, registry, api, utils, toast, metadata }) {
    // 1. Provide Context
    // We use provide/inject so any sub-component in this extension 
    // can easily access the API without needing it passed down as a prop.
    app.provide('exampleExtensionApi', api);

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
        component: ExampleSettings,
        icon: metadata.icon  // Icon from manifest (heroicon, SVG file, or inline SVG)
    });

    // 4. Register Routes
    // Maps URL paths to your Vue components.
    // Like nav links, these are automatically scoped under /extensions/example-extension/
    router.addRoute({
        path: '/page',
        component: ExamplePage
    });

    // Example: Using router navigation helpers
    // router.navigate('/page') - Navigate to a scoped path
    // router.back() - Go back in history
    // router.forward() - Go forward in history

    // Example: Using the enhanced API
    // api.get('/items/') - GET request with automatic CSRF token
    // api.post('/items/', { name: 'Test' }) - POST request
    // api.put('/items/1/', { name: 'Updated' }) - PUT request
    // api.delete('/items/1/') - DELETE request
    // All methods automatically handle CSRF tokens, but you must handle errors explicitly
    // Use api.handleError(error) to extract error info and show toasts as needed
}

export default setup
