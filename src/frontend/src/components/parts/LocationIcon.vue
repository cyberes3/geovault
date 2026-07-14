<template>
  <svg :class="size" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
    <!-- Crosshair Circle -->
    <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2" />
    <!-- Crosshair Lines -->
    <path d="M12 2V5" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
    <path d="M12 19V22" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
    <path d="M2 12L5 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
    <path d="M19 12L22 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
    <!-- Center Dot (optional) -->
    <circle v-if="showCenterDot" cx="12" cy="12" r="2" fill="currentColor" />
  </svg>
</template>

<script lang="ts">
import { defineComponent } from 'vue'

export default defineComponent({
  name: 'LocationIcon',
  props: {
    size: {
      type: String,
      default: 'w-5 h-5'
    },
    showCenterDot: {
      type: Boolean,
      default: false
    }
  }
})
</script>
