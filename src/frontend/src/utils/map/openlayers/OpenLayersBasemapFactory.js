import {XYZ} from 'ol/source'
import {Tile as TileLayer} from 'ol/layer'

import {OSM_TILE_SOURCE_ID} from '../tileSources/constants.js'
import {RasterTileUrls} from '../tileSources/RasterTileUrls.js'

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

  async createTileLayer(sourceId = OSM_TILE_SOURCE_ID) {
    const tileSource = await this.catalog.resolveSourceById(sourceId)
    return this.createTileLayerFromSource(tileSource)
  }

  createTileLayerFromSource(tileSource) {
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
