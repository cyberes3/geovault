<template>
  <div
    v-if="isOpen"
    class="fixed inset-0 z-50"
    role="dialog"
    aria-modal="true"
    @mousedown="handleBackdropMouseDown"
  >
    <!-- Backdrop -->
    <div class="absolute inset-0 bg-black/50"></div>

    <!-- Modal panel -->
    <div class="absolute inset-0 flex items-stretch justify-stretch sm:items-center sm:justify-center">
      <div
        class="bg-white flex flex-col w-full h-full sm:h-[90vh] sm:max-w-2xl sm:rounded-lg shadow-xl overflow-hidden"
        @mousedown.stop
        @click.stop
      >
        <!-- Header -->
        <div class="bg-white px-4 sm:px-6 py-4 border-b border-gray-200 sm:rounded-t-lg">
          <div class="flex items-center justify-between">
            <h3 class="text-lg font-medium text-gray-900">Bulk Operations</h3>
            <button
              @click="closeModal"
              class="p-2 sm:p-1 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150"
              title="Close modal"
            >
              <XMarkIcon class="h-6 w-6" />
            </button>
          </div>
        </div>

        <!-- Content -->
        <div class="bg-white p-4 sm:p-6 flex-1 overflow-y-auto min-h-0">
          <!-- Tags Section -->
          <div class="mb-6">
            <TagPicker
              v-model:tags="bulkData.tags"
              :available-tags="availableTags"
              :system-tags="[]"
              :disabled="false"
              :show-label="true"
              placeholder="Add tags to apply to all features..."
            />
          </div>

          <!-- Point Styling Section -->
          <div class="mb-6">
            <h4 class="text-sm font-semibold text-gray-900 mb-3">Point Styling</h4>
            <div class="space-y-4">
              <!-- Point Color -->
              <div>
                <div class="flex items-center justify-between mb-2">
                  <label class="block text-xs font-bold text-gray-500 uppercase">Point Color</label>
                  <div class="flex items-center space-x-2">
                    <ToggleButton
                      v-model="enabled.pointColor"
                      :label="''"
                      size="sm"
                      @update:modelValue="onPointColorToggle"
                    />
                  </div>
                </div>
                <ColorPicker
                  v-model="bulkData.pointColor"
                  :disabled="!enabled.pointColor"
                  size="md"
                />
              </div>

              <!-- Point Icon -->
              <div>
                <div class="flex items-center justify-between mb-2">
                  <label class="block text-xs font-bold text-gray-500 uppercase">Point Icon</label>
                  <div class="flex items-center space-x-2">
                    <ToggleButton
                      v-model="enabled.pointIcon"
                      :label="''"
                      size="sm"
                      @update:modelValue="onPointIconToggle"
                    />
                  </div>
                </div>
                <div class="mb-2">
                  <div class="flex items-center justify-between">
                    <label class="block text-xs font-medium text-gray-700">Default Icon</label>
                    <div class="flex items-center space-x-2">
                      <ToggleButton
                        v-model="useDefaultIcon"
                        :label="''"
                        size="sm"
                        :disabled="!enabled.pointIcon"
                        @update:modelValue="onDefaultIconToggle"
                      />
                    </div>
                  </div>
                </div>
                <div class="flex flex-col sm:flex-row sm:items-center space-y-2 sm:space-y-0 sm:space-x-2">
                  <button
                    @click="showIconPicker = true"
                    :disabled="!enabled.pointIcon || useDefaultIcon"
                    class="w-full sm:w-auto inline-flex items-center justify-center px-4 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-100"
                  >
                    <PhotoIcon class="w-4 h-4 mr-2" />
                    {{ bulkData.pointIcon ? 'Change Icon' : 'Select Icon' }}
                  </button>
                  <button
                    v-if="bulkData.pointIcon"
                    @click="clearPointIcon"
                    :disabled="!enabled.pointIcon || useDefaultIcon"
                    class="w-full sm:w-auto inline-flex items-center justify-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-100"
                  >
                    <XMarkIcon class="w-4 h-4 mr-1" />
                    Clear
                  </button>
                  <div v-if="bulkData.pointIcon && !useDefaultIcon" class="flex items-center">
                    <img
                      :src="resolveIconUrl(bulkData.pointIcon)"
                      alt="Selected icon"
                      class="w-8 h-8 object-contain border border-gray-300 rounded"
                      @error="handleIconError"
                    />
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Line Styling Section -->
          <div class="mb-6">
            <h4 class="text-sm font-semibold text-gray-900 mb-3">Line Styling</h4>
            <div>
              <div class="flex items-center justify-between mb-2">
                <label class="block text-xs font-bold text-gray-500 uppercase">Line Color</label>
                <div class="flex items-center space-x-2">
                  <ToggleButton
                    v-model="enabled.lineColor"
                    :label="''"
                    size="sm"
                    @update:modelValue="onLineColorToggle"
                  />
                </div>
              </div>
              <ColorPicker
                v-model="bulkData.lineColor"
                :disabled="!enabled.lineColor"
                size="md"
              />
            </div>
          </div>

          <!-- Polygon Styling Section -->
          <div class="mb-6">
            <h4 class="text-sm font-semibold text-gray-900 mb-3">Polygon Styling</h4>
            <div>
              <div class="flex items-center justify-between mb-2">
                <label class="block text-xs font-bold text-gray-500 uppercase">Polygon Fill Color</label>
                <div class="flex items-center space-x-2">
                  <ToggleButton
                    v-model="enabled.polyColor"
                    :label="''"
                    size="sm"
                    @update:modelValue="onPolyColorToggle"
                  />
                </div>
              </div>
              <ColorPicker
                v-model="bulkData.polyColor"
                :disabled="!enabled.polyColor"
                size="md"
              />
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="bg-gray-50 px-4 sm:px-6 py-3 border-t border-gray-200 sm:rounded-b-lg">
          <div class="flex flex-col sm:flex-row justify-end space-y-2 sm:space-y-0 sm:space-x-3">
            <button
              @click="closeModal"
              class="w-full sm:w-auto inline-flex items-center justify-center px-4 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            >
              Cancel
            </button>
            <button
              @click="handleApply"
              :disabled="saving"
              class="w-full sm:w-auto inline-flex items-center justify-center px-4 py-2 border border-transparent shadow-sm text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-70 disabled:cursor-not-allowed"
            >
              <svg
                v-if="saving"
                class="animate-spin -ml-1 mr-2 h-4 w-4 text-white"
                xmlns="http://www.w3.org/2000/svg"
                fill="none"
                viewBox="0 0 24 24"
              >
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path
                  class="opacity-75"
                  fill="currentColor"
                  d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"
                ></path>
              </svg>
              <span>{{ saving ? 'Saving…' : 'OK' }}</span>
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Icon Picker Dialog -->
    <IconPickerDialog
      :is-open="showIconPicker"
      @close="showIconPicker = false"
      @icon-selected="handleIconSelected"
    />
  </div>
