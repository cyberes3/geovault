<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
    <h3 class="text-base sm:text-lg font-semibold text-gray-900 mb-4">Import Summary</h3>
    <div v-if="isLoading" class="text-center py-8">
      <span class="text-blue-500 font-medium">Loading...</span>
    </div>
    <div v-else class="grid grid-cols-1 md:grid-cols-3 gap-4">
      <div class="bg-blue-50 border border-blue-200 rounded-lg p-4">
        <div class="flex items-center">
          <div class="flex-shrink-0">
            <DocumentIcon class="h-8 w-8 text-blue-600" />
          </div>
          <div class="ml-3">
            <p class="text-sm font-medium text-blue-700">Total Features</p>
            <p class="text-2xl font-bold text-blue-700">{{ totalFeatures }}</p>
          </div>
        </div>
      </div>

      <div class="bg-green-50 border border-green-200 rounded-lg p-4">
        <div class="flex items-center">
          <div class="flex-shrink-0">
            <CheckIcon class="h-8 w-8 text-green-400" />
          </div>
          <div class="ml-3">
            <p class="text-sm font-medium text-green-800">Ready to Import</p>
            <p class="text-2xl font-bold text-green-900">{{ importableCount }}</p>
          </div>
        </div>
      </div>

      <div class="bg-yellow-50 border border-yellow-200 rounded-lg p-4">
        <div class="flex items-center">
          <div class="flex-shrink-0">
            <ExclamationTriangleIcon class="h-8 w-8 text-yellow-400" />
          </div>
          <div class="ml-3">
            <p class="text-sm font-medium text-yellow-800">Duplicates</p>
            <p class="text-2xl font-bold text-yellow-900">{{ duplicateCount }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { CheckIcon, DocumentIcon, ExclamationTriangleIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'ImportSummaryStats',
  components: {
    CheckIcon,
    DocumentIcon,
    ExclamationTriangleIcon
  },
  props: {
    totalFeatures: {
      type: Number,
      default: 0
    },
    importableCount: {
      type: Number,
      default: 0
    },
    duplicateCount: {
      type: Number,
      default: 0
    },
    isLoading: {
      type: Boolean,
      default: false
    }
  }
};
</script>

