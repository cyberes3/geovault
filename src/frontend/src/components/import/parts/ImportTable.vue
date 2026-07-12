<template>
  <div class="overflow-hidden">
    <!-- Help text for clickable items -->
    <div v-if="filteredImportTable.length > 0 && !combinedLoading" class="mb-3">
      <p class="text-sm text-gray-600">
        Files that have been uploaded and are ready for processing. Click on any item in the table below to open the import processing page where you can review and edit features before importing.
      </p>
    </div>
    <!-- Bulk Import Controls -->
    <div v-if="filteredImportTable.length > 0 && !combinedLoading" class="mb-4">
      <div class="flex flex-col sm:flex-row sm:items-center space-y-2 sm:space-y-0 sm:space-x-3">
        <BaseButton
          @click="bulkImport"
          :disabled="validImportableCount === 0 || isBulkImporting || isBulkDeleting"
          class="w-full sm:w-auto"
          variant="primary"
          color="green"
          size="md"
          title="Import All Selected Items"
        >
          <Loader v-if="isBulkImporting" size="sm" layout="inline" :showMessage="false" />
          <ArrowUpTrayIcon v-else class="w-4 h-4 mr-2" />
          {{ isBulkImporting ? 'Importing...' : `Import ${validImportableCount} Item${validImportableCount === 1 ? '' : 's'}` }}
        </BaseButton>
        <BaseButton
          @click="bulkDelete"
          :disabled="selectedItems.size === 0 || isBulkDeleting || isBulkImporting"
          class="w-full sm:w-auto"
          variant="primary"
          color="red"
          size="md"
          title="Delete All Selected Items"
        >
          <Loader v-if="isBulkDeleting" size="sm" layout="inline" :showMessage="false" color="white" />
          <TrashIcon v-else class="w-4 h-4 mr-2" />
          {{ isBulkDeleting ? 'Deleting...' : `Delete ${selectedItems.size} Item${selectedItems.size === 1 ? '' : 's'}` }}
        </BaseButton>
      </div>
    </div>

    <div class="flex flex-col">
      <!-- Header Row (Desktop only) -->
      <div class="hidden md:flex bg-gray-50 md:px-3 md:py-3 lg:px-6 lg:py-4 border-b border-gray-200">
        <div class="w-12 flex-shrink-0 text-left">
          <input
            type="checkbox"
            ref="selectAllCheckbox"
            :checked="selectedItems.size === filteredImportTable.length && filteredImportTable.length > 0"
            @change="handleSelectAllToggle($event.target.checked)"
            class="checkbox-custom"
          />
        </div>
        <div class="flex-1 min-w-0 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">File Name</div>
        <div class="flex-1 min-w-0 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Features</div>
        <div class="flex-1 min-w-0 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Status</div>
        <div class="flex-1 min-w-0 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</div>
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
        <div v-if="!combinedLoading && filteredImportTable.length === 0 && hasInitiallyLoaded" class="py-12 text-center">
          <div class="flex flex-col items-center">
            <h3 class="text-lg font-medium text-gray-900 mb-2">No files uploaded yet</h3>
            <p class="text-gray-500 mb-6 max-w-sm">
              Get started by uploading your first geospatial data file. Supported formats include KMZ/KML and GeoJSON.
            </p>
          </div>
        </div>

        <!-- Actual data rows -->
        <div
          v-for="(item, index) in filteredImportTable"
          :key="`item-${index}`"
          :ref="el => setRowRef(el, index)"
          :class="[
            'flex',
            'flex-col md:flex-row',
            'items-stretch md:items-center',
            'p-3 md:p-0',
            'md:px-3 md:py-3',
            'lg:px-6 lg:py-4',
            'border border-gray-200 md:border-0',
            'rounded-lg md:rounded-none',
            'hover:bg-gray-50 transition-colors',
            'relative',
            (item.deleting || item.importing) ? 'opacity-60 bg-gray-50' : ''
          ]"
        >
          <!-- Tooltip (positioned relative to row) -->
          <div
            v-if="showTooltip[index]"
            class="custom-tooltip"
            :style="tooltipStyles[index]"
          >
            {{ item.original_filename }}
          </div>
          <!-- Checkbox + Status (mobile) / Checkbox only (desktop) -->
          <div class="w-full md:w-12 mb-2 md:mb-0 flex items-center justify-between md:justify-start md:flex-shrink-0">
            <input
              type="checkbox"
              :checked="selectedItems.has(item.id)"
              @change="handleItemToggle(item.id, $event.target.checked)"
              :disabled="item.imported || item.processing === true || (item.processing === false && item.feature_count === -1) || item.deleting || item.importing"
              class="checkbox-custom"
            />
            <!-- Mobile-only status badge on far right -->
            <div 
              class="md:hidden relative cursor-pointer"
              data-status-container
              @mouseenter="handleStatusHover($event, index)"
              @mouseleave="handleStatusLeave(index)"
              @touchstart="handleStatusTouchStart($event, index)"
              @touchend="handleStatusTouchEnd(index)"
              @click.stop="handleStatusClick($event, index)">
              <StatusBadge :item="item" />
            </div>
          </div>
          <!-- Filename -->
          <div class="flex-1 mb-2 md:mb-0 md:min-w-0 relative">
            <div class="md:text-sm font-medium text-gray-900 break-words md:break-normal">
              <!-- Disable link for duplicates in queue or when this specific item is being imported/deleted -->
              <router-link 
                v-if="item.file_duplicate?.status !== 'duplicate_in_queue' && !item.deleting && !item.importing"
                :to="`/import/process/${item.id}`"
                :ref="el => setFilenameRef(el, index)"
                class="filename-mobile text-blue-500 hover:text-blue-700 block md:inline">
                {{ item.original_filename }}
              </router-link>
              <span 
                v-else 
                :ref="el => setFilenameRef(el, index)"
                class="filename-mobile text-gray-500 cursor-not-allowed block md:inline">
                {{ item.original_filename }}
              </span>
            </div>
          </div>
          <!-- Features -->
          <div class="flex-1 mb-2 md:mb-0 md:text-center md:min-w-0">
            <div class="flex items-center md:justify-center">
              <span class="md:hidden text-[10px] font-semibold tracking-wide text-gray-900 mr-2 uppercase leading-none">Features</span>
              <span v-if="item.processing === true || (item.processing === false && item.feature_count === -1)" class="text-gray-400 text-xs sm:text-sm md:text-sm">
                -
              </span>
              <span v-else-if="item.processing_failed" class="text-red-600 flex items-center text-xs sm:text-sm md:text-sm">
                <XMarkIcon class="w-4 h-4" />
              </span>
              <span v-else class="font-medium text-gray-900 text-xs sm:text-sm md:text-sm">{{ item.feature_count }}</span>
            </div>
          </div>
          <!-- Status (desktop only - mobile shown in checkbox row) -->
          <div 
            class="hidden md:flex flex-1 items-center justify-center md:min-w-0 relative cursor-pointer"
            data-status-container
            @mouseenter="handleStatusHover($event, index)"
            @mouseleave="handleStatusLeave(index)"
            @touchstart="handleStatusTouchStart($event, index)"
            @touchend="handleStatusTouchEnd(index)"
            @click.stop="handleStatusClick($event, index)">
            <StatusBadge :item="item" />
          </div>
          <!-- Actions -->
          <div class="flex-1 mb-2 md:mb-0 md:text-center md:min-w-0">
            <div class="flex flex-col sm:flex-row md:flex-row items-stretch sm:items-center md:items-center justify-start sm:justify-center md:justify-center space-y-2 sm:space-y-0 md:space-y-0 sm:space-x-2 md:space-x-2">
              <BaseButton
                :disabled="item.imported || item.processing_failed || (item.processing === true || (item.processing === false && item.feature_count === -1)) || item.file_duplicate?.status === 'duplicate_in_queue' || item.deleting || item.importing"
                class="w-full sm:w-auto md:w-auto"
                variant="secondary"
                color="green"
                size="sm"
                @click="importItem(item, index)"
                title="Import This Item"
              >
                <ArrowUpTrayIcon class="w-4 h-4 mr-2" />
                Import
              </BaseButton>
              <BaseButton
                :disabled="item.deleting || item.importing"
                class="w-full sm:w-auto md:w-auto"
                variant="secondary"
                color="red"
                size="sm"
                @click="deleteItem(item, index)"
                title="Delete This Item"
              >
                <TrashIcon class="w-4 h-4 mr-2" />
                Delete
              </BaseButton>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {mapGetters} from "vuex";
