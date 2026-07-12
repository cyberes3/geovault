<template>
  <div class="space-y-4">
    <!-- Icon Section (for points) -->
    <IconSelector
      v-if="isPoint"
      :icon-url="currentIconUrl ?? undefined"
      :disabled="disabled"
      :show-remove="true"
      size="sm"
      :error="iconUploadError"
      @icon-selected="$emit('icon-selected', $event)"
      @icon-removed="$emit('icon-removed')"
    />

    <!-- Icon Color (for points) -->
    <!-- Enabled for: default markers (no icon) OR system icons (recolorable) -->
    <!-- Disabled for: user icons or external URLs (custom, non-recolorable) -->
    <!-- Hidden when custom icon is present -->
    <div v-if="isPoint && !isCustomIcon">
      <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Icon Color</label>
      <ColorPicker
        :model-value="markerColor"
        @update:model-value="$emit('update:markerColor', $event)"
        :disabled="disabled"
        size="sm"
      />
    </div>

    <!-- Line Color -->
    <div v-if="isLine">
      <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Line Color</label>
      <ColorPicker
        :model-value="strokeColor"
        @update:model-value="$emit('update:strokeColor', $event)"
        :disabled="disabled"
        size="sm"
        @change="$emit('stroke-color-change', $event)"
      />
    </div>

    <!-- Polygon Border Color -->
    <div v-if="isPolygon">
      <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Border Color</label>
      <ColorPicker
        :model-value="strokeColor"
        @update:model-value="$emit('update:strokeColor', $event)"
        :disabled="disabled"
        size="sm"
        @change="$emit('stroke-color-change', $event)"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import ColorPicker from '@/components/parts/ColorPickerElement.vue';
import IconSelector from '@/components/parts/IconSelector.vue';

defineProps<{
  isPoint: boolean;
  isLine: boolean;
  isPolygon: boolean;
  currentIconUrl: string | null;
  isCustomIcon: boolean;
  iconUploadError: string;
  markerColor: string;
  strokeColor: string;
  disabled: boolean;
}>();

defineEmits<{
  'icon-selected': [payload: { iconUrl: string; isSystemIcon: boolean }];
  'icon-removed': [];
  'update:markerColor': [value: string];
  'update:strokeColor': [value: string];
  'stroke-color-change': [value: string];
}>();
</script>
