/**
 * Builds raster tile URL templates from server tile-source client_config.
 * Matches MapLibre basemap URL handling in MapPage.
 */
export class RasterTileUrls {
  /**
   * @param {{ id?: string, client_config?: object }} tileSource
   * @returns {string[]}
   */
  static fromTileSource(tileSource) {
    const clientConfig = tileSource?.client_config ?? {}
    const sourceId = tileSource?.id ?? 'osm'
    const template =
      clientConfig.url ?? `/api/tiles/${sourceId}/{z}/{x}/{y}`

    const subdomains = Array.isArray(clientConfig.tileSubdomains)
      ? clientConfig.tileSubdomains
      : null

    if (!subdomains?.length) {
      return [template.replace('{s}', 'a')]
    }

    return subdomains.map((subdomain) => template.replace('{s}', subdomain))
  }
}
