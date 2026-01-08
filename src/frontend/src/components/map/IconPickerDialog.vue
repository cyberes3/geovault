<template>
  <BaseModal
    :is-open="isOpen"
    title="Choose an Icon Style"
    max-width="4xl"
    @close="closeDialog"
  >
    <div class="p-6">
      <!-- Points Section -->
      <div v-if="pointsIcons.length > 0" class="mb-4">
            <h4 class="text-sm font-semibold text-gray-900 mb-2">Points</h4>
            <div class="flex flex-wrap gap-0.5">
              <button
                v-for="icon in pointsIcons"
                :key="icon.url"
                type="button"
                @click.stop="selectIcon(icon.url)"
                :class="[
                  'relative w-10 h-10 sm:w-6 sm:h-6 flex items-center justify-center hover:bg-blue-100 transition-colors',
                  selectedIconUrl === icon.url ? 'border-2 border-blue-500' : ''
                ]"
                :title="icon.filename"
              >
                <div class="absolute inset-0 bg-gray-100"></div>
                <img
                  :src="resolveIconUrl(icon.url)"
                  :alt="icon.filename"
                  class="relative object-contain"
                  @error="handleIconError"
                  @load="handleIconLoad"
                />
              </button>
            </div>
          </div>

          <!-- Letters Section -->
          <div v-if="lettersIcons.length > 0" class="mb-4">
            <h4 class="text-sm font-semibold text-gray-900 mb-2">Letters</h4>
            <div class="flex flex-wrap gap-0.5">
              <button
                v-for="icon in lettersIcons"
                :key="icon.url"
                type="button"
                @click.stop="selectIcon(icon.url)"
                :class="[
                  'relative w-10 h-10 sm:w-6 sm:h-6 flex items-center justify-center hover:bg-blue-100 transition-colors',
                  selectedIconUrl === icon.url ? 'border-2 border-blue-500' : ''
                ]"
                :title="icon.filename"
              >
                <div class="absolute inset-0 bg-gray-100"></div>
                <img
                  :src="resolveIconUrl(icon.url)"
                  :alt="icon.filename"
                  class="relative object-contain"
                  @error="handleIconError"
                  @load="handleIconLoad"
                />
              </button>
            </div>
          </div>

          <!-- Recreation Section -->
          <div v-if="filteredRecreationIcons.length > 0" class="mb-4">
            <h4 class="text-sm font-semibold text-gray-900 mb-2">Recreation</h4>
            <div class="mb-2">
              <select
                v-model="selectedStyle"
                class="px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
              >
                <option value="standard">Standard</option>
                <option value="circle">Circle</option>
                <option value="square">Square</option>
                <option value="hexagon">Hexagon</option>
                <option value="diamond">Diamond</option>
                <option value="pentagon">Pentagon</option>
                <option value="triangle">Triangle</option>
                <option value="placemark">Placemark</option>
                <option value="square-rounded">Square Rounded</option>
              </select>
            </div>
            <div class="grid grid-cols-6 sm:grid-cols-12 gap-0.5 h-[258px] overflow-y-auto">
              <button
                v-for="icon in filteredRecreationIcons"
                :key="icon.url"
                type="button"
                @click.stop="selectIcon(icon.url)"
                :class="[
                  'relative w-10 h-10 sm:w-6 sm:h-6 flex items-center justify-center hover:bg-blue-100 transition-colors',
                  selectedIconUrl === icon.url ? 'border-2 border-blue-500' : ''
                ]"
                :title="icon.filename"
              >
                <div class="absolute inset-0 bg-gray-100"></div>
                <img
                  :src="resolveIconUrl(icon.url)"
                  class="relative max-w-full max-h-full object-contain"
                  @error="handleIconError"
                  @load="handleIconLoad"
                />
              </button>
            </div>
          </div>

          <!-- Custom Icon Upload Section -->
          <div class="mb-4">
            <h4 class="text-sm font-semibold text-gray-900 mb-2">Custom</h4>
            <div class="space-y-2">
              <p class="text-sm text-gray-600">For custom icon upload a file:</p>
              <input
                ref="customIconInput"
                type="file"
                accept=".png,.jpg,.jpeg,.ico"
                @change="handleCustomIconSelect"
                class="block w-full text-sm text-gray-500 file:mr-4 file:py-2 file:px-4 file:rounded-md file:border-0 file:text-sm file:font-semibold file:bg-blue-50 file:text-blue-700 hover:file:bg-blue-600 hover:file:text-white"
              />
              <p class="text-xs text-gray-500">
                Supported formats: PNG, JPG, ICO (max 500KB)
              </p>
              <div v-if="customIconPreview" class="mt-2">
                <img
                  :src="customIconPreview"
                  alt="Custom icon preview"
                  class="w-8 h-8 object-contain border border-gray-300 rounded"
                />
                <p class="text-xs text-gray-600 mt-1">Preview</p>
              </div>
              <div v-if="customIconError" class="mt-2 p-2 bg-red-50 border border-red-200 rounded-md">
                <p class="text-xs text-red-800">{{ customIconError }}</p>
              </div>
            </div>
          </div>
    </div>

    <template #footer>
      <button
        type="button"
        @click="closeDialog"
        class="mr-3 inline-flex items-center px-4 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
        title="Cancel icon selection"
      >
        Cancel
      </button>
      <button
        type="button"
        @click="handleOk"
        :disabled="!selectedIconUrl && !customIconFile"
        class="inline-flex items-center px-4 py-2 border border-transparent shadow-sm text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
        title="Confirm icon selection"
      >
        OK
      </button>
    </template>
  </BaseModal>
