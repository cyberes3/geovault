<template>
  <div
      v-if="place"
      class="fixed bottom-4 left-4 right-4 z-30 sm:hidden pointer-events-auto"
  >
    <div class="mx-auto max-w-md bg-white border border-gray-200 rounded-lg shadow-lg px-4 py-3 flex items-center justify-between gap-3">
      <span class="font-medium text-sm text-gray-900 truncate">{{ place.properties.name || 'Selected Place' }}</span>
      <button
          type="button"
          class="flex-shrink-0 text-sm text-blue-600 font-semibold hover:underline"
          @click="$emit('scroll-to-place')"
      >
        Scroll to item
      </button>
    </div>
  </div>
</template>

<script setup>
defineProps({
  place: { type: Object, default: null },
});

defineEmits(['scroll-to-place']);
</script>
