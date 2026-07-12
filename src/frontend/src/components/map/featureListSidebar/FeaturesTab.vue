<template>
  <div class="flex flex-col flex-1 min-h-0">
    <!-- Search Bar -->
    <div class="mb-2 px-1 lg:px-0.5 xl:px-1">
      <div class="relative">
        <input
          v-model="searchQuery"
          @input="handleSearchInput"
          type="text"
          placeholder="Search features..."
          class="w-full px-2 py-1.5 pr-7 text-xs border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent lg:px-1.5 lg:py-1 xl:px-2 xl:py-1.5"
        />
        <button
          v-if="searchQuery"
          @click="clearSearch"
          class="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 focus:outline-none"
          type="button"
          title="Clear Search"
        >
          <XMarkIcon class="w-4 h-4" />
        </button>
      </div>
    </div>

    <!-- Header -->
    <h2 class="text-xs font-semibold text-gray-900 mb-1 px-1">
      {{ isSearchMode ? 'Search Results' : '' }}
    </h2>

    <!-- Initial Loading Indicator -->
    <div v-if="showInitialFeaturesLoader" class="flex-1 flex items-center justify-center">
      <Loader size="md" layout="centered" message="Loading features..." />
    </div>

    <!-- Loading Indicator for Search -->
    <div v-else-if="isSearching" class="flex-1 flex items-center justify-center">
      <Loader size="md" layout="centered" message="Searching..." />
    </div>

    <!-- Feature List -->
    <div v-else class="flex-1 select-none min-h-0">
      <div v-if="displayFeatures.length === 0" class="text-xs text-gray-500 text-center py-3">
        {{ isSearchMode ? 'No results found' : 'No features' }}
      </div>
      <RecycleScroller
        v-else
        class="scroller"
        :items="displayFeaturesWithKeys"
        :item-size="32"
        key-field="database_id"
        v-slot="{ item }"
      >
        <div
          @click="handleFeatureClick(item)"
          @contextmenu.prevent="handleFeatureContextMenu(item)"
          class="px-1.5 py-1.5 bg-gray-50 hover:bg-gray-100 transition-colors flex items-center cursor-pointer lg:px-1 lg:py-1 xl:px-1.5 xl:py-1.5"
          :style="{ borderLeft: `3px solid ${featureRowVisualsById.get(String(item.database_id))?.color ?? DEFAULT_GEOMETRY_COLOR}` }"
        >
          <div class="flex-1 min-w-0 flex items-center gap-1.5">
            <div class="text-xs text-gray-900 truncate">
              {{ featureRowVisualsById.get(String(item.database_id))?.name }}
            </div>
            <!-- Feature Icon -->
            <img
              v-if="featureRowVisualsById.get(String(item.database_id))?.iconUrl"
              :src="featureRowVisualsById.get(String(item.database_id))?.iconUrl ?? undefined"
              class="w-4 h-4 flex-shrink-0 object-contain"
              :alt="`${featureRowVisualsById.get(String(item.database_id))?.name ?? ''} icon`"
              @error="handleIconError"
            />
          </div>
          <!-- Mobile/Tablet hide icon -->
          <button
            v-if="canHideFeatures"
            type="button"
            class="ml-1 text-gray-400 hover:text-gray-600 p-1 xl:hidden"
            title="Hide This Feature from the Main Map"
            @click.stop.prevent="emitHideFeature(item)"
          >
            <EyeSlashIcon class="w-4 h-4" />
          </button>
        </div>
      </RecycleScroller>
    </div>

    <!-- Footer Count -->
    <div class="mt-1 text-xs text-gray-500 border-t border-gray-200 pt-1 px-1">
      {{ displayFeatures.length }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { RecycleScroller } from 'vue-virtual-scroller';
import { XMarkIcon, EyeSlashIcon } from '@heroicons/vue/24/outline';
import Loader from '@/components/parts/Loader.vue';
import { APIHOST } from '@/config.js';
import { getGeometryTypeColor, DEFAULT_GEOMETRY_COLOR } from '@/utils/geometryColors.js';
import { getIconUrl, resolveIconUrl, isSystemIcon } from '@/utils/map/iconUtils.ts';
import { toastApiError } from '@/utils/apiError';
import { toast } from '@/utils/toast';
import type { GeoJsonFeature } from '@/types/geospatial';

type DisplayFeature = GeoJsonFeature & { database_id: string | number };

interface FeatureRowVisuals {
  name: string;
  iconUrl: string | null;
  color: string;
}

const props = defineProps<{
  features: GeoJsonFeature[];
  isInitialLoad: boolean;
  canHideFeatures: boolean;
}>();

const emit = defineEmits<{
  'feature-click': [feature: GeoJsonFeature];
  'feature-hide': [feature: GeoJsonFeature];
}>();

const API_BASE_URL = '/api/features/search/';

const searchQuery = ref('');
const searchResults = ref<GeoJsonFeature[]>([]);
const isSearching = ref(false);
let searchTimeout: ReturnType<typeof setTimeout> | null = null;

const isSearchMode = computed(() => searchQuery.value.trim().length > 0);
const displayFeatures = computed(() => (isSearchMode.value ? searchResults.value : props.features));

const displayFeaturesWithKeys = computed<DisplayFeature[]>(() =>
  displayFeatures.value.map((feature, index) => {
    const existingId = (feature as Partial<DisplayFeature>).database_id;
    if (existingId !== undefined) {
      return feature as DisplayFeature;
    }

    const properties = feature.properties;
    const databaseId = (properties.database_id as string | number | undefined) ?? feature.geojson_hash ?? `feature-${index}`;
    return { ...feature, database_id: databaseId };
  }),
);

const showInitialFeaturesLoader = computed(() => props.isInitialLoad && props.features.length === 0 && !isSearching.value);

function getFeatureName(feature: GeoJsonFeature): string {
  return (feature.properties.name as string | undefined) ?? '';
}

function getFeatureGeometryType(feature: GeoJsonFeature): string {
  return feature.geometry.type;
}

function getFeatureIconUrl(feature: GeoJsonFeature): string | null {
  const properties = feature.properties;
  const iconUrl = getIconUrl(properties);
  if (!iconUrl) {
    return null;
  }

  const markerColor = properties['marker-color'] as string | undefined;
  const builtInIcon = isSystemIcon(iconUrl);

  if (builtInIcon && markerColor) {
    const iconPathForRecolor = iconUrl.replace('/api/icons/system/', '');
    const encodedColor = encodeURIComponent(markerColor);
    const encodedIcon = encodeURIComponent(iconPathForRecolor);
    return `${APIHOST}/api/icons/recolor/?icon=${encodedIcon}&color=${encodedColor}`;
  }

  return resolveIconUrl(iconUrl);
}

// Perf fix: precompute each row's display name/icon/color once per features-array change
// instead of calling getFeatureIconUrl/getGeometryTypeColor-style helpers from the template,
// where they were being invoked (and recomputed) multiple times per row on every re-render.
const featureRowVisualsById = computed<Map<string, FeatureRowVisuals>>(() => {
  const map = new Map<string, FeatureRowVisuals>();
  for (const feature of displayFeaturesWithKeys.value) {
    map.set(String(feature.database_id), {
      name: getFeatureName(feature),
      iconUrl: getFeatureIconUrl(feature),
      color: getGeometryTypeColor(getFeatureGeometryType(feature)),
    });
  }
  return map;
});

function handleIconError(event: Event) {
  const target = event.target as HTMLImageElement;
  target.style.display = 'none';
}

function handleFeatureClick(feature: GeoJsonFeature) {
  emit('feature-click', feature);
}

function handleFeatureContextMenu(feature: GeoJsonFeature) {
  if (!props.canHideFeatures) {
    return;
  }
  emitHideFeature(feature);
}

function emitHideFeature(feature: GeoJsonFeature) {
  if (!props.canHideFeatures) {
    return;
  }
  emit('feature-hide', feature);
}

function handleSearchInput() {
  if (searchTimeout) {
    clearTimeout(searchTimeout);
  }

  const query = searchQuery.value.trim();
  if (!query) {
    clearSearch();
    return;
  }

  isSearching.value = true;
  searchTimeout = setTimeout(() => {
    void performSearch(query);
  }, 300);
}

interface FeatureSearchResponse {
  data?: {
    features?: GeoJsonFeature[];
  };
  error?: string;
}

async function performSearch(query: string) {
  if (!query) {
    clearSearch();
    return;
  }

  isSearching.value = true;

  try {
    const url = `${APIHOST}${API_BASE_URL}?query=${encodeURIComponent(query)}`;
    const response = await fetch(url);
    const data = (await response.json()) as FeatureSearchResponse;

    if (response.ok && data.data?.features) {
      const features = data.data.features;

      features.sort((a, b) => {
        const nameA = ((a.properties.name as string | undefined) ?? 'Unnamed Feature').toLowerCase();
        const nameB = ((b.properties.name as string | undefined) ?? 'Unnamed Feature').toLowerCase();
        return nameA.localeCompare(nameB);
      });

      searchResults.value = features;
    } else {
      console.error('Search failed:', data.error ?? 'Unknown error');
      toast.error(data.error ?? 'Search failed');
      searchResults.value = [];
    }
  } catch (error) {
    console.error('Error searching features:', error);
    toastApiError(error, 'Search failed');
    searchResults.value = [];
  } finally {
    isSearching.value = false;
  }
}

function clearSearch() {
  searchQuery.value = '';
  searchResults.value = [];
  isSearching.value = false;
  if (searchTimeout) {
    clearTimeout(searchTimeout);
    searchTimeout = null;
  }
}
</script>

<style scoped>
.scroller {
  height: 100%;
}
</style>
