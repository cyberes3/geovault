export type ShareType = 'tag' | 'collection' | 'feature' | 'live_track' | 'live_track_group';
export type ShareAudience = 'world' | 'authenticated';

interface ShareCommon {
    share_id: string;
    url: string;
    created_at: string;
    access_count: number;
    include_tags: boolean;
    allow_downloads: boolean;
}

export interface TagShareListItem extends ShareCommon {
    share_type: 'tag';
    tag: string;
    audience: 'world';
    domain: 'map';
}

export interface CollectionShareListItem extends ShareCommon {
    share_type: 'collection';
    collection_id: string;
    collection_name: string;
    audience: 'world';
    domain: 'map';
}

export interface FeatureShareListItem extends ShareCommon {
    share_type: 'feature';
    feature_id: number;
    feature_name: string;
    audience: 'world';
    domain: 'map';
}

export interface LiveTrackShareListItem extends ShareCommon {
    share_type: 'live_track';
    track_id: string;
    track_name: string;
    audience: ShareAudience;
    domain: 'live_track';
}

export interface LiveTrackGroupShareListItem extends ShareCommon {
    share_type: 'live_track_group';
    group_id: string;
    group_name: string;
    audience: ShareAudience;
    domain: 'live_track';
}

export type ShareListItem =
    | TagShareListItem
    | CollectionShareListItem
    | FeatureShareListItem
    | LiveTrackShareListItem
    | LiveTrackGroupShareListItem;

export type CreateSharePayload =
    | { share_type: 'tag'; tag: string; include_tags: boolean; allow_downloads: boolean }
    | { share_type: 'collection'; collection_id: string; include_tags: boolean; allow_downloads: boolean }
    | { share_type: 'feature'; feature_id: string | number; include_tags: boolean; allow_downloads: boolean }
    | { share_type: 'live_track'; track_id: string; audience: ShareAudience; include_tags?: boolean; allow_downloads?: boolean }
    | { share_type: 'live_track_group'; group_id: string; audience: ShareAudience; include_tags?: boolean; allow_downloads?: boolean };

export interface PublicTagShare {
    share_type: 'tag';
    tag: string;
    created_at: string;
    include_tags: boolean;
    allow_downloads: boolean;
}

export interface PublicCollectionShare {
    share_type: 'collection';
    collection_name: string;
    collection_id: string;
    created_at: string;
    include_tags: boolean;
    allow_downloads: boolean;
}

export interface PublicFeatureShare {
    share_type: 'feature';
    feature_name: string;
    created_at: string;
    include_tags: boolean;
    allow_downloads: boolean;
}

export interface PublicLiveTrackShare {
    share_type: 'live_track';
    share_access: 'world' | 'internal';
    track_id: string;
    track_name: string;
    created_at: string;
}

export interface PublicLiveTrackGroupShare {
    share_type: 'live_track_group';
    share_access: 'world' | 'internal';
    group_id: string;
    group_name: string;
    created_at: string;
}

export type PublicShare =
    | PublicTagShare
    | PublicCollectionShare
    | PublicFeatureShare
    | PublicLiveTrackShare
    | PublicLiveTrackGroupShare;
