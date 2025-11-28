<template>
  <div
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
        class="bg-white flex flex-col w-full h-full sm:h-[90vh] sm:max-w-6xl sm:rounded-lg shadow-xl overflow-hidden"
        @mousedown.stop
        @click.stop
      >
        <!-- Header -->
        <div class="flex items-center justify-between px-6 lg:px-8 py-4 border-b border-gray-200 bg-gray-50">
          <h3 class="text-lg font-medium text-gray-900">
            {{ collection ? 'Edit Collection' : 'Create New Collection' }}
          </h3>
          <button
            @click="closeDialog"
            class="text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition ease-in-out duration-150"
            title="Close dialog"
          >
            <svg class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- Content -->
        <div class="flex-1 overflow-y-auto bg-white p-6 lg:p-8">
          <form @submit.prevent="saveCollection">
            <!-- Name Input -->
            <div class="mb-4">
              <label class="block text-sm font-medium text-gray-700 mb-1">
                Name <span class="text-red-500">*</span>
              </label>
              <input
                v-model="formData.name"
                type="text"
                required
                class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
                placeholder="Enter collection name"
              />
            </div>

            <!-- Description Input -->
            <div class="mb-6">
              <label class="block text-sm font-medium text-gray-700 mb-1">
                Description
              </label>
              <textarea
                v-model="formData.description"
                rows="3"
                class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500"
                placeholder="Enter collection description (optional)"
              ></textarea>
            </div>

            <!-- Tags and Features Section - Two Column Layout -->
            <div class="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
              <!-- Tags Section -->
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-2">
                  Tags
                </label>
                <p class="text-xs text-gray-500 mb-3">Select tags to include all features with those tags</p>
                
                <!-- Tag Search -->
                <div class="relative mb-3">
                  <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <svg class="h-5 w-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                    </svg>
                  </div>
                  <input
                    v-model="tagSearchQuery"
                    type="text"
                    class="block w-full pl-10 pr-3 py-2 border border-gray-300 rounded-md leading-5 bg-white placeholder-gray-500 focus:outline-none focus:placeholder-gray-400 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 sm:text-sm"
                    placeholder="Search tags..."
                  />
                </div>

                <!-- Tags List -->
                <div v-if="loadingTags" class="text-center py-4">
                  <Loader size="sm" layout="centered" message="Loading tags..." />
                </div>

                <div v-else-if="filteredTags.length === 0" class="text-center py-4 text-gray-500 text-sm">
                  <p>No tags available</p>
                </div>

                <div v-else class="max-h-48 overflow-y-auto border border-gray-200 rounded-md p-2">
                  <div
                    v-for="tag in filteredTags"
                    :key="tag"
                    class="flex items-center px-3 py-2 hover:bg-gray-50 rounded space-x-3"
                  >
                    <input
                      type="checkbox"
                      :id="`tag-${tag}`"
                      class="checkbox-custom"
                      :checked="formData.tags.includes(tag)"
                      @change="onTagCheckboxChange(tag, $event.target.checked)"
                    />
                    <label
                      :for="`tag-${tag}`"
                      class="text-sm text-gray-700 truncate"
                    >
                      {{ tag }}
                    </label>
                  </div>
                </div>

                <!-- Selected Tags -->
                <div v-if="formData.tags.length > 0" class="mt-3">
                  <p class="text-xs text-gray-500 mb-2">Selected tags:</p>
                  <div class="flex flex-wrap gap-2">
                    <span
                      v-for="tag in formData.tags"
                      :key="tag"
                      @click="removeTag(tag)"
                      class="inline-flex items-center px-2 py-1 rounded-full text-xs font-medium bg-blue-100 text-blue-700 cursor-pointer hover:bg-blue-200"
                    >
                      {{ tag }}
                      <button
                        type="button"
                        @click.stop="removeTag(tag)"
                        class="ml-1 text-blue-500 hover:text-blue-700"
                        title="Remove tag"
                      >
                        <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
                        </svg>
                      </button>
                    </span>
                  </div>
                </div>
              </div>

              <!-- Features Section -->
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-2">
                  Features
                </label>
                <p class="text-xs text-gray-500 mb-3">Select individual features to include</p>
                
                <!-- Feature Search -->
                <div class="relative mb-3">
                  <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                    <svg class="h-5 w-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                    </svg>
                  </div>
                  <input
                    v-model="featureSearchQuery"
                    type="text"
                    class="block w-full pl-10 pr-3 py-2 border border-gray-300 rounded-md leading-5 bg-white placeholder-gray-500 focus:outline-none focus:placeholder-gray-400 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 sm:text-sm"
                    placeholder="Search features..."
                  />
                </div>

                <!-- Features List -->
                <div v-if="loadingFeatures" class="text-center py-4">
                  <Loader size="sm" layout="centered" message="Loading features..." />
                </div>

                <div v-else-if="filteredFeatures.length === 0" class="text-center py-4 text-gray-500 text-sm">
                  <p>No features available</p>
                </div>

                <div v-else class="max-h-48 overflow-y-auto border border-gray-200 rounded-md p-2">
                  <div
                    v-for="feature in filteredFeatures"
                    :key="feature.properties._id"
                    class="flex items-center px-3 py-2 hover:bg-gray-50 rounded space-x-3"
                  >
                    <input
                      type="checkbox"
                      :id="`feature-${feature.properties._id}`"
                      class="checkbox-custom"
                      :checked="isFeatureSelected(feature)"
                      @change="onFeatureCheckboxChange(feature, $event.target.checked)"
                    />
                    <label
                      :for="`feature-${feature.properties._id}`"
                      class="text-sm text-gray-700 truncate"
                    >
                      {{ feature.properties.name || 'Unnamed Feature' }}
                    </label>
                  </div>
                </div>

                <!-- Selected Features -->
                <div class="mt-3">
                  <p class="text-xs text-gray-500 mb-2">Selected features: {{ formData.feature_ids.length }}</p>
                </div>
              </div>
            </div>

            <!-- Error Message -->
            <div v-if="error" class="mb-4 p-3 bg-red-50 border border-red-200 rounded-md">
              <p class="text-sm text-red-800">{{ error }}</p>
            </div>

            <!-- Actions -->
            <div class="flex justify-end space-x-3 pt-4 border-t border-gray-200">
              <button
                type="button"
                @click="closeDialog"
                class="px-4 py-2 border border-gray-300 rounded-md shadow-sm text-sm font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                title="Cancel"
              >
                Cancel
              </button>
              <button
                type="submit"
                :disabled="saving || !formData.name.trim()"
                class="px-4 py-2 border border-transparent rounded-md shadow-sm text-sm font-medium text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                title="Save collection"
              >
                <span v-if="saving">Saving...</span>
                <span v-else>Save Collection</span>
              </button>
            </div>
          </form>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { getCookie } from "@/assets/js/auth.js";
