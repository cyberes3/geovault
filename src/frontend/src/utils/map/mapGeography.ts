/**
 * MapLibre / GeoJSON geographic limits: use before fitBounds, LngLatBounds, or map center.
 * Coordinates are [longitude, latitude] per GeoJSON; invalid pairs are rejected, not altered.
 */
export function isValidMapLngLatPair(lon: number, lat: number): boolean {
  return (
    Number.isFinite(lon) &&
    Number.isFinite(lat) &&
    Math.abs(lat) <= 90 &&
    Math.abs(lon) <= 180
  );
}
