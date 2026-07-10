const GEOCODING_SEARCH_PATH = '/api/geocoding/search/';

/**
 * @param {unknown} payload
 * @returns {object[]}
 */
export function parseGeocodingFeatures(payload) {
  const data = payload?.data;
  if (data && Array.isArray(data.features)) {
    return data.features;
  }
  if (Array.isArray(data)) {
    return data;
  }
  return [];
}

/**
 * @param {string} query
 * @param {{ signal?: AbortSignal, fetchFn?: typeof fetch }} [options]
 * @returns {Promise<{ ok: boolean, features: object[], error?: string }>}
 */
export async function searchGeocoding(query, options = {}) {
  const trimmed = String(query || '').trim();
  if (!trimmed) {
    return { ok: true, features: [] };
  }

  const fetchFn = options.fetchFn ?? window.fetch.bind(window);
  const url = `${GEOCODING_SEARCH_PATH}?q=${encodeURIComponent(trimmed)}`;
  const response = await fetchFn(url, {
    credentials: 'include',
    signal: options.signal,
  });
  const payload = await response.json();
  if (!response.ok) {
    return {
      ok: false,
      features: [],
      error: payload?.error || payload?.message || 'Place search failed',
    };
  }
  return {
    ok: true,
    features: parseGeocodingFeatures(payload),
  };
}

/**
 * @param {object} result
 * @returns {{ lon: number, lat: number } | null}
 */
export function getGeocodingResultCoordinates(result) {
  const coords = result?.coordinates || result?.geometry?.coordinates || result?.center;
  if (!Array.isArray(coords) || coords.length < 2) {
    return null;
  }
  const lon = Number(coords[0]);
  const lat = Number(coords[1]);
  if (!Number.isFinite(lon) || !Number.isFinite(lat)) {
    return null;
  }
  return { lon, lat };
}

/**
 * @param {object} result
 * @returns {string}
 */
export function getGeocodingResultLabel(result) {
  return result?.text || result?.place_name || 'Unknown place';
}
