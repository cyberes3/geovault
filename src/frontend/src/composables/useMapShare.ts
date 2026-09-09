import { computed, ref, watch, type Ref } from 'vue';
import { useRoute } from 'vue-router';
import { PublicShareSession } from '@/utils/sharing/publicShareSession';
import type { MapPageFeature, PublicShareInfo } from './mapPageTypes';

export interface UseMapShareDeps {
    getSelectedFeature: () => MapPageFeature | null;
}

export function useMapShare(deps: UseMapShareDeps) {
    const route = useRoute();
    const session = new PublicShareSession();

    const isMapshareRoute = computed(() => route.path === '/mapshare');
    const isPublicShareMode = computed(() => isMapshareRoute.value && !!route.query.id);
    const shareId = computed<string | null>(() => (route.query.id as string | undefined) ?? null);

    const publicShareError: Ref<string | null> = ref(null);
    const publicShareInfo: Ref<PublicShareInfo | null> = ref(null);
    const publicShareTag: Ref<string | null> = ref(null);
    const publicShareCollectionName: Ref<string | null> = ref(null);
    const publicShareRefinedFitShareId: Ref<string | null> = ref(null);

    const showFeatureShareDialog = ref(false);
    const featureToShare: Ref<MapPageFeature | null> = ref(null);

    const publicShareAllowedOptions = computed(() => {
        if (isPublicShareMode.value) {
            return { mapLayer: true, featureStats: false, userLocation: false };
        }
        return { mapLayer: true, featureStats: true, userLocation: true };
    });

    function syncFromSession(): void {
        publicShareError.value = session.error;
        publicShareInfo.value = session.toMapShareInfo();
        const info = publicShareInfo.value;
        publicShareTag.value = info?.share_type === 'tag' ? info.tag : null;
        publicShareCollectionName.value = info?.share_type === 'collection' ? info.collection_name : null;
    }

    function handlePublicShareError(errorMessage: string | null): void {
        session.status = 'invalid';
        session.error = errorMessage || 'Invalid share link';
        session.info = null;
        syncFromSession();
    }

    async function ensurePublicShareInfo(signal?: AbortSignal): Promise<boolean> {
        if (!isPublicShareMode.value || !shareId.value) {
            return false;
        }
        if (session.shareId !== shareId.value) {
            session.resetForShareIdChange(shareId.value);
            publicShareRefinedFitShareId.value = null;
        }
        const ok = await session.ensureInfo(signal);
        syncFromSession();
        return ok;
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

    function resetForRoute(): void {
        session.resetForShareIdChange(shareId.value);
        publicShareRefinedFitShareId.value = null;
        syncFromSession();
    }

    watch(shareId, (next, previous) => {
        if (next === previous) {
            return;
        }
        resetForRoute();
    });

    return {
        session,
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
        resetForRoute,
    };
}
