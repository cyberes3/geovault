import { ref } from 'vue';
import {
    createShare as createShareApi,
    deleteShare as deleteShareApi,
    listShares,
    updateShare as updateShareApi,
    type ShareListFilters,
} from '@/api/services/sharingApi';
import type { CreateSharePayload, ShareListItem } from '@/contracts/share';
import { getApiErrorMessage } from '@/utils/apiError';

export function shareDisplayName(share: ShareListItem): string {
    if (share.share_type === 'tag') {
        return share.tag;
    }
    if (share.share_type === 'collection') {
        return share.collection_name;
    }
    if (share.share_type === 'feature') {
        return share.feature_name;
    }
    if (share.share_type === 'live_track') {
        return share.track_name;
    }
    return share.group_name;
}

export function absoluteShareUrl(path: string | null | undefined): string {
    return `${window.location.origin}${path ?? ''}`;
}

export function useShareManagement() {
    const shares = ref<ShareListItem[]>([]);
    const loading = ref(false);
    const error = ref<string | null>(null);
    const copiedShareId = ref<string | null>(null);
    const deletingShareId = ref<string | null>(null);
    const updatingShareId = ref<string | null>(null);

    async function load(filters: ShareListFilters = {}): Promise<ShareListItem[]> {
        loading.value = true;
        error.value = null;
        try {
            shares.value = await listShares(filters);
            return shares.value;
        } catch (err) {
            error.value = getApiErrorMessage(err, 'Failed to load shares. Please try again.');
            throw err;
        } finally {
            loading.value = false;
        }
    }

    async function create(payload: CreateSharePayload): Promise<ShareListItem> {
        error.value = null;
        const created = await createShareApi(payload);
        shares.value = [created, ...shares.value.filter((share) => share.share_id !== created.share_id)];
        return created;
    }

    async function update(
        shareId: string,
        fields: Partial<Pick<ShareListItem, 'allow_downloads' | 'include_tags'>>,
    ): Promise<ShareListItem> {
        updatingShareId.value = shareId;
        error.value = null;
        try {
            const updated = await updateShareApi(shareId, fields);
            shares.value = shares.value.map((share) => (share.share_id === shareId ? updated : share));
            return updated;
        } finally {
            updatingShareId.value = null;
        }
    }

    async function remove(shareId: string): Promise<void> {
        deletingShareId.value = shareId;
        error.value = null;
        try {
            await deleteShareApi(shareId);
            shares.value = shares.value.filter((share) => share.share_id !== shareId);
        } finally {
            deletingShareId.value = null;
        }
    }

    async function copy(path: string, shareId: string): Promise<void> {
        await navigator.clipboard.writeText(absoluteShareUrl(path));
        copiedShareId.value = shareId;
        window.setTimeout(() => {
            if (copiedShareId.value === shareId) {
                copiedShareId.value = null;
            }
        }, 2000);
    }

    return {
        shares,
        loading,
        error,
        copiedShareId,
        deletingShareId,
        updatingShareId,
        load,
        create,
        update,
        remove,
        copy,
    };
}
