<template>
  <div class="overflow-hidden">
    <!-- Bulk Import Controls -->
    <div v-if="filteredImportQueue.length > 0 && !combinedLoading" class="mb-4 flex items-center justify-between">
      <div class="flex items-center space-x-3">
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
        <button
          @click="bulkDelete"
          :disabled="selectedItems.size === 0 || isBulkDeleting"
          class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <svg v-if="isBulkDeleting" class="animate-spin -ml-1 mr-2 h-4 w-4" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
          </svg>
          <svg v-else class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
          </svg>
          {{ isBulkDeleting ? 'Deleting...' : `Delete ${selectedItems.size} Item${selectedItems.size === 1 ? '' : 's'}` }}
        </button>
      </div>
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
                Upload A File
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
              :disabled="item.imported || item.processing === true || (item.processing === false && item.feature_count === -1) || item.deleting"
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
            <span v-if="item.deleting" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-orange-100 text-orange-800">
              <svg class="animate-spin -ml-1 mr-1 h-3 w-3" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              Deleting
            </span>
            <span v-else-if="item.imported" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-green-100 text-green-800">
              <svg class="w-3 h-3 mr-1" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd"></path>
              </svg>
              Imported
            </span>
            <span v-else-if="item.processing_failed" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-red-100 text-red-800">
              <svg class="w-3 h-3 mr-1" fill="currentColor" viewBox="0 0 20 20">
                <path fill-rule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7 4a1 1 0 11-2 0 1 1 0 012 0zm-1-9a1 1 0 00-1 1v4a1 1 0 102 0V6a1 1 0 00-1-1z" clip-rule="evenodd"></path>
              </svg>
              Processing Failed
            </span>
            <span v-else-if="item.processing === true || (item.processing === false && item.feature_count === -1)" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-yellow-100 text-yellow-800">
              <svg class="animate-spin -ml-1 mr-1 h-3 w-3" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              Processing
            </span>
            <span v-else-if="item.duplicate_status === 'duplicate_in_queue' || item.duplicate_status === 'duplicate_imported'" class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-purple-100 text-purple-800">
              <svg class="w-3 h-3 mr-1" fill="currentColor" viewBox="0 0 20 20">
                <path d="M7 9a2 2 0 012-2h6a2 2 0 012 2v6a2 2 0 01-2 2H9a2 2 0 01-2-2V9z"></path>
                <path d="M5 3a2 2 0 00-2 2v6a2 2 0 002 2V5h8a2 2 0 00-2-2H5z"></path>
              </svg>
              Duplicate
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
              :disabled="item.deleting"
              class="inline-flex items-center px-3 py-1.5 border border-transparent text-xs font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-400 disabled:hover:bg-gray-400"
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
import {IMPORT_BULK_DELETE_URL} from "@/assets/js/import/url.js";
import {ImportQueueItem} from "@/assets/js/types/import-types";
import {getCookie} from "@/assets/js/auth.js";
import {realtimeSocket} from "@/assets/js/websocket/realtimeSocket.js";

