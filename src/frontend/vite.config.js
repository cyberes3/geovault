import {fileURLToPath, URL} from 'node:url'

import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
export default defineConfig({
    plugins: [vue()],
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
                    // Split MapLibre GL JS into its own chunk
                    if (id.includes('maplibre-gl')) {
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
        proxy: {
            '/api': {
                target: 'http://127.0.0.1:8000',
                changeOrigin: true,
                secure: false,
            },
            '/accounts': {
                target: 'http://127.0.0.1:8000',
                changeOrigin: true,
                secure: false,
            },
            '/static': {
                target: 'http://127.0.0.1:8000',
                changeOrigin: true,
                secure: false,
            },
            '/ws': {
                target: 'ws://127.0.0.1:8000',
                ws: true,
                changeOrigin: true,
            },
        },
    },
})
