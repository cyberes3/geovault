import { computed, reactive, ref, type Ref } from 'vue';
import type { ImportFeatureItem, ImportFeatureProperties, ImportDuplicateSets } from '@/assets/js/types/import-types';
import { GeoFeatureTypeStrings } from '@/assets/js/types/geofeature-strings';
import { GeoPoint, GeoLineString, GeoPolygon } from '@/assets/js/types/geofeature-types';
import { searchImportItems } from '@/api/services/importApi';
import {
  calculateAdjustedTotalPages,
  calculateAdjustedHasNext,
  calculateAdjustedHasPrevious,
  calculateImportableCount,
} from '@/utils/import/paginationUtils';
import {
  calculateTotalDuplicateCount,
  calculateHashDuplicateCount,
  markDuplicateFeatures,
  isItemDuplicate,
  isItemHashDuplicate,
  getFeatureId,
  isItemSkipped as isItemSkippedUtil,
  isItemDisabled as isItemDisabledUtil,
} from '@/utils/import/duplicateDetection';
import { initializeFeatureDefaults } from '@/utils/import/featureProcessing';
import { PROCESSING_MESSAGES } from '@/assets/js/constants/processing-messages';
import { toastApiError } from '@/utils/apiError';
import { arrayAt } from '@/utils/arrayUtils';

export interface PaginationState {
  currentPage: number;
  pageSize: number;
  totalFeatures: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;
}

export interface EditCache {
  pages: Partial<Record<number, ImportFeatureItem[]>>;
  originals: Partial<Record<number, ImportFeatureItem[]>>;
  skippedFeatureIds: Set<string>;
}

/** Raw feature entry as sent by the backend inside a `page`/`initial_state` WebSocket message. */
export interface RawImportPageItem {
  error?: boolean;
  message?: string;
  geometry: { type: string; coordinates: unknown };
  properties: ImportFeatureProperties;
  id?: string | number;
}

interface RawDuplicateEntry {
  page_index: number;
  hash?: string;
  feature_store_id?: number;
  global_index?: number;
  queue_item_id?: number;
  queue_item_filename?: string;
}

/** Payload shape of the `page` message (and the `features` field of `initial_state`). */
export interface RawImportPagePayload {
  data?: RawImportPageItem[];
  pagination?: {
    page: number;
    total_features: number;
    total_pages: number;
    has_next: boolean;
    has_previous: boolean;
    duplicate_indices?: number[];
  };
  skipped_feature_ids?: string[];
  duplicates?: {
    feature_store_hash?: RawDuplicateEntry[];
    feature_store_geometry?: RawDuplicateEntry[];
    cross_queue_hash?: RawDuplicateEntry[];
    cross_queue_geometry?: RawDuplicateEntry[];
  };
}

export interface SearchResultMatch {
  page: number;
  feature_index: number;
  feature: { properties?: { name?: string; description?: string; geojson_hash?: string } };
}

interface UseImportProcessDataOptions {
  importId: Ref<string | number | null>;
  /** Ask the caller (the WebSocket connection) to request a given page of features. */
  requestPage: (page: number, pageSize: number) => void;
  /** Called when the backend reports the uploaded file itself could not be parsed. */
  onUnparsableFile: (message: string) => void;
}

/**
 * Structurally clones just the mutable parts of a feature (its `properties`, including array
 * fields that get replaced wholesale like `tags`) instead of a full `JSON.parse(JSON.stringify())`
 * of the entire feature -- `geometry` (which can hold large coordinate arrays for lines/polygons)
 * is never mutated by the editing UI, so it's safe to keep sharing the same reference.
 */
export function cloneFeatureForSnapshot(item: ImportFeatureItem): ImportFeatureItem {
  return {
    ...item,
    properties: {
      ...item.properties,
      tags: item.properties.tags ? [...item.properties.tags] : item.properties.tags,
      system_tags: item.properties.system_tags ? [...item.properties.system_tags] : item.properties.system_tags,
    },
  };
}

