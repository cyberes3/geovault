<template>
  <div>
    <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Tags</label>
    <TagPicker
      :tags="tags"
      @update:tags="$emit('update:tags', $event)"
      :available-tags="availableTags"
      :system-tags="systemTags"
      :disabled="disabled"
      :show-label="false"
    />
  </div>
</template>

<script setup lang="ts">
import TagPicker from '@/components/parts/TagPicker.vue';

defineProps<{
  tags: string[];
  availableTags: string[];
  systemTags: string[];
  disabled: boolean;
}>();

defineEmits<{
  'update:tags': [tags: string[]];
}>();
</script>
