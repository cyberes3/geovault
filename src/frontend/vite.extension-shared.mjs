/**
 * Shared Vite build config factory for extension frontends, so all 5 extensions build against
 * the exact same externalization/global-mapping list as core instead of drifting independently.
 * An extension's own `vite.config.js` should just be:
 *
 *   import { fileURLToPath } from 'node:url'
 *   import { createExtensionViteConfig } from '../../../../../frontend/vite.extension-shared.mjs'
 *
 *   export default createExtensionViteConfig({
 *     extensionDir: fileURLToPath(new URL('.', import.meta.url)),
 *     name: 'MyExtension',
 *     extraExternals: { 'piexifjs': 'piexif' } // only for deps NOT already provided by core
 *   })
 */
import path from 'path'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Everything core puts on `window` (see `src/frontend/src/main.js`). Declaring the full list as
 * external on every extension (even ones that never import a given package) is harmless - Rollup
 * only externalizes imports that are actually present - and it means no extension can silently
 * drift from what core actually provides.
 */
const CORE_GLOBALS = {
    vue: 'Vue',
    'vue-router': 'VueRouter',
    vuex: 'Vuex',
    axios: 'axios',
    'maplibre-gl': 'maplibregl',
    ol: 'ol',
    'ol/source': 'ol.source',
    'ol/layer': 'ol.layer',
    'ol/proj': 'ol.proj',
    'ol/proj.js': 'ol.proj',
    'ol/geom': 'ol.geom',
    'ol/geom/Point.js': 'ol.geom.Point',
    'ol/style': 'ol.style',
    'ol/style.js': 'ol.style',
    'ol/interaction': 'ol.interaction',
    'ol/Feature': 'ol.Feature',
    'ol/Feature.js': 'ol.Feature'
    // Heroicons is deliberately NOT externalized: core only needs it for a handful of nav icons
    // (loaded lazily by name, see resolveExtensionIcon.ts), so eagerly loading the entire ~391KB
    // library on every page just to share it as a global was pure waste. Extensions add
    // `@heroicons/vue` as their own dependency and import icons by name as usual - Vite tree-shakes
    // each extension bundle down to only the icons it actually uses.
}

/** Shared UI parts core exposes on `window.gv_core`/top-level globals - see main.js. */
const SHARED_PART_GLOBALS = {
    'platform/components/parts/BaseButton.vue': 'BaseButton',
    'platform/components/parts/BaseModal.vue': 'BaseModal',
    'platform/components/parts/Loader.vue': 'Loader',
    'platform/components/parts/LocationIcon.vue': 'LocationIcon',
    'platform/components/parts/ScrollingSelect.vue': 'ScrollingSelect',
    'platform/components/parts/SearchableCheckboxList.vue': 'SearchableCheckboxList',
    'platform/components/parts/ToggleButton.vue': 'ToggleButton',
    'platform/components/settings/components/SettingsInput.vue': 'SettingsInput'
}

const ALL_GLOBALS = { ...CORE_GLOBALS, ...SHARED_PART_GLOBALS }

function exitOnBuildError() {
    return {
        name: 'exit-on-build-error',
        buildEnd(err) {
            if (err) {
                console.error('\n[Extension build failed]', err.message || err)
                if (err.stack) console.error(err.stack)
                process.exit(1)
            }
        }
    }
}

/**
 * @param {object} options
 * @param {string} options.extensionDir - absolute path to the extension's frontend root (pass
 *   `fileURLToPath(new URL('.', import.meta.url))` from the extension's own vite.config.js).
 * @param {string} options.name - UMD global variable name for the built bundle, e.g. 'LiveTrackExtension'.
 * @param {Record<string, string>} [options.extraExternals] - additional id -> global-name pairs
 *   for dependencies this specific extension needs external that core doesn't already provide.
 *   Only use this for genuinely extension-specific runtime deps; everything core provides is
 *   already covered by `CORE_GLOBALS`/`SHARED_PART_GLOBALS` above.
 */
export function createExtensionViteConfig({ extensionDir, name, extraExternals = {} }) {
    const globals = { ...ALL_GLOBALS, ...extraExternals }
    const externalIds = Object.keys(globals)
    const platformRoot = path.resolve(extensionDir, '../../../../../frontend/src')

    return defineConfig({
        plugins: [vue(), exitOnBuildError()],
        resolve: {
            alias: [
                { find: '@', replacement: path.resolve(extensionDir, 'src') },
                // `platform/components/...` (externalized shared-part globals above) and
                // `platform/assets/css/...` (design-token CSS variables, safe to inline - unlike
                // JS there's no stale-singleton risk) only, deliberately. Anything under
                // `platform/utils/...` etc. must come from `window.gv_core` instead, or the
                // extension bundles its own stale copy instead of sharing core's live
                // singleton/instance; only resolving these two subpaths enforces that at build
                // time rather than by convention.
                { find: /^platform\/(components\/.*|assets\/css\/.*)$/, replacement: path.resolve(platformRoot, '$1') }
            ]
        },
        server: {
            fs: {
                allow: ['..', platformRoot]
            }
        },
        build: {
            minify: process.env.GEOVAULT_EXTENSION_DEV ? false : 'esbuild',
            sourcemap: !!process.env.GEOVAULT_EXTENSION_DEV,
            lib: {
                entry: path.resolve(extensionDir, 'src/main.js'),
                name,
                fileName: (format) => `index.${format}.js`,
                cssFileName: 'index',
                formats: ['umd']
            },
            rollupOptions: {
                external: (id) => externalIds.includes(id) || id.startsWith('platform/components/'),
                output: {
                    globals: (id) => globals[id],
                    extend: true
                }
            },
            outDir: 'dist',
            emptyOutDir: true
        }
    })
}
