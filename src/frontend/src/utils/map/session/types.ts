import type { MatchMode } from './ViewportKey';
import type { PublicShareInfo } from '@/composables/mapPageTypes';

export type MapSessionMode = 'main' | 'tag' | 'collection' | 'publicShare' | 'featureFocus';

export type ShareLoadKind = 'tag' | 'collection' | 'feature';

export const SHARE_TYPE_TO_KIND: Record<ShareLoadKind, ShareLoadKind> = {
    tag: 'tag',
    collection: 'collection',
    feature: 'feature',
};

export type LoadContext =
    | {
        kind: 'main';
        tags: string[];
        matchMode: MatchMode;
        spatial: 'bbox';
        replaceSource: boolean;
        isPublicShare: false;
    }
    | {
        kind: 'tag';
        tags: string[];
        matchMode: MatchMode;
        spatial: 'bbox';
        replaceSource: true;
        isPublicShare: false;
    }
    | {
        kind: 'collection';
        collectionId: string;
        tags: string[];
        matchMode: MatchMode;
        spatial: 'bbox';
        replaceSource: true;
        isPublicShare: false;
    }
    | {
        kind: 'share';
        shareId: string;
        shareType: ShareLoadKind;
        spatial: 'bbox' | 'global';
        replaceSource: true;
        isPublicShare: true;
        shareInfo: PublicShareInfo;
    }
    | {
        kind: 'featureFocus';
        featureId: string;
        spatial: 'global';
        replaceSource: false;
        isPublicShare: false;
    };

export type RouteQueryValue = string | string[] | null | undefined;

export interface MapRouteLocation {
    path: string;
    query: Record<string, RouteQueryValue>;
}

export type InteractionMode = 'idle' | 'selected' | 'disambiguating' | 'editing' | 'elevationProfile';
