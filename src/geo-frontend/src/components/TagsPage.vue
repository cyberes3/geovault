<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 mb-2">Tags</h1>
          <p class="text-gray-600">Browse your features organized by tags</p>
        </div>
        <router-link
          to="/dashboard"
          class="inline-flex items-center px-4 py-2 border border-gray-300 rounded-md shadow-sm text-sm font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
        >
          <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 19l-7-7m0 0l7-7m-7 7h18"></path>
          </svg>
          Back to Dashboard
        </router-link>
      </div>
    </div>

    <!-- Loading State -->
    <div v-if="loading" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-center py-12">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
        <span class="ml-3 text-gray-600">Loading tags...</span>
      </div>
    </div>

    <!-- Error State -->
    <div v-else-if="error" class="bg-red-50 border border-red-200 rounded-lg p-6">
      <div class="flex items-center">
        <svg class="w-5 h-5 text-red-600 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"></path>
        </svg>
        <p class="text-red-800">{{ error }}</p>
      </div>
    </div>

    <!-- Empty State -->
    <div v-else-if="!loading && Object.keys(tagsData).length === 0" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="text-center py-12">
        <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z"></path>
        </svg>
        <h3 class="mt-2 text-sm font-medium text-gray-900">No tags found</h3>
        <p class="mt-1 text-sm text-gray-500">Tags will appear here once you import features with tags.</p>
      </div>
    </div>

    <!-- Tags List -->
    <div v-else class="space-y-4">
      <div
        v-for="(features, tag) in tagsData"
        :key="tag"
        class="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden"
      >
        <!-- Tag Header -->
        <div class="bg-gray-50 px-6 py-4 border-b border-gray-200">
          <div class="flex items-center justify-between">
            <div class="flex items-center space-x-3">
              <span class="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium bg-blue-100 text-blue-800 border border-blue-200">
                {{ tag }}
              </span>
              <span class="text-sm text-gray-500">
                {{ features.length }} {{ features.length === 1 ? 'feature' : 'features' }}
              </span>
            </div>
            <button
              @click="toggleTag(tag)"
              class="text-sm text-gray-600 hover:text-gray-900"
            >
              <svg v-if="expandedTags[tag]" class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 15l7-7 7 7"></path>
              </svg>
              <svg v-else class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"></path>
              </svg>
            </button>
          </div>
        </div>

        <!-- Features List -->
        <div v-if="expandedTags[tag]" class="divide-y divide-gray-200">
          <div
            v-for="(feature, index) in features"
            :key="feature.properties._id || index"
            class="px-6 py-4 hover:bg-gray-50 transition-colors"
          >
            <div class="flex items-start justify-between">
              <div class="flex-1">
                <h4 class="text-sm font-medium text-gray-900">
                  {{ feature.properties.name || 'Unnamed Feature' }}
                </h4>
                <p v-if="feature.properties.description" class="mt-1 text-sm text-gray-500 line-clamp-2">
                  {{ feature.properties.description }}
                </p>
                <div class="mt-2 flex items-center space-x-4 text-xs text-gray-500">
                  <span class="capitalize">
                    {{ feature.geometry?.type || 'Unknown' }}
                  </span>
                  <span v-if="feature.properties._id">
                    ID: {{ feature.properties._id }}
                  </span>
                </div>
              </div>
              <div class="ml-4 flex-shrink-0">
                <router-link
                  v-if="feature.properties._id"
                  :to="{ path: '/map', query: { featureId: feature.properties._id } }"
                  class="inline-flex items-center px-3 py-1.5 border border-gray-300 rounded-md text-xs font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                >
                  View on Map
                  <svg class="w-3 h-3 ml-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
                  </svg>
                </router-link>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import {authMixin} from "@/assets/js/authMixin.js";

export default {
  name: 'TagsPage',
  mixins: [authMixin],
  data() {
    return {
      tagsData: {},
      loading: true,
      error: null,
      expandedTags: {} // Track which tags are expanded
    }
  },
  methods: {
    async fetchTagsData() {
      this.loading = true;
      this.error = null;
      
      try {
        const response = await fetch('/api/data/features/by-tag/');
        
        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }
        
        const data = await response.json();
        
        if (data.success && data.tags) {
          this.tagsData = data.tags;
          // Expand all tags by default
          Object.keys(data.tags).forEach(tag => {
            this.expandedTags[tag] = true;
          });
        } else {
          throw new Error(data.error || 'Failed to load tags');
        }
      } catch (error) {
        console.error('Error fetching tags data:', error);
        this.error = error.message || 'Failed to load tags. Please try again.';
      } finally {
        this.loading = false;
      }
    },
    toggleTag(tag) {
      this.expandedTags[tag] = !this.expandedTags[tag];
    }
  },
  async mounted() {
    await this.fetchTagsData();
  }
}
</script>

<style scoped>
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>

