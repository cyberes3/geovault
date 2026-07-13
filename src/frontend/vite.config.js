import { fileURLToPath, URL } from 'node:url'
import path from 'path'
import fs from 'node:fs'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Plugin to trigger full reload when an extension's frontend dist changes (dev only)
const extensionReloadPlugin = () => {
    const frontendDir = fileURLToPath(new URL('.', import.meta.url))
    const extensionsDir = path.resolve(frontendDir, '../backend/extensions')
    return {
        name: 'extension-reload',
        configureServer(server) {
            if (!fs.existsSync(extensionsDir)) return
            const dirs = fs.readdirSync(extensionsDir, { withFileTypes: true })
            for (const d of dirs) {
                if (!d.isDirectory()) continue
                const distPath = path.join(extensionsDir, d.name, 'src', 'frontend', 'dist')
                if (fs.existsSync(distPath)) {
                    server.watcher.add(distPath)
                }
            }
            server.watcher.on('change', (file) => {
                if (file.includes('/extensions/') && file.includes('/src/frontend/dist/')) {
                    server.ws.send({ type: 'full-reload', path: '*' })
                }
            })
        }
    }
}

// Plugin to replace highlight.js with JSON-only build
// This reduces bundle size from ~970KB to ~30KB
const highlightJsOptimizer = () => {
    const wrapperPath = fileURLToPath(new URL('./src/utils/highlight-json-only.js', import.meta.url))

    return {
        name: 'highlight-js-optimizer',
        enforce: 'pre', // Run before other plugins
        resolveId(id, importer) {
            // Only replace exact 'highlight.js' imports
            if (id === 'highlight.js') {
                // Skip if importing from our wrapper file (allow it to use real highlight.js)
                if (importer && (importer.includes('highlight-json-only.js') || importer.includes('src/utils'))) {
                    return null
                }
                // Replace all other imports (including from simple-code-editor)
                return wrapperPath
            }
            return null
        }
    }
}

