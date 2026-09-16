import type { StyleSpecification } from 'maplibre-gl';
import type { VaultFeature } from '@/contracts/feature';
import type { UserLocation } from '@/api/services/locationApi';

export type CameraSnapshot = {
    center: [number, number];
    zoom: number;
    pitch: number;
    bearing: number;
};

export type MapStyleInput = string | StyleSpecification;

export type LocationTrackingMode = 'off' | 'show-only' | 'follow';

export type IngestContext = {
    zoom: number;
    replaceIconsLowZoom: boolean;
    showLabels: boolean;
    viewSize: { width: number; height: number };
};

export type FeatureRuntimeOverlay = {
    iconId?: string;
    tooSmall?: boolean;
    elevation?: unknown;
    elevations?: unknown[];
    coordinateTimes?: unknown;
    detectedIconColor?: string;
};

export type RenderFeature = VaultFeature & {
    id?: string | number;
    properties: VaultFeature['properties'] & Record<string, unknown>;
};

export type { UserLocation };
