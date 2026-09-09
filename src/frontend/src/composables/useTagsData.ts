import { computed, nextTick, onActivated, onBeforeUnmount, onMounted, reactive, ref, watch, type ComponentPublicInstance } from 'vue';
import { useRouter } from 'vue-router';
import {
    applyBulkOperationsToTag as applyBulkOperationsToTagApi,
    deleteTag,
    getFeature,
    getTagCatalog,
    getTagFeatures,
    renameTag,
    replaceFeatureTags,
} from '@/api/services/featuresApi';
import type { TagCatalogEntry, TagFeatureRef, TagKind } from '@/contracts/tag';
import type { GeoJsonFeature } from '@/types/geospatial';
import { cloneBulkOperations, createEmptyBulkOperations, type BulkOperations, type RawBulkOperations } from '@/utils/bulkOperations';
import { buildRemoveTagMessage } from '@/utils/tags/tagMessages';
import { scrollToTag } from '@/utils/tags/tagDom';
import { getApiErrorMessage, toastApiError } from '@/utils/apiError';
import { toast } from '@/utils/toast.js';
import { downloadKmz } from '@/utils/sharing/downloadKmz';

const SEARCH_DEBOUNCE_MS = 400;
const TAG_NAME_MAX_LENGTH = 255;
// eslint-disable-next-line no-control-regex -- deliberately rejecting raw control chars in tag names
const CONTROL_CHAR_PATTERN = /[\x00-\x08\x0B\x0C\x0E-\x1F]/;

type TagFeatureMap = Record<string, GeoJsonFeature[]>;

function stubFromRef(ref: TagFeatureRef): GeoJsonFeature {
    return {
        type: 'Feature',
        properties: {
            database_id: ref.id,
            name: ref.name,
        },
        geometry: {
            type: (ref.geometry_type || 'Point') as GeoJsonFeature['geometry']['type'],
            coordinates: [],
        },
    };
}

function featureTagsFromGet(data: unknown): string[] {
    const record = data as { feature?: { geojson?: { properties?: { tags?: unknown } } } };
    const tags = record.feature?.geojson?.properties?.tags;
    return Array.isArray(tags) ? tags.filter((tag): tag is string => typeof tag === 'string') : [];
}

/**
 * Owns catalog/CRUD/navigation state for the tags page.
 * Mutations go through the tag kernel APIs only — never a slim metadata PUT.
 */
export function useTagsData() {
    const router = useRouter();

    const rootEl = ref<HTMLElement | null>(null);
    const tagEditInputEl = ref<HTMLInputElement | null>(null);

    const tagsData = ref<TagFeatureMap>({});
    const tagKinds = ref<Record<string, TagKind>>({});
    const tagCounts = ref<Record<string, number>>({});
    const totalTags = ref(0);
    const totalPages = ref(1);

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

    const hasNextPage = computed<boolean>(() => currentPage.value < totalPages.value);
    const hasPreviousPage = computed<boolean>(() => currentPage.value > 1);
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
        return tagKinds.value[tag] === 'system';
    }

    async function loadFeaturesForEntry(entry: TagCatalogEntry): Promise<GeoJsonFeature[]> {
        const stubs: GeoJsonFeature[] = [];
        let page = 1;
        let totalPagesForTag = 1;
        do {
            const featurePage = await getTagFeatures(entry.name, {
                page: String(page),
                page_size: '100',
            });
            stubs.push(...featurePage.items.map(stubFromRef));
            totalPagesForTag = featurePage.total_pages;
            page += 1;
        } while (page <= totalPagesForTag);
        return stubs;
    }

    async function fetchTagsData(showLoading = true): Promise<void> {
        if (showLoading) {
            loading.value = true;
        }
        error.value = null;

        try {
            const params: Record<string, string> = {
                page: String(currentPage.value),
                page_size: String(pageSize),
            };
            const trimmedSearch = searchQuery.value.trim();
            if (trimmedSearch) {
                params.search = trimmedSearch;
            }

            const catalog = await getTagCatalog(params);
            const nextData: TagFeatureMap = {};
            const nextKinds: Record<string, TagKind> = {};
            const nextCounts: Record<string, number> = {};

            await Promise.all(catalog.items.map(async (entry) => {
                nextKinds[entry.name] = entry.kind;
                nextCounts[entry.name] = entry.count;
                nextData[entry.name] = await loadFeaturesForEntry(entry);
            }));

            tagsData.value = nextData;
            tagKinds.value = nextKinds;
            tagCounts.value = nextCounts;
            totalTags.value = catalog.total_items;
            totalPages.value = Math.max(1, catalog.total_pages);
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
            await renameTag(oldTag, newTag);
            cancelTagEdit();
            await fetchTagsData(true);
            void nextTick(() => {
                scrollToTag(rootEl.value, newTag);
            });
        } catch (err) {
            console.error('Error updating tag:', err);
            toastApiError(err, 'Failed to update tag');
        }
    }

    function getFeatureCountForTag(tag: string): number {
        return tagCounts.value[tag] ?? (tagsData.value[tag] ?? []).length;
    }

    function openDeleteModal(tag: string): void {
        selectedTagForDelete.value = tag;
        deleteModalOpen.value = true;
    }

    function closeDeleteModal(): void {
        deleteModalOpen.value = false;
        selectedTagForDelete.value = '';
    }

    async function handleDeleteAllFeatures(tag: string): Promise<void> {
        try {
            await deleteTag(tag, true);
            closeDeleteModal();
            await fetchTagsData(true);
        } catch (err) {
            console.error('Error deleting tag:', err);
            toastApiError(err, 'Failed to delete tag');
        }
    }

    async function handleRemoveTagOnly(tag: string): Promise<void> {
        try {
            await deleteTag(tag, false);
            closeDeleteModal();
            await fetchTagsData(true);
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

        const featureId = feature.properties.database_id as number | string;
        try {
            const current = await getFeature(featureId);
            const nextTags = featureTagsFromGet(current).filter((item) => item !== tag);
            await replaceFeatureTags(featureId, nextTags);
            await fetchTagsData(false);
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
            await fetchTagsData(true);
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
        void downloadKmz({ tag }).catch((error) => {
            toastApiError(error, 'Failed to download KMZ.');
        });
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
