/**
 * Shared domain types for live-track trackers and groups, as kept in memory by `LiveTrackView`/
 * `WorldShareView` (normalized via `trackNormalization.ts`) and as returned by the tracker/group
 * API endpoints. Fields are mostly optional since call sites narrow/default defensively
 * throughout this extension (raw API payloads, partially-normalized in-memory copies, and
 * world-share payloads all flow through these same shapes).
 */
export interface PointParams {
    alt?: number;
    acc?: number;
    bearing?: number;
    prov?: string;
    spd_kph?: number;
    starttimestamp?: number | string;
    batt?: number;
    ischarging?: boolean | string;
    dist?: number;
    [key: string]: unknown;
}

export interface TrackPosition {
    lon: number;
    lat: number;
}

export interface TrackSettings {
    recent_data_window?: string | null;
    hidden?: boolean;
    allow_group_reshare?: boolean;
    [key: string]: unknown;
}

export type TrackVisibility = 'private' | 'shared' | 'public';

export type TrackCoordinate = [number, number, number?];

export interface TrackGeometry {
    type: string;
    coordinates: TrackCoordinate[];
}

export interface LiveTrack {
    id: string | number;
    name?: string;
    color?: string;
    tracker_secret?: string;
    hauk_password?: string;
    geometry?: TrackGeometry;
    last_point?: TrackCoordinate;
    last_position?: TrackPosition | null;
    last_timestamp_ms?: number | null;
    created_at?: string | number;
    updated_at?: string | number;
    updated_at_ms?: number | null;
    point_params?: PointParams[];
    latestPointParams?: PointParams;
    is_owner?: boolean;
    owner_email?: string;
    visibility?: TrackVisibility;
    settings?: TrackSettings;
    share_params_with_recipients?: boolean;
    share_params_with_world?: boolean;
    world_share_id?: string | null;
    world_share_url?: string | null;
    world_share_enabled?: boolean;
    internal_share_id?: string | null;
    internal_share_url?: string | null;
    shared_with_emails?: string[];
    subscriber_count?: number;
    [key: string]: unknown;
}

export interface LiveTrackGroup {
    id: string | number;
    name?: string;
    hidden?: boolean;
    visibility?: TrackVisibility;
    is_owner?: boolean;
    is_accepted?: boolean;
    owner_email?: string;
    track_ids?: Array<string | number>;
    shared_with_emails?: string[];
    world_share_id?: string | null;
    world_share_url?: string | null;
    world_share_enabled?: boolean;
    internal_share_url?: string | null;
    [key: string]: unknown;
}