// https://vitejs.dev/config/
export default defineConfig({
    define: {
        __SW_VERSION__: JSON.stringify(Date.now().toString())
    },
    plugins: [
        vue(),
        highlightJsOptimizer(),
        extensionReloadPlugin()
    ],
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url))
        }
    },
    build: {
        outDir: 'dist',
        assetsDir: 'static',
        rollupOptions: {
            output: {
                manualChunks: (id) => {
                    // Vite's virtual dynamic-import() helper is needed by every chunk that contains a
                    // `import()` (ours: lazyOl.js/lazyMaplibreGl.js/route-level code-splitting; third
                    // party: geotiff's internal lazy codec loading, etc). Left unassigned, Rollup
                    // placed its single canonical copy in whichever eager chunk it reached first
                    // (core-utils) - which then had to be imported by `vendor` (geotiff needs it) even
                    // though `vendor` is itself a dependency of `vue-vendor`/`core-utils`, producing a
                    // core-utils -> vue-vendor -> vendor -> core-utils circular chunk dependency.
                    // Vendor has no dependents of its own reaching back into core-utils/vue-vendor, so
                    // giving the helper a home there instead breaks the cycle.
                    if (id.includes('vite/preload-helper')) {
                        return 'vendor'
                    }
                    // Pervasive core utilities (used eagerly by the store/App.vue AND by dozens of
                    // unrelated route chunks) must never be left for Rollup to auto-place. Without an
                    // explicit home, Rollup happened to bundle them into whichever heavy, mostly-lazy
                    // chunk first pulled them in transitively (e.g. configService.ts, needed by both
                    // maptilerIntegration.js and App.vue) - which then forced EVERY chunk needing
                    // httpClient/toast/etc. to statically import that heavy chunk too, defeating lazy
                    // loading almost entirely. Keep them in their own small, always-eager chunk instead.
                    if (id.includes('/src/api/httpClient') ||
                        id.includes('/src/utils/cookies') ||
                        id.includes('/src/utils/toast.js') ||
                        id.includes('/src/utils/apiError') ||
                        id.includes('/src/utils/configService') ||
                        // The Vuex store and its direct dependencies (auth, websocket helpers, user
                        // API) are eager (imported by main.js at boot) but have no manualChunks rule
                        // of their own, so Rollup's automatic grouping was merging them into whichever
                        // other eager-but-unrelated bucket shared their reachability set (previously
                        // map-utils, for no reason related to maps at all). Give them an explicit,
                        // dedicated home instead of leaving it to chance.
                        id.includes('/src/assets/js/store/') ||
                        id.includes('/src/assets/js/auth.ts') ||
                        id.includes('/src/assets/js/websocket/') ||
                        id.includes('/src/api/services/userApi')) {
                        return 'core-utils'
                    }
                    // Split MapLibre GL JS into its own chunk
                    if (id.includes('maplibre-gl')) {
                        return 'maplibre-gl'
                    }
                    // Our own utilities that statically import maplibre-gl (map init, feature
                    // rendering, label markers) must live in the SAME chunk as the library itself.
                    // Otherwise they'd drag maplibre-gl into whatever shared chunk they're grouped
                    // into below (map-utils), which many maplibre-gl-free pages also depend on for
                    // things like coordinate parsing - forcing every page to eagerly load the ~1MB
                    // map-rendering library. `locationMarker.js`/`lazyMaplibreGl.js` load MapLibre
                    // lazily via a dynamic import instead, so they stay out of this bucket.
                    if (id.includes('/utils/map/maplibre/') &&
                        !id.includes('/utils/map/maplibre/locationMarker.js') &&
                        !id.includes('/utils/map/maplibre/lazyMaplibreGl.js')) {
                        return 'maplibre-gl'
                    }
                    // Split OpenLayers into its own chunk (for misc maps)
                    if (id.includes('node_modules/ol')) {
                        return 'openlayers'
                    }
                    // Split Chart.js into its own chunk
                    if (id.includes('node_modules/chart.js')) {
                        return 'chart.js'
                    }
                    // Split Turf.js into its own chunk
                    if (id.includes('node_modules/@turf')) {
                        return 'turf'
                    }
                    // Split Vue and Vue ecosystem into vendor chunk
                    if (id.includes('node_modules/vue') ||
                        id.includes('node_modules/vue-router') ||
                        id.includes('node_modules/vuex')) {
                        return 'vue-vendor'
                    }
                    // Split large libraries that may not be needed on all pages
                    if (id.includes('node_modules/moment')) {
                        return 'moment'
                    }
                    if (id.includes('node_modules/highlight.js')) {
                        return 'highlight'
                    }
                    if (id.includes('node_modules/marked')) {
                        return 'marked'
                    }
                    if (id.includes('node_modules/vue-virtual-scroller')) {
                        return 'vue-virtual-scroller'
                    }
                    if (id.includes('node_modules/vue-color')) {
                        return 'vue-color'
                    }
                    if (id.includes('node_modules/simple-code-editor')) {
                        return 'code-editor'
                    }
                    // Split icon library (used throughout but can be cached separately)
                    if (id.includes('node_modules/@heroicons')) {
                        return 'icons'
                    }
                    // Split HTTP client
                    if (id.includes('node_modules/axios')) {
                        return 'axios'
                    }
                    // Split map utilities into their own chunk
                    if (id.includes('/utils/map/')) {
                        return 'map-utils'
                    }
                    // Split shared components
                    if (id.includes('src/components/parts/Loader.vue')) {
                        return 'shared-components'
                    }
                    // Split other node_modules into vendor chunk (smaller utilities)
                    if (id.includes('node_modules')) {
                        return 'vendor'
                    }
                }
            }
        },
        chunkSizeWarningLimit: 1050, // Increase limit to 1MB for map libraries
    },
    server: {
        host: '0.0.0.0',
        fs: {
            allow: ['..', '../backend/extensions']
        },
        watch: {
            // Ignore large directories to improve dev server performance
            // These patterns are relative to the project root (where start-dev.sh runs)
            ignored: [
                '**/node_modules/**',
                '**/dist/**',
                '**/.git/**',
                '**/__pycache__/**',
                '**/venv/**',
                '**/src/backend/assets/icons/caltopo/**',
                '**/src/backend/data/**',
                '**/src/backend/venv/**',
                '**/src/backend/__pycache__/**',
                '**/src/tests/**',
            ],
        },
        proxy: (() => {
            // changeOrigin: true rewrites Host to the target (127.0.0.1:8000), so the backend never sees
            // the original host (e.g. 192.168.1.235:5173). Forward it so build_absolute_uri and profile URLs work.
            function forwardHost(proxy) {
                proxy.on('proxyReq', (proxyReq, req) => {
                    if (req.headers.host) proxyReq.setHeader('X-Forwarded-Host', req.headers.host);
                    const proto = req.headers['x-forwarded-proto'] || (req.socket?.encrypted ? 'https' : 'http');
                    proxyReq.setHeader('X-Forwarded-Proto', proto);
                });
            }
            return {
            '/api': {
                target: 'http://127.0.0.1:8000',
                changeOrigin: true,
                secure: false,
                configure: forwardHost,
            },
            '/accounts': {
                target: 'http://127.0.0.1:8000',
                changeOrigin: true,
                secure: false,
                configure: forwardHost,
            },
            '/static': {
                target: 'http://127.0.0.1:8000',
                changeOrigin: true,
                secure: false,
                configure: forwardHost,
            },
            '/extensions': {
                target: 'http://127.0.0.1:8000',
                changeOrigin: true,
                secure: false,
                configure: forwardHost,
            },
            '/ws': {
                target: 'ws://127.0.0.1:8000',
                ws: true,
                changeOrigin: true,
            },
            };
        })(),
    },
})
