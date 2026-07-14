<template>
  <div class="absolute top-4 left-4 right-4 z-10 max-w-md">
    <div
        class="bg-white ring-1 ring-black/5 flex items-center p-1"
        :class="showDropdown ? 'rounded-t-lg' : 'rounded-lg'"
    >
      <input
          :value="searchQuery"
          placeholder="Search locations..."
          class="flex-1 outline-none text-sm px-3 py-2 bg-transparent w-full disabled:opacity-60 disabled:cursor-not-allowed"
          :disabled="disabled"
          @input="$emit('update:searchQuery', ($event.target as HTMLInputElement).value); $emit('input')"
          @keyup.enter="$emit('search')"
      />
      <button
          type="button"
          class="p-2 hover:bg-gray-100 rounded-md text-gray-500 transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
          :disabled="disabled"
          @click="$emit('search')"
      >
        <div class="w-5 h-5 flex items-center justify-center overflow-hidden">
          <Loader v-if="isSearching" size="sm" :show-message="false" class="!py-0 !mt-0"/>
          <MagnifyingGlassIcon v-else class="w-5 h-5"/>
        </div>
      </button>
    </div>

    <div
        v-if="showDropdown"
        class="bg-white ring-1 ring-black/5 max-h-60 overflow-y-auto w-full absolute top-full left-0 z-50 rounded-t-none rounded-b-lg"
    >
      <div v-if="searchResults.length === 0" class="px-4 py-3 text-gray-500 text-sm italic">
        No results found
      </div>
      <div
          v-for="result in searchResults"
          :key="result.id || result.text"
          class="px-4 py-2 hover:bg-gray-100 cursor-pointer border-b border-gray-100 last:border-0 transition-colors"
          @click="$emit('select-result', result)"
      >
        <p class="text-sm font-semibold text-gray-900 truncate">{{ result.text || result.place_name }}</p>
        <p
            v-if="result.place_name && result.place_name !== result.text"
            class="text-xs text-gray-500 truncate mt-0.5"
        >
          {{ result.place_name }}
        </p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { MagnifyingGlassIcon } from '@heroicons/vue/24/outline';
import Loader from 'platform/components/parts/Loader.vue';
import type { GeocodingResult } from '@/types/gv-core';

const props = withDefaults(defineProps<{
  searchQuery?: string;
  searchResults?: GeocodingResult[];
  isSearching?: boolean;
  showResults?: boolean;
  searchTimeout?: ReturnType<typeof setTimeout> | null;
  disabled?: boolean;
}>(), {
  searchQuery: '',
  searchResults: () => [],
  isSearching: false,
  showResults: false,
  searchTimeout: null,
  disabled: false,
});

defineEmits<{
  'update:searchQuery': [value: string];
  input: [];
  search: [];
  'select-result': [result: GeocodingResult];
}>();

const showDropdown = computed((): boolean => {
  return props.showResults && (
    props.searchResults.length > 0
    || Boolean(props.searchQuery && !props.isSearching && props.searchTimeout === null)
  );
});
</script>
