<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between mb-4">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 mb-2">Collections</h1>
        </div>
        <button
            @click="openCreateDialog"
            class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md shadow-sm text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            title="Create a new collection"
        >
          <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"></path>
          </svg>
          Create New Collection
        </button>
      </div>

      <!-- Explanatory Text -->
      <div class="m-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
        <p class="text-sm text-gray-700">
          Collections are custom groupings of features that allow you to organize and view related geographic data together.
          You can create collections by matching tags (features with ANY of the specified tags) or by individually selecting features, then view them all together on the map, edit them, and share them with others.
        </p>
      </div>
    </div>

    <!-- Loading State -->
    <div v-if="loading" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-center py-12">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
        <span class="ml-3 text-gray-600">Loading collections...</span>
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
    <div v-else-if="!loading && collections.length === 0" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="text-center py-12">
        <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
        </svg>
        <h3 class="mt-2 text-sm font-medium text-gray-900">No collections found</h3>
        <p class="mt-1 text-sm text-gray-500">Create your first collection to organize your features.</p>
        <div class="mt-6">
          <button
              @click="openCreateDialog"
              class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md shadow-sm text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
          >
            Create New Collection
          </button>
        </div>
      </div>
    </div>

    <!-- Collections List -->
    <div v-else-if="!loading && collections.length > 0" class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <div
          v-for="collection in collections"
          :key="collection.id"
          class="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden hover:shadow-md transition-shadow"
      >
        <div class="p-6">
          <div class="flex items-start justify-between mb-3">
            <h3 class="text-lg font-semibold text-gray-900 truncate flex-1">{{ collection.name }}</h3>
            <div class="flex items-center space-x-1 ml-2">
              <button
                  class="p-1.5 text-gray-400 hover:text-blue-600 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                  title="Share collection"
                  type="button"
                  @click.stop.prevent="openShareDialog(collection)"
                  @mousedown.stop.prevent
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8.684 13.342C8.885 12.938 9 12.482 9 12c0-.482-.115-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"></path>
                </svg>
              </button>
              <button
                  class="p-1.5 text-gray-400 hover:text-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                  title="Edit collection"
                  type="button"
                  @click.stop.prevent="openEditDialog(collection)"
                  @mousedown.stop.prevent
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
              </button>
              <button
                  class="p-1.5 text-gray-400 hover:text-red-600 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-1 rounded"
                  title="Delete collection"
                  type="button"
                  @click.stop.prevent="deleteCollection(collection)"
                  @mousedown.stop.prevent
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
              </button>
            </div>
          </div>

          <p v-if="collection.description" class="text-sm text-gray-600 mb-4 line-clamp-2">
            {{ collection.description }}
          </p>

          <div class="flex items-center justify-between text-sm text-gray-500 mb-4">
            <span class="flex items-center">
              <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z"></path>
              </svg>
              {{ collection.feature_count }} {{ collection.feature_count === 1 ? 'feature' : 'features' }}
            </span>
          </div>

          <div class="flex space-x-2">
            <button
                @click="viewOnMap(collection.id)"
                class="flex-1 inline-flex items-center justify-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
                title="View collection on map"
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

    <!-- Collection Dialog -->
    <CollectionDialog
        v-if="dialogOpen"
        :collection="editingCollection"
        @close="closeDialog"
        @saved="handleCollectionSaved"
    />

    <!-- Collection Share Dialog -->
    <CollectionShareDialog
        v-if="shareDialogOpen"
        :isOpen="shareDialogOpen"
        :collection="selectedCollectionForShare"
        @close="closeShareDialog"
    />
  </div>
</template>

<script>
import {authMixin} from "@/assets/js/authMixin.js";
import { getCookie } from "@/assets/js/auth.js";
import CollectionDialog from "./CollectionDialog.vue";
import CollectionShareDialog from "./CollectionShareDialog.vue";

export default {
  name: 'CollectionsPage',
  components: {
    CollectionDialog,
    CollectionShareDialog
  },
  mixins: [authMixin],
  data() {
    return {
      collections: [],
      loading: true,
      error: null,
      dialogOpen: false,
      editingCollection: null,
      shareDialogOpen: false,
      selectedCollectionForShare: null
    }
  },
  methods: {
    async fetchCollections() {
      this.loading = true;
      this.error = null;

      try {
        const response = await fetch('/api/data/collections/');

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        if (data.success && data.collections) {
          this.collections = data.collections;
        } else {
          throw new Error(data.error || 'Failed to load collections');
        }
      } catch (error) {
        console.error('Error fetching collections:', error);
        this.error = error.message || 'Failed to load collections. Please try again.';
      } finally {
        this.loading = false;
      }
    },
    openCreateDialog() {
      this.editingCollection = null;
      this.dialogOpen = true;
    },
    openEditDialog(collection) {
      this.editingCollection = collection;
      this.dialogOpen = true;
    },
    closeDialog() {
      this.dialogOpen = false;
      this.editingCollection = null;
    },
    handleCollectionSaved() {
      this.closeDialog();
      this.fetchCollections();
    },
    async deleteCollection(collection) {
      // Show confirmation dialog
      const confirmMessage = `Are you sure you want to delete the collection "${collection.name}"?`;
      if (!confirm(confirmMessage)) {
        return;
      }

      try {
        const csrfToken = getCookie('csrftoken');
        const response = await fetch(`/api/data/collections/${collection.id}/delete/`, {
          method: 'DELETE',
          headers: {
            'X-CSRFToken': csrfToken || ''
          }
        });

        const data = await response.json();

        if (data.success) {
          // Refresh the collections list
          this.fetchCollections();
        } else {
          alert(data.error || 'Failed to delete collection');
        }
      } catch (error) {
        console.error('Error deleting collection:', error);
        alert('Failed to delete collection. Please try again.');
      }
    },
    viewOnMap(collectionId) {
      this.$router.push(`/map?collection=${collectionId}`);
    },
    openShareDialog(collection) {
      this.selectedCollectionForShare = collection;
      this.shareDialogOpen = true;
    },
    closeShareDialog() {
      this.shareDialogOpen = false;
      this.selectedCollectionForShare = null;
    }
  },
  mounted() {
    this.fetchCollections();
  }
};
</script>

