<template>
  <div v-if="feature" class="fixed bottom-0 left-0 right-0 w-full bg-white z-30 rounded-t-xl shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.1)] border-t border-gray-200 flex flex-col max-h-[80vh] md:absolute md:bottom-16 md:left-0 md:right-0 md:h-1/3 md:max-h-none md:rounded-none md:shadow-none md:z-20 lg:bottom-0 lg:left-0 lg:right-0">
    <div class="flex flex-col h-full min-h-0">
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
          class="text-gray-400 hover:text-gray-600 transition-colors p-2 sm:p-1 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0"
          title="Close Elevation Profile"
        >
          <XMarkIcon class="w-5 h-5" />
        </button>
      </div>

      <!-- Stats -->
      <div class="stats-container px-3 py-1.5 md:px-4 md:py-2 border-b border-gray-200 bg-gray-50 flex-none min-h-[28px] relative">
        <template v-if="hasElevationData && stats">
          <!-- Mobile layout: flex row with button on right -->
          <div class="sm:hidden flex items-center justify-between w-full gap-x-3 text-[10px]">
            <div class="flex items-center gap-x-3 flex-1">
              <div v-for="stat in firstRowStats" :key="stat.label" class="flex items-center">
                <span class="text-gray-600 mr-1">{{ stat.label }}:</span>
                <span class="font-medium text-gray-900">{{ stat.value }}</span>
              </div>
            </div>
            <!-- More button (only on mobile when there are remaining stats) -->
            <button
              v-if="hasRemainingStats"
              @click.stop="toggleDropdown"
              class="flex items-center justify-center text-gray-600 hover:text-gray-900 transition-colors flex-shrink-0 p-2 -mr-2 -my-1 min-w-[44px] min-h-[44px]"
              :class="{ 'text-gray-900': showMoreStats }"
            >
              <EllipsisHorizontalIcon class="w-4 h-4" />
            </button>
          </div>
          <!-- Desktop layout: grid -->
          <div class="hidden sm:grid grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-x-3 gap-y-1 text-[10px] md:text-xs w-full">
            <div v-for="stat in allStatItems" :key="stat.label" class="flex items-center">
              <span class="text-gray-600 mr-1">{{ stat.label }}:</span>
              <span class="font-medium text-gray-900">{{ stat.value }}</span>
            </div>
          </div>
        </template>
        <template v-else-if="isUpdatingChart">
          <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-x-3 gap-y-1 text-[10px] md:text-xs w-full animate-pulse">
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
          <!-- Min -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Max -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Avg -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          </div>
        </template>
        <template v-else>
          <div class="text-[10px] md:text-xs text-gray-400 italic">
            No stats available
          </div>
        </template>
        <!-- Mobile dropdown for remaining stats -->
        <div
          v-if="hasRemainingStats && hasElevationData && stats"
          class="stats-dropdown sm:hidden absolute top-full left-0 right-0 bg-white border-t border-gray-200 shadow-lg z-50 max-h-48 overflow-y-auto transition-all duration-200 ease-out"
          :class="showMoreStats ? 'opacity-100 translate-y-0' : 'opacity-0 -translate-y-2 pointer-events-none'"
          @click.stop
        >
          <div class="px-3 py-2 space-y-2">
            <div v-for="stat in remainingStats" :key="stat.label" class="flex items-center justify-between text-[10px]">
              <span class="text-gray-600 mr-2">{{ stat.label }}:</span>
              <span class="font-medium text-gray-900">{{ stat.value }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Chart Container, Loading Spinner, or Warning -->
      <div class="flex-1 min-h-0 overflow-y-auto overflow-x-hidden relative bg-white">
        <!-- Chart Container -->
        <div v-if="hasElevationData" ref="chartContainer" class="chart-container w-full relative">
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

      <!-- Label text for small screens - outside scrollable area -->
      <div v-if="hasElevationData" class="md:hidden text-center py-3 px-3 flex-none border-t border-gray-100">
        <p class="text-sm text-gray-500">Elevation Profile</p>
      </div>
    </div>
  </div>
</template>

<script>
import axios from 'axios'
import { Chart, registerables } from 'chart.js'
import { getCookie } from '@/assets/js/auth.js'
import Loader from '@/components/parts/Loader.vue'
import { XMarkIcon, ExclamationTriangleIcon, EllipsisHorizontalIcon } from '@heroicons/vue/24/outline'
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
} from '@/utils/map/elevationProfileUtils'

