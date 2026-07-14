<template>
  <div class="flex-shrink-0 bg-white rounded-none sm:rounded-lg shadow-sm border border-gray-200 border-t-0 sm:border-t p-4 sm:p-6">
    <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 mb-4">
      <div>
        <h1 class="text-xl sm:text-2xl font-bold text-gray-900 mb-1 sm:mb-2">Places</h1>
      </div>
      <BaseButton
          class="w-full sm:w-auto"
          variant="primary"
          color="blue"
          size="md"
          title="Add a New Place"
          @click="$emit('add-place')"
      >
        <PlusIcon class="h-5 w-5 mr-2"/>
        Add Place
      </BaseButton>
    </div>
    <div class="mt-2 sm:mt-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
      <p class="text-sm text-gray-700">
        Places are saved location bookmarks to help you remember important spots.
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { PlusIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';

defineEmits<{
  'add-place': [];
}>();
</script>
