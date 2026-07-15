<template>
  <div class="flex flex-col flex-1 min-h-0">
    <!-- Search Input -->
    <div class="mb-2 px-1 lg:px-0.5 xl:px-1">
      <div class="relative">
        <input
          v-model="geocodingQuery"
          @input="handleGeocodingInput"
          type="text"
          :placeholder="geocodingAvailable ? 'Search places or coordinates...' : 'Search for coordinates...'"
          class="w-full px-2 py-1.5 pr-7 text-xs border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent lg:px-1.5 lg:py-1 xl:px-2 xl:py-1.5"
        />
        <button
          v-if="geocodingQuery"
          @click="clearGeocodingSearch"
          class="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 focus:outline-none"
          type="button"
          title="Clear Search"
        >
          <XMarkIcon class="w-4 h-4" />
        </button>
      </div>
    </div>

    <!-- Loading Indicator -->
    <div v-if="isGeocodingSearching" class="flex-1 flex items-center justify-center">
      <Loader size="md" layout="centered" message="Searching places..." />
    </div>

    <!-- Results List -->
    <div v-else class="flex flex-col flex-1 min-h-0">
      <!-- Clear Results Button -->
      <div v-if="geocodingResults.length > 0" class="mb-2 px-1">
        <BaseButton
          @click="clearGeocodingSearch"
          class="w-full"
          variant="white"
          size="xs"
          type="button"
          title="Clear Results and Remove Marker"
        >
          Clear Results
        </BaseButton>
      </div>

      <div class="flex-1 select-none min-h-0">
        <div v-if="geocodingResults.length === 0 && !geocodingQuery.trim()" class="text-xs text-gray-500 text-center py-3">
          {{ geocodingAvailable ? 'Enter a place name to search' : 'Enter coordinates' }}
        </div>
        <div v-else-if="geocodingResults.length === 0 && geocodingQuery.trim()" class="text-xs text-gray-500 text-center py-3">
          {{ geocodingAvailable ? 'No results found' : 'Only coordinate search is available.' }}
        </div>
        <RecycleScroller
          v-else
          class="scroller"
          :items="geocodingResultsWithKeys"
          :item-size="48"
          key-field="id"
          v-slot="{ item }"
        >
          <div
            @click="handleGeocodingResultClick(item)"
            class="px-1.5 py-2 bg-gray-50 hover:bg-gray-100 transition-colors cursor-pointer lg:px-1 lg:py-1.5 xl:px-1.5 xl:py-2"
          >
            <div class="text-xs font-medium text-gray-900 truncate">
              {{ getGeocodingResultName(item) }}
            </div>
            <div v-if="getGeocodingResultDescription(item)" class="text-xs text-gray-500 truncate mt-0.5">
              {{ getGeocodingResultDescription(item) }}
            </div>
          </div>
        </RecycleScroller>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { RecycleScroller } from 'vue-virtual-scroller';
import { XMarkIcon } from '@heroicons/vue/24/outline';
import Loader from '@/components/parts/Loader.vue';
import BaseButton from '@/components/parts/BaseButton.vue';
import { parseCoordinates } from '@/utils/geo/coordinates';
import { toastApiError } from '@/utils/apiError';
import { toast } from '@/utils/toast';
import { APIHOST } from '@/config.js';

interface GeocodingResult {
  id?: string;
  type?: string;
  text?: string;
  place_name?: string;
  center?: [number, number];
  coordinates?: [number, number];
  bbox?: [number, number, number, number];
}

interface GeocodingSearchResponse {
  data?: {
    features?: GeocodingResult[];
  };
  error?: string;
}

const props = defineProps<{
  geocodingAvailable: boolean;
}>();

const emit = defineEmits<{
  'result-click': [result: GeocodingResult];
  clear: [];
}>();

const geocodingQuery = ref('');
const geocodingResults = ref<GeocodingResult[]>([]);
const isGeocodingSearching = ref(false);
let geocodingTimeout: ReturnType<typeof setTimeout> | null = null;
let currentSearchQuery = ''; // Tracks the latest search to prevent race conditions.

const geocodingResultsWithKeys = computed(() =>
  geocodingResults.value.map((result, index) => ({
    ...result,
    id: result.id ?? `geocoding-${index}-${result.place_name ?? ''}`,
  })),
);

function handleGeocodingInput() {
  if (geocodingTimeout) {
    clearTimeout(geocodingTimeout);
  }

  const query = geocodingQuery.value.trim();
  if (!query) {
    clearGeocodingSearch();
    return;
  }

  isGeocodingSearching.value = true;
  geocodingTimeout = setTimeout(() => {
    void performGeocodingSearch(query);
  }, 300);
}

async function performGeocodingSearch(query: string) {
  if (!query) {
    clearGeocodingSearch();
    return;
  }

  currentSearchQuery = query;
  isGeocodingSearching.value = true;

  const coordinates = parseCoordinates(query) as { lat: number; lng: number } | null;
  if (coordinates) {
    const coordinateResult: GeocodingResult = {
      type: 'Feature',
      text: `${coordinates.lat.toFixed(6)}, ${coordinates.lng.toFixed(6)}`,
      place_name: `Coordinates: ${coordinates.lat.toFixed(6)}\u00b0, ${coordinates.lng.toFixed(6)}\u00b0`,
      center: [coordinates.lng, coordinates.lat],
      coordinates: [coordinates.lng, coordinates.lat],
      // Small bbox around the point (~1km in each direction; 1km ~= 0.009 deg at the equator).
      bbox: [
        coordinates.lng - 0.009,
        coordinates.lat - 0.009,
        coordinates.lng + 0.009,
        coordinates.lat + 0.009,
      ],
    };

    if (currentSearchQuery === query) {
      geocodingResults.value = [coordinateResult];
      isGeocodingSearching.value = false;
    }
    return;
  }

  if (!props.geocodingAvailable) {
    if (currentSearchQuery === query) {
      geocodingResults.value = [];
      isGeocodingSearching.value = false;
    }
    return;
  }

  try {
    const url = `${APIHOST}/api/geocoding/search/?q=${encodeURIComponent(query)}`;
    const response = await fetch(url);
    const data = (await response.json()) as GeocodingSearchResponse;

    if (currentSearchQuery !== query) {
      return; // This response is stale, ignore it.
    }

    if (response.ok && data.data?.features) {
      geocodingResults.value = data.data.features;
    } else {
      console.error('Forward reverse_geocoding search failed:', data.error || 'Unknown error');
      toast.error(data.error || 'Place search failed');
      if (currentSearchQuery === query) {
        geocodingResults.value = [];
      }
    }
  } catch (error) {
    console.error('Error searching places:', error);
    toastApiError(error, 'Place search failed');
    if (currentSearchQuery === query) {
      geocodingResults.value = [];
    }
  } finally {
    if (currentSearchQuery === query) {
      isGeocodingSearching.value = false;
    }
  }
}

function clearGeocodingSearch() {
  geocodingQuery.value = '';
  geocodingResults.value = [];
  isGeocodingSearching.value = false;
  currentSearchQuery = '';
  if (geocodingTimeout) {
    clearTimeout(geocodingTimeout);
    geocodingTimeout = null;
  }
  emit('clear');
}

function getGeocodingResultName(result: GeocodingResult): string {
  return result.text || result.place_name || 'Unknown place';
}

function getGeocodingResultDescription(result: GeocodingResult): string | null {
  return result.place_name ?? null;
}

function handleGeocodingResultClick(result: GeocodingResult) {
  emit('result-click', result);
}
</script>

<style scoped>
.scroller {
  height: 100%;
}
</style>
