export const TILE_SOURCES_API_PATH = '/api/tiles/sources/'

export const TILE_SOURCES_API_URL = new URL(
  TILE_SOURCES_API_PATH,
  window.location.origin
).toString()

export const OSM_TILE_SOURCE_ID = 'osm'
