<template>
  <div v-if="isOpen" class="fixed inset-0 z-50 overflow-y-auto" aria-labelledby="modal-title" role="dialog" aria-modal="true">
    <div class="flex items-end justify-center min-h-screen pt-4 px-4 pb-20 text-center sm:block sm:p-0">
      <!-- Background overlay -->
      <div class="fixed inset-0 bg-gray-500 bg-opacity-75 transition-opacity" aria-hidden="true" @click="close"></div>

      <!-- This element is to trick the browser into centering the modal contents. -->
      <span class="hidden sm:inline-block sm:align-middle sm:h-screen" aria-hidden="true">&#8203;</span>

      <!-- Modal panel -->
      <div class="inline-block align-bottom bg-white rounded-lg text-left overflow-hidden shadow-xl transform transition-all sm:my-8 sm:align-middle sm:max-w-4xl sm:w-full">
        <div class="bg-white px-4 pt-5 pb-4 sm:p-6 sm:pb-4">
          <div class="sm:flex sm:items-start">
            <div class="mx-auto flex-shrink-0 flex items-center justify-center h-12 w-12 rounded-full bg-yellow-100 sm:mx-0 sm:h-10 sm:w-10">
              <svg class="h-6 w-6 text-yellow-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"></path>
              </svg>
            </div>
            <div class="mt-3 text-center sm:mt-0 sm:ml-4 sm:text-left w-full">
              <h3 class="text-lg leading-6 font-medium text-gray-900" id="modal-title">
                Edit Original Feature
              </h3>
              <div class="mt-2">
                <p class="text-sm text-gray-500">
                  You are editing the original feature that matches the coordinates of the duplicate feature you're trying to import.
                </p>
              </div>
            </div>
          </div>
        </div>

        <!-- Loading State -->
        <div v-if="loading" class="px-4 pb-4">
          <div class="flex items-center justify-center py-12">
            <div class="text-center">
              <div class="animate-spin h-8 w-8 border-2 border-blue-600 border-t-transparent rounded-full mx-auto mb-4"></div>
              <h3 class="text-lg font-medium text-gray-900 mb-2">Loading Feature Data</h3>
              <p class="text-gray-600">Fetching original feature information...</p>
            </div>
          </div>
        </div>

        <!-- Feature Details -->
        <div v-else-if="originalFeature" class="px-4 pb-4">
          <div class="bg-gray-50 rounded-lg p-4">
            <h4 class="text-sm font-medium text-gray-900 mb-3">Original Feature Details</h4>
            
            <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
              <!-- Name Field -->
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-2">
                  Name
                  <span v-if="originalValues.name" class="text-xs text-gray-500 font-normal ml-2">
                    (Original: "{{ originalValues.name }}")
                  </span>
                  <span v-else class="text-xs text-gray-400 font-normal ml-2">
                    (No original name)
                  </span>
                </label>
                <div class="flex items-center space-x-2">
                  <input
                    v-model="editableFeature.properties.name"
                    class="block flex-1 px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500"
                    placeholder="Feature name"
                  />
                  <button
                    v-if="originalValues.name"
                    @click="resetField('name')"
                    class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                    title="Reset to original value"
                  >
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                    </svg>
                  </button>
                </div>
              </div>

              <!-- Type Field (read-only) -->
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-2">Type</label>
                <input
                  :value="editableFeature.geometry.type"
                  class="block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed"
                  readonly
                />
              </div>

              <!-- Description Field -->
              <div class="md:col-span-2">
                <label class="block text-sm font-medium text-gray-700 mb-2">
                  Description
                  <span v-if="originalValues.description" class="text-xs text-gray-500 font-normal ml-2">
                    (Original: "{{ originalValues.description }}")
                  </span>
                  <span v-else class="text-xs text-gray-400 font-normal ml-2">
                    (No original description)
                  </span>
                </label>
                <div class="flex items-start space-x-2">
                  <textarea
                    v-model="editableFeature.properties.description"
                    class="block flex-1 px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 resize-none"
                    rows="3"
                    placeholder="Feature description"
                  ></textarea>
                  <button
                    v-if="originalValues.description"
                    @click="resetField('description')"
                    class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 mt-1"
                    title="Reset to original value"
                  >
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                    </svg>
                  </button>
                </div>
              </div>

              <!-- Created Date Field -->
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-2">
                  Created Date
                  <span v-if="originalValues.created" class="text-xs text-gray-500 font-normal ml-2">
                    (Original: {{ formatDateForInput(originalValues.created) }})
                  </span>
                  <span v-else class="text-xs text-gray-400 font-normal ml-2">
                    (No original date)
                  </span>
                </label>
                <div class="flex items-center space-x-2">
                  <input
                    type="datetime-local"
                    :value="formatDateForInput(editableFeature.properties.created)"
                    @change="updateDate($event)"
                    class="block flex-1 px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500"
                  />
                  <button
                    v-if="originalValues.created"
                    @click="resetField('created')"
                    class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                    title="Reset to original value"
                  >
                    <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                    </svg>
                  </button>
                </div>
              </div>

              <!-- Tags Section -->
              <div>
                <label class="block text-sm font-medium text-gray-700 mb-2">
                  Tags
                  <span v-if="originalValues.tags && originalValues.tags.length > 0" class="text-xs text-gray-500 font-normal ml-2">
                    (Original: {{ originalValues.tags.join(', ') }})
                  </span>
                  <span v-else class="text-xs text-gray-400 font-normal ml-2">
                    (No original tags)
                  </span>
                </label>
                <div class="space-y-2">
                  <div v-for="(tag, tagIndex) in editableFeature.properties.tags" :key="`tag-${tagIndex}`" class="flex items-center space-x-2">
                    <input
                      v-model="editableFeature.properties.tags[tagIndex]"
                      class="block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500"
                      :placeholder="`Tag ${tagIndex + 1}`"
                    />
                    <button
                      class="inline-flex items-center px-3 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500"
                      @click="removeTag(tagIndex)"
                    >
                      <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
                      </svg>
                    </button>
                  </div>
                </div>
                <div class="flex items-center space-x-2 mt-3">
                  <button
                    :class="{ 'opacity-50 cursor-not-allowed': isLastTagEmpty }"
                    :disabled="isLastTagEmpty"
                    class="inline-flex items-center px-3 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:bg-gray-400"
                    @click="addTag"
                  >
                    <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6"></path>
                    </svg>
                    Add Tag
                  </button>
                  <button
                    v-if="originalValues.tags && originalValues.tags.length > 0"
                    @click="resetField('tags')"
                    class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                    title="Reset all tags to original values"
                  >
                    <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                    </svg>
                    Reset Tags
                  </button>
                </div>
              </div>
            </div>

            <!-- Metadata -->
            <div class="mt-4 pt-4 border-t border-gray-200">
              <h5 class="text-sm font-medium text-gray-700 mb-2">Metadata</h5>
              <div class="grid grid-cols-1 md:grid-cols-2 gap-4 text-sm text-gray-600">
                <div>
                  <span class="font-medium">Feature ID:</span> {{ originalFeature.id }}
                </div>
                <div>
                  <span class="font-medium">Created:</span> {{ originalFeature.timestamp }}
                </div>
              </div>
              
              <!-- Map View Button -->
              <div class="mt-4">
                <button
                  @click="showMapView"
                  class="inline-flex items-center px-4 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                >
                  <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
                  </svg>
                  View on Map
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- Action Buttons -->
        <div class="bg-gray-50 px-4 py-3 sm:px-6 sm:flex sm:flex-row-reverse">
          <button
            type="button"
            :disabled="isSaving"
            class="w-full inline-flex justify-center rounded-md border border-transparent shadow-sm px-4 py-2 bg-blue-600 text-base font-medium text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 sm:ml-3 sm:w-auto sm:text-sm disabled:bg-gray-400"
            @click="saveChanges"
          >
            <svg v-if="isSaving" class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
            </svg>
            {{ isSaving ? 'Saving...' : 'Save Changes' }}
          </button>
          <button
            type="button"
            class="mt-3 w-full inline-flex justify-center rounded-md border border-gray-300 shadow-sm px-4 py-2 bg-white text-base font-medium text-gray-700 hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 sm:mt-0 sm:ml-3 sm:w-auto sm:text-sm"
            @click="close"
          >
            Cancel
          </button>
        </div>
      </div>
    </div>

    <!-- Map View Dialog -->
    <FeatureMapDialog
      :is-open="showMapDialog"
      :features="[editableFeature]"
      :selected-feature-index="0"
      :filename="editableFeature?.properties?.name || 'Original Feature'"
      @close="closeMapDialog"
    />
  </div>
