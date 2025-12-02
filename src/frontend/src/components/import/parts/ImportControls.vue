<template>
  <div class="space-y-4">
    <!-- Status Messages -->
    <div v-if="isImported && !isLoadingPage" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="p-4 bg-yellow-50 border border-yellow-200 rounded-md">
        <div class="flex">
          <div class="flex-shrink-0">
            <ExclamationTriangleIcon class="h-5 w-5 text-yellow-400" />
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
    <div v-else-if="fileDuplicate.status === 'duplicate_in_queue' && !isLoadingPage && showDuplicateMessage" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="text-center py-4">
        <div class="text-purple-500 mb-4">
          <ClipboardDocumentIcon class="mx-auto h-12 w-12 text-purple-400" />
        </div>
        <h3 class="text-lg font-medium text-gray-900 mb-2">Duplicate File in Queue</h3>
        <p class="text-gray-600">This file is a duplicate of <span class="font-medium text-purple-700">{{ fileDuplicate.originalFilename }}</span>, which is already waiting in the import queue.</p>
        <p class="text-gray-500 text-sm mt-2">No actions can be performed on this duplicate file.</p>
      </div>
    </div>
    <div v-else-if="fileDuplicate.status === 'duplicate_imported' && !isLoadingPage && showDuplicateMessage" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="p-4 bg-purple-50 border border-purple-200 rounded-md">
        <div class="flex">
          <div class="flex-shrink-0">
            <ClipboardDocumentIcon class="h-5 w-5 text-purple-400" />
          </div>
          <div class="ml-3">
            <h3 class="text-sm font-medium text-purple-800">Duplicate of Imported File</h3>
            <div class="mt-2 text-sm text-purple-700">
              <p>This file is a duplicate of <span class="font-medium">{{ fileDuplicate.originalFilename }}</span>, which has already been imported.</p>
              <p class="mt-1">You can still import this file. Features that match existing features in your library will be marked as duplicates during import.</p>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-else-if="errorMessage && errorMessage !== '' && !isLoadingPage && importableCount === 0" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="p-4 bg-red-50 border border-red-200 rounded-md">
        <div class="flex">
          <div class="flex-shrink-0">
            <ExclamationCircleIcon class="h-5 w-5 text-red-400" />
          </div>
          <div class="ml-3">
            <h3 class="text-sm font-medium text-red-800">{{ processingFailedTitle }}</h3>
            <div class="mt-2 text-sm text-red-700">
              <p>{{ errorMessage }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-else-if="showNoFeaturesMessage && !isLoadingPage && importableCount === 0" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="text-center py-4">
        <div class="text-gray-500 mb-4">
          <DocumentIcon class="mx-auto h-12 w-12 text-gray-400" />
        </div>
        <h3 class="text-lg font-medium text-gray-900 mb-2">No Features to Import</h3>
        <p class="text-gray-600">This file has been processed but contains no importable features.</p>
      </div>
    </div>

    <!-- Pagination Controls -->
    <div v-if="(hasFeatures || isLoadingPage)" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <div class="flex flex-col md:flex-row md:items-center md:justify-center gap-4">
        <div class="text-sm text-gray-700 text-center md:text-left">
          <span v-if="!isLoadingPage">
            Showing features {{ (currentPage - 1) * pageSize + 1 }} - {{ Math.min(currentPage * pageSize, totalFeatures) }} of {{ totalFeatures }}
          </span>
          <span v-else class="text-blue-500 font-medium">Loading...</span>
        </div>
        <div class="flex flex-wrap items-center justify-center md:justify-start gap-2 md:gap-3">
          <!-- Hide Duplicates Toggle -->
          <div v-if="hasFeatures && !isLoadingPage" class="flex items-center space-x-2 md:mr-4 md:pr-4 md:border-r border-gray-300 w-full md:w-auto justify-center md:justify-start mb-2 md:mb-0">
            <ToggleButton
                :model-value="hideDuplicates"
                label="Hide duplicates"
                :disabled="isLoadingPage"
                size="sm"
                @update:model-value="$emit('toggle-hide-duplicates', $event)"
            />
            <label class="text-sm text-gray-700 cursor-pointer whitespace-nowrap" @click="!isLoadingPage && $emit('toggle-hide-duplicates', !hideDuplicates)">
              Hide duplicates
            </label>
          </div>
          <button
              :disabled="!hasPreviousPage || isLoadingPage || totalPages <= 1"
              class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              @click="$emit('previous-page')"
              title="Go to previous page"
          >
            <ChevronLeftIcon class="w-4 h-4 mr-1" />
            Previous
          </button>
          <span class="text-sm text-gray-700">Page {{ currentPage }} of {{ totalPages }}</span>
          <button
              :disabled="!hasNextPage || isLoadingPage || totalPages <= 1"
              class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              @click="$emit('next-page')"
              title="Go to next page"
          >
            Next
            <ChevronRightIcon class="w-4 h-4 ml-1" />
          </button>
          <div class="flex items-center space-x-2 md:ml-4 md:pl-4 md:border-l border-gray-300">
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
                title="Jump to page"
            >
              Go
            </button>
          </div>
          <button
              :disabled="isLoadingPage || !hasFeatures"
              class="inline-flex items-center justify-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed w-full md:w-auto md:ml-4 md:pl-4 md:border-l"
              @click="$emit('show-map-preview')"
              title="Preview all features on current page"
          >
            <MapIcon class="w-4 h-4 mr-2" />
            Map Preview (Current Page)
          </button>
        </div>
      </div>
    </div>

    <!-- Action Buttons -->
    <div v-if="showActionButtons && shouldShowActions" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div v-if="isLoadingPage" class="text-center py-4">
        <span class="text-blue-500 font-medium">Loading...</span>
      </div>
      <div v-else-if="hasFeatures" class="flex flex-col sm:flex-row items-stretch sm:items-center gap-4 sm:space-x-4">
          <button
              :disabled="lockButtons || isSaving"
              :class="saveStatus === 'success' ? 'w-full sm:w-[160px] inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 transition-colors duration-200' : (saveStatus === 'error' ? 'w-full sm:w-[160px] inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 transition-colors duration-200' : 'w-full sm:w-[160px] inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors duration-200')"
              @click="$emit('save-changes')"
              title="Save all changes"
          >
          <Loader v-if="isSaving" size="sm" layout="inline" :showMessage="false" color="white" />
          <CheckIcon v-else-if="saveStatus === 'success'" class="w-4 h-4 mr-2" />
          <XMarkIcon v-else-if="saveStatus === 'error'" class="w-4 h-4 mr-2" />
          <ArrowDownTrayIcon v-else class="w-4 h-4 mr-2" />
          {{ isSaving ? 'Saving...' : (saveStatus === 'success' ? 'Saved!' : (saveStatus === 'error' ? 'Failed' : 'Save Changes')) }}
        </button>
        <button
            :disabled="lockButtons || isImporting || importableCount === 0"
            class="w-full sm:w-[220px] inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors duration-200"
            @click="$emit('perform-import')"
            title="Import selected features"
        >
          <span class="inline-flex items-center justify-center w-full">
            <Loader v-if="isImporting" size="sm" layout="inline" :showMessage="false" color="white" />
            <ArrowUpTrayIcon v-else class="w-4 h-4 mr-2" />
            <span class="inline-block" style="min-width: 140px; text-align: center;">
              {{ isImporting ? `Importing ${importableCount} Feature${importableCount === 1 ? '' : 's'}...` : `Import ${importableCount} Feature${importableCount === 1 ? '' : 's'}` }}
            </span>
          </span>
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import Loader from "@/components/parts/Loader.vue";
import ToggleButton from "@/components/parts/ToggleButton.vue";
import {PROCESSING_MESSAGES} from "@/assets/js/constants/processing-messages.js";
import { ExclamationTriangleIcon, ClipboardDocumentIcon, ExclamationCircleIcon, DocumentIcon, ChevronLeftIcon, ChevronRightIcon, MapIcon, ArrowDownTrayIcon, ArrowUpTrayIcon, CheckIcon, XMarkIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'ImportControls',
  components: {
    Loader,
    ToggleButton,
    ExclamationTriangleIcon,
    ClipboardDocumentIcon,
    ExclamationCircleIcon,
    DocumentIcon,
    ChevronLeftIcon,
    ChevronRightIcon,
    MapIcon,
    ArrowDownTrayIcon,
    ArrowUpTrayIcon,
    CheckIcon,
    XMarkIcon
  },
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
    fileDuplicate: {
      type: Object,
      default: () => ({
        status: null,
        originalFilename: null
      })
    },
    showDuplicateMessage: {
      type: Boolean,
      default: true
    },
    showActionButtons: {
      type: Boolean,
      default: true
    },
    errorMessage: {
      type: String,
      default: ''
    },
    saveStatus: {
      type: String,
      default: null
    },
    hideDuplicates: {
      type: Boolean,
      default: false
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
    processingFailedTitle() {
      return PROCESSING_MESSAGES.PROCESSING_FAILED_TITLE;
    },
    shouldShowActions() {
      return (
          this.isLoadingPage ||
          this.isImported ||
          this.fileDuplicate.status === 'duplicate_in_queue' ||
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

