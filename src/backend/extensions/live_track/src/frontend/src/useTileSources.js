/**
 * Composable for tile sources and selected layer state.
 * Used by LiveTrackView and WorldShareView. Loads from core's shared `tileSourceCatalog`
 * singleton (via `window.gv_core`) instead of a raw fetch, so live_track shares the same
 * cache/in-flight request and error handling as the rest of the app.
 *
 * @param {{ defaultSource?: Object, afterFetch?: (tileSourcesRef: import('vue').Ref, selectedLayerRef: import('vue').Ref) => void }} options
 * @returns {{ tileSources: import('vue').Ref<Object[]>, selectedLayer: import('vue').Ref<string>, fetchTileSources: () => Promise<void> }}
 */
import { ref } from 'vue';
import { defaultOsmSource } from './mapTileUtils.js';

export function useTileSources({ defaultSource = defaultOsmSource, afterFetch } = {}) {
  const tileSources = ref([defaultSource]);
  const selectedLayer = ref(defaultSource.id);

  async function fetchTileSources() {
    try {
      const sources = await window.gv_core.tileSourceCatalog.load();
      tileSources.value = sources.length > 0 ? sources : [defaultSource];
      if (typeof afterFetch === 'function') {
        afterFetch(tileSources, selectedLayer);
      }
      if (!tileSources.value.some((s) => s.id === selectedLayer.value)) {
        selectedLayer.value = tileSources.value[0]?.id || defaultSource.id;
      }
    } catch (e) {
      console.warn('useTileSources: fetch failed', e);
      tileSources.value = [defaultSource];
      selectedLayer.value = defaultSource.id;
    }
  }

  return { tileSources, selectedLayer, fetchTileSources };
}
