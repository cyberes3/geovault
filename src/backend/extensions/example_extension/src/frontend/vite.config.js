import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

/**
 * ==============================================================================
 * Extension Build Configuration
 * ==============================================================================
 * This config builds the extension as a standalone library that can be dynamically
 * loaded by the main GeoVault application.
 */
export default defineConfig({
  plugins: [
    vue(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
      // Allow importing platform utilities if needed (though window.GeoVault is preferred)
      'platform': path.resolve(__dirname, '../../../../../frontend/src')
    }
  },
  server: {
    fs: {
      // Allow Vite dev server to access platform files during development
      allow: ['..', '../../../../../frontend']
    }
  },
  build: {
    // Build as a library (UMD format)
    lib: {
      entry: path.resolve(__dirname, 'src/main.js'),
      name: 'ExampleExtension', // Global variable name for the UMD bundle
      fileName: (format) => `index.${format}.js`,
      cssFileName: 'index',
      formats: ['umd'] // UMD works with dynamic import() and global externals
    },
    rollupOptions: {
      // CRITICAL: Externalize the Vue ecosystem!
      // The platform provides these libraries globally (window.Vue, window.Vuex, etc.).
      // If you bundler them here, the extension will have its own independent Vue instance,
      // breaking reactivity, routing, and store access.
      external: ['vue', 'vue-router', 'vuex', 'axios'],
      output: {
        // Map externalized imports to the global window variables provided by the platform
        globals: {
          vue: 'Vue',
          'vue-router': 'VueRouter',
          vuex: 'Vuex',
          axios: 'axios'
        },
        // Ensure the setup function is appended to the global object rather than overwriting it
        extend: true
      }
    },
    outDir: 'dist',
    emptyOutDir: true
  }
})
