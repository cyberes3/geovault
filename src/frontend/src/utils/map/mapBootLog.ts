/**
 * Filterable production diagnostics for map boot and first data load.
 * Console prefix: `[map-boot]`. Do not put coordinates or style query strings in the payload.
 */
export const MAP_BOOT_LOG_PREFIX = '[map-boot]'

export function mapBootLog(step: string, details?: Record<string, unknown>): void {
  if (details) console.log(MAP_BOOT_LOG_PREFIX, step, details)
  else console.log(MAP_BOOT_LOG_PREFIX, step)
}

export function mapBootWarn(step: string, details?: Record<string, unknown>): void {
  if (details) console.warn(MAP_BOOT_LOG_PREFIX, step, details)
  else console.warn(MAP_BOOT_LOG_PREFIX, step)
}

export function mapBootError(step: string, details?: Record<string, unknown>): void {
  if (details) console.error(MAP_BOOT_LOG_PREFIX, step, details)
  else console.error(MAP_BOOT_LOG_PREFIX, step)
}

export function describeStyleInput(style: unknown): Record<string, unknown> {
  if (typeof style === 'string') {
    try {
      const url = new URL(style, typeof window !== 'undefined' ? window.location.origin : 'http://localhost')
      return { kind: 'url', host: url.host, path: url.pathname }
    } catch {
      return { kind: 'url' }
    }
  }
  if (style && typeof style === 'object') {
    const spec = style as { sources?: Record<string, unknown>; layers?: Array<{ id?: string }> }
    return {
      kind: 'spec',
      sourceIds: Object.keys(spec.sources ?? {}),
      layerIds: (spec.layers ?? []).map((layer) => layer.id).filter((id): id is string => !!id),
    }
  }
  return { kind: 'empty' }
}

export function describeMapSnapshot(map: {
  isStyleLoaded?: () => boolean
  loaded?: () => boolean
  getZoom?: () => number
  getStyle?: () => { sources?: Record<string, unknown>; layers?: Array<{ id?: string }> }
  getSource?: (id: string) => unknown
} | null | undefined): Record<string, unknown> {
  if (!map) return { hasMap: false }
  try {
    const style = map.getStyle?.()
    return {
      hasMap: true,
      styleLoaded: typeof map.isStyleLoaded === 'function' ? map.isStyleLoaded() : null,
      loaded: typeof map.loaded === 'function' ? map.loaded() : null,
      zoom: typeof map.getZoom === 'function' ? map.getZoom() : null,
      sourceIds: Object.keys(style?.sources ?? {}),
      layerIds: (style?.layers ?? []).map((layer) => layer.id).filter((id): id is string => !!id),
      hasGeojsonSource: !!map.getSource?.('geojson-data'),
    }
  } catch (error) {
    return {
      hasMap: true,
      snapshotError: error instanceof Error ? error.message : String(error),
    }
  }
}

export function describeError(error: unknown): string {
  if (error instanceof Error) return error.message
  return String(error)
}

export function attachMapLibreErrorLogging(map: { on: (event: 'error', handler: (event: { error?: Error; sourceId?: string }) => void) => void }): void {
  map.on('error', (event) => {
    mapBootError('maplibre-error', {
      message: event.error instanceof Error ? event.error.message : describeError(event.error ?? event),
      sourceId: event.sourceId ?? null,
    })
  })
}
