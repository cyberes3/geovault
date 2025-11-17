<template>
  <div v-if="feature" class="absolute bottom-4 right-4 bg-white rounded-lg shadow-lg border border-gray-200 z-10 max-w-md w-96">
    <div class="p-4">
      <div class="flex items-start justify-between mb-4">
        <h3 class="text-lg font-semibold text-gray-900">Edit Feature</h3>
        <button
          @click="$emit('cancel')"
          class="ml-2 text-gray-400 hover:text-gray-600"
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
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
            required
          />
        </div>

        <!-- Tags Field -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Tags (comma-separated)</label>
          <input
            v-model="tagsInput"
            type="text"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
            placeholder="tag1, tag2, tag3"
          />
        </div>

        <!-- Description Field -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Description</label>
          <textarea
            v-model="formData.description"
            rows="3"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
          ></textarea>
        </div>

        <!-- Icon Color Field (only for points with no PNG icon) -->
        <div v-if="isPoint && !hasPngIcon">
          <label class="block text-sm font-medium text-gray-700 mb-1">Icon Color</label>
          <div class="flex items-center space-x-2">
            <input
              v-model="formData.markerColor"
              type="color"
              class="h-10 w-20 border border-gray-300 rounded cursor-pointer"
            />
            <input
              v-model="formData.markerColor"
              type="text"
              class="flex-1 px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
              placeholder="#ff0000"
              pattern="^#[0-9A-Fa-f]{6}$"
            />
          </div>
        </div>

        <!-- Line/Polygon Color and Width Fields -->
        <div v-if="isLine || isPolygon">
          <label class="block text-sm font-medium text-gray-700 mb-1">Border Color</label>
          <div class="flex items-center space-x-2">
            <input
              v-model="formData.strokeColor"
              type="color"
              class="h-10 w-20 border border-gray-300 rounded cursor-pointer"
              @input="onStrokeColorChange"
            />
            <input
              v-model="formData.strokeColor"
              type="text"
              class="flex-1 px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
              placeholder="#ff0000"
              pattern="^#[0-9A-Fa-f]{6}$"
              @input="onStrokeColorChange"
            />
          </div>
        </div>

        <!-- Width Field (for lines and polygons) -->
        <div v-if="isLine || isPolygon">
          <label class="block text-sm font-medium text-gray-700 mb-1">Border Width</label>
          <input
            v-model.number="formData.strokeWidth"
            type="number"
            min="1"
            max="20"
            step="1"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
          />
        </div>

        <!-- Raw JSON Field (Coordinates Only) -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Coordinates</label>
          <textarea
            v-model="rawJsonInput"
            rows="6"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 font-mono text-xs"
            placeholder="[]"
          ></textarea>
        </div>

        <!-- Error Message -->
        <div v-if="errorMessage" class="p-3 bg-red-50 border border-red-200 rounded-md">
          <p class="text-sm text-red-800">{{ errorMessage }}</p>
        </div>

        <!-- Success Message -->
        <div v-if="successMessage" class="p-3 bg-green-50 border border-green-200 rounded-md">
          <p class="text-sm text-green-800">{{ successMessage }}</p>
        </div>

        <!-- Action Buttons -->
        <div class="flex justify-end space-x-2 pt-2">
          <button
            type="button"
            @click="$emit('cancel')"
            class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
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
  </div>
</template>

<script>
import {APIHOST} from '@/config.js'
import {GeoJSON} from 'ol/format'
import { getProtectedTags } from '@/utils/configService.js'
import { filterProtectedTags } from '@/utils/tagUtils.js'

export default {
  name: 'FeatureEditBox',
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
      successMessage: '',
      protectedTags: []
    }
  },
  computed: {
    geometryType() {
      if (!this.feature) return null
      const geometry = this.feature.getGeometry()
      if (!geometry) return null
      return geometry.getType()
    },
    isPoint() {
      return this.geometryType === 'Point' || this.geometryType === 'MultiPoint'
    },
    isLine() {
      return this.geometryType === 'LineString' || this.geometryType === 'MultiLineString'
    },
    isPolygon() {
      return this.geometryType === 'Polygon' || this.geometryType === 'MultiPolygon'
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

      // Initialize raw JSON
      this.updateRawJson()
    },

    checkForPngIcon(properties) {
      const iconPropertyNames = ['icon', 'icon-href', 'iconUrl', 'icon_url', 'marker-icon', 'marker-symbol', 'symbol']

      for (const propName of iconPropertyNames) {
        if (properties[propName] && typeof properties[propName] === 'string') {
          const iconUrl = properties[propName].trim()
          // Check if it's a PNG icon (ends with .png or starts with /api/data/icons/)
          if (iconUrl.endsWith('.png') || iconUrl.startsWith('/api/data/icons/')) {
            return true
          }
        }
      }
      return false
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
      this.successMessage = ''
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

        // Update marker color for points if no PNG icon
        if (this.isPoint && !this.hasPngIcon) {
          formFieldUpdates['marker-color'] = this.formData.markerColor
        }

        // Update stroke and stroke-width for lines and polygons
        if (this.isLine || this.isPolygon) {
          formFieldUpdates.stroke = this.formData.strokeColor
          formFieldUpdates['stroke-width'] = this.formData.strokeWidth
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
        // Update properties with the form field updates (same as what we sent to the server)
        Object.assign(properties, formFieldUpdates)
        // Restore _id since we removed it before sending
        properties._id = featureId
        this.feature.set('properties', properties)
        
        // Trigger feature change to update any listeners
        this.feature.changed()

        this.successMessage = 'Feature updated successfully!'
        this.isSaving = false

        // Emit saved event after a short delay
        setTimeout(() => {
          this.$emit('saved')
        }, 1000)

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

