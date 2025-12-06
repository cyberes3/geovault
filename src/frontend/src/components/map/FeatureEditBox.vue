<template>
  <div v-if="feature" class="fixed inset-0 z-50 bg-white flex flex-col w-full h-full md:absolute md:inset-auto md:bottom-4 md:right-4 md:w-96 md:max-w-md md:h-auto md:max-h-[calc(100%-2rem)] rounded-t-xl md:rounded-lg shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.1)] md:shadow-xl md:border md:border-gray-200">
    <!-- Header (Sticky) -->
    <div class="sticky top-0 z-10 flex-none flex items-center justify-between px-6 py-4 border-b border-gray-200 bg-gray-50 sm:rounded-t-lg">
      <h3 class="text-lg font-medium text-gray-900 truncate">Edit Feature</h3>
      <button
        @click="$emit('cancel')"
        :disabled="isSaving"
        class="text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150 disabled:opacity-50 disabled:cursor-not-allowed"
        title="Close edit dialog"
      >
        <XMarkIcon class="h-6 w-6" />
      </button>
    </div>

    <!-- Scrollable Content -->
    <div class="flex-1 overflow-y-auto px-6 py-2">
      <form @submit.prevent="handleSubmit" class="space-y-4">
        <!-- Name Field -->
        <div>
          <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Name</label>
          <input
            v-model="formData.name"
            type="text"
            :disabled="isSaving"
            class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
            required
          />
        </div>

        <!-- Tags Field -->
        <div>
          <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Tags</label>
          <TagPicker
            v-model:tags="formData.tags"
            :available-tags="availableTags"
            :system-tags="systemTags"
            :disabled="isSaving"
            :show-label="false"
          />
        </div>

        <!-- Description Field -->
        <div>
          <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Description</label>
          <textarea
            v-model="formData.description"
            rows="3"
            :disabled="isSaving"
            class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
          ></textarea>
        </div>

        <!-- Created Date Field -->
        <div>
          <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Created Date</label>
          <input
            type="datetime-local"
            :disabled="isSaving"
            :value="formatDateForInput(formData.created)"
            @change="updateDate"
            class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
          />
        </div>

        <!-- Icon Section (for points) -->
        <IconSelector
          v-if="isPoint"
          :icon-url="currentIconUrl"
          :disabled="isSaving"
          :show-remove="true"
          size="sm"
          :error="iconUploadError"
          @icon-selected="handleIconSelectedFromSelector"
          @icon-removed="handleRemoveIcon"
        />

        <!-- Icon Color (for points) -->
        <!-- Enabled for: default markers (no icon) OR system icons (recolorable) -->
        <!-- Disabled for: user icons or external URLs (custom, non-recolorable) -->
        <div v-if="isPoint">
          <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Icon Color</label>
          <ColorPicker
            v-model="formData.markerColor"
            :disabled="isSaving || isCustomIcon"
            size="sm"
          />
        </div>

        <!-- Line/Polygon Color -->
        <div v-if="isLine">
          <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Line Color</label>
          <ColorPicker
            v-model="formData.strokeColor"
            :disabled="isSaving"
            size="sm"
            @change="onStrokeColorChange"
          />
        </div>

        <div v-if="isPolygon">
          <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Border Color</label>
          <ColorPicker
            v-model="formData.strokeColor"
            :disabled="isSaving"
            size="sm"
            @change="onStrokeColorChange"
          />
        </div>

        <!-- Coordinates Section -->
        <div class="pt-2">
          <div class="flex items-center justify-center gap-4">
            <button
              type="button"
              @click="openCoordinatesDialog"
              :disabled="isSaving"
              class="text-xs text-blue-600 hover:text-blue-800 hover:underline disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center font-medium focus:outline-none"
              title="Edit coordinates manually"
            >
              <MapIcon class="w-3 h-3 mr-1" />
              Edit Coords
            </button>
            <button
              type="button"
              @click="openReplacementDialog"
              :disabled="isSaving"
              class="text-xs text-blue-600 hover:text-blue-800 hover:underline disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center font-medium focus:outline-none"
              title="Update spatial data from file"
            >
              <ArrowUpTrayIcon class="w-3 h-3 mr-1" />
              Update Geo
            </button>
          </div>
        </div>

        <!-- Account-level visibility toggle (main map only) -->
        <div
          v-if="canHideFeature && featureId"
          class="mt-3 pt-3 border-t border-gray-200"
        >
          <label class="inline-flex items-start gap-2 cursor-pointer">
            <input
              type="checkbox"
              v-model="hideOnMainMap"
              @change="handleHideToggle"
              :disabled="isSaving"
              class="mt-0.5 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            />
            <div>
              <span class="block text-xs font-semibold text-gray-800">
                Hide this feature on the main map
              </span>
              <span class="block text-[11px] text-gray-500">
                Only affects your main map view. Collections, tag views, and public shares are unaffected.
              </span>
            </div>
          </label>
        </div>

        <!-- Error Message -->
        <div v-if="errorMessage" class="p-2 bg-red-50 border border-red-200 rounded-md">
          <p class="text-xs text-red-800">{{ errorMessage }}</p>
        </div>
      </form>
    </div>

    <!-- Footer with Action Buttons (Sticky) -->
    <div class="sticky bottom-0 z-10 flex-none flex justify-between px-6 py-4 gap-3 border-t border-gray-200 bg-gray-50 sm:rounded-b-lg">
      <button
        type="button"
        @click="handleDelete"
        :disabled="isSaving"
        class="px-3 py-2 text-sm font-medium text-white bg-red-600 border border-transparent rounded-md hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 disabled:opacity-50 disabled:cursor-not-allowed"
        title="Delete feature"
      >
        Delete
      </button>
      <div class="flex space-x-2 flex-1 md:flex-none justify-end">
        <button
          type="button"
          @click="$emit('cancel')"
          :disabled="isSaving"
          class="px-3 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed flex-1 md:flex-none"
          title="Cancel editing"
        >
          Cancel
        </button>
        <button
          type="button"
          @click="handleSubmit"
          :disabled="isSaving"
          class="px-3 py-2 text-sm font-medium text-white bg-blue-600 border border-transparent rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed flex-1 md:flex-none"
          title="Save changes"
        >
          {{ isSaving ? 'Saving...' : 'Save' }}
        </button>
      </div>
    </div>

    <!-- Replacement Feature Dialog -->
    <ReplacementFeatureDialog
      :is-open="replacementDialogOpen"
      :feature-id="featureId"
      @close="closeReplacementDialog"
      @applied="handleReplacementApplied"
    />

    <!-- Coordinates Edit Dialog -->
    <div
      v-if="coordinatesDialogOpen"
      class="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
      @click.self="closeCoordinatesDialog"
    >
      <div class="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 max-h-[90vh] flex flex-col">
        <div class="flex items-center justify-between p-4 border-b border-gray-200">
          <h3 class="text-lg font-semibold text-gray-900">Edit Coordinates</h3>
          <button
            @click="closeCoordinatesDialog"
            class="text-gray-400 hover:text-gray-600"
            title="Close coordinates editor"
          >
            <XMarkIcon class="w-5 h-5" />
          </button>
        </div>
        <div class="p-4 overflow-y-auto flex-1">
          <div class="space-y-4">
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-2">
                Coordinates (JSON array)
              </label>
              <textarea
                v-model="rawJsonInput"
                rows="12"
                :disabled="isSaving"
                class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 font-mono text-xs disabled:bg-gray-100 disabled:cursor-not-allowed"
                placeholder="[]"
              ></textarea>
            </div>
          </div>
        </div>
        <div class="flex justify-end space-x-2 p-4 border-t border-gray-200">
          <button
            type="button"
            @click="closeCoordinatesDialog"
            :disabled="isSaving"
            class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            title="Close"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {APIHOST} from '@/config.js'
