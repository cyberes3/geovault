import { computed, onBeforeUnmount, reactive, type ComputedRef, type Ref } from 'vue';
import type { GeoJsonFeature } from '@/types/geospatial';

export const TAG_FEATURE_PAGE_SIZE = 10;
const TAG_SEARCH_DEBOUNCE_MS = 400;

/** Paginated + search-filtered view of a single tag's features, memoized per tag. */
export interface TagFeatureView {
    /** Features for the current page, after search filtering. */
    features: GeoJsonFeature[];
    /** Total features for this tag, unfiltered (drives the ">10" search/pagination gate). */
    totalCount: number;
    /** Features remaining after search filtering, across all pages. */
    filteredCount: number;
    currentPage: number;
    totalPages: number;
    hasNextPage: boolean;
    hasPreviousPage: boolean;
    /** Placeholder row count so the card height stays stable across partially-filled pages. */
    placeholderCount: number;
}

function emptyView(): TagFeatureView {
    return {
        features: [],
        totalCount: 0,
        filteredCount: 0,
        currentPage: 1,
        totalPages: 1,
        hasNextPage: false,
        hasPreviousPage: false,
        placeholderCount: 0,
    };
}

function matchesSearch(feature: GeoJsonFeature, query: string): boolean {
    const name = String(feature.properties.name ?? '').toLowerCase();
    const description = String(feature.properties.description ?? '').toLowerCase();
    const geometryType = String(feature.geometry.type).toLowerCase();
    return name.includes(query) || description.includes(query) || geometryType.includes(query);
}

/**
 * Per-tag search + pagination for the tags page feature lists.
 *
 * All per-tag views are derived through a single memoized computed map, keyed by tag
 * name, so expanding/paging/searching one tag only recomputes that tag's entry's
 * dependencies (tag data, its committed search text, its page) rather than re-scanning
 * every tag's feature array on every render.
 */
export function useTagFeaturePagination(tagsData: Ref<Record<string, GeoJsonFeature[]>>) {
    const tagCurrentPages = reactive<Record<string, number>>({});
    // Raw, immediate value bound to the search input (keeps typing responsive).
    const tagSearchInputs = reactive<Record<string, string>>({});
    // Debounced value that actually drives filtering, mirroring the page-level search debounce.
    const tagSearchCommitted = reactive<Record<string, string>>({});
    const debounceTimers = new Map<string, ReturnType<typeof setTimeout>>();

    const tagFeatureViews: ComputedRef<Record<string, TagFeatureView>> = computed(() => {
        const views: Record<string, TagFeatureView> = {};
        const data = tagsData.value;

        for (const tag of Object.keys(data)) {
            const rawFeatures = data[tag];
            const committedQuery = (tagSearchCommitted[tag] ?? '').trim().toLowerCase();
            const filtered = committedQuery
                ? rawFeatures.filter((feature) => matchesSearch(feature, committedQuery))
                : rawFeatures;

            const totalPages = Math.max(1, Math.ceil(filtered.length / TAG_FEATURE_PAGE_SIZE));
            const requestedPage = tagCurrentPages[tag] ?? 1;
            const currentPage = Math.min(Math.max(requestedPage, 1), totalPages);
            const startIndex = (currentPage - 1) * TAG_FEATURE_PAGE_SIZE;
            const pageFeatures = filtered.slice(startIndex, startIndex + TAG_FEATURE_PAGE_SIZE);
            const hasMultiplePages = filtered.length > TAG_FEATURE_PAGE_SIZE;

            views[tag] = {
                features: pageFeatures,
                totalCount: rawFeatures.length,
                filteredCount: filtered.length,
                currentPage,
                totalPages,
                hasNextPage: currentPage < totalPages,
                hasPreviousPage: currentPage > 1,
                placeholderCount: hasMultiplePages ? Math.max(0, TAG_FEATURE_PAGE_SIZE - pageFeatures.length) : 0,
            };
        }

        return views;
    });

    function getTagView(tag: string): TagFeatureView {
        return tagFeatureViews.value[tag] ?? emptyView();
    }

    function getTagSearchQuery(tag: string): string {
        return tagSearchInputs[tag] ?? '';
    }

    function updateTagSearchQuery(tag: string, query: string): void {
        tagSearchInputs[tag] = query;

        const existingTimer = debounceTimers.get(tag);
        if (existingTimer !== undefined) {
            clearTimeout(existingTimer);
        }

        debounceTimers.set(
            tag,
            setTimeout(() => {
                tagSearchCommitted[tag] = query;
                tagCurrentPages[tag] = 1;
                debounceTimers.delete(tag);
            }, TAG_SEARCH_DEBOUNCE_MS),
        );
    }

    function tagNextPage(tag: string): void {
        const view = getTagView(tag);
        if (view.hasNextPage) {
            tagCurrentPages[tag] = view.currentPage + 1;
        }
    }

    function tagPreviousPage(tag: string): void {
        const view = getTagView(tag);
        if (view.hasPreviousPage) {
            tagCurrentPages[tag] = view.currentPage - 1;
        }
    }

    onBeforeUnmount(() => {
        for (const timer of debounceTimers.values()) {
            clearTimeout(timer);
        }
        debounceTimers.clear();
    });

    return {
        tagFeaturePageSize: TAG_FEATURE_PAGE_SIZE,
        tagFeatureViews,
        getTagView,
        getTagSearchQuery,
        updateTagSearchQuery,
        tagNextPage,
        tagPreviousPage,
    };
}
