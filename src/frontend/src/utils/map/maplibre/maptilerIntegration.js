/**
 * MapTiler integration utilities for MapLibre
 * 
 * This module handles all MapTiler-specific functionality including:
 * - 3D terrain configuration
 * - Hillshade layers
 * - API key and proxy management
 * - Terrain toggle control
 */

import { fetchConfig as fetchCachedConfig } from '@/utils/configService.js'

/**
 * MapTiler configuration class
 * Manages API key, proxy settings, and terrain/hillshade sources
 */
export class MapTilerConfig {
  constructor() {
    this.apiKey = null
    this.useProxy = false
    this.terrainSource = null
    this.hillshadeSource = null
    this.terrainExaggeration = 1.5  // Default terrain exaggeration
    this.hillshadeOpacity = 0.3     // Default hillshade opacity
  }

  /**
   * Fetch MapTiler configuration and tile sources from the backend
   * @param {Array} tileSources - Pre-fetched tile sources array to avoid duplicate API call
   * @param {Object} serverConfig - Optional pre-fetched server config to avoid duplicate API call
   * @returns {Promise<boolean>} True if MapTiler is configured
   */
  async fetchConfig(tileSources = null, serverConfig = null) {
    try {
      // Use provided server config or fetch from cache (which may also fetch if not cached)
      const config = serverConfig || await fetchCachedConfig()
      
      if (!config.maptiler) {
        return false
      }
      
      this.useProxy = config.maptiler.proxy_tiles || false
      // API key is only provided when not using proxy
      this.apiKey = config.maptiler.apiKey || null
      
      // Use provided tile sources (should always be provided to avoid duplicate API call)
      let sources = tileSources
      if (!sources) {
        console.warn('MapTilerConfig.fetchConfig called without tileSources - this may cause duplicate API calls')
        const tilesResponse = await fetch('/api/tiles/sources/')
        const tilesData = await tilesResponse.json()
        sources = tilesData.sources || []
      }
      
      // Find terrain and hillshade sources
      for (const source of sources) {
        if (source.id === 'maptiler_terrain') {
          this.terrainSource = source.client_config
          this.terrainExaggeration = source.exaggeration || 1.5
        } else if (source.id === 'maptiler_hillshade') {
          this.hillshadeSource = source.client_config
          this.hillshadeOpacity = source.opacity || 0.3
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

  /**
   * Check if MapTiler is available (either with API key or proxy)
   * @returns {boolean}
   */
  isAvailable() {
    return (this.apiKey !== null || this.useProxy) && this.terrainSource !== null
  }

  /**
   * Create terrain source configuration for MapLibre
   * @returns {Object|null} MapLibre terrain source configuration
   */
  createTerrainSource() {
    return this.terrainSource
  }

  /**
   * Create hillshade source configuration for MapLibre
   * @returns {Object|null} MapLibre hillshade source configuration
   */
  createHillshadeSource() {
    return this.hillshadeSource
  }
}

/**
 * Setup 3D terrain on the map
 * @param {Object} map - MapLibre map instance
 * @param {MapTilerConfig} config - MapTiler configuration
 */
export function setupTerrain(map, config) {
  if (!map || !config.isAvailable()) {
    return
  }

  try {
    // Remove existing terrain first to ensure clean setup
    if (map.getSource('terrain-source')) {
      // Remove terrain configuration first
      if (map.getStyle()) {
        map.setTerrain(null)
      }
      // Then remove the source
      map.removeSource('terrain-source')
    }

    // Add terrain source
    const terrainSource = config.createTerrainSource()
    map.addSource('terrain-source', terrainSource)

    // Configure terrain in map style using exaggeration from config
    if (map.getStyle()) {
      map.setTerrain({
        source: 'terrain-source',
        exaggeration: config.terrainExaggeration
      })
    }

    // Add atmospheric fog effect for enhanced 3D visualization
    setupAtmosphere(map)
  } catch (error) {
    console.error('Error setting up terrain:', error)
  }
}

/**
 * Remove 3D terrain from the map
 * @param {Object} map - MapLibre map instance
 */
export function removeTerrain(map) {
  if (!map) {
    return
  }

  try {
    // Remove atmospheric fog effect
    removeAtmosphere(map)

    // Remove terrain configuration
    if (map.getStyle()) {
      map.setTerrain(null)
    }

    // Remove terrain source
    if (map.getSource('terrain-source')) {
      map.removeSource('terrain-source')
    }
  } catch (error) {
    console.error('Error removing terrain:', error)
  }
}

/**
 * Add hillshade layer to the map
 * @param {Object} map - MapLibre map instance
 * @param {MapTilerConfig} config - MapTiler configuration
 * @param {string} beforeLayer - Layer ID to insert hillshade before (default: 'feature-layer')
 */
export function addHillshade(map, config, beforeLayer = 'feature-layer') {
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
      map.addSource('hillshade-source', hillshadeSource)
    }

    // Add hillshade layer using opacity from config
    const layerConfig = {
      id: 'hillshade-layer',
      type: 'raster',
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

/**
 * Remove hillshade layer from the map
 * @param {Object} map - MapLibre map instance
 */
export function removeHillshade(map) {
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
 * Setup atmospheric sky and fog effect for enhanced 3D visualization
 * Uses MapLibre's setSky() method to add realistic atmosphere
 * @param {Object} map - MapLibre map instance
 */
export function setupAtmosphere(map) {
  if (!map) {
    return
  }

  try {
    // Add sky and fog effect using MapLibre's setSky() method
    // This creates a realistic atmosphere with sky, horizon, and fog colors
    // Reference: https://maplibre.org/maplibre-gl-js/docs/examples/sky-fog-terrain/
    map.setSky({
      'sky-color': '#80b3ff',           // Light blue sky color
      'sky-horizon-blend': 0.5,         // Smooth blend between sky and horizon (0-1)
      'horizon-color': '#d1e7ff',       // Lighter blue horizon color
      'horizon-fog-blend': 0.5,         // Blend between horizon and fog (0-1)
      'fog-color': '#c0d8f0',           // Soft blue-gray fog color
      'fog-ground-blend': 0.1           // How fog blends with ground (0-1)
    })
  } catch (error) {
    console.error('Error setting up atmosphere:', error)
  }
}

/**
 * Remove atmospheric sky and fog effect from the map
 * @param {Object} map - MapLibre map instance
 */
export function removeAtmosphere(map) {
  if (!map) {
    return
  }

  try {
    // Remove sky and fog effect by passing undefined
    map.setSky(undefined)
  } catch (error) {
    console.error('Error removing atmosphere:', error)
  }
}

/**
 * Create a 3D terrain toggle control for MapLibre
 * @param {Object} options - Control options
 * @param {boolean} options.initialState - Initial terrain state
 * @param {Function} options.onToggle - Callback when terrain is toggled (receives new state)
 * @returns {Object} MapLibre IControl implementation
 */
export function createTerrainControl(options = {}) {
  const { initialState = false, onToggle = () => {} } = options

  return {
    onAdd: (map) => {
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

