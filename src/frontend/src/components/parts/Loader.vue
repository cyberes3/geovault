<template>
  <div class="flex flex-col items-center justify-center py-12">
    <div class="relative">
      <div class="w-12 h-12 border-2 border-transparent rounded-full animate-spin" style="border-bottom-color: #4B6BAB;"></div>
    </div>
    <p class="mt-4 text-sm text-gray-600">{{ message }}</p>
  </div>
</template>

<script setup>
defineProps({
  message: {
    type: String,
    default: 'Loading...'
  }
})
</script>

<style scoped>

</style>