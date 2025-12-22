<template>
  <!-- Modal Backdrop -->
  <div
    v-if="isOpen"
    class="color-picker-backdrop fixed top-0 left-0 right-0 bottom-0 z-50 m-0 overflow-hidden"
    role="dialog"
    aria-modal="true"
    @mousedown="handleBackdropMouseDown"
  >
    <!-- Background overlay -->
    <div class="absolute inset-0 bg-black/50 transition-opacity"></div>

    <!-- Modal panel -->
    <div class="absolute inset-0 flex items-center justify-center">
      <div
        class="bg-white flex flex-col rounded-lg shadow-xl overflow-hidden color-picker-dialog"
        @click.stop
        @mousedown.stop
      >
        <!-- Header -->
        <div class="flex items-center justify-between px-4 py-3 border-b border-gray-200 bg-white rounded-t-lg">
          <h3 class="text-base font-semibold text-gray-900">Choose a Color</h3>
          <button
            @click="handleCancel"
            class="p-1 -mr-1 text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150"
            title="Close dialog"
          >
            <XMarkIcon class="h-5 w-5" />
          </button>
        </div>

        <!-- Content -->
        <div class="flex-1 bg-white p-4 overflow-y-auto">
          <!-- Vue Color SketchPicker -->
          <div class="color-picker-wrapper">
            <sketch-picker
              :model-value="colorValue"
              :preset-colors="predefinedColors"
              :disable-alpha="true"
              @update:model-value="handleColorChange"
            />
          </div>

          <!-- Footer with OK Button -->
          <div class="mt-4 pt-3 border-t border-gray-200 flex justify-end">
            <button
              type="button"
              class="inline-flex items-center justify-center px-5 py-2 border border-transparent shadow-sm text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors"
              @click="handleOk"
            >
              OK
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { XMarkIcon } from '@heroicons/vue/24/outline'
import { SketchPicker } from 'vue-color'
import 'vue-color/style.css'
import { normalizeHex } from '@/utils/colorUtils.js'

export default {
  name: 'ColorPickerDialog',
  components: {
    XMarkIcon,
    'sketch-picker': SketchPicker
  },
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    modelValue: {
      type: String,
      default: '#000000'
    }
  },
  emits: ['update:modelValue', 'close', 'confirm'],
  data() {
    return {
      predefinedColors: [
        '#000000',
        '#C0C0C0',
        '#FF0000',
        '#FFAA00',
        '#F0F000',
        '#00CD00',
        '#0000FF',
        '#009AFF',
        '#A200FF'
      ],
      colorValue: null
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.initializeFromColor(this.modelValue)
        // Prevent body scroll when dialog is open
        document.body.style.overflow = 'hidden'
        // Add escape key listener
        document.addEventListener('keydown', this.handleEscapeKey)
      } else {
        document.body.style.overflow = ''
        // Remove escape key listener
        document.removeEventListener('keydown', this.handleEscapeKey)
      }
    },
    modelValue(newVal) {
      if (this.isOpen) {
        this.initializeFromColor(newVal)
      }
    }
  },
  methods: {
    initializeFromColor(color) {
      const normalized = normalizeHex(color)
      // vue-color expects a hex string for modelValue
      this.colorValue = normalized
    },
    handleColorChange(color) {
      this.colorValue = color
      // color can be a string (hex) or an object with hex property
      const hexColor = typeof color === 'string' ? color : (color.hex || color)
      this.$emit('update:modelValue', hexColor)
    },
    handleOk() {
      const hexColor = typeof this.colorValue === 'string' 
        ? this.colorValue 
        : (this.colorValue?.hex || this.modelValue)
      this.$emit('update:modelValue', hexColor)
      this.$emit('confirm', hexColor)
      this.$emit('close')
    },
    handleCancel() {
      // Reset to original color
      this.initializeFromColor(this.modelValue)
      this.$emit('close')
    },
    handleBackdropMouseDown(event) {
      if (event.target === event.currentTarget) {
        this.handleCancel()
      }
    },
    handleEscapeKey(event) {
      if (event.key === 'Escape' && this.isOpen) {
        this.handleCancel()
      }
    }
  },
  beforeUnmount() {
    // Clean up event listener if component is destroyed while modal is open
    document.removeEventListener('keydown', this.handleEscapeKey)
    document.body.style.overflow = ''
  }
}
</script>

