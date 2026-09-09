import type { LoadContext, MapRouteLocation, MapSessionMode, ShareLoadKind } from './types';
import type { MatchMode } from './ViewportKey';
import type { PublicShareInfo } from '@/composables/mapPageTypes';

function asStringList(value: string | string[] | null | undefined): string[] {
    if (value == null) return [];
    return (Array.isArray(value) ? value : [value]).map((item) => String(item)).filter(Boolean);
}

function asSingle(value: string | string[] | null | undefined): string | null {
    if (value == null) return null;
    const first = Array.isArray(value) ? value[0] : value;
    return first ? String(first) : null;
}

export class MapFilterState {
    collectionId: string | null = null;
    tags: string[] = [];
    matchMode: MatchMode = 'AND';
    focusFeatureId: string | null = null;
    mode: MapSessionMode = 'main';

    applyRoute(location: MapRouteLocation): void {
        const collectionId = asSingle(location.query.collection);
        const tags = asStringList(location.query.tag);
        const matchMode = asSingle(location.query.match_mode);
        const featureId = asSingle(location.query.featureId);
        this.collectionId = collectionId;
        this.tags = tags;
        this.matchMode = matchMode === 'OR' ? 'OR' : 'AND';
        this.focusFeatureId = featureId;

        if (location.path === '/mapshare') {
            this.mode = 'publicShare';
            return;
        }
        if (collectionId) {
            this.mode = 'collection';
            return;
        }
        if (tags.length > 0) {
            this.mode = 'tag';
            return;
        }
        if (featureId) {
            this.mode = 'featureFocus';
            return;
        }
        this.mode = 'main';
    }

    setSidebarTags(tags: string[], matchMode: MatchMode = 'AND'): void {
        this.tags = tags;
        this.matchMode = matchMode;
        if (this.mode === 'main' && tags.length > 0) {
            this.mode = 'tag';
        }
        if (this.mode === 'tag' && tags.length === 0) {
            this.mode = this.collectionId ? 'collection' : (this.focusFeatureId ? 'featureFocus' : 'main');
        }
    }

    getLoadContext(share: PublicShareInfo | null): LoadContext {
        if (this.mode === 'publicShare') {
            if (!share?.share_id || !share.share_type) {
                throw new Error('Public share context requires loaded share info');
            }
            const shareType = share.share_type as ShareLoadKind;
            return {
                kind: 'share',
                shareId: share.share_id,
                shareType,
                spatial: shareType === 'feature' ? 'global' : 'bbox',
                replaceSource: true,
                isPublicShare: true,
                shareInfo: share,
            };
        }
        if (this.mode === 'collection' && this.collectionId) {
            return {
                kind: 'collection',
                collectionId: this.collectionId,
                tags: this.tags,
                matchMode: this.matchMode,
                spatial: 'bbox',
                replaceSource: true,
                isPublicShare: false,
            };
        }
        if (this.mode === 'tag' && this.tags.length > 0) {
            return {
                kind: 'tag',
                tags: this.tags,
                matchMode: this.matchMode,
                spatial: 'bbox',
                replaceSource: true,
                isPublicShare: false,
            };
        }
        if (this.mode === 'featureFocus' && this.focusFeatureId) {
            return {
                kind: 'featureFocus',
                featureId: this.focusFeatureId,
                spatial: 'global',
                replaceSource: false,
                isPublicShare: false,
            };
        }
        return {
            kind: 'main',
            tags: this.tags,
            matchMode: this.matchMode,
            spatial: 'bbox',
            replaceSource: false,
            isPublicShare: false,
        };
    }

    get isMainMap(): boolean {
        return this.mode === 'main' || this.mode === 'featureFocus';
    }

    get isUrlDrivenCamera(): boolean {
        return this.mode === 'tag' || this.mode === 'collection' || this.mode === 'featureFocus' || this.mode === 'publicShare';
    }
}
