export interface FeatureProperties {
    name?: string;
    description?: string;
    icon?: string;
    tags?: string[];
    system_tags?: string[];
    'marker-color'?: string;
    stroke?: string;
    fill?: string;
    'fill-opacity'?: number;
    'stroke-width'?: number;
    created?: string;
    time?: string;
    database_id?: string | number;
    feature_ref?: string | number;
    geojson_hash?: string;
    coordinateProperties?: Record<string, unknown>;
}

export interface VaultFeatureGeometry {
    type: string;
    coordinates?: unknown;
    geometries?: VaultFeatureGeometry[];
}

export interface VaultFeature {
    type: 'Feature';
    geometry: VaultFeatureGeometry;
    properties: FeatureProperties;
    geojson_hash?: string;
}

export interface FeatureListProjection {
    id: number;
    name: string;
    geometry_type: string;
}
