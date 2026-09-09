import { getPublicShareInfo } from '@/api/services/sharingApi';
import type { PublicShare } from '@/contracts/share';
import type { PublicShareInfo } from '@/composables/mapPageTypes';
import { ApiError, isAbortError } from '@/utils/apiError';

export type PublicShareSessionStatus = 'idle' | 'loading' | 'ready' | 'invalid';

export class PublicShareSession {
    status: PublicShareSessionStatus = 'idle';
    info: PublicShare | null = null;
    error: string | null = null;
    shareId: string | null = null;
    private inflight: Promise<boolean> | null = null;

    get allowDownloads(): boolean {
        if (!this.info || this.info.share_type === 'live_track' || this.info.share_type === 'live_track_group') {
            return false;
        }
        return this.info.allow_downloads;
    }

    get includeTags(): boolean {
        if (!this.info || this.info.share_type === 'live_track' || this.info.share_type === 'live_track_group') {
            return false;
        }
        return this.info.include_tags;
    }

    resetForShareIdChange(shareId: string | null = null): void {
        this.status = 'idle';
        this.info = null;
        this.error = null;
        this.shareId = shareId;
        this.inflight = null;
    }

    async ensureInfo(signal?: AbortSignal): Promise<boolean> {
        if (!this.shareId) {
            this.status = 'invalid';
            this.error = 'Invalid share link';
            return false;
        }
        if (this.status === 'ready' && this.info) {
            return true;
        }
        if (this.inflight) {
            return this.inflight;
        }
        this.status = 'loading';
        this.inflight = this.load(signal);
        try {
            return await this.inflight;
        } finally {
            this.inflight = null;
        }
    }

    toMapShareInfo(): PublicShareInfo | null {
        if (!this.shareId || !this.info) {
            return null;
        }
        if (this.info.share_type === 'live_track' || this.info.share_type === 'live_track_group') {
            return null;
        }
        return {
            share_id: this.shareId,
            share_type: this.info.share_type,
            tag: this.info.share_type === 'tag' ? this.info.tag : null,
            collection_name: this.info.share_type === 'collection' ? this.info.collection_name : null,
            collection_id: this.info.share_type === 'collection' ? this.info.collection_id : null,
            feature_name: this.info.share_type === 'feature' ? this.info.feature_name : null,
            include_tags: this.info.include_tags,
            allow_downloads: this.info.allow_downloads,
        };
    }

    private async load(signal?: AbortSignal): Promise<boolean> {
        try {
            this.info = await getPublicShareInfo(this.shareId as string, signal);
            this.status = 'ready';
            this.error = null;
            return true;
        } catch (error) {
            if (isAbortError(error)) {
                return false;
            }
            this.info = null;
            this.status = 'invalid';
            const status = ApiError.from(error).status;
            this.error = [401, 403, 404].includes(status) ? 'Invalid share link' : 'Failed to load share information';
            return false;
        }
    }
}
