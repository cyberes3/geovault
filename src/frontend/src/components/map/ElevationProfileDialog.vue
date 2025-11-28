<template>
  <div v-if="feature" class="fixed bottom-0 left-0 right-0 w-full bg-white z-30 rounded-t-xl shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.1)] border-t border-gray-200 flex flex-col md:absolute md:bottom-0 md:left-0 md:right-0 md:h-1/4 md:rounded-none md:shadow-none md:z-20">
    <div class="flex flex-col h-full">
      <!-- Header -->
      <div class="relative flex items-center justify-between px-3 py-2 md:px-4 md:py-3 border-b border-gray-200 bg-gray-50 md:bg-white rounded-t-xl md:rounded-none flex-none">
        <h3 class="text-sm md:text-lg font-semibold text-gray-900">
          <span class="md:hidden">{{ getFeatureName(feature) }}</span>
          <span class="hidden md:inline">Elevation Profile</span>
        </h3>
        <div class="hidden md:block absolute left-1/2 transform -translate-x-1/2">
          <span class="text-lg text-gray-900">{{ getFeatureName(feature) }}</span>
        </div>
        <button
          @click="$emit('close')"
          class="text-gray-400 hover:text-gray-600 transition-colors p-1"
          title="Close elevation profile"
        >
          <XMarkIcon class="w-5 h-5" />
        </button>
      </div>

      <!-- Stats -->
      <div class="px-3 py-1.5 md:px-4 md:py-2 border-b border-gray-200 bg-gray-50 flex-none min-h-[28px] flex items-center">
        <div v-if="hasElevationData && stats" class="flex flex-wrap gap-x-3 gap-y-1 text-[10px] md:text-xs justify-between md:justify-start w-full">
          <div class="flex items-center">
            <span class="text-gray-600 mr-1">Dist:</span>
            <span class="font-medium text-gray-900">{{ stats.totalDistance }}</span>
          </div>
          <div class="flex items-center">
            <span class="text-gray-600 mr-1">Change:</span>
            <span class="font-medium text-gray-900">{{ stats.totalElevationChange }}</span>
          </div>
          <div class="flex items-center">
             <span class="text-gray-600 mr-1">Asc:</span>
             <span class="font-medium text-gray-900">{{ stats.grossAscent }}</span>
          </div>
           <div class="flex items-center">
             <span class="text-gray-600 mr-1">Des:</span>
             <span class="font-medium text-gray-900">{{ stats.grossDescent }}</span>
          </div>
          <div class="hidden sm:flex items-center">
            <span class="text-gray-600 mr-1">Min:</span>
            <span class="font-medium text-gray-900">{{ stats.minElevation }}</span>
          </div>
          <div class="hidden sm:flex items-center">
            <span class="text-gray-600 mr-1">Max:</span>
            <span class="font-medium text-gray-900">{{ stats.maxElevation }}</span>
          </div>
          <!-- Speed stats (only show if available) -->
          <div v-if="stats.movingAverageSpeed" class="flex items-center">
            <span class="text-gray-600 mr-1">Mov Avg:</span>
            <span class="font-medium text-gray-900">{{ stats.movingAverageSpeed }}</span>
          </div>
          <div v-if="stats.totalMovingTime" class="flex items-center">
            <span class="text-gray-600 mr-1">Moving:</span>
            <span class="font-medium text-gray-900">{{ stats.totalMovingTime }}</span>
          </div>
          <div v-if="stats.totalTrackTime" class="flex items-center">
            <span class="text-gray-600 mr-1">Total:</span>
            <span class="font-medium text-gray-900">{{ stats.totalTrackTime }}</span>
          </div>
        </div>
        <div v-else-if="isUpdatingChart" class="flex flex-wrap gap-x-3 gap-y-1 text-[10px] md:text-xs justify-between md:justify-start w-full animate-pulse">
          <!-- Dist -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-10"></div>
          </div>
          <!-- Change -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-10 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Asc -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Des -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Min (Hidden on mobile) -->
          <div class="hidden sm:flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Max (Hidden on mobile) -->
          <div class="hidden sm:flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
        </div>
        <div v-else class="text-[10px] md:text-xs text-gray-400 italic">
          No stats available
        </div>
      </div>

      <!-- Chart Container, Loading Spinner, or Warning -->
      <div class="h-32 md:h-auto md:flex-1 overflow-hidden relative bg-white">
        <!-- Chart Container -->
        <div v-if="hasElevationData" ref="chartContainer" class="h-full w-full relative">
          <canvas ref="chartCanvas"></canvas>
          <!-- Loading Spinner Overlay -->
          <div v-if="isUpdatingChart" class="absolute inset-0 flex items-center justify-center z-10 pointer-events-none">
            <Loader size="sm" layout="centered" :showMessage="false" />
          </div>
        </div>
        <!-- Loading Spinner -->
        <div v-if="isUpdatingChart && feature" class="absolute inset-0 flex items-center justify-center bg-white z-20">
          <Loader size="sm" layout="centered" message="Loading..." />
        </div>
        <!-- No Data Warning -->
        <div v-else-if="!hasElevationData && feature" class="absolute inset-0 flex items-center justify-center bg-white z-10">
          <div class="text-center p-2">
            <ExclamationTriangleIcon class="w-8 h-8 md:w-12 md:h-12 text-yellow-500 mx-auto mb-2" />
            <p class="text-gray-700 font-medium text-xs md:text-base">No elevation data</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import axios from 'axios'