Chart.register(...registerables)

export default {
  name: 'ElevationProfileDialog',
  props: {
    feature: {
      type: Object,
      default: null
    },
    shareId: {
      type: String,
      default: null
    },
    isPublicShare: {
      type: Boolean,
      default: false
    }
  },
  emits: ['close', 'hover-point', 'hover-clear', 'click-point'],
  components: {
    Loader,
    XMarkIcon,
    ExclamationTriangleIcon,
    EllipsisHorizontalIcon
  },
  data() {
    return {
      chart: null,
      hasElevationData: false,
      isUpdatingChart: false,
      stats: null,
      coordinateMapping: null, // Maps chart data indices to original coordinates [lon, lat]
      distances: null, // Store distances array for mapping
      showMoreStats: false, // Track if mobile dropdown is open
      toggleDebounceTimer: null // Track debounce timer
    }
  },
  computed: {
    elevationProfileSource() {
      // Get elevation profile source from store, default to 'gps'
      const settings = this.$store.state.userSettings
      return settings?.map?.elevation_profile_source || 'gps'
    },
    /**
     * Get all stat items as an array for easier manipulation
     */
    allStatItems() {
      if (!this.stats) return []
      const items = [
        { label: 'Dist', value: this.stats.totalDistance },
        { label: 'Elev. Change', value: this.stats.totalElevationChange },
        { label: 'Asc', value: this.stats.grossAscent },
        { label: 'Des', value: this.stats.grossDescent },
        { label: 'Min Elv', value: this.stats.minElevation },
        { label: 'Max Elv', value: this.stats.maxElevation },
        { label: 'Avg. Elv', value: this.stats.averageElevation }
      ]
      // Add track-specific stats (only for tracks with timestamps)
      if (this.stats.totalTrackTime) {
        items.push({ label: 'Total Time', value: this.stats.totalTrackTime })
      }
      if (this.stats.totalMovingTime) {
        items.push({ label: 'Moving Time', value: this.stats.totalMovingTime })
      }
      if (this.stats.averageMovingSpeed) {
        items.push({ label: 'Avg. Moving Speed', value: this.stats.averageMovingSpeed })
      }
      return items
    },
    /**
     * First row stats (first 2 items on mobile)
     */
    firstRowStats() {
      return this.allStatItems.slice(0, 2)
    },
    /**
     * Remaining stats (beyond first row)
     */
    remainingStats() {
      return this.allStatItems.slice(2)
    },
    /**
     * Whether there are remaining stats to show
     */
    hasRemainingStats() {
      return this.remainingStats.length > 0
    },
  },
  watch: {
    feature: {
      handler() {
        // Reset dropdown state when feature changes
        this.showMoreStats = false
        if (this.toggleDebounceTimer) {
          clearTimeout(this.toggleDebounceTimer)
          this.toggleDebounceTimer = null
        }
        this.$nextTick(() => {
          this.updateChart()
        })
      },
      immediate: true
    },
    $route() {
      // Close dialog when route changes
      if (this.feature) {
        this.$emit('close')
      }
    }
  },
  mounted() {
    this.updateChart()
    // Add keyboard event listener for Escape key
    document.addEventListener('keydown', this.handleKeyDown)
    // Add click outside listener to close dropdown
    document.addEventListener('click', this.handleClickOutside)
  },
  beforeUnmount() {
    // Clear debounce timer
    if (this.toggleDebounceTimer) {
      clearTimeout(this.toggleDebounceTimer)
      this.toggleDebounceTimer = null
    }
    // Remove keyboard event listener
    document.removeEventListener('keydown', this.handleKeyDown)
    // Remove click outside listener
    document.removeEventListener('click', this.handleClickOutside)

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
        if (this.showMoreStats) {
          this.showMoreStats = false
        } else {
          this.$emit('close')
        }
      }
    },

    /**
     * Handle clicks outside the dropdown to close it
     */
    handleClickOutside(event) {
      if (this.showMoreStats) {
        // Check if click is outside the stats container and dropdown
        const statsContainer = event.target.closest('.stats-container')
        const dropdown = event.target.closest('.stats-dropdown')
        if (!statsContainer && !dropdown) {
          this.showMoreStats = false
        }
      }
    },

    /**
     * Debounced toggle for dropdown
     */
    toggleDropdown() {
      // Clear any existing debounce timer
      if (this.toggleDebounceTimer) {
        clearTimeout(this.toggleDebounceTimer)
      }
      
      // Set new debounce timer
      this.toggleDebounceTimer = setTimeout(() => {
        this.showMoreStats = !this.showMoreStats
        this.toggleDebounceTimer = null
      }, 150) // 150ms debounce
    },

    /**
     * Get feature name from properties
     * Returns feature name or 'Unnamed Feature' as fallback
     */
    getFeatureName(feature) {
      if (!feature) return 'Unnamed Feature'
      const properties = feature.properties || {}
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

      const properties = this.feature.properties || {}
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
     * Format a number with commas and no decimal places
     * @param {number} value - Number to format
     * @returns {string} Formatted number with commas
     */
    formatElevationNumber(value) {
      return Math.round(value).toLocaleString('en-US')
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
        ? `+${this.formatElevationNumber(totalElevationChange)} ${elevUnit}`
        : `${this.formatElevationNumber(totalElevationChange)} ${elevUnit}`

      // Elevation range (max - min) - use original elevations
      const minElevation = Math.min(...elevations)
      const maxElevation = Math.max(...elevations)
      const elevationRange = `${this.formatElevationNumber(maxElevation - minElevation)} ${elevUnit}`
      
      // Average elevation - use original elevations
      const averageElevation = elevations.reduce((sum, elev) => sum + elev, 0) / elevations.length

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
        grossAscent: `+${this.formatElevationNumber(grossAscent)} ${elevUnit}`,
        grossDescent: `-${this.formatElevationNumber(grossDescent)} ${elevUnit}`,
        minElevation: `${this.formatElevationNumber(minElevation)} ${elevUnit}`,
        maxElevation: `${this.formatElevationNumber(maxElevation)} ${elevUnit}`,
        averageElevation: `${this.formatElevationNumber(averageElevation)} ${elevUnit}`
      }

      // Calculate speed stats if timestamps and distances in meters are available
      // Note: Timestamps are only available for GPX tracks/routes with time data.
      // Manually drawn features or features without time data won't have speed stats.
      if (timestamps && timestamps.length >= 2 && distancesMeters && distancesMeters.length >= 2) {
        const speeds = calculateSpeeds(distancesMeters, timestamps)
        const speedStats = calculateSpeedStats(speeds, distancesMeters, timestamps)
        if (speedStats) {
          stats.averageSpeed = speedStats.averageSpeed
          stats.averageMovingSpeed = speedStats.averageMovingSpeed
          stats.totalMovingTime = speedStats.totalMovingTime
          stats.totalTrackTime = speedStats.totalTrackTime
        }
      }

      return stats
    },

    /**
     * Fetch elevations from API for a feature
     * @param {string} featureId - The feature database ID or hash
     * @param {string} source - Either 'external' for external elevation API or 'internal' for GPS elevations
     */
    async fetchElevationsFromAPI(featureId, source = 'external') {
      try {
        let endpoint
        if (this.isPublicShare && this.shareId) {
          // For public shares, only use internal elevations (GPS data stored in feature)
          // External elevation API requires authentication
          if (source === 'external') {
            // Public shares can't use external elevation API, return null
            return null
          }
          endpoint = `/api/sharing/public/feature/${this.shareId}/elevations/internal/`
        } else {
          // Use authenticated endpoints for regular features
          endpoint = source === 'external' 
            ? `/api/feature/${featureId}/elevations/external/`
            : `/api/feature/${featureId}/elevations/internal/`
        }
          
        const response = await axios.get(endpoint, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        })
        if (response.status === 200 && response.data.coordinates) {
          return response.data.coordinates
        }
        return null
      } catch (error) {
        console.error(`Error fetching elevations from API (${source}):`, error)
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

      const geometry = this.feature.geometry
      if (!geometry) {
        this.hasElevationData = false
        this.stats = null
        this.isUpdatingChart = false
        return
      }

      // For LineString and MultiLineString features, MapLibre simplifies the geometry
      // for rendering performance, which loses most coordinates. We need the full
      // coordinates for accurate elevation profiles and track statistics.
      // Always fetch from API if we have a feature ID.
      
      const featureId = this.feature.properties?.database_id || this.feature.properties?.geojson_hash
      let coordinates = null
      
      // Determine elevation source and fetch coordinates with elevation data
      if (this.elevationProfileSource === 'api') {
        // === EXTERNAL ELEVATION SOURCE ===
        // User explicitly wants elevations from the external elevation API
        
        if (!featureId) {
          console.warn('Cannot fetch external elevations: no feature ID available')
          this.hasElevationData = false
          this.stats = null
          this.isUpdatingChart = false
          return
        }

        const apiCoordinates = await this.fetchElevationsFromAPI(featureId, 'external')
        if (apiCoordinates && apiCoordinates.length > 0) {
          coordinates = apiCoordinates
        } else {
          console.warn('Failed to fetch elevations from external API')
          this.hasElevationData = false
          this.stats = null
          this.isUpdatingChart = false
          return
        }
      } else {
        // === GPS ELEVATION SOURCE ===
        // User wants GPS elevations from the original imported data
        
        // For LineString/MultiLineString, always fetch full coordinates from API
        // because MapLibre simplifies the geometry (e.g., 2807 points -> 16 points)
        if (featureId && (geometry.type === 'LineString' || geometry.type === 'MultiLineString')) {
          const apiCoordinates = await this.fetchElevationsFromAPI(featureId, 'internal')
          if (apiCoordinates && apiCoordinates.length > 0) {
            coordinates = apiCoordinates
          } else {
            console.warn('GPS elevations unavailable from API')
            this.hasElevationData = false
            this.stats = null
            this.isUpdatingChart = false
            return
          }
        } else {
          // For Point features or if no feature ID, use geometry coordinates
          coordinates = extractCoordinates(geometry)
          
          if (coordinates.length === 0) {
            this.hasElevationData = false
            this.stats = null
            this.isUpdatingChart = false
            return
          }
          
          // Check if coordinates have elevation data (3rd element)
          const hasElevationInCoords = coordinates.length > 0 && coordinates[0].length >= 3
          
          if (!hasElevationInCoords) {
            console.warn('No elevation data in geometry coordinates')
            this.hasElevationData = false
            this.stats = null
            this.isUpdatingChart = false
            return
          }
        }
      }

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
                    const elevUnit = getElevationUnitLabel()
                    const formattedElevation = Math.round(context.parsed.y).toLocaleString('en-US')
                    return `Elevation: ${formattedElevation} ${elevUnit}`
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
/* Chart container styling - fit to available space */
.chart-container {
  height: 100%;
}

canvas {
  display: block;
}
</style>