</template>

<script>
import axios from "axios";
import {getCookie} from "@/assets/js/auth.js";
import FeatureMapDialog from "@/components/import/parts/FeatureMapDialog.vue";

export default {
  name: 'EditOriginalFeatureDialog',
  components: {
    FeatureMapDialog
  },
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    originalFeature: {
      type: Object,
      default: null
    },
    loading: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return {
      editableFeature: null,
      isSaving: false,
      showMapDialog: false
    }
  },
  computed: {
    isLastTagEmpty() {
      if (!this.editableFeature || !this.editableFeature.properties.tags) return false;
      const tags = this.editableFeature.properties.tags;
      return tags.length > 0 && tags[tags.length - 1].trim().length === 0;
    },
    originalValues() {
      if (!this.originalFeature || !this.originalFeature.geojson) return {};
      return {
        name: this.originalFeature.geojson.properties?.name || '',
        description: this.originalFeature.geojson.properties?.description || '',
        created: this.originalFeature.geojson.properties?.created || '',
        tags: this.originalFeature.geojson.properties?.tags || []
      };
    }
  },
  watch: {
    originalFeature: {
      handler(newFeature) {
        if (newFeature) {
          // Create a deep copy of the feature for editing
          this.editableFeature = JSON.parse(JSON.stringify(newFeature.geojson));
        }
      },
      immediate: true
    }
  },
  methods: {
    close() {
      this.$emit('close');
    },
    showMapView() {
      this.showMapDialog = true;
    },
    closeMapDialog() {
      this.showMapDialog = false;
    },
    addTag() {
      if (!this.isLastTagEmpty) {
        this.editableFeature.properties.tags.push('');
      }
    },
    removeTag(tagIndex) {
      this.editableFeature.properties.tags.splice(tagIndex, 1);
    },
    updateDate(event) {
      this.editableFeature.properties.created = event.target.value;
    },
    formatDateForInput(dateString) {
      if (!dateString) return '';
      const date = new Date(dateString);
      if (isNaN(date.getTime())) return '';
      return date.toISOString().slice(0, 16);
    },
    resetField(fieldName) {
      if (!this.originalFeature || !this.originalFeature.geojson) return;
      
      const originalValue = this.originalFeature.geojson.properties?.[fieldName];
      
      switch (fieldName) {
        case 'name':
        case 'description':
        case 'created':
          this.editableFeature.properties[fieldName] = originalValue || '';
          break;
        case 'tags':
          // For tags, we need to create a deep copy of the array
          this.editableFeature.properties.tags = originalValue ? [...originalValue] : [];
          break;
        default:
          console.warn(`Unknown field: ${fieldName}`);
      }
    },
    async saveChanges() {
      if (!this.originalFeature || !this.editableFeature) return;
      
      this.isSaving = true;
      try {
        const csrftoken = getCookie('csrftoken');
        const response = await axios.put(
          `/api/data/feature/${this.originalFeature.id}/update/`,
          this.editableFeature,
          {
            headers: {
              'X-CSRFToken': csrftoken,
              'Content-Type': 'application/json'
            }
          }
        );
        
        if (response.data.success) {
          this.$emit('saved', this.originalFeature.id);
          this.close();
        } else {
          alert('Error saving changes: ' + response.data.msg);
        }
      } catch (error) {
        console.error('Error saving feature:', error);
        alert('Error saving changes: ' + error.message);
      } finally {
        this.isSaving = false;
      }
    }
  }
}
</script>
