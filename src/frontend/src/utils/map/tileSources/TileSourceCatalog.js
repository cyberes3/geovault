import {
  OSM_TILE_SOURCE_ID,
  TILE_SOURCES_API_URL
} from './constants.js'

export class TileSourceCatalogError extends Error {
  constructor(message, options = {}) {
    super(message)
    this.name = 'TileSourceCatalogError'
    this.cause = options.cause
  }
}

/**
 * Loads and caches tile sources from the GeoVault API.
 * Proxy URLs (e.g. /api/tiles/osm/...) are applied server-side before this client sees them.
 * This intentionally has no direct-tile fallback: clients must use the API so proxy config is honored.
 */
export class TileSourceCatalog {
  /**
   * @param {{ apiUrl?: string, fetchFn?: typeof fetch }} [options]
   */
  constructor(options = {}) {
    this.apiUrl = options.apiUrl ?? TILE_SOURCES_API_URL
    this.fetchFn = options.fetchFn ?? window.fetch.bind(window)
    this._loadPromise = null
  }

  /** Start loading tile sources without awaiting (e.g. before many maps initialize). */
  prefetch() {
    return this.load()
  }

  /** @returns {Promise<object[]>} */
  load() {
    if (!this._loadPromise) {
      this._loadPromise = this._fetchVisibleSources().catch((error) => {
        this._loadPromise = null
        throw error
      })
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
      throw new TileSourceCatalogError('Tile sources API returned no visible tile sources')
    }

    if (preferredId) {
      const preferred = sources.find((source) => source.id === preferredId)
      if (preferred) {
        return preferred
      }
      throw new TileSourceCatalogError(`Tile sources API did not include required source: ${preferredId}`)
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
        throw new Error(`HTTP ${response.status} ${response.statusText || ''}`.trim())
      }

      const data = await response.json()
      if (!Array.isArray(data?.sources)) {
        throw new Error('Tile sources response did not include a sources array')
      }

      const visible = data.sources.filter((source) => !source.hidden)
      if (visible.length === 0) {
        throw new Error('Tile sources response did not include visible sources')
      }
      return visible
    } catch (error) {
      const causeMessage = error instanceof Error ? error.message : String(error)
      throw new TileSourceCatalogError(
        `Unable to load tile sources from ${this.apiUrl}: ${causeMessage}`,
        {cause: error}
      )
    }
  }
}
