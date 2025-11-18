<template>
  <div v-if="feature" class="absolute bottom-4 right-4 bg-white rounded-lg shadow-lg border border-gray-200 z-10 max-w-md w-96">
    <div class="p-4">
      <div class="flex items-start justify-between mb-4">
        <h3 class="text-lg font-semibold text-gray-900">Edit Feature</h3>
        <button
          @click="$emit('cancel')"
          :disabled="isSaving"
          class="ml-2 text-gray-400 hover:text-gray-600 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
          </svg>
        </button>
      </div>

      <form @submit.prevent="handleSubmit" class="space-y-4">
        <!-- Name Field -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Name</label>
          <input
            v-model="formData.name"
            type="text"
            :disabled="isSaving"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
            required
          />
        </div>

        <!-- Tags Field -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Tags (comma-separated)</label>
          <input
            v-model="tagsInput"
            type="text"
            :disabled="isSaving"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
            placeholder="tag1, tag2, tag3"
          />
        </div>

        <!-- Description Field -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Description</label>
          <textarea
            v-model="formData.description"
            rows="3"
            :disabled="isSaving"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
          ></textarea>
        </div>

        <!-- Icon Section (for points) -->
        <div v-if="isPoint">
          <label class="block text-sm font-medium text-gray-700 mb-1">Icon</label>
          
          <!-- Current Icon Preview -->
          <div v-if="hasPngIcon && currentIconUrl" class="mb-2">
            <div class="flex items-center space-x-2">
              <img 
                :src="resolveIconUrl(currentIconUrl)" 
                alt="Current icon" 
                class="w-8 h-8 object-contain border border-gray-300 rounded"
                @error="handleIconError"
              />
              <span class="text-sm text-gray-600">Current icon</span>
              <button
                type="button"
                @click="handleRemoveIcon"
                :disabled="isSaving"
                class="ml-auto text-sm text-red-600 hover:text-red-800 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                Remove
              </button>
            </div>
          </div>

          <!-- Choose Icon Button -->
          <div class="space-y-2">
            <button
              type="button"
              @click="openIconPicker"
              :disabled="isSaving"
              class="w-full px-4 py-2 text-sm font-medium text-white bg-blue-600 border border-transparent rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Choose Icon
            </button>
            <p class="text-xs text-gray-500">
              Select from preset icons or upload your own
            </p>
          </div>

          <!-- Icon Preview (for newly selected file) -->
          <div v-if="iconPreviewUrl" class="mt-2">
            <img 
              :src="iconPreviewUrl" 
              alt="Icon preview" 
              class="w-8 h-8 object-contain border border-gray-300 rounded"
            />
            <p class="text-xs text-gray-600 mt-1">Preview</p>
          </div>

          <!-- Icon Upload Error -->
          <div v-if="iconUploadError" class="mt-2 p-2 bg-red-50 border border-red-200 rounded-md">
            <p class="text-xs text-red-800">{{ iconUploadError }}</p>
          </div>
        </div>

        <!-- Icon Color Field (for points) -->
        <div v-if="isPoint">
          <label class="block text-sm font-medium text-gray-700 mb-1">Icon Color</label>
          <div class="flex items-center space-x-2">
            <input
              v-model="formData.markerColor"
              type="color"
              :disabled="isSaving"
              class="h-10 w-20 border border-gray-300 rounded cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
            />
            <input
              v-model="formData.markerColor"
              type="text"
              :disabled="isSaving"
              class="flex-1 px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
              placeholder="#ff0000"
              pattern="^#[0-9A-Fa-f]{6}$"
            />
          </div>
          <p v-if="hasPngIcon && isBuiltInIcon" class="text-xs text-gray-500 mt-1">
            Recolors black pixels in the icon
          </p>
          <p v-else-if="!hasPngIcon" class="text-xs text-gray-500 mt-1">
            Color for default circle icon
          </p>
        </div>

        <!-- Line/Polygon Color Field -->
        <div v-if="isLine || isPolygon">
          <label class="block text-sm font-medium text-gray-700 mb-1">Border Color</label>
          <div class="flex items-center space-x-2">
            <input
              v-model="formData.strokeColor"
              type="color"
              :disabled="isSaving"
              class="h-10 w-20 border border-gray-300 rounded cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
              @input="onStrokeColorChange"
            />
            <input
              v-model="formData.strokeColor"
              type="text"
              :disabled="isSaving"
              class="flex-1 px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
              placeholder="#ff0000"
              pattern="^#[0-9A-Fa-f]{6}$"
              @input="onStrokeColorChange"
            />
          </div>
        </div>

        <!-- Raw JSON Field (Coordinates Only) -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Coordinates</label>
          <textarea
            v-model="rawJsonInput"
            rows="6"
            :disabled="isSaving"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 font-mono text-xs disabled:bg-gray-100 disabled:cursor-not-allowed"
            placeholder="[]"
          ></textarea>
        </div>

        <!-- Error Message -->
        <div v-if="errorMessage" class="p-3 bg-red-50 border border-red-200 rounded-md">
          <p class="text-sm text-red-800">{{ errorMessage }}</p>
        </div>

        <!-- Action Buttons -->
        <div class="flex justify-end space-x-2 pt-2">
          <button
            type="button"
            @click="$emit('cancel')"
            :disabled="isSaving"
            class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Cancel
          </button>
          <button
            type="button"
            @click="handleDelete"
            :disabled="isSaving"
            class="px-4 py-2 text-sm font-medium text-white bg-red-600 border border-transparent rounded-md hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Delete
          </button>
          <button
            type="submit"
            :disabled="isSaving"
            class="px-4 py-2 text-sm font-medium text-white bg-blue-600 border border-transparent rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            {{ isSaving ? 'Saving...' : 'Save' }}
          </button>
        </div>
      </form>
    </div>

    <!-- Icon Picker Dialog -->
    <IconPickerDialog
      :is-open="iconPickerOpen"
      @close="closeIconPicker"
      @icon-selected="handleIconSelected"
    />
  </div>
