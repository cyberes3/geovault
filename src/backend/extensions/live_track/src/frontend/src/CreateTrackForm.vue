<template>
  <div class="p-4 space-y-4">
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Name <span class="text-red-500">*</span></label>
      <input
        :value="name"
        type="text"
        placeholder="Tracker name"
        class="w-full border border-gray-300 px-3 py-2 rounded-lg"
        @input="$emit('update:name', ($event.target && $event.target.value) || '')"
      />
      <p v-if="error" class="text-sm text-red-600">{{ error }}</p>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Color</label>
      <ColorPickerElement
        :model-value="displayColor"
        @update:model-value="$emit('color-picked', $event)"
      />
    </div>
  </div>
</template>

<script>
export default {
  name: 'CreateTrackForm',
  props: {
    name: { type: String, default: '' },
    displayColor: { type: String, default: '#6C93DE' },
    error: { type: String, default: '' }
  },
  emits: ['update:name', 'color-picked']
};
</script>
