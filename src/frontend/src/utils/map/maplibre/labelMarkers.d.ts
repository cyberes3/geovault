import type { Map as MapLibreMap } from 'maplibre-gl';
import type { GeoJsonFeature } from '@/types/geospatial';

export function getResolutionFromZoom(zoom: number): number;

export function checkLabelBorderIntersection(
    geometry: GeoJsonFeature['geometry'],
    labelPosition: number[] | null,
    text: string | null | undefined,
    resolution: number,
    strokeWidth?: number,
): boolean;

/** Manages label marker DOM elements for map features, with debounced/immediate update modes. */
export class LabelMarkerManager {
    constructor(map: MapLibreMap);
    setVisibility(show: boolean): void;
    updateMarkers(features: GeoJsonFeature[], immediate?: boolean): void;
    clearAllMarkers(): void;
    removeMarker(featureId: string | number): void;
    clear(): void;
}