</template>

<script>
import {APIHOST} from '@/config.js'
import {GeoJSON} from 'ol/format'
import { getProtectedTags } from '@/utils/configService.js'
import { filterProtectedTags, isProtectedTag } from '@/utils/tagUtils.js'
import IconPickerDialog from './IconPickerDialog.vue'

// Helper functions for icon type checking
function isSystemIcon(iconUrl) {
  return iconUrl.startsWith('/api/data/icons/system/')
}

function isUserIcon(iconUrl) {
  return iconUrl.startsWith('/api/data/icons/user/')
}

export default {
  name: 'FeatureEditBox',
  components: {
    IconPickerDialog
  },
  props: {
    feature: {
      type: Object,
      default: null
    }
  },
  emits: ['cancel', 'saved', 'deleted'],
  data() {
    return {
      formData: {
        name: '',
        description: '',
        tags: [],
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
      protectedTags: [],
      uploadedIconFile: null,
      iconPreviewUrl: null,
      iconUploadError: '',
      currentIconUrl: null,
      iconRemoved: false,
      iconPickerOpen: false
    }
  },
  computed: {
    geometryType() {
      if (!this.feature) return null
      const geometry = this.feature.getGeometry()
      if (!geometry) return null
      return geometry.getType()
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
    }
  },
  async mounted() {
    // Fetch protected tags on mount
    this.protectedTags = await getProtectedTags()
    this.initializeForm()
  },
  watch: {
    feature: {
      handler() {
        this.initializeForm()
      },
      immediate: true
    }
  },
  methods: {
    initializeForm() {
      if (!this.feature) return

      const properties = this.feature.get('properties') || {}

      // Initialize form data
      this.formData.name = properties.name || ''
      this.formData.description = properties.description || ''
      // Filter protected tags when initializing
      const allTags = Array.isArray(properties.tags) ? properties.tags : []
      this.formData.tags = filterProtectedTags(allTags, this.protectedTags)
      this.tagsInput = this.formData.tags.join(', ')
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

        const response = await fetch(`${APIHOST}/api/data/icons/upload/`, {
          method: 'POST',
          headers: {
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include',
          body: formData
        })

        const data = await response.json()

        if (!response.ok || !data.success) {
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

      const geometry = this.feature.getGeometry()
      if (!geometry) return

      // Convert OpenLayers geometry to GeoJSON (geometry only, not full feature)
      const format = new GeoJSON()
      const geometryJson = format.writeGeometryObject(geometry, {
        featureProjection: 'EPSG:3857',
        dataProjection: 'EPSG:4326'
      })

      // Extract only coordinates (or geometries for GeometryCollection)
      if (geometryJson.type === 'GeometryCollection') {
        // For GeometryCollection, show geometries array
        this.rawJsonInput = JSON.stringify(geometryJson.geometries || [], null, 2)
      } else {
        // For all other types, show coordinates array
        this.rawJsonInput = JSON.stringify(geometryJson.coordinates || [], null, 2)
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
        const originalProperties = this.feature.get('properties') || {}
        const featureId = originalProperties._id
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
        const geometry = this.feature.getGeometry()
        if (!geometry) {
          this.errorMessage = 'Feature has no geometry'
          this.isSaving = false
          return
        }

        const format = new GeoJSON()
        let featureData = format.writeFeatureObject(this.feature, {
          featureProjection: 'EPSG:3857',
          dataProjection: 'EPSG:4326'
        })

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
        const parsedTags = this.parseTags(this.tagsInput)
        // Filter protected tags before sending
        const filteredTags = filterProtectedTags(parsedTags, this.protectedTags)
        const formFieldUpdates = {
          name: this.formData.name,
          description: this.formData.description || '',
          tags: filteredTags
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

        // Remove _id from properties before sending (it's only for frontend use)
        delete featureData.properties._id

        // Send update request
        const response = await fetch(`${APIHOST}/api/data/feature/${featureId}/update/`, {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include',
          body: JSON.stringify(featureData)
        })

        const data = await response.json()

        if (!response.ok || !data.success) {
          this.errorMessage = data.error || 'Failed to update feature'
          this.isSaving = false
          return
        }

        // Update the feature object's properties immediately so reopening the dialog shows correct values
        const properties = this.feature.get('properties') || {}
        
        // Preserve protected tags from the original feature (same logic as backend)
        // Use originalProperties to get tags before any modifications
        const originalTags = Array.isArray(originalProperties.tags) ? originalProperties.tags : []
        const protectedTags = originalTags.filter(tag => isProtectedTag(tag, this.protectedTags))
        
        // Combine filtered user tags with preserved protected tags (same as backend does)
        const tagsWithProtected = [...filteredTags, ...protectedTags]
        
        // Update properties with the form field updates, but use tags with protected tags preserved
        const updatedFormFieldUpdates = {
          ...formFieldUpdates,
          tags: tagsWithProtected
        }
        Object.assign(properties, updatedFormFieldUpdates)
        // Restore _id since we removed it before sending
        properties._id = featureId
        this.feature.set('properties', properties)
        
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
        
        // Trigger feature change to update any listeners
        this.feature.changed()

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
      const originalProperties = this.feature.get('properties') || {}
      const featureId = originalProperties._id
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
        const response = await fetch(`${APIHOST}/api/data/feature/${featureId}/delete/`, {
          method: 'DELETE',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include'
        })

        const data = await response.json()

        if (!response.ok || !data.success) {
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

    openIconPicker() {
      this.iconPickerOpen = true
    },
    closeIconPicker() {
      this.iconPickerOpen = false
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
    }
  }
}
</script>

