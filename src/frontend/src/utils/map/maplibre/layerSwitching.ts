import type { Map as MapLibreMap, GeoJSONSource, LngLatLike } from 'maplibre-gl'
import type { GeoJsonFeatureCollection } from '@/types/geospatial'
import type { LabelMarkerManager } from './labelMarkers.js'
import { ensureLayersExist, addFeaturesToMap } from './index.js'

function getGeoJsonSourceData(map: MapLibreMap): GeoJsonFeatureCollection | null {
  const source = map.getSource<GeoJSONSource>('geojson-data')
  if (!source) return null
  const data = source.serialize().data
  if (typeof data === 'string') return null
  return data as GeoJsonFeatureCollection
}

/**
 * Restore GeoJSON features to the map after layer switch
 */
export async function restoreGeoJsonFeatures(
  map: MapLibreMap | null | undefined,
  geojsonData: GeoJsonFeatureCollection | null | undefined,
  showAllLabels: boolean,
  labelMarkerManager: LabelMarkerManager | null | undefined
): Promise<void> {
  if (!map) return

  // Always ensure the geojson-data source exists (setStyle() destroys all sources)
  // Wait a brief moment to ensure style is fully loaded before adding source
  await new Promise(resolve => setTimeout(resolve, 50))

  if (!map.getSource('geojson-data')) {
    map.addSource('geojson-data', {
      type: 'geojson',
      data: {
        type: 'FeatureCollection',
        features: []
      }
    })
  }

  // Ensure layers exist before adding features
  ensureLayersExist(map, showAllLabels)

  // Restore features - setStyle() destroys all sources, so source should be empty
  // Use addFeaturesToMap which handles all the processing (icons, labels, etc.)
  // Since the source is empty after setStyle(), this will effectively set the features directly
  if (geojsonData?.features && geojsonData.features.length > 0) {
    try {
      const zoom = map.getZoom()
      // addFeaturesToMap will merge with existing features, but since setStyle() destroyed
      // all sources, the source should be empty, so this effectively sets the features
      await addFeaturesToMap(map, geojsonData, showAllLabels, zoom)

      // Verify features were actually added
      const data = getGeoJsonSourceData(map)
      const featuresAdded = data?.features && data.features.length > 0
      if (!featuresAdded) {
        console.warn('Features were not added to map during restoration', {
          expectedCount: geojsonData.features.length,
          sourceExists: !!map.getSource('geojson-data')
        })
      }
    } catch (error) {
      console.error('Error restoring features to map:', error)
      // Don't throw - allow the function to continue
    }
  }

  // Ensure feature layers are positioned after all base layers
  const ourFeatureLayers = ['polygons', 'polygon-outlines', 'lines', 'points', 'replacement-points', 'point-icons']
  ourFeatureLayers.forEach(layerId => {
    if (map.getLayer(layerId)) {
      map.moveLayer(layerId)
    }
  })

  // Update label markers
  if (showAllLabels && labelMarkerManager) {
    const data = getGeoJsonSourceData(map)
    if (data?.features) {
      labelMarkerManager.updateMarkers(data.features)
    }
  }
}

/**
 * Restore map view (center, zoom, pitch, bearing) after layer switch
 */
export function restoreMapView(
  map: MapLibreMap | null | undefined,
  center: LngLatLike,
  zoom: number,
  pitch: number,
  bearing: number
): void {
  if (!map) return
  map.setCenter(center)
  map.setZoom(zoom)
  map.setPitch(pitch)
  map.setBearing(bearing)
}

export interface MapState {
  center: ReturnType<MapLibreMap['getCenter']>
  zoom: number
  pitch: number
  bearing: number
}

/**
 * Extract current map state for restoration
 */
export function getMapState(map: MapLibreMap | null | undefined): MapState | null {
  if (!map) return null

  return {
    center: map.getCenter(),
    zoom: map.getZoom(),
    pitch: map.getPitch(),
    bearing: map.getBearing()
  }
}

/**
 * Extract GeoJSON data from map source
 */
export function getGeoJsonData(map: MapLibreMap | null | undefined): GeoJsonFeatureCollection | null {
  if (!map) return null
  return getGeoJsonSourceData(map)
}
