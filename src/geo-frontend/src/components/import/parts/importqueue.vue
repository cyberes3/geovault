<template>
  <div class="overflow-hidden">
    <!-- Bulk Import Controls -->
    <div v-if="filteredImportQueue.length > 0 && !combinedLoading" class="mb-4 flex items-center justify-between">
      <button
        @click="bulkImport"
        :disabled="selectedItems.size === 0 || isBulkImporting"
        class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
      >
        <svg v-if="isBulkImporting" class="animate-spin -ml-1 mr-2 h-4 w-4" fill="none" viewBox="0 0 24 24">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
        </svg>
        <svg v-else class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
        </svg>
        {{ isBulkImporting ? 'Importing...' : `Import ${selectedItems.size} Item${selectedItems.size === 1 ? '' : 's'}` }}
      </button>
    </div>

    <table class="min-w-full divide-y divide-gray-200">
      <thead class="bg-gray-50">
        <tr>
          <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">
            <input
              ref="selectAllCheckbox"
              type="checkbox"
              :checked="selectedItems.size === filteredImportQueue.length && filteredImportQueue.length > 0"
              @change="toggleSelectAll"
              class="h-4 w-4 text-blue-600 focus:ring-blue-500 border-gray-300 rounded"
            />
          </th>
          <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">File Name</th>
          <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Features</th>
          <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
          <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</th>
        </tr>
      </thead>
      <tbody class="bg-white divide-y divide-gray-200">
        <!-- Loading placeholders -->
        <tr v-for="n in 5" v-if="combinedLoading" :key="`loading-${n}`" class="animate-pulse">
          <td class="px-6 py-4 whitespace-nowrap">
            <div class="w-4 h-4 bg-gray-200 rounded"></div>
          </td>
          <td class="px-6 py-4 whitespace-nowrap">
            <div class="flex items-center">
              <div class="w-8 h-8 bg-gray-200 rounded-lg"></div>
              <div class="ml-4 w-32 h-4 bg-gray-200 rounded"></div>
            </div>
          </td>
          <td class="px-6 py-4 whitespace-nowrap text-center">
            <div class="w-16 h-4 bg-gray-200 rounded mx-auto"></div>
          </td>
          <td class="px-6 py-4 whitespace-nowrap text-center">
            <div class="w-20 h-6 bg-gray-200 rounded mx-auto"></div>
          </td>
          <td class="px-6 py-4 whitespace-nowrap text-center">
            <div class="w-16 h-6 bg-gray-200 rounded mx-auto"></div>
          </td>
        </tr>

        <!-- Empty state when no files are uploaded -->
        <tr v-if="!combinedLoading && filteredImportQueue.length === 0 && hasInitiallyLoaded">
          <td colspan="5" class="px-6 py-12 text-center">
            <div class="flex flex-col items-center">
              <div class="w-16 h-16 bg-gray-100 rounded-full flex items-center justify-center mb-4">
                <svg class="w-8 h-8 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
                </svg>
              </div>
              <h3 class="text-lg font-medium text-gray-900 mb-2">No files uploaded yet</h3>
              <p class="text-gray-500 mb-6 max-w-sm">
                Get started by uploading your first geospatial data file. Supported formats include KMZ/KML and GeoJSON.
              </p>
              <router-link
                to="/import/upload"
                class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors duration-200"
              >
                <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
                </svg>
                Upload Your First File
              </router-link>
            </div>
          </td>
        </tr>

        <!-- Actual data rows -->
        <tr v-for="(item, index) in filteredImportQueue" :key="`item-${index}`" class="hover:bg-gray-50">
          <td class="px-6 py-4 whitespace-nowrap">
            <input
              type="checkbox"
              :checked="selectedItems.has(item.id)"
              @change="toggleItemSelection(item.id)"
              :disabled="item.imported || item.processing === true || (item.processing === false && item.feature_count === -1)"
              class="h-4 w-4 text-blue-600 focus:ring-blue-500 border-gray-300 rounded disabled:opacity-50 disabled:cursor-not-allowed"
            />
          </td>
          <td class="px-6 py-4 whitespace-nowrap">
            <div class="flex items-center">
              <div class="flex-shrink-0">
                <div class="w-8 h-8 bg-blue-100 rounded-lg flex items-center justify-center">
                  <svg class="w-4 h-4 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
                  </svg>
                </div>
              </div>
              <div class="ml-4">
                <div class="text-sm font-medium text-gray-900">
                  <a :href="`/#/import/process/${item.id}`" class="text-blue-600 hover:text-blue-900">
                    {{ item.original_filename }}
                  </a>
                </div>
              </div>
            </div>
          </td>
          <td class="px-6 py-4 whitespace-nowrap text-center text-sm text-gray-900">
            <span v-if="item.processing === true || (item.processing === false && item.feature_count === -1)" class="text-gray-400">
              -
            </span>
            <span v-else class="font-medium">{{ item.feature_count }}</span>
          </td>
          <td class="px-6 py-4 whitespace-nowrap text-center">
            <span v-if="item.imported" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800">
              <svg class="w-3 h-3 mr-1" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd"></path>
              </svg>
              Imported
            </span>
            <span v-else-if="item.processing === true || (item.processing === false && item.feature_count === -1)" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-100 text-yellow-800">
              <svg class="animate-spin -ml-1 mr-1 h-3 w-3" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              Processing
            </span>
            <span v-else class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
              <svg class="w-3 h-3 mr-1" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd"></path>
              </svg>
              Ready
            </span>
          </td>
          <td class="px-6 py-4 whitespace-nowrap text-center text-sm font-medium">
            <button
              class="inline-flex items-center px-3 py-1.5 border border-transparent text-xs font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 transition-colors duration-200"
              @click="deleteItem(item, index)"
            >
              <svg class="w-3 h-3 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
              </svg>
              Delete
            </button>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script>
