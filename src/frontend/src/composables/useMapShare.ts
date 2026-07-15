/**
 * Public "mapshare" mode: read-only map views reached via `/mapshare?id=...`, scoped to a
 * single tag, collection, or feature. Owns the share metadata fetch, share-mode error state,
 * and the feature share dialog used by the "share" button on a selected feature.
 */
import { computed, ref, type Ref } from 'vue';
import { useRoute } from 'vue-router';
import { getPublicShareInfo } from '@/api/services/sharingApi';
import { isAbortError } from '@/utils/apiError';
import type { PublicShareInfo, MapPageFeature } from './mapPageTypes';

export interface UseMapShareDeps {
    getSelectedFeature: () => MapPageFeature | null;
}

export function useMapShare(deps: UseMapShareDeps) {
    const route = useRoute();

    const isMapshareRoute = computed(() => route.path === '/mapshare');
    const isPublicShareMode = computed(() => isMapshareRoute.value && !!route.query.id);
    const shareId = computed<string | null>(() => (route.query.id as string | undefined) ?? null);

    const publicShareError: Ref<string | null> = ref(null);
    const publicShareInfo: Ref<PublicShareInfo | null> = ref(null);
    const publicShareTag: Ref<string | null> = ref(null);
    const publicShareCollectionName: Ref<string | null> = ref(null);
    /** Share id for which we already ran a tight fit to loaded geometries after first bbox response (mapshare tag/collection). */
    const publicShareRefinedFitShareId: Ref<string | null> = ref(null);

    const showFeatureShareDialog = ref(false);
    const featureToShare: Ref<MapPageFeature | null> = ref(null);

    const publicShareAllowedOptions = computed(() => {
        if (isPublicShareMode.value) {
            return { mapLayer: true, featureStats: false, userLocation: false };
        }
        return { mapLayer: true, featureStats: true, userLocation: true };
    });

    function handlePublicShareError(errorMessage: string | null): void {
        publicShareError.value = errorMessage || 'Invalid share link';
    }

    /**
     * Ensures public share info is loaded and cached. Called before building bbox URLs for
     * public shares. Does not manage `isDataLoading` - the caller handles that.
     */
    async function ensurePublicShareInfo(signal?: AbortSignal): Promise<boolean> {
        if (!isPublicShareMode.value || !shareId.value) {
            return false;
        }

        if (publicShareInfo.value?.share_id === shareId.value) {
            return true;
        }

        try {
            const infoData = await getPublicShareInfo(shareId.value, signal);

            publicShareInfo.value = {
                share_id: shareId.value,
                share_type: infoData.share_type,
                tag: infoData.tag ?? null,
                collection_name: infoData.collection_name ?? null,
                collection_id: infoData.collection_id ?? null,
                feature_name: infoData.feature_name ?? null,
                feature_id: infoData.feature_id ?? null,
                include_tags: infoData.include_tags ?? false,
                allow_downloads: infoData.allow_downloads ?? false,
            };

            if (infoData.share_type === 'tag') {
                publicShareTag.value = infoData.tag ?? null;
                publicShareCollectionName.value = null;
            } else if (infoData.share_type === 'collection') {
                publicShareCollectionName.value = infoData.collection_name ?? null;
                publicShareTag.value = null;
            } else {
                publicShareTag.value = null;
                publicShareCollectionName.value = null;
            }

            return true;
        } catch (error) {
            if (isAbortError(error)) {
                return false;
            }
            console.error('Error fetching share info:', error);
            handlePublicShareError('Failed to load share information');
            return false;
        }
    }

    function handleShareFeature(): void {
        const feature = deps.getSelectedFeature();
        if (!feature) return;
        featureToShare.value = feature;
        showFeatureShareDialog.value = true;
    }

    function handleCloseFeatureShareDialog(): void {
        showFeatureShareDialog.value = false;
        featureToShare.value = null;
    }

    return {
        isMapshareRoute,
        isPublicShareMode,
        shareId,
        publicShareError,
        publicShareInfo,
        publicShareTag,
        publicShareCollectionName,
        publicShareRefinedFitShareId,
        publicShareAllowedOptions,
        showFeatureShareDialog,
        featureToShare,
        handlePublicShareError,
        ensurePublicShareInfo,
        handleShareFeature,
        handleCloseFeatureShareDialog,
    };
}
