/**
 * Shared track geometry helpers: coordinate sorting, segment splitting, GeoJSON feature builders.
 * Used by LiveTrackView and WorldShareView for map drawing.
 */
import type { Map as MapLibreMap, PaddingOptions } from 'maplibre-gl';
import { getArrowImageId } from './trackArrowMap';
import { resolveSelectedTrackAccuracyMeters } from './mapAccuracyCircle';
import { resolveTrackLastCoordinate } from './trackLastPoint';
import type { LiveTrack, TrackCoordinate } from './types/track';

type LonLat = [number, number];

export const isValidMapLngLatPair = window.gv_core.isValidMapLngLatPair;

/** Do not draw track across jumps larger than this (meters). 5 miles. Same as Android tracker. */
export const MAX_JUMP_METERS = 5 * 1609.344;

function filterValidLngLats(coordList: LonLat[] | null | undefined): LonLat[] {
  if (!Array.isArray(coordList)) return [];
  return coordList.filter((c) => isValidMapLngLatPair(c[0], c[1]));
}

export function distanceMeters(lon1: number, lat1: number, lon2: number, lat2: number): number {
  const R = 6371000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

export function getCoordsSortedByTime(track: LiveTrack): TrackCoordinate[] {
  const geom = track.geometry ?? { type: 'LineString', coordinates: [] };
  const coords = geom.coordinates;
  if (coords.length <= 1) return [...coords];
  return [...coords].sort((a, b) => {
    const ta = typeof a[2] === 'number' ? a[2] : 0;
    const tb = typeof b[2] === 'number' ? b[2] : 0;
    return ta - tb;
  });
}

/** Degrees from north (0 = up), clockwise. Uses two most recent points by time. */
export function getTrackDirectionAngle(track: LiveTrack): number {
  const sorted = getCoordsSortedByTime(track);
  const valid = filterValidLngLats(sorted.map((c): LonLat => [c[0], c[1]]));
  const resolved = resolveTrackLastCoordinate(track);
  if (resolved && resolved.length >= 2 && isValidMapLngLatPair(resolved[0], resolved[1])) {
    const head: LonLat = [resolved[0], resolved[1]];
    const tail = valid[valid.length - 1];
    if (!tail || tail[0] !== head[0] || tail[1] !== head[1]) {
      valid.push(head);
    }
  }
  if (valid.length < 2) return 0;
  const prev = valid[valid.length - 2];
  const last = valid[valid.length - 1];
  const dLon = last[0] - prev[0];
  const dLat = last[1] - prev[1];
  if (dLon === 0 && dLat === 0) return 0;
  return (Math.atan2(dLon, dLat) * 180) / Math.PI;
}

export function splitTrackIntoSegments(coords: LonLat[]): LonLat[][] {
  const valid = filterValidLngLats(coords);
  if (valid.length < 2) return [];
  const segments: LonLat[][] = [];
  let current: LonLat[] = [valid[0]];
  for (let i = 1; i < valid.length; i++) {
    const prev = valid[i - 1];
    const curr = valid[i];
    const dist = distanceMeters(prev[0], prev[1], curr[0], curr[1]);
    if (dist > MAX_JUMP_METERS) {
      if (current.length >= 2) segments.push(current);
      current = [curr];
    } else {
      current.push(curr);
    }
  }
  if (current.length >= 2) segments.push(current);
  return segments;
}

export interface TrackLineFeature {
  type: 'Feature';
  properties: { color: string; selected: boolean; trackId: string | number | undefined };
  geometry: { type: 'LineString'; coordinates: LonLat[] };
}

export function buildLineFeatures(track: LiveTrack, selected: boolean = false): TrackLineFeature[] {
  const coordsSorted = getCoordsSortedByTime(track);
  const coords = filterValidLngLats(coordsSorted.map((c): LonLat => [c[0], c[1]]));
  if (coords.length < 2) return [];
  const segments = splitTrackIntoSegments(coords);
  const color = track.color ?? '#6C93DE';
  const features: TrackLineFeature[] = [];
  const trackId = track.id;
  for (const segment of segments) {
    features.push({
      type: 'Feature',
      properties: { color, selected: !!selected, trackId },
      geometry: { type: 'LineString', coordinates: segment }
    });
  }
  return features;
}

export interface TrackPointFeature {
  type: 'Feature';
  properties: {
    color: string;
    iconImage: string;
    rotation: number;
    selected: boolean;
    trackId: string | number | undefined;
    accuracy?: number;
    latitude?: number;
  };
  geometry: { type: 'Point'; coordinates: LonLat };
}

export interface BuildPointFeatureOptions {
  includeAccuracy?: boolean;
}

export function buildPointFeature(track: LiveTrack, selected: boolean = false, options: BuildPointFeatureOptions = {}): TrackPointFeature | null {
  const last = resolveTrackLastCoordinate(track);
  const pos: LonLat | null =
    last && last.length >= 2 && isValidMapLngLatPair(last[0], last[1]) ? [last[0], last[1]] : null;
  if (!pos) return null;
  const color = track.color ?? '#6C93DE';
  const iconImage = getArrowImageId(color, selected);
  const rotation = getTrackDirectionAngle(track);
  const trackId = track.id;
  const includeAccuracy = options.includeAccuracy === true;
  const accuracy = includeAccuracy ? resolveSelectedTrackAccuracyMeters(track, selected) : 0;
  return {
    type: 'Feature',
    properties: {
      color,
      iconImage,
      rotation,
      selected: !!selected,
      trackId,
      ...(includeAccuracy ? { accuracy, latitude: pos[1] } : {})
    },
    geometry: { type: 'Point', coordinates: pos }
  };
}

export interface FitMapOptions {
  padding?: number | PaddingOptions;
  maxZoom?: number;
  duration?: number;
  singlePointZoom?: number;
}

/** Fit map bounds to multiple tracks' geometries. Uses getCoordsSortedByTime for ordering. */
export function fitMapToTracks(map: MapLibreMap | null | undefined, tracks: LiveTrack[] | null | undefined, options: FitMapOptions = {}): void {
  if (!map || !tracks?.length) return;
  const allCoords: LonLat[] = [];
  for (const track of tracks) {
    const raw = getCoordsSortedByTime(track).map((c): LonLat => [c[0], c[1]]);
    allCoords.push(...filterValidLngLats(raw));
  }
  const padding = options.padding ?? 40;
  const maxZoom = options.maxZoom ?? 16;
  const duration = options.duration ?? 0;
  if (allCoords.length === 0) return;
  if (allCoords.length >= 2) {
    const lons = allCoords.map((c) => c[0]);
    const lats = allCoords.map((c) => c[1]);
    map.fitBounds(
      [
        [Math.min(...lons), Math.min(...lats)],
        [Math.max(...lons), Math.max(...lats)]
      ],
      { padding, maxZoom, duration }
    );
  } else if (allCoords.length === 1) {
    map.jumpTo({ center: allCoords[0], zoom: options.singlePointZoom ?? 14, duration });
  }
}

/** Fit map bounds to a single track's geometry. Uses getCoordsSortedByTime for ordering. */
export function fitMapToSingleTrack(map: MapLibreMap | null | undefined, track: LiveTrack | null | undefined, options: FitMapOptions = {}): void {
  if (!map || !track) return;
  const coords = filterValidLngLats(getCoordsSortedByTime(track).map((c): LonLat => [c[0], c[1]]));
  const padding = options.padding ?? 40;
  const maxZoom = options.maxZoom ?? 16;
  const duration = options.duration ?? 0;
  if (coords.length === 0) return;
  if (coords.length >= 2) {
    const lons = coords.map((c) => c[0]);
    const lats = coords.map((c) => c[1]);
    map.fitBounds(
      [
        [Math.min(...lons), Math.min(...lats)],
        [Math.max(...lons), Math.max(...lats)]
      ],
      { padding, maxZoom, duration }
    );
  } else if (coords.length === 1) {
    map.jumpTo({ center: coords[0], zoom: options.singlePointZoom ?? 14, duration });
  }
}

export interface CenterMapOptions {
  duration?: number;
  padding?: number | PaddingOptions;
  minZoom?: number;
}

/** Pan map to the resolved last point of a single track. */
export function centerMapOnTrackLastPoint(map: MapLibreMap | null | undefined, track: LiveTrack | null | undefined, options: CenterMapOptions = {}): void {
  if (!map || !track) return;
  const resolved = resolveTrackLastCoordinate(track);
  if (!resolved || resolved.length < 2 || !isValidMapLngLatPair(resolved[0], resolved[1])) return;
  const last: LonLat = [resolved[0], resolved[1]];
  const duration = options.duration ?? 200;
  const zoom = options.minZoom != null ? Math.max(map.getZoom(), options.minZoom) : map.getZoom();
  if (options.padding != null) {
    map.easeTo({ center: last, zoom, duration, padding: options.padding });
    return;
  }
  map.easeTo({ center: last, zoom, duration });
}
