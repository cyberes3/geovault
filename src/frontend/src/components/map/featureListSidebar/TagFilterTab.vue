<template>
  <div class="flex flex-col flex-1 min-h-0">
    <!-- Match Mode Radio Buttons -->
    <div class="mb-2 px-1">
      <div class="flex items-center gap-3 text-xs">
        <span class="text-gray-600 font-medium">Match:</span>
        <label class="flex items-center gap-1.5 cursor-pointer">
          <input
            type="radio"
            :checked="tagMatchMode === 'AND'"
            @change="$emit('update:tagMatchMode', 'AND')"
            value="AND"
            class="radio-custom"
          />
          <span class="text-gray-700">AND</span>
        </label>
        <label class="flex items-center gap-1.5 cursor-pointer">
          <input
            type="radio"
            :checked="tagMatchMode === 'OR'"
            @change="$emit('update:tagMatchMode', 'OR')"
            value="OR"
            class="radio-custom"
          />
          <span class="text-gray-700">OR</span>
        </label>
      </div>
    </div>

    <!-- Tag Search Input -->
    <div class="mb-2 px-1">
      <div class="relative">
        <input
          :value="tagSearchQuery"
          @input="$emit('update:tagSearchQuery', ($event.target as HTMLInputElement).value)"
          @keydown.enter="$emit('search-enter')"
          type="text"
          placeholder="Search tags..."
          class="w-full px-2 py-1.5 pr-7 text-xs border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
        />
        <button
          v-if="tagSearchQuery"
          @click="$emit('update:tagSearchQuery', '')"
          class="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 focus:outline-none"
          type="button"
          title="Clear Tag Search"
        >
          <XMarkIcon class="w-4 h-4" />
        </button>
      </div>
    </div>

    <!-- Selected Tags -->
    <div v-if="selectedTags.length > 0" class="mb-2 px-1">
      <div class="flex flex-wrap gap-1 mb-1">
        <span
          v-for="tag in selectedTags"
          :key="tag"
          :class="[
            'inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded max-w-full',
            tag.endsWith(':') ? 'bg-purple-100 text-purple-800' : 'bg-blue-100 text-blue-800'
          ]"
          :title="tag.endsWith(':') ? `Prefix match: ${tag}` : tag"
        >
          <span class="truncate min-w-0">{{ tag }}</span>
          <button
            @click="$emit('remove-tag', tag)"
            :class="[
              'hover:text-blue-800 focus:outline-none flex-shrink-0',
              tag.endsWith(':') ? 'text-purple-600' : 'text-blue-600'
            ]"
            type="button"
            title="Remove Tag from Filter"
          >
            <XMarkIcon class="w-3 h-3" />
          </button>
        </span>
      </div>
      <button
        @click="$emit('clear-filters')"
        class="text-xs text-blue-500 hover:text-blue-700 focus:outline-none"
        type="button"
        title="Clear All Tag Filters"
      >
        Clear filters
      </button>
    </div>

    <!-- Available Tags List -->
    <div class="flex-1 min-h-0">
      <!-- Initial Loading Indicator -->
      <div v-if="showInitialTagsLoader" class="flex items-center justify-center h-full">
        <Loader size="md" layout="centered" message="Loading tags..." />
      </div>
      <div v-else-if="filteredAvailableTagsWithKeys.length === 0 && availableTags.length === 0" class="text-xs text-gray-500 text-center py-3">
        No tags available
      </div>
      <div v-else-if="filteredAvailableTagsWithKeys.length === 0" class="text-xs text-gray-500 text-center py-3">
        No tags match your search
      </div>
      <RecycleScroller
        v-else
        class="scroller"
        :items="filteredAvailableTagsWithKeys"
        :item-size="28"
        key-field="key"
        v-slot="{ item }"
      >
        <button
          @click="$emit('toggle-tag', item.tag)"
          class="w-full px-1.5 py-1 text-left text-xs rounded transition-colors bg-gray-50 hover:bg-gray-100 text-gray-900 truncate min-w-0"
          :title="item.tag"
        >
          {{ item.tag }}
        </button>
      </RecycleScroller>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { RecycleScroller } from 'vue-virtual-scroller';
import { XMarkIcon } from '@heroicons/vue/24/outline';
import Loader from '@/components/parts/Loader.vue';
import type { TagMatchMode, TaggedListItem } from '@/composables/useTagFilter';

const props = defineProps<{
  availableTags: string[];
  selectedTags: string[];
  tagSearchQuery: string;
  tagMatchMode: TagMatchMode;
  isInitialLoad: boolean;
  isFiltering: boolean;
  filteredAvailableTagsWithKeys: TaggedListItem[];
}>();

defineEmits<{
  'update:tagSearchQuery': [value: string];
  'update:tagMatchMode': [value: TagMatchMode];
  'toggle-tag': [tag: string];
  'remove-tag': [tag: string];
  'clear-filters': [];
  'search-enter': [];
}>();

const showInitialTagsLoader = computed(() => props.isInitialLoad && props.availableTags.length === 0 && !props.isFiltering);
</script>

<style scoped>
.scroller {
  height: 100%;
}
</style>
