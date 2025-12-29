<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
    <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
      <div>
        <h1 class="text-xl sm:text-2xl font-bold text-gray-900 mb-1 sm:mb-2">Process Import</h1>
        <h2 v-if="originalFilename != null" class="text-sm sm:text-lg text-gray-600 break-words">{{ originalFilename }}</h2>
        <div v-else class="h-6 w-48 bg-gray-200 rounded animate-pulse"></div>
        <p v-if="uploadTimestamp != null" class="text-xs sm:text-sm text-gray-500 mt-1">{{ formattedUploadDate }}</p>
      </div>
      <div class="flex items-center sm:justify-end">
        <span v-if="isImported" class="inline-flex items-center px-3 py-1 rounded-full text-xs sm:text-sm font-medium bg-green-100 text-green-800">
          <CheckIcon class="w-4 h-4 mr-1" />
          Imported
        </span>
      </div>
    </div>
  </div>
</template>

<script>
import { CheckIcon } from '@heroicons/vue/24/outline';
import { formatDate } from '@/utils/dateUtils.js';

export default {
  name: 'ImportProcessHeader',
  components: {
    CheckIcon
  },
  props: {
    originalFilename: {
      type: String,
      default: null
    },
    uploadTimestamp: {
      type: String,
      default: null
    },
    isImported: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    formattedUploadDate() {
      return formatDate(this.uploadTimestamp);
    }
  }
};
</script>

