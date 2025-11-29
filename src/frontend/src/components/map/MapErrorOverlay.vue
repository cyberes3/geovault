<template>
  <transition name="fade">
    <div
      v-if="visible"
      class="absolute inset-0 z-50 flex items-center justify-center bg-gray-900 bg-opacity-50"
    >
      <div class="bg-white rounded-lg shadow-xl p-6 max-w-md mx-4 select-none">
        <div class="flex items-center space-x-3 mb-4">
          <ExclamationCircleIcon class="w-8 h-8 text-red-600" />
          <h3 class="text-lg font-semibold text-gray-900">
            {{ title }}
          </h3>
        </div>
        <p v-if="message" class="text-gray-700 mb-4">
          {{ message }}
        </p>
        <p v-if="subtext" class="text-sm text-gray-500">
          {{ subtext }}
        </p>
      </div>
    </div>
  </transition>
</template>

<script>
import { ExclamationCircleIcon } from '@heroicons/vue/24/outline'

export default {
  name: 'MapErrorOverlay',
  components: {
    ExclamationCircleIcon
  },
  props: {
    visible: {
      type: Boolean,
      required: true
    },
    title: {
      type: String,
      required: true
    },
    message: {
      type: String,
      default: ''
    },
    subtext: {
      type: String,
      default: ''
    }
  }
}
</script>


