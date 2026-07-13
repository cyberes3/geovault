/**
 * The single canonical "no better answer yet" fallback camera position, used by every map
 * instance (MapLibre and OpenLayers) when there's no geolocation, saved camera, URL-driven view,
 * or feature/track extent to fit to.
 */
export const WORLD_VIEW_CENTER_LONLAT: [number, number] = [0, 0];
export const WORLD_VIEW_ZOOM = 2;
