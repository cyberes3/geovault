<template>
  <div 
    :class="[
      'bg-white flex flex-col h-full overflow-hidden',
      // Tablet landscape styles (compact) - show at lg (1024px)
      'lg:flex lg:static lg:w-64 lg:border-r lg:border-gray-200',
      // Desktop styles (full width) - show at xl (1280px) and above
      'xl:w-80',
      // Mobile/Tablet portrait styles (modal behavior) - up to lg (1024px)
      isMobileOpen ? 'fixed inset-0 z-50 w-full' : 'hidden'
    ]"
  >
    <!-- Mobile Header -->
    <div class="lg:hidden flex items-center justify-between px-4 py-3 border-b border-gray-200">
      <h2 class="text-lg font-semibold text-gray-900">Features</h2>
      <button 
        @click="$emit('close')" 
        class="text-gray-500 hover:text-gray-700 p-1 rounded-md hover:bg-gray-100"
      >
        <XMarkIcon class="w-6 h-6" />
      </button>
    </div>

    <!-- Tabs -->
    <div class="flex border-b border-gray-200 mb-2 px-1.5 pt-1.5 lg:px-1 xl:px-1.5">
      <button
        @click="activeTab = 'features-in-vicinity'"
        :class="[
          'px-2 py-1 text-xs font-medium transition-colors',
          activeTab === 'features-in-vicinity'
            ? 'text-blue-500 border-b-2 border-blue-500'
            : 'text-gray-600 hover:text-gray-900'
        ]"
        title="View features in current map view"
      >
        Features in Vicinity
      </button>
      <button
        @click="activeTab = 'tag-filter'"
        :class="[
          'px-2 py-1 text-xs font-medium transition-colors flex items-center gap-1',
          activeTab === 'tag-filter'
            ? 'text-blue-500 border-b-2 border-blue-500'
            : 'text-gray-600 hover:text-gray-900'
        ]"
        title="Filter features by tags"
      >
        Tag Filter
        <FunnelIcon
          v-if="selectedTags.length > 0"
          class="w-3 h-3 text-blue-500"
        />
      </button>
    </div>

    <!-- Features in Vicinity Tab Content -->
    <div v-if="activeTab === 'features-in-vicinity'" class="flex flex-col flex-1 min-h-0">
      <!-- Search Bar -->
      <div class="mb-2 px-1 lg:px-0.5 xl:px-1">
        <div class="relative">
          <input
            v-model="searchQuery"
            @input="handleSearchInput"
            type="text"
            placeholder="Search features..."
            class="w-full px-2 py-1.5 pr-7 text-xs border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent lg:px-1.5 lg:py-1 xl:px-2 xl:py-1.5"
          />
          <button
            v-if="searchQuery"
            @click="clearSearch"
            class="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 focus:outline-none"
            type="button"
            title="Clear search"
          >
            <XMarkIcon class="w-4 h-4" />
          </button>
        </div>
      </div>

      <!-- Header -->
      <h2 class="text-xs font-semibold text-gray-900 mb-1 px-1">
        {{ isSearchMode ? 'Search Results' : '' }}
      </h2>

      <!-- Initial Loading Indicator -->
      <div v-if="showInitialFeaturesLoader" class="flex-1 flex items-center justify-center">
        <Loader size="md" layout="centered" message="Loading features..." />
      </div>

      <!-- Loading Indicator for Search -->
      <div v-else-if="isSearching" class="flex-1 flex items-center justify-center">
        <Loader size="md" layout="centered" message="Searching..." />
      </div>

      <!-- Feature List -->
      <div v-else class="flex-1 select-none min-h-0">
        <div v-if="displayFeatures.length === 0" class="text-xs text-gray-500 text-center py-3">
          {{ isSearchMode ? 'No results found' : 'No features' }}
        </div>
        <RecycleScroller
          v-else
          class="scroller"
          :items="displayFeaturesWithKeys"
          :item-size="32"
          key-field="database_id"
          v-slot="{ item }"
        >
          <div
            @click="handleFeatureClick(item)"
            @contextmenu.prevent="handleFeatureContextMenu(item)"
            class="px-1.5 py-1.5 bg-gray-50 hover:bg-gray-100 transition-colors flex items-center cursor-pointer lg:px-1 lg:py-1 xl:px-1.5 xl:py-1.5"
            :style="{ borderLeft: `3px solid ${getGeometryTypeColor(item)}` }"
          >
            <div class="flex-1 min-w-0 flex items-center gap-1.5">
              <div class="text-xs text-gray-900 truncate">
                {{ getFeatureName(item) }}
              </div>
              <!-- Feature Icon -->
              <img
                v-if="getFeatureIconUrl(item)"
                :src="getFeatureIconUrl(item)"
                class="w-4 h-4 flex-shrink-0 object-contain"
                :alt="`${getFeatureName(item)} icon`"
                @error="$event.target.style.display = 'none'"
              />
            </div>
            <!-- Mobile/Tablet hide icon -->
            <button
              v-if="canHideFeatures"
              type="button"
              class="ml-1 text-gray-400 hover:text-gray-600 p-1 xl:hidden"
              title="Hide this feature from the main map"
              @click.stop.prevent="emitHideFeature(item)"
            >
              <EyeSlashIcon class="w-4 h-4" />
            </button>
          </div>
        </RecycleScroller>
      </div>

      <!-- Footer Count -->
      <div class="mt-1 text-xs text-gray-500 border-t border-gray-200 pt-1 px-1">
        {{ displayFeatures.length }}
      </div>
    </div>

    <!-- Tag Filter Tab Content -->
    <div v-if="activeTab === 'tag-filter'" class="flex flex-col flex-1 min-h-0">
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
            title="Clear tag search"
          >
            <XMarkIcon class="w-4 h-4" />
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
              title="Remove tag from filter"
            >
              <XMarkIcon class="w-3 h-3" />
            </button>
          </span>
        </div>
        <button
          @click="clearTagFilters"
          class="text-xs text-blue-500 hover:text-blue-700 focus:outline-none"
          type="button"
          title="Clear all tag filters"
        >
          Clear filters
        </button>
      </div>

      <!-- Available Tags List -->
      <div class="flex-1 min-h-0">
        <!-- Initial Loading Indicator -->
        <div v-if="showInitialTagsLoader" class="flex items-center justify-center h-full">
          <Loader size="md" layout="centered" message="Loading tags..." />
        </div>
        <div v-else-if="filteredAvailableTags.length === 0 && availableTags.length === 0" class="text-xs text-gray-500 text-center py-3">
          No tags available
        </div>
        <div v-else-if="filteredAvailableTags.length === 0" class="text-xs text-gray-500 text-center py-3">
          No tags match your search
        </div>
        <RecycleScroller
          v-else
          class="scroller"
          :items="filteredAvailableTagsWithKeys"
          :item-size="28"
          key-field="key"
          v-slot="{ item }"
        >
          <button
            @click="toggleTag(item.tag)"
            class="w-full px-1.5 py-1 text-left text-xs rounded transition-colors bg-gray-50 hover:bg-gray-100 text-gray-900"
            title="Toggle tag filter"
          >
            {{ item.tag }}
          </button>
        </RecycleScroller>
      </div>
    </div>
  </div>
