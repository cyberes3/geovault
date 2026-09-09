import { computed, reactive, ref, type Ref } from 'vue';
import type { ImportFeatureItem, ImportFeatureProperties } from '@/assets/js/types/import-types';
import { GeoFeatureTypeStrings } from '@/assets/js/types/geofeature-strings';
import { GeoPoint, GeoLineString, GeoPolygon } from '@/assets/js/types/geofeature-types';
import { searchImportItems } from '@/api/services/importApi';
import { isValidPageNumber } from '@/utils/import/paginationUtils';
import { getFeatureId } from '@/utils/import/duplicateDetection';
import { initializeFeatureDefaults } from '@/utils/import/featureProcessing';
import { PROCESSING_MESSAGES } from '@/assets/js/constants/processing-messages';
import { toastApiError } from '@/utils/apiError';
import {
    type DuplicateCountsWire,
    type SkipIntentWire,
    emptyDuplicateCounts,
    emptySkipIntent,
    parseDuplicateCounts,
    parseSkipIntent,
    parseVerdict,
} from '@/contracts/duplicates';

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
}

export interface RawImportPageItem {
    error?: boolean;
    message?: string;
    geometry: { type: string; coordinates: unknown };
    properties: ImportFeatureProperties;
    id?: string | number;
    duplicate_verdict?: unknown;
}

export interface RawImportPagePayload {
    data?: RawImportPageItem[];
    pagination?: {
        page: number;
        total_features: number;
        total_pages: number;
        has_next: boolean;
        has_previous: boolean;
    };
    skip_intent?: unknown;
    duplicate_counts?: unknown;
    feature_count?: number;
}

export interface SearchResultMatch {
    page: number;
    feature_index: number;
    feature: { properties?: { name?: string; description?: string; geojson_hash?: string } };
}

interface UseImportProcessDataOptions {
    importId: Ref<string | number | null>;
    requestPage: (page: number, pageSize: number, hideDuplicates: boolean) => void;
    onUnparsableFile: (message: string) => void;
}

export function cloneFeatureForSnapshot(item: ImportFeatureItem): ImportFeatureItem {
    return {
        ...item,
        duplicate_verdict: item.duplicate_verdict ? { ...item.duplicate_verdict } : item.duplicate_verdict,
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

    const hideDuplicates = ref(false);
    const draftCount = ref(0);
    const duplicateCounts = ref<DuplicateCountsWire>(emptyDuplicateCounts());
    const serverSkipIntent = ref<SkipIntentWire>(emptySkipIntent());

    const editCache: EditCache = reactive({
        pages: {},
        originals: {},
    });

    const searchQuery = ref('');
    const searchResults = ref<SearchResultMatch[]>([]);
    const totalSearchMatches = ref(0);
    const isSearching = ref(false);
    let searchTimeout: ReturnType<typeof setTimeout> | null = null;

    const hashDuplicateCount = computed(() => duplicateCounts.value.hash);
    const totalDuplicateCount = computed(() => duplicateCounts.value.hash + duplicateCounts.value.geometry);

    const filteredItemsForUser = computed(() =>
        itemsForUser.value.map((item, originalIndex) => ({ item, originalIndex })),
    );

    function parseGeoJson(item: RawImportPageItem): ImportFeatureItem {
        const verdict = parseVerdict(item.duplicate_verdict);
        const withVerdict = { ...item, duplicate_verdict: verdict };
        switch (item.geometry.type) {
            case GeoFeatureTypeStrings.Point:
            case GeoFeatureTypeStrings.MultiPoint:
                return new GeoPoint(withVerdict);
            case GeoFeatureTypeStrings.LineString:
            case GeoFeatureTypeStrings.MultiLineString:
                return new GeoLineString(withVerdict);
            case GeoFeatureTypeStrings.Polygon:
            case GeoFeatureTypeStrings.MultiPolygon:
                return new GeoPolygon(withVerdict);
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
    }

    function handlePageData(data: RawImportPagePayload): void {
        itemsForUser.value = [];

        if (data.data && data.data.length > 0) {
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
        }

        if (data.duplicate_counts !== undefined) {
            duplicateCounts.value = parseDuplicateCounts(data.duplicate_counts);
        }
        if (typeof data.feature_count === 'number') {
            draftCount.value = data.feature_count;
        } else if (!hideDuplicates.value && data.pagination) {
            draftCount.value = data.pagination.total_features;
        }
        if (data.skip_intent !== undefined) {
            serverSkipIntent.value = parseSkipIntent(data.skip_intent);
        }

        loadingPage.value = false;
    }

    async function loadPage(page: number): Promise<void> {
        cacheCurrentPageChanges();
        loadingPage.value = true;
        requestPage(page, pagination.pageSize, hideDuplicates.value);
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
        if (isValidPageNumber(page, Math.max(pagination.totalPages, 1))) {
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
        draftCount.value = 0;
        duplicateCounts.value = emptyDuplicateCounts();
        serverSkipIntent.value = emptySkipIntent();
        editCache.pages = {};
        editCache.originals = {};
        clearSearch();
    }

    return {
        msg,
        loadingPage,
        itemsForUser,
        originalItems,
        pagination,
        hideDuplicates,
        draftCount,
        duplicateCounts,
        serverSkipIntent,
        editCache,
        hashDuplicateCount,
        totalDuplicateCount,
        filteredItemsForUser,
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
        getFeatureId: (item: ImportFeatureItem | null | undefined, index: number) =>
            getFeatureId(item, index, pagination.currentPage, pagination.pageSize),
        reset,
    };
}

export type UseImportProcessData = ReturnType<typeof useImportProcessData>;
