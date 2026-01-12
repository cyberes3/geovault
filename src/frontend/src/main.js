import './assets/css/main.css'

import { createApp } from 'vue'
import App from './App.vue'
import store from "@/assets/js/store.ts";
import router from "@/router.js";
import '@/assets/css/root.css'
import 'simple-code-editor/themes/themes.css'
import 'simple-code-editor/themes/themes-base16.css'

import { extensionRegistry } from './utils/extensionRegistry.js';
import axios from 'axios';

const app = createApp(App);

/**
 * Dynamically load and setup extensions.
 */
async function loadExtensions() {
    try {
        const response = await axios.get('/api/extensions/');
        const extensions = response.data;

        for (const ext of extensions) {
            if (ext.frontend_entry) {
                try {
                    // Dynamic ES module import
                    const extension = await import(/* @vite-ignore */ ext.frontend_entry);

                    // Scoped router wrapper to enforce /extensions/<name>/
                    const scopedRouter = {
                        addRoute: (route) => {
                            if (!route.path.startsWith(`/extensions/${ext.name}/`)) {
                                console.warn(`Extension ${ext.name} tried to register out-of-scope route: ${route.path}`);
                                return;
                            }
                            router.addRoute(route);
                        }
                    };

                    if (typeof extension.setup === 'function') {
                        await extension.setup({
                            app,
                            router: scopedRouter,
                            store,
                            registry: extensionRegistry
                        });
                    }
                } catch (err) {
                    console.error(`Failed to load extension module ${ext.name}:`, err);
                }
            }
        }
    } catch (err) {
        console.error('Failed to fetch extensions metadata:', err);
    }
}

// Start app after loading extensions
loadExtensions().then(() => {
    app.use(router)
        .use(store)
        .mount('#app');
});
