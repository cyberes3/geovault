<template>
  <div>
    <label class="block font-medium text-gray-700 mb-2" :class="labelClasses">
      {{ label }}
    </label>

    <!-- Icon Display with Choose Button -->
    <div class="flex items-center justify-between p-2 bg-gray-50 rounded-md border border-gray-200 h-[50px]">
      <!-- Current Icon Preview (Left) -->
      <div class="flex items-center h-full">
        <div v-if="hasIcon" class="mr-2 flex items-center">
          <img
            :src="resolveIconUrl(currentIconUrl ?? '')"
            alt="Current icon"
            class="w-8 h-8 object-contain border border-gray-300 rounded bg-white"
            @error="handleIconError"
          />
        </div>
        <span v-else class="text-xs text-gray-500 italic">Default Marker</span>
      </div>

      <!-- Buttons (Right) -->
      <div class="flex items-center space-x-2">
        <button
          v-if="hasIcon && showRemove"
          type="button"
          @click="handleRemove"
          :disabled="disabled"
          class="text-xs px-3 py-2 sm:px-2 sm:py-1 min-h-[44px] sm:min-h-0 text-red-600 hover:text-red-800 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
          title="Remove Icon"
        >
          Remove
        </button>
        <button
          v-if="showReset"
          type="button"
          @click="handleReset"
          :disabled="disabled || !hasIconChanged"
          class="text-xs px-3 py-2 sm:px-2 sm:py-1 min-h-[44px] sm:min-h-0 text-gray-600 hover:text-gray-800 bg-gray-50 hover:bg-gray-100 rounded border border-gray-200 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-gray-500 disabled:opacity-50 disabled:cursor-not-allowed font-medium"
          title="Reset to Original Icon"
        >
          Reset
        </button>
        <button
          type="button"
          @click="openPicker"
          :disabled="disabled"
          class="text-xs px-3 py-2 sm:px-2 sm:py-1 min-h-[44px] sm:min-h-0 text-white bg-blue-500 hover:bg-blue-700 rounded border border-transparent focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed disabled:bg-gray-400 disabled:hover:bg-gray-400 font-medium shadow-sm"
          title="Choose Icon"
        >
          Choose
        </button>
      </div>
    </div>

    <!-- Icon Preview (for newly selected icon) -->
    <div v-if="previewUrl" class="mt-2 flex items-center p-2 bg-blue-50 border border-blue-100 rounded-md">
      <img
        :src="previewUrl"
        alt="Icon preview"
        class="w-6 h-6 object-contain border border-gray-300 rounded bg-white"
      />
      <p class="text-xs text-blue-800 ml-2">New Selection Preview</p>
    </div>

    <!-- Icon Error -->
    <div v-if="error" class="mt-1 p-1.5 bg-red-50 border border-red-200 rounded-md">
      <p class="text-xs text-red-800">{{ error }}</p>
    </div>

    <!-- Icon Picker Dialog -->
    <IconPickerDialog
      :is-open="isPickerOpen"
      @close="closePicker"
      @icon-selected="handleIconSelected"
    />
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import {APIHOST} from '@/config.js'
import IconPickerDialog from '@/components/map/IconPickerDialog.vue'
import { isSystemIcon, resolveIconUrl, handleIconError } from '@/utils/map/iconUtils'

type IconSelectorSize = 'sm' | 'md'

// Helper to normalize icon URLs for comparison (removes APIHOST prefix)
function normalizeIconUrl(value: string | null | undefined): string | null {
  if (!value) return null
  let trimmed = String(value).trim()
  if (trimmed === '') return null
  
  // Remove APIHOST prefix if present for comparison
  if (trimmed.startsWith(APIHOST)) {
    trimmed = trimmed.substring(APIHOST.length)
  }
  
  return trimmed
}

// Helper to normalize color values for comparison
function normalizeColor(value: string | null | undefined): string | null {
  if (!value) return null
  return String(value).trim().toLowerCase() || null
}

export default defineComponent({
  name: 'IconSelector',
  components: {
    IconPickerDialog
  },
  props: {
    // The current icon URL (from properties like 'icon', 'icon-href', etc.)
    iconUrl: {
      type: String,
      default: null
    },
    disabled: {
      type: Boolean,
      default: false
    },
    label: {
      type: String,
      default: 'Icon'
    },
    size: {
      type: String as PropType<IconSelectorSize>,
      default: 'md', // 'sm' or 'md'
      validator: (value: string) => ['sm', 'md'].includes(value)
    },
    showRemove: {
      type: Boolean,
      default: true
    },
    // Show reset button (only on import process page)
    showReset: {
      type: Boolean,
      default: false
    },
    // The original icon URL (for reset functionality)
    originalIconUrl: {
      type: String,
      default: null
    },
    // The current icon color (for default markers)
    iconColor: {
      type: String,
      default: null
    },
    // The original icon color (for reset functionality)
    originalIconColor: {
      type: String,
      default: null
    },
    // External error message (optional)
    error: {
      type: String,
      default: null
    }
  },
  emits: ['icon-selected', 'icon-removed', 'icon-reset', 'icon-color-reset'],
  data() {
    return {
      isPickerOpen: false,
      currentIconUrl: null as string | null,
      previewUrl: null as string | null
    }
  },
  computed: {
    labelClasses() {
      return this.size === 'sm' 
        ? 'text-xs font-bold uppercase' 
        : 'text-sm'
    },
    hasIcon() {
      return !!this.currentIconUrl?.trim()
    },
    hasIconChanged() {
      const currentIcon = normalizeIconUrl(this.currentIconUrl)
      const originalIcon = normalizeIconUrl(this.originalIconUrl)
      
      // For default markers (no icon), check if color has changed
      if (!currentIcon && !originalIcon) {
        return normalizeColor(this.iconColor) !== normalizeColor(this.originalIconColor)
      }
      
      return currentIcon !== originalIcon
    }
  },
  watch: {
    iconUrl: {
      handler(newValue: string | null) {
        this.updateIconState(newValue)
      },
      immediate: true
    }
  },
  methods: {
    updateIconState(iconUrl: string | null) {
      this.currentIconUrl = iconUrl
      this.previewUrl = null
    },
    
    // Thin wrappers so the template (Options API, no direct module-scope access) can call the
    // shared `@/utils/map/iconUtils` helpers via `this.*`.
    resolveIconUrl,
    handleIconError,

    resetIconState(iconUrl: string | null = null) {
      this.currentIconUrl = iconUrl
      this.previewUrl = null
    },

    openPicker() {
      this.isPickerOpen = true
    },

    closePicker() {
      this.isPickerOpen = false
    },

    handleIconSelected(iconUrl: string) {
      this.currentIconUrl = iconUrl
      this.previewUrl = this.resolveIconUrl(iconUrl)
      
      this.$emit('icon-selected', {
        iconUrl,
        isSystemIcon: isSystemIcon(iconUrl)
      })
      
      this.closePicker()
    },

    handleRemove() {
      this.resetIconState()
      this.$emit('icon-removed')
    },

    handleReset() {
      const originalUrl = this.originalIconUrl || null
      this.resetIconState(originalUrl)
      this.$emit('icon-reset', originalUrl)
      
      // For default markers (no icon), also emit color reset
      if (!originalUrl) {
        this.$emit('icon-color-reset')
      }
    }
  }
})
</script>

