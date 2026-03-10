<template>
  <div class="space-y-1">
    <label v-if="label" class="block text-sm font-medium text-gray-700">{{ label }}</label>
    <div
      class="border border-gray-300 rounded-md bg-white shadow-sm overflow-hidden"
      :class="{ 'opacity-60': disabled }"
    >
      <div
        class="overflow-y-auto custom-scrollbar min-h-0"
        :style="{ height: maxHeight, maxHeight: maxHeight }"
      >
        <div v-if="loading" class="flex items-center justify-center py-8">
          <Loader size="sm" layout="inline" :show-message="false" />
        </div>
        <template v-else-if="effectiveItems.length === 0">
          <p class="px-3 py-4 text-sm text-gray-500 text-center">{{ emptyMessage }}</p>
        </template>
        <button
          v-for="item in effectiveItems"
          :key="item.value"
          type="button"
          :disabled="disabled"
          :title="isSelected(item.value) ? 'Click to remove' : 'Click to add'"
          :class="[
            'w-full text-left px-3 py-2 text-sm focus:outline-none border-b border-gray-100 last:border-b-0 transition-colors',
            isSelected(item.value)
              ? 'bg-blue-100 text-blue-800 hover:bg-blue-100 focus:bg-blue-100'
              : 'text-gray-700 hover:bg-blue-100 hover:text-blue-800 focus:bg-blue-100 focus:text-blue-800',
            disabled ? 'opacity-60 cursor-not-allowed' : 'cursor-pointer'
          ]"
          @click="onSelect(item)"
        >
          {{ item.label }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import Loader from './Loader.vue';

export default {
  name: 'ScrollingSelect',
  components: { Loader },
  props: {
    /** Options to show. Each: { value: string, label: string } (or id/label for compatibility). */
    items: { type: Array, default: () => [] },
    /** Values that are selected (shown highlighted; click toggles when not disabled). */
    selectedValues: { type: Array, default: () => [] },
    /** Max height of the scroll area (CSS value, e.g. '12rem' or '200px'). */
    maxHeight: { type: String, default: '12rem' },
    loading: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    label: { type: String, default: '' },
    emptyMessage: { type: String, default: 'No options' },
  },
  emits: ['select'],
  computed: {
    effectiveItems() {
      return (this.items || []).map((it) => ({
        value: it.value != null ? String(it.value) : (it.id != null ? String(it.id) : ''),
        label: it.label != null ? it.label : (it.email != null ? it.email : String(it.value ?? it.id ?? '')),
      })).filter((it) => it.value !== '' || it.label !== '');
    },
  },
  methods: {
    isSelected(value) {
      return this.selectedValues && this.selectedValues.some((v) => String(v) === String(value));
    },
    onSelect(item) {
      if (this.disabled) return;
      this.$emit('select', item);
    },
  },
};
</script>
