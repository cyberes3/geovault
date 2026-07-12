<template>
  <BaseModal
    :is-open="isOpen"
    title="Edit Coordinates"
    max-width="2xl"
    @close="handleClose"
  >
    <div class="flex flex-col h-full min-h-0 p-4">
      <div class="flex-shrink-0 space-y-2 mb-4">
        <div class="flex items-center justify-between">
          <label class="block text-sm font-medium text-gray-700">
            Coordinates (JSON array)
          </label>
          <BaseButton
            type="button"
            @click="formatJson"
            :disabled="!canFormat"
            variant="white"
            size="xs"
            :title="canFormat ? 'Format JSON' : 'Cannot format: Invalid JSON'"
          >
            Format JSON
          </BaseButton>
        </div>
        <div class="p-2 bg-blue-50 border border-blue-200 rounded-md">
          <p class="text-xs text-blue-800">
            GeoJSON coordinates use <strong>[longitude, latitude]</strong> order (backwards from the common [latitude, longitude] format). Elevation is in meters.
          </p>
        </div>
      </div>
      <div class="flex-1 min-h-0 rounded-lg overflow-hidden bg-gray-50">
        <CodeEditor
          v-model="localCoordinates"
          :read-only="disabled"
          :languages="[['json', 'JSON']]"
          :line-nums="false"
          :wrap="false"
          :header="false"
          :copy-code="false"
          :display-language="false"
          theme="github"
          font-size="13px"
          width="100%"
          height="100%"
          padding="12px"
          border-radius="8px"
          tab-spaces="2"
        />
      </div>
    </div>

    <template #footer-left>
      <p v-if="errorMessage" class="text-sm text-red-600 truncate">{{ errorMessage }}</p>
      <p v-else-if="validationError" class="text-sm text-red-600 truncate">{{ validationError }}</p>
      <p v-else-if="isValid && geometryType" class="text-sm text-green-600">✓ Coordinates are valid</p>
    </template>
    <template #footer>
      <BaseButton
        type="button"
        @click="handleClose"
        :disabled="disabled"
        variant="white"
        size="sm"
        title="Close"
      >
        Close
      </BaseButton>
      <BaseButton
        type="button"
        @click="handleSave"
        :disabled="!canSave"
        variant="primary"
        color="blue"
        size="sm"
        :title="canSave ? 'Save Coordinates' : (validationError || 'Invalid coordinates')"
      >
        Save
      </BaseButton>
    </template>
  </BaseModal>
</template>

<script>
import BaseModal from '@/components/parts/BaseModal.vue'
import BaseButton from '@/components/parts/BaseButton.vue'
import { restoreElevationInGeometry } from '@/utils/elevationUtils.js'
import { validateCoordinates } from '@/utils/geo/coordinates'
import CodeEditor from 'simple-code-editor'

