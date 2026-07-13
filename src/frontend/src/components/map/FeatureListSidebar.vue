<template>
  <!-- Teleport when open on mobile so stacking matches BaseModal (above app nav z-300). -->
  <Teleport to="body" :disabled="!isMobileOpen">
    <div
      data-app-mobile-overlay="sheet"
      :class="sidebarRootClass"
      :role="isMobileOpen ? 'dialog' : undefined"
      :aria-modal="isMobileOpen ? 'true' : undefined"
      :aria-labelledby="isMobileOpen ? 'feature-list-sidebar-title' : undefined"
    >
    <!-- Mobile Header -->
    <div class="lg:hidden flex items-center justify-between px-4 py-3 border-b border-gray-200">
      <h2 id="feature-list-sidebar-title" class="text-lg font-semibold text-gray-900">Features</h2>
      <button
        @click="emit('close')"
        class="text-gray-500 hover:text-gray-700 p-1 rounded-md hover:bg-gray-100"
      >
        <XMarkIcon class="w-6 h-6" />
      </button>
    </div>

    <!-- Tabs -->
    <div class="flex border-b border-gray-200 mb-2 px-1.5 pt-1.5 lg:px-1 xl:px-1.5">
      <button
        @click="activeTab = 'features-in-vicinity'"
        :class="[
          'px-2 py-1 text-xs font-medium transition-colors',
          activeTab === 'features-in-vicinity'
            ? 'text-blue-500 border-b-2 border-blue-500'
            : 'text-gray-600 hover:text-gray-900'
        ]"
        title="View Features in Current Map View"
      >
        Features in Vicinity
      </button>
      <button
        @click="activeTab = 'tag-filter'"
        :class="[
          'px-2 py-1 text-xs font-medium transition-colors flex items-center gap-1',
          activeTab === 'tag-filter'
            ? 'text-blue-500 border-b-2 border-blue-500'
            : 'text-gray-600 hover:text-gray-900'
        ]"
        title="Filter Features by Tags"
      >
        Tag Filter
        <FunnelIcon
          v-if="selectedTags.length > 0"
          class="w-3 h-3 text-blue-500"
        />
      </button>
      <button
        @click="activeTab = 'reverse_geocoding'"
        :class="[
          'px-2 py-1 text-xs font-medium transition-colors',
          activeTab === 'reverse_geocoding'
            ? 'text-blue-500 border-b-2 border-blue-500'
            : 'text-gray-600 hover:text-gray-900'
        ]"
        title="Search for Places or Paste Coordinates"
      >
        Search Places
      </button>
    </div>

    <FeaturesTab
      v-if="activeTab === 'features-in-vicinity'"
      :features="features"
      :is-initial-load="isInitialLoad"
      :can-hide-features="canHideFeatures"
      @feature-click="onFeatureClick"
      @feature-hide="(feature) => emit('feature-hide', feature)"
      @feature-hover="(feature) => emit('feature-hover', feature)"
    />

    <TagFilterTab
      v-if="activeTab === 'tag-filter'"
      :available-tags="availableTags"
      :selected-tags="selectedTags"
      :tag-search-query="tagSearchQuery"
      :tag-match-mode="tagMatchMode"
      :is-initial-load="isInitialLoad"
      :is-filtering="isFiltering"
      :filtered-available-tags-with-keys="filteredAvailableTagsWithKeys"
      @update:tag-search-query="tagSearchQuery = $event"
      @update:tag-match-mode="tagMatchMode = $event"
      @toggle-tag="toggleTag"
      @remove-tag="removeTag"
      @clear-filters="clearTagFilters"
      @search-enter="handleTagSearchEnter"
    />

    <PlaceSearchTab
      v-if="activeTab === 'reverse_geocoding'"
      :geocoding-available="geocodingAvailable"
      @result-click="onGeocodingResultClick"
      @clear="emit('reverse_geocoding-clear')"
    />
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, toRef, watch } from 'vue';
import { FunnelIcon, XMarkIcon } from '@heroicons/vue/24/outline';
import FeaturesTab from './featureListSidebar/FeaturesTab.vue';
import TagFilterTab from './featureListSidebar/TagFilterTab.vue';
import PlaceSearchTab from './featureListSidebar/PlaceSearchTab.vue';
import { useTagFilter } from '@/composables/useTagFilter';
import type { GeoJsonFeature } from '@/types/geospatial';

const props = withDefaults(defineProps<{
  features?: GeoJsonFeature[];
  availableTags?: string[];
  initialSelectedTags?: string[];
  isInitialLoad?: boolean;
  isMobileOpen?: boolean;
  canHideFeatures?: boolean;
  geocodingAvailable?: boolean;
}>(), {
  features: () => [],
  availableTags: () => [],
  initialSelectedTags: () => [],
  isInitialLoad: false,
  isMobileOpen: false,
  canHideFeatures: false,
  geocodingAvailable: false,
});

const emit = defineEmits<{
  'feature-click': [feature: GeoJsonFeature];
  'feature-hide': [feature: GeoJsonFeature];
  'feature-hover': [feature: GeoJsonFeature | null];
  'tag-filter-change': [payload: { tags: string[]; matchMode: 'AND' | 'OR' }];
  'tag-filter-loading-change': [loading: boolean];
  'tag-filter-start': [];
  'reverse_geocoding-result-click': [result: unknown];
  'reverse_geocoding-clear': [];
  close: [];
}>();

type TabId = 'features-in-vicinity' | 'tag-filter' | 'reverse_geocoding';
const activeTab = ref<TabId>('features-in-vicinity');

const {
  selectedTags,
  tagSearchQuery,
  tagMatchMode,
  isFiltering,
  filteredAvailableTagsWithKeys,
  toggleTag,
  removeTag,
  clearTagFilters,
  handleTagSearchEnter,
} = useTagFilter({
  availableTags: toRef(props, 'availableTags'),
  initialSelectedTags: toRef(props, 'initialSelectedTags'),
  emit,
  onActivate: () => {
    activeTab.value = 'tag-filter';
  },
});

const sidebarRootClass = computed(() => {
  if (props.isMobileOpen) {
    return [
      'bg-white',
      'flex',
      'flex-col',
      'overflow-hidden',
      'fixed',
      'inset-0',
      'z-50',
      'w-full',
      'h-full',
      'lg:hidden',
    ].join(' ');
  }
  return [
    'bg-white',
    'flex',
    'flex-col',
    'h-full',
    'overflow-hidden',
    'hidden',
    'lg:flex',
    'lg:static',
    'lg:w-64',
    'lg:border-r',
    'lg:border-gray-200',
    'xl:w-80',
  ].join(' ');
});

function onFeatureClick(feature: GeoJsonFeature) {
  emit('feature-click', feature);
  // Close modal on mobile when a feature is selected.
  if (props.isMobileOpen) {
    emit('close');
  }
}

function onGeocodingResultClick(result: unknown) {
  emit('reverse_geocoding-result-click', result);
  // Close modal on mobile when a result is selected.
  if (props.isMobileOpen) {
    emit('close');
  }
}

watch(
  () => props.isMobileOpen,
  (open) => {
    if (open) {
      document.body.classList.add('overflow-hidden');
    } else {
      document.body.classList.remove('overflow-hidden');
    }
  },
);

onBeforeUnmount(() => {
  document.body.classList.remove('overflow-hidden');
});
</script>
