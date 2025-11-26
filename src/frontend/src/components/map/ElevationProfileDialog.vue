<template>
  <div v-if="feature" class="absolute bottom-0 left-0 right-0 bg-white border-t border-gray-200 z-20" style="height: 25%;">
    <div class="h-full flex flex-col">
      <!-- Header -->
      <div class="relative flex items-center justify-between px-4 py-3 border-b border-gray-200">
        <h3 class="text-lg font-semibold text-gray-900">Elevation Profile</h3>
        <div class="absolute left-1/2 transform -translate-x-1/2">
          <span class="text-lg text-gray-900">{{ getFeatureName(feature) }}</span>
        </div>
        <button
          @click="$emit('close')"
          class="text-gray-400 hover:text-gray-600 transition-colors"
          title="Close elevation profile"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
          </svg>
        </button>
      </div>

      <!-- Stats -->
      <div v-if="hasElevationData && stats" class="px-4 py-2 border-b border-gray-200 bg-gray-50">
        <div class="flex flex-wrap gap-x-6 gap-y-1 text-xs">
          <div>
            <span class="text-gray-600">Distance:</span>
            <span class="font-medium text-gray-900 ml-1">{{ stats.totalDistance }}</span>
          </div>
          <div>
            <span class="text-gray-600">Elevation Change:</span>
            <span class="font-medium text-gray-900 ml-1">{{ stats.totalElevationChange }}</span>
          </div>
          <div>
            <span class="text-gray-600">Elevation Range:</span>
            <span class="font-medium text-gray-900 ml-1">{{ stats.elevationRange }}</span>
          </div>
          <div>
            <span class="text-gray-600">Ascent:</span>
            <span class="font-medium text-gray-900 ml-1">{{ stats.grossAscent }}</span>
            <span class="text-gray-600 ml-2">Descent:</span>
            <span class="font-medium text-gray-900 ml-1">{{ stats.grossDescent }}</span>
          </div>
        </div>
      </div>

      <!-- Chart Container, Loading Spinner, or Warning -->
      <div class="flex-1 overflow-hidden relative">
        <!-- Chart Container -->
        <div v-if="hasElevationData" ref="chartContainer" class="h-full w-full relative">
          <canvas ref="chartCanvas"></canvas>
          <!-- Loading Spinner Overlay -->
          <div v-if="isUpdatingChart" class="absolute inset-0 flex items-center justify-center z-10 pointer-events-none">
            <div class="animate-spin rounded-full h-8 w-8 border-2 border-transparent bg-white bg-opacity-90 rounded-full p-2" style="border-bottom-color: #4B6BAB;"></div>
          </div>
        </div>
        <!-- Loading Spinner -->
        <div v-if="isUpdatingChart && feature" class="absolute inset-0 flex items-center justify-center bg-white z-20">
          <div class="text-center">
            <div class="inline-block animate-spin rounded-full h-8 w-8 border-2 border-transparent" style="border-bottom-color: #4B6BAB;"></div>
            <p class="mt-4 text-sm text-gray-600">Loading chart...</p>
          </div>
        </div>
        <!-- No Data Warning -->
        <div v-else-if="!hasElevationData && feature" class="absolute inset-0 flex items-center justify-center bg-white z-10">
          <div class="text-center">
            <svg class="w-12 h-12 text-yellow-500 mx-auto mb-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"></path>
            </svg>
            <p class="text-gray-700 font-medium">No elevation data available</p>
            <p class="text-sm text-gray-500 mt-1">This feature does not contain elevation information.</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import axios from 'axios'
import { Chart, registerables } from 'chart.js'
import { GeoJSON } from 'ol/format'
import { toLonLat } from 'ol/proj'
import { getCookie } from '@/assets/js/auth.js'

Chart.register(...registerables)