import Loader from "@/components/parts/Loader.vue";

export default {
  name: 'CollectionDialog',
  components: {
    Loader
  },
  props: {
    collection: {
      type: Object,
      default: null
    }
  },
  emits: ['close', 'saved'],
  data() {
    return {
      formData: {
        name: '',
        description: '',
        tags: [],
        feature_ids: []
      },
      tagSearchQuery: '',
      featureSearchQuery: '',
      availableTags: [],
      availableFeatures: [],
      loadingTags: false,
      loadingFeatures: false,
      saving: false,
      error: null
    }
  },
  computed: {
    filteredTags() {
      if (!this.tagSearchQuery.trim()) {
        return this.availableTags.filter(tag => !this.formData.tags.includes(tag));
      }
      const query = this.tagSearchQuery.toLowerCase();
      return this.availableTags.filter(tag => 
        tag.toLowerCase().includes(query) && !this.formData.tags.includes(tag)
      );
    },
    filteredFeatures() {
      if (!this.featureSearchQuery.trim()) {
        return this.availableFeatures;
      }
      const query = this.featureSearchQuery.toLowerCase();
      return this.availableFeatures.filter(f => {
        const name = (f.properties.name || '').toLowerCase();
        const description = (f.properties.description || '').toLowerCase();
        return name.includes(query) || description.includes(query);
      });
    }
  },
  methods: {
    onTagCheckboxChange(tag, checked) {
      const index = this.formData.tags.indexOf(tag);
      if (checked && index === -1) {
        this.formData.tags.push(tag);
      } else if (!checked && index > -1) {
        this.formData.tags.splice(index, 1);
      }
    },
    isFeatureSelected(feature) {
      const featureId = String(feature.properties._id);
      return this.formData.feature_ids.includes(featureId);
    },
    onFeatureCheckboxChange(feature, checked) {
      const featureId = String(feature.properties._id);
      const index = this.formData.feature_ids.indexOf(featureId);
      if (checked && index === -1) {
        this.formData.feature_ids.push(featureId);
      } else if (!checked && index > -1) {
        this.formData.feature_ids.splice(index, 1);
      }
    },
    async fetchTags() {
      this.loadingTags = true;
      try {
        const response = await fetch('/api/features/by-tag/');
        const data = await response.json();
        
        if (response.ok) {
          // Get user tags and system tags separately
          const userTags = data.user_tags ? Object.keys(data.user_tags).sort() : [];
          const systemTags = data.system_tags ? Object.keys(data.system_tags).sort() : [];
          
          // Combine with user tags first, then system tags
          this.availableTags = [...userTags, ...systemTags];
        } else {
          this.availableTags = [];
        }
      } catch (error) {
        console.error('Error fetching tags:', error);
        this.availableTags = [];
      } finally {
        this.loadingTags = false;
      }
    },
    async fetchFeatures() {
      this.loadingFeatures = true;
      try {
        const response = await fetch('/api/features/all/');
        const data = await response.json();
        
        if (response.ok && data.data && data.data.features) {
          this.availableFeatures = data.data.features;
        } else {
          this.availableFeatures = [];
        }
      } catch (error) {
        console.error('Error fetching features:', error);
        this.availableFeatures = [];
      } finally {
        this.loadingFeatures = false;
      }
    },
    removeTag(tag) {
      const index = this.formData.tags.indexOf(tag);
      if (index > -1) {
        this.formData.tags.splice(index, 1);
      }
    },
    async saveCollection() {
      if (!this.formData.name.trim()) {
        this.error = 'Name is required';
        return;
      }

      this.saving = true;
      this.error = null;

      try {
        const url = this.collection 
          ? `/api/collections/${this.collection.id}/update/`
          : '/api/collections/create/';
        
        const method = this.collection ? 'PUT' : 'POST';
        
        const response = await fetch(url, {
          method: method,
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': getCookie('csrftoken')
          },
          body: JSON.stringify({
            name: this.formData.name.trim(),
            description: this.formData.description.trim() || null,
            tags: this.formData.tags,
            feature_ids: this.formData.feature_ids.map(id => parseInt(id))
          })
        });

        const data = await response.json();

        if (response.ok) {
          this.$emit('saved');
        } else {
          this.error = data.error || 'Failed to save collection';
        }
      } catch (error) {
        console.error('Error saving collection:', error);
        this.error = 'Failed to save collection. Please try again.';
      } finally {
        this.saving = false;
      }
    },
    closeDialog() {
      this.$emit('close');
    },
    handleBackdropMouseDown(event) {
      if (event.target === event.currentTarget) {
        this.closeDialog();
      }
    },
    handleEscapeKey(event) {
      if (event.key === 'Escape') {
        this.closeDialog();
      }
    }
  },
  mounted() {
    // Initialize form data from collection if editing
    if (this.collection) {
      this.formData.name = this.collection.name || '';
      this.formData.description = this.collection.description || '';
      this.formData.tags = this.collection.tags ? [...this.collection.tags] : [];
      this.formData.feature_ids = this.collection.feature_ids
        ? this.collection.feature_ids.map(id => String(id))
        : [];
    }
    
    // Fetch available tags and features
    this.fetchTags();
    this.fetchFeatures();

    // Prevent background scroll and move modal to body to avoid layout offsets
    document.body.classList.add('overflow-hidden');
    this.$nextTick(() => {
      if (this.$el && this.$el.parentNode !== document.body) {
        document.body.appendChild(this.$el);
      }
    });
    document.addEventListener('keydown', this.handleEscapeKey);
  },
  beforeUnmount() {
    document.body.classList.remove('overflow-hidden');
    document.removeEventListener('keydown', this.handleEscapeKey);
  }
};
</script>

