import type TileLayerType from 'ol/layer/Tile'
import type { Options as XYZOptions } from 'ol/source/XYZ'
import type { TileSource } from '@/api/services/tilesApi'
import { OSM_TILE_SOURCE_ID } from '../tileSources/constants.js'
import { RasterTileUrls } from '../tileSources/RasterTileUrls.js'
import type { TileSourceCatalog } from '../tileSources/TileSourceCatalog.js'

/**
 * `ol/source` and `ol/layer` pull in the bulk of OpenLayers, so they're loaded lazily here rather
 * than statically imported - see `lazyOl.js` for why. Cached the same way.
 */
let olModulesPromise: Promise<[typeof import('ol/source'), typeof import('ol/layer')]> | null = null

function loadOlModules(): Promise<[typeof import('ol/source'), typeof import('ol/layer')]> {
  olModulesPromise ??= Promise.all([import('ol/source'), import('ol/layer')])
  return olModulesPromise
}

/**
 * Creates OpenLayers raster basemap layers from server tile-source configuration.
 */
export class OpenLayersBasemapFactory {
  private catalog: TileSourceCatalog

  constructor(catalog: TileSourceCatalog) {
    this.catalog = catalog
  }

  prefetch(): Promise<TileSource[]> {
    return this.catalog.prefetch()
  }

  async createTileLayer(sourceId: string = OSM_TILE_SOURCE_ID): Promise<TileLayerType> {
    const tileSource = await this.catalog.resolveSourceById(sourceId)
    return this.createTileLayerFromSource(tileSource)
  }

  async createTileLayerFromSource(tileSource: TileSource): Promise<TileLayerType> {
    const [{XYZ}, {Tile: TileLayer}] = await loadOlModules()

    const clientConfig = tileSource.client_config
    const urls = RasterTileUrls.fromTileSource(tileSource)

    const xyzOptions: XYZOptions = {
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
