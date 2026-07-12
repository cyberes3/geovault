import { computed, reactive, ref, watch, type Ref } from 'vue';
import { useStore } from 'vuex';
import type { RootState } from '@/assets/js/store';
import type { ImportTableItem } from '@/assets/js/types/import-types';
import type { BulkJobOutcome } from '@/assets/js/store/modules/importQueue';
import { toggleSetItem } from '@/assets/js/toggle-utils';
import { performImport, deleteImportItem } from '@/api/services/importApi';
import { toastApiError } from '@/utils/apiError';
import { toast } from '@/utils/toast';

/** A queue item annotated with whatever this-tab-only import/delete-in-progress state applies to it. */
export interface DisplayImportTableItem extends ImportTableItem {
  deleting: boolean;
  importing: boolean;
}

interface RootGetters {
  'importQueue/importTable': ImportTableItem[];
  'importQueue/isBulkImporting': boolean;
  'importQueue/isBulkDeleting': boolean;
  'importQueue/bulkImportingItemIds': number[];
  'importQueue/bulkDeletingItemIds': number[];
  'importQueue/lastBulkImportOutcome': BulkJobOutcome | null;
  'importQueue/lastBulkDeleteOutcome': BulkJobOutcome | null;
  'websocket/connected': boolean;
}

interface UseImportQueueTableOptions {
  /** Mirrors the component's `isLoading` prop - an external hint that the caller is (re)loading. */
  isLoading: Ref<boolean>;
}

/**
 * Table state for `ImportTable.vue`: reads the live import queue from the `importQueue` Vuex
 * module (getters only) and owns this-tab-only per-item import/delete/selection UI state.
 */