function cloneFeatureList(items: ImportFeatureItem[]): ImportFeatureItem[] {
  return items.map(cloneFeatureForSnapshot);
}

/**
 * Data fetching + pagination + search for the import queue item being processed on
 * `ImportProcessPage.vue`. Page navigation itself is driven by the page's `ImportStatusSocket`
 * connection (via `requestPage`); this composable owns the resulting state, per-page edit cache,
 * duplicate bookkeeping, and skip-state.
 */
export function useImportProcessData(options: UseImportProcessDataOptions) {
  const { importId, requestPage, onUnparsableFile } = options;

  const msg = ref('');
  const loadingPage = ref(false);

  const itemsForUser = ref<ImportFeatureItem[]>([]);
  const originalItems = ref<ImportFeatureItem[]>([]);

  const pagination = reactive<PaginationState>({
    currentPage: 1,
    pageSize: 50,
    totalFeatures: 0,
    totalPages: 0,
    hasNext: false,
    hasPrevious: false,
  });

  const duplicates = reactive<ImportDuplicateSets>({
    featureStoreHash: [],
    featureStoreGeometry: [],
    crossQueueHash: [],
    crossQueueGeometry: [],
    indices: [],
  });

  const hideDuplicates = ref(false);
  const skippedFeatureIds = ref<Set<string>>(new Set());

  const editCache: EditCache = reactive({
    pages: {},
    originals: {},
    skippedFeatureIds: new Set<string>(),
  });

  const searchQuery = ref('');
  const searchResults = ref<SearchResultMatch[]>([]);
  const totalSearchMatches = ref(0);
  const isSearching = ref(false);
  let searchTimeout: ReturnType<typeof setTimeout> | null = null;

  const hashDuplicateCount = computed(() => calculateHashDuplicateCount(duplicates));
  const totalDuplicateCount = computed(() => calculateTotalDuplicateCount(duplicates));
  const importableCount = computed(() => calculateImportableCount(pagination.totalFeatures, hashDuplicateCount.value, skippedFeatureIds.value));

  const adjustedTotalPages = computed(() => calculateAdjustedTotalPages(
    pagination.totalFeatures, totalDuplicateCount.value, pagination.pageSize, hideDuplicates.value, pagination.totalPages,
  ));
  const adjustedHasNext = computed(() => calculateAdjustedHasNext(pagination.currentPage, adjustedTotalPages.value, hideDuplicates.value, pagination.hasNext));
  const adjustedHasPrevious = computed(() => calculateAdjustedHasPrevious(pagination.currentPage, hideDuplicates.value, pagination.hasPrevious));

  const filteredItemsForUser = computed(() => {
    const entries = itemsForUser.value.map((item, originalIndex) => ({ item, originalIndex }));
    if (hideDuplicates.value) {
      return entries.filter(({ item }) => !isItemDuplicate(item));
    }
    return entries;
  });

  const showEmptyPageMessage = computed(() =>
    hideDuplicates.value &&
    itemsForUser.value.length > 0 &&
    filteredItemsForUser.value.length === 0 &&
    !loadingPage.value,
  );

  function parseGeoJson(item: RawImportPageItem): ImportFeatureItem {
    switch (item.geometry.type) {
      case GeoFeatureTypeStrings.Point:
      case GeoFeatureTypeStrings.MultiPoint:
        return new GeoPoint(item);
      case GeoFeatureTypeStrings.LineString:
      case GeoFeatureTypeStrings.MultiLineString:
        return new GeoLineString(item);
      case GeoFeatureTypeStrings.Polygon:
      case GeoFeatureTypeStrings.MultiPolygon:
        return new GeoPolygon(item);
      default:
        throw new Error(`Invalid feature type: ${item.geometry.type}`);
    }
  }

  function cacheCurrentPageChanges(): void {
    if (pagination.currentPage && itemsForUser.value.length > 0) {
      editCache.pages[pagination.currentPage] = cloneFeatureList(itemsForUser.value);
      if (originalItems.value.length > 0) {
        editCache.originals[pagination.currentPage] = cloneFeatureList(originalItems.value);
      }
    }
    editCache.skippedFeatureIds = new Set(skippedFeatureIds.value);
  }

  function restoreCachedPageChanges(page: number): void {
    const cachedPage = editCache.pages[page];
    if (cachedPage) {
      itemsForUser.value = cloneFeatureList(cachedPage);
      const cachedOriginals = editCache.originals[page];
      if (cachedOriginals) {
        originalItems.value = cloneFeatureList(cachedOriginals);
      }
    }
    skippedFeatureIds.value = new Set(editCache.skippedFeatureIds);
  }

  function handlePageData(data: RawImportPagePayload): void {
    itemsForUser.value = [];

    if (data.data && data.data.length > 0) {
      // A single `{error: true}` entry means the whole file was unparsable.
      if (data.data.length === 1 && data.data[0].error) {
        const errorItem = data.data[0];
        msg.value = errorItem.message ?? PROCESSING_MESSAGES.FILE_PROCESSING_FAILED_WITH_LOGS;
        loadingPage.value = false;
        onUnparsableFile(msg.value);
        return;
      }

      const parsed: ImportFeatureItem[] = [];
      data.data.forEach((item) => {
        if (item.error) return;
        initializeFeatureDefaults(item);
        parsed.push(parseGeoJson(item));
      });
      itemsForUser.value = parsed;
      originalItems.value = cloneFeatureList(parsed);

      if (data.pagination) {
        restoreCachedPageChanges(data.pagination.page);
      }
    }

    if (data.pagination) {
      pagination.currentPage = data.pagination.page;
      pagination.totalFeatures = data.pagination.total_features;
      pagination.totalPages = data.pagination.total_pages;
      pagination.hasNext = data.pagination.has_next;
      pagination.hasPrevious = data.pagination.has_previous;
      duplicates.indices = data.pagination.duplicate_indices ?? [];
    }

    if (data.skipped_feature_ids) {
      const merged = new Set(skippedFeatureIds.value);
      data.skipped_feature_ids.forEach((featureId) => merged.add(featureId));
      skippedFeatureIds.value = merged;
      editCache.skippedFeatureIds = new Set(merged);
    }

    if (data.duplicates) {
      duplicates.featureStoreHash = data.duplicates.feature_store_hash ?? [];
      duplicates.featureStoreGeometry = data.duplicates.feature_store_geometry ?? [];
      duplicates.crossQueueHash = data.duplicates.cross_queue_hash ?? [];
      duplicates.crossQueueGeometry = data.duplicates.cross_queue_geometry ?? [];
      markDuplicateFeatures(itemsForUser.value, duplicates);
    }

    loadingPage.value = false;
  }

  async function loadPage(page: number): Promise<void> {
    cacheCurrentPageChanges();
    loadingPage.value = true;
    requestPage(page, pagination.pageSize);
  }

  async function nextPage(): Promise<void> {
    if (pagination.hasNext) {
      await loadPage(pagination.currentPage + 1);
    }
  }

  async function previousPage(): Promise<void> {
    if (pagination.hasPrevious) {
      await loadPage(pagination.currentPage - 1);
    }
  }

  async function goToPage(page: number): Promise<void> {
    if (page >= 1 && page <= pagination.totalPages) {
      await loadPage(page);
    }
  }

  function handleSearchInput(): void {
    if (searchTimeout) {
      clearTimeout(searchTimeout);
    }
    searchTimeout = setTimeout(() => {
      void performSearch();
    }, 300);
  }

  async function performSearch(): Promise<void> {
    if (!searchQuery.value.trim()) {
      searchResults.value = [];
      totalSearchMatches.value = 0;
      isSearching.value = false;
      return;
    }

    if (importId.value == null) {
      return;
    }

    isSearching.value = true;
    try {
      const data = (await searchImportItems(importId.value, searchQuery.value.trim())) as {
        matches?: SearchResultMatch[];
        total_matches?: number;
      };
      searchResults.value = data.matches ?? [];
      totalSearchMatches.value = data.total_matches ?? 0;
    } catch (error) {
      toastApiError(error, 'Error searching features');
      searchResults.value = [];
      totalSearchMatches.value = 0;
    } finally {
      isSearching.value = false;
    }
  }

  function clearSearch(): void {
    searchQuery.value = '';
    searchResults.value = [];
    totalSearchMatches.value = 0;
    isSearching.value = false;
    if (searchTimeout) {
      clearTimeout(searchTimeout);
      searchTimeout = null;
    }
  }

  function toggleSkipItem(index: number): void {
    const item = arrayAt(itemsForUser.value, index);
    if (!item) {
      console.warn('toggleSkipItem: item not found at index', index);
      return;
    }

    const featureId = getFeatureId(item, index, pagination.currentPage, pagination.pageSize);
    const updated = new Set(skippedFeatureIds.value);
    if (updated.has(featureId)) {
      updated.delete(featureId);
    } else {
      updated.add(featureId);
    }
    skippedFeatureIds.value = updated;
    editCache.skippedFeatureIds = new Set(updated);
  }

  function isItemSkipped(item: ImportFeatureItem | null | undefined, index: number): boolean {
    return isItemSkippedUtil(item, index, skippedFeatureIds.value, pagination.currentPage, pagination.pageSize);
  }

  function isItemDisabled(item: ImportFeatureItem | null | undefined, index: number, isImported: boolean, isImporting: boolean): boolean {
    return isItemDisabledUtil(item, index, isImported, isImporting, skippedFeatureIds.value, pagination.currentPage, pagination.pageSize);
  }

  /** Reset all state, e.g. when navigating away or switching to a different queue item. */
  function reset(): void {
    msg.value = '';
    loadingPage.value = false;
    itemsForUser.value = [];
    originalItems.value = [];
    pagination.currentPage = 1;
    pagination.totalFeatures = 0;
    pagination.totalPages = 0;
    pagination.hasNext = false;
    pagination.hasPrevious = false;
    duplicates.featureStoreHash = [];
    duplicates.featureStoreGeometry = [];
    duplicates.crossQueueHash = [];
    duplicates.crossQueueGeometry = [];
    duplicates.indices = [];
    editCache.pages = {};
    editCache.originals = {};
    editCache.skippedFeatureIds = new Set();
    skippedFeatureIds.value = new Set();
    clearSearch();
  }

  return {
    msg,
    loadingPage,
    itemsForUser,
    originalItems,
    pagination,
    duplicates,
    hideDuplicates,
    skippedFeatureIds,
    editCache,

    hashDuplicateCount,
    totalDuplicateCount,
    importableCount,
    adjustedTotalPages,
    adjustedHasNext,
    adjustedHasPrevious,
    filteredItemsForUser,
    showEmptyPageMessage,

    searchQuery,
    searchResults,
    totalSearchMatches,
    isSearching,
    handleSearchInput,
    performSearch,
    clearSearch,

    parseGeoJson,
    handlePageData,
    cacheCurrentPageChanges,
    restoreCachedPageChanges,
    loadPage,
    nextPage,
    previousPage,
    goToPage,

    toggleSkipItem,
    isItemSkipped,
    isItemDisabled,
    isItemDuplicate,
    isItemHashDuplicate,
    getFeatureId: (item: ImportFeatureItem | null | undefined, index: number) => getFeatureId(item, index, pagination.currentPage, pagination.pageSize),

    reset,
  };
}

export type UseImportProcessData = ReturnType<typeof useImportProcessData>;
