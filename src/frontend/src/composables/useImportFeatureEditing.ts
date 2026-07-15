import { ref, type Ref } from 'vue';
import type { ImportFeatureItem } from '@/assets/js/types/import-types';
import type { EditCache, PaginationState } from './useImportProcessData';
import { cloneFeatureForSnapshot } from './useImportProcessData';
import {
  isPointGeometry,
  isLineGeometry,
  isPolygonGeometry,
  handleStrokeColorChange as applyStrokeColorChange,
} from '@/utils/import/featureProcessing';
import { isSystemIcon } from '@/utils/import/iconDetection';
import { getFeaturesByTag } from '@/api/services/featuresApi';
import { sortUserTagsAlphabetically } from '@/utils/tagUtils';
import { arrayAt } from '@/utils/arrayUtils';
import {
  updateImportFeatures,
  updateImportSkipState,
  performImport as performImportRequest,
  recheckImportDuplicates,
} from '@/api/services/importApi';
import { toastApiError } from '@/utils/apiError';

interface IconSelectedEvent {
  iconUrl: string;
  isSystemIcon?: boolean;
}

interface UseImportFeatureEditingOptions {
  importId: Ref<string | number | null>;
  itemsForUser: Ref<ImportFeatureItem[]>;
  originalItems: Ref<ImportFeatureItem[]>;
  pagination: PaginationState;
  editCache: EditCache;
  duplicates: { indices: number[] };
  skippedFeatureIds: Ref<Set<string>>;
}

/** A partial feature update in the shape the backend's `update` endpoint expects. */
interface FeatureUpdatePayload {
  properties: {
    geojson_hash: string | undefined;
    name?: string;
    description?: string;
    created?: string;
    tags?: string[];
  };
}

function getComparableFeature(feature: ImportFeatureItem): unknown {
  // Tags are already separated out (user tags only in `properties.tags`).
  return {
    type: feature.type,
    geometry: feature.geometry,
    properties: { ...feature.properties },
  };
}

function haveFeaturesChanged(current: ImportFeatureItem | undefined, original: ImportFeatureItem | undefined): boolean {
  if (!current || !original) return false;
  return JSON.stringify(getComparableFeature(current)) !== JSON.stringify(getComparableFeature(original));
}

/**
 * Prepare a partial update for sending to the backend. Only `geojson_hash` (required, used to
 * match the feature) plus the handful of user-editable fields are sent.
 */
function prepareFeatureForBackend(feature: ImportFeatureItem): FeatureUpdatePayload {
  const properties = feature.properties;
  const geojsonHash = properties.geojson_hash ?? (feature.id != null ? String(feature.id) : undefined);

  const partialUpdate: FeatureUpdatePayload = {
    properties: { geojson_hash: geojsonHash },
  };
  if (properties.name !== undefined) {
    partialUpdate.properties.name = properties.name;
  }
  if (properties.description !== undefined) {
    partialUpdate.properties.description = properties.description;
  }
  if (properties.created != null) {
    partialUpdate.properties.created = properties.created;
  }
  if (properties.tags !== undefined) {
    partialUpdate.properties.tags = properties.tags;
  }
  return partialUpdate;
}

/**
 * Per-feature edit state for `ImportProcessPage.vue`: field resets, icon/color/tag handlers,
 * and diffing/saving the current page + all cached pages against their original snapshots.
 */
