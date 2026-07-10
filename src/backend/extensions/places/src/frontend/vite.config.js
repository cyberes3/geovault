import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

function exitOnBuildError() {
    return {
        name: 'exit-on-build-error',
        buildEnd(err) {
            if (err) {
                console.error('\n[Extension build failed]', err.message || err);
                if (err.stack) console.error(err.stack);
                process.exit(1);
            }
        },
    };
}

export default defineConfig({
    plugins: [
        vue(),
        exitOnBuildError(),
    ],
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
            'platform': path.resolve(__dirname, '../../../../../frontend/src')
        }
    },
    build: {
        minify: process.env.GEOVAULT_EXTENSION_DEV ? false : 'esbuild',
        sourcemap: !!process.env.GEOVAULT_EXTENSION_DEV,
        lib: {
            entry: path.resolve(__dirname, 'src/main.js'),
            name: 'PlacesExtension',
            fileName: (format) => `index.${format}.js`,
            cssFileName: 'index',
            formats: ['umd']
        },
        rollupOptions: {
            external: (id) => {
                if (['vue', 'vue-router', 'vuex', 'axios', 'maplibre-gl'].includes(id)) {
                    return true;
                }
                if (id.startsWith('@heroicons/vue')) {
                    return true;
                }
                if (id.startsWith('platform/components/parts/')) {
                    return true;
                }
                return false;
            },
            output: {
                globals: (id) => {
                    const baseGlobals = {
                        vue: 'Vue',
                        'vue-router': 'VueRouter',
                        vuex: 'Vuex',
                        axios: 'axios',
                        'maplibre-gl': 'maplibregl',
                        '@heroicons/vue/24/outline': 'HeroiconsOutline',
                        '@heroicons/vue/24/solid': 'HeroiconsSolid'
                    };
                    if (baseGlobals[id]) {
                        return baseGlobals[id];
                    }
                    const sharedPartGlobals = {
                        'platform/components/parts/BaseButton.vue': 'BaseButton',
                        'platform/components/parts/BaseModal.vue': 'BaseModal',
                        'platform/components/parts/Loader.vue': 'Loader',
                        'platform/components/parts/SettingsInput.vue': 'SettingsInput',
                    };
                    return sharedPartGlobals[id];
                },
                extend: true
            }
        },
        outDir: 'dist',
        emptyOutDir: true
    }
})