<style scoped>
/* Ensure modal backdrop covers entire viewport, including any body margins */
.color-picker-backdrop {
  position: fixed !important;
  top: 0 !important;
  left: 0 !important;
  right: 0 !important;
  bottom: 0 !important;
  margin: 0 !important;
  padding: 0 !important;
  width: 100vw !important;
  height: 100vh !important;
  max-width: 100vw !important;
  max-height: 100vh !important;
}

/* Fixed size for color picker dialog */
.color-picker-dialog {
  width: 280px;
  max-height: 90vh;
}

/* Style vue-color SketchPicker to match website design */
.color-picker-wrapper {
  width: 100%;
}

.vc-sketch-picker {
  box-shadow: none !important;
  width: 91%;
}

/* Hide R/G/B input fields, keep only hex */
.color-picker-wrapper :deep(.field_single) {
  display: none !important;
}

/* Make hex input 75% width and center it */
.color-picker-wrapper :deep(.field) {
  display: flex !important;
  justify-content: center !important;
}

/* Make hex input uppercase */
.color-picker-wrapper :deep(.field_double input) {
  text-transform: uppercase !important;
}

.color-picker-wrapper :deep(.field_double) {
  width: 75% !important;
  max-width: 75% !important;
}

.color-picker-wrapper :deep(.field_double .vc-editable-input) {
  width: 100% !important;
}

.color-picker-wrapper :deep(.field_double input) {
  width: 100% !important;
}

/* Override vue-color default styles */
.color-picker-wrapper :deep(.vc-sketch) {
  width: 100% !important;
  box-shadow: none !important;
  border: none !important;
  padding: 0 !important;
}

/* Compact saturation area */
.color-picker-wrapper :deep(.vc-sketch-saturation-wrap) {
  height: 140px !important;
  border-radius: 0.375rem !important;
  overflow: hidden !important;
}

/* Compact hue slider */
.color-picker-wrapper :deep(.vc-sketch-hue-wrap) {
  height: 8px !important;
  border-radius: 0.25rem !important;
  overflow: hidden !important;
  margin: 8px 0 !important;
}

/* Reduce spacing in controls */
.color-picker-wrapper :deep(.vc-sketch-controls) {
  padding: 0 !important;
}

.color-picker-wrapper :deep(.vc-sketch-sliders) {
  padding: 0 !important;
}

/* Compact color preview */
.color-picker-wrapper :deep(.vc-sketch-color-wrap) {
  width: 28px !important;
  height: 28px !important;
  border-radius: 0.375rem !important;
}

/* Compact and style inputs */
.color-picker-wrapper :deep(input) {
  border: 1px solid #d1d5db !important;
  border-radius: 0.25rem !important;
  padding: 0.25rem 0.375rem !important;
  font-size: 0.8125rem !important;
  box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05) !important;
  text-align: center !important;
  font-family: ui-monospace, monospace !important;
}

.color-picker-wrapper :deep(input:focus) {
  outline: none !important;
  border-color: var(--color-blue-500) !important;
  box-shadow: 0 0 0 1px var(--color-blue-500) !important;
}

/* Style field labels - more compact */
.color-picker-wrapper :deep(.vc-sketch-field) {
  padding-bottom: 0 !important;
}

.color-picker-wrapper :deep(.vc-sketch-field label) {
  font-size: 0.625rem !important;
  font-weight: 500 !important;
  color: #6b7280 !important;
  text-transform: uppercase !important;
  letter-spacing: 0.05em !important;
  margin-top: 0.25rem !important;
}

/* Hide alpha slider */
.color-picker-wrapper :deep(.vc-sketch-alpha-wrap) {
  display: none !important;
}

/* Compact preset colors */
.color-picker-wrapper :deep(.vc-sketch-presets) {
  margin-top: 0.75rem !important;
  padding: 0 !important;
  border-top: 1px solid #e5e7eb !important;
  padding-top: 0.75rem !important;
}

.color-picker-wrapper :deep(.vc-sketch-presets-color) {
  width: 18px !important;
  height: 18px !important;
  border: 1px solid #d1d5db !important;
  border-radius: 0.25rem !important;
  margin: 2px !important;
  box-shadow: 0 1px 2px 0 rgba(0, 0, 0, 0.05) !important;
  transition: all 0.15s ease-in-out !important;
}

.color-picker-wrapper :deep(.vc-sketch-presets-color:hover) {
  transform: scale(1.15) !important;
  box-shadow: 0 2px 4px 0 rgba(0, 0, 0, 0.1) !important;
  border-color: var(--color-blue-500) !important;
}
</style>