export default {
  name: 'CoordinatesDialog',
  components: {
    BaseModal,
    BaseButton,
    CodeEditor
  },
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    coordinates: {
      type: String,
      default: ''
    },
    feature: {
      type: Object,
      default: null
    },
    geometryType: {
      type: String,
      default: ''
    },
    disabled: {
      type: Boolean,
      default: false
    }
  },
  emits: ['close', 'save'],
  data() {
    return {
      localCoordinates: '',
      errorMessage: '',
      validationError: null,
      validationTimeout: null
    }
  },
  computed: {
    isValid() {
      if (!this.localCoordinates || !this.localCoordinates.trim()) {
        return false
      }
      try {
        const parsed = JSON.parse(this.localCoordinates)
        if (!Array.isArray(parsed)) {
          return false
        }

        // Reject empty arrays
        if (parsed.length === 0) {
          return false
        }

        // If we have a geometry type, validate coordinates
        // Note: validationError is set by validateCoordinates() method
        // This computed just checks if the structure is valid
        if (this.geometryType) {
          const validation = validateCoordinates(parsed, this.geometryType)
          return validation.valid
        }

        // No geometry type, just check if it's a valid non-empty array
        return true
      } catch (e) {
        return false
      }
    },
    canSave() {
      return this.isValid && !this.validationError && !this.disabled
    },
    canFormat() {
      // Disable format button if JSON is invalid
      if (this.validationError && this.validationError.includes('Invalid JSON')) {
        return false
      }
      if (this.errorMessage && this.errorMessage.includes('Invalid JSON')) {
        return false
      }
      return !this.disabled
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        // If coordinates prop is provided and valid, use it (preserves user edits)
        // Otherwise, restore elevation from feature
        let coordsToShow = this.coordinates || ''

        // Check if coordinates prop is valid JSON
        let hasValidCoordinates = false
        if (coordsToShow && coordsToShow.trim()) {
          try {
            const parsed = JSON.parse(coordsToShow)
            if (Array.isArray(parsed)) {
              hasValidCoordinates = true
            }
          } catch (e) {
            // Invalid JSON, will restore from feature
          }
        }

        // Only restore from feature if coordinates prop is empty or invalid
        if (!hasValidCoordinates && this.feature && this.feature.geometry && this.feature.properties) {
          // Restore elevation in geometry before extracting coordinates
          const featureWithElevation = restoreElevationInGeometry({
            type: 'Feature',
            geometry: this.feature.geometry,
            properties: this.feature.properties
          })

          const geometry = featureWithElevation.geometry
          if (geometry) {
            if (geometry.type === 'GeometryCollection') {
              coordsToShow = JSON.stringify(geometry.geometries || [], null, 2)
            } else {
              coordsToShow = JSON.stringify(geometry.coordinates || [], null, 2)
            }
          }
        }

        this.localCoordinates = coordsToShow
        this.errorMessage = ''
        this.validationError = null
        // Validate after setting coordinates
        this.$nextTick(() => {
          this.validateCoordinates()
        })
      }
    },
    coordinates(newVal) {
      if (this.isOpen) {
        // Only update if we have valid coordinates (user edits)
        // Don't overwrite if user is currently editing
        if (newVal && newVal.trim()) {
          try {
            const parsed = JSON.parse(newVal)
            if (Array.isArray(parsed)) {
              this.localCoordinates = newVal
              this.validateCoordinates()
            }
          } catch (e) {
            // Invalid JSON, ignore
          }
        }
      }
    },
    localCoordinates(newVal) {
      // Debounce validation on input change
      if (this.validationTimeout) {
        clearTimeout(this.validationTimeout)
      }
      this.validationTimeout = setTimeout(() => {
        this.validateCoordinates()
      }, 300)
    }
  },
  methods: {
    validateCoordinates() {
      if (!this.localCoordinates || !this.localCoordinates.trim()) {
        this.validationError = 'Coordinates cannot be empty'
        return
      }

      try {
        const parsed = JSON.parse(this.localCoordinates)
        if (!Array.isArray(parsed)) {
          this.validationError = 'Coordinates must be a valid JSON array'
          return
        }

        // Reject empty arrays
        if (parsed.length === 0) {
          this.validationError = 'Coordinates cannot be empty'
          return
        }

        // If we have a geometry type, validate coordinates
        if (this.geometryType) {
          const validation = validateCoordinates(parsed, this.geometryType)
          this.validationError = validation.error
        } else {
          // No geometry type, but still check it's a valid non-empty array
          this.validationError = null
        }
      } catch (e) {
        this.validationError = `Invalid JSON: ${e.message}`
      }
    },
    formatJson() {
      if (!this.localCoordinates || !this.localCoordinates.trim()) {
        return
      }

      try {
        const parsed = JSON.parse(this.localCoordinates)
        // Format with 2-space indentation
        this.localCoordinates = JSON.stringify(parsed, null, 2)
        // Clear any existing errors
        this.errorMessage = ''
        this.validationError = null
        // Trigger validation after formatting
        this.$nextTick(() => {
          this.validateCoordinates()
        })
      } catch (e) {
        this.errorMessage = `Cannot format: Invalid JSON - ${e.message}`
      }
    },
    handleClose() {
      this.errorMessage = ''
      this.validationError = null
      if (this.validationTimeout) {
        clearTimeout(this.validationTimeout)
      }
      this.$emit('close')
    },
    handleSave() {
      if (!this.isValid) {
        this.errorMessage = this.validationError || 'Invalid coordinates'
        return
      }

      try {
        const parsed = JSON.parse(this.localCoordinates)
        if (!Array.isArray(parsed)) {
          this.errorMessage = 'Coordinates must be a valid JSON array'
          return
        }

        // Final validation if geometry type is available
        if (this.geometryType) {
          const validation = validateCoordinates(parsed, this.geometryType)
          if (!validation.valid) {
            this.errorMessage = validation.error
            return
          }
        }

        this.errorMessage = ''
        this.validationError = null
        this.$emit('save', this.localCoordinates)
      } catch (e) {
        this.errorMessage = `Invalid JSON: ${e.message}`
      }
    }
  },
  beforeUnmount() {
    if (this.validationTimeout) {
      clearTimeout(this.validationTimeout)
    }
  }
}
</script>

<style>
.code-area {
  border: 1px solid var(--main-blue);
}
</style>

