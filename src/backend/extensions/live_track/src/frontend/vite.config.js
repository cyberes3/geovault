import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

/**
 * Exit with code 1 on build error so start-dev.sh --kill-others-on-fail stops all processes.
 * (Vite watch mode otherwise keeps running after build errors.)
 */
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
    exitOnBuildError(),
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
    // In dev (start-dev.sh sets GEOVAULT_EXTENSION_DEV=1): no minify + sourcemaps for debuggable stack traces
    minify: process.env.GEOVAULT_EXTENSION_DEV ? false : 'esbuild',
    sourcemap: !!process.env.GEOVAULT_EXTENSION_DEV,
    // Build as a library (UMD format)
    lib: {
      entry: path.resolve(__dirname, 'src/main.js'),
      name: 'LiveTrackExtension', // Global variable name for the UMD bundle
      fileName: (format) => `index.${format}.js`,
      cssFileName: 'index',
      formats: ['umd'] // UMD works with dynamic import() and global externals
    },
    rollupOptions: {
      // CRITICAL: Externalize the shared ecosystem provided by the platform globals.
      external: (id) => {
        if (['vue', 'vue-router', 'vuex', 'axios', 'maplibre-gl'].includes(id)) {
          return true;
        }
        if (id.startsWith('@heroicons/vue')) {
          return true;
        }
        // Shared part components are provided by core globals (window.gv_core + top-level aliases).
        if (id.startsWith('platform/components/parts/')) {
          return true;
        }
        return false;
      },
      output: {
        // Map externalized imports to the global window variables provided by the platform
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
            'platform/components/parts/LocationIcon.vue': 'LocationIcon',
            'platform/components/parts/ScrollingSelect.vue': 'ScrollingSelect',
            'platform/components/parts/SearchableCheckboxList.vue': 'SearchableCheckboxList',
            'platform/components/parts/ToggleButton.vue': 'ToggleButton'
          };
          return sharedPartGlobals[id];
        },
        // Ensure the setup function is appended to the global object rather than overwriting it
        extend: true
      }
    },
    outDir: 'dist',
    emptyOutDir: true
  }
})
