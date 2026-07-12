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
          <Loader v-if="isBulkImporting" size="sm" layout="inline" :show-message="false" />
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
          <Loader v-if="isBulkDeleting" size="sm" layout="inline" :show-message="false" color="white" />
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
            :checked="selectedItems.size === filteredImportTable.length && filteredImportTable.length > 0"
            @change="handleSelectAllToggle(($event.target as HTMLInputElement).checked)"
            class="checkbox-custom"
          />
        </div>
        <div class="flex-1 min-w-0 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">File Name</div>
        <div class="flex-1 min-w-0 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Features</div>
        <div class="flex-1 min-w-0 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Status</div>
        <div class="flex-1 min-w-0 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</div>
      </div>

      <!-- Loading placeholders -->
      <div v-if="combinedLoading" class="flex flex-col space-y-3 md:space-y-0 md:divide-y md:divide-gray-200">
        <div
          v-for="n in 3"
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

      <!-- Actual data rows (virtualized: row height differs a lot between the mobile stacked-card
           layout and the single-line desktop layout, so DynamicScroller's measured-height mode is
           used instead of a fixed RecycleScroller item-size). -->
      <DynamicScroller
        v-if="!combinedLoading && filteredImportTable.length > 0"
        :items="filteredImportTable"
        key-field="id"
        :min-item-size="72"
        :page-mode="true"
        v-slot="{ item, index, active }"
      >
        <DynamicScrollerItem :item="item" :active="active" :index="index" tag="div">
          <div
            :ref="el => setRowRef(el, item.id)"
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
              index === filteredImportTable.length - 1 ? 'mb-0' : 'mb-3 md:mb-0',
              index === filteredImportTable.length - 1 ? '' : 'md:border-b md:border-gray-200',
              (item.deleting || item.importing) ? 'opacity-60 bg-gray-50' : ''
            ]"
          >
            <!-- Tooltip (positioned relative to row) -->
            <div
              v-if="showTooltip[item.id]"
              class="custom-tooltip"
              :style="tooltipStyles[item.id]"
            >
              {{ item.original_filename }}
            </div>
            <!-- Checkbox + Status (mobile) / Checkbox only (desktop) -->
            <div class="w-full md:w-12 mb-2 md:mb-0 flex items-center justify-between md:justify-start md:flex-shrink-0">
              <input
                type="checkbox"
                :checked="selectedItems.has(item.id)"
                @change="handleItemToggle(item.id, ($event.target as HTMLInputElement).checked)"
                :disabled="item.imported || isItemStillProcessing(item) || item.deleting || item.importing"
                class="checkbox-custom"
              />
              <!-- Mobile-only status badge on far right -->
              <div
                class="md:hidden relative cursor-pointer"
                data-status-container
                @mouseenter="handleStatusHover(item.id)"
                @mouseleave="handleStatusLeave(item.id)"
                @touchstart="handleStatusTouchStart($event, item.id)"
                @touchend="handleStatusTouchEnd()"
                @click.stop="handleStatusClick($event, item.id)">
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
                  :ref="el => setFilenameRef(el, item.id)"
                  class="filename-mobile text-blue-500 hover:text-blue-700 block md:inline">
                  {{ item.original_filename }}
                </router-link>
                <span
                  v-else
                  :ref="el => setFilenameRef(el, item.id)"
                  class="filename-mobile text-gray-500 cursor-not-allowed block md:inline">
                  {{ item.original_filename }}
                </span>
              </div>
            </div>
            <!-- Features -->
            <div class="flex-1 mb-2 md:mb-0 md:text-center md:min-w-0">
              <div class="flex items-center md:justify-center">
                <span class="md:hidden text-[10px] font-semibold tracking-wide text-gray-900 mr-2 uppercase leading-none">Features</span>
                <span v-if="isItemStillProcessing(item)" class="text-gray-400 text-xs sm:text-sm md:text-sm">
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
              @mouseenter="handleStatusHover(item.id)"
              @mouseleave="handleStatusLeave(item.id)"
              @touchstart="handleStatusTouchStart($event, item.id)"
              @touchend="handleStatusTouchEnd()"
              @click.stop="handleStatusClick($event, item.id)">
              <StatusBadge :item="item" />
            </div>
            <!-- Actions -->
            <div class="flex-1 mb-2 md:mb-0 md:text-center md:min-w-0">
              <div class="flex flex-col sm:flex-row md:flex-row items-stretch sm:items-center md:items-center justify-start sm:justify-center md:justify-center space-y-2 sm:space-y-0 md:space-y-0 sm:space-x-2 md:space-x-2">
                <BaseButton
                  :disabled="item.imported || item.processing_failed || isItemStillProcessing(item) || item.file_duplicate?.status === 'duplicate_in_queue' || item.deleting || item.importing"
                  class="w-full sm:w-auto md:w-auto"
                  variant="secondary"
                  color="green"
                  size="sm"
                  @click="importItem(item)"
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
                  @click="deleteItem(item)"
                  title="Delete This Item"
                >
                  <TrashIcon class="w-4 h-4 mr-2" />
                  Delete
                </BaseButton>
              </div>
            </div>
          </div>
        </DynamicScrollerItem>
      </DynamicScroller>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, toRef, watch, type ComponentPublicInstance } from 'vue';
