<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="mb-4">
        <h1 class="text-2xl font-bold text-gray-900 mb-2">Tags</h1>
      </div>

      <!-- Explanatory Text -->
      <div class="m-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
        <p class="text-sm text-gray-700">
          Tags are labels attached to your geographic features that help you organize, filter, and find them easily.
          You can create custom tags, edit or delete them, share them with others, and each feature can have multiple tags for flexible categorization.
        </p>
      </div>

      <!-- Search Input -->
      <div class="relative">
        <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
          <svg class="h-5 w-5 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
          </svg>
        </div>
        <input
            v-model="searchQuery"
            class="block w-full pl-10 pr-3 py-2 border border-gray-300 rounded-md leading-5 bg-white placeholder-gray-500 focus:outline-none focus:placeholder-gray-400 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 sm:text-sm"
            placeholder="Search tags..."
            type="text"
        />
        <button
            v-if="searchQuery"
            class="absolute inset-y-0 right-0 pr-3 flex items-center"
            @click="searchQuery = ''"
            title="Clear search"
        >
          <svg class="h-5 w-5 text-gray-400 hover:text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path d="M6 18L18 6M6 6l12 12" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
          </svg>
        </button>
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
          <path d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
        </svg>
        <p class="text-red-800">{{ error }}</p>
      </div>
    </div>

    <!-- Empty State -->
    <div v-else-if="!loading && Object.keys(tagsData).length === 0" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="text-center py-12">
        <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
        </svg>
        <h3 class="mt-2 text-sm font-medium text-gray-900">No tags found</h3>
        <p class="mt-1 text-sm text-gray-500">Tags will appear here once you import features with tags.</p>
      </div>
    </div>

    <!-- No Search Results -->
    <div v-else-if="!loading && Object.keys(filteredTagsData).length === 0 && searchQuery" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="text-center py-12">
        <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
        </svg>
        <h3 class="mt-2 text-sm font-medium text-gray-900">No tags match your search</h3>
        <p class="mt-1 text-sm text-gray-500">Try adjusting your search query.</p>
      </div>
    </div>

    <!-- Tags List -->
    <div v-else-if="!loading && Object.keys(filteredTagsData).length > 0" class="space-y-4">
      <div
          v-for="(features, tag) in filteredTagsData"
          :key="tag"
          class="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden"
      >
        <!-- Tag Header -->
        <div class="bg-gray-50 px-6 py-4 border-b border-gray-200">
          <div class="flex items-center justify-between">
            <div class="flex items-center space-x-3 flex-1">
              <span v-if="editingTag !== tag" class="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium bg-blue-100 text-blue-800 border border-blue-200">
                {{ tag }}
              </span>
              <input
                  v-else
                  ref="tagEditInput"
                  v-model="editingTagValue"
                  class="inline-flex items-center px-3 py-1 rounded-md text-sm font-medium bg-white text-gray-900 border border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1"
                  type="text"
                  @keyup.enter="saveTagEdit(tag)"
                  @keyup.esc="cancelTagEdit"
                  @focus.stop
                  @click.stop
              />
            </div>
            <div v-if="editingTag !== tag" class="flex items-center space-x-1">
              <button
                  class="p-1.5 text-gray-400 hover:text-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                  title="Share tag"
                  type="button"
                  @click.stop.prevent="openShareDialog(tag)"
                  @mousedown.stop.prevent
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M8.684 13.342C8.885 12.938 9 12.482 9 12c0-.482-.115-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z" stroke-linecap="round" stroke-linejoin="round"
                        stroke-width="2"></path>
                </svg>
              </button>
              <button
                  class="p-1.5 text-gray-400 hover:text-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                  title="Edit tag name"
                  type="button"
                  @click.stop.prevent="startTagEdit(tag)"
                  @mousedown.stop.prevent
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
              </button>
              <button
                  class="p-1.5 text-gray-400 hover:text-red-600 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-1 rounded"
                  title="Delete tag"
                  type="button"
                  @click.stop.prevent="deleteTag(tag)"
                  @mousedown.stop.prevent
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
              </button>
            </div>
            <button
                v-else
                class="ml-2 p-1.5 bg-blue-600 text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                title="Save tag name"
                @click.stop="saveTagEdit(tag)"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path d="M5 13l4 4L19 7" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
              </svg>
            </button>
          </div>
        </div>

        <!-- Features List -->
        <div class="divide-y divide-gray-200">
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
                </div>
              </div>
              <div class="ml-4 flex-shrink-0 relative z-10 flex items-center space-x-2">
                <button
                    class="p-1.5 text-gray-400 hover:text-red-600 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-1 rounded"
                    title="Remove this feature from tag"
                    type="button"
                    @click.stop.prevent="removeTagFromFeature(tag, feature)"
                >
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path d="M6 18L18 6M6 6l12 12" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                  </svg>
                </button>
                <router-link
                    v-if="feature.properties._id"
                    :to="{ path: '/map', query: { featureId: feature.properties._id } }"
                    class="inline-flex items-center px-3 py-1.5 border border-gray-300 rounded-md text-xs font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 cursor-pointer"
                    @click.stop
                >
                  View on Map
                  <svg class="w-3 h-3 ml-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                  </svg>
                </router-link>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Share Dialog -->
    <TagShareDialog
        :isOpen="shareDialogOpen"
        :tag="selectedTagForShare"
        @close="shareDialogOpen = false"
    />
  </div>
