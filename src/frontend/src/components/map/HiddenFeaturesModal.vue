<template>
  <div
    v-if="visible"
    class="fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-40"
    @click.self="$emit('close')"
  >
    <div class="bg-white rounded-lg shadow-xl w-full max-w-md mx-4 h-[70vh] max-h-[70vh] flex flex-col">
      <div class="flex items-center justify-between px-4 py-3 border-b border-gray-200">
        <h3 class="text-base font-semibold text-gray-900">
          Hidden Features
        </h3>
        <button
          type="button"
          class="text-gray-400 hover:text-gray-600"
          @click="$emit('close')"
          title="Close"
        >
          ✕
        </button>
      </div>

      <!-- Search bar -->
      <div class="px-4 pt-3 pb-1 border-b border-gray-100">
        <div class="relative">
          <input
            v-model="searchQuery"
            type="text"
            placeholder="Search features..."
            class="w-full px-2 py-1.5 pr-7 text-xs border border-gray-300 rounded focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
          />
          <button
            v-if="searchQuery"
            type="button"
            class="absolute right-1 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 focus:outline-none"
            @click="searchQuery = ''"
            title="Clear search"
          >
            <XMarkIcon class="w-4 h-4" />
          </button>
        </div>
      </div>

      <div class="flex-1 overflow-y-auto px-4 py-3">
        <div v-if="!filteredItems.length" class="text-xs text-gray-500 text-center py-3">
          {{ searchQuery ? 'No matching hidden features.' : 'No hidden features.' }}
        </div>
        <div v-else class="space-y-0.5">
          <div
            v-for="item in filteredItems"
            :key="item.id"
            class="px-1.5 py-1 bg-gray-50 hover:bg-gray-100 transition-colors flex items-center cursor-pointer"
            :style="{ borderLeft: `3px solid ${getGeometryTypeColor(item.geometry_type)}` }"
            @click="$emit('unhide', item.id)"
            :title="`Un-hide ${item.name || 'Unnamed feature'}`"
          >
            <div class="flex-1 min-w-0">
              <div class="text-xs text-gray-900 truncate">
                {{ item.name || 'Unnamed feature' }}
              </div>
            </div>
            <button
              type="button"
              class="ml-1 px-2 py-1 text-xs font-medium text-blue-600 hover:text-blue-800 hover:bg-blue-50 rounded transition-colors"
              title="Un-hide this feature"
              @click.stop="$emit('unhide', item.id)"
            >
              Show
            </button>
          </div>
        </div>
      </div>

      <div class="flex items-center justify-end px-4 py-3 border-t border-gray-200">
        <button
          type="button"
          class="inline-flex items-center px-3 py-1.5 text-sm font-medium text-white bg-blue-600 hover:bg-blue-700 rounded disabled:opacity-50 disabled:cursor-not-allowed"
          :disabled="!items || items.length === 0 || isUnhidingAll"
          @click="handleUnhideAll"
        >
          <Loader
            v-if="isUnhidingAll"
            size="sm"
            layout="inline"
            :showMessage="false"
            color="#ffffff"
          />
          {{ isUnhidingAll ? 'Un-hiding...' : 'Un-hide All' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { XMarkIcon } from '@heroicons/vue/24/outline'
import { getGeometryTypeColor } from '@/utils/geometryColors.js'
import Loader from '@/components/parts/Loader.vue'

export default {
  name: 'HiddenFeaturesModal',
  components: {
    XMarkIcon,
    Loader
  },
  props: {
    visible: {
      type: Boolean,
      default: false,
    },
    items: {
      type: Array,
      default: () => [],
    },
  },
  emits: ['close', 'unhide', 'unhide-all'],
  data() {
    return {
      searchQuery: '',
      isUnhidingAll: false,
    }
  },
  computed: {
    filteredItems() {
      if (!Array.isArray(this.items) || !this.items.length) {
        return []
      }
      const q = this.searchQuery.trim().toLowerCase()
      if (!q) {
        return this.items
      }
      return this.items.filter(item => {
        const name = (item.name || '').toString().toLowerCase()
        const id = (item.id || '').toString().toLowerCase()
        return name.includes(q) || id.includes(q)
      })
    },
  },
  mounted() {
    window.addEventListener('keydown', this.handleKeydown)
  },
  beforeUnmount() {
    window.removeEventListener('keydown', this.handleKeydown)
  },
  methods: {
    handleKeydown(event) {
      if (!this.visible) return
      if (event.key === 'Escape' || event.key === 'Esc') {
        event.preventDefault()
        this.$emit('close')
      }
    },
    handleUnhideAll() {
      this.isUnhidingAll = true
      this.$emit('unhide-all')
      // The parent will handle the operation, and we'll reset after a delay
      // or when the modal closes
    },
    getGeometryTypeColor,
  },
  watch: {
    visible(newVal) {
      if (!newVal) {
        // Reset loading state when modal closes
        this.isUnhidingAll = false
      }
    },
    $route() {
      // Close modal when route changes
      if (this.visible) {
        this.$emit('close')
      }
    }
  },
}
</script>


