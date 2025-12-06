<template>
  <div
    v-if="isOpen"
    class="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50"
    @click.self="handleClose"
  >
    <div class="bg-white rounded-lg shadow-xl max-w-2xl w-full mx-4 max-h-[90vh] flex flex-col">
      <div class="flex items-center justify-between p-4 border-b border-gray-200">
        <h3 class="text-lg font-semibold text-gray-900">Edit Coordinates</h3>
        <button
          @click="handleClose"
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
              v-model="localCoordinates"
              rows="12"
              :disabled="disabled"
              class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 font-mono text-xs disabled:bg-gray-100 disabled:cursor-not-allowed"
              placeholder="[]"
            ></textarea>
            <p v-if="errorMessage" class="mt-2 text-sm text-red-600">{{ errorMessage }}</p>
          </div>
        </div>
      </div>
      <div class="flex justify-end space-x-2 p-4 border-t border-gray-200">
        <button
          type="button"
          @click="handleClose"
          :disabled="disabled"
          class="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-md hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
          title="Close"
        >
          Close
        </button>
        <button
          type="button"
          @click="handleSave"
          :disabled="disabled || !isValid"
          class="px-4 py-2 text-sm font-medium text-white bg-blue-600 border border-transparent rounded-md hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
          title="Save coordinates"
        >
          Save
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { XMarkIcon } from '@heroicons/vue/24/outline'

export default {
  name: 'CoordinatesDialog',
  components: {
    XMarkIcon
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
    disabled: {
      type: Boolean,
      default: false
    }
  },
  emits: ['close', 'save'],
  data() {
    return {
      localCoordinates: '',
      errorMessage: ''
    }
  },
  computed: {
    isValid() {
      if (!this.localCoordinates || !this.localCoordinates.trim()) {
        return false
      }
      try {
        const parsed = JSON.parse(this.localCoordinates)
        return Array.isArray(parsed)
      } catch (e) {
        return false
      }
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.localCoordinates = this.coordinates || ''
        this.errorMessage = ''
      }
    },
    coordinates(newVal) {
      if (this.isOpen) {
        this.localCoordinates = newVal || ''
      }
    }
  },
  methods: {
    handleClose() {
      this.errorMessage = ''
      this.$emit('close')
    },
    handleSave() {
      if (!this.isValid) {
        this.errorMessage = 'Invalid JSON array format'
        return
      }
      
      try {
        const parsed = JSON.parse(this.localCoordinates)
        if (!Array.isArray(parsed)) {
          this.errorMessage = 'Coordinates must be a valid JSON array'
          return
        }
        this.errorMessage = ''
        this.$emit('save', this.localCoordinates)
      } catch (e) {
        this.errorMessage = `Invalid JSON: ${e.message}`
      }
    }
  }
}
</script>

