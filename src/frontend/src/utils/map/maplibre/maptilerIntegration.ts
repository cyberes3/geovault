/**
 * MapTiler integration utilities for MapLibre
 *
 * This module handles all MapTiler-specific functionality including:
 * - 3D terrain configuration
 * - Hillshade layers
 * - API key and proxy management
 * - Terrain toggle control
 */

import type { Map as MapLibreMap, IControl, SourceSpecification } from 'maplibre-gl'
import { fetchConfig as fetchCachedConfig, type ServerConfig } from '@/utils/configService'
import type { TileSource, TileSourceClientConfig } from '@/api/services/tilesApi'

function callSetFogIfAvailable(map: MapLibreMap): void {
  // `setFog` was removed from MapLibre's public API/types, but some styles/older callers still
  // reference it - keep this defensive runtime check without letting it break the types.
  const mapWithFog = map as unknown as { setFog?: (fog: null) => void }
  mapWithFog.setFog?.(null)
}

/**
 * MapTiler configuration class
 * Manages API key, proxy settings, and terrain/hillshade sources
 */
export class MapTilerConfig {
  apiKey: string | null = null
  useProxy = false
  terrainSource: TileSourceClientConfig | null = null
  hillshadeSource: TileSourceClientConfig | null = null
  terrainExaggeration = 1.5 // Default terrain exaggeration
  hillshadeOpacity = 0.3 // Default hillshade opacity

  /**
   * Fetch MapTiler configuration and tile sources from the backend
   * @param tileSources Pre-fetched tile sources array to avoid duplicate API call
   * @param serverConfig Optional pre-fetched server config to avoid duplicate API call
   * @returns True if MapTiler is configured
   */
  async fetchConfig(tileSources: TileSource[] | null = null, serverConfig: ServerConfig | null = null): Promise<boolean> {
    try {
      // Use provided server config or fetch from cache (which may also fetch if not cached)
      const config = serverConfig ?? await fetchCachedConfig()

      if (!config.maptiler) {
        return false
      }

      this.useProxy = config.maptiler.proxy_tiles ?? false
      // API key is only provided when not using proxy
      this.apiKey = config.maptiler.apiKey ?? null

      // Use provided tile sources (should always be provided to avoid duplicate API call)
      let sources = tileSources
      if (!sources) {
        console.warn('MapTilerConfig.fetchConfig called without tileSources - this may cause duplicate API calls')
        const tilesResponse = await fetch('/api/tiles/sources/')
        const tilesData: unknown = await tilesResponse.json()
        sources = ((tilesData as { sources?: TileSource[] }).sources) ?? []
      }

      // Find terrain and hillshade sources
      for (const source of sources) {
        if (source.id === 'maptiler-terrain') {
          this.terrainSource = source.client_config
          this.terrainExaggeration = source.exaggeration ?? 1.5
        } else if (source.id === 'maptiler-hillshade') {
          this.hillshadeSource = source.client_config
          this.hillshadeOpacity = source.opacity ?? 0.3
        }
      }

      return true
    } catch (error) {
      console.error('Error fetching MapTiler config:', error)
      this.apiKey = null
      this.useProxy = false
      this.terrainSource = null
      this.hillshadeSource = null
      return false
    }
  }

  /** Check if MapTiler is available (either with API key or proxy) */
  isAvailable(): boolean {
    return (this.apiKey !== null || this.useProxy) && this.terrainSource !== null
  }

  /** Create terrain source configuration for MapLibre */
  createTerrainSource(): TileSourceClientConfig | null {
    return this.terrainSource
  }

  /** Create hillshade source configuration for MapLibre */
  createHillshadeSource(): TileSourceClientConfig | null {
    return this.hillshadeSource
  }
}

/**
 * Setup 3D terrain on the map
 * Parallelizes terrain source setup and sky/atmosphere setup for faster initialization
 */
export async function setupTerrain(map: MapLibreMap | null | undefined, config: MapTilerConfig, applyAtmosphere = true): Promise<void> {
  if (!map || !config.isAvailable()) {
    return
  }

  try {
    // Remove existing terrain first to ensure clean setup
    if (map.getSource('terrain-source')) {
      // Remove terrain configuration first
      map.setTerrain(null)
      // Then remove the source
      map.removeSource('terrain-source')
    }

    // Parallelize terrain source setup and sky/atmosphere setup
    // These operations are independent and can happen simultaneously
    await Promise.all([
      // Setup terrain source (tiles will load asynchronously in background)
      (async () => {
        const terrainSource = config.createTerrainSource()
        map.addSource('terrain-source', terrainSource as unknown as SourceSpecification)

        // Configure terrain in map style using exaggeration from config
        map.setTerrain({
          source: 'terrain-source',
          exaggeration: config.terrainExaggeration
        })
      })(),

      // Setup sky/atmosphere (independent of terrain source)
      (async () => {
        // Remove any existing sky/atmosphere first to ensure clean state
        removeAtmosphere(map)

        // Always set blue sky when terrain is enabled
        // Only apply full atmosphere effects (fog) on imagery/satellite layers
        if (applyAtmosphere) {
          setupAtmosphere(map)
        } else {
          setupSky(map)
        }
      })()
    ])
  } catch (error) {
    console.error('Error setting up terrain:', error)
  }
}

/** Remove 3D terrain from the map */
export function removeTerrain(map: MapLibreMap | null | undefined): void {
  if (!map) {
    return
  }

  try {
    // Remove atmospheric fog effect
    removeAtmosphere(map)

    // Remove terrain configuration
    map.setTerrain(null)

    // Remove terrain source
    if (map.getSource('terrain-source')) {
      map.removeSource('terrain-source')
    }
  } catch (error) {
    console.error('Error removing terrain:', error)
  }
}

