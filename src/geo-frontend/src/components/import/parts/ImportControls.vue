<template>
  <div class="space-y-4">
    <!-- Status Messages -->
    <div v-if="isImported && !isLoadingPage" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="p-4 bg-yellow-50 border border-yellow-200 rounded-md">
        <div class="flex">
          <div class="flex-shrink-0">
            <svg class="h-5 w-5 text-yellow-400" fill="currentColor" viewBox="0 0 20 20">
              <path clip-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" fill-rule="evenodd"></path>
            </svg>
          </div>
          <div class="ml-3">
            <h3 class="text-sm font-medium text-yellow-800">Already Imported</h3>
            <div class="mt-2 text-sm text-yellow-700">
              <p>This item has already been imported to the feature store and cannot be modified.</p>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-else-if="duplicateStatus === 'duplicate_in_queue' && !isLoadingPage && showDuplicateMessage" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="text-center py-4">
        <div class="text-purple-500 mb-4">
          <svg class="mx-auto h-12 w-12 text-purple-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
          </svg>
        </div>
        <h3 class="text-lg font-medium text-gray-900 mb-2">Duplicate File in Queue</h3>
        <p class="text-gray-600">This file is a duplicate of <span class="font-medium text-purple-700">{{ duplicateOriginalFilename }}</span>, which is already waiting in the import queue.</p>
        <p class="text-gray-500 text-sm mt-2">No actions can be performed on this duplicate file.</p>
      </div>
    </div>
    <div v-else-if="duplicateStatus === 'duplicate_imported' && !isLoadingPage && showDuplicateMessage" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="p-4 bg-purple-50 border border-purple-200 rounded-md">
        <div class="flex">
          <div class="flex-shrink-0">
            <svg class="h-5 w-5 text-purple-400" fill="currentColor" viewBox="0 0 20 20">
              <path d="M7 9a2 2 0 012-2h6a2 2 0 012 2v6a2 2 0 01-2 2H9a2 2 0 01-2-2V9z"></path>
              <path d="M5 3a2 2 0 00-2 2v6a2 2 0 002 2V5h8a2 2 0 00-2-2H5z"></path>
            </svg>
          </div>
          <div class="ml-3">
            <h3 class="text-sm font-medium text-purple-800">Duplicate of Imported File</h3>
            <div class="mt-2 text-sm text-purple-700">
              <p>This file is a duplicate of <span class="font-medium">{{ duplicateOriginalFilename }}</span>, which has already been imported.</p>
              <p class="mt-1">You can still import this file. Features that match existing features in your library will be marked as duplicates during import.</p>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-else-if="showNoFeaturesMessage && !isLoadingPage && importableCount === 0" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="text-center py-4">
        <div class="text-gray-500 mb-4">
          <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
          </svg>
        </div>
        <h3 class="text-lg font-medium text-gray-900 mb-2">No Features to Import</h3>
        <p class="text-gray-600">This file has been processed but contains no importable features.</p>
      </div>
    </div>

    <!-- Pagination Controls -->
    <div v-if="(hasFeatures || isLoadingPage)" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="flex items-center justify-between flex-wrap gap-4">
        <div class="text-sm text-gray-700">
          <span v-if="!isLoadingPage">
            Showing features {{ (currentPage - 1) * pageSize + 1 }} - {{ Math.min(currentPage * pageSize, totalFeatures) }} of {{ totalFeatures }}
          </span>
          <span v-else class="text-blue-600 font-medium">Loading...</span>
        </div>
        <div class="flex items-center space-x-2">
          <button
              :disabled="!hasPreviousPage || isLoadingPage || totalPages <= 1"
              class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              @click="$emit('previous-page')"
          >
            <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path d="M15 19l-7-7 7-7" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
            </svg>
            Previous
          </button>
          <span class="text-sm text-gray-700">Page {{ currentPage }} of {{ totalPages }}</span>
          <button
              :disabled="!hasNextPage || isLoadingPage || totalPages <= 1"
              class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              @click="$emit('next-page')"
          >
            Next
            <svg class="w-4 h-4 ml-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path d="M9 5l7 7-7 7" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
            </svg>
          </button>
          <div class="flex items-center space-x-2 ml-4 pl-4 border-l border-gray-300">
            <label class="text-sm text-gray-700" for="goto-page">Go to:</label>
            <input
                id="goto-page"
                v-model.number="gotoPageInputLocal"
                :disabled="isLoadingPage || totalPages <= 1"
                :max="totalPages"
                class="w-16 px-2 py-1 border border-gray-300 rounded-md text-sm focus:ring-blue-500 focus:border-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                min="1"
                type="number"
                @keyup.enter="jumpToPage"
            />
            <button
                :disabled="isLoadingPage || !isValidPageNumber || totalPages <= 1"
                class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                @click="jumpToPage"
            >
              Go
            </button>
          </div>
          <button
              :disabled="isLoadingPage || !hasFeatures"
              class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed ml-4 pl-4 border-l"
              @click="$emit('show-map-preview')"
          >
            <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
            </svg>
            Map Preview (Current Page)
          </button>
        </div>
      </div>
    </div>

    <!-- Action Buttons -->
    <div v-if="showActionButtons && shouldShowActions" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div v-if="isLoadingPage" class="text-center py-4">
        <span class="text-blue-600 font-medium">Loading...</span>
      </div>
      <div v-else-if="hasFeatures" class="flex items-center space-x-4">
          <button
              :disabled="lockButtons || isSaving"
              class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors duration-200"
              @click="$emit('save-changes')"
          >
          <svg v-if="isSaving" class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" fill="none" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path class="opacity-75" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" fill="currentColor"></path>
          </svg>
          <svg v-else class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
          </svg>
          {{ isSaving ? 'Saving...' : 'Save Changes' }}
        </button>
        <button
            :disabled="lockButtons || isImporting || importableCount === 0"
            class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors duration-200"
            @click="$emit('perform-import')"
        >
          <svg v-if="isImporting" class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" fill="none" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path class="opacity-75" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z" fill="currentColor"></path>
          </svg>
          <svg v-else class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
          </svg>
          {{ isImporting ? 'Importing...' : `Import ${importableCount} Features` }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
export default {
  name: 'ImportControls',
  props: {
    hasFeatures: {
      type: Boolean,
      required: true
    },
    isLoadingPage: {
      type: Boolean,
      required: true
    },
    currentPage: {
      type: Number,
      required: true
    },
    pageSize: {
      type: Number,
      required: true
    },
    totalFeatures: {
      type: Number,
      required: true
    },
    totalPages: {
      type: Number,
      required: true
    },
    hasNextPage: {
      type: Boolean,
      required: true
    },
    hasPreviousPage: {
      type: Boolean,
      required: true
    },
    duplicateCount: {
      type: Number,
      required: true
    },
    isImported: {
      type: Boolean,
      required: true
    },
    lockButtons: {
      type: Boolean,
      required: true
    },
    isSaving: {
      type: Boolean,
      required: true
    },
    isImporting: {
      type: Boolean,
      required: true
    },
    importableCount: {
      type: Number,
      required: true
    },
    gotoPageInput: {
      type: Number,
      default: null
    },
    showNoFeaturesMessage: {
      type: Boolean,
      default: true
    },
    duplicateStatus: {
      type: String,
      default: null
    },
    duplicateOriginalFilename: {
      type: String,
      default: null
    },
    showDuplicateMessage: {
      type: Boolean,
      default: true
    },
    showActionButtons: {
      type: Boolean,
      default: true
    }
  },
  data() {
    return {
      gotoPageInputLocal: this.gotoPageInput
    };
  },
  computed: {
    isValidPageNumber() {
      return this.gotoPageInputLocal &&
          this.gotoPageInputLocal >= 1 &&
          this.gotoPageInputLocal <= this.totalPages &&
          this.gotoPageInputLocal !== this.currentPage;
    },
    shouldShowActions() {
      return (
          this.isLoadingPage ||
          this.isImported ||
          this.duplicateStatus === 'duplicate_in_queue' ||
          (this.showNoFeaturesMessage && !this.isLoadingPage && this.importableCount === 0) ||
          this.hasFeatures
      );
    }
  },
  watch: {
    gotoPageInput(newVal) {
      this.gotoPageInputLocal = newVal;
    }
  },
  methods: {
    jumpToPage() {
      if (this.isValidPageNumber) {
        this.$emit('jump-to-page', this.gotoPageInputLocal);
        this.gotoPageInputLocal = null;
      }
    }
  }
}
</script>

<style scoped>
</style>

