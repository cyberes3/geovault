export const TILE_SOURCES_API_URL = '/api/tiles/sources/'

export const OSM_TILE_SOURCE_ID = 'osm'

/** Fallback when the tile-sources API is unavailable. */
export const FALLBACK_OSM_TILE_SOURCE = {
  id: OSM_TILE_SOURCE_ID,
  name: 'OpenStreetMap',
  type: 'xyz',
  client_config: {
    url: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    tileSize: 256,
    attribution: '© OpenStreetMap contributors'
  }
}
