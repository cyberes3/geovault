/**
 * Composable for tile sources and selected layer state.
 * Used by LiveTrackView and WorldShareView.
 *
 * @param {{ apiUrl: string, defaultSource: Object, afterFetch?: (tileSourcesRef: import('vue').Ref, selectedLayerRef: import('vue').Ref) => void }} options
 * @returns {{ tileSources: import('vue').Ref<Object[]>, selectedLayer: import('vue').Ref<string>, fetchTileSources: () => Promise<void> }}
 */
import { ref } from 'vue';
import { defaultOsmSource } from './mapTileUtils.js';

export function useTileSources({ apiUrl = '/api/tiles/sources/', defaultSource = defaultOsmSource, afterFetch } = {}) {
  const tileSources = ref([defaultSource]);
  const selectedLayer = ref('osm');

  async function fetchTileSources() {
    try {
      const response = await fetch(apiUrl);
      const data = await response.json();
      if (data.sources && Array.isArray(data.sources)) {
        tileSources.value = data.sources.filter((s) => !s.hidden);
      }
      if (tileSources.value.length === 0) {
        tileSources.value = [defaultSource];
      }
      if (typeof afterFetch === 'function') {
        afterFetch(tileSources, selectedLayer);
      }
      if (!tileSources.value.some((s) => s.id === selectedLayer.value)) {
        selectedLayer.value = tileSources.value[0]?.id || 'osm';
      }
    } catch (e) {
      console.warn('useTileSources: fetch failed', e);
      tileSources.value = [defaultSource];
      selectedLayer.value = 'osm';
    }
  }

  return { tileSources, selectedLayer, fetchTileSources };
}
