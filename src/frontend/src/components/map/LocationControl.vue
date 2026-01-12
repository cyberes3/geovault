<template>
  <div class="flex flex-col gap-1">
    <!-- Location Control Group -->
    <div class="flex flex-col bg-white border border-gray-200 rounded shadow-md overflow-hidden">
      <!-- Location Tracking Button -->
      <button
        class="p-2 transition-colors duration-200 focus:outline-none border-b border-gray-100"
        :class="[
          trackingState === 'disabled' ? 'text-gray-700 hover:bg-gray-50' : 'hover:bg-blue-50'
        ]"
        :style="trackingState !== 'disabled' ? { color: '#1a73e8' } : {}"
        :title="locationButtonTitle"
        @click="$emit('toggle-location')"
      >
        <svg class="w-5 h-5" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
          <!-- Crosshair Circle -->
          <circle cx="12" cy="12" r="8" stroke="currentColor" stroke-width="2" />
          <!-- Crosshair Lines -->
          <path d="M12 2V5" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          <path d="M12 19V22" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          <path d="M2 12L5 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          <path d="M19 12L22 12" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
          <!-- Center Dot (only for locked state) -->
          <circle v-if="trackingState === 'locked'" cx="12" cy="12" r="2" fill="currentColor" />
        </svg>
      </button>

      <!-- Home Button -->
      <button
        class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
        title="Go to home extent"
        @click="$emit('go-home')"
      >
        <HomeIcon class="w-5 h-5" />
      </button>
    </div>
  </div>
</template>

<script>
import { HomeIcon } from '@heroicons/vue/24/outline'

/**
 * Tracking State:
 * 'disabled' - Not tracking (Grey crosshair)
 * 'tracking' - Tracking, but map not locked (Blue crosshair)
 * 'locked'   - Tracking and map locked to location (Blue crosshair with dot)
 */

export default {
  name: 'LocationControl',
  components: {
    HomeIcon
  },
  props: {
    trackingState: {
      type: String,
      default: 'disabled',
      validator: (value) => ['disabled', 'tracking', 'locked'].includes(value)
    }
  },
  emits: ['toggle-location', 'go-home'],
  computed: {
    locationButtonTitle() {
      switch (this.trackingState) {
        case 'locked': return 'Map locked to location'
        case 'tracking': return 'Lock map to location'
        case 'disabled':
        default: return 'Start location tracking'
      }
    }
  }
}
</script>
