<template>
  <div class="overflow-hidden">
    <!-- Help text for clickable items -->
    <div v-if="filteredImportQueue.length > 0 && !combinedLoading" class="mb-3">
      <p class="text-sm text-gray-600">
        Files that have been uploaded and are ready for processing. Click on any item in the table below to open the import processing page where you can review and edit features before importing.
      </p>
    </div>
    <!-- Bulk Import Controls -->
    <div v-if="filteredImportQueue.length > 0 && !combinedLoading" class="mb-4">
      <div class="flex flex-col sm:flex-row sm:items-center space-y-2 sm:space-y-0 sm:space-x-3">
        <button
          @click="bulkImport"
          :disabled="validImportableCount === 0 || isBulkImporting || isBulkDeleting"
          class="w-full sm:w-auto inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
          title="Import all selected items"
        >
          <Loader v-if="isBulkImporting" size="sm" layout="inline" :showMessage="false" />
          <ArrowUpTrayIcon v-else class="w-4 h-4 mr-2" />
          {{ isBulkImporting ? 'Importing...' : `Import ${validImportableCount} Item${validImportableCount === 1 ? '' : 's'}` }}
        </button>
        <button
          @click="bulkDelete"
          :disabled="selectedItems.size === 0 || isBulkDeleting || isBulkImporting"
          class="w-full sm:w-auto inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
          title="Delete all selected items"
        >
          <Loader v-if="isBulkDeleting" size="sm" layout="inline" :showMessage="false" color="white" />
          <TrashIcon v-else class="w-4 h-4 mr-2" />
          {{ isBulkDeleting ? 'Deleting...' : `Delete ${selectedItems.size} Item${selectedItems.size === 1 ? '' : 's'}` }}
        </button>
      </div>
    </div>

    <div class="flex flex-col">
      <!-- Header Row (Desktop only) -->
      <div class="hidden md:flex bg-gray-50 px-3 py-3 sm:px-6 sm:py-3 border-b border-gray-200">
        <div class="w-12 text-left">
          <input
            type="checkbox"
            ref="selectAllCheckbox"
            :checked="selectedItems.size === filteredImportQueue.length && filteredImportQueue.length > 0"
            @change="handleSelectAllToggle($event.target.checked)"
            class="checkbox-custom"
          />
        </div>
        <div class="flex-1 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">File Name</div>
        <div class="flex-1 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Features</div>
        <div class="flex-1 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Status</div>
        <div class="flex-1 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</div>
      </div>

      <!-- Items -->
      <div class="flex flex-col space-y-3 md:space-y-0 md:divide-y md:divide-gray-200">
        <!-- Loading placeholders -->
        <div
          v-for="n in 3"
          v-if="combinedLoading"
          :key="`loading-${n}`"
          class="flex flex-col md:flex-row md:items-center p-3 md:p-0 md:px-3 md:py-3 lg:px-6 lg:py-4 border border-gray-200 md:border-0 rounded-lg md:rounded-none animate-pulse"
        >
          <div class="w-full md:w-12 mb-2 md:mb-0 flex items-center justify-between">
            <div class="w-4 h-4 bg-gray-200 rounded"></div>
            <div class="md:hidden w-16 h-5 bg-gray-200 rounded-full"></div>
          </div>
          <div class="flex-1 mb-2 md:mb-0">
            <div class="flex items-center">
              <div class="w-8 h-8 bg-gray-200 rounded-lg"></div>
              <div class="ml-4 w-32 h-4 bg-gray-200 rounded"></div>
            </div>
          </div>
          <div class="flex-1 mb-2 md:mb-0 md:text-center">
            <div class="flex items-center md:justify-center">
              <div class="md:hidden w-16 h-4 bg-gray-200 rounded mr-2"></div>
              <div class="w-8 h-4 bg-gray-200 rounded md:mx-auto"></div>
            </div>
          </div>
          <div class="hidden md:flex flex-1 items-center justify-center">
            <div class="w-20 h-6 bg-gray-200 rounded"></div>
          </div>
          <div class="flex-1 md:text-center">
            <div class="flex items-center justify-start md:justify-center space-x-2">
              <div class="w-16 h-7 bg-gray-200 rounded"></div>
              <div class="w-16 h-7 bg-gray-200 rounded"></div>
            </div>
          </div>
        </div>

        <!-- Empty state when no files are uploaded -->
        <div v-if="!combinedLoading && filteredImportQueue.length === 0 && hasInitiallyLoaded" class="py-12 text-center">
          <div class="flex flex-col items-center">
            <h3 class="text-lg font-medium text-gray-900 mb-2">No files uploaded yet</h3>
            <p class="text-gray-500 mb-6 max-w-sm">
              Get started by uploading your first geospatial data file. Supported formats include KMZ/KML and GeoJSON.
            </p>
            <router-link
              v-if="!isOnUploadPage"
              to="/import/upload"
              class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors duration-200"
            >
              <ArrowUpTrayIcon class="w-4 h-4 mr-2" />
              Upload A File
            </router-link>
          </div>
        </div>

        <!-- Actual data rows -->
        <div
          v-for="(item, index) in filteredImportQueue"
          :key="`item-${index}`"
          :class="[
            'flex flex-col md:flex-row md:items-center p-3 md:p-0 md:px-3 md:py-3 lg:px-6 lg:py-4 border border-gray-200 md:border-0 rounded-lg md:rounded-none hover:bg-gray-50 transition-colors',
            (item.deleting || item.importing) ? 'opacity-60 bg-gray-50' : ''
          ]"
        >
          <!-- Checkbox + Status (mobile) / Checkbox only (desktop) -->
          <div class="w-full md:w-12 mb-2 md:mb-0 flex items-center justify-between">
            <input
              type="checkbox"
              :checked="selectedItems.has(item.id)"
              @change="handleItemToggle(item.id, $event.target.checked)"
              :disabled="item.imported || item.processing === true || (item.processing === false && item.feature_count === -1) || item.deleting || item.importing"
              class="checkbox-custom"
            />
            <!-- Mobile-only status badge on far right -->
            <div class="md:hidden">
              <StatusBadge :item="item" />
            </div>
          </div>
          <!-- Filename -->
          <div class="flex-1 mb-2 md:mb-0">
            <div class="text-base sm:text-lg font-medium text-gray-900 break-words">
              <!-- Disable link for duplicates in queue or when this specific item is being imported/deleted -->
              <router-link v-if="item.file_duplicate?.status !== 'duplicate_in_queue' && !item.deleting && !item.importing"
                 :to="`/import/process/${item.id}`"
                 class="text-blue-500 hover:text-blue-700">
                {{ item.original_filename }}
              </router-link>
              <span v-else class="text-gray-500 cursor-not-allowed">
                {{ item.original_filename }}
              </span>
            </div>
          </div>
          <!-- Features -->
          <div class="flex-1 mb-2 md:mb-0 md:text-center text-xs sm:text-sm text-gray-900">
            <div class="flex items-center md:justify-center">
              <span class="md:hidden text-[10px] font-semibold tracking-wide text-gray-900 mr-2 uppercase leading-none">Features</span>
              <span v-if="item.processing === true || (item.processing === false && item.feature_count === -1)" class="text-gray-400">
                -
              </span>
              <span v-else-if="item.processing_failed" class="text-red-600 flex items-center">
                <XMarkIcon class="w-4 h-4" />
              </span>
              <span v-else class="font-medium text-gray-900">{{ item.feature_count }}</span>
            </div>
          </div>
          <!-- Status (desktop only - mobile shown in checkbox row) -->
          <div class="hidden md:flex flex-1 items-center justify-center">
            <StatusBadge :item="item" />
          </div>
          <!-- Actions -->
          <div class="flex-1 md:text-center text-sm font-medium">
            <div class="flex flex-col sm:flex-row items-stretch sm:items-center justify-stretch sm:justify-center space-y-2 sm:space-y-0 sm:space-x-2">
              <button
                :disabled="item.imported || item.processing_failed || (item.processing === true || (item.processing === false && item.feature_count === -1)) || item.file_duplicate?.status === 'duplicate_in_queue' || item.deleting || item.importing"
                class="w-full sm:w-auto inline-flex items-center justify-center px-3 py-1.5 border border-green-200 text-sm font-medium rounded-md text-green-700 bg-green-50 hover:bg-green-100 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-300 transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-100 disabled:hover:bg-gray-100"
                @click="importItem(item, index)"
                title="Import this item"
              >
                <ArrowUpTrayIcon class="w-4 h-4 mr-2" />
                Import
              </button>
              <button
                :disabled="item.deleting || item.importing"
                class="w-full sm:w-auto inline-flex items-center justify-center px-3 py-1.5 border border-red-200 text-sm font-medium rounded-md text-red-700 bg-red-50 hover:bg-red-100 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-300 transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-100 disabled:hover:bg-gray-100"
                @click="deleteItem(item, index)"
                title="Delete this item"
              >
                <TrashIcon class="w-4 h-4 mr-2" />
                Delete
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {mapState} from "vuex";
import axios from "axios";
import {ImportQueueItem} from "@/assets/js/types/import-types";
import {getCookie} from "@/assets/js/auth.js";
import {realtimeSocket} from "@/assets/js/websocket/realtimeSocket.js";
import { toggleSetItem } from "@/assets/js/toggle-utils.js";
import Loader from "@/components/parts/Loader.vue";
import StatusBadge from "@/components/import/parts/StatusBadge.vue";
import { ArrowUpTrayIcon, TrashIcon, XMarkIcon } from '@heroicons/vue/24/outline';

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
      // Filter out items that have been locally deleted and add deleting/importing state
      return this.importQueue
        .slice()
        .filter(item => !this.deletedItems.has(item.id))
        .map(item => ({
          ...item,
          deleting: this.deletingItems.has(item.id),
          importing: this.importingItems.has(item.id)
        }))
        // Sort so the oldest items appear at the top of the table
        .sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
    },
    combinedLoading() {
      // Show loading placeholders only when:
      // 1. We're actually loading (isLoading or internalLoading is true)
      // 2. We haven't initially loaded yet (hasInitiallyLoaded is false)
      // 3. AND we don't have any data in the store yet
      return (this.isLoading || this.internalLoading) && !this.hasInitiallyLoaded && (!this.importQueue || this.importQueue.length === 0);
    },
    validImportableCount() {
      // Count items that can actually be imported (same logic as bulkImport)
      let count = 0;
      this.selectedItems.forEach(itemId => {
        const item = this.filteredImportQueue.find(i => i.id === itemId);
        if (item && !item.imported && !item.processing_failed && !(item.processing === true || (item.processing === false && item.feature_count === -1)) && item.file_duplicate?.status !== 'duplicate_in_queue') {
          count++;
        }
      });
      return count;
    },
    isOnUploadPage() {
      // Check if we're on the /import/upload page
      return this.$route.path === '/import/upload';
    },
    isAnyOperationInProgress() {
      // Check if any bulk import or delete operation is in progress
      return this.isBulkImporting || this.isBulkDeleting;
    }
  },
  components: {
    Loader,
    StatusBadge,
    ArrowUpTrayIcon,
    TrashIcon
  },
  data() {
    return {
      internalLoading: true,
      hasInitiallyLoaded: false,
      isRefreshing: false,
      hasRequestedInitialLoad: false, // Track if we've already requested initial data load
      deletedItems: new Set(), // Track locally deleted items to prevent flicker
      deletedItemTimeouts: new Map(), // Track how many refresh cycles each deleted item has been gone
      selectedItems: new Set(), // Track selected items for bulk import
      isBulkImporting: false, // Track bulk import state
      isBulkDeleting: false, // Track bulk delete state
      refreshInterval: null, // Auto-refresh interval
      deletingItems: new Set(), // Track items currently being deleted
      importingItems: new Set(), // Track items currently being imported individually
      deleteJobIds: new Map(), // Track delete job IDs for each item
      bulkImportJobId: null, // Track current bulk import job ID
      bulkDeleteJobId: null, // Track current bulk delete job ID
      bulkJobHandlers: [], // Store handler references for cleanup
    }
  },
  watch: {
    websocketConnected(newVal) {
      if (newVal) {
        // WebSocket connected - delete job events are now handled directly by store actions
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
      this.hasRequestedInitialLoad = true // Mark that we've requested initial load

      try {
        // Request refresh from WebSocket
        realtimeSocket.requestRefresh('import_queue')
        // Don't set internalLoading = false here - keep it true until data arrives
        // The subscribeToImportQueueUpdates() method will set it to false when setImportQueue mutation is received
      } catch (error) {
        console.error('Error requesting queue refresh:', error)
        // Only set loading to false on error
        this.internalLoading = false
        this.hasRequestedInitialLoad = false // Reset on error so we can retry
      } finally {
        // Keep isRefreshing true until data arrives to prevent duplicate requests
        // It will be set to false when data arrives via subscribeToImportQueueUpdates
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
          this.deletedItems.delete(itemId);
          this.deletedItemTimeouts.delete(itemId);
        } else if (!stillExistsOnServer) {
          // Item was successfully deleted from server, clean up tracking
          this.deletedItems.delete(itemId);
          this.deletedItemTimeouts.delete(itemId);
        }
      }
    },
    async importItem(item, index) {
      if (item.imported || item.processing_failed || (item.processing === true || (item.processing === false && item.feature_count === -1)) || item.file_duplicate?.status === 'duplicate_in_queue') {
        return;
      }

      const confirmMessage = `Are you sure you want to import "${item.original_filename}" without reviewing it?`;
      if (!window.confirm(confirmMessage)) {
        return;
      }

      // Mark item as importing
      this.importingItems.add(item.id);
      this.$forceUpdate();

      const csrftoken = getCookie('csrftoken');

      try {
        const response = await axios.post(`/api/item/import/perform/${item.id}`, {}, {
          headers: {
            'X-CSRFToken': csrftoken
          }
        });

        if (response.status === 200) {
          alert(`Successfully imported "${item.original_filename}"!`);
          // Refresh the queue
          this.$store.dispatch('refreshImportQueue');
        } else {
          alert(`Failed to import: ${response.data.msg}`);
        }
      } catch (error) {
        console.error(`Failed to import item ${item.id}:`, error);
        alert(`Failed to import: ${error.message}`);
      } finally {
        // Remove from importing items
        this.importingItems.delete(item.id);
        this.$forceUpdate();
      }
    },
    async deleteItem(item, index) {
      if (window.confirm(`Delete "${item.original_filename}" (#${item.id})`)) {
        // Mark item as deleting
        this.deletingItems.add(item.id);

        // Force reactivity update
        this.$forceUpdate();

        try {
          const response = await axios.delete('/api/item/import/delete/' + item.id, {
            headers: {
              'X-CSRFToken': getCookie('csrftoken')
            }
          });

          if (response.status === 200 && response.data.job_id) {
            // Store the job ID for tracking
            this.deleteJobIds.set(item.id, response.data.job_id);
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
      this.importingItems.clear();
      this.deleteJobIds.clear();
    },
    // Bulk import methods
    updateSelectAllCheckbox() {
      // ToggleButton doesn't support indeterminate state, so this method is no longer needed
      // The toggle will show as checked when all items are selected, unchecked otherwise
    },
    toggleItemSelection(itemId) {
      if (this.selectedItems.has(itemId)) {
        this.selectedItems.delete(itemId);
      } else {
        this.selectedItems.add(itemId);
      }
      this.updateSelectAllCheckbox();
    },
    handleItemToggle(itemId, value) {
      toggleSetItem(this.selectedItems, itemId, value);
      this.updateSelectAllCheckbox();
    },
    handleSelectAllToggle(value) {
      if (value) {
        this.selectAll();
      } else {
        this.clearSelection();
      }
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
        // Select items that are not imported, not currently processing, and not being deleted or imported
        // Note: Duplicates can be selected for bulk deletion, but will be excluded from bulk import
        if (!item.imported && !(item.processing === true || (item.processing === false && item.feature_count === -1)) && !item.deleting && !item.importing) {
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
        if (item && !item.imported && !item.processing_failed && !(item.processing === true || (item.processing === false && item.feature_count === -1)) && item.file_duplicate?.status !== 'duplicate_in_queue') {
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
        alert('No valid items selected for import. Processing, already imported, failed items, or file-level duplicates cannot be bulk imported.');
        this.updateSelectAllCheckbox();
        return;
      }

      const selectedCount = this.selectedItems.size;
      const confirmMessage = `Are you sure you want to import ${selectedCount} item${selectedCount === 1 ? '' : 's'} without reviewing them?`;

      if (!window.confirm(confirmMessage)) {
        return;
      }

      this.isBulkImporting = true;
      const itemIds = Array.from(this.selectedItems);

      // Mark all items as importing immediately to disable buttons
      itemIds.forEach(itemId => {
        this.importingItems.add(itemId);
      });
      this.$forceUpdate();

      try {
        // Send WebSocket message to start bulk import
        realtimeSocket.send('bulk_import_job', 'start_bulk_import', {
          item_ids: itemIds,
          import_custom_icons: true
        });

        // Clear selection immediately
        this.clearSelection();

      } catch (error) {
        console.error('Bulk import error:', error);
        // Remove items from importingItems on error
        itemIds.forEach(itemId => {
          this.importingItems.delete(itemId);
        });
        // Error will be reflected in table status icons after refresh
        this.$forceUpdate();
      } finally {
        // Note: isBulkImporting will be set to false when the job completes via WebSocket
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
      const itemIds = Array.from(this.selectedItems);

      // Mark all items as deleting immediately to disable buttons
      itemIds.forEach(itemId => {
        this.deletingItems.add(itemId);
      });
      this.$forceUpdate();

      try {
        // Send WebSocket message to start bulk delete
        realtimeSocket.send('bulk_delete_job', 'start_bulk_delete', {
          item_ids: itemIds
        });

        // Clear selection immediately
        this.clearSelection();

      } catch (error) {
        console.error('Bulk delete error:', error);
        // Remove items from deletingItems on error
        itemIds.forEach(itemId => {
          this.deletingItems.delete(itemId);
        });
        // Error will be reflected in table status icons after refresh
        this.$forceUpdate();
      } finally {
        // Note: isBulkDeleting will be set to false when the job completes via WebSocket
      }
    },
    setupRealtimeConnection() {
      // The realtime connection is now managed globally in App.vue
      // Loading state is handled by the store subscription
    },
    subscribeToImportQueueUpdates() {
      // Subscribe to import queue mutations to handle loading completion
      this.$store.subscribe((mutation, state) => {
        if (mutation.type === 'setImportQueue') {
          // When import queue data is received, mark as initially loaded
          this.hasInitiallyLoaded = true;
          this.internalLoading = false;
          this.isRefreshing = false; // Clear refreshing flag when data arrives
        }
      });
    },
    setupBulkJobHandlers() {
      // Clear any existing handlers
      this.cleanupBulkJobHandlers();

      // Define handlers
      const bulkImportJobStarted = (data) => {
        this.bulkImportJobId = data.job_id;
      };

      const bulkImportStatusUpdated = (data) => {
        // Update progress if needed
        if (data.current_item_id) {
          // Item is being processed, keep it in importingItems
          this.importingItems.add(data.current_item_id);
          this.$forceUpdate();
        }
      };

      const bulkImportCompleted = (data) => {
        this.isBulkImporting = false;
        this.bulkImportJobId = null;

        // Remove all items from importingItems
        const itemIds = data.item_ids || [];
        itemIds.forEach(itemId => {
          this.importingItems.delete(itemId);
        });

        // Refresh the queue to update status icons
        this.$store.dispatch('refreshImportQueue');

        this.$forceUpdate();
      };

      const bulkImportFailed = (data) => {
        this.isBulkImporting = false;
        this.bulkImportJobId = null;

        // Remove all items from importingItems
        const itemIds = data.item_ids || [];
        itemIds.forEach(itemId => {
          this.importingItems.delete(itemId);
        });

        // Refresh the queue to update status icons
        this.$store.dispatch('refreshImportQueue');

        this.$forceUpdate();
      };

      const bulkDeleteJobStarted = (data) => {
        this.bulkDeleteJobId = data.job_id;
      };

      const bulkDeleteStatusUpdated = (data) => {
        // Update progress if needed
        if (data.current_item_id) {
          // Item is being processed, keep it in deletingItems
          this.deletingItems.add(data.current_item_id);
          this.$forceUpdate();
        }
      };

      const bulkDeleteCompleted = (data) => {
        this.isBulkDeleting = false;
        this.bulkDeleteJobId = null;

        // Remove all items from deletingItems
        const itemIds = data.item_ids || [];
        itemIds.forEach(itemId => {
          this.deletingItems.delete(itemId);
        });

        // Refresh the queue to update status icons
        this.$store.dispatch('refreshImportQueue');

        this.$forceUpdate();
      };

      const bulkDeleteFailed = (data) => {
        this.isBulkDeleting = false;
        this.bulkDeleteJobId = null;

        // Remove all items from deletingItems
        const itemIds = data.item_ids || [];
        itemIds.forEach(itemId => {
          this.deletingItems.delete(itemId);
        });

        // Refresh the queue to update status icons
        this.$store.dispatch('refreshImportQueue');

        this.$forceUpdate();
      };

      // Subscribe to bulk import job events
      realtimeSocket.subscribe('bulk_import_job', 'job_started', bulkImportJobStarted);
      realtimeSocket.subscribe('bulk_import_job', 'status_updated', bulkImportStatusUpdated);
      realtimeSocket.subscribe('bulk_import_job', 'completed', bulkImportCompleted);
      realtimeSocket.subscribe('bulk_import_job', 'failed', bulkImportFailed);

      // Subscribe to bulk delete job events
      realtimeSocket.subscribe('bulk_delete_job', 'job_started', bulkDeleteJobStarted);
      realtimeSocket.subscribe('bulk_delete_job', 'status_updated', bulkDeleteStatusUpdated);
      realtimeSocket.subscribe('bulk_delete_job', 'completed', bulkDeleteCompleted);
      realtimeSocket.subscribe('bulk_delete_job', 'failed', bulkDeleteFailed);

      // Store handlers for cleanup
      this.bulkJobHandlers = [
        { module: 'bulk_import_job', event: 'job_started', handler: bulkImportJobStarted },
        { module: 'bulk_import_job', event: 'status_updated', handler: bulkImportStatusUpdated },
        { module: 'bulk_import_job', event: 'completed', handler: bulkImportCompleted },
        { module: 'bulk_import_job', event: 'failed', handler: bulkImportFailed },
        { module: 'bulk_delete_job', event: 'job_started', handler: bulkDeleteJobStarted },
        { module: 'bulk_delete_job', event: 'status_updated', handler: bulkDeleteStatusUpdated },
        { module: 'bulk_delete_job', event: 'completed', handler: bulkDeleteCompleted },
        { module: 'bulk_delete_job', event: 'failed', handler: bulkDeleteFailed },
      ];
    },
    cleanupBulkJobHandlers() {
      // Unsubscribe from all bulk job events
      this.bulkJobHandlers.forEach(({ module, event, handler }) => {
        realtimeSocket.unsubscribe(module, event, handler);
      });
      this.bulkJobHandlers = [];
    },
  },
  async created() {
    // If we already have data in the store, mark as initially loaded
    // This prevents showing loading placeholders when navigating back with browser buttons
    if (this.importQueue && this.importQueue.length > 0) {
      this.hasInitiallyLoaded = true;
      this.internalLoading = false;
    } else {
      // If we don't have data, request a refresh via WebSocket
      // This ensures the component gets data when created on a new page
      this.fetchQueueList();
    }

    // Setup realtime connection (now managed globally)
    this.setupRealtimeConnection()

    // Subscribe to manual refresh mutations
    this.subscribeToRefreshMutation()

    // Setup bulk job WebSocket handlers
    this.setupBulkJobHandlers()

    // Subscribe to import queue updates to handle loading completion
    this.subscribeToImportQueueUpdates();
  },
  mounted() {
    // WebSocket is already connected in created()
    // Fallback: If we still haven't received data, haven't requested initial load yet, and WebSocket is connected, request refresh
    if (!this.hasInitiallyLoaded && !this.hasRequestedInitialLoad && this.websocketConnected && (!this.importQueue || this.importQueue.length === 0)) {
      this.fetchQueueList();
    }
  },
  beforeDestroy() {
    // Unsubscribe from bulk job events
    this.cleanupBulkJobHandlers();

    // Clear deleted items when component is destroyed (user navigates away)
    this.clearDeletedItems();
    // Clear selected items when component is destroyed
    this.clearSelection();
  },
}
</script>

<style scoped>
/* Checkbox styles are now in main.css */
</style>