export function useImportQueueTable({ isLoading }: UseImportQueueTableOptions) {
  const store = useStore<RootState>();
  const getters = computed(() => store.getters as RootGetters);

  const importTable = computed(() => getters.value['importQueue/importTable']);
  const isBulkImporting = computed(() => getters.value['importQueue/isBulkImporting']);
  const isBulkDeleting = computed(() => getters.value['importQueue/isBulkDeleting']);
  const bulkImportingItemIds = computed(() => getters.value['importQueue/bulkImportingItemIds']);
  const bulkDeletingItemIds = computed(() => getters.value['importQueue/bulkDeletingItemIds']);
  const lastBulkImportOutcome = computed(() => getters.value['importQueue/lastBulkImportOutcome']);
  const lastBulkDeleteOutcome = computed(() => getters.value['importQueue/lastBulkDeleteOutcome']);
  const websocketConnected = computed(() => getters.value['websocket/connected']);

  const internalLoading = ref(true);
  const hasInitiallyLoaded = ref(false);
  const isRefreshing = ref(false);
  const hasRequestedInitialLoad = ref(false);

  const deletedItems = ref<number[]>([]);
  const deletedItemTimeouts = new Map<number, number>();
  const selectedItems = reactive(new Set<number>());
  const deletingItems = reactive(new Set<number>());
  const importingItems = reactive(new Set<number>());
  const deleteJobIds = new Map<number, string>();

  const filteredImportTable = computed<DisplayImportTableItem[]>(() => {
    const bulkImportingIds = new Set(bulkImportingItemIds.value);
    const bulkDeletingIds = new Set(bulkDeletingItemIds.value);
    return importTable.value
      .filter((item) => !deletedItems.value.includes(item.id))
      .map((item) => ({
        ...item,
        deleting: deletingItems.has(item.id) || bulkDeletingIds.has(item.id),
        importing: importingItems.has(item.id) || bulkImportingIds.has(item.id),
      }))
      // Sort so the oldest items appear at the top of the table.
      .sort((a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime());
  });

  const combinedLoading = computed(() =>
    (isLoading.value || internalLoading.value) && !hasInitiallyLoaded.value && importTable.value.length === 0,
  );

  /** True while the backend is still counting features (or has finished without a usable count). */
  function isItemStillProcessing(item: DisplayImportTableItem): boolean {
    return item.processing || item.feature_count === -1;
  }

  function isItemImportable(item: DisplayImportTableItem): boolean {
    return !item.imported && !item.processing_failed && !isItemStillProcessing(item) &&
      item.file_duplicate?.status !== 'duplicate_in_queue' &&
      item.file_duplicate?.status !== 'all_features_duplicate';
  }

  const validImportableCount = computed(() => {
    let count = 0;
    selectedItems.forEach((itemId) => {
      const item = filteredImportTable.value.find((i) => i.id === itemId);
      if (item && isItemImportable(item)) {
        count++;
      }
    });
    return count;
  });

  function checkForRestoreItems(serverQueue: ImportTableItem[]): void {
    for (const [itemId, timeoutCount] of deletedItemTimeouts.entries()) {
      deletedItemTimeouts.set(itemId, timeoutCount + 1);

      const stillExistsOnServer = serverQueue.some((item) => item.id === itemId);

      if (stillExistsOnServer && timeoutCount >= 2) {
        // Item still exists on the server after 3 refresh cycles; restore it.
        deletedItems.value = deletedItems.value.filter((id) => id !== itemId);
        deletedItemTimeouts.delete(itemId);
      } else if (!stillExistsOnServer) {
        // Item was successfully deleted from the server; clean up tracking.
        deletedItems.value = deletedItems.value.filter((id) => id !== itemId);
        deletedItemTimeouts.delete(itemId);
      }
    }
  }

  function fetchQueueList(): void {
    // Manual refresh only -- the WebSocket handles real-time updates otherwise.
    if (isRefreshing.value) {
      return;
    }

    isRefreshing.value = true;
    internalLoading.value = true;
    hasRequestedInitialLoad.value = true;

    try {
      void store.dispatch('importQueue/requestQueueRefresh');
      // Don't set internalLoading = false here; the `importTable` watcher below does that once
      // the new data lands in the store.
    } catch (error) {
      console.error('Error requesting queue refresh:', error);
      internalLoading.value = false;
      hasRequestedInitialLoad.value = false;
    }
  }

  async function importItem(item: DisplayImportTableItem): Promise<void> {
    if (!isItemImportable(item)) {
      return;
    }

    if (!window.confirm(`Are you sure you want to import "${item.original_filename}" without reviewing it?`)) {
      return;
    }

    importingItems.add(item.id);
    try {
      await performImport(item.id);
      toast.success(`Successfully imported "${item.original_filename}"!`);
    } catch (error) {
      toastApiError(error, `Failed to import "${item.original_filename}"`);
    } finally {
      importingItems.delete(item.id);
    }
  }

  async function deleteItem(item: DisplayImportTableItem): Promise<void> {
    if (!window.confirm(`Delete "${item.original_filename}" (#${item.id})`)) {
      return;
    }

    deletingItems.add(item.id);
    if (!deletedItems.value.includes(item.id)) {
      deletedItems.value = [...deletedItems.value, item.id];
    }
    deletedItemTimeouts.set(item.id, 0);

    try {
      const data = await deleteImportItem(item.id);
      if (data.job_id) {
        deleteJobIds.set(item.id, data.job_id);
      } else {
        throw new Error(data.msg || 'server reported failure');
      }
    } catch (error) {
      toastApiError(error, `Failed to delete "${item.original_filename}"`);
      // Restore the item since deletion failed.
      deletingItems.delete(item.id);
      deletedItems.value = deletedItems.value.filter((id) => id !== item.id);
      deletedItemTimeouts.delete(item.id);
      deleteJobIds.delete(item.id);
    }
  }

  function clearDeletedItems(): void {
    deletedItems.value = [];
    deletedItemTimeouts.clear();
    deletingItems.clear();
    importingItems.clear();
    deleteJobIds.clear();
  }

  function handleItemToggle(itemId: number, value: boolean): void {
    toggleSetItem(selectedItems, itemId, value);
  }

  function handleSelectAllToggle(value: boolean): void {
    if (value) {
      selectAll();
    } else {
      clearSelection();
    }
  }

  function selectAll(): void {
    filteredImportTable.value.forEach((item) => {
      // Duplicates can be selected for bulk deletion, but are excluded from bulk import
      // (validImportableCount / bulkImport filter that out).
      if (!item.imported && !isItemStillProcessing(item) && !item.deleting && !item.importing) {
        selectedItems.add(item.id);
      }
    });
  }

  function clearSelection(): void {
    selectedItems.clear();
  }

  async function bulkImport(): Promise<void> {
    if (selectedItems.size === 0) {
      return;
    }

    const validItems: number[] = [];
    const invalidItems: number[] = [];
    selectedItems.forEach((itemId) => {
      const item = filteredImportTable.value.find((i) => i.id === itemId);
      if (item && isItemImportable(item)) {
        validItems.push(itemId);
      } else {
        invalidItems.push(itemId);
      }
    });
    invalidItems.forEach((itemId) => selectedItems.delete(itemId));

    if (selectedItems.size === 0) {
      toast.error('No valid items selected for import. Processing, already imported, failed items, or file-level duplicates cannot be bulk imported.');
      return;
    }

    const selectedCount = selectedItems.size;
    if (!window.confirm(`Are you sure you want to import ${selectedCount} item${selectedCount === 1 ? '' : 's'} without reviewing them?`)) {
      return;
    }

    const itemIds = Array.from(selectedItems);
    // The store optimistically marks these items as importing and starts the bulk_import_job
    // WebSocket module; BulkImportJobModule dispatches the completion/failure back into it.
    void store.dispatch('importQueue/startBulkImport', { itemIds, importCustomIcons: true });
    clearSelection();
  }

  async function bulkDelete(): Promise<void> {
    if (selectedItems.size === 0) {
      return;
    }

    const invalidItems: number[] = [];
    selectedItems.forEach((itemId) => {
      const item = filteredImportTable.value.find((i) => i.id === itemId);
      if (item && (item.imported || item.deleting)) {
        invalidItems.push(itemId);
      }
    });
    invalidItems.forEach((itemId) => selectedItems.delete(itemId));

    if (selectedItems.size === 0) {
      toast.error('No valid items selected for deletion. Imported items or items being deleted cannot be bulk deleted.');
      return;
    }

    const selectedCount = selectedItems.size;
    if (!window.confirm(`Are you sure you want to delete ${selectedCount} item${selectedCount === 1 ? '' : 's'}? Deleted items cannot be recovered.`)) {
      return;
    }

    const itemIds = Array.from(selectedItems);
    // The store optimistically marks these items as deleting and starts the bulk_delete_job
    // WebSocket module; BulkDeleteJobModule dispatches the completion/failure back into it.
    void store.dispatch('importQueue/startBulkDelete', { itemIds });
    clearSelection();
  }

  // A full replacement of importTable (SET_IMPORT_TABLE) only happens once the queue's
  // "initial_state" has been (re)loaded; incremental item add/remove/update mutations don't
  // reassign the array reference, so this fires exactly when the old `setImportTable`-mutation
  // subscription used to.
  watch(importTable, (newTable) => {
    hasInitiallyLoaded.value = true;
    internalLoading.value = false;
    isRefreshing.value = false;
    checkForRestoreItems(newTable);
  });

  watch(lastBulkImportOutcome, (outcome) => {
    if (!outcome) return;
    window.alert(outcome.message);
    void store.dispatch('importQueue/clearLastBulkImportOutcome');
  });

  watch(lastBulkDeleteOutcome, (outcome) => {
    if (!outcome) return;
    window.alert(outcome.message);
    void store.dispatch('importQueue/clearLastBulkDeleteOutcome');
  });

  function initialize(): void {
    if (importTable.value.length > 0) {
      hasInitiallyLoaded.value = true;
      internalLoading.value = false;
    } else {
      fetchQueueList();
    }
  }

  /** Fallback kick in case nothing has arrived by the time the component mounts. */
  function requestRefreshIfStillEmpty(): void {
    if (!hasInitiallyLoaded.value && !hasRequestedInitialLoad.value && websocketConnected.value && importTable.value.length === 0) {
      fetchQueueList();
    }
  }

  return {
    importTable,
    filteredImportTable,
    combinedLoading,
    hasInitiallyLoaded,
    validImportableCount,
    isBulkImporting,
    isBulkDeleting,
    websocketConnected,
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
  };
}

export type UseImportQueueTable = ReturnType<typeof useImportQueueTable>;
