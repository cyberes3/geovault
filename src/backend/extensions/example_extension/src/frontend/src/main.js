import ExamplePage from './ExamplePage.vue';
import ExampleSettings from './ExampleSettings.vue';
import './assets/main.css';

/**
 * ==============================================================================
 * Extension Frontend Setup
 * ==============================================================================
 * This 'setup' function is the main entry point for your frontend extension.
 * The platform calls this function and injects several core services:
 * 
 * @param {Object} app      - The main Vue 3 application instance.
 * @param {Object} router   - A scoped router for adding extension-specific paths.
 * @param {Object} store    - The global Vuex store instance.
 * @param {Object} registry - The UI registry for adding nav links and tabs.
 * @param {Object} api      - A helper for generating scoped API URLs.
 */
export async function setup({ app, router, store, registry, api }) {

    // 1. Provide Context
    // We use provide/inject so any sub-component in this extension 
    // can easily generate API URLs without needing the 'api' object passed down as a prop.
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
    registry.registerSettingsTab({
        id: 'example-extension',
        label: 'Example Extension',
        component: ExampleSettings
    });

    // 4. Register Routes
    // Maps URL paths to your Vue components.
    // Like nav links, these are automatically scoped under /extensions/example-extension/
    router.addRoute({
        path: '/page',
        component: ExamplePage
    });
}
