<template>
  <div v-if="feature" class="absolute bottom-4 right-4 bg-white rounded-lg shadow-xl border border-gray-200 z-10 max-w-md w-80">
    <div class="p-4">
      <!-- Header -->
      <div class="flex items-start justify-between">
        <h3 class="text-lg font-bold text-gray-900 pr-2">{{ getFeatureName(feature) }}</h3>
        <div class="flex items-center space-x-2 flex-shrink-0">
          <button
            v-if="isLineOrTrack"
            @click="$emit('show-profile')"
            class="text-gray-400 hover:text-blue-600 transition-colors"
            title="Show elevation profile"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"></path>
            </svg>
          </button>
          <button
            v-if="showEditButton"
            @click="$emit('edit')"
            class="text-gray-400 hover:text-blue-600 transition-colors"
            title="Edit feature"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"></path>
            </svg>
          </button>
          <button
            @click="$emit('zoom')"
            class="text-gray-400 hover:text-blue-600 transition-colors"
            title="Zoom to feature"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17.657 16.657L13.414 20.9a1.998 1.998 0 01-2.827 0l-4.244-4.243a8 8 0 1111.314 0z"></path>
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 11a3 3 0 11-6 0 3 3 0 016 0z"></path>
            </svg>
          </button>
          <button
            @click="$emit('close')"
            class="text-gray-400 hover:text-gray-600 transition-colors"
            title="Close"
          >
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
            </svg>
          </button>
        </div>
      </div>

      <!-- Feature Type -->
      <div class="mb-4 text-sm text-gray-600 italic">
        {{ getFeatureGeometryType(feature) }}
      </div>

      <!-- Description -->
      <div v-if="getFeatureDescription(feature)" class="mb-4">
        <div class="text-sm text-gray-700 prose prose-sm max-w-none prose-headings:text-gray-900 prose-p:text-gray-700 prose-a:text-blue-600 prose-strong:text-gray-900 prose-ul:text-gray-700 prose-ol:text-gray-700" v-html="renderMarkdown(getFeatureDescription(feature))"></div>
      </div>

      <!-- Tags -->
      <div v-if="getFeatureTags(feature).userTags.length > 0 || getFeatureTags(feature).systemTags.length > 0" class="space-y-2">
        <!-- User Tags (Blue) -->
        <div v-if="getFeatureTags(feature).userTags.length > 0" class="flex flex-wrap gap-2">
          <span
            v-for="tag in getFeatureTags(feature).userTags"
            :key="`user-${tag}`"
            class="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium bg-blue-100 text-blue-800"
          >
            {{ tag }}
          </span>
        </div>
        <!-- System Tags (Grey) -->
        <div v-if="getFeatureTags(feature).systemTags.length > 0" class="flex flex-wrap gap-2">
          <span
            v-for="tag in getFeatureTags(feature).systemTags"
            :key="`system-${tag}`"
            class="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium bg-gray-200 text-gray-600"
          >
            {{ tag }}
          </span>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { marked } from 'marked'

export default {
  name: 'FeatureInfoBox',
  props: {
    feature: {
      type: Object,
      default: null
    },
    showEditButton: {
      type: Boolean,
      default: true
    }
  },
  emits: ['close', 'edit', 'zoom', 'show-profile'],
  computed: {
    isLineOrTrack() {
      if (!this.feature) return false
      const geometry = this.feature.getGeometry()
      if (!geometry) return false
      const geomType = geometry.getType()
      return geomType === 'LineString' || geomType === 'MultiLineString'
    }
  },
  methods: {
    getFeatureName(feature) {
      const properties = feature.get('properties') || {}
      return properties.name || 'Unnamed Feature'
    },
    getFeatureGeometryType(feature) {
      const geometry = feature.getGeometry()
      if (!geometry) return 'Unknown'
      return geometry.getType()
    },
    getFeatureDescription(feature) {
      const properties = feature.get('properties') || {}
      return properties.description || null
    },
    getFeatureTags(feature) {
      const properties = feature.get('properties') || {}
      const userTags = Array.isArray(properties.tags) 
        ? properties.tags.filter(tag => tag && tag.trim() !== '')
        : []
      const systemTags = Array.isArray(properties.system_tags)
        ? properties.system_tags.filter(tag => tag && tag.trim() !== '')
        : []
      return { userTags, systemTags }
    },
    renderMarkdown(markdown) {
      if (!markdown) return ''
      return marked.parse(markdown)
    }
  }
}
</script>

