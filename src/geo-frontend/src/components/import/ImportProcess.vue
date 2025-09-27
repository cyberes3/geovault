<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 mb-2">Process Import</h1>
          <h2 v-if="originalFilename != null" class="text-lg text-gray-600">{{ originalFilename }}</h2>
          <div v-else class="h-6 w-48 bg-gray-200 rounded animate-pulse"></div>
        </div>
        <div class="flex items-center space-x-2">
          <button
            :disabled="originalFilename == null"
            @click="showMapPreview"
            :class="originalFilename == null ? 'inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-400 bg-gray-100 cursor-not-allowed' : 'inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500'"
          >
            <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
            </svg>
            Map Preview
          </button>
          <span v-if="isImported" class="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium bg-green-100 text-green-800">
            <svg class="w-4 h-4 mr-1" fill="currentColor" viewBox="0 0 20 20">
              <path fill-rule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clip-rule="evenodd"></path>
            </svg>
            Imported
          </span>
        </div>
      </div>
    </div>

    <!-- Error Message -->
    <div v-if="msg !== '' && msg != null" class="bg-red-50 border border-red-200 rounded-lg p-4">
      <div class="flex">
        <div class="flex-shrink-0">
          <svg class="h-5 w-5 text-red-400" fill="currentColor" viewBox="0 0 20 20">
            <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd"></path>
          </svg>
        </div>
        <div class="ml-3">
          <h3 class="text-sm font-medium text-red-800">Error</h3>
          <div class="mt-2 text-sm text-red-700">
            <p>{{ msg }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Import Logs -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Processing Logs</h2>
      <div class="bg-gray-50 rounded-lg p-4">
        <div class="h-32 overflow-auto">
          <ul class="space-y-2">
            <li v-for="(item, index) in workerLog" :key="`logitem-${index}`" class="flex items-start space-x-2">
              <span class="text-sm text-gray-500">{{ item.timestamp }}</span>
              <span class="text-sm text-gray-700">{{ item.msg }}</span>
            </li>
            <li v-if="workerLog.length === 0" class="text-sm text-gray-500 italic">
              No logs available yet...
            </li>
          </ul>
        </div>
      </div>
    </div>

    <!-- Loading State -->
    <Loader v-if="originalFilename == null"/>


    <!-- Feature Items -->
    <div v-if="itemsForUser.length > 0" class="space-y-6">
      <div v-for="(item, index) in itemsForUser" :key="`item-${index}`" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
        <div class="flex items-center justify-between mb-6">
          <h3 class="text-lg font-semibold text-gray-900">Feature {{ index + 1 }}</h3>
          <div class="flex items-center space-x-2">
            <button
              @click="showFeatureMap(index)"
              class="inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            >
              <svg class="w-3 h-3 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
              </svg>
              View on Map
            </button>
            <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-800">
              {{ item.geometry.type }}
            </span>
          </div>
        </div>

        <div class="grid grid-cols-1 md:grid-cols-2 gap-6">
          <!-- Name Field -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Name</label>
            <div class="flex items-center space-x-2">
              <input
                v-model="item.properties.name"
                :class="isImported ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500'"
                :disabled="isImported"
                :placeholder="originalItems[index].properties.name"
              />
              <button
                v-if="!isImported"
                class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                @click="resetNestedField(index, 'properties', 'name')"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                </svg>
              </button>
            </div>
          </div>

          <!-- Description Field -->
          <div class="md:col-span-2">
            <label class="block text-sm font-medium text-gray-700 mb-2">Description</label>
            <div class="flex items-start space-x-2">
              <textarea
                v-model="item.properties.description"
                :class="isImported ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed resize-none' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500 resize-none'"
                :disabled="isImported"
                :placeholder="originalItems[index].properties.description"
                rows="4"
                class="text-sm"
              ></textarea>
              <button
                v-if="!isImported"
                class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 mt-1"
                @click="resetNestedField(index, 'properties', 'description')"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                </svg>
              </button>
            </div>
          </div>

          <!-- Created Date Field -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Created Date</label>
            <div class="flex items-center space-x-2">
              <input
                type="datetime-local"
                :class="isImported ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500'"
                :disabled="isImported"
                :value="formatDateForInput(item.properties.created)"
                @change="updateDate(index, $event)"
              />
              <button
                v-if="!isImported"
                class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm leading-4 font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                @click="resetNestedField(index, 'properties', 'created')"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                </svg>
              </button>
            </div>
          </div>

          <!-- Tags Section -->
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-2">Tags</label>
            <div class="space-y-2">
              <div v-for="(tag, tagIndex) in item.properties.tags" :key="`tag-${tagIndex}`" class="flex items-center space-x-2">
                <input
                  v-model="item.properties.tags[tagIndex]"
                  :class="isImported ? 'block w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-100 cursor-not-allowed' : 'block w-full px-3 py-2 border border-gray-300 rounded-md focus:ring-blue-500 focus:border-blue-500'"
                  :disabled="isImported"
                  :placeholder="getTagPlaceholder(index, tag)"
                />
                <button
                  v-if="!isImported"
                  class="inline-flex items-center px-3 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-red-600 hover:bg-red-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500"
                  @click="removeTag(index, tagIndex)"
                >
                  <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"></path>
                  </svg>
                </button>
              </div>
            </div>
            <div v-if="!isImported" class="flex items-center space-x-2 mt-3">
              <button
                :class="{ 'opacity-50 cursor-not-allowed': isLastTagEmpty(index) }"
                :disabled="isLastTagEmpty(index)"
                class="inline-flex items-center px-3 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:bg-gray-400"
                @click="addTag(index)"
              >
                <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6"></path>
                </svg>
                Add Tag
              </button>
              <button
                class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                @click="resetTags(index)"
              >
                <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
                </svg>
                Reset Tags
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Action Buttons -->
    <div v-if="itemsForUser.length > 0" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div v-if="isImported" class="mb-4 p-4 bg-yellow-50 border border-yellow-200 rounded-md">
        <div class="flex">
          <div class="flex-shrink-0">
            <svg class="h-5 w-5 text-yellow-400" fill="currentColor" viewBox="0 0 20 20">
              <path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd"></path>
            </svg>
          </div>
          <div class="ml-3">
            <h3 class="text-sm font-medium text-yellow-800">Already Imported</h3>
            <div class="mt-2 text-sm text-yellow-700">
              <p>This item has already been imported to the feature store and cannot be modified.</p>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!isImported" class="flex items-center space-x-4">
        <button
          :disabled="lockButtons || isSaving"
          class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-green-600 hover:bg-green-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-green-500 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors duration-200"
          @click="saveChanges"
        >
          <svg v-if="isSaving" class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
          </svg>
          <svg v-else class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4"></path>
          </svg>
          {{ isSaving ? 'Saving...' : 'Save Changes' }}
        </button>
        <button
          :disabled="lockButtons || isImporting"
          class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:bg-gray-400 disabled:cursor-not-allowed transition-colors duration-200"
          @click="performImport"
        >
          <svg v-if="isImporting" class="animate-spin -ml-1 mr-2 h-4 w-4 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
            <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
            <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
          </svg>
          <svg v-else class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
          </svg>
          {{ isImporting ? 'Importing...' : 'Import to Feature Store' }}
        </button>
      </div>
    </div>


    <div class="hidden">
      <!-- Load the queue to populate it. -->
      <Importqueue/>
    </div>

    <!-- Map Preview Dialog -->
    <MapPreviewDialog
      :is-open="showMapPreviewDialog"
      :features="itemsForUser"
      :filename="originalFilename"
      @close="closeMapPreview"
    />

    <!-- Feature Map Dialog -->
    <FeatureMapDialog
      :is-open="showFeatureMapDialog"
      :features="itemsForUser"
      :selected-feature-index="selectedFeatureIndex"
      :filename="originalFilename"
      @close="closeFeatureMap"
    />
  </div>
</template>

<script>
import {mapState} from "vuex";
import {authMixin} from "@/assets/js/authMixin.js";
import axios from "axios";
import {capitalizeFirstLetter} from "@/assets/js/string.js";
import Importqueue from "@/components/import/parts/importqueue.vue";
import {GeoFeatureTypeStrings} from "@/assets/js/types/geofeature-strings";
import {GeoPoint, GeoLineString, GeoPolygon} from "@/assets/js/types/geofeature-types";
import {getCookie} from "@/assets/js/auth.js";
// Removed flatpickr dependency - using native HTML5 date input
import Loader from "@/components/parts/Loader.vue";
import MapPreviewDialog from "@/components/import/MapPreviewDialog.vue";
import FeatureMapDialog from "@/components/import/FeatureMapDialog.vue";

// TODO: for each feature, query the DB and check if there is a duplicate. For points that's duplicate coords, for linestrings and polygons that's duplicate points
// TODO: redo the entire log feature to include local timestamps

export default {
  computed: {
    ...mapState(["userInfo"]),
  },
  components: {Loader, Importqueue, MapPreviewDialog, FeatureMapDialog},
  data() {
    return {
      msg: "",
      currentId: null,
      originalFilename: null,
      itemsForUser: [],
      originalItems: [],
      workerLog: [],
      lockButtons: false,
      isImported: false, // Track if this item has been imported
      showMapPreviewDialog: false, // Track map preview dialog state
      showFeatureMapDialog: false, // Track feature map dialog state
      selectedFeatureIndex: 0, // Track which feature is selected for the feature map
      isSaving: false, // Track save operation loading state
      isImporting: false, // Track import operation loading state
      // Removed flatpickrConfig - using native HTML5 datetime-local input
    }
  },
  mixins: [authMixin],
  props: ['id'],
  methods: {
    parseGeoJson(item) {
      switch (item.geometry.type) {
        case GeoFeatureTypeStrings.Point:
          return new GeoPoint(item);
        case GeoFeatureTypeStrings.LineString:
          return new GeoLineString(item);
        case GeoFeatureTypeStrings.Polygon:
          return new GeoPolygon(item);
        default:
          throw new Error(`Invalid feature type: ${item.type}`);
      }
    },
    resetField(index, fieldName) {
      this.itemsForUser[index][fieldName] = this.originalItems[index][fieldName];
    },
    resetNestedField(index, nestedField, fieldName) {
      this.itemsForUser[index][nestedField][fieldName] = this.originalItems[index][nestedField][fieldName];
    },
    addTag(index) {
      if (!this.isLastTagEmpty(index)) {
        this.itemsForUser[index].properties.tags.push('');
      }
    },
    getTagPlaceholder(index, tag) {
      const originalTagIndex = this.originalItems[index].properties.tags.indexOf(tag);
      return originalTagIndex !== -1 ? this.originalItems[index].properties.tags[originalTagIndex] : '';
    },
    isLastTagEmpty(index) {
      const tags = this.itemsForUser[index].properties.tags;
      return tags.length > 0 && tags[tags.length - 1].trim().length === 0;
    },
    resetTags(index) {
      this.itemsForUser[index].properties.tags = [...this.originalItems[index].properties.tags];
    },
    removeTag(index, tagIndex) {
      this.itemsForUser[index].properties.tags.splice(tagIndex, 1);
    },
    updateDate(index, event) {
      this.itemsForUser[index].properties.created = event.target.value;
    },
    formatDateForInput(dateString) {
      if (!dateString) return '';
      // Convert date string to datetime-local format (YYYY-MM-DDTHH:MM)
      const date = new Date(dateString);
      if (isNaN(date.getTime())) return '';
      return date.toISOString().slice(0, 16);
    },
    saveChanges() {
      this.lockButtons = true
      this.isSaving = true
      const csrftoken = getCookie('csrftoken')
      axios.put('/api/data/item/import/update/' + this.id, this.itemsForUser, {
        headers: {
          'X-CSRFToken': csrftoken
        }
      }).then(response => {
        if (response.data.success) {
          // Refresh data from server to reflect persisted values (e.g., regenerated tags)
          axios.get('/api/data/item/import/get/' + this.id).then(res => {
            if (res.data.success) {
              this.itemsForUser = []
              res.data.geofeatures.forEach((item) => {
                this.itemsForUser.push(this.parseGeoJson(item))
              })
              this.originalItems = JSON.parse(JSON.stringify(this.itemsForUser))
            }
          })
        } else {
          this.msg = 'Error saving changes: ' + response.data.msg;
          window.alert(this.msg);
        }
        this.lockButtons = false
        this.isSaving = false
      }).catch(error => {
        this.msg = 'Error saving changes: ' + error.message;
        window.alert(this.msg);
        this.lockButtons = false
        this.isSaving = false
      });
    },
    async performImport() {
      this.lockButtons = true
      this.isImporting = true
      const csrftoken = getCookie('csrftoken')

      // Save changes first.
      await axios.put('/api/data/item/import/update/' + this.id, this.itemsForUser, {
        headers: {
          'X-CSRFToken': csrftoken
        }
      })

      axios.post('/api/data/item/import/perform/' + this.id, [], {
        headers: {
          'X-CSRFToken': csrftoken
        }
      }).then(response => {
        if (response.data.success) {
          this.$store.dispatch('refreshImportQueue')
          // Redirect to import page after successful import
          this.$router.replace('/import');
        } else {
          this.msg = 'Error performing import: ' + response.data.msg;
          window.alert(this.msg);
        }
        this.lockButtons = false
        this.isImporting = false
      }).catch(error => {
        this.msg = 'Error performing import: ' + error.message;
        window.alert(this.msg);
        this.lockButtons = false
        this.isImporting = false
      });
    },
    showMapPreview() {
      this.showMapPreviewDialog = true;
    },
    closeMapPreview() {
      this.showMapPreviewDialog = false;
    },
    showFeatureMap(featureIndex) {
      this.selectedFeatureIndex = featureIndex;
      this.showFeatureMapDialog = true;
    },
    closeFeatureMap() {
      this.showFeatureMapDialog = false;
    },
    clearComponentState() {
      // Clear all component data to reset state
      this.msg = "";
      this.currentId = null;
      this.originalFilename = null;
      this.itemsForUser = [];
      this.originalItems = [];
      this.workerLog = [];
      this.lockButtons = false;
      this.isImported = false;
      this.showMapPreviewDialog = false;
      this.showFeatureMapDialog = false;
      this.selectedFeatureIndex = 0;
      this.isSaving = false;
      this.isImporting = false;
    },
  },
  mounted() {
    // Add navigation warning when user tries to leave the page
    this.beforeUnloadHandler = (event) => {
      event.preventDefault();
      event.returnValue = '';
      return '';
    };
    window.addEventListener('beforeunload', this.beforeUnloadHandler);
  },
  beforeUnmount() {
    // Remove the navigation warning when component is destroyed
    if (this.beforeUnloadHandler) {
      window.removeEventListener('beforeunload', this.beforeUnloadHandler);
    }
    // Clear component state when component is destroyed
    this.clearComponentState();
  },
  beforeRouteLeave(to, from, next) {
    // Warn user before leaving this route
    const answer = window.confirm('Are you sure you want to leave this page? Your changes may not be saved.');
    if (answer) {
      // Clear component state when user confirms they want to leave
      this.clearComponentState();
      next();
    } else {
      next(false);
    }
  },
  beforeRouteEnter(to, from, next) {
    const now = new Date().toISOString()
    let ready = false
    next(async vm => {
      if (vm.currentId !== vm.id) {
        vm.msg = ""
        vm.currentId = null
        vm.originalFilename = null
        vm.itemsForUser = []
        vm.originalItems = []
        vm.workerLog = []
        vm.lockButtons = false
        vm.isImported = false
        while (!ready) {
          try {
            const response = await axios.get('/api/data/item/import/get/' + vm.id)
            if (!response.data.success) {
              vm.msg = capitalizeFirstLetter(response.data.msg).trim(".") + "."
            } else {
              vm.currentId = vm.id
              if (Object.keys(response.data).length > 0) {
                vm.originalFilename = response.data.original_filename
                vm.isImported = response.data.imported || false

                // If the item is already imported, redirect to import page
                if (vm.isImported) {
                  vm.$router.replace('/import');
                  return;
                }

                // Check if this is an error response (unprocessable file)
                if (response.data.geofeatures.length > 0 && response.data.geofeatures[0].error) {
                  // This is an unprocessable file, show the error message
                  const errorItem = response.data.geofeatures[0];
                  vm.msg = `File processing failed: ${errorItem.message}`;
                  vm.workerLog = [{timestamp: now, msg: errorItem.message}];
                } else {
                  // Normal processing - parse the geofeatures
                  response.data.geofeatures.forEach((item) => {
                    vm.itemsForUser.push(vm.parseGeoJson(item))
                  })
                  vm.originalItems = JSON.parse(JSON.stringify(vm.itemsForUser))
                }
              }
              if (!response.data.processing) {
                vm.workerLog = vm.workerLog.concat(response.data.log)
                if (response.data.msg != null && response.data.msg.length > 0) {
                  vm.workerLog.push({timestamp: now, msg: response.data.msg})
                }
                ready = true
              } else {
                vm.workerLog = [{timestamp: now, msg: "uploaded data still processing"}]
                await new Promise(r => setTimeout(r, 1000));
              }
            }
          } catch (error) {
            if (error.response && error.response.data && error.response.data.code === 404) {
              // Import ID does not exist.
              vm.$router.replace('/import');
              return;
            }
            vm.msg = capitalizeFirstLetter(error.message).trim(".") + "."
          }
        }
      }
    })
  }
  ,
}

</script>

<style scoped>

</style>
