import { fileURLToPath, URL } from 'node:url'
import path from 'path'
import fs from 'node:fs'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { manualChunkName } from './vite.manual-chunks.mjs'

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
                manualChunks: manualChunkName
            }
        },
        chunkSizeWarningLimit: 1050, // Increase limit to 1MB for map libraries
    },
    worker: {
        format: 'es',
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
            '/share': {
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
