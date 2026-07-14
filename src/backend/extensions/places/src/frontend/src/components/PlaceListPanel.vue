<template>
  <div class="w-full sm:w-1/2 min-w-0 min-h-0 sm:flex-1 flex flex-col sm:overflow-hidden bg-white rounded-none sm:rounded-lg shadow-sm border border-gray-200 relative">
    <div
        v-if="loading"
        class="absolute inset-0 z-10 flex flex-col items-center justify-center bg-white/50 pointer-events-auto cursor-wait rounded-none sm:rounded-lg"
        aria-busy="true"
        aria-live="polite"
    >
      <div class="inline-flex bg-white rounded-lg shadow-lg border border-gray-200 px-4 py-3">
        <Loader size="sm" layout="inline" :show-message="true" message="Loading places..."/>
      </div>
    </div>

    <div class="p-4 border-b border-gray-200 flex flex-col sm:flex-row items-stretch sm:items-center gap-2">
      <div class="relative flex-1 min-w-0">
        <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
          <MagnifyingGlassIcon class="h-5 w-5 text-gray-500" aria-hidden="true"/>
        </div>
        <input
            type="text"
            :value="searchQuery"
            class="block w-full pl-10 pr-3 py-2 border border-gray-300 rounded-lg shadow-sm focus:ring-2 focus:ring-blue-500 focus:border-transparent outline-none transition-all sm:text-sm"
            placeholder="Search places..."
            @input="$emit('update:searchQuery', ($event.target as HTMLInputElement).value)"
        />
      </div>
      <select
          id="places-sort"
          :value="sortBy"
          class="select-custom w-full sm:w-auto min-w-0 px-3 py-2 text-sm border border-gray-300 rounded-lg shadow-sm focus:outline-none flex-shrink-0"
          aria-label="Sort Places"
          @change="$emit('update:sortBy', ($event.target as HTMLSelectElement).value)"
      >
        <option value="composite">Default Sort</option>
        <option value="created">Last Created</option>
        <option value="modified">Last Modified</option>
        <option value="navigated">Last Navigated To</option>
      </select>
    </div>

    <div ref="listScrollContainer" class="p-4 sm:flex-1 sm:min-h-0 sm:overflow-y-auto sm:overscroll-contain">
      <div v-if="places.length === 0 && !loading" class="text-center py-12">
        <div class="mx-auto w-12 h-12 text-gray-500 mb-4">
          <MapPinIcon class="w-12 h-12 mx-auto"/>
        </div>
        <h3 class="text-sm font-medium text-gray-900">No places found</h3>
        <p class="mt-1 text-sm text-gray-600">Get started by creating a new place.</p>
      </div>
      <div v-else class="space-y-4">
        <PlaceListItem
            v-for="place in places"
            :key="place.properties.database_id"
            :place="place"
            :is-selected="selectedPlaceId === place.properties.database_id"
            :copied="copiedPlaceId === place.properties.database_id"
            @select="$emit('select', $event)"
            @touch-select="(placeItem, event) => $emit('touch-select', placeItem, event)"
            @hover="$emit('hover', $event)"
            @edit="$emit('edit', $event)"
            @delete="$emit('delete', $event)"
            @open-description="$emit('open-description', $event)"
            @open-maps="$emit('open-maps', $event)"
            @copy-coordinates="$emit('copy-coordinates', $event)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { MagnifyingGlassIcon, MapPinIcon } from '@heroicons/vue/24/outline';
import Loader from 'platform/components/parts/Loader.vue';
import PlaceListItem from '@/components/PlaceListItem.vue';
import type { PlaceFeature } from '@/types/places';

withDefaults(defineProps<{
  places: PlaceFeature[];
  loading?: boolean;
  searchQuery?: string;
  sortBy?: string;
  selectedPlaceId?: number | null;
  copiedPlaceId?: number | null;
}>(), {
  loading: false,
  searchQuery: '',
  sortBy: 'composite',
  selectedPlaceId: null,
  copiedPlaceId: null,
});

defineEmits<{
  'update:searchQuery': [value: string];
  'update:sortBy': [value: string];
  select: [place: PlaceFeature];
  'touch-select': [place: PlaceFeature, event: TouchEvent];
  hover: [placeId: number | null];
  edit: [place: PlaceFeature];
  delete: [place: PlaceFeature];
  'open-description': [place: PlaceFeature];
  'open-maps': [place: PlaceFeature];
  'copy-coordinates': [place: PlaceFeature];
}>();

const listScrollContainer = ref<HTMLElement | null>(null);
defineExpose({ listScrollContainer });
</script>
