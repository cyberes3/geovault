const GEOCODING_SEARCH_PATH = '/api/geocoding/search/';

export function parseGeocodingFeatures(payload: unknown): unknown[] {
  const data = (payload as { data?: unknown } | null)?.data;
  if (data && typeof data === 'object' && Array.isArray((data as { features?: unknown }).features)) {
    return (data as { features: unknown[] }).features;
  }
  if (Array.isArray(data)) {
    return data;
  }
  return [];
}

export interface GeocodingSearchOptions {
  signal?: AbortSignal;
  fetchFn?: typeof fetch;
}

export interface GeocodingSearchResult {
  ok: boolean;
  features: unknown[];
  error?: string;
}

export async function searchGeocoding(query: string, options: GeocodingSearchOptions = {}): Promise<GeocodingSearchResult> {
  const trimmed = query.trim();
  if (!trimmed) {
    return { ok: true, features: [] };
  }

  const fetchFn = options.fetchFn ?? window.fetch.bind(window);
  const url = `${GEOCODING_SEARCH_PATH}?q=${encodeURIComponent(trimmed)}`;
  const response = await fetchFn(url, {
    credentials: 'include',
    signal: options.signal,
  });
  const payload: unknown = await response.json();
  if (!response.ok) {
    const errorPayload = payload as { error?: string; message?: string } | null;
    return {
      ok: false,
      features: [],
      error: errorPayload?.error || errorPayload?.message || 'Place search failed',
    };
  }
  return {
    ok: true,
    features: parseGeocodingFeatures(payload),
  };
}

export function getGeocodingResultCoordinates(result: unknown): { lon: number; lat: number } | null {
  const r = result as { coordinates?: unknown; geometry?: { coordinates?: unknown }; center?: unknown } | null;
  const coords = r?.coordinates ?? r?.geometry?.coordinates ?? r?.center;
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

export function getGeocodingResultLabel(result: unknown): string {
  const r = result as { text?: string; place_name?: string } | null;
  return r?.text || r?.place_name || 'Unknown place';
}
