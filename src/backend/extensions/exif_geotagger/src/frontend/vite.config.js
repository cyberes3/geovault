import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
    plugins: [
        vue(),
    ],
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url))
        }
    },
    build: {
        lib: {
            entry: path.resolve(__dirname, 'src/main.js'),
            name: 'ExifGeotaggerExtension',
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
                'ol/source',
                'ol/layer',
                'ol/proj.js',
                'ol/Feature.js',
                'ol/geom/Point.js',
                'ol/style.js',
                '@heroicons/vue/24/outline'
            ],
            output: {
                globals: {
                    vue: 'Vue',
                    'vue-router': 'VueRouter',
                    vuex: 'Vuex',
                    axios: 'axios',
                    'ol': 'ol',
                    'ol/source': 'ol.source',
                    'ol/layer': 'ol.layer',
                    'ol/proj.js': 'ol.proj',
                    'ol/Feature.js': 'ol.Feature',
                    'ol/geom/Point.js': 'ol.geom.Point',
                    'ol/style.js': 'ol.style',
                    '@heroicons/vue/24/outline': 'HeroiconsOutline'
                },
                extend: true
            }
        },
        outDir: 'dist',
        emptyOutDir: true
    }
})
