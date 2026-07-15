import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, reactive, ref, watch, type ComponentPublicInstance } from 'vue';
import { useRouter } from 'vue-router';
import {
    applyBulkOperationsToTag as applyBulkOperationsToTagApi,
    bulkDeleteFeaturesByTag,
    bulkUpdateFeatureMetadata,
    getFeaturesByTag,
    updateFeatureMetadata,
    type FeatureMetadataUpdate,
} from '@/api/services/featuresApi';
import type { GeoJsonFeature } from '@/types/geospatial';
import { cloneBulkOperations, createEmptyBulkOperations, type BulkOperations, type RawBulkOperations } from '@/utils/bulkOperations';
import { buildRemoveTagMessage } from '@/utils/tags/tagMessages';
import { scrollToTag } from '@/utils/tags/tagDom';
import { getApiErrorMessage, toastApiError } from '@/utils/apiError';
import { toast } from '@/utils/toast.js';

const SEARCH_DEBOUNCE_MS = 400;
const TAG_NAME_MAX_LENGTH = 255;
// Control characters other than tab/newline/carriage return.
// eslint-disable-next-line no-control-regex -- deliberately rejecting raw control chars in tag names
const CONTROL_CHAR_PATTERN = /[\x00-\x08\x0B\x0C\x0E-\x1F]/;

type TagFeatureMap = Record<string, GeoJsonFeature[]>;

interface TagsPaginationInfo {
    total_tags: number;
    total_pages: number;
    has_next: boolean;
    has_previous: boolean;
}

interface FeaturesByTagResponse {
    user_tags?: TagFeatureMap;
    system_tags?: TagFeatureMap;
    pagination?: TagsPaginationInfo;
}

function featureTags(feature: GeoJsonFeature): string[] {
    return Array.isArray(feature.properties.tags) ? (feature.properties.tags as string[]) : [];
}

function featureId(feature: GeoJsonFeature): number | string {
    return feature.properties.database_id as number | string;
}

/**
 * Owns all data/CRUD/navigation state for the tags page: fetching + pagination,
 * inline rename, delete flows, bulk styling by tag, and share/map navigation.
 * Per-tag feature search/pagination lives in `useTagFeaturePagination` since it
 * has its own memoization concerns.
 */
