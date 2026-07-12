/**
 * Shared types for the `MapPage.vue` composables (`useMapInitialization`, `useMapLayers`,
 * `useFeatureData`, `useFeatureSelection`, `useMapShare`, `useCollectionTagFilters`,
 * `useMapGeolocation`).
 */
import type { GeoJsonFeature, GeoJsonFeatureCollection } from '@/types/geospatial';
import type { UserLocation } from '@/api/services/locationApi';

/** A feature as returned by `convertMapLibreFeature()` - a `GeoJsonFeature` plus a top-level id used as a RecycleScroller key. */
export interface MapPageFeature extends GeoJsonFeature {
    database_id?: string | number;
}

export type MapPageFeatureCollection = GeoJsonFeatureCollection;

export type TrackingState = 'disabled' | 'tracking' | 'locked';

/** `data.map?.*` shape of `userSettings/userSettings`, as read by the map page. */
export interface MapUserSettings {
    enable_antialias?: boolean;
    replace_icons_low_zoom?: boolean;
    default_basemap?: string;
    enable_3d_terrain?: boolean;
    enable_hillshade?: boolean;
    [key: string]: unknown;
}

export interface UserSettings {
    map?: MapUserSettings;
    account?: Record<string, unknown>;
    [key: string]: unknown;
}

/** Describes what `loadDataForCurrentView` should load: the default viewport, a collection, or one of the public share modes. */
export type LoadContextType = 'default' | 'collection' | 'share_tag' | 'share_collection' | 'share_feature' | 'share_unknown';

export interface PublicShareInfo {
    share_id: string;
    share_type: 'tag' | 'collection' | 'feature';
    tag: string | null;
    collection_name: string | null;
    collection_id: string | null;
    feature_name: string | null;
    feature_id: string | null;
    include_tags: boolean;
    allow_downloads: boolean;
}

export interface LoadContext {
    type: LoadContextType;
    isPublicShare: boolean;
    shareId?: string | null;
    shareInfo?: PublicShareInfo | null;
    collectionId?: string | null;
    tags?: string[] | null;
    matchMode?: 'AND' | 'OR';
}

export type { UserLocation };
