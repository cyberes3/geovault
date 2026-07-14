/**
 * Convert MapLibre features to GeoJSON format for Vue components
 * Returns pure GeoJSON features with no OpenLayers compatibility layer
 */

import type { GeoJsonFeature } from '@/types/geospatial'
import type { MapPageFeature } from '@/composables/mapPageTypes'

/** Loosest common shape of the various feature-like objects passed in here (raw MapLibre query results, already-converted features, etc). */
export interface ConvertibleMapLibreFeature {
    geometry?: unknown
    properties?: Record<string, unknown> | null
}

/** Convert a MapLibre feature to GeoJSON format. */
export function convertMapLibreFeature(mlFeature: ConvertibleMapLibreFeature): MapPageFeature {
    const geometry = (mlFeature.geometry ?? {}) as GeoJsonFeature['geometry']

    // MapLibre serializes array properties to JSON strings when storing features
    // We need to parse them back to arrays for tags and system_tags
    const properties = mlFeature.properties ?? {}
    const normalizedProperties = {...properties}

    // Parse tags if it's a string
    if (typeof normalizedProperties.tags === 'string') {
        try {
            normalizedProperties.tags = JSON.parse(normalizedProperties.tags)
        } catch {
            console.warn('Failed to parse tags as JSON:', normalizedProperties.tags)
            normalizedProperties.tags = []
        }
    }

    // Parse system_tags if it's a string
    if (typeof normalizedProperties.system_tags === 'string') {
        try {
            normalizedProperties.system_tags = JSON.parse(normalizedProperties.system_tags)
        } catch {
            console.warn('Failed to parse system_tags as JSON:', normalizedProperties.system_tags)
            normalizedProperties.system_tags = []
        }
    }

    // Parse _elevations if it's a string (for LineString/MultiLineString elevation data)
    if (typeof normalizedProperties._elevations === 'string') {
        try {
            normalizedProperties._elevations = JSON.parse(normalizedProperties._elevations)
        } catch {
            console.warn('Failed to parse _elevations as JSON:', normalizedProperties._elevations)
            normalizedProperties._elevations = null
        }
    }

    // Parse _coordinateProperties if it's a string (for timestamp data)
    if (typeof normalizedProperties._coordinateProperties === 'string') {
        try {
            normalizedProperties._coordinateProperties = JSON.parse(normalizedProperties._coordinateProperties)
        } catch {
            console.warn('Failed to parse _coordinateProperties as JSON:', normalizedProperties._coordinateProperties)
            normalizedProperties._coordinateProperties = null
        }
    }

    // Parse coordinateProperties if it's a string (for timestamp data)
    if (typeof normalizedProperties.coordinateProperties === 'string') {
        try {
            normalizedProperties.coordinateProperties = JSON.parse(normalizedProperties.coordinateProperties)
        } catch {
            console.warn('Failed to parse coordinateProperties as JSON:', normalizedProperties.coordinateProperties)
            normalizedProperties.coordinateProperties = null
        }
    }

    // Return pure GeoJSON feature
    return {
        type: 'Feature',
        // Add database_id as a top-level property for use as RecycleScroller key
        database_id: normalizedProperties.database_id as string | number | undefined,
        properties: normalizedProperties,
        geometry: geometry
    }
}

