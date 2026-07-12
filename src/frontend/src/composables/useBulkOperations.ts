import { computed, ref, type Ref } from 'vue';
import {
  DEFAULT_BULK_OPERATIONS,
  cloneBulkOperations,
  hasBulkOperationsConfigured as hasBulkOperationsConfiguredUtil,
  areBulkOperationsEqual,
  type BulkOperations,
  type RawBulkOperations,
} from '@/utils/bulkOperations';
import { getImportBulkOperations, updateImportBulkOperations } from '@/api/services/importApi';
import { toastApiError } from '@/utils/apiError';

/**
 * Bulk-operations modal state for the import processing page: loads/saves the raw bulk
 * operations config for a queue item, and tracks whether it has unsaved changes so
 * `ImportProcessPage` can fold saving it into its own "Save Changes" flow.
 */
export function useBulkOperations(importId: Ref<string | number | null>) {
  const isModalOpen = ref(false);

  // Normalized, for display in the modal.
  const bulkOperations = ref<BulkOperations>(cloneBulkOperations(DEFAULT_BULK_OPERATIONS));
  // Raw (pre-normalization), for saving and for comparing "was a key present at all".
  const originalBulkOperations = ref<RawBulkOperations>({});
  // Raw, exactly what was last loaded from/saved to the backend.
  const loadedBulkOperations = ref<RawBulkOperations>({});

  const hasBulkOperationsConfigured = computed(() => hasBulkOperationsConfiguredUtil(bulkOperations.value));
  const hasBulkOperationsChanged = computed(() => !areBulkOperationsEqual(originalBulkOperations.value, loadedBulkOperations.value));

  async function loadBulkOperations(): Promise<void> {
    if (importId.value == null) return;

    try {
      const data = (await getImportBulkOperations(importId.value)) as { bulk_operations?: RawBulkOperations };
      const ops = data.bulk_operations;
      if (ops) {
        bulkOperations.value = cloneBulkOperations(ops);
        const rawOps: RawBulkOperations = { ...ops };
        originalBulkOperations.value = rawOps;
        loadedBulkOperations.value = rawOps;
      } else {
        // No bulk operations found, use empty object (not DEFAULT_BULK_OPERATIONS) so we can
        // detect when keys are later added (e.g. pointIcon: null for "default icon").
        bulkOperations.value = cloneBulkOperations({});
        originalBulkOperations.value = {};
        loadedBulkOperations.value = {};
      }
    } catch (error) {
      console.error('Error loading bulk operations:', error);
      bulkOperations.value = cloneBulkOperations({});
      originalBulkOperations.value = {};
      loadedBulkOperations.value = {};
    }
  }

  /** Update local state only; persisting happens later via `saveBulkOperations`. */
  function updateBulkOperations(bulkData: RawBulkOperations): void {
    bulkOperations.value = cloneBulkOperations(bulkData);
    originalBulkOperations.value = { ...bulkData };
  }

  async function saveBulkOperations(bulkData: RawBulkOperations): Promise<void> {
    if (importId.value == null) return;

    try {
      await updateImportBulkOperations(importId.value, bulkData);
      bulkOperations.value = cloneBulkOperations(bulkData);
      const rawOps: RawBulkOperations = { ...bulkData };
      originalBulkOperations.value = rawOps;
      loadedBulkOperations.value = rawOps;
    } catch (error) {
      toastApiError(error, 'Error saving bulk operations');
      throw error; // Let the caller's save-changes flow know this step failed.
    }
  }

  function openModal(): void {
    isModalOpen.value = true;
  }

  function closeModal(): void {
    isModalOpen.value = false;
  }

  function reset(): void {
    isModalOpen.value = false;
    bulkOperations.value = cloneBulkOperations(DEFAULT_BULK_OPERATIONS);
    originalBulkOperations.value = {};
    loadedBulkOperations.value = {};
  }

  return {
    isModalOpen,
    bulkOperations,
    originalBulkOperations,
    loadedBulkOperations,
    hasBulkOperationsConfigured,
    hasBulkOperationsChanged,
    loadBulkOperations,
    updateBulkOperations,
    saveBulkOperations,
    openModal,
    closeModal,
    reset,
  };
}

export type UseBulkOperations = ReturnType<typeof useBulkOperations>;