</template>

<script>
import {APIHOST} from '@/config.js'
import { FunnelIcon, XMarkIcon, EyeSlashIcon } from '@heroicons/vue/24/outline'
import Loader from '@/components/parts/Loader.vue'
import { getGeometryTypeColor } from '@/utils/geometryColors.js'
import { sortTagsByPriority, sortUserTagsAlphabetically, isSystemTag } from '@/utils/tagUtils.js'
import { getIconUrl, resolveIconUrl, isSystemIcon } from '@/utils/map/iconUtils.ts'
import { RecycleScroller } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'

export default {
  name: 'FeatureListSidebar',
  components: {
    Loader,
    FunnelIcon,
    XMarkIcon,
    EyeSlashIcon,
    RecycleScroller
  },
  props: {
    features: {
      type: Array,
      required: false,
      default: () => []
    },
    availableTags: {
      type: Array,
      required: false,
      default: () => []
    },
    initialSelectedTags: {
      type: Array,
      required: false,
      default: () => []
    },
    isInitialLoad: {
      type: Boolean,
      required: false,
      default: false
    },
    isMobileOpen: {
      type: Boolean,
      default: false
    },
    canHideFeatures: {
      type: Boolean,
      default: false
    }
  },
  emits: ['feature-click', 'feature-hide', 'tag-filter-change', 'tag-filter-loading-change', 'tag-filter-start', 'close'],
  data() {
    return {
      activeTab: 'features-in-vicinity',
      searchQuery: '',
      searchResults: [],
      isSearching: false,
      searchTimeout: null,
      API_BASE_URL: '/api/features/search/',
      // Tag filter state
      selectedTags: [],
      tagSearchQuery: '',
      tagFilteredFeatures: [],
      isFiltering: false,
      filterTimeout: null,
    }
  },
  computed: {
    isSearchMode() {
      return this.searchQuery.trim().length > 0
    },
    displayFeatures() {
      return this.isSearchMode ? this.searchResults : this.features
    },
    displayFeaturesWithKeys() {
      // Wrap features with database_id at top level for RecycleScroller
      return this.displayFeatures.map((feature, index) => {
        // If feature already has database_id at top level, use it
        if (feature.database_id !== undefined) {
          return feature
        }
        
        // Extract database_id from properties (native GeoJSON)
        const properties = feature.properties || {}
        const geojsonHash = feature.geojson_hash
        const databaseId = properties.database_id || geojsonHash || `feature-${index}`
        
        // Return feature with database_id at top level
        return {
          ...feature,
          database_id: databaseId
        }
      })
    },
    showInitialFeaturesLoader() {
      return this.isInitialLoad && this.features.length === 0 && !this.isSearching
    },
    showInitialTagsLoader() {
      return this.isInitialLoad && this.availableTags.length === 0 && !this.isFiltering
    },
    filteredAvailableTags() {
      // Filter out already selected tags
      let unselectedTags = this.availableTags.filter(tag => !this.selectedTags.includes(tag))

      // Apply search filter if there's a search query
      if (this.tagSearchQuery.trim()) {
        const query = this.tagSearchQuery.toLowerCase()
        unselectedTags = unselectedTags.filter(tag => tag.toLowerCase().includes(query))
      }

      // Separate user tags and system tags
      const userTags = unselectedTags.filter(tag => !isSystemTag(tag))
      const systemTags = unselectedTags.filter(tag => isSystemTag(tag))
      
      // Sort user tags alphabetically, system tags by priority
      const sortedUserTags = sortUserTagsAlphabetically(userTags)
      const sortedSystemTags = sortTagsByPriority(systemTags)
      
      // Return user tags first, then system tags
      return [...sortedUserTags, ...sortedSystemTags]
    },
    filteredAvailableTagsWithKeys() {
      // Convert tags to objects with keys for RecycleScroller
      return this.filteredAvailableTags.map((tag, index) => ({
        key: `tag-${index}-${tag}`,
        tag: tag
      }))
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
      // Tags are now provided via prop, no need to fetch
    },
    // When initialSelectedTags changes (e.g., route query changes), update local selection
    initialSelectedTags: {
      immediate: true,
      handler(newTags) {
        const tagsArray = Array.isArray(newTags) ? newTags : []
        if (tagsArray.length > 0) {
          this.selectedTags = [...tagsArray]
          this.activeTab = 'tag-filter'
        }
      }
    }
  },
  methods: {
    getFeatureName(feature) {
      // Pure GeoJSON features only
      const properties = feature.properties || {}
      return properties.name || 'Unnamed Feature'
    },
    getFeatureGeometryType(feature) {
      // Pure GeoJSON features only
      if (feature.geometry) {
        return feature.geometry.type || 'Unknown'
      }
      return 'Unknown'
    },
    getGeometryTypeColor(feature) {
      const geometryType = this.getFeatureGeometryType(feature)
      return getGeometryTypeColor(geometryType)
    },
    getFeatureIconUrl(feature) {
      // Pure GeoJSON features only
      const properties = feature.properties || {}
      const iconUrl = getIconUrl(properties)
      
      if (!iconUrl) {
        return null
      }
      
      // Get marker color for potential recoloring of system icons
      const markerColor = properties['marker-color']
      const builtInIcon = isSystemIcon(iconUrl)
      
      if (builtInIcon && markerColor) {
        // Handle system icon recoloring
        const iconPathForRecolor = iconUrl.replace('/api/icons/system/', '')
        const encodedColor = encodeURIComponent(markerColor)
        const encodedIcon = encodeURIComponent(iconPathForRecolor)
        return `${APIHOST}/api/icons/recolor/?icon=${encodedIcon}&color=${encodedColor}`
      }
      
      return resolveIconUrl(iconUrl)
    },
    handleFeatureContextMenu(feature) {
      // Right-click hide (desktop only)
      if (!this.canHideFeatures) {
        return
      }
      this.emitHideFeature(feature)
    },
    emitHideFeature(feature) {
      if (!this.canHideFeatures) {
        return
      }
      this.$emit('feature-hide', feature)
    },
    handleFeatureClick(feature) {
      this.$emit('feature-click', feature)
      // Close modal on mobile when a feature is selected
      if (this.isMobileOpen) {
        this.$emit('close')
      }
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

        if (response.ok && data.data && data.data.features) {
          // Use native GeoJSON features
          const features = data.data.features

          // Sort features alphabetically by name
          features.sort((a, b) => {
            const nameA = (a.properties?.name || 'Unnamed Feature').toLowerCase()
            const nameB = (b.properties?.name || 'Unnamed Feature').toLowerCase()
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
        this.$emit('tag-filter-loading-change', false)
        return
      }

      this.isFiltering = true
      this.$emit('tag-filter-loading-change', true)
      this.filterTimeout = setTimeout(() => {
        this.filterByTags()
      }, 300)
    },
    async filterByTags() {
      if (this.selectedTags.length === 0) {
        this.tagFilteredFeatures = []
        this.isFiltering = false
        this.$emit('tag-filter-loading-change', false)
        return
      }

      this.isFiltering = true
      
      // Emit tags for immediate filtering of existing features
      this.$emit('tag-filter-start', this.selectedTags)
      this.$emit('tag-filter-loading-change', true)

      try {
        // Build URL with multiple tag parameters
        const tagParams = this.selectedTags.map(tag => `tags=${encodeURIComponent(tag)}`).join('&')
        const url = `${APIHOST}/api/features/filter-by-tags/?${tagParams}`
        const response = await fetch(url)
        const data = await response.json()

        if (response.ok && data.data && data.data.features) {
          // Use native GeoJSON features
          const features = data.data.features

          // Sort features alphabetically by name
          features.sort((a, b) => {
            const nameA = (a.properties?.name || 'Unnamed Feature').toLowerCase()
            const nameB = (b.properties?.name || 'Unnamed Feature').toLowerCase()
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
        this.$emit('tag-filter-loading-change', false)
      }
    }
  },
  async mounted() {
    // Tags are now provided via prop from parent component
    // System tag detection is handled by the imported isSystemTag function from tagUtils.js
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

<style scoped>
.scroller {
  height: 100%;
}
</style>
