<template>
  <div v-if="feature" class="absolute bottom-4 right-4 bg-white rounded-lg shadow-xl border border-gray-200 z-10 max-w-md w-80">
    <div class="p-4">
      <!-- Header -->
      <div class="flex items-start justify-between mb-4">
        <h3 class="text-lg font-bold text-gray-900 pr-2">{{ getFeatureName(feature) }}</h3>
        <div class="flex items-center space-x-2 flex-shrink-0">
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
      <div class="mb-4">
        <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
          {{ getFeatureGeometryType(feature) }}
        </span>
      </div>

      <!-- Description -->
      <div v-if="getFeatureDescription(feature)" class="mb-4">
        <div class="text-sm text-gray-700 prose prose-sm max-w-none prose-headings:text-gray-900 prose-p:text-gray-700 prose-a:text-blue-600 prose-strong:text-gray-900 prose-ul:text-gray-700 prose-ol:text-gray-700" v-html="renderMarkdown(getFeatureDescription(feature))"></div>
      </div>

      <!-- Tags -->
      <div v-if="getFeatureTags(feature).length > 0" class="flex flex-wrap gap-2">
        <span
          v-for="tag in getFeatureTags(feature)"
          :key="tag"
          class="inline-flex items-center px-2 py-1 rounded-md text-xs font-medium bg-gray-100 text-gray-800"
        >
          {{ tag }}
        </span>
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
  emits: ['close', 'edit'],
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
      if (Array.isArray(properties.tags)) {
        return properties.tags.filter(tag => tag && tag.trim() !== '')
      }
      return []
    },
    renderMarkdown(markdown) {
      if (!markdown) return ''
      return marked.parse(markdown)
    }
  }
}
</script>

