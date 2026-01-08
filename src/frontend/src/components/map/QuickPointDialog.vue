<template>
  <BaseModal
    :is-open="isOpen"
    title="Add Quick Point"
    max-width="2xl"
    @close="handleClose"
  >
    <div class="p-4 space-y-4">
      <!-- Name Field -->
      <div>
            <label class="block text-xs font-bold text-gray-500 uppercase mb-1">
              Name <span class="text-red-500">*</span>
            </label>
            <input
              v-model="featureName"
              type="text"
              :disabled="isSaving"
              class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
              placeholder="Enter a name for the point"
              required
            />
          </div>

          <!-- Tags Field -->
          <div>
            <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Tags</label>
            <TagPicker
              v-model:tags="tags"
              :available-tags="availableTags"
              :disabled="isSaving"
              :show-label="false"
            />
          </div>

          <!-- Description Field -->
          <div>
            <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Description</label>
            <textarea
              v-model="description"
              rows="3"
              :disabled="isSaving"
              class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
            ></textarea>
          </div>

          <!-- Icon Section -->
          <IconSelector
            :icon-url="iconUrl"
            :disabled="isSaving"
            :show-remove="true"
            size="sm"
            @icon-selected="handleIconSelected"
            @icon-removed="handleIconRemoved"
          />

          <!-- Icon Color -->
          <div>
            <label class="block text-xs font-bold text-gray-500 uppercase mb-1">Icon Color</label>
            <ColorPicker
              v-model="markerColor"
              :disabled="isSaving || isCustomIcon"
              size="sm"
            />
          </div>

          <!-- Coordinates Input -->
          <div>
            <label class="block text-xs font-bold text-gray-500 uppercase mb-1">
              Coordinates <span class="text-red-500">*</span>
            </label>
            <input
              v-model="coordinatesInput"
              type="text"
              :disabled="isSaving"
              placeholder="37.7749, -122.4194"
              class="w-full px-2 py-1.5 text-sm border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100 disabled:cursor-not-allowed"
              @input="validateCoordinates"
            />
            <p class="mt-1 text-xs text-gray-500">
              Enter as: <code class="bg-gray-100 px-1 rounded">latitude, longitude</code>
            </p>
            <p v-if="coordinateError" class="mt-1 text-xs text-red-600">{{ coordinateError }}</p>
          </div>

      <!-- Error Message -->
      <div v-if="errorMessage" class="p-3 bg-red-50 border border-red-200 rounded-md">
        <p class="text-sm text-red-800">{{ errorMessage }}</p>
      </div>
    </div>

    <template #footer>
      <button
        type="button"
        @click="handleClose"
        :disabled="isSaving"
        class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
        title="Cancel"
      >
        Cancel
      </button>
      <button
        type="button"
        @click="handleSave"
        :disabled="isSaving || !isValid"
        class="px-4 py-2 text-sm font-medium text-white bg-blue-600 border border-transparent rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
        title="Create point"
      >
        {{ isSaving ? 'Creating...' : 'Create Point' }}
      </button>
    </template>
  </BaseModal>
</template>

<script>
import {APIHOST} from '@/config.js'
import BaseModal from '@/components/parts/BaseModal.vue'
import TagPicker from '@/components/parts/TagPicker.vue'
import ColorPickerElement from '@/components/parts/ColorPickerElement.vue'
import IconSelector from '@/components/parts/IconSelector.vue'
import { parseCoordinates } from '@/utils/coordinateParser.js'

// Helper functions for icon type checking
function isSystemIcon(iconUrl) {
  return iconUrl && iconUrl.includes('/api/icons/system/')
}

function isUserIcon(iconUrl) {
  return iconUrl && iconUrl.includes('/api/icons/user/')
}

export default {
  name: 'QuickPointDialog',
  components: {
    BaseModal,
    TagPicker,
    ColorPicker: ColorPickerElement,
    IconSelector
  },
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    availableTags: {
      type: Array,
      default: () => []
    }
  },
  emits: ['close', 'created'],
  data() {
    return {
      featureName: '',
      description: '',
      tags: [],
      markerColor: '#ff0000',
      iconUrl: null,
      coordinatesInput: '',
      latitude: null,
      longitude: null,
      coordinateError: '',
      isSaving: false,
      errorMessage: ''
    }
  },
  computed: {
    isValid() {
      return this.featureName.trim() !== '' && 
             this.latitude !== null && 
             this.longitude !== null && 
             !this.coordinateError
    },
    isCustomIcon() {
      // Custom icon is a user-uploaded icon or any icon that's not a system icon
      if (!this.iconUrl) return false
      return isUserIcon(this.iconUrl) || !isSystemIcon(this.iconUrl)
    },
    hasUnsavedData() {
      // Check if user has entered any data
      return this.featureName.trim() !== '' ||
             this.description.trim() !== '' ||
             this.tags.length > 0 ||
             this.markerColor !== '#ff0000' ||
             this.iconUrl !== null ||
             this.coordinatesInput.trim() !== ''
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.reset()
      }
    }
  },
  methods: {
    reset() {
      this.featureName = ''
      this.description = ''
      this.tags = []
      this.markerColor = '#ff0000'
      this.iconUrl = null
      this.coordinatesInput = ''
      this.latitude = null
      this.longitude = null
      this.coordinateError = ''
      this.isSaving = false
      this.errorMessage = ''
    },
    handleClose() {
      if (this.isSaving) {
        return
      }
      
      // Show confirmation if there's unsaved data
      if (this.hasUnsavedData) {
        const confirmed = window.confirm('You have unsaved changes. Are you sure you want to close?')
        if (!confirmed) {
          return
        }
      }
      
      this.$emit('close')
    },
    validateCoordinates() {
      this.coordinateError = ''
      this.latitude = null
      this.longitude = null
      
      const input = this.coordinatesInput.trim()
      if (!input) {
        return
      }
      
      // Use the same coordinate parsing logic as search places input
      const coordinates = parseCoordinates(input)
      if (coordinates) {
        // Successfully parsed coordinates
        this.latitude = coordinates.lat
        this.longitude = coordinates.lng
      } else {
        // Failed to parse - set error message
        this.coordinateError = 'Invalid coordinate format'
      }
    },
    handleIconSelected(event) {
      this.iconUrl = event.iconUrl
    },
    handleIconRemoved() {
      this.iconUrl = null
    },
    async handleSave() {
      if (!this.isValid) {
        this.errorMessage = 'Please provide a name and valid coordinates'
        return
      }

      this.errorMessage = ''
      this.isSaving = true

      try {
        // Prepare request payload
        const payload = {
          latitude: this.latitude,
          longitude: this.longitude,
          name: this.featureName.trim(),
          marker_color: this.markerColor
        }
        
        // Add optional fields
        if (this.description.trim()) {
          payload.description = this.description.trim()
        }
        
        if (this.tags.length > 0) {
          payload.tags = this.tags
        }
        
        if (this.iconUrl) {
          payload.icon = this.iconUrl
        }

        // Call backend API
        const response = await fetch(`${APIHOST}/api/features/quick-point/create/`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': this.getCsrfToken()
          },
          credentials: 'include',
          body: JSON.stringify(payload)
        })

        const data = await response.json()

        if (!response.ok) {
          this.errorMessage = data.error || data.msg || 'Failed to create point'
          this.isSaving = false
          return
        }

        // Success - emit event with the created feature and close
        this.$emit('created', data.feature)
        this.handleClose()
      } catch (error) {
        console.error('Error creating quick point:', error)
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