import {mapState} from "vuex";
import {authMixin} from "@/assets/js/authMixin.js";
import axios from "axios";
import {IMPORT_QUEUE_LIST_URL} from "@/assets/js/import/url.js";
import {ImportQueueItem} from "@/assets/js/types/import-types";
import {getCookie} from "@/assets/js/auth.js";

export default {
  props: {
    isLoading: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    ...mapState(["userInfo", "importQueue"]),
    filteredImportQueue() {
      // Filter out items that have been locally deleted
      return this.importQueue.filter(item => !this.deletedItems.has(item.id));
    },
    combinedLoading() {
      // Show loading placeholders only when:
      // 1. We're actually loading (isLoading or internalLoading is true)
      // 2. We haven't initially loaded yet (hasInitiallyLoaded is false)
      // 3. AND we don't have any data in the store yet
      return (this.isLoading || this.internalLoading) && !this.hasInitiallyLoaded && (!this.importQueue || this.importQueue.length === 0);
    },
    isIndeterminate() {
      return this.selectedItems.size > 0 && this.selectedItems.size < this.filteredImportQueue.length;
    }
  },
  components: {},
  mixins: [authMixin],
  data() {
    return {
      internalLoading: true,
      hasInitiallyLoaded: false,
      isRefreshing: false,
      deletedItems: new Set(), // Track locally deleted items to prevent flicker
      deletedItemTimeouts: new Map(), // Track how many refresh cycles each deleted item has been gone
      selectedItems: new Set(), // Track selected items for bulk import
      isBulkImporting: false, // Track bulk import state
    }
  },
  watch: {
    isIndeterminate(newVal) {
      if (this.$refs.selectAllCheckbox) {
        this.$refs.selectAllCheckbox.indeterminate = newVal;
      }
    }
  },
  methods: {
    subscribeToRefreshMutation() {
      this.$store.subscribe((mutation, state) => {
        if (mutation.type === 'triggerImportQueueRefresh') {
          this.refreshData();
        }
      });
    },
    async refreshData() {
      console.log("IMPORT QUEUE: refreshing")
      await this.fetchQueueList()
    },
    async fetchQueueList() {
      // Prevent overlapping requests
      if (this.isRefreshing) {
        return
      }

      this.isRefreshing = true
      this.internalLoading = true

      try {
        const response = await axios.get(IMPORT_QUEUE_LIST_URL)
        const ourImportQueue = response.data.data.map((item) => new ImportQueueItem(item))

        // Check for items that should be restored (still exist on server after 3 refresh cycles)
        this.checkForRestoreItems(ourImportQueue);

        this.$store.commit('setImportQueue', ourImportQueue)
        this.hasInitiallyLoaded = true

        // Clear selection for items that no longer exist
        this.selectedItems.forEach(itemId => {
          if (!ourImportQueue.some(item => item.id === itemId)) {
            this.selectedItems.delete(itemId);
          }
        });
        this.updateSelectAllCheckbox();
      } catch (error) {
        console.error('Error fetching queue list:', error)
      } finally {
        this.internalLoading = false
        this.isRefreshing = false
      }
    },
    checkForRestoreItems(serverQueue) {
      // Increment timeout counters for all deleted items
      for (const [itemId, timeoutCount] of this.deletedItemTimeouts.entries()) {
        this.deletedItemTimeouts.set(itemId, timeoutCount + 1);

        // Check if this item still exists on the server
        const stillExistsOnServer = serverQueue.some(item => item.id === itemId);

        if (stillExistsOnServer && timeoutCount >= 2) { // 3 refresh cycles (0, 1, 2)
          // Item still exists on server after 3 refresh cycles, restore it
          console.log(`Restoring item ${itemId} - still exists on server after 3 refresh cycles`);
          this.deletedItems.delete(itemId);
          this.deletedItemTimeouts.delete(itemId);
        } else if (!stillExistsOnServer) {
          // Item was successfully deleted from server, clean up tracking
          this.deletedItems.delete(itemId);
          this.deletedItemTimeouts.delete(itemId);
        }
      }
    },
    async deleteItem(item, index) {
      if (window.confirm(`Delete "${item.original_filename}" (#${item.id})`)) {
        // Add to deleted items set to prevent flicker during auto-refresh
        this.deletedItems.add(item.id);
        // Initialize timeout counter for this item
        this.deletedItemTimeouts.set(item.id, 0);

        try {
          const response = await axios.delete('/api/data/item/import/delete/' + item.id, {
            headers: {
              'X-CSRFToken': getCookie('csrftoken')
            }
          });

          if (!response.data.success) {
            throw new Error("server reported failure");
          }

          // Keep the item in deletedItems set - it will be filtered out until auto-refresh removes it from server
        } catch (error) {
          alert(`Failed to delete ${item.id}: ${error.message}`);
          // Remove from deleted items set to restore the item if deletion failed
          this.deletedItems.delete(item.id);
          this.deletedItemTimeouts.delete(item.id);
        }
      }
    },
    clearDeletedItems() {
      // Clear the deleted items list when navigating away
      this.deletedItems.clear();
      this.deletedItemTimeouts.clear();
    },
    // Bulk import methods
    updateSelectAllCheckbox() {
      this.$nextTick(() => {
        if (this.$refs.selectAllCheckbox) {
          this.$refs.selectAllCheckbox.indeterminate = this.isIndeterminate;
        }
      });
    },
    toggleItemSelection(itemId) {
      if (this.selectedItems.has(itemId)) {
        this.selectedItems.delete(itemId);
      } else {
        this.selectedItems.add(itemId);
      }
      this.updateSelectAllCheckbox();
    },
    toggleSelectAll() {
      if (this.selectedItems.size === this.filteredImportQueue.length) {
        this.clearSelection();
      } else {
        this.selectAll();
      }
    },
    selectAll() {
      this.filteredImportQueue.forEach(item => {
        // Only select items that are ready for import (not imported, not processing)
        if (!item.imported && !(item.processing === true || (item.processing === false && item.feature_count === -1))) {
          this.selectedItems.add(item.id);
        }
      });
      this.updateSelectAllCheckbox();
    },
    clearSelection() {
      this.selectedItems.clear();
      this.updateSelectAllCheckbox();
    },
    async bulkImport() {
      if (this.selectedItems.size === 0) {
        return;
      }

      // Double-check that we're not trying to import processing or already imported items
      const validItems = [];
      const invalidItems = [];

      this.selectedItems.forEach(itemId => {
        const item = this.filteredImportQueue.find(i => i.id === itemId);
        if (item && !item.imported && !(item.processing === true || (item.processing === false && item.feature_count === -1))) {
          validItems.push(itemId);
        } else {
          invalidItems.push(itemId);
        }
      });

      // Remove invalid items from selection
      invalidItems.forEach(itemId => {
        this.selectedItems.delete(itemId);
      });

      if (this.selectedItems.size === 0) {
        alert('No valid items selected for import. Processing or already imported items cannot be bulk imported.');
        this.updateSelectAllCheckbox();
        return;
      }

      const selectedCount = this.selectedItems.size;
      const confirmMessage = `Are you sure you want to import ${selectedCount} item${selectedCount === 1 ? '' : 's'} without reviewing them?`;

      if (!window.confirm(confirmMessage)) {
        return;
      }

      this.isBulkImporting = true;
      const csrftoken = getCookie('csrftoken');
      let successCount = 0;
      let errorCount = 0;
      const errors = [];

      try {
        // Import each selected item
        for (const itemId of this.selectedItems) {
          try {
            const response = await axios.post(`/api/data/item/import/perform/${itemId}`, [], {
              headers: {
                'X-CSRFToken': csrftoken
              }
            });

            if (response.data.success) {
              successCount++;
            } else {
              errorCount++;
              errors.push(`Item ${itemId}: ${response.data.msg}`);
            }
          } catch (error) {
            errorCount++;
            errors.push(`Item ${itemId}: ${error.message}`);
          }
        }

        // Show results
        if (errorCount === 0) {
          alert(`Successfully imported ${successCount} item${successCount === 1 ? '' : 's'}!`);
        } else {
          const errorMessage = `Imported ${successCount} item${successCount === 1 ? '' : 's'} successfully, but ${errorCount} failed:\n\n${errors.join('\n')}`;
          alert(errorMessage);
        }

        // Refresh the queue and clear selection
        this.$store.dispatch('refreshImportQueue');
        this.clearSelection();

      } catch (error) {
        alert(`Bulk import failed: ${error.message}`);
      } finally {
        this.isBulkImporting = false;
      }
    },
  },
  async created() {
    // If we already have data in the store, mark as initially loaded
    // This prevents showing loading placeholders when navigating back with browser buttons
    if (this.importQueue && this.importQueue.length > 0) {
      this.hasInitiallyLoaded = true;
      this.internalLoading = false;
    }

    await this.fetchQueueList()
    this.subscribeToRefreshMutation()
  },
  beforeDestroy() {
    // Clear deleted items when component is destroyed (user navigates away)
    this.clearDeletedItems();
    // Clear selected items when component is destroyed
    this.clearSelection();
  },
}
</script>

<style scoped>

</style>
