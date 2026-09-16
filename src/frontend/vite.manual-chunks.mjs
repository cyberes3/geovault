/**
 * Rollup manualChunks for the GeoVault frontend production build.
 * @param {string} id
 * @returns {string | undefined}
 */
export function manualChunkName(id) {
    if (id.includes('vite/preload-helper')) {
        return 'vendor';
    }
    if (id.includes('/src/api/httpClient') ||
        id.includes('/src/utils/cookies') ||
        id.includes('/src/utils/toast.js') ||
        id.includes('/src/utils/apiError') ||
        id.includes('/src/utils/configService') ||
        id.includes('/src/assets/js/store/') ||
        id.includes('/src/assets/js/auth.ts') ||
        id.includes('/src/assets/js/websocket/') ||
        id.includes('/src/api/services/userApi')) {
        return 'core-utils';
    }
    if (id.includes('maplibre-gl-worker')) {
        return undefined;
    }
    if (id.includes('node_modules/maplibre-gl') ||
        id.includes('node_modules/@maplibre/')) {
        return 'maplibre-gl';
    }
    if (id.includes('node_modules/chart.js')) {
        return 'chart.js';
    }
    if (id.includes('node_modules/@turf')) {
        return 'turf';
    }
    if (id.includes('node_modules/vue') ||
        id.includes('node_modules/vue-router') ||
        id.includes('node_modules/vuex')) {
        return 'vue-vendor';
    }
    if (id.includes('node_modules/moment')) {
        return 'moment';
    }
    if (id.includes('node_modules/highlight.js')) {
        return 'highlight';
    }
    if (id.includes('node_modules/marked')) {
        return 'marked';
    }
    if (id.includes('node_modules/vue-virtual-scroller')) {
        return 'vue-virtual-scroller';
    }
    if (id.includes('node_modules/vue-color')) {
        return 'vue-color';
    }
    if (id.includes('node_modules/simple-code-editor')) {
        return 'code-editor';
    }
    if (id.includes('node_modules/@heroicons')) {
        return 'icons';
    }
    if (id.includes('node_modules/axios')) {
        return 'axios';
    }
    if (id.includes('/utils/map/')) {
        return 'map-utils';
    }
    if (id.includes('src/components/parts/Loader.vue')) {
        return 'shared-components';
    }
    if (id.includes('node_modules')) {
        return 'vendor';
    }
}
