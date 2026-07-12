import {TileSourceCatalog} from '../tileSources/TileSourceCatalog.js'
import {OpenLayersBasemapFactory} from './OpenLayersBasemapFactory.js'

/** Shared catalog instance so every consumer (map basemaps, settings UI) hits the same cache. */
export const tileSourceCatalog = new TileSourceCatalog()

/** Shared basemap factory for small OpenLayers maps (import previews, feature replacement, etc.). */
export const openLayersBasemap = new OpenLayersBasemapFactory(tileSourceCatalog)

export {OpenLayersBasemapFactory} from './OpenLayersBasemapFactory.js'
export {
  TileSourceCatalog,
  TileSourceCatalogError
} from '../tileSources/TileSourceCatalog.js'
export {RasterTileUrls} from '../tileSources/RasterTileUrls.js'
export {
  OSM_TILE_SOURCE_ID,
  TILE_SOURCES_API_PATH,
  TILE_SOURCES_API_URL
} from '../tileSources/constants.js'
