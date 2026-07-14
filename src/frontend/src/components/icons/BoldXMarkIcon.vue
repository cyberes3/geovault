<template>
  <svg
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      stroke-width="3"
      stroke="currentColor"
      aria-hidden="true"
      :class="iconClass"
  >
    <path
        stroke-linecap="round"
        stroke-linejoin="round"
        d="M6 18 18 6M6 6l12 12"
    />
  </svg>
</template>

<script lang="ts">
import { defineComponent } from 'vue'

export default defineComponent({
  name: 'BoldXMarkIcon',
  props: {
    iconClass: {
      type: String,
      default: ''
    }
  }
})
</script>

