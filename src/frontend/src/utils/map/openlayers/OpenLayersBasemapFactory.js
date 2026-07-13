import {OSM_TILE_SOURCE_ID} from '../tileSources/constants.js'
import {RasterTileUrls} from '../tileSources/RasterTileUrls.js'

/**
 * `ol/source` and `ol/layer` pull in the bulk of OpenLayers, so they're loaded lazily here rather
 * than statically imported - see `lazyOl.js` for why. Cached the same way.
 * @type {Promise<[typeof import('ol/source'), typeof import('ol/layer')]> | null}
 */
let olModulesPromise = null

/** @returns {Promise<[typeof import('ol/source'), typeof import('ol/layer')]>} */
function loadOlModules() {
  olModulesPromise ??= Promise.all([import('ol/source'), import('ol/layer')])
  return olModulesPromise
}

/**
 * Creates OpenLayers raster basemap layers from server tile-source configuration.
 */
export class OpenLayersBasemapFactory {
  /**
   * @param {import('../tileSources/TileSourceCatalog.js').TileSourceCatalog} catalog
   */
  constructor(catalog) {
    this.catalog = catalog
  }

  prefetch() {
    return this.catalog.prefetch()
  }

  /** @returns {Promise<import('ol/layer').Tile>} */
  async createTileLayer(sourceId = OSM_TILE_SOURCE_ID) {
    const tileSource = await this.catalog.resolveSourceById(sourceId)
    return this.createTileLayerFromSource(tileSource)
  }

  /** @returns {Promise<import('ol/layer').Tile>} */
  async createTileLayerFromSource(tileSource) {
    const [{XYZ}, {Tile: TileLayer}] = await loadOlModules()

    const clientConfig = tileSource.client_config ?? {}
    const urls = RasterTileUrls.fromTileSource(tileSource)

    const xyzOptions = {
      crossOrigin: 'anonymous',
      attributions: clientConfig.attribution ?? ''
    }

    if (urls.length === 1) {
      xyzOptions.url = urls[0]
    } else {
      xyzOptions.urls = urls
    }

    return new TileLayer({
      source: new XYZ(xyzOptions)
    })
  }
}