/** Add hillshade layer to the map */
export function addHillshade(map: MapLibreMap | null | undefined, config: MapTilerConfig, beforeLayer = 'feature-layer'): void {
  if (!map || !config.isAvailable()) {
    return
  }

  try {
    // Check if hillshade is already added
    if (map.getLayer('hillshade-layer')) {
      return
    }

    // Add hillshade source if not present
    if (!map.getSource('hillshade-source')) {
      const hillshadeSource = config.createHillshadeSource()
      map.addSource('hillshade-source', hillshadeSource as unknown as SourceSpecification)
    }

    // Add hillshade layer using opacity from config
    const layerConfig = {
      id: 'hillshade-layer',
      type: 'raster' as const,
      source: 'hillshade-source',
      paint: {
        'raster-opacity': config.hillshadeOpacity
      }
    }

    // Add before specified layer if it exists
    if (map.getLayer(beforeLayer)) {
      map.addLayer(layerConfig, beforeLayer)
    } else {
      map.addLayer(layerConfig)
    }
  } catch (error) {
    console.error('Error adding hillshade:', error)
  }
}

/** Remove hillshade layer from the map */
export function removeHillshade(map: MapLibreMap | null | undefined): void {
  if (!map) {
    return
  }

  try {
    // Remove hillshade layer
    if (map.getLayer('hillshade-layer')) {
      map.removeLayer('hillshade-layer')
    }

    // Remove hillshade source
    if (map.getSource('hillshade-source')) {
      map.removeSource('hillshade-source')
    }
  } catch (error) {
    console.error('Error removing hillshade:', error)
  }
}

/**
 * Setup blue sky only (no fog effects) for 3D visualization
 * Uses MapLibre's setSky() method to add a simple blue sky
 */
export function setupSky(map: MapLibreMap | null | undefined): void {
  if (!map) {
    return
  }

  try {
    // Add simple blue sky without fog effects
    // Only set sky and horizon properties, omit fog properties entirely
    // This ensures no fog effect is applied
    const skyConfig = {
      'sky-color': '#80b3ff', // Light blue sky color
      'sky-horizon-blend': 0.2, // Minimal blend between sky and horizon
      'horizon-color': '#d1e7ff' // Lighter blue horizon color
    }

    // Explicitly disable fog if setFog method exists
    callSetFogIfAvailable(map)

    map.setSky(skyConfig)
  } catch (error) {
    console.error('Error setting up sky:', error)
  }
}

/**
 * Setup atmospheric sky and fog effect for enhanced 3D visualization
 * Uses MapLibre's setSky() method to add realistic atmosphere with fog
 */
export function setupAtmosphere(map: MapLibreMap | null | undefined): void {
  if (!map) {
    return
  }

  try {
    // Add sky and fog effect using MapLibre's setSky() method
    // This creates a realistic atmosphere with sky, horizon, and fog colors
    // Reference: https://maplibre.org/maplibre-gl-js/docs/examples/sky-fog-terrain/
    map.setSky({
      'sky-color': '#80b3ff', // Light blue sky color
      'sky-horizon-blend': 0.5, // Smooth blend between sky and horizon (0-1)
      'horizon-color': '#d1e7ff', // Lighter blue horizon color
      'horizon-fog-blend': 0.21, // Blend between horizon and fog (reduced 75% total from 0.5)
      'fog-color': '#c0d8f0', // Soft blue-gray fog color
      'fog-ground-blend': 0.042 // How fog blends with ground (reduced 75% total from 0.1)
    })
  } catch (error) {
    console.error('Error setting up atmosphere:', error)
  }
}

/** Remove atmospheric sky and fog effect from the map */
export function removeAtmosphere(map: MapLibreMap | null | undefined): void {
  if (!map) {
    return
  }

  try {
    // Remove fog if setFog method exists
    callSetFogIfAvailable(map)
    // Remove sky and fog effect
    map.setSky(undefined as unknown as Parameters<typeof map.setSky>[0])
  } catch (error) {
    console.error('Error removing atmosphere:', error)
  }
}

export interface TerrainControlOptions {
  /** Initial terrain state */
  initialState?: boolean
  /** Callback when terrain is toggled (receives new state) */
  onToggle?: (newState: boolean) => void
}

/** Create a 3D terrain toggle control for MapLibre */
export function createTerrainControl(options: TerrainControlOptions = {}): IControl {
  const { initialState = false, onToggle = () => {} } = options

  return {
    onAdd: () => {
      const container = document.createElement('div')
      container.className = 'maplibregl-ctrl maplibregl-ctrl-group'

      const button = document.createElement('button')
      button.className = 'maplibregl-ctrl-terrain'
      button.type = 'button'
      button.title = 'Toggle 3D Terrain'
      button.setAttribute('aria-label', 'Toggle 3D Terrain')

      // Set initial state
      if (initialState) {
        button.classList.add('maplibregl-ctrl-terrain-enabled')
      }

      // Click handler
      button.onclick = () => {
        const isEnabled = button.classList.contains('maplibregl-ctrl-terrain-enabled')
        const newState = !isEnabled

        if (newState) {
          button.classList.add('maplibregl-ctrl-terrain-enabled')
        } else {
          button.classList.remove('maplibregl-ctrl-terrain-enabled')
        }

        onToggle(newState)
      }

      container.appendChild(button)
      return container
    },
    onRemove: () => {
      // Cleanup handled by MapLibre
    }
  }
}
