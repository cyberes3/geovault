<template>
  <div>
    <label v-if="label" class="block text-sm font-medium text-gray-700 mb-2">
      {{ label }}
    </label>
    <p v-if="description" class="text-xs text-gray-500 mb-3">{{ description }}</p>

    <div class="relative mb-3">
      <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
        <MagnifyingGlassIcon class="h-5 w-5 text-gray-400" />
      </div>
      <input
        v-model="searchQuery"
        type="text"
        @keydown.enter.prevent
        class="block w-full pl-10 pr-3 py-2 border border-gray-300 rounded-md leading-5 bg-white placeholder-gray-500 focus:outline-none focus:placeholder-gray-400 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 sm:text-sm"
        :placeholder="searchPlaceholder"
      />
    </div>

    <div v-if="loading" class="text-center py-4">
      <Loader size="sm" layout="centered" :message="loadingMessage" />
    </div>

    <div v-else-if="filteredItems.length === 0" class="text-center py-4 text-gray-500 text-sm">
      <p>{{ emptyMessage }}</p>
    </div>

    <div v-else :class="[maxHeight, 'overflow-y-auto border border-gray-200 rounded-md p-2']">
      <div
        v-for="item in filteredItems"
        :key="String(getItemId(item))"
        class="flex items-center px-3 py-2 hover:bg-gray-50 rounded space-x-3"
      >
        <input
          type="checkbox"
          :id="`item-${uid}-${String(getItemId(item))}`"
          class="checkbox-custom"
          :checked="isSelected(item)"
          @change="onCheckboxChange(item, $event.target.checked)"
        />
        <label
          :for="`item-${uid}-${String(getItemId(item))}`"
          class="text-sm text-gray-700 truncate cursor-pointer"
        >
          {{ getItemLabel(item) }}
        </label>
      </div>
    </div>

    <div v-if="showSelectedCount" class="mt-3">
      <p class="text-xs text-gray-500 mb-2">{{ selectedCountLabel }}: {{ selectedIds.length }}</p>
    </div>
  </div>
</template>

<script>
import { ref, computed, watch } from 'vue';
import { MagnifyingGlassIcon } from '@heroicons/vue/24/outline';
import Loader from './Loader.vue';

let uidCounter = 0;

export default {
  name: 'SearchableCheckboxList',
  components: { Loader, MagnifyingGlassIcon },
  props: {
    items: { type: Array, default: () => [] },
    modelValue: { type: Array, default: () => [] },
    getItemId: { type: Function, required: true },
    getItemLabel: { type: Function, required: true },
    label: { type: String, default: '' },
    description: { type: String, default: '' },
    searchPlaceholder: { type: String, default: 'Search...' },
    loading: { type: Boolean, default: false },
    loadingMessage: { type: String, default: 'Loading...' },
    emptyMessage: { type: String, default: 'No items available' },
    maxHeight: { type: String, default: 'max-h-48' },
    showSelectedCount: { type: Boolean, default: true },
    selectedCountLabel: { type: String, default: 'Selected' },
    /** Optional: filter items by search query. Receives (query, item), return true to include. Default uses getItemLabel. */
    filterFn: { type: Function, default: null },
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const uid = `scbl-${++uidCounter}`;
    const searchQuery = ref('');

    const selectedIds = computed(() => props.modelValue || []);

    const filteredItems = computed(() => {
      const list = props.items || [];
      const q = (searchQuery.value || '').trim().toLowerCase();
      if (!q) return list;
      if (props.filterFn && typeof props.filterFn === 'function') {
        return list.filter((item) => props.filterFn(q, item));
      }
      return list.filter((item) => {
        const label = (props.getItemLabel(item) || '').toLowerCase();
        return label.includes(q);
      });
    });

    function isSelected(item) {
      const id = props.getItemId(item);
      return selectedIds.value.some((s) => String(s) === String(id));
    }

    function onCheckboxChange(item, checked) {
      const id = props.getItemId(item);
      const next = [...selectedIds.value];
      const idx = next.findIndex((s) => String(s) === String(id));
      if (checked && idx === -1) {
        next.push(id);
      } else if (!checked && idx !== -1) {
        next.splice(idx, 1);
      }
      emit('update:modelValue', next);
    }

    return {
      uid,
      searchQuery,
      selectedIds,
      filteredItems,
      isSelected,
      onCheckboxChange,
    };
  },
};
</script>
