<template>
  <!-- Teleport when open on mobile so stacking matches BaseModal (above app nav z-300). -->
  <Teleport to="body" :disabled="!isMobileOpen">
    <div
      data-app-mobile-overlay="sheet"
      :class="sidebarRootClass"
      :role="isMobileOpen ? 'dialog' : undefined"
      :aria-modal="isMobileOpen ? 'true' : undefined"
      :aria-labelledby="isMobileOpen ? 'feature-list-sidebar-title' : undefined"
    >
    <!-- Mobile Header -->
    <div class="lg:hidden flex items-center justify-between px-4 py-3 border-b border-gray-200">
      <h2 id="feature-list-sidebar-title" class="text-lg font-semibold text-gray-900">Features</h2>
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
        title="View Features in Current Map View"
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
        title="Filter Features by Tags"
      >
        Tag Filter
        <FunnelIcon
          v-if="selectedTags.length > 0"
          class="w-3 h-3 text-blue-500"
        />
      </button>
      <button
        @click="activeTab = 'reverse_geocoding'"
        :class="[
          'px-2 py-1 text-xs font-medium transition-colors',
          activeTab === 'reverse_geocoding'
            ? 'text-blue-500 border-b-2 border-blue-500'
            : 'text-gray-600 hover:text-gray-900'
        ]"
        title="Search for Places or Paste Coordinates"
      >
        Search Places
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
            title="Clear Search"
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
              title="Hide This Feature from the Main Map"
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
      <!-- Match Mode Radio Buttons -->
      <div class="mb-2 px-1">
        <div class="flex items-center gap-3 text-xs">
          <span class="text-gray-600 font-medium">Match:</span>
          <label class="flex items-center gap-1.5 cursor-pointer">
            <input
              type="radio"
              v-model="tagMatchMode"
              value="AND"
              class="radio-custom"
            />
            <span class="text-gray-700">AND</span>
          </label>
          <label class="flex items-center gap-1.5 cursor-pointer">
            <input
              type="radio"
              v-model="tagMatchMode"
              value="OR"
              class="radio-custom"
            />
            <span class="text-gray-700">OR</span>
          </label>
        </div>
      </div>

      <!-- Tag Search Input -->
      <div class="mb-2 px-1">
        <div class="relative">
          <input
            v-model="tagSearchQuery"
            @input="handleTagSearchInput"
            @keydown.enter="handleTagSearchEnter"
            type="text"
            placeholder="Search tags..."
            class="w-full px-2 py-1.5 pr-7 text-xs border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
          <button
            v-if="tagSearchQuery"
            @click="clearTagSearch"
            class="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 focus:outline-none"
            type="button"
            title="Clear Tag Search"
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
            :class="[
              'inline-flex items-center gap-1 px-2 py-0.5 text-xs rounded max-w-full',
              tag.endsWith(':') ? 'bg-purple-100 text-purple-800' : 'bg-blue-100 text-blue-800'
            ]"
            :title="tag.endsWith(':') ? `Prefix match: ${tag}` : tag"
          >
            <span class="truncate min-w-0">{{ tag }}</span>
            <button
              @click="removeTag(tag)"
              :class="[
                'hover:text-blue-800 focus:outline-none flex-shrink-0',
                tag.endsWith(':') ? 'text-purple-600' : 'text-blue-600'
              ]"
              type="button"
              title="Remove Tag from Filter"
            >
              <XMarkIcon class="w-3 h-3" />
            </button>
          </span>
        </div>
        <button
          @click="clearTagFilters"
          class="text-xs text-blue-500 hover:text-blue-700 focus:outline-none"
          type="button"
          title="Clear All Tag Filters"
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
            class="w-full px-1.5 py-1 text-left text-xs rounded transition-colors bg-gray-50 hover:bg-gray-100 text-gray-900 truncate min-w-0"
            :title="item.tag"
          >
            {{ item.tag }}
          </button>
        </RecycleScroller>
      </div>
    </div>

    <!-- Forward Geocoding Tab Content (place search) -->
    <div v-if="activeTab === 'reverse_geocoding'" class="flex flex-col flex-1 min-h-0">
      <!-- Search Input -->
      <div class="mb-2 px-1 lg:px-0.5 xl:px-1">
        <div class="relative">
          <input
            v-model="geocodingQuery"
            @input="handleGeocodingInput"
            type="text"
            :placeholder="geocodingAvailable ? 'Search places or coordinates...' : 'Search for coordinates...'"
            class="w-full px-2 py-1.5 pr-7 text-xs border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent lg:px-1.5 lg:py-1 xl:px-2 xl:py-1.5"
          />
          <button
            v-if="geocodingQuery"
            @click="clearGeocodingSearch"
            class="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 focus:outline-none"
            type="button"
            title="Clear Search"
          >
            <XMarkIcon class="w-4 h-4" />
          </button>
        </div>
      </div>

      <!-- Loading Indicator -->
      <div v-if="isGeocodingSearching" class="flex-1 flex items-center justify-center">
        <Loader size="md" layout="centered" message="Searching places..." />
      </div>

      <!-- Results List -->
      <div v-else class="flex flex-col flex-1 min-h-0">
        <!-- Clear Results Button -->
        <div v-if="geocodingResults.length > 0" class="mb-2 px-1">
          <BaseButton
            @click="clearGeocodingSearch"
            class="w-full"
            variant="white"
            size="xs"
            type="button"
            title="Clear Results and Remove Marker"
          >
            Clear Results
          </BaseButton>
        </div>
        
        <div class="flex-1 select-none min-h-0">
          <div v-if="geocodingResults.length === 0 && !geocodingQuery.trim()" class="text-xs text-gray-500 text-center py-3">
            {{ geocodingAvailable ? 'Enter a place name to search' : 'Enter coordinates' }}
          </div>
          <div v-else-if="geocodingResults.length === 0 && geocodingQuery.trim()" class="text-xs text-gray-500 text-center py-3">
            {{ geocodingAvailable ? 'No results found' : 'Only coordinate search is available.' }}
          </div>
          <RecycleScroller
            v-else
            class="scroller"
            :items="geocodingResultsWithKeys"
            :item-size="48"
            key-field="id"
            v-slot="{ item }"
          >
            <div
              @click="handleGeocodingResultClick(item)"
              class="px-1.5 py-2 bg-gray-50 hover:bg-gray-100 transition-colors cursor-pointer lg:px-1 lg:py-1.5 xl:px-1.5 xl:py-2"
            >
              <div class="text-xs font-medium text-gray-900 truncate">
                {{ getGeocodingResultName(item) }}
              </div>
              <div v-if="getGeocodingResultDescription(item)" class="text-xs text-gray-500 truncate mt-0.5">
                {{ getGeocodingResultDescription(item) }}
              </div>
            </div>
          </RecycleScroller>
        </div>
      </div>
    </div>
    </div>
  </Teleport>
