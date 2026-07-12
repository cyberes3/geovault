<template>
  <BaseModal
    :is-open="isOpen"
    :title="collection ? 'Edit Collection' : 'Create New Collection'"
    max-width="6xl"
    :close-on-escape="false"
    @close="closeDialog"
  >
    <form @submit.prevent="saveCollection" class="flex flex-col flex-1 min-h-0">
      <!-- Scrollable Content -->
      <div class="flex-1 overflow-y-auto p-6 lg:p-8 min-h-0">
              <!-- Name Input -->
              <div class="mb-4">
                <label class="block text-sm font-medium text-gray-700 mb-1">
                  Name <span class="text-red-500">*</span>
                </label>
                <input
                  v-model="formData.name"
                  type="text"
                  required
                  @keydown.enter.prevent
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
                      <MagnifyingGlassIcon class="h-5 w-5 text-gray-400" />
                    </div>
                    <input
                      v-model="tagSearchQuery"
                      type="text"
                      @keydown.enter.prevent
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
                  <div class="mt-3">
                    <p class="text-xs text-gray-500 mb-2">Selected tags: {{ formData.tags.length }}</p>
                  </div>
                </div>

                <!-- Features Section -->
                <div>
                  <SearchableCheckboxList
                    label="Features"
                    description="Select individual features to include"
                    :items="availableFeatures"
                    :model-value="formData.feature_ids"
                    @update:model-value="formData.feature_ids = $event"
                    :get-item-id="(f) => String(f.properties.database_id)"
                    :get-item-label="(f) => f.properties.name || 'Unnamed Feature'"
                    search-placeholder="Search features..."
                    :loading="loadingFeatures"
                    loading-message="Loading features..."
                    empty-message="No features available"
                    selected-count-label="Selected Features"
                    :filter-fn="featureFilterFn"
                  />
                </div>
              </div>

              <!-- Error Message -->
              <div v-if="error" class="mb-4 p-3 bg-red-50 border border-red-200 rounded-md">
                <p class="text-sm text-red-800">{{ error }}</p>
              </div>
      </div>
    </form>

    <template #footer>
      <BaseButton
        type="button"
        @click="closeDialog"
        variant="white"
        size="md"
        title="Cancel"
      >
        Cancel
      </BaseButton>
      <BaseButton
        type="button"
        @click="saveCollection"
        :disabled="saving || !formData.name.trim()"
        variant="primary"
        color="blue"
        size="md"
        title="Save Collection"
      >
        <span v-if="saving">Saving...</span>
        <span v-else>Save Collection</span>
      </BaseButton>
    </template>
  </BaseModal>
</template>

<script>
import { getFeaturesByTag, getAllFeatures } from "@/api/services/featuresApi";
import { saveCollection } from "@/api/services/collectionsApi";
import { getApiErrorMessage } from "@/utils/apiError";
import BaseModal from '@/components/parts/BaseModal.vue'
import BaseButton from '@/components/parts/BaseButton.vue'
import Loader from "@/components/parts/Loader.vue";
import SearchableCheckboxList from '@/components/parts/SearchableCheckboxList.vue';
import { MagnifyingGlassIcon } from '@heroicons/vue/24/outline';
import { sortTagsByPriority, sortUserTagsAlphabetically, isSystemTag } from "@/utils/tagUtils.js";

export default {
  name: 'CollectionDialog',
  components: {
    BaseModal,
    BaseButton,
    Loader,
    MagnifyingGlassIcon,
    SearchableCheckboxList
  },
  props: {
    isOpen: {
      type: Boolean,
      default: true
    },
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
      let tags;
      if (!this.tagSearchQuery.trim()) {
        tags = this.availableTags;
      } else {
        const query = this.tagSearchQuery.toLowerCase();
        tags = this.availableTags.filter(tag => 
          tag.toLowerCase().includes(query)
        );
      }
      // Separate and sort: user tags alphabetically, system tags by priority
      const userTags = tags.filter(tag => !isSystemTag(tag));
      const systemTags = tags.filter(tag => isSystemTag(tag));
      const sortedUserTags = sortUserTagsAlphabetically(userTags);
      const sortedSystemTags = sortTagsByPriority(systemTags);
      return [...sortedUserTags, ...sortedSystemTags];
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        this.$nextTick(() => {
          this.syncFormFromProps();
        });
      }
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
    featureFilterFn(query, feature) {
      const q = query.toLowerCase();
      const name = (feature.properties.name || '').toLowerCase();
      const description = (feature.properties.description || '').toLowerCase();
      return name.includes(q) || description.includes(q);
    },
    async fetchTags() {
      this.loadingTags = true;
      try {
        const data = await getFeaturesByTag();

        // Get user tags and system tags separately
        const userTags = data.user_tags ? Object.keys(data.user_tags) : [];
        const systemTags = data.system_tags ? Object.keys(data.system_tags) : [];

        // Sort user tags alphabetically, system tags by priority
        const sortedUserTags = sortUserTagsAlphabetically(userTags);
        const sortedSystemTags = sortTagsByPriority(systemTags);

        // Combine: user tags first, then system tags
        this.availableTags = [...sortedUserTags, ...sortedSystemTags];
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
        const data = await getAllFeatures();
        this.availableFeatures = (data.data && data.data.features) || [];
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
        await saveCollection({
          name: this.formData.name.trim(),
          description: this.formData.description.trim() || null,
          tags: this.formData.tags,
          feature_ids: this.formData.feature_ids.map(id => parseInt(id))
        }, this.collection?.id);

        this.$emit('saved');
      } catch (error) {
        console.error('Error saving collection:', error);
        this.error = getApiErrorMessage(error, 'Failed to save collection. Please try again.');
      } finally {
        this.saving = false;
      }
    },
    closeDialog() {
      this.$emit('close');
    },
    syncFormFromProps() {
      this.tagSearchQuery = '';
      this.error = null;
      if (this.collection) {
        this.formData.name = this.collection.name || '';
        this.formData.description = this.collection.description || '';
        this.formData.tags = this.collection.tags ? [...this.collection.tags] : [];
        this.formData.feature_ids = this.collection.feature_ids
          ? this.collection.feature_ids.map((id) => String(id))
          : [];
      } else {
        this.formData.name = '';
        this.formData.description = '';
        this.formData.tags = [];
        this.formData.feature_ids = [];
      }
    },
    handleBackdropMouseDown(event) {
      if (event.target === event.currentTarget) {
        this.closeDialog();
      }
    }
  },
  mounted() {
    if (this.isOpen) {
      this.syncFormFromProps();
    }
    this.fetchTags();
    this.fetchFeatures();
  }
};
</script>

