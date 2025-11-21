<template>
  <div class="w-80 bg-white border-r border-gray-200 p-1.5 flex flex-col">
    <!-- Tabs -->
    <div class="flex border-b border-gray-200 mb-2">
      <button
        @click="activeTab = 'features-in-view'"
        :class="[
          'px-2 py-1 text-xs font-medium transition-colors',
          activeTab === 'features-in-view'
            ? 'text-blue-600 border-b-2 border-blue-600'
            : 'text-gray-600 hover:text-gray-900'
        ]"
      >
        Features in View
      </button>
      <button
        @click="activeTab = 'tag-filter'"
        :class="[
          'px-2 py-1 text-xs font-medium transition-colors flex items-center gap-1',
          activeTab === 'tag-filter'
            ? 'text-blue-600 border-b-2 border-blue-600'
            : 'text-gray-600 hover:text-gray-900'
        ]"
      >
        Tag Filter
        <svg
          v-if="selectedTags.length > 0"
          class="w-3 h-3 text-blue-600"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z"
          />
        </svg>
      </button>
    </div>

    <!-- Features in View Tab Content -->
    <div v-if="activeTab === 'features-in-view'" class="flex flex-col flex-1">
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

    <!-- Tag Filter Tab Content -->
    <div v-if="activeTab === 'tag-filter'" class="flex flex-col flex-1">
      <!-- Tag Search Input -->
      <div class="mb-2 px-1">
        <div class="relative">
          <input
            v-model="tagSearchQuery"
            @input="handleTagSearchInput"
            type="text"
            placeholder="Search tags..."
            class="w-full px-2 py-1.5 pr-7 text-xs border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
          <button
            v-if="tagSearchQuery"
            @click="clearTagSearch"
            class="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 focus:outline-none"
            type="button"
          >
            <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
            </svg>
          </button>
        </div>
      </div>

      <!-- Selected Tags -->
      <div v-if="selectedTags.length > 0" class="mb-2 px-1">
        <div class="flex flex-wrap gap-1 mb-1">
          <span
            v-for="tag in selectedTags"
            :key="tag"
            class="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-100 text-blue-800 text-xs rounded"
          >
            {{ tag }}
            <button
              @click="removeTag(tag)"
              class="text-blue-600 hover:text-blue-800 focus:outline-none"
              type="button"
            >
              <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
              </svg>
            </button>
          </span>
        </div>
        <button
          @click="clearTagFilters"
          class="text-xs text-blue-600 hover:text-blue-800 focus:outline-none"
          type="button"
        >
          Clear filters
        </button>
      </div>

      <!-- Available Tags List -->
      <div class="flex-1 overflow-y-auto">
        <div v-if="isLoadingTags" class="flex items-center justify-center py-4">
          <div class="animate-spin rounded-full h-4 w-4 border-b-2 border-blue-600"></div>
        </div>
        <div v-else-if="filteredAvailableTags.length === 0" class="text-xs text-gray-500 text-center py-3">
          No tags found
        </div>
        <div v-else class="space-y-0.5">
          <button
            v-for="tag in filteredAvailableTags"
            :key="tag"
            @click="toggleTag(tag)"
            class="w-full px-1.5 py-1 text-left text-xs rounded transition-colors bg-gray-50 hover:bg-gray-100 text-gray-900"
          >
            {{ tag }}
          </button>
        </div>
      </div>

      <!-- Filtered Features List -->
      <div v-if="selectedTags.length > 0" class="mt-2 border-t border-gray-200 pt-2">
        <h3 class="text-xs font-semibold text-gray-900 mb-1 px-1">Filtered Results</h3>
        <div v-if="isFiltering" class="flex items-center justify-center py-2">
          <div class="animate-spin rounded-full h-4 w-4 border-b-2 border-blue-600"></div>
        </div>
        <div v-else class="max-h-48 overflow-y-auto">
          <div v-if="tagFilteredFeatures.length === 0" class="text-xs text-gray-500 text-center py-2">
            No features match all selected tags
          </div>
          <div v-else class="space-y-0.5">
            <div
              v-for="feature in tagFilteredFeatures"
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
        <div v-if="!isFiltering" class="mt-1 text-xs text-gray-500 border-t border-gray-200 pt-1 px-1">
          {{ tagFilteredFeatures.length }} features
        </div>
      </div>
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
  emits: ['feature-click', 'tag-filter-change'],
  data() {
    return {
      activeTab: 'features-in-view',
      searchQuery: '',
      searchResults: [],
      isSearching: false,
      searchTimeout: null,
      API_BASE_URL: '/api/data/features/search/',
      // Tag filter state
      selectedTags: [],
      tagSearchQuery: '',
      availableTags: [],
      isLoadingTags: false,
      tagFilteredFeatures: [],
      isFiltering: false,
      filterTimeout: null
    }
  },
  computed: {
    isSearchMode() {
      return this.searchQuery.trim().length > 0
    },
    displayFeatures() {
      return this.isSearchMode ? this.searchResults : this.features
    },
    filteredAvailableTags() {
      if (!this.tagSearchQuery.trim()) {
        return this.availableTags.filter(tag => !this.selectedTags.includes(tag))
      }
      const query = this.tagSearchQuery.toLowerCase()
      return this.availableTags.filter(tag => 
        tag.toLowerCase().includes(query) && !this.selectedTags.includes(tag)
      )
    }
  },
  watch: {
    selectedTags: {
      handler() {
        this.debouncedFilterByTags()
      },
      deep: true
    },
    activeTab(newTab) {
      if (newTab === 'tag-filter' && this.availableTags.length === 0) {
        this.fetchAvailableTags()
      }
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
    },
    // Tag filter methods
    async fetchAvailableTags() {
      this.isLoadingTags = true
      try {
        const response = await fetch(`${APIHOST}/api/data/features/by-tag/`)
        const data = await response.json()
        
        if (data.success && data.tags) {
          // Extract unique tags from the tags object keys
          this.availableTags = Object.keys(data.tags).sort()
        } else {
          console.error('Failed to fetch tags:', data.error || 'Unknown error')
          this.availableTags = []
        }
      } catch (error) {
        console.error('Error fetching available tags:', error)
        this.availableTags = []
      } finally {
        this.isLoadingTags = false
      }
    },
    handleTagSearchInput() {
      // Tag search is just for filtering the list, no API call needed
    },
    clearTagSearch() {
      this.tagSearchQuery = ''
    },
    toggleTag(tag) {
      if (this.selectedTags.includes(tag)) {
        this.removeTag(tag)
      } else {
        this.selectedTags.push(tag)
      }
    },
    removeTag(tag) {
      const index = this.selectedTags.indexOf(tag)
      if (index > -1) {
        this.selectedTags.splice(index, 1)
      }
    },
    clearTagFilters() {
      this.selectedTags = []
      this.tagFilteredFeatures = []
      // Emit empty array to clear map filter and restore normal behavior
      this.$emit('tag-filter-change', null)
    },
    debouncedFilterByTags() {
      if (this.filterTimeout) {
        clearTimeout(this.filterTimeout)
      }
      
      if (this.selectedTags.length === 0) {
        this.tagFilteredFeatures = []
        // Emit null to clear map filter and restore normal behavior
        this.$emit('tag-filter-change', null)
        return
      }
      
      this.isFiltering = true
      this.filterTimeout = setTimeout(() => {
        this.filterByTags()
      }, 300)
    },
    async filterByTags() {
      if (this.selectedTags.length === 0) {
        this.tagFilteredFeatures = []
        this.isFiltering = false
        return
      }
      
      this.isFiltering = true
      
      try {
        // Build URL with multiple tag parameters
        const tagParams = this.selectedTags.map(tag => `tags=${encodeURIComponent(tag)}`).join('&')
        const url = `${APIHOST}/api/data/features/filter-by-tags/?${tagParams}`
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
          
          this.tagFilteredFeatures = features
          
          // Emit event to parent to update map with filtered features
          this.$emit('tag-filter-change', features)
        } else {
          console.error('Tag filter failed:', data.error || 'Unknown error')
          this.tagFilteredFeatures = []
          // Emit empty array to clear map filter
          this.$emit('tag-filter-change', [])
        }
      } catch (error) {
        console.error('Error filtering features by tags:', error)
        this.tagFilteredFeatures = []
        // Emit empty array to clear map filter
        this.$emit('tag-filter-change', [])
      } finally {
        this.isFiltering = false
      }
    }
  },
  mounted() {
    // Fetch available tags when component mounts (will be used when tag filter tab is opened)
    this.fetchAvailableTags()
  },
  beforeUnmount() {
    // Clean up timeouts
    if (this.searchTimeout) {
      clearTimeout(this.searchTimeout)
    }
    if (this.filterTimeout) {
      clearTimeout(this.filterTimeout)
    }
  }
}
</script>

