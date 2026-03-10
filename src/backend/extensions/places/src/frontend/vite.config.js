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
            '@': fileURLToPath(new URL('./src', import.meta.url))
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
            external: [
                'vue',
                'vue-router',
                'vuex',
                'axios',
                'ol',
                '@heroicons/vue/24/outline',
                '@heroicons/vue/24/solid',
                '@/components/parts/Loader.vue'
            ],
            output: {
                globals: {
                    vue: 'Vue',
                    'vue-router': 'VueRouter',
                    vuex: 'Vuex',
                    axios: 'axios',
                    ol: 'ol',
                    '@heroicons/vue/24/outline': 'HeroiconsOutline',
                    '@heroicons/vue/24/solid': 'HeroiconsSolid',
                    '@/components/parts/Loader.vue': 'Loader'
                },
                extend: true
            }
        },
        outDir: 'dist',
        emptyOutDir: true
    }
})
