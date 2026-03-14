/**
 * Build the track shape expected by LatestParamsModal from a track-like object
 * (raw API response or normalized track with geometry.coordinates and point_params).
 * Used by WorldShareView and can be used by LiveTrackView for consistency.
 */

/**
 * @param {Object} track - Track with geometry.coordinates, point_params (array), name
 * @returns {{ name: string, last_position: { lon: number, lat: number } | null, last_timestamp_ms: number | null, latestPointParams: Object }}
 */
export function trackToParamsModalShape(track) {
  if (!track) return null;
  const coords = track.geometry?.coordinates || [];
  const lastCoord = coords[coords.length - 1];
  const pointParams = track.point_params || [];
  const latestParams = pointParams.length ? pointParams[pointParams.length - 1] : {};
  let lastTimestampMs = null;
  if (latestParams && typeof latestParams === 'object') {
    const tsKey = Object.keys(latestParams).find((k) => k.toLowerCase().includes('timestamp'));
    if (tsKey != null && latestParams[tsKey] != null) {
      lastTimestampMs = latestParams[tsKey];
    }
  }
  return {
    name: track.name,
    last_position: lastCoord && lastCoord.length >= 2 ? { lon: lastCoord[0], lat: lastCoord[1] } : null,
    last_timestamp_ms: lastTimestampMs,
    latestPointParams: latestParams
  };
}
