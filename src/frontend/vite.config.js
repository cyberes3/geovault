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
                manualChunks: {
                    // Split MapLibre GL JS into its own chunk
                    'maplibre-gl': ['maplibre-gl'],
                    // Split OpenLayers into its own chunk (for the original map)
                    'openlayers': ['ol'],
                }
            }
        },
        chunkSizeWarningLimit: 1000, // Increase limit to 1MB for map libraries
    },
    server: {
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
