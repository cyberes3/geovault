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

        <!-- Icon Color Field (only if no PNG icon) -->
        <div v-if="!hasPngIcon">
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

        <!-- Raw JSON Field -->
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Raw JSON</label>
          <textarea
            v-model="rawJsonInput"
            rows="8"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 font-mono text-xs"
            placeholder='{"type": "Feature", ...}'
          ></textarea>
          <p class="mt-1 text-xs text-gray-500">Edit the full GeoJSON feature (will be validated on save)</p>
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

export default {
  name: 'FeatureEditBox',
  props: {
    feature: {
      type: Object,
      default: null
    }
  },
  emits: ['cancel', 'saved'],
  data() {
    return {
      formData: {
        name: '',
        description: '',
        tags: [],
        markerColor: '#ff0000'
      },
      tagsInput: '',
      rawJsonInput: '',
      hasPngIcon: false,
      isSaving: false,
      errorMessage: '',
      successMessage: ''
    }
  },
  mounted() {
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
      this.formData.tags = Array.isArray(properties.tags) ? properties.tags : []
      this.tagsInput = this.formData.tags.join(', ')
      this.formData.markerColor = properties['marker-color'] || '#ff0000'

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

      // Convert OpenLayers geometry to GeoJSON
      const format = new GeoJSON()
      const featureJson = format.writeFeatureObject(this.feature, {
        featureProjection: 'EPSG:3857',
        dataProjection: 'EPSG:4326'
      })

      this.rawJsonInput = JSON.stringify(featureJson, null, 2)
    },

    parseTags(tagsString) {
      if (!tagsString || !tagsString.trim()) return []
      return tagsString.split(',').map(tag => tag.trim()).filter(tag => tag.length > 0)
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

        // Parse raw JSON if provided, otherwise build from form data
        let featureData
        if (this.rawJsonInput && this.rawJsonInput.trim()) {
          try {
            featureData = JSON.parse(this.rawJsonInput)
            // Ensure it's a proper Feature object
            if (featureData.type !== 'Feature') {
              this.errorMessage = 'JSON must be a GeoJSON Feature object'
              this.isSaving = false
              return
            }
            
            // Clean up nested properties (flatten if needed)
            if (featureData.properties && typeof featureData.properties === 'object') {
              // If properties contains another properties object, flatten it
              while (featureData.properties.properties && typeof featureData.properties.properties === 'object') {
                const nestedProps = featureData.properties.properties
                // Merge nested properties into parent, with nested taking precedence
                featureData.properties = { ...featureData.properties, ...nestedProps }
                delete featureData.properties.properties
              }
            }
          } catch (e) {
            this.errorMessage = `Invalid JSON: ${e.message}`
            this.isSaving = false
            return
          }
        } else {
          // Build feature from form data
          const geometry = this.feature.getGeometry()
          if (!geometry) {
            this.errorMessage = 'Feature has no geometry'
            this.isSaving = false
            return
          }

          const format = new GeoJSON()
          const featureJson = format.writeFeatureObject(this.feature, {
            featureProjection: 'EPSG:3857',
            dataProjection: 'EPSG:4326'
          })

          featureData = featureJson
        }

        // Ensure properties object exists
        if (!featureData.properties) {
          featureData.properties = {}
        }

        // Merge form field values into the feature data
        // Form fields ALWAYS take precedence over raw JSON values
        // This ensures the color picker and other form fields work even when raw JSON is provided
        const formFieldUpdates = {
          name: this.formData.name,
          description: this.formData.description || '',
          tags: this.parseTags(this.tagsInput)
        }

        // Update marker color if no PNG icon
        if (!this.hasPngIcon) {
          formFieldUpdates['marker-color'] = this.formData.markerColor
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

        this.successMessage = 'Feature updated successfully!'
        
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