import { Chart, registerables } from 'chart.js'
import { getCookie } from '@/assets/js/auth.js'
import Loader from '@/components/parts/Loader.vue'
import { XMarkIcon, ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import { 
  getElevationMultiplier, 
  getDistanceMultiplier, 
  getElevationUnitLabel, 
  getDistanceUnitLabel,
  formatDistance,
  formatElevation,
  formatSpeed,
  getSpeedUnitLabel
} from '@/utils/units'
import {
  haversineDistance,
  extractCoordinates,
  extractTimestamps,
  processElevationData,
  mapDistanceToCoordinate,
  smoothElevationData,
  calculateSpeeds,
  calculateSpeedStats
} from '@/utils/map/utils/elevationProfileUtils'

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
  components: {
    Loader,
    XMarkIcon,
    ExclamationTriangleIcon
  },
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
     * Calculate statistics from elevation data
     * Returns stats object with formatted values
     * @param {Array} distances - Array of cumulative distances in user units
     * @param {Array} elevations - Array of elevations in user units
     * @param {Array} distancesMeters - Array of cumulative distances in meters (for speed calculations)
     * @param {Array} timestamps - Array of ISO timestamp strings (optional, for speed calculations)
     */
    calculateStats(distances, elevations, distancesMeters = null, timestamps = null) {
      if (distances.length === 0 || elevations.length === 0) {
        return null
      }

      const distUnit = getDistanceUnitLabel()
      const elevUnit = getElevationUnitLabel()
      const elevMultiplier = getElevationMultiplier()

      // Total distance (last distance value)
      const totalDistanceVal = distances[distances.length - 1]
      const totalDistance = `${totalDistanceVal.toFixed(2)} ${distUnit}`

      // Total elevation change (end - start) - use original elevations
      const totalElevationChange = elevations[elevations.length - 1] - elevations[0]
      const totalElevationChangeFormatted = totalElevationChange >= 0
        ? `+${totalElevationChange.toFixed(0)} ${elevUnit}`
        : `${totalElevationChange.toFixed(0)} ${elevUnit}`

      // Elevation range (max - min) - use original elevations
      const minElevation = Math.min(...elevations)
      const maxElevation = Math.max(...elevations)
      const elevationRange = `${(maxElevation - minElevation).toFixed(0)} ${elevUnit}`

      // Gross elevation change (sum of all positive and negative changes)
      // Use smoothed elevation data to filter out GPS noise
      const smoothedElevations = smoothElevationData(elevations)
      let grossAscent = 0
      let grossDescent = 0

      // Threshold for noise filtering (approx 0.1 ft or 0.03 m)
      const noiseThreshold = 0.1 * (elevUnit === 'ft' ? 1 : 0.3048)

      for (let i = 1; i < smoothedElevations.length; i++) {
        const change = smoothedElevations[i] - smoothedElevations[i - 1]
        // Filter out very small changes (GPS noise)
        if (Math.abs(change) >= noiseThreshold) {
          if (change > 0) {
            grossAscent += change
          } else {
            grossDescent += Math.abs(change)
          }
        }
      }

      const stats = {
        totalDistance,
        totalElevationChange: totalElevationChangeFormatted,
        elevationRange,
        grossAscent: `${grossAscent.toFixed(0)} ${elevUnit}`,
        grossDescent: `${grossDescent.toFixed(0)} ${elevUnit}`,
        minElevation: `${minElevation.toFixed(0)} ${elevUnit}`,
        maxElevation: `${maxElevation.toFixed(0)} ${elevUnit}`
      }

      // Calculate speed stats if timestamps and distances in meters are available
      // Note: Timestamps are only available for GPX tracks/routes with time data.
      // Manually drawn features or features without time data won't have speed stats.
      if (timestamps && timestamps.length >= 2 && distancesMeters && distancesMeters.length >= 2) {
        const speeds = calculateSpeeds(distancesMeters, timestamps)
        const speedStats = calculateSpeedStats(speeds, distancesMeters, timestamps)
        if (speedStats) {
          stats.movingAverageSpeed = speedStats.movingAverageSpeed
          stats.totalMovingTime = speedStats.totalMovingTime
          stats.totalTrackTime = speedStats.totalTrackTime
        }
      }

      return stats
    },

    /**
     * Fetch elevations from API for a feature
     */
    async fetchElevationsFromAPI(featureId) {
      try {
        const response = await axios.get(`/api/feature/${featureId}/elevations/`, {
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
      let coordinates = extractCoordinates(geometry)
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

      // Extract timestamps from coordinateProperties
      const timestamps = extractTimestamps(this.feature)

      // Process elevation data
      const { distances, distancesMeters, elevations, coordinateMapping, timestamps: validTimestamps } = processElevationData(coordinates, timestamps)

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

      // Calculate statistics (include speed stats if timestamps available)
      this.stats = this.calculateStats(distances, elevations, distancesMeters, validTimestamps)
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
          afterDatasetsDraw: (chart) => {
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
            const coordinate = mapDistanceToCoordinate(distanceMiles, component.distances, component.coordinateMapping)
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

