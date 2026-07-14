export interface PlaceProperties {
    database_id: number;
    name?: string;
    description?: string | null;
    address?: string | null;
    created_at?: string;
}

export interface PlaceGeometry {
    type: 'Point';
    coordinates: number[];
}

export interface PlaceFeature {
    type: 'Feature';
    geometry: PlaceGeometry;
    properties: PlaceProperties;
}

export interface PlaceFeatureCollection {
    type: 'FeatureCollection';
    features: PlaceFeature[];
}

export interface PlacePayloadOverrides {
    name?: string;
    description?: string | null;
    address?: string | null;
}

export interface PlacePayload {
    type: 'Feature';
    geometry: PlaceGeometry;
    properties: {
        name: string;
        description: string | null;
        address?: string;
    };
}

export interface PlaceMapFeatureProperties {
    database_id?: number;
    is_highlighted: 0 | 1;
    [key: string]: unknown;
}

export interface PlaceMapFeature {
    type: 'Feature';
    geometry: { type: 'Point'; coordinates: [number, number] };
    properties: PlaceMapFeatureProperties;
}