</template>

<script>
import {authMixin} from "@/assets/js/authMixin.js";
import TagShareDialog from "./TagShareDialog.vue";

export default {
  name: 'TagsPage',
  components: {
    TagShareDialog
  },
  mixins: [authMixin],
  data() {
    return {
      tagsData: {},
      loading: true,
      error: null,
      searchQuery: '', // Search query for filtering tags
      editingTag: null, // Tag currently being edited
      editingTagValue: '', // Value of tag being edited
      shareDialogOpen: false, // Whether share dialog is open
      selectedTagForShare: '' // Tag selected for sharing
    }
  },
  computed: {
    filteredTagsData() {
      if (!this.searchQuery.trim()) {
        return this.tagsData;
      }

      const query = this.searchQuery.toLowerCase().trim();
      const filtered = {};

      for (const [tag, features] of Object.entries(this.tagsData)) {
        if (tag.toLowerCase().includes(query)) {
          filtered[tag] = features;
        }
      }

      return filtered;
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
    startTagEdit(tag, event) {
      if (event) {
        event.preventDefault();
        event.stopPropagation();
      }
      this.editingTag = tag;
      this.editingTagValue = tag;
      // Focus the input after it's rendered
      this.$nextTick(() => {
        // Find the tag edit input (not the search input which has a placeholder)
        const allInputs = this.$el.querySelectorAll('input[type="text"]');
        const tagInput = Array.from(allInputs).find(input => !input.placeholder);
        if (tagInput) {
          // Use setTimeout to ensure focus happens after any other focus events
          setTimeout(() => {
            tagInput.focus();
            tagInput.select();
          }, 0);
        }
      });
    },
    cancelTagEdit() {
      this.editingTag = null;
      this.editingTagValue = '';
    },
    async saveTagEdit(oldTag) {
      const newTag = this.editingTagValue.trim();

      // Validate the new tag name
      if (!newTag) {
        alert('Tag name cannot be empty');
        return;
      }

      // Validate tag length (max 255 characters)
      if (newTag.length > 255) {
        alert('Tag name cannot exceed 255 characters');
        return;
      }

      // Validate tag format: no control characters (except tab, newline, carriage return)
      if (/[\x00-\x08\x0B\x0C\x0E-\x1F]/.test(newTag)) {
        alert('Tag name cannot contain control characters');
        return;
      }

      if (newTag === oldTag) {
        // No change, just cancel
        this.cancelTagEdit();
        return;
      }

      // Check if the new tag already exists
      if (this.tagsData[newTag]) {
        alert(`Tag "${newTag}" already exists. Please choose a different name.`);
        return;
      }

      try {
        // Get all features with this tag
        const features = this.tagsData[oldTag] || [];

        // Update each feature's tags
        const updatePromises = features.map(async (feature) => {
          if (!feature.properties._id) {
            return;
          }

          // Get current tags
          const currentTags = Array.isArray(feature.properties.tags)
              ? [...feature.properties.tags]
              : [];

          // Replace old tag with new tag
          const tagIndex = currentTags.indexOf(oldTag);
          if (tagIndex !== -1) {
            currentTags[tagIndex] = newTag;
          } else {
            // Tag not found in array, add it (shouldn't happen, but handle gracefully)
            currentTags.push(newTag);
          }

          // Update the feature
          const csrfToken = this.getCookie('csrftoken');
          const response = await fetch(`/api/data/feature/${feature.properties._id}/update-metadata/`, {
            method: 'PUT',
            headers: {
              'Content-Type': 'application/json',
              'X-CSRFToken': csrfToken || ''
            },
            body: JSON.stringify({
              tags: currentTags
            })
          });

          if (!response.ok) {
            throw new Error(`Failed to update feature ${feature.properties._id}`);
          }
        });

        // Wait for all updates to complete
        await Promise.all(updatePromises);

        // Update local state
        const newTagsData = {...this.tagsData};
        newTagsData[newTag] = newTagsData[oldTag];
        delete newTagsData[oldTag];
        this.tagsData = newTagsData;

        // Cancel edit mode
        this.cancelTagEdit();

        // Refresh the data to ensure consistency
        await this.fetchTagsData();

        // Scroll to the newly renamed tag after data refresh
        this.$nextTick(() => {
          this.scrollToTag(newTag);
        });
      } catch (error) {
        console.error('Error updating tag:', error);
        alert(`Failed to update tag: ${error.message}`);
      }
    },
    scrollToTag(tagName) {
      // Find the tag element by looking for the tag name in the DOM
      // The tag container has class "bg-white rounded-lg shadow-sm border border-gray-200"
      const tagContainers = this.$el.querySelectorAll('.bg-white.rounded-lg.shadow-sm');
      for (const container of tagContainers) {
        // Check if this container's header contains the tag name
        const tagHeader = container.querySelector('.bg-gray-50');
        if (tagHeader) {
          const tagSpan = tagHeader.querySelector('span.inline-flex');
          if (tagSpan && tagSpan.textContent.trim() === tagName) {
            // Scroll the container into view with smooth behavior
            container.scrollIntoView({behavior: 'smooth', block: 'center'});
            break;
          }
        }
      }
    },
    async deleteTag(tag) {
      // Get the number of features with this tag
      const features = this.tagsData[tag] || [];
      const featureCount = features.length;

      // Show confirmation dialog
      const confirmMessage = `Are you sure you want to delete the tag "${tag}"?\n\nThis will remove the tag from ${featureCount} ${featureCount === 1 ? 'feature' : 'features'}.`;
      if (!confirm(confirmMessage)) {
        return;
      }

      try {
        // Remove the tag from all features
        const updatePromises = features.map(async (feature) => {
          if (!feature.properties._id) {
            return;
          }

          // Get current tags
          const currentTags = Array.isArray(feature.properties.tags)
              ? [...feature.properties.tags]
              : [];

          // Remove the tag from the array
          const filteredTags = currentTags.filter(t => t !== tag);

          // Update the feature
          const csrfToken = this.getCookie('csrftoken');
          const response = await fetch(`/api/data/feature/${feature.properties._id}/update-metadata/`, {
            method: 'PUT',
            headers: {
              'Content-Type': 'application/json',
              'X-CSRFToken': csrfToken || ''
            },
            body: JSON.stringify({
              tags: filteredTags
            })
          });

          if (!response.ok) {
            throw new Error(`Failed to update feature ${feature.properties._id}`);
          }
        });

        // Wait for all updates to complete
        await Promise.all(updatePromises);

        // Remove tag from local state
        const newTagsData = {...this.tagsData};
        delete newTagsData[tag];
        this.tagsData = newTagsData;

        // Refresh the data to ensure consistency
        await this.fetchTagsData();
      } catch (error) {
        console.error('Error deleting tag:', error);
        alert(`Failed to delete tag: ${error.message}`);
      }
    },
    async removeTagFromFeature(tag, feature) {
      if (!feature.properties._id) {
        return;
      }

      // Show confirmation dialog
      const featureName = feature.properties.name || 'Unnamed Feature';
      const confirmMessage = `Are you sure you want to remove the tag "${tag}" from "${featureName}"?`;
      if (!confirm(confirmMessage)) {
        return;
      }

      try {
        // Get current tags
        const currentTags = Array.isArray(feature.properties.tags)
            ? [...feature.properties.tags]
            : [];

        // Remove the tag from the array
        const filteredTags = currentTags.filter(t => t !== tag);

        // Update the feature
        const csrfToken = this.getCookie('csrftoken');
        const response = await fetch(`/api/data/feature/${feature.properties._id}/update-metadata/`, {
          method: 'PUT',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': csrfToken || ''
          },
          body: JSON.stringify({
            tags: filteredTags
          })
        });

        if (!response.ok) {
          throw new Error(`Failed to update feature ${feature.properties._id}`);
        }

        // Update local state - remove feature from tag's list
        const newTagsData = {...this.tagsData};
        if (newTagsData[tag]) {
          newTagsData[tag] = newTagsData[tag].filter(f => f.properties._id !== feature.properties._id);
          // If no features left with this tag, remove the tag entry
          if (newTagsData[tag].length === 0) {
            delete newTagsData[tag];
          }
        }
        this.tagsData = newTagsData;
      } catch (error) {
        console.error('Error removing tag from feature:', error);
        alert(`Failed to remove tag from feature: ${error.message}`);
      }
    },
    getCookie(name) {
      let cookieValue = null;
      if (document.cookie && document.cookie !== '') {
        const cookies = document.cookie.split(';');
        for (let i = 0; i < cookies.length; i++) {
          const cookie = cookies[i].trim();
          if (cookie.substring(0, name.length + 1) === (name + '=')) {
            cookieValue = decodeURIComponent(cookie.substring(name.length + 1));
            break;
          }
        }
      }
      return cookieValue;
    },
    openShareDialog(tag) {
      this.selectedTagForShare = tag;
      this.shareDialogOpen = true;
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

