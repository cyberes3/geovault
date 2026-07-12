import { computed, nextTick, ref, watch, type Ref } from 'vue';
import { sortTagsByPriority, sortUserTagsAlphabetically, isSystemTag } from '@/utils/tagUtils.js';

export type TagMatchMode = 'AND' | 'OR';

export interface TaggedListItem {
    key: string;
    tag: string;
}

interface UseTagFilterEmit {
    (event: 'tag-filter-change', payload: { tags: string[]; matchMode: TagMatchMode }): void;
    (event: 'tag-filter-loading-change', loading: boolean): void;
}

export interface UseTagFilterOptions {
    availableTags: Ref<string[]>;
    initialSelectedTags: Ref<string[]>;
    emit: UseTagFilterEmit;
    /** Called when `initialSelectedTags` arrive so the parent can switch to the tag-filter tab. */
    onActivate: () => void;
}

/** Shared tag-filter state: used both by the tag-filter tab UI and the parent's tab-button indicator dot. */
export function useTagFilter(options: UseTagFilterOptions) {
    const { availableTags, initialSelectedTags, emit, onActivate } = options;

    const selectedTags = ref<string[]>([]);
    const tagSearchQuery = ref('');
    const tagMatchMode = ref<TagMatchMode>('AND');
    const isFiltering = ref(false);
    let filterTimeout: ReturnType<typeof setTimeout> | null = null;

    const filteredAvailableTags = computed<string[]>(() => {
        let unselectedTags = availableTags.value.filter((tag) => !selectedTags.value.includes(tag));

        const query = tagSearchQuery.value.trim().toLowerCase();
        if (query) {
            unselectedTags = unselectedTags.filter((tag) => tag.toLowerCase().includes(query));
        }

        const userTags = unselectedTags.filter((tag) => !isSystemTag(tag));
        const systemTags = unselectedTags.filter((tag) => isSystemTag(tag));

        return [...sortUserTagsAlphabetically(userTags), ...sortTagsByPriority(systemTags)];
    });

    const filteredAvailableTagsWithKeys = computed<TaggedListItem[]>(() =>
        filteredAvailableTags.value.map((tag, index) => ({ key: `tag-${index}-${tag}`, tag })),
    );

    async function filterByTags() {
        // Filtering itself is delegated to the parent (MapPage): it uses the bbox API to fetch
        // filtered features and updates the `features` prop; this just announces the request.
        emit('tag-filter-loading-change', true);
        emit('tag-filter-change', { tags: selectedTags.value, matchMode: tagMatchMode.value });
        emit('tag-filter-loading-change', false);
    }

    function debouncedFilterByTags() {
        if (filterTimeout) {
            clearTimeout(filterTimeout);
        }

        if (selectedTags.value.length === 0) {
            emit('tag-filter-change', { tags: [], matchMode: 'AND' });
            emit('tag-filter-loading-change', false);
            return;
        }

        // NOTE: intentionally never reset back to false, matching the original implementation.
        // In practice `isInitialLoad` (the other half of `showInitialTagsLoader`) is already
        // false by the time a real filter runs, so this doesn't surface visibly.
        isFiltering.value = true;
        emit('tag-filter-loading-change', true);
        filterTimeout = setTimeout(() => {
            void filterByTags();
        }, 300);
    }

    watch(selectedTags, debouncedFilterByTags, { deep: true });
    watch(tagMatchMode, debouncedFilterByTags);

    // When initialSelectedTags changes (e.g., route query changes), update local selection.
    watch(
        initialSelectedTags,
        (newTags) => {
            if (newTags.length > 0) {
                selectedTags.value = [...newTags];
                onActivate();
                // Explicitly trigger filtering immediately (don't wait for the debounce).
                void nextTick(() => filterByTags());
            }
        },
        { immediate: true },
    );

    function toggleTag(tag: string) {
        if (selectedTags.value.includes(tag)) {
            removeTag(tag);
        } else {
            selectedTags.value.push(tag);
        }
    }

    function removeTag(tag: string) {
        const index = selectedTags.value.indexOf(tag);
        if (index > -1) {
            selectedTags.value.splice(index, 1);
        }
    }

    function clearTagFilters() {
        selectedTags.value = [];
        emit('tag-filter-change', { tags: [], matchMode: 'AND' });
    }

    function clearTagSearch() {
        tagSearchQuery.value = '';
    }

    function handleTagSearchEnter() {
        // Allow adding tags by pressing Enter (including prefix tags ending in ':').
        const query = tagSearchQuery.value.trim();
        if (query && !selectedTags.value.includes(query)) {
            selectedTags.value.push(query);
            tagSearchQuery.value = '';
        }
    }

    return {
        selectedTags,
        tagSearchQuery,
        tagMatchMode,
        isFiltering,
        filteredAvailableTags,
        filteredAvailableTagsWithKeys,
        toggleTag,
        removeTag,
        clearTagFilters,
        clearTagSearch,
        handleTagSearchEnter,
    };
}
