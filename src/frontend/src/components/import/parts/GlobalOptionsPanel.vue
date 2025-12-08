<template>
  <div v-if="hasFeatures && !isLoading && !isProcessing" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
    <h3 class="text-sm font-semibold text-gray-900 mb-3">Global Options</h3>
    <div class="flex flex-col sm:flex-row sm:items-center gap-4">
      <!-- Import Custom Icons Toggle -->
      <div class="flex items-center space-x-3">
        <ToggleButton
          :model-value="importCustomIcons"
          label="Import custom icons for all features"
          :disabled="isDisabled"
          size="md"
          @update:model-value="$emit('update:import-custom-icons', $event)"
        />
        <label 
          class="text-sm font-medium text-gray-700 cursor-pointer whitespace-nowrap" 
          @click="!isDisabled && $emit('update:import-custom-icons', !importCustomIcons)"
        >
          Import custom icons for all features
        </label>
      </div>

      <!-- Buttons Section -->
      <div class="flex items-center gap-2 sm:ml-auto">
        <!-- Recheck Duplicates Button -->
        <button
          :disabled="isDisabled || isRecheckingDuplicates"
          class="inline-flex items-center justify-center px-4 py-2 border border-blue-200 text-sm font-medium rounded-md text-blue-700 bg-blue-50 hover:bg-blue-100 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-300 disabled:bg-gray-100 disabled:hover:bg-gray-100 disabled:cursor-not-allowed transition-colors duration-200 whitespace-nowrap"
          @click="$emit('recheck-duplicates')"
          title="Recheck for duplicate features"
        >
          <Loader v-if="isRecheckingDuplicates" size="sm" layout="inline" :showMessage="false" color="#1d4ed8" />
          {{ isRecheckingDuplicates ? 'Rechecking...' : 'Recheck Duplicates' }}
        </button>

        <!-- Bulk Operations Button -->
        <button
          :disabled="isDisabled"
          class="inline-flex items-center justify-center px-4 py-2 border border-blue-200 text-sm font-medium rounded-md text-blue-700 bg-blue-50 hover:bg-blue-100 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-300 disabled:bg-gray-100 disabled:hover:bg-gray-100 disabled:cursor-not-allowed transition-colors duration-200 whitespace-nowrap"
          @click="$emit('open-bulk-operations')"
          title="Bulk Operations"
        >
          Bulk Operations
        </button>
        <RectangleStackIcon v-if="hasBulkOperationsConfigured" class="w-5 h-5 text-blue-500 flex-shrink-0" />
      </div>
    </div>
  </div>
</template>

<script>
import ToggleButton from '@/components/parts/ToggleButton.vue';
import Loader from '@/components/parts/Loader.vue';
import { RectangleStackIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'GlobalOptionsPanel',
  components: {
    ToggleButton,
    Loader,
    RectangleStackIcon
  },
  props: {
    hasFeatures: {
      type: Boolean,
      default: false
    },
    isLoading: {
      type: Boolean,
      default: false
    },
    isProcessing: {
      type: Boolean,
      default: false
    },
    importCustomIcons: {
      type: Boolean,
      default: false
    },
    lockButtons: {
      type: Boolean,
      default: false
    },
    isImporting: {
      type: Boolean,
      default: false
    },
    isSaving: {
      type: Boolean,
      default: false
    },
    isImported: {
      type: Boolean,
      default: false
    },
    isRecheckingDuplicates: {
      type: Boolean,
      default: false
    },
    hasBulkOperationsConfigured: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    isDisabled() {
      return this.lockButtons || this.isImporting || this.isSaving || this.isImported;
    }
  },
  emits: ['update:import-custom-icons', 'recheck-duplicates', 'open-bulk-operations']
};
</script>