export default {
  props: {
    isLoading: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    ...mapState(["userInfo", "importQueue", "websocketConnected"]),
    filteredImportQueue() {
      // Filter out items that have been locally deleted and add deleting state
      return this.importQueue
        .filter(item => !this.deletedItems.has(item.id))
        .map(item => ({
          ...item,
          deleting: this.deletingItems.has(item.id)
        }));
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
      isBulkDeleting: false, // Track bulk delete state
      refreshInterval: null, // Auto-refresh interval
      deletingItems: new Set(), // Track items currently being deleted
      deleteJobIds: new Map(), // Track delete job IDs for each item
    }
  },
  watch: {
    isIndeterminate(newVal) {
      if (this.$refs.selectAllCheckbox) {
        this.$refs.selectAllCheckbox.indeterminate = newVal;
      }
    },
    websocketConnected(newVal) {
      if (newVal) {
        // WebSocket connected, subscribe to delete job events
        this.subscribeToDeleteJobEvents();
      }
    }
  },
  methods: {
    subscribeToRefreshMutation() {
      this.$store.subscribe((mutation, state) => {
        if (mutation.type === 'triggerImportQueueRefresh') {
          // Only refresh if we're not in the middle of an auto-refresh cycle
          // This prevents duplicate API calls when the parent component is already refreshing
          if (!this.isRefreshing) {
            this.refreshData();
          }
        }
      });
    },
    async refreshData() {
      console.log("IMPORT QUEUE: refreshing")
      await this.fetchQueueList()
    },
    async fetchQueueList() {
      // This method is now only used for manual refresh
      // WebSocket handles real-time updates
      if (this.isRefreshing) {
        return
      }

      this.isRefreshing = true
      this.internalLoading = true

      try {
        // Request refresh from WebSocket
        realtimeSocket.requestRefresh('import_job')
      } catch (error) {
        console.error('Error requesting queue refresh:', error)
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
        // Mark item as deleting
        this.deletingItems.add(item.id);
        
        // Force reactivity update
        this.$forceUpdate();

        try {
          const response = await axios.delete('/api/data/item/import/delete/' + item.id, {
            headers: {
              'X-CSRFToken': getCookie('csrftoken')
            }
          });

          if (response.data.success && response.data.job_id) {
            // Store the job ID for tracking
            this.deleteJobIds.set(item.id, response.data.job_id);
            console.log(`Delete job started for item ${item.id}: ${response.data.job_id}`);
          } else {
            throw new Error(response.data.msg || "server reported failure");
          }

        } catch (error) {
          console.error(`Failed to start delete job for item ${item.id}:`, error);
          alert(`Failed to delete ${item.id}: ${error.message}`);
          // Remove from deleting items set to restore the item if deletion failed
          this.deletingItems.delete(item.id);
          this.deleteJobIds.delete(item.id);
          this.$forceUpdate();
        }
      }
    },
    clearDeletedItems() {
      // Clear the deleted items list when navigating away
      this.deletedItems.clear();
      this.deletedItemTimeouts.clear();
      this.deletingItems.clear();
      this.deleteJobIds.clear();
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
        // Select items that are not imported, not currently processing, and not being deleted
        if (!item.imported && !(item.processing === true || (item.processing === false && item.feature_count === -1)) && !item.deleting) {
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

      // Double-check that we're not trying to import processing, already imported, or failed items
      const validItems = [];
      const invalidItems = [];

      this.selectedItems.forEach(itemId => {
        const item = this.filteredImportQueue.find(i => i.id === itemId);
        if (item && !item.imported && !item.processing_failed && !(item.processing === true || (item.processing === false && item.feature_count === -1))) {
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
        alert('No valid items selected for import. Processing, already imported, or failed items cannot be bulk imported.');
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
    async bulkDelete() {
      if (this.selectedItems.size === 0) {
        return;
      }

      // Check for items that cannot be deleted (imported items or items being deleted)
      const invalidItems = [];
      this.selectedItems.forEach(itemId => {
        const item = this.filteredImportQueue.find(i => i.id === itemId);
        if (item && (item.imported || item.deleting)) {
          invalidItems.push(itemId);
        }
      });

      // Remove invalid items from selection
      invalidItems.forEach(itemId => {
        this.selectedItems.delete(itemId);
      });

      if (this.selectedItems.size === 0) {
        alert('No valid items selected for deletion. Imported items or items being deleted cannot be bulk deleted.');
        this.updateSelectAllCheckbox();
        return;
      }

      const selectedCount = this.selectedItems.size;
      const confirmMessage = `Are you sure you want to delete ${selectedCount} item${selectedCount === 1 ? '' : 's'}? This action cannot be undone.`;

      if (!window.confirm(confirmMessage)) {
        return;
      }

      this.isBulkDeleting = true;
      const csrftoken = getCookie('csrftoken');
      const itemIds = Array.from(this.selectedItems);

      try {
        const response = await axios.delete(IMPORT_BULK_DELETE_URL, {
          data: { ids: itemIds },
          headers: {
            'X-CSRFToken': csrftoken
          }
        });

        if (response.data.success && response.data.job_ids) {
          // Mark all items as deleting
          itemIds.forEach(itemId => {
            this.deletingItems.add(itemId);
          });

          // Store job IDs for tracking
          response.data.job_ids.forEach((jobId, index) => {
            if (index < itemIds.length) {
              this.deleteJobIds.set(itemIds[index], jobId);
            }
          });

          // Clear selection
          this.clearSelection();

          // Show success message
          alert(`Started deletion of ${response.data.started_count} item${response.data.started_count === 1 ? '' : 's'}!`);

          // Force reactivity update
          this.$forceUpdate();
        } else {
          throw new Error(response.data.msg || 'Unknown error occurred');
        }

      } catch (error) {
        console.error('Bulk delete error:', error);
        alert(`Bulk delete failed: ${error.response?.data?.msg || error.message}`);
      } finally {
        this.isBulkDeleting = false;
      }
    },
    setupRealtimeConnection() {
      // The realtime connection is now managed globally in App.vue
      // We just need to handle the initial loading state
      this.hasInitiallyLoaded = true
      this.internalLoading = false
    },
    subscribeToDeleteJobEvents() {
      // Subscribe to WebSocket events for delete jobs
      if (this.$store.state.websocketConnected) {
        // Listen for delete job events
        this.$store.subscribe((mutation, state) => {
          if (mutation.type === 'websocket/delete_job_started') {
            this.handleDeleteJobStarted(mutation.payload);
          } else if (mutation.type === 'websocket/delete_job_status_updated') {
            this.handleDeleteJobStatusUpdated(mutation.payload);
          } else if (mutation.type === 'websocket/delete_job_completed') {
            this.handleDeleteJobCompleted(mutation.payload);
          } else if (mutation.type === 'websocket/delete_job_failed') {
            this.handleDeleteJobFailed(mutation.payload);
          }
        });
      }
    },
    handleDeleteJobStarted(event) {
      console.log('Delete job started:', event);
      // The item should already be marked as deleting, but ensure it is
      if (event.data && event.data.item_id) {
        this.deletingItems.add(event.data.item_id);
        this.$forceUpdate();
      }
    },
    handleDeleteJobStatusUpdated(event) {
      console.log('Delete job status updated:', event);
      // Update progress if needed (optional for now)
    },
    handleDeleteJobCompleted(event) {
      console.log('Delete job completed:', event);
      if (event.data && event.data.item_id) {
        // Remove from deleting items and add to deleted items
        this.deletingItems.delete(event.data.item_id);
        this.deleteJobIds.delete(event.data.item_id);
        this.deletedItems.add(event.data.item_id);
        this.deletedItemTimeouts.set(event.data.item_id, 0);
        this.$forceUpdate();
        
        // Manually refresh the table to ensure it updates
        this.refreshData();
      }
    },
    handleDeleteJobFailed(event) {
      console.log('Delete job failed:', event);
      if (event.data && event.data.item_id) {
        // Remove from deleting items to restore the item
        this.deletingItems.delete(event.data.item_id);
        this.deleteJobIds.delete(event.data.item_id);
        this.$forceUpdate();
        alert(`Delete failed for item ${event.data.item_id}: ${event.data.error_message || 'Unknown error'}`);
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

    // Setup realtime connection (now managed globally)
    this.setupRealtimeConnection()

    // Subscribe to manual refresh mutations
    this.subscribeToRefreshMutation()

    // Subscribe to WebSocket events for delete jobs
    if (this.$store.state.websocketConnected) {
      this.subscribeToDeleteJobEvents();
    }
  },
  mounted() {
    // WebSocket is already connected in created()
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