export default {
  name: 'ElevationProfileDialog',
  props: {
    feature: {
      type: Object,
      default: null
    }
  },
          emits: ['close', 'hover-point', 'hover-clear', 'click-point'],
  data() {
    return {
      chart: null,
      hasElevationData: false,
      isUpdatingChart: false,
      stats: null,
      coordinateMapping: null, // Maps chart data indices to original coordinates [lon, lat]
      distances: null // Store distances array for mapping
    }
  },
  computed: {
    elevationProfileSource() {
      // Get elevation profile source from store, default to 'gps'
      const settings = this.$store.state.userSettings
      return settings?.map?.elevation_profile_source || 'gps'
    }
  },
  watch: {
    feature: {
      handler() {
        this.$nextTick(() => {
          this.updateChart()
        })
      },
      immediate: true
    }
  },
  mounted() {
    this.updateChart()
    // Add keyboard event listener for Escape key
    document.addEventListener('keydown', this.handleKeyDown)
  },
  beforeUnmount() {
    // Remove keyboard event listener
    document.removeEventListener('keydown', this.handleKeyDown)

    // Destroy chart instance
    if (this.chart) {
      try {
        this.chart.destroy()
      } catch (e) {
        console.warn('Error destroying chart on unmount:', e)
      }
      this.chart = null
    }

    // Also check if Chart.js has a chart on the canvas
    if (this.$refs.chartCanvas) {
      const existingChart = Chart.getChart(this.$refs.chartCanvas)
      if (existingChart) {
        try {
          existingChart.destroy()
        } catch (e) {
          console.warn('Error destroying existing chart on unmount:', e)
        }
      }
    }
  },
  methods: {
    /**
     * Handle keyboard events
     */
    handleKeyDown(event) {
      if (event.key === 'Escape') {
        this.$emit('close')
      }
    },

    /**
     * Get feature name from properties
     * Returns feature name or 'Unnamed Feature' as fallback
     */
    getFeatureName(feature) {
      if (!feature) return 'Unnamed Feature'
      const properties = feature.get('properties') || {}
      return properties.name || 'Unnamed Feature'
    },

    /**
     * Get feature stroke color and adjust if too light or dark
     * Returns RGB color string suitable for chart
     */
    getFeatureColor() {
      if (!this.feature) {
        return 'rgb(20, 184, 166)' // Default teal
      }

      const properties = this.feature.get('properties') || {}
      const strokeColor = properties.stroke || '#ff0000'

      // Convert hex to RGB
      let r, g, b
      if (strokeColor.startsWith('#')) {
        const hex = strokeColor.slice(1)
        r = parseInt(hex.substring(0, 2), 16)
        g = parseInt(hex.substring(2, 4), 16)
        b = parseInt(hex.substring(4, 6), 16)
      } else if (strokeColor.startsWith('rgb')) {
        // Extract RGB values from rgb() or rgba() string
        const matches = strokeColor.match(/\d+/g)
        if (matches && matches.length >= 3) {
          r = parseInt(matches[0])
          g = parseInt(matches[1])
          b = parseInt(matches[2])
        } else {
          return 'rgb(20, 184, 166)' // Default if parsing fails
        }
      } else {
        return 'rgb(20, 184, 166)' // Default if format unknown
      }

      // Calculate relative luminance (perceived brightness)
      // Using the formula: 0.299*R + 0.587*G + 0.114*B
      const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255

      // Adjust if too light (luminance > 0.8) or too dark (luminance < 0.2)
      if (luminance > 0.8) {
        // Too light - darken it
        const factor = 0.6 // Darken by 40%
        r = Math.max(0, Math.min(255, Math.round(r * factor)))
        g = Math.max(0, Math.min(255, Math.round(g * factor)))
        b = Math.max(0, Math.min(255, Math.round(b * factor)))
      } else if (luminance < 0.2) {
        // Too dark - lighten it
        const factor = 1.8 // Lighten by 80%
        r = Math.max(0, Math.min(255, Math.round(r * factor)))
        g = Math.max(0, Math.min(255, Math.round(g * factor)))
        b = Math.max(0, Math.min(255, Math.round(b * factor)))
      }

      return `rgb(${r}, ${g}, ${b})`
    },

    /**
     * Calculate distance between two coordinates using Haversine formula
     * Returns distance in meters
     */
    haversineDistance(lat1, lon1, lat2, lon2) {
      const R = 6371000 // Earth radius in meters
      const phi1 = (lat1 * Math.PI) / 180
      const phi2 = (lat2 * Math.PI) / 180
      const deltaPhi = ((lat2 - lat1) * Math.PI) / 180
      const deltaLambda = ((lon2 - lon1) * Math.PI) / 180

      const a =
        Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) +
        Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2)
      const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

      return R * c
    },

    /**
     * Extract coordinates from OpenLayers geometry
     * Returns array of [lon, lat, elevation] coordinates
     */
    extractCoordinates(geometry) {
      if (!geometry) return []

      const format = new GeoJSON()
      const geometryJson = format.writeGeometryObject(geometry, {
        featureProjection: 'EPSG:3857',
        dataProjection: 'EPSG:4326'
      })

      const geomType = geometryJson.type
      const coords = geometryJson.coordinates

      if (geomType === 'LineString') {
        // LineString: [[lon, lat, ele], ...]
        return coords || []
      } else if (geomType === 'MultiLineString') {
        // MultiLineString: [[[lon, lat, ele], ...], [[lon, lat, ele], ...], ...]
        // Merge all lines into one continuous array
        const merged = []
        if (Array.isArray(coords)) {
          for (const line of coords) {
            if (Array.isArray(line)) {
              merged.push(...line)
            }
          }
        }
        return merged
      }

      return []
    },

    /**
     * Process elevation data and calculate cumulative distances
     * Returns { distances: [], elevations: [], coordinateMapping: [] } where distances are in miles and elevations are in feet
     * coordinateMapping maps chart data indices to original coordinates [lon, lat]
     */
    processElevationData(coordinates) {
      const distances = [0] // Start at 0 miles
      const elevations = []
      const coordinateMapping = [] // Maps chart index to [lon, lat]
      let cumulativeDistance = 0 // in meters

      // Filter out points without elevation data and process
      const validPoints = []
      for (const coord of coordinates) {
        if (Array.isArray(coord) && coord.length >= 3) {
          const elevation = coord[2]
          // Check if elevation exists and is not 0 (0 might be a placeholder)
          if (elevation !== null && elevation !== undefined && elevation !== 0) {
            validPoints.push(coord)
          }
        }
      }

      if (validPoints.length === 0) {
        return { distances: [], elevations: [], coordinateMapping: [] }
      }

      // Add first point - convert elevation from meters to feet (1 meter = 3.28084 feet)
      elevations.push(validPoints[0][2] * 3.28084)
      coordinateMapping.push([validPoints[0][0], validPoints[0][1]]) // [lon, lat]

      // Process remaining points
      for (let i = 1; i < validPoints.length; i++) {
        const prevCoord = validPoints[i - 1]
        const currCoord = validPoints[i]

        // Calculate distance between consecutive points
        const distanceMeters = this.haversineDistance(
          prevCoord[1], // lat1
          prevCoord[0], // lon1
          currCoord[1], // lat2
          currCoord[0]  // lon2
        )

        // Add to cumulative distance
        cumulativeDistance += distanceMeters

        // Convert to miles (1 meter = 0.000621371 miles)
        const distanceMiles = cumulativeDistance * 0.000621371

        distances.push(distanceMiles)
        // Convert elevation from meters to feet
        elevations.push(currCoord[2] * 3.28084)
        // Store coordinate mapping
        coordinateMapping.push([currCoord[0], currCoord[1]]) // [lon, lat]
      }

      return { distances, elevations, coordinateMapping }
    },

    /**
     * Map chart distance (in miles) to corresponding coordinate on the line
     * Returns [lon, lat] or null if not found
     */
    mapDistanceToCoordinate(targetDistanceMiles) {
      if (!this.distances || !this.coordinateMapping || this.distances.length === 0) {
        return null
      }

      // Find the segment that contains this distance
      for (let i = 0; i < this.distances.length - 1; i++) {
        const dist1 = this.distances[i]
        const dist2 = this.distances[i + 1]

        if (targetDistanceMiles >= dist1 && targetDistanceMiles <= dist2) {
          // Interpolate between the two points
          const ratio = (targetDistanceMiles - dist1) / (dist2 - dist1)
          const coord1 = this.coordinateMapping[i]
          const coord2 = this.coordinateMapping[i + 1]

          // Linear interpolation
          const lon = coord1[0] + (coord2[0] - coord1[0]) * ratio
          const lat = coord1[1] + (coord2[1] - coord1[1]) * ratio

          return [lon, lat]
        }
      }

      // If beyond the last point, return the last coordinate
      if (targetDistanceMiles >= this.distances[this.distances.length - 1]) {
        return this.coordinateMapping[this.coordinateMapping.length - 1]
      }

      // If before the first point, return the first coordinate
      if (targetDistanceMiles <= this.distances[0]) {
        return this.coordinateMapping[0]
      }

      return null
    },

    /**
     * Smooth elevation data using a moving average to reduce GPS noise
     * This is commonly used in GPS software to get more accurate elevation gain/loss
     */
    smoothElevationData(elevations, windowSize = 10) {
      if (elevations.length === 0) return []
      if (elevations.length <= windowSize) return elevations

      const smoothed = []
      for (let i = 0; i < elevations.length; i++) {
        const start = Math.max(0, i - Math.floor(windowSize / 2))
        const end = Math.min(elevations.length, i + Math.ceil(windowSize / 2))
        const window = elevations.slice(start, end)
        const avg = window.reduce((a, b) => a + b, 0) / window.length
        smoothed.push(avg)
      }
      return smoothed
    },

    /**
     * Calculate statistics from elevation data
     * Returns stats object with formatted values
     */
    calculateStats(distances, elevations) {
      if (distances.length === 0 || elevations.length === 0) {
        return null
      }

      // Total distance (last distance value)
      const totalDistanceMiles = distances[distances.length - 1]
      const totalDistance = totalDistanceMiles >= 1
        ? `${totalDistanceMiles.toFixed(2)} mi`
        : `${(totalDistanceMiles * 5280).toFixed(0)} ft`

      // Total elevation change (end - start) - use original elevations
      const totalElevationChange = elevations[elevations.length - 1] - elevations[0]
      const totalElevationChangeFormatted = totalElevationChange >= 0
        ? `+${totalElevationChange.toFixed(0)} ft`
        : `${totalElevationChange.toFixed(0)} ft`

      // Elevation range (max - min) - use original elevations
      const minElevation = Math.min(...elevations)
      const maxElevation = Math.max(...elevations)
      const elevationRange = `${(maxElevation - minElevation).toFixed(0)} ft`

      // Gross elevation change (sum of all positive and negative changes)
      // Use smoothed elevation data to filter out GPS noise
      const smoothedElevations = this.smoothElevationData(elevations)
      let grossAscent = 0
      let grossDescent = 0

      for (let i = 1; i < smoothedElevations.length; i++) {
        const change = smoothedElevations[i] - smoothedElevations[i - 1]
        // Filter out very small changes (GPS noise)
        if (Math.abs(change) >= 0.1) {
          if (change > 0) {
            grossAscent += change
          } else {
            grossDescent += Math.abs(change)
          }
        }
      }

      return {
        totalDistance,
        totalElevationChange: totalElevationChangeFormatted,
        elevationRange,
        grossAscent: `${grossAscent.toFixed(0)} ft`,
        grossDescent: `${grossDescent.toFixed(0)} ft`
      }
    },

    /**
     * Fetch elevations from API for a feature
     */
    async fetchElevationsFromAPI(featureId) {
      try {
        const response = await axios.get(`/api/data/feature/${featureId}/elevations/`, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        })
        if (response.status === 200 && response.data.coordinates) {
          return response.data.coordinates
        }
        return null
      } catch (error) {
        console.error('Error fetching elevations from API:', error)
        return null
      }
    },

    /**
     * Update the chart with current feature data
     */
    async updateChart() {
      // Prevent concurrent chart updates
      if (this.isUpdatingChart) {
        return
      }

      this.isUpdatingChart = true

      // Destroy existing chart
      if (this.chart) {
        try {
          this.chart.destroy()
        } catch (e) {
          console.warn('Error destroying chart:', e)
        }
        this.chart = null
      }

      if (!this.feature) {
        this.hasElevationData = false
        this.stats = null
        this.isUpdatingChart = false
        return
      }

      const geometry = this.feature.getGeometry()
      if (!geometry) {
        this.hasElevationData = false
        this.stats = null
        this.isUpdatingChart = false
        return
      }

      // Extract coordinates
      let coordinates = this.extractCoordinates(geometry)
      if (coordinates.length === 0) {
        this.hasElevationData = false
        this.stats = null
        this.isUpdatingChart = false
        return
      }

      // If using API elevations, fetch them
      if (this.elevationProfileSource === 'api') {
        const featureId = this.feature.get('properties')?._id || this.feature.get('properties')?.id
        if (featureId) {
          const apiCoordinates = await this.fetchElevationsFromAPI(featureId)
          if (apiCoordinates && apiCoordinates.length > 0) {
            // Use API coordinates (they already have elevations)
            coordinates = apiCoordinates
          } else {
            // API fetch failed, fallback to GPS elevations
            console.warn('Failed to fetch elevations from API, falling back to GPS elevations')
            // coordinates already contains GPS elevations, continue with them
          }
        } else {
          // No feature ID available, use GPS elevations
          console.warn('No feature ID available, using GPS elevations')
        }
      }
      // If using GPS elevations, coordinates already contain them from extractCoordinates

      // Process elevation data
      const { distances, elevations, coordinateMapping } = this.processElevationData(coordinates)

      if (distances.length === 0 || elevations.length === 0) {
        this.hasElevationData = false
        this.stats = null
        this.coordinateMapping = null
        this.distances = null
        this.isUpdatingChart = false
        return
      }

      // Store coordinate mapping and distances for hover tracking
      this.coordinateMapping = coordinateMapping
      this.distances = distances

      // Calculate statistics
      this.stats = this.calculateStats(distances, elevations)
      this.hasElevationData = true

      // Wait for next tick to ensure canvas is rendered
      const renderStartTime = Date.now()
      this.$nextTick(() => {
        if (!this.$refs.chartCanvas) {
          this.isUpdatingChart = false
          return
        }

        const canvas = this.$refs.chartCanvas
        const ctx = canvas.getContext('2d')
        if (!ctx) {
          this.isUpdatingChart = false
          return
        }

        // Check if Chart.js already has a chart instance on this canvas
        const existingChart = Chart.getChart(canvas)
        if (existingChart) {
          try {
            existingChart.destroy()
          } catch (e) {
            console.warn('Error destroying existing chart from canvas:', e)
          }
        }

        // Get feature color
        const featureColor = this.getFeatureColor()
        // Extract RGB values for rgba background
        const rgbMatch = featureColor.match(/\d+/g)
        const bgColor = rgbMatch && rgbMatch.length >= 3
          ? `rgba(${rgbMatch[0]}, ${rgbMatch[1]}, ${rgbMatch[2]}, 0.2)`
          : 'rgba(20, 184, 166, 0.2)'

        // Find min and max elevation indices
        let minIndex = 0
        let maxIndex = 0
        let minElevation = elevations[0]
        let maxElevation = elevations[0]

        for (let i = 1; i < elevations.length; i++) {
          if (elevations[i] < minElevation) {
            minElevation = elevations[i]
            minIndex = i
          }
          if (elevations[i] > maxElevation) {
            maxElevation = elevations[i]
            maxIndex = i
          }
        }

        // Create datasets for min/max markers - use {x, y} format
        const minMarkerChartData = elevations.map((elev, idx) => idx === minIndex ? {
          x: distances[idx],
          y: elev
        } : null).filter(d => d !== null)
        const maxMarkerChartData = elevations.map((elev, idx) => idx === maxIndex ? {
          x: distances[idx],
          y: elev
        } : null).filter(d => d !== null)

        // Create custom plugin to draw markers on top
        // Store min/max indices and distances for marker positioning
        const minDistance = distances[minIndex]
        const maxDistance = distances[maxIndex]
        const markerPlugin = {
          id: 'markerPlugin',
          beforeTooltipDraw: (chart) => {
            const ctx = chart.ctx

            // Optimize: Get metadata once and check validity before drawing
            const minPointMeta = chart.getDatasetMeta(1)
            const maxPointMeta = chart.getDatasetMeta(2)

            // Draw min marker - optimized checks
            if (minPointMeta?.data?.length > 0) {
              const minPoint = minPointMeta.data[0]
              if (minPoint && typeof minPoint.x === 'number' && typeof minPoint.y === 'number') {
                ctx.save()
                ctx.beginPath()
                ctx.arc(minPoint.x, minPoint.y, 6, 0, 2 * Math.PI)
                ctx.fillStyle = '#ef4444'
                ctx.fill()
                ctx.strokeStyle = '#000000'
                ctx.lineWidth = 2
                ctx.stroke()
                ctx.restore()
              }
            }

            // Draw max marker - optimized checks
            if (maxPointMeta?.data?.length > 0) {
              const maxPoint = maxPointMeta.data[0]
              if (maxPoint && typeof maxPoint.x === 'number' && typeof maxPoint.y === 'number') {
                ctx.save()
                ctx.beginPath()
                ctx.arc(maxPoint.x, maxPoint.y, 6, 0, 2 * Math.PI)
                ctx.fillStyle = '#10b981'
                ctx.fill()
                ctx.strokeStyle = '#000000'
                ctx.lineWidth = 2
                ctx.stroke()
                ctx.restore()
              }
            }
          }
        }

        // Create render completion plugin to detect when chart is fully rendered
        const component = this
        let spinnerHidden = false
        const renderCompletePlugin = {
          id: 'renderCompletePlugin',
          afterDraw: (chart) => {
            // Hide spinner after chart is drawn (only once)
            if (!spinnerHidden) {
              spinnerHidden = true
              const elapsed = Date.now() - renderStartTime
              const minDisplayTime = 300 // Minimum 300ms display time
              const remainingTime = Math.max(0, minDisplayTime - elapsed)

              // Wait for browser to paint the chart (multiple frames for reliability)
              requestAnimationFrame(() => {
                requestAnimationFrame(() => {
                  requestAnimationFrame(() => {
                    // Ensure minimum display time, then hide spinner
                    setTimeout(() => {
                      // Verify canvas has content before hiding
                      const canvas = chart.canvas
                      if (canvas && canvas.width > 0 && canvas.height > 0) {
                        component.isUpdatingChart = false
                      } else {
                        // Canvas not ready, wait a bit more
                        setTimeout(() => {
                          component.isUpdatingChart = false
                        }, 100)
                      }
                    }, remainingTime)
                  })
                })
              })
            }
          }
        }

        // Create hover and click tracking plugin
        // Store reference to component instance for use in plugin
        let lastHoverCoordinate = null
        const hoverPlugin = {
          id: 'hoverPlugin',
          afterEvent: (chart, args) => {
            // Early return if no event or chart area
            if (!args?.event || !chart?.chartArea) return

            const event = args.event.native
            if (!event) return

            // Early return for non-mouse events (skip touch, etc.)
            if (event.type !== 'mousemove' && event.type !== 'click' && event.type !== 'mouseout') return

            const chartArea = chart.chartArea

            // Get mouse position relative to chart (cache rect for performance)
            const rect = chart.canvas.getBoundingClientRect()
            const x = event.clientX - rect.left
            const y = event.clientY - rect.top

            // Check if mouse is within chart area
            if (x < chartArea.left || x > chartArea.right ||
                y < chartArea.top || y > chartArea.bottom) {
              // Mouse left chart area - only emit if we had a previous coordinate
              if (lastHoverCoordinate) {
                component.$emit('hover-clear')
                lastHoverCoordinate = null
              }
              return
            }

            // Get the x-scale to convert pixel position to distance value
            const xScale = chart.scales.x
            if (!xScale) {
              if (lastHoverCoordinate) {
                component.$emit('hover-clear')
                lastHoverCoordinate = null
              }
              return
            }

            // Convert pixel X to distance value (miles)
            const distanceMiles = xScale.getValueForPixel(x)

            // Check if we got a valid distance value
            if (distanceMiles === null || distanceMiles === undefined || isNaN(distanceMiles)) {
              if (lastHoverCoordinate) {
                component.$emit('hover-clear')
                lastHoverCoordinate = null
              }
              return
            }

            // Map distance to coordinate
            const coordinate = component.mapDistanceToCoordinate(distanceMiles)
            if (coordinate && Array.isArray(coordinate) && coordinate.length >= 2) {
              // Only emit if coordinate changed (avoid unnecessary emissions)
              const coordKey = `${coordinate[0].toFixed(6)},${coordinate[1].toFixed(6)}`
              const lastKey = lastHoverCoordinate ? `${lastHoverCoordinate[0].toFixed(6)},${lastHoverCoordinate[1].toFixed(6)}` : null

              if (coordKey !== lastKey) {
                component.$emit('hover-point', coordinate)
                lastHoverCoordinate = coordinate
              }

              // Handle click events (always emit clicks)
              if (event.type === 'click') {
                component.$emit('click-point', coordinate)
              }
            } else {
              if (lastHoverCoordinate) {
                component.$emit('hover-clear')
                lastHoverCoordinate = null
              }
            }
          }
        }

        // Create chart data with x,y coordinates for proper numeric scaling
        const chartData = elevations.map((elev, idx) => ({
          x: distances[idx],
          y: elev
        }))

        // Optimize fill for large datasets - disable for very large datasets to improve performance
        const shouldFill = chartData.length < 10000 // Disable fill for datasets with >10k points

        // Create chart
        try {
          this.chart = new Chart(ctx, {
          type: 'line',
          plugins: [markerPlugin, hoverPlugin, renderCompletePlugin],
          data: {
            datasets: [
              {
                label: 'Elevation (ft)',
                data: chartData,
                borderColor: featureColor, // Use feature's stroke color (adjusted if needed)
                backgroundColor: bgColor, // Semi-transparent version of feature color
                fill: shouldFill ? 'origin' : false, // Use 'origin' for better performance, disable for large datasets
                tension: 0, // No smoothing - raw line
                pointRadius: 0, // Hide points by default for cleaner look
                pointHoverRadius: 4
              },
              {
                label: 'Min Elevation',
                data: minMarkerChartData,
                borderColor: 'transparent',
                backgroundColor: 'transparent',
                pointRadius: 0, // Hide the default points, we'll draw them in plugin
                showLine: false
              },
              {
                label: 'Max Elevation',
                data: maxMarkerChartData,
                borderColor: 'transparent',
                backgroundColor: 'transparent',
                pointRadius: 0, // Hide the default points, we'll draw them in plugin
                showLine: false
              }
            ]
          },
          options: {
            responsive: true,
            maintainAspectRatio: false,
            resizeDelay: 0, // Disable resize delay for immediate updates
            devicePixelRatio: Math.min(window.devicePixelRatio || 1, 2), // Limit to 2x for performance
            parsing: false, // Data is already in correct format, skip parsing
            layout: {
              padding: {
                top: 15,
                right: 15,
                bottom: 15,
                left: 15
              }
            },
            elements: {
              line: {
                borderJoinStyle: 'round' // Better performance than 'miter'
              }
            },
            plugins: {
              legend: {
                display: false
              },
              tooltip: {
                mode: 'index',
                intersect: false,
                position: 'nearest',
                animation: false,
                filter: (tooltipItem) => {
                  // Only show tooltip for the main elevation dataset, not the min/max markers
                  return tooltipItem.datasetIndex === 0
                },
                callbacks: {
                  title: (items) => {
                    return `Distance: ${items[0].parsed.x.toFixed(2)} mi`
                  },
                  label: (context) => {
                    return `Elevation: ${context.parsed.y.toFixed(0)} ft`
                  }
                }
              },
              animation: {
                duration: 0
              }
            },
            scales: {
              x: {
                type: 'linear',
                min: distances[0],
                max: distances[distances.length - 1],
                title: {
                  display: false
                },
                ticks: {
                  display: false
                },
                grid: {
                  display: true
                },
                padding: {
                  left: 15,
                  right: 15
                }
              },
              y: {
                title: {
                  display: false
                },
                ticks: {
                  display: false
                },
                grid: {
                  display: true
                },
                padding: {
                  top: 15,
                  bottom: 15
                }
              }
            },
            interaction: {
              mode: 'index',
              intersect: false
            }
          }
        })
        } catch (e) {
          console.error('Error creating chart:', e)
          this.hasElevationData = false
          this.isUpdatingChart = false
        }
      })
    }
  }
}
</script>

<style scoped>
/* Ensure chart container takes full height */
canvas {
  max-height: 100%;
}
</style>

