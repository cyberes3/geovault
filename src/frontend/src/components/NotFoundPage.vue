<template>
  <div class="max-w-7xl mx-auto pt-6">
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-8 md:p-12">
      <div class="text-center">
        <!-- 404 Heading -->
        <h1 class="text-6xl md:text-8xl font-bold text-gray-900 mb-4">404</h1>

        <!-- Error Icon -->
        <div class="flex justify-center mb-6">
          <div class="w-20 h-20 bg-blue-100 rounded-full flex items-center justify-center">
            <ExclamationCircleIcon class="w-12 h-12 text-blue-500"/>
          </div>
        </div>

        <!-- Error Message -->
        <h2 class="text-2xl md:text-3xl font-semibold text-gray-900 mb-4">
          Page Not Found
        </h2>
        <p class="text-lg text-gray-600 mb-8 max-w-2xl mx-auto">
          The page you're looking for doesn't exist.
        </p>

        <!-- Navigation Buttons -->
        <div class="flex flex-col sm:flex-row gap-4 justify-center">
          <router-link
              to="/dashboard"
              class="inline-flex items-center px-6 py-3 border border-transparent text-base font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors duration-200"
          >
            <HomeIcon class="w-5 h-5 mr-2"/>
            Go to Dashboard
          </router-link>
          <router-link
              to="/map"
              class="inline-flex items-center px-6 py-3 border border-gray-300 text-base font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors duration-200"
          >
            <MapIcon class="w-5 h-5 mr-2"/>
            View Map
          </router-link>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue'
import {ExclamationCircleIcon, HomeIcon, MapIcon} from '@heroicons/vue/24/outline';

export default defineComponent({
  name: 'NotFoundPage',
  components: {
    ExclamationCircleIcon,
    HomeIcon,
    MapIcon
  }
})
</script>

<style scoped>
/* Component-specific styles if needed */
</style>