import ReplacementFeatureDialog from './ReplacementFeatureDialog.vue'
import TagPicker from '@/components/parts/TagPicker.vue'
import ColorPickerElement from '@/components/parts/ColorPickerElement.vue'
import IconSelector from '@/components/parts/IconSelector.vue'
import { XMarkIcon, MapIcon, ArrowUpTrayIcon } from '@heroicons/vue/24/outline'
import { sortTagsByPriority } from '@/utils/tagUtils.js'

// Helper functions for icon type checking
function isSystemIcon(iconUrl) {
  return iconUrl && iconUrl.includes('/api/icons/system/')
}

function isUserIcon(iconUrl) {
  return iconUrl && iconUrl.includes('/api/icons/user/')
}

export default {
  name: 'FeatureEditBox',
  components: {
    ReplacementFeatureDialog,
    TagPicker,
    ColorPicker: ColorPickerElement,
    IconSelector,
    XMarkIcon,
    MapIcon,
    ArrowUpTrayIcon
  },
  props: {
    feature: {
      type: Object,
      default: null
    },
    availableTags: {
      type: Array,
      required: false,
      default: () => []
    },
    canHideFeature: {
      type: Boolean,
      default: false
    },
    initialHidden: {
      type: Boolean,
      default: false
    }
  },
  emits: ['cancel', 'saved', 'deleted', 'visibility-change'],
  data() {
    return {
      formData: {
        name: '',
        description: '',
        tags: [],
        created: '',
        markerColor: '#ff0000',
        strokeColor: '#ff0000',
        strokeWidth: 2,
        fillColor: '#ff0000'
      },
      tagsInput: '',
      rawJsonInput: '',
      hasPngIcon: false,
      isSaving: false,
      errorMessage: '',
      uploadedIconFile: null,
      iconPreviewUrl: null,
      iconUploadError: '',
      currentIconUrl: null,
      iconRemoved: false,
      replacementDialogOpen: false,
      coordinatesDialogOpen: false,
      hideOnMainMap: false
    }
  },
  computed: {
    featureId() {
      if (!this.feature) return null
      const properties = this.feature.properties || {}
      return properties.database_id
    },
    geometryType() {
      if (!this.feature) return null
      const geometry = this.feature.geometry
      if (!geometry) return null
      return geometry.type
    },
    // Note: MultiPoint and MultiPolygon should not appear in processed features.
    // KML's MultiGeometry converts to GeometryCollection (not MultiPoint/MultiPolygon).
    // If they do appear, the backend will error/assert. These checks are kept for
    // defensive purposes and backward compatibility.
    isPoint() {
      return this.geometryType === 'Point' || this.geometryType === 'MultiPoint'
    },
    isLine() {
      return this.geometryType === 'LineString' || this.geometryType === 'MultiLineString'
    },
    isPolygon() {
      return this.geometryType === 'Polygon' || this.geometryType === 'MultiPolygon'
    },
    isBuiltInIcon() {
      return this.currentIconUrl && isSystemIcon(this.currentIconUrl)
    },
    isCustomIcon() {
      // Custom icon is a user-uploaded icon or any icon that's not a system icon
      if (!this.currentIconUrl || !this.hasPngIcon) return false
      return isUserIcon(this.currentIconUrl) || !isSystemIcon(this.currentIconUrl)
    },
    systemTags() {
      if (!this.feature) return []
      const properties = this.feature.properties || {}
      const tags = Array.isArray(properties.system_tags)
        ? properties.system_tags.filter(tag => tag && tag.trim() !== '')
        : []
      // Sort system tags by priority (ascending: 1 first, then 2, ..., then 0), then alphabetically
      return sortTagsByPriority(tags)
    }
  },
  mounted() {
    this.initializeForm()
  },
  watch: {
    feature: {
      async handler() {
        this.initializeForm()
      },
      immediate: true
    },
  },
  methods: {
    initializeForm() {
      if (!this.feature) return

      const properties = this.feature.properties || {}

      // Initialize form data
      this.formData.name = properties.name || ''
      this.formData.description = properties.description || ''
      // Tags are already separated - user tags only in tags field
      this.formData.tags = Array.isArray(properties.tags) ? properties.tags : []
      this.tagsInput = this.formData.tags.join(', ') // Keep for backward compatibility
      this.formData.created = this.formatDateForInput(properties.created || '')
      this.formData.markerColor = properties['marker-color'] || '#ff0000'

      // Initialize stroke color and width for lines and polygons
      this.formData.strokeColor = properties.stroke || '#ff0000'
      this.formData.strokeWidth = properties['stroke-width'] || 2

      // Initialize fill color for polygons
      if (this.isPolygon) {
        // If fill exists, use it; otherwise calculate from stroke with 10% opacity
        if (properties.fill) {
          // Extract hex from fill if it's rgba, otherwise use as-is
          this.formData.fillColor = this.extractHexFromColor(properties.fill) || properties.fill
        } else {
          // Default: use stroke color as base for fill
          this.formData.fillColor = this.formData.strokeColor
        }
      }

      // Check for PNG icon
      this.hasPngIcon = this.checkForPngIcon(properties)

      // Store current icon URL for display
      this.currentIconUrl = this.getCurrentIconUrl(properties)

      // Initialize raw JSON
      this.updateRawJson()

      // Reset icon upload state
      this.uploadedIconFile = null
      this.iconPreviewUrl = null
      this.iconUploadError = ''
      this.iconRemoved = false

      // Initialize hide toggle based on prop
      this.hideOnMainMap = !!this.initialHidden
    },

    checkForPngIcon(properties) {
      const iconPropertyNames = ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol']
      const validIconExtensions = ['.png', '.jpg', '.jpeg', '.gif', '.bmp', '.svg', '.webp', '.ico']

      for (const propName of iconPropertyNames) {
        if (properties[propName] && typeof properties[propName] === 'string') {
          const iconUrl = properties[propName].trim()
          // Check if it's an icon (ends with valid extension or is a system/user icon)
          if (isSystemIcon(iconUrl) || isUserIcon(iconUrl)) {
            return true
          }
          // Check if it ends with a valid icon extension
          const lowerUrl = iconUrl.toLowerCase()
          for (const ext of validIconExtensions) {
            if (lowerUrl.endsWith(ext)) {
              return true
            }
          }
        }
      }
      return false
    },

    getCurrentIconUrl(properties) {
      const iconPropertyNames = ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol']
      const validIconExtensions = ['.png', '.jpg', '.jpeg', '.gif', '.bmp', '.svg', '.webp', '.ico']

      for (const propName of iconPropertyNames) {
        if (properties[propName] && typeof properties[propName] === 'string') {
          const iconUrl = properties[propName].trim()
          if (iconUrl) {
            // Check if it's a system or user icon
            if (isSystemIcon(iconUrl) || isUserIcon(iconUrl)) {
              return iconUrl
            }
            // Check if it ends with a valid icon extension
            const lowerUrl = iconUrl.toLowerCase()
            for (const ext of validIconExtensions) {
              if (lowerUrl.endsWith(ext)) {
                return iconUrl
              }
            }
          }
        }
      }
      return null
    },

    resolveIconUrl(iconUrl) {
      // If already absolute URL, return as is
      if (iconUrl.startsWith('http://') || iconUrl.startsWith('https://')) {
        return iconUrl
      }
      // If relative URL starting with /api/, prepend APIHOST
      if (iconUrl.startsWith('/api/')) {
        return `${APIHOST}${iconUrl}`
      }
      // If relative URL starting with /assets/, prepend APIHOST (for non-icon assets)
      if (iconUrl.startsWith('/assets/')) {
        return `${APIHOST}${iconUrl}`
      }
      // If relative URL starting with assets/, prepend /assets/ (for non-icon assets)
      if (iconUrl.startsWith('assets/')) {
        return `${APIHOST}/${iconUrl}`
      }
      // Fallback: assume it's a relative path and prepend APIHOST
      return `${APIHOST}${iconUrl.startsWith('/') ? '' : '/'}${iconUrl}`
    },

    handleIconError(event) {
      // Hide broken image
      if (event.target && event.target.parentElement) {
        event.target.style.display = 'none'
      }
    },

    handleIconFileSelect(event) {
      this.iconUploadError = ''
      this.iconPreviewUrl = null
      this.uploadedIconFile = null

      const file = event.target.files[0]
      if (!file) {
        return
      }

      // Validate file extension (only PNG, JPG, ICO allowed)
      const validExtensions = ['.png', '.jpg', '.jpeg', '.ico']
      const fileExt = '.' + file.name.split('.').pop().toLowerCase()
      if (!validExtensions.includes(fileExt)) {
        this.iconUploadError = `Invalid file type. Allowed: ${validExtensions.join(', ')}`
        event.target.value = '' // Clear the input
        return
      }

      // Validate file size (500KB = 512000 bytes)
      const maxSize = 512000
      if (file.size > maxSize) {
        this.iconUploadError = `File size exceeds maximum allowed size of 500KB`
        event.target.value = '' // Clear the input
        return
      }

      // Create preview URL
      this.uploadedIconFile = file
      const reader = new FileReader()
      reader.onload = (e) => {
        this.iconPreviewUrl = e.target.result
      }
      reader.readAsDataURL(file)
    },

    handleRemoveIcon() {
      this.uploadedIconFile = null
      this.iconPreviewUrl = null
      this.iconUploadError = ''
      this.currentIconUrl = null
      this.hasPngIcon = false
      this.iconRemoved = true

      // Clear file input
      if (this.$refs.iconFileInput) {
        this.$refs.iconFileInput.value = ''
      }
    },

    async uploadIcon() {
      if (!this.uploadedIconFile) {
        return null
      }

      try {
        const formData = new FormData()
        formData.append('file', this.uploadedIconFile)

        const response = await fetch(`${APIHOST}/api/icons/upload/`, {
          method: 'POST',
          headers: {
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include',
          body: formData
        })

        const data = await response.json()

        if (!response.ok) {
          this.iconUploadError = data.error || 'Failed to upload icon'
          return null
        }

        return data.icon_url
      } catch (error) {
        console.error('Error uploading icon:', error)
        this.iconUploadError = `Error: ${error.message}`
        return null
      }
    },

    updateRawJson() {
      if (!this.feature) return

      // Pure GeoJSON features only
      const geometry = this.feature.geometry
      if (!geometry) return

      // Extract only coordinates (or geometries for GeometryCollection)
      if (geometry.type === 'GeometryCollection') {
        // For GeometryCollection, show geometries array
        this.rawJsonInput = JSON.stringify(geometry.geometries || [], null, 2)
      } else {
        // For all other types, show coordinates array
        this.rawJsonInput = JSON.stringify(geometry.coordinates || [], null, 2)
      }
    },

    parseTags(tagsString) {
      if (!tagsString || !tagsString.trim()) return []
      return tagsString.split(',').map(tag => tag.trim()).filter(tag => tag.length > 0)
    },

    hexToRgba(hexColor, opacity) {
      // Convert hex color to RGBA string
      const hex = hexColor.replace('#', '')
      const r = parseInt(hex.slice(0, 2), 16)
      const g = parseInt(hex.slice(2, 4), 16)
      const b = parseInt(hex.slice(4, 6), 16)
      return `rgba(${r}, ${g}, ${b}, ${opacity})`
    },

    extractHexFromColor(colorString) {
      // Extract hex color from rgba string or return hex if already hex
      if (!colorString) return null

      // If it's already a hex color, return it
      if (colorString.startsWith('#')) {
        return colorString
      }

      // If it's rgba, extract RGB values and convert to hex
      const rgbaMatch = colorString.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/)
      if (rgbaMatch) {
        const r = parseInt(rgbaMatch[1])
        const g = parseInt(rgbaMatch[2])
        const b = parseInt(rgbaMatch[3])
        return `#${[r, g, b].map(x => {
          const hex = x.toString(16)
          return hex.length === 1 ? '0' + hex : hex
        }).join('')}`
      }

      return null
    },

    onStrokeColorChange() {
      // When stroke color changes for polygons, automatically update fill to 10% opacity
      if (this.isPolygon) {
        this.formData.fillColor = this.formData.strokeColor
      }
    },

    async handleSubmit() {
      this.errorMessage = ''
      this.iconUploadError = ''
      this.isSaving = true

      try {
        // Get feature ID from original feature properties
        const originalProperties = this.feature.properties || {}
        const featureId = originalProperties.database_id
        if (!featureId) {
          this.errorMessage = 'Feature ID not found. Cannot update feature.'
          this.isSaving = false
          return
        }

        // Upload icon first if a new icon file was selected
        let uploadedIconUrl = null
        if (this.uploadedIconFile) {
          uploadedIconUrl = await this.uploadIcon()
          if (!uploadedIconUrl) {
            // Error already set in uploadIcon method
            this.isSaving = false
            return
          }
        }

        // Build feature from form data and current feature
        // Pure GeoJSON features only
        const geometry = this.feature.geometry
        if (!geometry) {
          this.errorMessage = 'Feature has no geometry'
          this.isSaving = false
          return
        }

        // Create GeoJSON feature format
        let featureData = {
          type: 'Feature',
          geometry: this.feature.geometry,
          properties: this.feature.properties || {}
        }

        // Parse raw JSON if provided to update only the coordinates
        if (this.rawJsonInput && this.rawJsonInput.trim()) {
          try {
            const coordinatesData = JSON.parse(this.rawJsonInput)

            // Validate it's an array
            if (!Array.isArray(coordinatesData)) {
              this.errorMessage = 'Coordinates must be a valid JSON array'
              this.isSaving = false
              return
            }

            // Get the current geometry type
            const currentGeometry = featureData.geometry
            if (!currentGeometry || !currentGeometry.type) {
              this.errorMessage = 'Feature has no valid geometry type'
              this.isSaving = false
              return
            }

            // Update only the coordinates/geometries in the existing geometry
            // Note: MultiPoint and MultiPolygon should not appear (KML converts to GeometryCollection).
            // If they do appear, the backend will error/assert.
            if (currentGeometry.type === 'GeometryCollection') {
              // For GeometryCollection, update geometries array
              featureData.geometry.geometries = coordinatesData
            } else {
              // For all other types, update coordinates array
              featureData.geometry.coordinates = coordinatesData
            }
          } catch (e) {
            this.errorMessage = `Invalid JSON: ${e.message}`
            this.isSaving = false
            return
          }
        }

        // Ensure properties object exists
        if (!featureData.properties) {
          featureData.properties = {}
        }

        // Merge form field values into the feature data
        // Form fields ALWAYS take precedence over raw JSON values
        // This ensures the color picker and other form fields work even when raw JSON is provided
        // Use formData.tags array directly (preferred) or fall back to parsing tagsInput for backward compatibility
        const tagsToUse = this.formData.tags.length > 0 ? this.formData.tags : this.parseTags(this.tagsInput)
        // Tags are already separated - user tags only in tags field
        const formFieldUpdates = {
          name: this.formData.name,
          description: this.formData.description || '',
          tags: tagsToUse
        }

        // Add created date if set
        if (this.formData.created) {
          // Convert datetime-local format to ISO format
          const date = new Date(this.formData.created);
          if (!isNaN(date.getTime())) {
            formFieldUpdates.created = date.toISOString();
          }
        }

        // Handle icon for points
        if (this.isPoint) {
          // If icon was uploaded via old file input, set it
          if (uploadedIconUrl) {
            // Set icon in the first available property name
            formFieldUpdates['icon'] = uploadedIconUrl
            // Uploaded icons can't be recolored, so clear marker-color
            delete formFieldUpdates['marker-color']
          }
          // If icon was selected from picker (preset or uploaded)
          else if (this.currentIconUrl && !this.iconRemoved) {
            // Check if it's a system or user icon
            if (isSystemIcon(this.currentIconUrl) || isUserIcon(this.currentIconUrl)) {
              formFieldUpdates['icon'] = this.currentIconUrl
              // For system icons, save marker-color for recoloring
              if (isSystemIcon(this.currentIconUrl)) {
                formFieldUpdates['marker-color'] = this.formData.markerColor
              } else {
                // User icons can't be recolored
                delete formFieldUpdates['marker-color']
              }
            }
          }
          // If icon was removed (user clicked remove button)
          else if (this.iconRemoved) {
            // Remove icon by setting it to empty string
            formFieldUpdates['icon'] = ''
            // Also remove from other possible icon property names
            formFieldUpdates['icon-href'] = ''
            formFieldUpdates['iconUrl'] = ''
            formFieldUpdates['icon_url'] = ''
            formFieldUpdates['marker-icon'] = ''
            formFieldUpdates['marker-symbol'] = ''
            formFieldUpdates['symbol'] = ''
            // Restore marker-color
            formFieldUpdates['marker-color'] = this.formData.markerColor
          }
          // If no icon and no uploaded icon, use marker color
          else if (!this.hasPngIcon && !uploadedIconUrl) {
            formFieldUpdates['marker-color'] = this.formData.markerColor
          }
        }

        // Update stroke for lines and polygons (stroke-width is normalized on import, don't change it)
        if (this.isLine || this.isPolygon) {
          formFieldUpdates.stroke = this.formData.strokeColor
          // Don't update stroke-width - it's normalized on import
        }

        // Update fill and fill-opacity for polygons
        if (this.isPolygon) {
          // Use the stroke color as the base fill color, with 10% opacity
          formFieldUpdates.fill = this.formData.strokeColor
          formFieldUpdates['fill-opacity'] = 0.1
        }

        // Merge form field updates into properties, with form fields taking precedence
        featureData.properties = {
          ...featureData.properties,
          ...formFieldUpdates
        }

        // Remove database_id from properties before sending (it's only for frontend use)
        delete featureData.properties.database_id

        // Remove system_tags from properties before sending (backend will preserve originals from DB)
        delete featureData.properties.system_tags

        // Remove internal OpenLayers properties (icon caching, etc.)
        delete featureData.properties._iconSrc
        delete featureData.properties._iconFailed
        delete featureData.properties._iconScale

        // Remove nested properties object (artifact of how we store properties in OpenLayers)
        // When we do feature.set('properties', {...}), writeFeatureObject serializes it as a nested object
        delete featureData.properties.properties

        // Remove geojson_hash (internal tracking property)
        delete featureData.properties.geojson_hash

        // Send update request
        const response = await fetch(`${APIHOST}/api/feature/${featureId}/update/`, {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include',
          body: JSON.stringify(featureData)
        })

        const data = await response.json()

        if (!response.ok) {
          this.errorMessage = data.error || 'Failed to update feature'
          this.isSaving = false
          return
        }

        // Fetch the updated feature from the server to get all updated properties including system_tags
        try {
          const fetchResponse = await fetch(`${APIHOST}/api/feature/${featureId}/`, {
            credentials: 'include'
          })

          if (fetchResponse.ok) {
            const fetchData = await fetchResponse.json()
            if (fetchResponse.ok && fetchData.feature) {
              // Get the updated GeoJSON feature from the server
              const geojsonData = fetchData.feature.geojson

              // Use the GeoJSON directly (pure GeoJSON, no OpenLayers compatibility)
              const updatedFeature = geojsonData

              // Ensure properties exist
              if (!updatedFeature.properties) {
                updatedFeature.properties = {}
              }

              // Add the _id to properties
              updatedFeature.properties.database_id = featureId

              // Preserve geojson_hash if available
              if (fetchData.feature.geojson_hash) {
                updatedFeature.geojson_hash = fetchData.feature.geojson_hash
              }

              // Update the feature (pure GeoJSON)
              this.feature.geometry = updatedFeature.geometry
              this.feature.properties = updatedFeature.properties

              // Update icon state if icon was uploaded or removed
              if (this.isPoint) {
                if (uploadedIconUrl) {
                  this.currentIconUrl = uploadedIconUrl
                  this.hasPngIcon = true
                  this.iconRemoved = false
                } else if (this.iconRemoved) {
                  this.currentIconUrl = null
                  this.hasPngIcon = false
                } else if (this.currentIconUrl && (isSystemIcon(this.currentIconUrl) || isUserIcon(this.currentIconUrl))) {
                  // Icon was selected from picker (preset or uploaded) - state already set in handleIconSelected
                  this.hasPngIcon = true
                  this.iconRemoved = false
                }
              }
            }
          }
        } catch (fetchError) {
          console.error('Error fetching updated feature:', fetchError)
          // Fall back to local update if fetch fails
          const properties = this.feature.properties || {}
          Object.assign(properties, formFieldUpdates)
          properties.database_id = featureId
          this.feature.properties = properties
        }

        // Close dialog immediately on success (no message)
        this.isSaving = false
        this.$emit('saved')

      } catch (error) {
        console.error('Error updating feature:', error)
        this.errorMessage = `Error: ${error.message}`
        this.isSaving = false
      }
    },

    async handleDelete() {
      // Get feature ID
      const originalProperties = this.feature.properties || {}
      const featureId = originalProperties.database_id
      if (!featureId) {
        this.errorMessage = 'Feature ID not found. Cannot delete feature.'
        return
      }

      // Show confirmation dialog
      const featureName = originalProperties.name || 'this feature'
      const confirmed = window.confirm(`Are you sure you want to delete "${featureName}"? This action cannot be undone.`)

      if (!confirmed) {
        return
      }

      this.errorMessage = ''
      this.successMessage = ''
      this.isSaving = true

      try {
        // Send delete request
        const response = await fetch(`${APIHOST}/api/feature/${featureId}/delete/`, {
          method: 'DELETE',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include'
        })

        const data = await response.json()

        if (!response.ok) {
          this.errorMessage = data.error || 'Failed to delete feature'
          this.isSaving = false
          return
        }

        // Emit deleted event
        this.$emit('deleted')

      } catch (error) {
        console.error('Error deleting feature:', error)
        this.errorMessage = `Error: ${error.message}`
        this.isSaving = false
      }
    },

    handleIconSelected(iconUrl) {
      // Set the selected icon URL
      this.uploadedIconFile = null
      this.iconPreviewUrl = null
      this.iconUploadError = ''

      // Handle system and user icons
      if (isSystemIcon(iconUrl)) {
        // System icon - set it directly
        this.currentIconUrl = iconUrl
        this.hasPngIcon = true
        this.iconRemoved = false
      } else if (isUserIcon(iconUrl)) {
        // User icon
        this.currentIconUrl = iconUrl
        this.hasPngIcon = true
        this.iconRemoved = false
      }
    },
    handleIconSelectedFromSelector(event) {
      // Wrapper to bridge IconSelector component with existing logic
      this.handleIconSelected(event.iconUrl)
    },
    getCsrfToken() {
      // Get CSRF token from cookies
      const name = 'csrftoken'
      let cookieValue = null
      if (document.cookie && document.cookie !== '') {
        const cookies = document.cookie.split(';')
        for (let i = 0; i < cookies.length; i++) {
          const cookie = cookies[i].trim()
          if (cookie.substring(0, name.length + 1) === (name + '=')) {
            cookieValue = decodeURIComponent(cookie.substring(name.length + 1))
            break
          }
        }
      }
      return cookieValue || ''
    },
    openReplacementDialog() {
      if (!this.featureId) {
        this.errorMessage = 'Feature ID not found. Cannot update spatial data.'
        return
      }
      this.replacementDialogOpen = true
    },
    closeReplacementDialog() {
      this.replacementDialogOpen = false
    },
    handleReplacementApplied() {
      // Refresh the feature data after replacement is applied
      // The parent component should handle refreshing the map
      this.$emit('saved')
      this.closeReplacementDialog()
    },
    handleHideToggle() {
      if (!this.featureId) {
        return
      }
      this.$emit('visibility-change', {
        featureId: this.featureId,
        hidden: this.hideOnMainMap
      })
    },
    openCoordinatesDialog() {
      this.coordinatesDialogOpen = true
    },
    closeCoordinatesDialog() {
      this.coordinatesDialogOpen = false
    },
    formatDateForInput(dateString) {
      if (!dateString) return '';
      // Convert date string to datetime-local format (YYYY-MM-DDTHH:MM)
      const date = new Date(dateString);
      if (isNaN(date.getTime())) return '';
      return date.toISOString().slice(0, 16);
    },
    updateDate(event) {
      this.formData.created = event.target.value;
    }
  }
}
</script>

<style scoped>
.tag-input {
  text-transform: lowercase;
}
</style>