</template>

<script>
import {APIHOST} from '@/config.js'
import BaseModal from '@/components/parts/BaseModal.vue'

export default {
  name: 'IconPickerDialog',
  props: {
    isOpen: {
      type: Boolean,
      default: false
    }
  },
  emits: ['close', 'icon-selected'],
  components: {
    BaseModal
  },
  data() {
    return {
      iconRegistry: {
        points: [],
        letters: [],
        recreation: []
      },
      selectedStyle: 'standard',
      selectedIconUrl: null,
      customIconFile: null,
      customIconPreview: null,
      customIconError: '',
      isLoading: false
    }
  },
  computed: {
    pointsIcons() {
      return (this.iconRegistry && this.iconRegistry.points) || []
    },
    lettersIcons() {
      return (this.iconRegistry && this.iconRegistry.letters) || []
    },
    recreationIcons() {
      return (this.iconRegistry && this.iconRegistry.recreation) || []
    },
    filteredRecreationIcons() {
      if (!this.iconRegistry || !this.iconRegistry.recreation) return []
      return this.iconRegistry.recreation.filter(icon => icon.style === this.selectedStyle)
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.loadIconRegistry()
      } else {
        this.resetDialog()
      }
    }
  },
  methods: {
    async loadIconRegistry() {
      this.isLoading = true
      try {
        const response = await fetch(`${APIHOST}/api/icons/registry/`)
        if (!response.ok) {
          throw new Error(`Failed to load icon registry: ${response.statusText}`)
        }
        const data = await response.json()
        // Ensure all required properties exist
        this.iconRegistry = {
          points: data.points || [],
          letters: data.letters || [],
          recreation: data.recreation || []
        }
      } catch (error) {
        console.error('Error loading icon registry:', error)
        this.customIconError = 'Failed to load icon registry'
        // Reset to empty structure on error
        this.iconRegistry = {
          points: [],
          letters: [],
          recreation: []
        }
      } finally {
        this.isLoading = false
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
    selectIcon(iconUrl) {
      this.selectedIconUrl = iconUrl
      this.customIconFile = null
      this.customIconPreview = null
      this.customIconError = ''
    },
    handleCustomIconSelect(event) {
      this.customIconError = ''
      this.customIconPreview = null
      this.customIconFile = null
      this.selectedIconUrl = null

      const file = event.target.files[0]
      if (!file) {
        return
      }

      // Validate file extension
      const validExtensions = ['.png', '.jpg', '.jpeg', '.ico']
      const fileExt = '.' + file.name.split('.').pop().toLowerCase()
      if (!validExtensions.includes(fileExt)) {
        this.customIconError = `Invalid file type. Allowed: ${validExtensions.join(', ')}`
        event.target.value = ''
        return
      }

      // Validate file size (500KB = 512000 bytes)
      const maxSize = 512000
      if (file.size > maxSize) {
        this.customIconError = `File size exceeds maximum allowed size of 500KB`
        event.target.value = ''
        return
      }

      // Create preview URL
      this.customIconFile = file
      const reader = new FileReader()
      reader.onload = (e) => {
        this.customIconPreview = e.target.result
      }
      reader.readAsDataURL(file)
    },
    async handleOk() {
      if (this.customIconFile) {
        // Upload custom icon
        try {
          const formData = new FormData()
          formData.append('file', this.customIconFile)

          const response = await fetch(`${APIHOST}/api/icons/upload/`, {
            method: 'POST',
            headers: {
              'X-CSRFToken': this.getCsrfToken()
            },
            credentials: 'include',
            body: formData
          })

          const data = await response.json()

          if (!response.ok) {
            this.customIconError = data.error || 'Failed to upload icon'
            return
          }

          this.$emit('icon-selected', data.icon_url)
          this.closeDialog()
        } catch (error) {
          console.error('Error uploading icon:', error)
          this.customIconError = `Error: ${error.message}`
        }
      } else if (this.selectedIconUrl) {
        // Use selected preset icon
        this.$emit('icon-selected', this.selectedIconUrl)
        this.closeDialog()
      }
    },
    closeDialog() {
      this.$emit('close')
    },
    handleIconError(event) {
      // Hide broken image
      if (event.target && event.target.parentElement) {
        event.target.style.display = 'none'
      }
    },
    handleIconLoad(event) {
      // Hide placeholder when image loads
      const placeholder = event.target.previousElementSibling
      if (placeholder && placeholder.classList.contains('bg-gray-100')) {
        placeholder.style.display = 'none'
      }
    },
    resetDialog() {
      this.selectedIconUrl = null
      this.customIconFile = null
      this.customIconPreview = null
      this.customIconError = ''
      this.selectedStyle = 'standard'
      if (this.$refs.customIconInput) {
        this.$refs.customIconInput.value = ''
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