export function useImportFeatureEditing(options: UseImportFeatureEditingOptions) {
  const { importId, itemsForUser, originalItems, pagination, editCache, duplicates, skippedFeatureIds } = options;

  const availableUserTags = ref<string[]>([]);

  async function fetchUserTags(): Promise<void> {
    try {
      const data = (await getFeaturesByTag()) as { user_tags?: Record<string, unknown> };
      if (data.user_tags) {
        availableUserTags.value = sortUserTagsAlphabetically(Object.keys(data.user_tags));
      } else {
        availableUserTags.value = [];
      }
    } catch (error) {
      toastApiError(error, 'Error fetching user tags');
      availableUserTags.value = [];
    }
  }

  function getSystemTags(item: ImportFeatureItem | null | undefined): string[] {
    const systemTags = item?.properties.system_tags;
    if (!Array.isArray(systemTags)) return [];
    return systemTags.filter((tag) => tag.trim() !== '');
  }

  function resetNestedField(index: number, nestedField: 'properties', fieldName: keyof ImportFeatureItem['properties']): void {
    const item = arrayAt(itemsForUser.value, index);
    const original = arrayAt(originalItems.value, index);
    if (!item || !original) return;
    item[nestedField][fieldName] = original[nestedField][fieldName];
  }

  function updateDate(index: number, event: Event): void {
    const item = arrayAt(itemsForUser.value, index);
    if (!item) return;
    const dateValue = (event.target as HTMLInputElement).value;
    // datetime-local format is YYYY-MM-DDTHH:MM (no seconds/timezone); treat as UTC.
    item.properties.created = dateValue ? new Date(`${dateValue}:00Z`).toISOString() : null;
  }

  function formatDateForInput(dateString: string | null | undefined): string {
    if (!dateString) return '';
    const date = new Date(dateString);
    if (isNaN(date.getTime())) return '';
    return date.toISOString().slice(0, 16);
  }

  function handleIconSelected(item: ImportFeatureItem, event: IconSelectedEvent): void {
    const iconUrl = event.iconUrl;
    item.properties.icon = iconUrl;
    item.properties['icon-href'] = iconUrl;
    item.properties.iconUrl = iconUrl;
    item.properties.icon_url = iconUrl;

    // If it's a system icon, ensure marker-color is set for recoloring.
    if (event.isSystemIcon && !item.properties['marker-color']) {
      item.properties['marker-color'] = '#ff0000';
    }
  }

  function handleIconRemoved(item: ImportFeatureItem): void {
    item.properties.icon = '';
    item.properties['icon-href'] = '';
    item.properties.iconUrl = '';
    item.properties.icon_url = '';
    item.properties['marker-icon'] = '';
    item.properties['marker-symbol'] = '';
    item.properties.symbol = '';

    item.properties['marker-color'] ??= '#ff0000';
  }

  function handleIconReset(index: number, item: ImportFeatureItem, originalIconUrl: string | null): void {
    if (originalIconUrl) {
      item.properties.icon = originalIconUrl;
      item.properties['icon-href'] = originalIconUrl;
      item.properties.iconUrl = originalIconUrl;
      item.properties.icon_url = originalIconUrl;
    } else {
      item.properties.icon = '';
      item.properties['icon-href'] = '';
      item.properties.iconUrl = '';
      item.properties.icon_url = '';
      item.properties['marker-icon'] = '';
      item.properties['marker-symbol'] = '';
      item.properties.symbol = '';
    }

    const originalItem = arrayAt(originalItems.value, index);
    const originalColor = originalItem?.properties['marker-color'];
    if (!originalIconUrl || isSystemIcon(originalIconUrl)) {
      // Default marker or system icon: reset color to original or default.
      item.properties['marker-color'] = originalColor || '#ff0000';
    } else {
      // External/user icon: not recolorable, shown in black.
      item.properties['marker-color'] = '#000000';
    }
  }

  function handleIconColorReset(index: number, item: ImportFeatureItem): void {
    const originalItem = arrayAt(originalItems.value, index);
    item.properties['marker-color'] = originalItem?.properties['marker-color'] || '#ff0000';
  }

  function handleStrokeColorChangeForItem(item: ImportFeatureItem): void {
    applyStrokeColorChange(item);
  }

  /** Collect changed features from the current page and every cached page. */
  function getChangedFeatures(): FeatureUpdatePayload[] {
    const changedFeatures: FeatureUpdatePayload[] = [];

    itemsForUser.value.forEach((feature, idx) => {
      if (!feature.isDuplicate && haveFeaturesChanged(feature, originalItems.value[idx])) {
        changedFeatures.push(prepareFeatureForBackend(feature));
      }
    });

    Object.entries(editCache.pages).forEach(([pageKey, cachedFeatures]) => {
      const pageNum = Number(pageKey);
      if (pageNum === pagination.currentPage || !cachedFeatures) return;

      const originalForPage = editCache.originals[pageNum] ?? [];
      cachedFeatures.forEach((feature, idx) => {
        const globalIdx = (pageNum - 1) * pagination.pageSize + idx;
        if (duplicates.indices.includes(globalIdx) || feature.isDuplicate) return;

        const original = arrayAt(originalForPage, idx);
        if (!original || haveFeaturesChanged(feature, original)) {
          changedFeatures.push(prepareFeatureForBackend(feature));
        }
      });
    });

    return changedFeatures;
  }

  function hasFeatureChanges(): boolean {
    return getChangedFeatures().length > 0;
  }

  /** Save the given (already-diffed) changed features, then resync originals to match. */
  async function saveFeatures(changedFeatures: FeatureUpdatePayload[]): Promise<{ updatedCount: number }> {
    if (importId.value == null) {
      return { updatedCount: 0 };
    }
    const data = (await updateImportFeatures(importId.value, changedFeatures)) as { updated_count?: number };

    // Current page now matches what we just saved.
    originalItems.value = itemsForUser.value.map(cloneFeatureForSnapshot);
    if (pagination.currentPage) {
      editCache.originals[pagination.currentPage] = originalItems.value.map(cloneFeatureForSnapshot);
    }

    // Cached (non-current) pages also now match what was saved.
    Object.keys(editCache.pages).forEach((pageKey) => {
      const pageNum = Number(pageKey);
      if (pageNum === pagination.currentPage) return;
      const cachedFeatures = editCache.pages[pageNum];
      if (cachedFeatures) {
        editCache.originals[pageNum] = cachedFeatures.map(cloneFeatureForSnapshot);
      }
    });

    return { updatedCount: data.updated_count ?? 0 };
  }

  async function saveSkipState(): Promise<void> {
    if (importId.value == null) return;
    const skippedFeatureIdsArray = Array.from(skippedFeatureIds.value).filter((id) => !id.startsWith('index_'));
    await updateImportSkipState(importId.value, skippedFeatureIdsArray);
  }

  async function requestImport(importCustomIcons: boolean): Promise<void> {
    if (importId.value == null) return;
    const skippedFeatureIdsArray = Array.from(skippedFeatureIds.value).filter((id) => !id.startsWith('index_'));
    await performImportRequest(importId.value, {
      import_custom_icons: importCustomIcons,
      skipped_feature_ids: skippedFeatureIdsArray,
    });
  }

  async function requestRecheckDuplicates(): Promise<void> {
    if (importId.value == null) return;
    await recheckImportDuplicates(importId.value);
  }

  return {
    availableUserTags,
    fetchUserTags,
    getSystemTags,

    isPointGeometry,
    isLineGeometry,
    isPolygonGeometry,

    resetNestedField,
    updateDate,
    formatDateForInput,
    handleIconSelected,
    handleIconRemoved,
    handleIconReset,
    handleIconColorReset,
    handleStrokeColorChangeForItem,

    getChangedFeatures,
    hasFeatureChanges,
    saveFeatures,
    saveSkipState,
    requestImport,
    requestRecheckDuplicates,
  };
}

export type UseImportFeatureEditing = ReturnType<typeof useImportFeatureEditing>;