import axios from "axios";
import {ImportTableItem} from "@/assets/js/types/import-types";
import {getCookie} from "@/utils/cookies";
import { toggleSetItem } from "@/assets/js/toggle-utils.js";
import Loader from "@/components/parts/Loader.vue";
import BaseButton from "@/components/parts/BaseButton.vue";
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
    ...mapGetters("auth", ["userInfo"]),
    ...mapGetters("importQueue", [
      "importTable",
      "isBulkImporting",
      "isBulkDeleting",
      "bulkImportingItemIds",
      "bulkDeletingItemIds",
      "lastBulkImportOutcome",
      "lastBulkDeleteOutcome",
    ]),
    ...mapGetters("websocket", {websocketConnected: "connected"}),
    filteredImportTable() {
      // Filter out items that have been locally deleted and add deleting/importing state.
      // "deleting"/"importing" is true either from a single-item action (local Sets) or because
      // a bulk job in the store is currently working on this item.
      const bulkImportingIds = new Set(this.bulkImportingItemIds);
      const bulkDeletingIds = new Set(this.bulkDeletingItemIds);
      return this.importTable
        .slice()
        .filter(item => !this.deletedItems.includes(item.id))
        .map(item => ({
          ...item,
          deleting: this.deletingItems.has(item.id) || bulkDeletingIds.has(item.id),
          importing: this.importingItems.has(item.id) || bulkImportingIds.has(item.id)
        }))
        // Sort so the oldest items appear at the top of the table
        .sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
    },
    combinedLoading() {
      // Show loading placeholders only when:
      // 1. We're actually loading (isLoading or internalLoading is true)
      // 2. We haven't initially loaded yet (hasInitiallyLoaded is false)
      // 3. AND we don't have any data in the store yet
      return (this.isLoading || this.internalLoading) && !this.hasInitiallyLoaded && (!this.importTable || this.importTable.length === 0);
    },
    validImportableCount() {
      // Count items that can actually be imported (same logic as bulkImport)
      let count = 0;
      this.selectedItems.forEach(itemId => {
        const item = this.filteredImportTable.find(i => i.id === itemId);
        if (item && !item.imported && !item.processing_failed && !(item.processing === true || (item.processing === false && item.feature_count === -1)) && 
            item.file_duplicate?.status !== 'duplicate_in_queue' && 
            item.file_duplicate?.status !== 'all_features_duplicate') {
          count++;
        }
      });
      return count;
    },
    isAnyOperationInProgress() {
      // Check if any bulk import or delete operation is in progress
      return this.isBulkImporting || this.isBulkDeleting;
    }
  },
  components: {
    Loader,
    BaseButton,
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
      deletedItems: [], // Track locally deleted items to prevent flicker
      deletedItemTimeouts: new Map(), // Track how many refresh cycles each deleted item has been gone
      selectedItems: new Set(), // Track selected items for bulk import
      refreshInterval: null, // Auto-refresh interval
      deletingItems: new Set(), // Track items currently being deleted individually (not via a bulk job)
      importingItems: new Set(), // Track items currently being imported individually (not via a bulk job)
      deleteJobIds: new Map(), // Track delete job IDs for each item
      filenameRefs: {}, // Store refs to filename elements for truncation detection
      rowRefs: {}, // Store refs to row elements for tooltip positioning
      showTooltip: {}, // Track which tooltips are visible
      isTruncated: {}, // Track which filenames are truncated
      tooltipStyles: {}, // Store tooltip positioning styles
      touchTimeouts: {}, // Store touch timeout IDs
      resizeTimeout: null, // Store resize timeout ID
      touchHandled: {}, // Track if touch event was handled to prevent click event
    }
  },
  watch: {
    filteredImportTable() {
      // Check truncation when table updates
      this.$nextTick(() => {
        this.checkAllFilenameTruncation()
      })
    },
    // A full replacement of importTable (SET_IMPORT_TABLE) only happens once the queue's
    // "initial_state" has been (re)loaded; incremental item add/remove/update mutations don't
    // reassign the array, so this fires exactly when the old `setImportTable`-mutation
    // subscription used to.
    importTable(newTable) {
      this.hasInitiallyLoaded = true;
      this.internalLoading = false;
      this.isRefreshing = false;
      this.checkForRestoreItems(newTable);
    },
    lastBulkImportOutcome(outcome) {
      if (!outcome) return;
      window.alert(outcome.message);
      this.$store.dispatch('importQueue/clearLastBulkImportOutcome');
    },
    lastBulkDeleteOutcome(outcome) {
      if (!outcome) return;
      window.alert(outcome.message);
      this.$store.dispatch('importQueue/clearLastBulkDeleteOutcome');
    }
  },
  methods: {
    setFilenameRef(el, index) {
      if (el) {
        this.filenameRefs[index] = el
        this.$nextTick(() => {
          this.checkFilenameTruncation(index)
        })
      }
    },
    checkFilenameTruncation(index) {
      this.$nextTick(() => {
        const element = this.filenameRefs[index]
        if (element) {
          // Only check on mobile (where filename-mobile class applies)
          if (window.innerWidth < 768) {
            const isTruncated = element.scrollWidth > element.clientWidth
            this.isTruncated[index] = isTruncated
          } else {
            // On desktop, text can wrap, so no truncation
            this.isTruncated[index] = false
          }
        }
      })
    },
    handleStatusHover(event, index) {
      // Always show tooltip on hover if filename is truncated
      if (this.isTruncated[index]) {
        this.showTooltip[index] = true
        this.updateTooltipPosition(event, index)
      }
    },
    handleStatusLeave(index) {
      this.showTooltip[index] = false
    },
    handleStatusTouchStart(event, index) {
      // Toggle tooltip on tap/click
      event.preventDefault()
      event.stopPropagation()
      
      // Mark that touch was handled to prevent click event from firing
      this.touchHandled[index] = true
      
      // Clear any existing timeout first
      if (this.touchTimeouts[index]) {
        clearTimeout(this.touchTimeouts[index])
        this.touchTimeouts[index] = null
      }
      
      // Toggle tooltip: if already showing, hide it; otherwise show it
      if (this.showTooltip[index]) {
        this.showTooltip[index] = false
      } else {
        this.showTooltip[index] = true
        this.updateTooltipPosition(event, index)
        // Hide tooltip after 3 seconds on mobile
        this.touchTimeouts[index] = setTimeout(() => {
          this.showTooltip[index] = false
          this.touchTimeouts[index] = null
        }, 3000)
      }
      
      // Clear touch handled flag after a short delay to allow for next interaction
      setTimeout(() => {
        this.touchHandled[index] = false
      }, 300)
    },
    handleStatusTouchEnd(index) {
      // Don't hide immediately to give user time to read
    },
    handleStatusClick(event, index) {
      // Handle click (for desktop mouse clicks)
      // Skip if touch was already handled (prevents double-toggle on mobile)
      if (this.touchHandled[index]) {
        event.preventDefault()
        event.stopPropagation()
        return
      }
      
      // Don't show tooltip on desktop click - only on mobile touch
      event.preventDefault()
      event.stopPropagation()
    },
    updateTooltipPosition(event, index) {
      // Position tooltip centered along the table card (row)
      // Using absolute positioning so it scrolls with the page
      const rowElement = this.rowRefs[index]
      if (rowElement) {
        this.tooltipStyles[index] = {
          left: '50%',
          top: '-8px',
          transform: 'translate(-50%, -100%)',
          position: 'absolute'
        }
      }
    },
    setRowRef(el, index) {
      if (el) {
        this.rowRefs[index] = el
      }
    },
    closeAllTooltips() {
      // Close all tooltips
      Object.keys(this.showTooltip).forEach(index => {
        this.showTooltip[index] = false
      })
      // Clear all timeouts
      Object.values(this.touchTimeouts).forEach(timeout => {
        if (timeout) clearTimeout(timeout)
      })
      this.touchTimeouts = {}
    },
    handleOutsideClick(event) {
      // Check if click is outside any status badge or tooltip
      const clickedElement = event.target
      let clickedOnStatus = false
      
      // Check if click is on a status badge or its container
      const statusContainers = document.querySelectorAll('[data-status-container]')
      statusContainers.forEach(container => {
        if (container.contains(clickedElement)) {
          clickedOnStatus = true
        }
      })
      
      // Check if click is on a tooltip
      const tooltips = document.querySelectorAll('.custom-tooltip')
      tooltips.forEach(tooltip => {
        if (tooltip.contains(clickedElement)) {
          clickedOnStatus = true
        }
      })
      
      // If not clicked on status or tooltip, close all tooltips
      if (!clickedOnStatus) {
        this.closeAllTooltips()
      }
    },
    checkAllFilenameTruncation() {
      // Check truncation for all filename elements
      Object.keys(this.filenameRefs).forEach(index => {
        this.checkFilenameTruncation(parseInt(index))
      })
    },
    handleResize() {
      // Debounce resize handler
      clearTimeout(this.resizeTimeout)
      this.resizeTimeout = setTimeout(() => {
        this.checkAllFilenameTruncation()
      }, 150)
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
        this.$store.dispatch('importQueue/requestQueueRefresh')
        // Don't set internalLoading = false here - keep it true until data arrives.
        // The `importTable` watcher will set it to false once the new data lands in the store.
      } catch (error) {
        console.error('Error requesting queue refresh:', error)
        // Only set loading to false on error
        this.internalLoading = false
        this.hasRequestedInitialLoad = false // Reset on error so we can retry
      } finally {
        // Keep isRefreshing true until data arrives to prevent duplicate requests.
        // It will be set to false by the `importTable` watcher once data arrives.
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
          this.deletedItems = this.deletedItems.filter(id => id !== itemId);
          this.deletedItemTimeouts.delete(itemId);
        } else if (!stillExistsOnServer) {
          // Item was successfully deleted from server, clean up tracking
          this.deletedItems = this.deletedItems.filter(id => id !== itemId);
          this.deletedItemTimeouts.delete(itemId);
        }
      }
    },
    async importItem(item, index) {
      if (item.imported || item.processing_failed || (item.processing === true || (item.processing === false && item.feature_count === -1)) || 
          item.file_duplicate?.status === 'duplicate_in_queue' || 
          item.file_duplicate?.status === 'all_features_duplicate') {
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
        // Optimistically hide the item immediately
        if (!this.deletedItems.includes(item.id)) {
          this.deletedItems.push(item.id);
        }
        this.deletedItemTimeouts.set(item.id, 0);

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
          // Remove from deleting/deleted items set to restore the item if deletion failed
          this.deletingItems.delete(item.id);
          this.deletedItems = this.deletedItems.filter(id => id !== item.id);
          this.deletedItemTimeouts.delete(item.id);
          this.deleteJobIds.delete(item.id);
          this.$forceUpdate();
        }
      }
    },
    clearDeletedItems() {
      // Clear the deleted items list when navigating away
      this.deletedItems = [];
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
      if (this.selectedItems.size === this.filteredImportTable.length) {
        this.clearSelection();
      } else {
        this.selectAll();
      }
    },
    selectAll() {
      this.filteredImportTable.forEach(item => {
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
        const item = this.filteredImportTable.find(i => i.id === itemId);
        if (item && !item.imported && !item.processing_failed && !(item.processing === true || (item.processing === false && item.feature_count === -1)) && 
            item.file_duplicate?.status !== 'duplicate_in_queue' && 
            item.file_duplicate?.status !== 'all_features_duplicate') {
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

      const itemIds = Array.from(this.selectedItems);

      // The store optimistically marks these items as importing and starts the bulk_import_job
      // WebSocket module; BulkImportJobModule dispatches the completion/failure back into it.
      this.$store.dispatch('importQueue/startBulkImport', { itemIds, importCustomIcons: true });
      this.clearSelection();
    },
    async bulkDelete() {
      if (this.selectedItems.size === 0) {
        return;
      }

      // Check for items that cannot be deleted (imported items or items being deleted)
      const invalidItems = [];
      this.selectedItems.forEach(itemId => {
        const item = this.filteredImportTable.find(i => i.id === itemId);
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
      const confirmMessage = `Are you sure you want to delete ${selectedCount} item${selectedCount === 1 ? '' : 's'}? Deleted items cannot be recovered.`;

      if (!window.confirm(confirmMessage)) {
        return;
      }

      const itemIds = Array.from(this.selectedItems);

      // The store optimistically marks these items as deleting and starts the bulk_delete_job
      // WebSocket module; BulkDeleteJobModule dispatches the completion/failure back into it.
      this.$store.dispatch('importQueue/startBulkDelete', { itemIds });
      this.clearSelection();
    },
  },
  async created() {
    // If we already have data in the store, mark as initially loaded
    // This prevents showing loading placeholders when navigating back with browser buttons
    if (this.importTable && this.importTable.length > 0) {
      this.hasInitiallyLoaded = true;
      this.internalLoading = false;
    } else {
      // If we don't have data, request a refresh via WebSocket
      // This ensures the component gets data when created on a new page
      this.fetchQueueList();
    }

    // Realtime connection + WebSocket module registration is managed globally in App.vue; the
    // `importTable`/`lastBulk*Outcome` watchers above react to whatever those modules dispatch.
  },
  mounted() {
    // WebSocket is already connected in created()
    // Fallback: If we still haven't received data, haven't requested initial load yet, and WebSocket is connected, request refresh
    if (!this.hasInitiallyLoaded && !this.hasRequestedInitialLoad && this.websocketConnected && (!this.importTable || this.importTable.length === 0)) {
      this.fetchQueueList();
    }
    
    // Check filename truncation on mount and window resize
    this.checkAllFilenameTruncation();
    window.addEventListener('resize', this.handleResize);
    
    // Add click listener to close tooltips when clicking outside
    document.addEventListener('click', this.handleOutsideClick);
    document.addEventListener('touchstart', this.handleOutsideClick);
  },
  beforeUnmount() {
    // Clear deleted items when component is destroyed (user navigates away)
    this.clearDeletedItems();
    // Clear selected items when component is destroyed
    this.clearSelection();
    
    // Clean up resize listener and timeouts
    window.removeEventListener('resize', this.handleResize);
    document.removeEventListener('click', this.handleOutsideClick);
    document.removeEventListener('touchstart', this.handleOutsideClick);
    if (this.resizeTimeout) {
      clearTimeout(this.resizeTimeout);
    }
    Object.values(this.touchTimeouts).forEach(timeout => {
      if (timeout) clearTimeout(timeout);
    });
  },
}
</script>

<style scoped>
/* Checkbox styles are now in main.css */

/* Dynamic font sizing for mobile filename - fits in one line with min 12pt */
.filename-mobile {
  font-size: clamp(0.75rem, 4vw, 1rem);
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@media (min-width: 768px) {
  .filename-mobile {
    font-size: inherit;
    white-space: normal;
    overflow: visible;
    text-overflow: clip;
  }
}

/* Custom Tooltip (matching FeatureInfoBox style) */
.custom-tooltip {
  position: absolute;
  z-index: 9999;
  background-color: rgba(0, 0, 0, 0.9);
  color: white;
  padding: 0.375rem 0.5rem;
  border-radius: 0.25rem;
  font-size: 0.8125rem;
  font-weight: 500;
  line-height: 1.4;
  white-space: normal;
  word-wrap: break-word;
  word-break: break-word;
  overflow-wrap: break-word;
  width: 100%;
  box-shadow: 0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  pointer-events: none;
}

/* Tooltip arrow */
.custom-tooltip::after {
  content: '';
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  border: 5px solid transparent;
  border-top-color: rgba(0, 0, 0, 0.9);
}

/* Mobile adjustments */
@media (max-width: 768px) {
  .custom-tooltip {
    width: 100%;
  }
}
</style>

