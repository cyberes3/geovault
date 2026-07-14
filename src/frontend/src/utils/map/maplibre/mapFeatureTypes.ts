import type { GeoJsonFeature } from '@/types/geospatial';

/**
 * Known properties this module reads/writes on a feature, layered over the base
 * `Record<string, any>` properties bag (`GeoJsonFeature['properties']`) so these specific
 * accesses are properly typed instead of resolving to `any`.
 */
export interface MapFeatureProperties {
    database_id?: string | number;
    name?: string | null;
    stroke?: string;
    'stroke-width'?: number;
    'marker-color'?: string;
    _isLabelPoint?: boolean;
    _isSmallFeatureReplacement?: boolean;
    _placeLabelBelow?: boolean;
    _originalFeatureId?: string | number;
    _originalGeometryType?: string;
    _isTooSmall?: boolean;
    _elevation?: unknown;
    _elevations?: unknown[];
    coordinateProperties?: { times?: unknown };
    _coordinateProperties?: { times?: unknown };
    '_icon-id'?: string;
    _detectedIconColor?: string;
    _colorDetectionInProgress?: boolean;
    [key: string]: unknown;
}

/** A rendered map feature, which (unlike the base `GeoJsonFeature` type) may carry a GeoJSON `id`. */
export type MapFeature = Omit<GeoJsonFeature, 'properties'> & { id?: string | number; properties: MapFeatureProperties };