</template>

<script>
import {APIHOST} from '@/config.js'
import { FunnelIcon, XMarkIcon, EyeSlashIcon } from '@heroicons/vue/24/outline'
import Loader from '@/components/parts/Loader.vue'
import BaseButton from '@/components/parts/BaseButton.vue'
import { getGeometryTypeColor } from '@/utils/geometryColors.js'
import { sortTagsByPriority, sortUserTagsAlphabetically, isSystemTag } from '@/utils/tagUtils.js'
import { getIconUrl, resolveIconUrl, isSystemIcon } from '@/utils/map/iconUtils.ts'
import { parseCoordinates } from '@/utils/coordinateParser.js'
import { toastApiError } from '@/utils/apiError.js'
import { toast } from '@/utils/toast'
import { RecycleScroller } from 'vue-virtual-scroller'
import 'vue-virtual-scroller/dist/vue-virtual-scroller.css'

export default {
  name: 'FeatureListSidebar',
  components: {
    Loader,
    BaseButton,
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
    },
    geocodingAvailable: {
      type: Boolean,
      default: false
    }
  },
  emits: ['feature-click', 'feature-hide', 'tag-filter-change', 'tag-filter-loading-change', 'tag-filter-start', 'reverse_geocoding-result-click', 'reverse_geocoding-clear', 'close'],
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
      tagMatchMode: 'AND', // Default to AND mode
      tagFilteredFeatures: [],
      isFiltering: false,
      filterTimeout: null,
      // Forward reverse_geocoding state (place search)
      geocodingQuery: '',
      geocodingResults: [],
      isGeocodingSearching: false,
      geocodingTimeout: null,
      currentSearchQuery: '', // Track the query of the current/latest search to prevent race conditions
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
    sidebarRootClass() {
      if (this.isMobileOpen) {
        return [
          'bg-white',
          'flex',
          'flex-col',
          'overflow-hidden',
          'fixed',
          'inset-0',
          'z-50',
          'w-full',
          'h-full',
          'lg:hidden'
        ].join(' ')
      }
      return [
        'bg-white',
        'flex',
        'flex-col',
        'h-full',
        'overflow-hidden',
        'hidden',
        'lg:flex',
        'lg:static',
        'lg:w-64',
        'lg:border-r',
        'lg:border-gray-200',
        'xl:w-80'
      ].join(' ')
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
    },
    geocodingResultsWithKeys() {
      // Convert forward reverse_geocoding results to objects with keys for RecycleScroller
      return this.geocodingResults.map((result, index) => ({
        ...result,
        id: result.id || `geocoding-${index}-${result.place_name || ''}`
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
    tagMatchMode() {
      // Re-filter when match mode changes
      this.debouncedFilterByTags()
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
          // Explicitly trigger filter immediately (don't wait for debounce)
          this.$nextTick(() => {
            this.filterByTags()
          })
        }
      }
    },
    isMobileOpen(open) {
      if (open) {
        document.body.classList.add('overflow-hidden')
      } else {
        document.body.classList.remove('overflow-hidden')
      }
    }
  },
  methods: {
    getFeatureName(feature) {
      // Pure GeoJSON features only
      const properties = feature.properties || {}
      return properties.name || ''
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
          toast.error(data.error || 'Search failed')
          this.searchResults = []
        }
      } catch (error) {
        console.error('Error searching features:', error)
        toastApiError(error, 'Search failed')
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
    handleTagSearchEnter() {
      // Allow adding tags by pressing Enter (including prefix tags with :)
      const query = this.tagSearchQuery.trim()
      if (query && !this.selectedTags.includes(query)) {
        this.selectedTags.push(query)
        this.tagSearchQuery = ''
      }
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
      // Emit empty tags to clear map filter and restore normal behavior
      this.$emit('tag-filter-change', { tags: [], matchMode: 'AND' })
    },
    debouncedFilterByTags() {
      if (this.filterTimeout) {
        clearTimeout(this.filterTimeout)
      }

      if (this.selectedTags.length === 0) {
        this.tagFilteredFeatures = []
        // Clear filter
        this.$emit('tag-filter-change', { tags: [], matchMode: 'AND' })
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
      // Logic refactored to delegate to parent (MapPage) via event
      // MapPage will use the bbox API to fetch filtered features

      this.$emit('tag-filter-loading-change', true)

      // Emit tags and match mode for MapPage to handle
      // This will trigger a data reload in MapPage with the tag parameters
      this.$emit('tag-filter-change', {
        tags: this.selectedTags,
        matchMode: this.tagMatchMode
      })
      
      // We don't fetch here anymore. MapPage will update the 'features' prop
      // when data is loaded.
      
      this.$emit('tag-filter-loading-change', false)
    },
    // Forward reverse_geocoding methods (place search)
    handleGeocodingInput() {
      // Clear existing timeout
      if (this.geocodingTimeout) {
        clearTimeout(this.geocodingTimeout)
      }

      const query = this.geocodingQuery.trim()

      // If query is empty, clear search immediately
      if (!query) {
        this.clearGeocodingSearch()
        return
      }

      // Show loading spinner immediately while user is typing
      this.isGeocodingSearching = true

      // Debounce search (300ms)
      this.geocodingTimeout = setTimeout(() => {
        this.performGeocodingSearch(query)
      }, 300)
    },
    async performGeocodingSearch(query) {
      if (!query) {
        this.clearGeocodingSearch()
        return
      }

      // Store the current search query to prevent race conditions
      this.currentSearchQuery = query
      this.isGeocodingSearching = true

      // First, try to parse the query as coordinates
      const coordinates = parseCoordinates(query)
      if (coordinates) {
        // Successfully parsed as coordinates - create a direct result
        const coordinateResult = {
          type: 'Feature',
          text: `${coordinates.lat.toFixed(6)}, ${coordinates.lng.toFixed(6)}`,
          place_name: `Coordinates: ${coordinates.lat.toFixed(6)}°, ${coordinates.lng.toFixed(6)}°`,
          center: [coordinates.lng, coordinates.lat],
          coordinates: [coordinates.lng, coordinates.lat],
          // Create a small bbox around the point (approximately 1km in each direction)
          // 1km ≈ 0.009° at the equator
          bbox: [
            coordinates.lng - 0.009,
            coordinates.lat - 0.009,
            coordinates.lng + 0.009,
            coordinates.lat + 0.009
          ]
        }
        
        // Only update if this is still the current query
        if (this.currentSearchQuery === query) {
          this.geocodingResults = [coordinateResult]
          this.isGeocodingSearching = false
        }
        return
      }

      // Not coordinates: only call geocoding API if available; otherwise coordinate-only mode
      if (!this.geocodingAvailable) {
        if (this.currentSearchQuery === query) {
          this.geocodingResults = []
          this.isGeocodingSearching = false
        }
        return
      }

      try {
        const url = `${APIHOST}/api/geocoding/search/?q=${encodeURIComponent(query)}`
        const response = await fetch(url)
        const data = await response.json()

        // Only update results if this response is for the current query
        // This prevents older requests from overwriting newer results
        if (this.currentSearchQuery !== query) {
          return // This response is stale, ignore it
        }

        if (response.ok && data.data && data.data.features) {
          this.geocodingResults = data.data.features
        } else {
          console.error('Forward reverse_geocoding search failed:', data.error || 'Unknown error')
          toast.error(data.error || 'Place search failed')
          if (this.currentSearchQuery === query) {
            this.geocodingResults = []
          }
        }
      } catch (error) {
        console.error('Error searching places:', error)
        toastApiError(error, 'Place search failed')
        // Only clear results if this is still the current query
        if (this.currentSearchQuery === query) {
          this.geocodingResults = []
        }
      } finally {
        // Only clear loading state if this is still the current query
        if (this.currentSearchQuery === query) {
          this.isGeocodingSearching = false
        }
      }
    },
    clearGeocodingSearch() {
      this.geocodingQuery = ''
      this.geocodingResults = []
      this.isGeocodingSearching = false
      this.currentSearchQuery = '' // Clear current search query
      if (this.geocodingTimeout) {
        clearTimeout(this.geocodingTimeout)
        this.geocodingTimeout = null
      }
      // Emit event to clear marker on map
      this.$emit('reverse_geocoding-clear')
    },
    getGeocodingResultName(result) {
      // Use 'text' as the title (e.g., "Denver International Airport")
      return result.text || result.place_name || 'Unknown place'
    },
    getGeocodingResultDescription(result) {
      return result.place_name || null
    },
    handleGeocodingResultClick(result) {
      this.$emit('reverse_geocoding-result-click', result)
      // Close modal on mobile when a result is selected
      if (this.isMobileOpen) {
        this.$emit('close')
      }
    }
  },
  async mounted() {
    // Tags are now provided via prop from parent component
    // System tag detection is handled by the imported isSystemTag function from tagUtils.js
  },
  beforeUnmount() {
    document.body.classList.remove('overflow-hidden')
    // Clean up timeouts
    if (this.searchTimeout) {
      clearTimeout(this.searchTimeout)
    }
    if (this.filterTimeout) {
      clearTimeout(this.filterTimeout)
    }
    if (this.geocodingTimeout) {
      clearTimeout(this.geocodingTimeout)
    }
  }
}
</script>

<style scoped>
.scroller {
  height: 100%;
}
</style>
