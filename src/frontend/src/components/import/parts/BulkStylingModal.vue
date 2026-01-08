<template>
  <BaseModal
    :is-open="isOpen"
    title="Bulk Operations"
    max-width="2xl"
    @close="closeModal"
  >
    <div class="p-4 sm:p-6">
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

    <template #footer>
      <button
        @click="closeModal"
        class="inline-flex items-center justify-center px-4 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
      >
        Cancel
      </button>
      <button
        @click="handleApply"
        :disabled="saving"
        class="inline-flex items-center justify-center px-4 py-2 border border-transparent shadow-sm text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-70 disabled:cursor-not-allowed"
      >
        <Loader
          v-if="saving"
          size="sm"
          layout="inline"
          :showMessage="false"
          color="#ffffff"
        />
        {{ saving ? 'Applying...' : 'Apply' }}
      </button>
    </template>
  </BaseModal>

  <!-- Icon Picker Dialog -->
  <IconPickerDialog
    :is-open="showIconPicker"
    @close="showIconPicker = false"
    @icon-selected="handleIconSelected"
  />
</template>

<script>
import { PhotoIcon } from '@heroicons/vue/24/outline'
import BaseModal from '@/components/parts/BaseModal.vue'
import TagPicker from '@/components/parts/TagPicker.vue'
import IconPickerDialog from '@/components/map/IconPickerDialog.vue'
import ToggleButton from '@/components/parts/ToggleButton.vue'
import ColorPickerElement from '@/components/parts/ColorPickerElement.vue'
import Loader from '@/components/parts/Loader.vue'
import { APIHOST } from '@/config.js'

export default {
  name: 'BulkStylingModal',
  components: {
    BaseModal,
    PhotoIcon,
    TagPicker,
    IconPickerDialog,
    ToggleButton,
    ColorPicker: ColorPickerElement,
    Loader
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
    originalBulkOps: {
      type: Object,
      default: () => ({})
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
        // Initialize form with current bulk operations when modal opens
        this.$nextTick(() => {
          this.initializeForm()
        })
      } else {
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
    originalBulkOps: {
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
      // Initialize with current bulk operations (normalized) for values
      const ops = this.currentBulkOps || {}
      this.bulkData = {
        tags: ops.tags || [],
        pointColor: ops.pointColor || null,
        pointIcon: ops.pointIcon || null,
        lineColor: ops.lineColor || null,
        polyColor: ops.polyColor || null
      }
      // Use originalBulkOps (raw) to determine which toggles should be enabled
      // This distinguishes between "key not set" (disabled) and "key set to null" (enabled)
      const rawOps = this.originalBulkOps || {}
      this.enabled = {
        pointColor: rawOps.pointColor !== null && rawOps.pointColor !== undefined && 'pointColor' in rawOps,
        pointIcon: 'pointIcon' in rawOps,
        lineColor: rawOps.lineColor !== null && rawOps.lineColor !== undefined && 'lineColor' in rawOps,
        polyColor: rawOps.polyColor !== null && rawOps.polyColor !== undefined && 'polyColor' in rawOps
      }
      // Set useDefaultIcon to true if pointIcon is enabled but the value is null (meaning default icon)
      this.useDefaultIcon = this.enabled.pointIcon && !this.bulkData.pointIcon
      // Note: Default values are set by toggle handlers when toggles are enabled
      this.showIconPicker = false
    },
    closeModal() {
      this.$emit('close')
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
      // Emit the bulk data to parent, only including keys for enabled toggles
      // This ensures we don't send keys that weren't explicitly configured
      const dataToEmit = {
        tags: this.bulkData.tags || []
      }
      
      // Only include keys when their toggles are enabled
      if (this.enabled.pointColor) {
        dataToEmit.pointColor = this.bulkData.pointColor
      }
      
      if (this.enabled.pointIcon) {
        // If useDefaultIcon is enabled, set to null to remove custom icons
        // Otherwise, include the selected icon (or null if none selected)
        dataToEmit.pointIcon = this.useDefaultIcon ? null : (this.bulkData.pointIcon || null)
      }
      
      if (this.enabled.lineColor) {
        dataToEmit.lineColor = this.bulkData.lineColor
      }
      
      if (this.enabled.polyColor) {
        dataToEmit.polyColor = this.bulkData.polyColor
      }
      
      this.$emit('apply', dataToEmit)
      if (this.autoCloseOnApply && !this.saving) {
        this.closeModal()
      }
    }
  },
}
</script>

