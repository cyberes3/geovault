import type { LngLatLike, Map as MapLibreMap } from 'maplibre-gl'
import { MapCamera } from '@/utils/map/common/MapCamera'
import type { CameraSnapshot } from '@/utils/map/common/types'

function asCenter(center: LngLatLike): [number, number] {
  if (Array.isArray(center)) return [Number(center[0]), Number(center[1])]
  const value = center as { lng: number; lat: number }
  return [value.lng, value.lat]
}

export function restoreMapView(
  map: MapLibreMap | null | undefined,
  center: LngLatLike,
  zoom: number,
  pitch: number,
  bearing: number
): void {
  new MapCamera().apply(map ?? null, { center: asCenter(center), zoom, pitch, bearing })
}

export function getMapState(map: MapLibreMap | null | undefined): CameraSnapshot | null {
  return new MapCamera().save(map ?? null)
}
