<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <div class="flex flex-col md:flex-row md:items-center md:justify-between gap-3 mb-4">
        <div class="order-1">
          <h1 class="text-xl sm:text-2xl font-bold text-gray-900 mb-1 sm:mb-2">Collections</h1>
        </div>
        <div class="order-2 w-full text-center md:w-auto md:text-left">
          <BaseButton
              @click="openCreateDialog"
              class="inline-flex"
              variant="primary"
              color="blue"
              size="md"
              title="Create a new collection"
          >
            <PlusIcon class="w-5 h-5 mr-2" />
            Create New Collection
          </BaseButton>
        </div>
      </div>

      <!-- Explanatory Text -->
      <div class="mt-2 sm:mt-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
        <p class="text-sm text-gray-700">
          Collections are custom groupings of features that allow you to organize and view related geographic data together.
          You can create collections by matching tags (features with ANY of the specified tags) or by individually selecting features, then view them all together on the map, edit them, and share them with others.
        </p>
      </div>
    </div>

    <!-- Loading State -->
    <div v-if="loading" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <Loader size="md" layout="centered" message="Loading collections..." />
    </div>

    <!-- Error State -->
    <div v-else-if="error" class="bg-red-50 border border-red-200 rounded-lg p-6">
      <div class="flex items-center">
        <ExclamationCircleIcon class="w-5 h-5 text-red-600 mr-2" />
        <p class="text-red-800">{{ error }}</p>
      </div>
    </div>

    <!-- Empty State -->
    <div v-else-if="!loading && collections.length === 0" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="text-center py-12">
        <FolderIcon class="mx-auto h-12 w-12 text-gray-400" />
        <h3 class="mt-2 text-sm font-medium text-gray-900">No collections found</h3>
        <p class="mt-1 text-sm text-gray-500">Create your first collection to organize your features.</p>
        <div class="mt-6">
          <BaseButton
              @click="openCreateDialog"
              variant="primary"
              color="blue"
              size="md"
          >
            Create New Collection
          </BaseButton>
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
        <div class="p-4 sm:p-6 h-full flex flex-col">
          <div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between mb-3">
            <ScrollNameWithTooltip
                :name="collection.name"
                root-class="text-base sm:text-lg font-semibold text-gray-900 sm:flex-1 order-1"
            />
            <div class="flex items-center justify-center sm:justify-end sm:ml-2 space-x-1 order-2">
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  title="Share collection"
                  type="button"
                  @click.stop.prevent="openShareDialog(collection)"
                  @mousedown.stop.prevent
              >
                <ShareIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  title="Download Collection KMZ"
                  type="button"
                  @click.stop.prevent="downloadCollectionKmz(collection)"
                  @mousedown.stop.prevent
              >
                <ArrowDownTrayIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  title="Edit collection"
                  type="button"
                  @click.stop.prevent="openEditDialog(collection)"
                  @mousedown.stop.prevent
              >
                <PencilIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  title="Bulk style features in this collection"
                  type="button"
                  @click.stop.prevent="openBulkOperationsModal(collection)"
                  @mousedown.stop.prevent
              >
                <RectangleStackIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  title="Delete collection"
                  type="button"
                  @click.stop.prevent="deleteCollection(collection)"
                  @mousedown.stop.prevent
              >
                <TrashIcon class="w-4 h-4" />
              </button>
            </div>
          </div>

          <p v-if="collection.description" class="text-sm text-gray-600 mb-4 line-clamp-2">
            {{ collection.description }}
          </p>

          <div class="flex items-center justify-between text-sm text-gray-500 mb-4">
            <span class="flex items-center">
              <TagIcon class="w-4 h-4 mr-1" />
              {{ collection.feature_count }} {{ collection.feature_count === 1 ? 'feature' : 'features' }}
            </span>
          </div>

          <div class="mt-auto flex justify-center sm:justify-start">
            <BaseButton
                @click="viewOnMap(collection.id)"
                class="sm:flex-1"
                variant="primary"
                color="blue"
                size="sm"
                title="View collection on map"
            >
              <MapIcon class="w-4 h-4 mr-2" />
              View on Map
            </BaseButton>
          </div>
        </div>
      </div>
    </div>

    <!-- Collection Dialog -->
    <CollectionDialog
        :is-open="dialogOpen"
        :collection="editingCollection"
        @close="closeDialog"
        @saved="handleCollectionSaved"
    />

    <!-- Collection Share Dialog -->
    <ShareDialog
        v-if="shareDialogOpen"
        :is-open="shareDialogOpen"
        share-type="collection"
        :item="selectedCollectionForShare || {}"
        @close="closeShareDialog"
    />

    <!-- Bulk Operations Modal -->
    <BulkStylingModal
        :isOpen="bulkOperationsModalOpen"
        :currentBulkOps="currentBulkOperationsForSelectedCollection"
        :saving="bulkOperationsSaving"
        :autoCloseOnApply="false"
        @close="closeBulkOperationsModal"
        @apply="handleApplyBulkOperations"
    />
  </div>