import { DynamicScroller, DynamicScrollerItem } from 'vue-virtual-scroller';
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css';
import Loader from '@/components/parts/Loader.vue';
import BaseButton from '@/components/parts/BaseButton.vue';
import StatusBadge from '@/components/import/parts/StatusBadge.vue';
import { ArrowUpTrayIcon, TrashIcon, XMarkIcon } from '@heroicons/vue/24/outline';
import { useImportQueueTable } from '@/composables/useImportQueueTable';

const props = withDefaults(defineProps<{ isLoading?: boolean }>(), {
  isLoading: false,
});

const {
  filteredImportTable,
  combinedLoading,
  hasInitiallyLoaded,
  validImportableCount,
  isBulkImporting,
  isBulkDeleting,
  selectedItems,
  importItem,
  deleteItem,
  handleItemToggle,
  handleSelectAllToggle,
  bulkImport,
  bulkDelete,
  isItemStillProcessing,
  initialize,
  requestRefreshIfStillEmpty,
  clearDeletedItems,
  clearSelection,
} = useImportQueueTable({ isLoading: toRef(props, 'isLoading') });

// Tooltip / filename-truncation state is purely presentational DOM bookkeeping local to this
// component, keyed by item id (not array index) so it survives DynamicScroller recycling views.
const filenameRefs = new Map<number, HTMLElement>();
const rowRefs = new Map<number, HTMLElement>();
const touchTimeouts = new Map<number, ReturnType<typeof setTimeout>>();
const touchHandled = new Map<number, boolean>();
let resizeTimeout: ReturnType<typeof setTimeout> | undefined;

const showTooltip = reactive<Record<number, boolean>>({});
const isTruncated = reactive<Record<number, boolean>>({});
const tooltipStyles = reactive<Record<number, Record<string, string>>>({});

function setFilenameRef(el: Element | ComponentPublicInstance | null, itemId: number): void {
  const element = el as HTMLElement | null;
  if (element) {
    filenameRefs.set(itemId, element);
    void nextTick(() => {
      checkFilenameTruncation(itemId);
    });
  }
}

function checkFilenameTruncation(itemId: number): void {
  void nextTick(() => {
    const element = filenameRefs.get(itemId);
    if (element) {
      // Only check on mobile (where the filename-mobile class applies its single-line clamp).
      isTruncated[itemId] = window.innerWidth < 768 ? element.scrollWidth > element.clientWidth : false;
    }
  });
}

