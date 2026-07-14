import type { TileSource } from '@/api/services/tilesApi'
import {
  OSM_TILE_SOURCE_ID,
  TILE_SOURCES_API_URL
} from './constants.js'

export class TileSourceCatalogError extends Error {
  cause?: unknown

  constructor(message: string, options: { cause?: unknown } = {}) {
    super(message)
    this.name = 'TileSourceCatalogError'
    this.cause = options.cause
  }
}

interface TileSourceCatalogOptions {
  apiUrl?: string
  fetchFn?: typeof fetch
}

/**
 * Loads and caches tile sources from the GeoVault API.
 * Proxy URLs (e.g. /api/tiles/osm/...) are applied server-side before this client sees them.
 * This intentionally has no direct-tile fallback: clients must use the API so proxy config is honored.
 */
export class TileSourceCatalog {
  private apiUrl: string
  private fetchFn: typeof fetch
  private _loadPromise: Promise<TileSource[]> | null

  constructor(options: TileSourceCatalogOptions = {}) {
    this.apiUrl = options.apiUrl ?? TILE_SOURCES_API_URL
    this.fetchFn = options.fetchFn ?? window.fetch.bind(window)
    this._loadPromise = null
  }

  /** Start loading tile sources without awaiting (e.g. before many maps initialize). */
  prefetch(): Promise<TileSource[]> {
    return this.load()
  }

  load(): Promise<TileSource[]> {
    this._loadPromise ??= this._fetchVisibleSources().catch((error: unknown) => {
      this._loadPromise = null
      throw error
    })
    return this._loadPromise
  }

  resolveSource(sources: TileSource[], preferredId: string | undefined = OSM_TILE_SOURCE_ID): TileSource {
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

  async resolveSourceById(preferredId: string | undefined = OSM_TILE_SOURCE_ID): Promise<TileSource> {
    const sources = await this.load()
    return this.resolveSource(sources, preferredId)
  }

  /** Clears cache (tests). */
  reset(): void {
    this._loadPromise = null
  }

  private async _fetchVisibleSources(): Promise<TileSource[]> {
    try {
      const response = await this.fetchFn(this.apiUrl, {credentials: 'include'})
      if (!response.ok) {
        throw new Error(`HTTP ${response.status} ${response.statusText || ''}`.trim())
      }

      const data: unknown = await response.json()
      const sources = (data as { sources?: unknown }).sources
      if (!Array.isArray(sources)) {
        throw new Error('Tile sources response did not include a sources array')
      }

      const visible = (sources as TileSource[]).filter((source) => !source.hidden)
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