</template>

<script>
import { XMarkIcon, PhotoIcon } from '@heroicons/vue/24/outline'
import TagPicker from '@/components/parts/TagPicker.vue'
import IconPickerDialog from '@/components/map/IconPickerDialog.vue'
import ToggleButton from '@/components/parts/ToggleButton.vue'
import ColorPickerElement from '@/components/parts/ColorPickerElement.vue'
import { APIHOST } from '@/config.js'

export default {
  name: 'BulkStylingModal',
  components: {
    XMarkIcon,
    PhotoIcon,
    TagPicker,
    IconPickerDialog,
    ToggleButton,
    ColorPicker: ColorPickerElement
  },
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    availableTags: {
      type: Array,
      default: () => []
    },
    currentBulkOps: {
      type: Object,
      default: () => ({
        tags: [],
        pointColor: null,
        pointIcon: null,
        lineColor: null,
        polyColor: null
      })
    },
    // When true, show loading state on OK button and disable it
    saving: {
      type: Boolean,
      default: false
    },
    // When true (default), modal will close itself after apply.
    // When false, parent is responsible for closing after save completes.
    autoCloseOnApply: {
      type: Boolean,
      default: true
    }
  },
  emits: ['close', 'apply'],
  data() {
    return {
      showIconPicker: false,
      useDefaultIcon: false,
      bulkData: {
        tags: [],
        pointColor: null,
        pointIcon: null,
        lineColor: null,
        polyColor: null
      },
      enabled: {
        pointColor: false,
        pointIcon: false,
        lineColor: false,
        polyColor: false
      }
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        // Prevent background scroll
        document.body.classList.add('overflow-hidden')
        // Move modal to body to avoid layout offsets
        this.$nextTick(() => {
          if (this.$el && this.$el.parentNode !== document.body) {
            document.body.appendChild(this.$el)
          }
          // Initialize form with current bulk operations when modal opens
          this.initializeForm()
        })
        // Add escape key listener
        document.addEventListener('keydown', this.handleEscapeKey)
      } else {
        // Restore background scroll
        document.body.classList.remove('overflow-hidden')
        // Remove escape key listener
        document.removeEventListener('keydown', this.handleEscapeKey)
        // Reset modal state when it closes
        this.resetModal()
      }
    },
    currentBulkOps: {
      handler() {
        if (this.isOpen) {
          this.initializeForm()
        }
      },
      deep: true
    },
    $route() {
      // Close modal when route changes
      if (this.isOpen) {
        this.closeModal()
      }
    }
  },
  methods: {
    resetModal() {
      // Reset all internal state to default values
      this.bulkData = {
        tags: [],
        pointColor: null,
        pointIcon: null,
        lineColor: null,
        polyColor: null
      }
      this.enabled = {
        pointColor: false,
        pointIcon: false,
        lineColor: false,
        polyColor: false
      }
      this.showIconPicker = false
      this.useDefaultIcon = false
    },
    initializeForm() {
      // Initialize with current bulk operations or defaults
      const ops = this.currentBulkOps || {}
      this.bulkData = {
        tags: ops.tags || [],
        pointColor: ops.pointColor || null,
        pointIcon: ops.pointIcon || null,
        lineColor: ops.lineColor || null,
        polyColor: ops.polyColor || null
      }
      // Set enabled state based on whether values are set
      // For pointIcon, check if it's explicitly in the ops dict (even if null)
      // This distinguishes between "not set" and "explicitly set to null (default icon)"
      const pointIconExplicitlySet = 'pointIcon' in ops
      this.enabled = {
        pointColor: ops.pointColor !== null && ops.pointColor !== undefined,
        pointIcon: pointIconExplicitlySet,
        lineColor: ops.lineColor !== null && ops.lineColor !== undefined,
        polyColor: ops.polyColor !== null && ops.polyColor !== undefined
      }
      // Set useDefaultIcon to true if pointIcon is enabled but the value is null (meaning default icon)
      this.useDefaultIcon = this.enabled.pointIcon && !this.bulkData.pointIcon
      // Note: Default values are set by toggle handlers when toggles are enabled
      this.showIconPicker = false
    },
    closeModal() {
      this.$emit('close')
    },
    handleBackdropMouseDown(event) {
      if (event.target === event.currentTarget) {
        this.closeModal()
      }
    },
    handleEscapeKey(event) {
      if (event.key === 'Escape' && this.isOpen) {
        this.closeModal()
      }
    },
    handleIconSelected(iconUrl) {
      this.bulkData.pointIcon = iconUrl
      this.useDefaultIcon = false // Clear default icon flag when an icon is selected
      this.showIconPicker = false
    },
    clearPointIcon() {
      this.bulkData.pointIcon = null
    },
    onPointColorToggle(enabled) {
      if (enabled) {
        // When enabling, set default color if not already set
        if (!this.bulkData.pointColor) {
          this.bulkData.pointColor = '#ff0000'
        }
      } else {
        // When disabling, set to null
        this.bulkData.pointColor = null
      }
    },
    onPointIconToggle(enabled) {
      if (!enabled) {
        // When disabling, set to null and reset default icon toggle
        this.bulkData.pointIcon = null
        this.useDefaultIcon = false
      }
    },
    onDefaultIconToggle(enabled) {
      if (enabled) {
        // When enabling default icon, clear any selected icon
        this.bulkData.pointIcon = null
        this.showIconPicker = false
      }
    },
    onLineColorToggle(enabled) {
      if (enabled) {
        // When enabling, set default color if not already set
        if (!this.bulkData.lineColor) {
          this.bulkData.lineColor = '#ff0000'
        }
      } else {
        // When disabling, set to null
        this.bulkData.lineColor = null
      }
    },
    onPolyColorToggle(enabled) {
      if (enabled) {
        // When enabling, set default color if not already set
        if (!this.bulkData.polyColor) {
          this.bulkData.polyColor = '#ff0000'
        }
      } else {
        // When disabling, set to null
        this.bulkData.polyColor = null
      }
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
      // Fallback: assume it's a relative path and prepend APIHOST
      return `${APIHOST}${iconUrl.startsWith('/') ? '' : '/'}${iconUrl}`
    },
    handleIconError(event) {
      // Hide broken image
      if (event.target && event.target.parentElement) {
        event.target.style.display = 'none'
      }
    },
    handleApply() {
      // Emit the bulk data to parent, ensuring null values for disabled options
      // If useDefaultIcon is enabled, set pointIcon to null (removes custom icons)
      const dataToEmit = {
        tags: this.bulkData.tags,
        pointColor: this.enabled.pointColor ? this.bulkData.pointColor : null,
        lineColor: this.enabled.lineColor ? this.bulkData.lineColor : null,
        polyColor: this.enabled.polyColor ? this.bulkData.polyColor : null
      }
      
      // Only include pointIcon if the toggle is enabled
      // If useDefaultIcon is enabled, set to null to remove custom icons
      // Otherwise, include the selected icon (or null if none selected)
      if (this.enabled.pointIcon) {
        dataToEmit.pointIcon = this.useDefaultIcon ? null : (this.bulkData.pointIcon || null)
      }
      
      this.$emit('apply', dataToEmit)
      if (this.autoCloseOnApply && !this.saving) {
        this.closeModal()
      }
    }
  },
  beforeUnmount() {
    // Restore background scroll
    document.body.classList.remove('overflow-hidden')
    // Remove escape key listener if component is unmounted while modal is open
    document.removeEventListener('keydown', this.handleEscapeKey)
  }
}
</script>