function setRowRef(el: Element | ComponentPublicInstance | null, itemId: number): void {
  const element = el as HTMLElement | null;
  if (element) {
    rowRefs.set(itemId, element);
  }
}

function updateTooltipPosition(itemId: number): void {
  // Position tooltip centered along the table row. Absolute positioning so it scrolls with the page.
  if (rowRefs.has(itemId)) {
    tooltipStyles[itemId] = {
      left: '50%',
      top: '-8px',
      transform: 'translate(-50%, -100%)',
      position: 'absolute',
    };
  }
}

function handleStatusHover(itemId: number): void {
  if (isTruncated[itemId]) {
    showTooltip[itemId] = true;
    updateTooltipPosition(itemId);
  }
}

function handleStatusLeave(itemId: number): void {
  showTooltip[itemId] = false;
}

function handleStatusTouchStart(event: TouchEvent, itemId: number): void {
  event.preventDefault();
  event.stopPropagation();

  touchHandled.set(itemId, true);

  const existingTimeout = touchTimeouts.get(itemId);
  if (existingTimeout) {
    clearTimeout(existingTimeout);
    touchTimeouts.delete(itemId);
  }

  if (showTooltip[itemId]) {
    showTooltip[itemId] = false;
  } else {
    showTooltip[itemId] = true;
    updateTooltipPosition(itemId);
    // Hide tooltip after 3 seconds on mobile.
    touchTimeouts.set(itemId, setTimeout(() => {
      showTooltip[itemId] = false;
      touchTimeouts.delete(itemId);
    }, 3000));
  }

  setTimeout(() => {
    touchHandled.set(itemId, false);
  }, 300);
}

function handleStatusTouchEnd(): void {
  // Don't hide immediately to give the user time to read the tooltip.
}

function handleStatusClick(event: Event, itemId: number): void {
  // Skip if touch already handled this interaction (prevents double-toggle on mobile).
  if (touchHandled.get(itemId)) {
    event.preventDefault();
    event.stopPropagation();
    return;
  }
  // Don't show tooltip on desktop click - only on mobile touch.
  event.preventDefault();
  event.stopPropagation();
}

function closeAllTooltips(): void {
  for (const itemId of Object.keys(showTooltip)) {
    showTooltip[Number(itemId)] = false;
  }
  for (const timeout of touchTimeouts.values()) {
    clearTimeout(timeout);
  }
  touchTimeouts.clear();
}

function handleOutsideClick(event: Event): void {
  const clickedElement = event.target as Node;

  const clickedOnStatus =
    Array.from(document.querySelectorAll('[data-status-container]')).some((container) => container.contains(clickedElement)) ||
    Array.from(document.querySelectorAll('.custom-tooltip')).some((tooltip) => tooltip.contains(clickedElement));

  if (!clickedOnStatus) {
    closeAllTooltips();
  }
}

function checkAllFilenameTruncation(): void {
  for (const itemId of filenameRefs.keys()) {
    checkFilenameTruncation(itemId);
  }
}

function handleResize(): void {
  clearTimeout(resizeTimeout);
  resizeTimeout = setTimeout(() => {
    checkAllFilenameTruncation();
  }, 150);
}

watch(filteredImportTable, () => {
  void nextTick(() => {
    checkAllFilenameTruncation();
  });
});

initialize();

onMounted(() => {
  requestRefreshIfStillEmpty();

  checkAllFilenameTruncation();
  window.addEventListener('resize', handleResize);
  document.addEventListener('click', handleOutsideClick);
  document.addEventListener('touchstart', handleOutsideClick);
});

onBeforeUnmount(() => {
  clearDeletedItems();
  clearSelection();

  window.removeEventListener('resize', handleResize);
  document.removeEventListener('click', handleOutsideClick);
  document.removeEventListener('touchstart', handleOutsideClick);
  clearTimeout(resizeTimeout);
  for (const timeout of touchTimeouts.values()) {
    clearTimeout(timeout);
  }
});
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
