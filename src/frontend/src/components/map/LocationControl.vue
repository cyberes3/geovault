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
        <LocationIcon :show-center-dot="trackingState === 'locked'" />
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
import LocationIcon from '@/components/parts/LocationIcon.vue'

/**
 * Tracking State:
 * 'disabled' - Not tracking (Grey crosshair)
 * 'tracking' - Tracking, but map not locked (Blue crosshair)
 * 'locked'   - Tracking and map locked to location (Blue crosshair with dot)
 */

export default {
  name: 'LocationControl',
  components: {
    HomeIcon,
    LocationIcon
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
