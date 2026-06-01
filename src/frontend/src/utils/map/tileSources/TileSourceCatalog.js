import {
  FALLBACK_OSM_TILE_SOURCE,
  OSM_TILE_SOURCE_ID,
  TILE_SOURCES_API_URL
} from './constants.js'

/**
 * Loads and caches tile sources from the GeoVault API.
 * Proxy URLs (e.g. /api/tiles/osm/...) are applied server-side before this client sees them.
 */
export class TileSourceCatalog {
  /**
   * @param {{ apiUrl?: string, fetchFn?: typeof fetch }} [options]
   */
  constructor(options = {}) {
    this.apiUrl = options.apiUrl ?? TILE_SOURCES_API_URL
    this.fetchFn = options.fetchFn ?? fetch
    this._loadPromise = null
  }

  /** Start loading tile sources without awaiting (e.g. before many maps initialize). */
  prefetch() {
    return this.load()
  }

  /** @returns {Promise<object[]>} */
  load() {
    if (!this._loadPromise) {
      this._loadPromise = this._fetchVisibleSources()
    }
    return this._loadPromise
  }

  /**
   * @param {object[]} sources
   * @param {string} [preferredId]
   * @returns {object}
   */
  resolveSource(sources, preferredId = OSM_TILE_SOURCE_ID) {
    if (!Array.isArray(sources) || sources.length === 0) {
      return FALLBACK_OSM_TILE_SOURCE
    }

    if (preferredId) {
      const preferred = sources.find((source) => source.id === preferredId)
      if (preferred) {
        return preferred
      }
    }

    const osm = sources.find((source) => source.id === OSM_TILE_SOURCE_ID)
    return osm ?? sources[0]
  }

  /**
   * @param {string} [preferredId]
   * @returns {Promise<object>}
   */
  async resolveSourceById(preferredId = OSM_TILE_SOURCE_ID) {
    const sources = await this.load()
    return this.resolveSource(sources, preferredId)
  }

  /** Clears cache (tests). */
  reset() {
    this._loadPromise = null
  }

  async _fetchVisibleSources() {
    try {
      const response = await this.fetchFn(this.apiUrl, {credentials: 'include'})
      if (!response.ok) {
        throw new Error(`Tile sources HTTP ${response.status}`)
      }

      const data = await response.json()
      if (!Array.isArray(data?.sources)) {
        return [FALLBACK_OSM_TILE_SOURCE]
      }

      const visible = data.sources.filter((source) => !source.hidden)
      return visible.length > 0 ? visible : [FALLBACK_OSM_TILE_SOURCE]
    } catch (error) {
      console.warn('TileSourceCatalog: using OSM fallback', error)
      return [FALLBACK_OSM_TILE_SOURCE]
    }
  }
}
