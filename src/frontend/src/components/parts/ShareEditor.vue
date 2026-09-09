<template>
  <div class="space-y-4">
    <div class="flex items-center gap-3">
      <div class="flex-shrink-0">
        <ToggleButton
          :model-value="includeTags"
          label="Include Tags"
          size="md"
          :disabled="disabled"
          @update:model-value="$emit('update:includeTags', $event)"
        />
      </div>
      <label class="block text-sm font-medium text-gray-700 cursor-pointer" @click="!disabled && $emit('update:includeTags', !includeTags)">
        Include Tags
      </label>
    </div>
    <div class="flex items-center gap-3">
      <div class="flex-shrink-0">
        <ToggleButton
          :model-value="allowDownloads"
          label="Allow Download"
          size="md"
          :disabled="disabled"
          @update:model-value="$emit('update:allowDownloads', $event)"
        />
      </div>
      <label class="block text-sm font-medium text-gray-700 cursor-pointer" @click="!disabled && $emit('update:allowDownloads', !allowDownloads)">
        Allow Download
      </label>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue'
import ToggleButton from './ToggleButton.vue'

export default defineComponent({
  name: 'ShareEditor',
  components: { ToggleButton },
  props: {
    includeTags: { type: Boolean, required: true },
    allowDownloads: { type: Boolean, required: true },
    disabled: { type: Boolean, default: false },
  },
  emits: ['update:includeTags', 'update:allowDownloads'],
})
</script>