</template>

<script>
import { getCookie } from "@/assets/js/auth.js";
import CollectionDialog from "./CollectionDialog.vue";
import ShareDialog from "@/components/parts/ShareDialog.vue";
import Loader from "../parts/Loader.vue";
import BaseButton from "../parts/BaseButton.vue";
import ScrollNameWithTooltip from "../parts/ScrollNameWithTooltip.vue";
import BulkStylingModal from "@/components/import/parts/BulkStylingModal.vue";
import { createEmptyBulkOperations, cloneBulkOperations } from "@/utils/bulkOperations.js";
import { PlusIcon, ExclamationCircleIcon, FolderIcon, ShareIcon, ArrowDownTrayIcon, PencilIcon, TrashIcon, TagIcon, MapIcon, RectangleStackIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'CollectionsPage',
  components: {
    CollectionDialog,
    ShareDialog,
    Loader,
    BaseButton,
    ScrollNameWithTooltip,
    PlusIcon,
    ExclamationCircleIcon,
    FolderIcon,
    ShareIcon,
    ArrowDownTrayIcon,
    PencilIcon,
    TrashIcon,
    TagIcon,
    MapIcon,
    RectangleStackIcon,
    BulkStylingModal
  },
  data() {
    return {
      collections: [],
      loading: true,
      error: null,
      dialogOpen: false,
      editingCollection: null,
      shareDialogOpen: false,
      selectedCollectionForShare: null,

      // Bulk operations state for collections page
      bulkOperationsModalOpen: false,
      bulkOperationsSelectedCollectionId: null,
      bulkOperationsByCollection: {}, // { collectionId: bulkOps }
      bulkOperationsSaving: false
    }
  },
  methods: {
    async fetchCollections() {
      this.loading = true;
      this.error = null;

      try {
        const response = await fetch('/api/collections/');

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        if (response.ok && data.collections) {
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
        const response = await fetch(`/api/collections/${collection.id}/delete/`, {
          method: 'DELETE',
          headers: {
            'X-CSRFToken': csrfToken || ''
          }
        });

        const data = await response.json();

        if (response.ok) {
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
    },
    downloadCollectionKmz(collection) {
      const url = `/api/export-kmz?collection=${collection.id}`;
      window.open(url, '_blank');
    },
    openBulkOperationsModal(collection) {
      this.bulkOperationsSelectedCollectionId = collection.id;
      this.bulkOperationsModalOpen = true;
    },
    closeBulkOperationsModal() {
      this.bulkOperationsModalOpen = false;
    },
    async handleApplyBulkOperations(bulkData) {
      if (!this.bulkOperationsSelectedCollectionId) {
        this.bulkOperationsModalOpen = false;
        return;
      }
      const collectionId = this.bulkOperationsSelectedCollectionId;

      // Cache last-used bulk operations per collection
      this.bulkOperationsByCollection = {
        ...this.bulkOperationsByCollection,
        [collectionId]: cloneBulkOperations(bulkData)
      };

      this.bulkOperationsSaving = true;
      try {
        await this.applyBulkOperationsToCollection(collectionId, bulkData);
        this.bulkOperationsModalOpen = false;
      } finally {
        this.bulkOperationsSaving = false;
      }
    },
    async applyBulkOperationsToCollection(collectionId, bulkData) {
      try {
        const csrfToken = getCookie('csrftoken');
        const response = await fetch(`/api/collections/${collectionId}/bulk-operations/`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': csrfToken || ''
          },
          body: JSON.stringify({
            bulk_operations: bulkData
          })
        });

        if (!response.ok) {
          const errorData = await response.json().catch(() => ({}));
          throw new Error(errorData.error || `Failed to apply bulk operations: ${response.status}`);
        }

        // Refresh collections list to ensure counts/metadata stay in sync
        await this.fetchCollections();
      } catch (error) {
        console.error('Error applying bulk operations to collection:', error);
        alert(`Failed to apply bulk operations: ${error.message}`);
      }
    },
    currentBulkOperationsForCollection(collectionId) {
      return this.bulkOperationsByCollection[collectionId] || createEmptyBulkOperations();
    },
    currentBulkOperationsForSelectedCollection() {
      if (!this.bulkOperationsSelectedCollectionId) {
        return createEmptyBulkOperations();
      }
      return this.currentBulkOperationsForCollection(this.bulkOperationsSelectedCollectionId);
    }
  },
  mounted() {
    this.fetchCollections();
  }
};
</script>

