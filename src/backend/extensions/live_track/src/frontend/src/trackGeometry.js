/**
 * Shared track geometry helpers: coordinate sorting, segment splitting, GeoJSON feature builders.
 * Used by LiveTrackView and WorldShareView for map drawing.
 */

import { getArrowImageId } from './trackArrowMap.js';

/** Do not draw track across jumps larger than this (meters). 100 miles. Same as Android tracker. */
export const MAX_JUMP_METERS = 100 * 1609.344;

export function distanceMeters(lon1, lat1, lon2, lat2) {
  const R = 6371000;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) * Math.sin(dLon / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c;
}

export function getCoordsSortedByTime(track) {
  const geom = track.geometry || {};
  const coords = geom.coordinates || [];
  if (coords.length <= 1) return [...coords];
  return [...coords].sort((a, b) => {
    const ta = typeof a[2] === 'number' ? a[2] : 0;
    const tb = typeof b[2] === 'number' ? b[2] : 0;
    return ta - tb;
  });
}

/** Degrees from north (0 = up), clockwise. Uses two most recent points by time. */
export function getTrackDirectionAngle(track) {
  const coords = getCoordsSortedByTime(track);
  if (coords.length < 2) return 0;
  const prev = coords[coords.length - 2];
  const last = coords[coords.length - 1];
  const dLon = last[0] - prev[0];
  const dLat = last[1] - prev[1];
  if (dLon === 0 && dLat === 0) return 0;
  return (Math.atan2(dLon, dLat) * 180) / Math.PI;
}

export function splitTrackIntoSegments(coords) {
  if (coords.length < 2) return [];
  const segments = [];
  let current = [coords[0]];
  for (let i = 1; i < coords.length; i++) {
    const prev = coords[i - 1];
    const curr = coords[i];
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

export function buildLineFeatures(track, selected = false) {
  const coordsSorted = getCoordsSortedByTime(track);
  const coords = coordsSorted.map((c) => [c[0], c[1]]);
  if (coords.length < 2) return [];
  const segments = splitTrackIntoSegments(coords);
  const color = track.color || '#6C93DE';
  const features = [];
  for (const segment of segments) {
    features.push({
      type: 'Feature',
      properties: { color, selected: !!selected },
      geometry: { type: 'LineString', coordinates: segment }
    });
  }
  return features;
}

export function buildPointFeature(track, selected = false) {
  const coordsSorted = getCoordsSortedByTime(track);
  const last = coordsSorted.length ? coordsSorted[coordsSorted.length - 1] : null;
  const pos = last && last.length >= 2 ? [last[0], last[1]] : null;
  if (!pos) return null;
  const color = track.color || '#6C93DE';
  const iconImage = getArrowImageId(color, selected);
  const rotation = getTrackDirectionAngle(track);
  return {
    type: 'Feature',
    properties: { color, iconImage, rotation, selected: !!selected },
    geometry: { type: 'Point', coordinates: pos }
  };
}

/**
 * Fit map bounds to multiple tracks' geometries. Uses getCoordsSortedByTime for ordering.
 * @param {import('maplibre-gl').Map} map - MapLibre map instance
 * @param {Object[]} tracks - Array of tracks with geometry.coordinates
 */
export function fitMapToTracks(map, tracks) {
  if (!map || !tracks?.length) return;
  const allCoords = [];
  for (const track of tracks) {
    const coords = getCoordsSortedByTime(track).map((c) => [c[0], c[1]]);
    allCoords.push(...coords);
  }
  if (allCoords.length >= 2) {
    const lons = allCoords.map((c) => c[0]);
    const lats = allCoords.map((c) => c[1]);
    map.fitBounds(
      [
        [Math.min(...lons), Math.min(...lats)],
        [Math.max(...lons), Math.max(...lats)]
      ],
      { padding: 40, maxZoom: 16, duration: 0 }
    );
  } else if (allCoords.length === 1) {
    map.jumpTo({ center: allCoords[0], zoom: 14, duration: 0 });
  }
}

/**
 * Fit map bounds to a single track's geometry. Uses getCoordsSortedByTime for ordering.
 * @param {import('maplibre-gl').Map} map - MapLibre map instance
 * @param {Object} track - Track with geometry.coordinates
 */
export function fitMapToSingleTrack(map, track) {
  if (!map || !track) return;
  const coords = getCoordsSortedByTime(track).map((c) => [c[0], c[1]]);
  if (coords.length >= 2) {
    const lons = coords.map((c) => c[0]);
    const lats = coords.map((c) => c[1]);
    map.fitBounds(
      [
        [Math.min(...lons), Math.min(...lats)],
        [Math.max(...lons), Math.max(...lats)]
      ],
      { padding: 40, maxZoom: 16, duration: 0 }
    );
  } else if (coords.length === 1) {
    map.jumpTo({ center: coords[0], zoom: 14, duration: 0 });
  }
}

/**
 * Pan map to the last point of a single track.
 * @param {import('maplibre-gl').Map} map - MapLibre map instance
 * @param {Object} track - Track with geometry.coordinates
 */
export function centerMapOnTrackLastPoint(map, track) {
  if (!map || !track) return;
  const coords = getCoordsSortedByTime(track).map((c) => [c[0], c[1]]);
  const last = coords.length ? coords[coords.length - 1] : null;
  if (!last) return;
  map.panTo(last, { duration: 200 });
}
