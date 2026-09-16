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

export interface PublicShareInfo {
    share_id: string;
    share_type: 'tag' | 'collection' | 'feature';
    tag: string | null;
    collection_name: string | null;
    collection_id: string | null;
    feature_name: string | null;
    include_tags: boolean;
    allow_downloads: boolean;
}

/** The tag/collection/feature context currently shown by the map, used by `MapControlsSidebar`'s header. */
export interface MapViewContext {
    type: 'tag' | 'collection' | 'feature';
    name: string;
    isPublicShare: boolean;
}

export type { UserLocation };
