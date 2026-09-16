import type { GeoJsonFeature } from '@/types/geospatial';
import type { MapPageFeature } from '@/composables/mapPageTypes';

export interface ConvertibleMapLibreFeature {
    geometry?: unknown;
    properties?: Record<string, unknown> | null;
}

const DECODE_FIELDS = {
    tags: [],
    system_tags: [],
    _elevations: null,
    _coordinateProperties: null,
    coordinateProperties: null,
} as const;

function decodeProperty(value: unknown, fallback: unknown): unknown {
    if (typeof value === 'string') {
        try {
            return JSON.parse(value);
        } catch {
            return fallback;
        }
    }
    if (value !== null && typeof value === 'object') {
        return value;
    }
    return value ?? fallback;
}

/** Convert a MapLibre feature to GeoJSON. Nested props may arrive as JSON strings. */
export function convertMapLibreFeature(mlFeature: ConvertibleMapLibreFeature): MapPageFeature {
    const geometry = (mlFeature.geometry ?? {}) as GeoJsonFeature['geometry'];
    const properties = { ...(mlFeature.properties ?? {}) };

    for (const [key, fallback] of Object.entries(DECODE_FIELDS)) {
        if (key in properties || typeof properties[key] === 'string') {
            properties[key] = decodeProperty(properties[key], fallback);
        }
    }

    return {
        type: 'Feature',
        database_id: properties.database_id as string | number | undefined,
        properties,
        geometry,
    };
}