export function useTagsData() {
    const router = useRouter();

    const rootEl = ref<HTMLElement | null>(null);
    const tagEditInputEl = ref<HTMLInputElement | null>(null);

    const tagsData = ref<TagFeatureMap>({});
    const userTagsData = ref<TagFeatureMap>({});
    const systemTagsData = ref<TagFeatureMap>({});
    const paginationInfo = ref<TagsPaginationInfo | null>(null);

    const loading = ref(true);
    const refreshing = ref(false);
    const error = ref<string | null>(null);
    const searchQuery = ref('');

    const editingTag = ref<string | null>(null);
    const editingTagValue = ref('');

    const shareDialogOpen = ref(false);
    const selectedTagForShare = ref('');

    const deleteModalOpen = ref(false);
    const selectedTagForDelete = ref('');

    const pageSize = 10;
    const currentPage = ref(1);
    const gotoPageInput = ref<number | null>(null);

    const bulkOperationsModalOpen = ref(false);
    const bulkOperationsSelectedTag = ref('');
    const bulkOperationsByTag = reactive<Record<string, BulkOperations>>({});
    const bulkOperationsSaving = ref(false);

    const totalTags = computed<number>(() => paginationInfo.value?.total_tags ?? Object.keys(tagsData.value).length);
    const totalPages = computed<number>(() => paginationInfo.value?.total_pages ?? Math.ceil(totalTags.value / pageSize));
    const hasNextPage = computed<boolean>(() => paginationInfo.value?.has_next ?? currentPage.value < totalPages.value);
    const hasPreviousPage = computed<boolean>(() => paginationInfo.value?.has_previous ?? currentPage.value > 1);
    const isValidPageNumber = computed<boolean>(() => {
        const value = gotoPageInput.value;
        return value !== null && value >= 1 && value <= totalPages.value && value !== currentPage.value;
    });

    const currentBulkOperationsForSelectedTag = computed<BulkOperations>(() => {
        if (!bulkOperationsSelectedTag.value) {
            return createEmptyBulkOperations();
        }
        return currentBulkOperationsForTag(bulkOperationsSelectedTag.value);
    });

    function isSystemTag(tag: string): boolean {
        return tag in systemTagsData.value;
    }

    async function fetchTagsData(showLoading = true, mergeMode = false): Promise<void> {
        if (showLoading) {
            loading.value = true;
        }
        error.value = null;

        try {
            const params: Record<string, string> = { page: String(currentPage.value) };
            const trimmedSearch = searchQuery.value.trim();
            if (trimmedSearch) {
                params.search = trimmedSearch;
            }

            const data = (await getFeaturesByTag(params)) as FeaturesByTagResponse;
            const newUserTags = data.user_tags ?? {};
            const newSystemTags = data.system_tags ?? {};

            if (mergeMode) {
                userTagsData.value = { ...userTagsData.value, ...newUserTags };
                systemTagsData.value = { ...systemTagsData.value, ...newSystemTags };
            } else {
                userTagsData.value = newUserTags;
                systemTagsData.value = newSystemTags;
            }

            tagsData.value = { ...userTagsData.value, ...systemTagsData.value };
            paginationInfo.value = data.pagination ?? null;
        } catch (err) {
            console.error('Error fetching tags data:', err);
            error.value = getApiErrorMessage(err, 'Failed to load tags. Please try again.');
        } finally {
            if (showLoading) {
                loading.value = false;
            }
        }
    }

    async function refreshTagsData(): Promise<void> {
        refreshing.value = true;
        try {
            await fetchTagsData(false);
        } catch (err) {
            console.error('Error refreshing tags data:', err);
        } finally {
            refreshing.value = false;
        }
    }

    function bindTagEditInput(el: Element | ComponentPublicInstance | null): void {
        tagEditInputEl.value = el as HTMLInputElement | null;
    }

    function startTagEdit(tag: string, event?: Event): void {
        event?.preventDefault();
        event?.stopPropagation();
        editingTag.value = tag;
        editingTagValue.value = tag;
        void nextTick(() => {
            tagEditInputEl.value?.focus();
            tagEditInputEl.value?.select();
        });
    }

    function cancelTagEdit(): void {
        editingTag.value = null;
        editingTagValue.value = '';
    }

    function renameTagInLocalState(oldTag: string, newTag: string): void {
        const updatedTags = { ...tagsData.value };
        updatedTags[newTag] = updatedTags[oldTag];
        delete updatedTags[oldTag];
        tagsData.value = updatedTags;

        if (oldTag in userTagsData.value) {
            const updatedUserTags = { ...userTagsData.value };
            updatedUserTags[newTag] = updatedUserTags[oldTag];
            delete updatedUserTags[oldTag];
            userTagsData.value = updatedUserTags;
        }
        if (oldTag in systemTagsData.value) {
            const updatedSystemTags = { ...systemTagsData.value };
            updatedSystemTags[newTag] = updatedSystemTags[oldTag];
            delete updatedSystemTags[oldTag];
            systemTagsData.value = updatedSystemTags;
        }
    }

    async function saveTagEdit(oldTag: string): Promise<void> {
        if (isSystemTag(oldTag)) {
            toast.error('System tags cannot be edited');
            cancelTagEdit();
            return;
        }

        const newTag = editingTagValue.value.trim();

        if (!newTag) {
            toast.error('Tag name cannot be empty');
            return;
        }
        if (newTag.length > TAG_NAME_MAX_LENGTH) {
            toast.error(`Tag name cannot exceed ${TAG_NAME_MAX_LENGTH} characters`);
            return;
        }
        if (CONTROL_CHAR_PATTERN.test(newTag)) {
            toast.error('Tag name cannot contain control characters');
            return;
        }
        if (newTag === oldTag) {
            cancelTagEdit();
            return;
        }
        if (newTag in tagsData.value) {
            toast.error(`Tag "${newTag}" already exists. Please choose a different name.`);
            return;
        }

        try {
            const features = tagsData.value[oldTag] ?? [];
            const updates: FeatureMetadataUpdate[] = features.map((feature) => {
                const currentTags = [...featureTags(feature)];
                const tagIndex = currentTags.indexOf(oldTag);
                if (tagIndex !== -1) {
                    currentTags[tagIndex] = newTag;
                } else {
                    currentTags.push(newTag);
                }
                return { feature_id: featureId(feature), tags: currentTags };
            });

            if (updates.length > 0) {
                await bulkUpdateFeatureMetadata(updates);
            }

            renameTagInLocalState(oldTag, newTag);
            cancelTagEdit();
            await fetchTagsData(true, true);

            void nextTick(() => {
                scrollToTag(rootEl.value, newTag);
            });
        } catch (err) {
            console.error('Error updating tag:', err);
            toastApiError(err, 'Failed to update tag');
        }
    }

    function getFeatureCountForTag(tag: string): number {
        return (tagsData.value[tag] ?? []).length;
    }

    function openDeleteModal(tag: string): void {
        selectedTagForDelete.value = tag;
        deleteModalOpen.value = true;
    }

    function closeDeleteModal(): void {
        deleteModalOpen.value = false;
        selectedTagForDelete.value = '';
    }

    function removeTagEverywhere(tag: string): void {
        const updatedTags = { ...tagsData.value };
        delete updatedTags[tag];
        tagsData.value = updatedTags;

        if (tag in userTagsData.value) {
            const updated = { ...userTagsData.value };
            delete updated[tag];
            userTagsData.value = updated;
        }
        if (tag in systemTagsData.value) {
            const updated = { ...systemTagsData.value };
            delete updated[tag];
            systemTagsData.value = updated;
        }
    }

    function removeUserTagFromLocalState(tag: string): void {
        const updatedTags = { ...tagsData.value };
        delete updatedTags[tag];
        tagsData.value = updatedTags;

        if (tag in userTagsData.value) {
            const updated = { ...userTagsData.value };
            delete updated[tag];
            userTagsData.value = updated;
        }
    }

    async function handleDeleteAllFeatures(tag: string): Promise<void> {
        try {
            const result = (await bulkDeleteFeaturesByTag(tag)) as { deleted_count?: number };
            console.log(`Deleted ${result.deleted_count ?? 0} features with tag "${tag}"`);

            removeTagEverywhere(tag);
            closeDeleteModal();
            await fetchTagsData(true, true);
        } catch (err) {
            console.error('Error deleting tag:', err);
            toastApiError(err, 'Failed to delete tag');
        }
    }

    async function handleRemoveTagOnly(tag: string): Promise<void> {
        try {
            const features = tagsData.value[tag] ?? [];
            const updates: FeatureMetadataUpdate[] = features.map((feature) => ({
                feature_id: featureId(feature),
                tags: featureTags(feature).filter((t) => t !== tag),
            }));

            if (updates.length > 0) {
                await bulkUpdateFeatureMetadata(updates);
            }

            removeUserTagFromLocalState(tag);
            closeDeleteModal();
            await fetchTagsData(true, true);
        } catch (err) {
            console.error('Error removing tag:', err);
            toastApiError(err, 'Failed to remove tag');
        }
    }

    async function removeTagFromFeature(tag: string, feature: GeoJsonFeature): Promise<void> {
        if (isSystemTag(tag)) {
            toast.error('System tags cannot be removed from features');
            return;
        }

        const featureName = String(feature.properties.name || 'Unnamed Feature');
        if (!confirm(buildRemoveTagMessage(tag, featureName))) {
            return;
        }

        try {
            const filteredTags = featureTags(feature).filter((t) => t !== tag);
            await updateFeatureMetadata(featureId(feature), { tags: filteredTags });

            const updatedTags = { ...tagsData.value };
            if (tag in updatedTags) {
                const remaining = updatedTags[tag].filter((f) => f.properties.database_id !== feature.properties.database_id);
                if (remaining.length === 0) {
                    delete updatedTags[tag];
                } else {
                    updatedTags[tag] = remaining;
                }
            }
            tagsData.value = updatedTags;
        } catch (err) {
            console.error('Error removing tag from feature:', err);
            toastApiError(err, 'Failed to remove tag from feature');
        }
    }

    function openBulkOperationsModal(tag: string): void {
        bulkOperationsSelectedTag.value = tag;
        bulkOperationsModalOpen.value = true;
    }

    function closeBulkOperationsModal(): void {
        bulkOperationsModalOpen.value = false;
    }

    function currentBulkOperationsForTag(tag: string): BulkOperations {
        return bulkOperationsByTag[tag] ?? createEmptyBulkOperations();
    }

    async function applyBulkOperationsToTag(tag: string, bulkData: RawBulkOperations): Promise<void> {
        try {
            await applyBulkOperationsToTagApi(tag, bulkData);
            await fetchTagsData(true, true);
        } catch (err) {
            console.error('Error applying bulk operations to tag:', err);
            toastApiError(err, 'Failed to apply bulk operations');
        }
    }

    async function handleApplyBulkOperations(bulkData: RawBulkOperations): Promise<void> {
        if (!bulkOperationsSelectedTag.value) {
            bulkOperationsModalOpen.value = false;
            return;
        }
        const tag = bulkOperationsSelectedTag.value;
        bulkOperationsByTag[tag] = cloneBulkOperations(bulkData);

        bulkOperationsSaving.value = true;
        try {
            await applyBulkOperationsToTag(tag, bulkData);
            bulkOperationsModalOpen.value = false;
        } finally {
            bulkOperationsSaving.value = false;
        }
    }

    function openShareDialog(tag: string): void {
        selectedTagForShare.value = tag;
        shareDialogOpen.value = true;
    }

    function downloadTagKmz(tag: string): void {
        window.open(`/api/export-kmz?tag=${encodeURIComponent(tag)}`, '_blank');
    }

    function viewTagOnMap(tag: string): void {
        void router.push({ path: '/map', query: { tag } });
    }

    function scrollTagsListToTop(): void {
        void nextTick(() => {
            const tagsList = rootEl.value?.querySelector('.space-y-4');
            tagsList?.scrollIntoView({ behavior: 'smooth', block: 'start' });
        });
    }

    function nextPage(): void {
        if (!hasNextPage.value) return;
        currentPage.value += 1;
        gotoPageInput.value = null;
        void fetchTagsData();
        scrollTagsListToTop();
    }

    function previousPage(): void {
        if (!hasPreviousPage.value) return;
        currentPage.value -= 1;
        gotoPageInput.value = null;
        void fetchTagsData();
        scrollTagsListToTop();
    }

    function jumpToPage(): void {
        if (!isValidPageNumber.value || gotoPageInput.value === null) return;
        currentPage.value = gotoPageInput.value;
        gotoPageInput.value = null;
        void fetchTagsData();
        scrollTagsListToTop();
    }

    let searchDebounceTimer: ReturnType<typeof setTimeout> | undefined;

    watch(searchQuery, () => {
        if (searchDebounceTimer !== undefined) {
            clearTimeout(searchDebounceTimer);
        }
        currentPage.value = 1;
        gotoPageInput.value = null;
        searchDebounceTimer = setTimeout(() => {
            void fetchTagsData();
        }, SEARCH_DEBOUNCE_MS);
    });

    onMounted(() => {
        void fetchTagsData();
    });

    // Kept alive by the app's top-level <keep-alive>, so returning to /tags
    // re-activates this instance instead of remounting it. Skipped on the very
    // first activation (which fires right after mount) since there's no data yet.
    onActivated(() => {
        if (Object.keys(tagsData.value).length > 0 && !refreshing.value) {
            void refreshTagsData();
        }
    });

    onBeforeUnmount(() => {
        if (searchDebounceTimer !== undefined) {
            clearTimeout(searchDebounceTimer);
        }
    });

    return {
        rootEl,
        tagsData,
        loading,
        refreshing,
        error,
        searchQuery,
        editingTag,
        editingTagValue,
        shareDialogOpen,
        selectedTagForShare,
        deleteModalOpen,
        selectedTagForDelete,
        pageSize,
        currentPage,
        gotoPageInput,
        bulkOperationsModalOpen,
        bulkOperationsSaving,
        totalTags,
        totalPages,
        hasNextPage,
        hasPreviousPage,
        isValidPageNumber,
        currentBulkOperationsForSelectedTag,
        isSystemTag,
        fetchTagsData,
        refreshTagsData,
        bindTagEditInput,
        startTagEdit,
        cancelTagEdit,
        saveTagEdit,
        getFeatureCountForTag,
        openDeleteModal,
        closeDeleteModal,
        handleDeleteAllFeatures,
        handleRemoveTagOnly,
        removeTagFromFeature,
        openBulkOperationsModal,
        closeBulkOperationsModal,
        handleApplyBulkOperations,
        openShareDialog,
        downloadTagKmz,
        viewTagOnMap,
        nextPage,
        previousPage,
        jumpToPage,
    };
}
