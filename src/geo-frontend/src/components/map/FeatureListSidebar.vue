<template>
  <div class="w-80 bg-white border-r border-gray-200 p-1.5 flex flex-col">
    <!-- Search Bar -->
    <div class="mb-2 px-1">
      <div class="relative">
        <input
          v-model="searchQuery"
          @input="handleSearchInput"
          type="text"
          placeholder="Search features..."
          class="w-full px-2 py-1.5 pr-7 text-xs border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
        />
        <button
          v-if="searchQuery"
          @click="clearSearch"
          class="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 focus:outline-none"
          type="button"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
          </svg>
        </button>
      </div>
    </div>

    <!-- Header -->
    <h2 class="text-xs font-semibold text-gray-900 mb-1 px-1">
      {{ isSearchMode ? 'Search Results' : 'Features in View' }}
    </h2>
    
    <!-- Loading Indicator -->
    <div v-if="isSearching" class="flex-1 flex items-center justify-center">
      <div class="flex flex-col items-center space-y-2">
        <div class="animate-spin rounded-full h-6 w-6 border-b-2 border-blue-600"></div>
        <div class="text-xs text-gray-500">Searching...</div>
      </div>
    </div>
    
    <!-- Feature List -->
    <div v-else class="flex-1 overflow-y-auto select-none">
      <div v-if="displayFeatures.length === 0" class="text-xs text-gray-500 text-center py-3">
        {{ isSearchMode ? 'No results found' : 'No features' }}
      </div>
      <div v-else class="space-y-0.5">
        <div
          v-for="feature in displayFeatures"
          :key="getFeatureId(feature)"
          @click="handleFeatureClick(feature)"
          class="px-1.5 py-1 bg-gray-50 hover:bg-gray-100 transition-colors flex items-center cursor-pointer"
          :style="{ borderLeft: `3px solid ${getGeometryTypeColor(feature)}` }"
        >
          <div class="text-xs text-gray-900 truncate">
            {{ getFeatureName(feature) }}
          </div>
        </div>
      </div>
    </div>
    
    <!-- Footer Count -->
    <div class="mt-1 text-xs text-gray-500 border-t border-gray-200 pt-1 px-1">
      {{ displayFeatures.length }}
    </div>
  </div>
</template>

<script>
import {GeoJSON} from 'ol/format'
import {APIHOST} from '@/config.js'

export default {
  name: 'FeatureListSidebar',
  props: {
    features: {
      type: Array,
      required: false,
      default: () => []
    }
  },
  emits: ['feature-click'],
  data() {
    return {
      searchQuery: '',
      searchResults: [],
      isSearching: false,
      searchTimeout: null,
      API_BASE_URL: '/api/data/features/search/'
    }
  },
  computed: {
    isSearchMode() {
      return this.searchQuery.trim().length > 0
    },
    displayFeatures() {
      return this.isSearchMode ? this.searchResults : this.features
    }
  },
  methods: {
    getFeatureId(feature) {
      if (!feature._geoJsonMapId) {
        feature._geoJsonMapId = `feature_${Date.now()}_${Math.random()}`
      }
      return feature._geoJsonMapId
    },
    getFeatureName(feature) {
      const properties = feature.get('properties') || {}
      return properties.name || 'Unnamed Feature'
    },
    getFeatureGeometryType(feature) {
      const geometry = feature.getGeometry()
      if (!geometry) return 'Unknown'
      return geometry.getType()
    },
    getGeometryTypeColor(feature) {
      const geometryType = this.getFeatureGeometryType(feature)
      
      const colors = {
        'Point': '#93c5fd',
        'MultiPoint': '#93c5fd',
        'LineString': '#86efac',
        'MultiLineString': '#86efac',
        'Polygon': '#fbbf24',
        'MultiPolygon': '#fbbf24'
      }
      
      return colors[geometryType] || '#d1d5db'
    },
    handleFeatureClick(feature) {
      this.$emit('feature-click', feature)
    },
    handleSearchInput() {
      // Clear existing timeout
      if (this.searchTimeout) {
        clearTimeout(this.searchTimeout)
      }
      
      const query = this.searchQuery.trim()
      
      // If query is empty, clear search immediately
      if (!query) {
        this.clearSearch()
        return
      }
      
      // Show loading spinner immediately while user is typing
      this.isSearching = true
      
      // Debounce search (300ms)
      this.searchTimeout = setTimeout(() => {
        this.performSearch(query)
      }, 300)
    },
    async performSearch(query) {
      if (!query) {
        this.clearSearch()
        return
      }
      
      this.isSearching = true
      
      try {
        const url = `${APIHOST}${this.API_BASE_URL}?query=${encodeURIComponent(query)}`
        const response = await fetch(url)
        const data = await response.json()
        
        if (data.success && data.data && data.data.features) {
          // Convert GeoJSON features to OpenLayers features
          const format = new GeoJSON()
          const features = format.readFeatures(data.data, {
            featureProjection: 'EPSG:3857',
            dataProjection: 'EPSG:4326'
          })
          
          // Preserve properties from original GeoJSON
          features.forEach((feature, index) => {
            const originalFeature = data.data.features[index]
            if (originalFeature && originalFeature.properties) {
              feature.set('properties', originalFeature.properties)
            }
            if (originalFeature && originalFeature.geojson_hash) {
              feature.set('geojson_hash', originalFeature.geojson_hash)
            }
          })
          
          // Sort features alphabetically by name
          features.sort((a, b) => {
            const nameA = this.getFeatureName(a).toLowerCase()
            const nameB = this.getFeatureName(b).toLowerCase()
            return nameA.localeCompare(nameB)
          })
          
          this.searchResults = features
        } else {
          console.error('Search failed:', data.error || 'Unknown error')
          this.searchResults = []
        }
      } catch (error) {
        console.error('Error searching features:', error)
        this.searchResults = []
      } finally {
        this.isSearching = false
      }
    },
    clearSearch() {
      this.searchQuery = ''
      this.searchResults = []
      this.isSearching = false
      if (this.searchTimeout) {
        clearTimeout(this.searchTimeout)
        this.searchTimeout = null
      }
    }
  },
  beforeUnmount() {
    // Clean up timeout
    if (this.searchTimeout) {
      clearTimeout(this.searchTimeout)
    }
  }
}
</script>

