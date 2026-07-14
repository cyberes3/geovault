<template>
  <div class="p-4">
    <label for="live-track-layer-select" class="block text-sm font-medium text-gray-700 mb-2">Layer</label>
    <select
      id="live-track-layer-select"
      :value="selectedLayer"
      class="w-full text-sm border border-gray-300 rounded-md px-2.5 py-1.5 bg-white text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
      title="Map Layer"
      @change="$emit('update:selectedLayer', ($event.target as HTMLSelectElement).value)"
    >
      <option v-for="source in tileSources" :key="source.id" :value="source.id">
        {{ source.name }}
      </option>
    </select>
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue';
import type { TileSource } from './types/gv-core';

export default defineComponent({
  name: 'MapLayerSidebar',
  props: {
    tileSources: { type: Array as PropType<TileSource[]>, default: () => [] },
    selectedLayer: { type: String, default: 'osm' },
  },
  emits: ['update:selectedLayer'],
});
</script>
