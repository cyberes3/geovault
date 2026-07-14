<template>
  <div ref="rootEl" class="space-y-6 min-w-0 max-w-full overflow-x-hidden">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6 relative">
      <!-- Refresh Spinner -->
      <div
        v-show="refreshing"
        class="absolute top-4 right-4 z-10 flex items-center"
      >
        <Loader
          size="sm"
          layout="inline"
          :show-message="false"
        />
      </div>
      <div class="mb-4">
        <h1 class="text-2xl font-bold text-gray-900 mb-2">Tags</h1>
      </div>

      <!-- Explanatory Text -->
      <div class="m-4 p-4 bg-blue-50 border border-blue-200 rounded-lg">
        <p class="text-sm text-gray-700">
          Tags are labels attached to your geographic features that help you organize, filter, and find them easily.
          You can create custom tags, edit or delete them, share them with others, and each feature can have multiple tags for flexible categorization.
        </p>
        <p class="text-sm text-gray-700 mt-2">
          When deleting a tag, you can choose to either delete all features with that tag or just remove the tag from those features.
          To remove a tag from a single feature without deleting it, use the × button next to the feature.
        </p>
      </div>

      <!-- Search Input -->
      <div class="relative">
        <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
          <MagnifyingGlassIcon class="h-5 w-5 text-gray-400" />
        </div>
        <input
            v-model="searchQuery"
            class="block w-full pl-10 pr-3 py-2 border-2 border-blue-500 rounded-md leading-5 bg-white placeholder-gray-500 focus:outline-none focus:placeholder-gray-400 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 sm:text-sm"
            placeholder="Search tags..."
            type="text"
        />
        <button
            v-if="searchQuery"
            class="absolute inset-y-0 right-0 pr-3 flex items-center min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 justify-center"
            @click="searchQuery = ''"
            title="Clear Search"
        >
          <XMarkIcon class="h-5 w-5 text-gray-400 hover:text-gray-600" />
        </button>
      </div>
    </div>

    <!-- Loading State -->
    <div v-if="loading" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <Loader layout="centered" message="Loading tags..." />
    </div>

    <!-- Error State -->
    <div v-else-if="error" class="bg-red-50 border border-red-200 rounded-lg p-6">
      <div class="flex items-center">
        <ExclamationCircleIcon class="w-5 h-5 text-red-600 mr-2" />
        <p class="text-red-800">{{ error }}</p>
      </div>
    </div>

    <!-- Empty State -->
    <div v-else-if="!loading && Object.keys(tagsData).length === 0 && !searchQuery" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="text-center py-12">
        <TagIcon class="mx-auto h-12 w-12 text-gray-400" />
        <h3 class="mt-2 text-sm font-medium text-gray-900">No tags found</h3>
        <p class="mt-1 text-sm text-gray-500">Tags will appear here once you import features with tags.</p>
      </div>
    </div>

    <!-- No Search Results -->
    <div v-else-if="!loading && Object.keys(tagsData).length === 0 && searchQuery" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="text-center py-12">
        <MagnifyingGlassIcon class="mx-auto h-12 w-12 text-gray-400" />
        <h3 class="mt-2 text-sm font-medium text-gray-900">No tags match your search</h3>
        <p class="mt-1 text-sm text-gray-500">Try adjusting your search query.</p>
      </div>
    </div>

    <!-- Tags List -->
    <div v-else-if="!loading && Object.keys(tagsData).length > 0" class="space-y-4">
      <div
          v-for="tag in Object.keys(tagsData)"
          :key="tag"
          :data-tag="tag"
          class="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden"
      >
        <!-- Tag Header -->
        <div class="bg-gray-50 px-6 py-4 border-b border-gray-200">
          <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 sm:gap-4">
            <!-- Title Row -->
            <div class="flex items-center space-x-3 flex-1 min-w-0">
              <span v-if="editingTag !== tag" :class="[
                'inline-flex items-center px-3 py-1 rounded-full text-sm font-medium border truncate max-w-full',
                isSystemTag(tag) 
                  ? 'bg-purple-100 text-purple-800 border-purple-200' 
                  : 'bg-blue-100 text-blue-700 border-blue-200'
              ]">
                <span class="truncate">{{ tag }}</span>
              </span>
              <input
                  v-else
                  :ref="bindTagEditInput"
                  v-model="editingTagValue"
                  class="inline-flex items-center px-3 py-1 rounded-md text-sm font-medium bg-white text-gray-900 border border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1"
                  type="text"
                  @keyup.enter="saveTagEdit(tag)"
                  @keyup.esc="cancelTagEdit"
                  @focus.stop
                  @click.stop
              />
              <div
                  v-if="editingTag === tag"
                  class="ml-2 flex items-center gap-1 flex-shrink-0"
              >
                <BaseButton
                    class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0"
                    variant="primary"
                    color="blue"
                    size="xs"
                    title="Save Tag Name"
                    @click.stop="saveTagEdit(tag)"
                >
                  <CheckIcon class="w-4 h-4" />
                </BaseButton>
                <BaseButton
                    class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0"
                    variant="white"
                    size="xs"
                    title="Cancel Editing"
                    @click.stop="cancelTagEdit"
                >
                  <XMarkIcon class="w-4 h-4" />
                </BaseButton>
              </div>
            </div>
            <!-- Control Buttons Row -->
            <div v-if="editingTag !== tag" class="flex items-center space-x-1 flex-wrap">
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  title="View on Map"
                  type="button"
                  @click.stop.prevent="viewTagOnMap(tag)"
                  @mousedown.stop.prevent
              >
                <MapIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  title="Share Tag"
                  type="button"
                  @click.stop.prevent="openShareDialog(tag)"
                  @mousedown.stop.prevent
              >
                <ShareIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  title="Download Tag KMZ"
                  type="button"
                  @click.stop.prevent="downloadTagKmz(tag)"
                  @mousedown.stop.prevent
              >
                <ArrowDownTrayIcon class="w-4 h-4" />
              </button>
              <button
                  v-if="!isSystemTag(tag)"
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  title="Edit Tag Name"
                  type="button"
                  @click.stop.prevent="startTagEdit(tag, $event)"
                  @mousedown.stop.prevent
              >
                <PencilIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  title="Bulk Style Features in This Tag"
                  type="button"
                  @click.stop.prevent="openBulkOperationsModal(tag)"
                  @mousedown.stop.prevent
              >
                <RectangleStackIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-blue-600 hover:text-blue-500 hover:bg-blue-50 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded transition-colors"
                  :title="isSystemTag(tag) ? 'Delete System Tag and All Its Features' : 'Delete Tag'"
                  type="button"
                  @click.stop.prevent="openDeleteModal(tag)"
                  @mousedown.stop.prevent
              >
                <TrashIcon class="w-4 h-4" />
              </button>
            </div>
          </div>
        </div>

        <!-- Tag Search Box (shown when tag has more than 10 features) -->
        <div v-if="tagFeatureViews[tag].totalCount > 10" class="px-6 py-3 border-b border-gray-200 bg-gray-50">
          <div class="relative">
            <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
              <MagnifyingGlassIcon class="h-4 w-4 text-gray-400" />
            </div>
            <input
                :value="getTagSearchQuery(tag)"
                class="block w-full pl-9 pr-8 py-2 border border-gray-300 rounded-md leading-5 bg-white placeholder-gray-500 focus:outline-none focus:placeholder-gray-400 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 text-sm"
                placeholder="Search features in this tag..."
                type="text"
                @input="updateTagSearchQuery(tag, ($event.target as HTMLInputElement).value)"
            />
            <button
                v-if="getTagSearchQuery(tag)"
                class="absolute inset-y-0 right-0 pr-3 flex items-center min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 justify-center"
                @click="updateTagSearchQuery(tag, '')"
                title="Clear Search"
            >
              <XMarkIcon class="h-4 w-4 text-gray-400 hover:text-gray-600" />
            </button>
          </div>
        </div>

        <!-- Features List -->
        <div class="divide-y divide-gray-200">
          <div
              v-for="(feature, index) in tagFeatureViews[tag].features"
              :key="feature.properties.database_id || index"
              class="px-6 py-4 hover:bg-gray-50 transition-colors feature-row"
          >
            <div class="flex items-start justify-between gap-4">
              <div class="flex-1 min-w-0">
                <h4 class="text-sm font-medium text-gray-900 truncate">
                  {{ feature.properties.name || 'Unnamed Feature' }}
                </h4>
                <p v-if="feature.properties.description" class="mt-1 text-sm text-gray-500 line-clamp-2">
                  {{ feature.properties.description }}
                </p>
                <div class="mt-2 flex items-center space-x-4 text-xs text-gray-500">
                  <span class="capitalize">
                    {{ feature.geometry.type || 'Unknown' }}
                  </span>
                </div>
              </div>
              <div class="flex-shrink-0 relative z-10 flex items-center space-x-2">
                <button
                    v-if="!isSystemTag(tag)"
                    class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-red-600 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-1 rounded"
                    title="Remove This Feature from Tag"
                    type="button"
                    @click.stop.prevent="removeTagFromFeature(tag, feature)"
                >
                  <XMarkIcon class="w-4 h-4" />
                </button>
                <BaseButton
                    v-if="feature.properties.database_id"
                    tag="router-link"
                    :to="{ path: '/map', query: { featureId: feature.properties.database_id } }"
                    class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0"
                    variant="primary"
                    color="blue"
                    size="xs"
                    title="View on Map"
                    @click.stop
                >
                  <MapIcon class="w-4 h-4" />
                </BaseButton>
              </div>
            </div>
          </div>
          <!-- Placeholder rows to keep list height consistent across pages -->
          <div
              v-for="n in tagFeatureViews[tag].placeholderCount"
              :key="`placeholder-${tag}-${n}`"
              class="feature-row-placeholder border-t border-gray-200"
              aria-hidden="true"
          >
          </div>
        </div>

        <!-- Tag Feature Pagination Controls -->
        <div v-if="tagFeatureViews[tag].totalCount > 10 && tagFeatureViews[tag].totalPages > 1" class="px-6 py-3 border-t border-gray-200 bg-gray-50">
          <div class="flex items-center justify-between flex-wrap gap-2">
            <div class="text-xs text-gray-600">
              Showing features {{ (tagFeatureViews[tag].currentPage - 1) * tagFeaturePageSize + 1 }} - {{ Math.min(tagFeatureViews[tag].currentPage * tagFeaturePageSize, tagFeatureViews[tag].filteredCount) }} of {{ tagFeatureViews[tag].filteredCount }}
            </div>
            <div class="flex items-center space-x-2">
              <BaseButton
                  :disabled="!tagFeatureViews[tag].hasPreviousPage"
                  class="min-h-[44px] sm:min-h-0"
                  variant="white"
                  size="xs"
                  @click="tagPreviousPage(tag)"
                  title="Previous Page"
              >
                <ArrowLeftIcon class="w-3 h-3 mr-1" />
                Prev
              </BaseButton>
              <span class="text-xs text-gray-700">Page {{ tagFeatureViews[tag].currentPage }} of {{ tagFeatureViews[tag].totalPages }}</span>
              <BaseButton
                  :disabled="!tagFeatureViews[tag].hasNextPage"
                  class="min-h-[44px] sm:min-h-0"
                  variant="white"
                  size="xs"
                  @click="tagNextPage(tag)"
                  title="Next Page"
              >
                Next
                <ArrowRightIcon class="w-3 h-3 ml-1" />
              </BaseButton>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Pagination Controls -->
    <div v-if="!loading && Object.keys(tagsData).length > 0 && totalPages > 1" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="flex items-center justify-between flex-wrap gap-4">
        <div class="text-sm text-gray-700">
          Showing tags {{ (currentPage - 1) * pageSize + 1 }} - {{ Math.min(currentPage * pageSize, totalTags) }} of {{ totalTags }}
        </div>
        <div class="flex items-center space-x-2">
          <BaseButton
              :disabled="!hasPreviousPage || totalPages <= 1"
              variant="white"
              size="sm"
              @click="previousPage"
              title="Go to Previous Page"
          >
            <ArrowLeftIcon class="w-4 h-4 mr-1" />
            Previous
          </BaseButton>
          <span class="text-sm text-gray-700">Page {{ currentPage }} of {{ totalPages }}</span>
          <BaseButton
              :disabled="!hasNextPage || totalPages <= 1"
              variant="white"
              size="sm"
              @click="nextPage"
              title="Go to Next Page"
          >
            Next
            <ArrowRightIcon class="w-4 h-4 ml-1" />
          </BaseButton>
          <div class="flex items-center space-x-2 ml-4 pl-4 border-l border-gray-300">
            <label class="text-sm text-gray-700" for="goto-page">Go to:</label>
            <input
                id="goto-page"
                v-model.number="gotoPageInput"
                :max="totalPages"
                class="w-16 px-2 py-1 border border-gray-300 rounded-md text-sm focus:ring-blue-500 focus:border-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                min="1"
                type="number"
                @keyup.enter="jumpToPage"
            />
            <BaseButton
                :disabled="!isValidPageNumber || totalPages <= 1"
                variant="white"
                size="sm"
                @click="jumpToPage"
                title="Jump to Page"
            >
              Go
            </BaseButton>
          </div>
        </div>
      </div>
    </div>

    <!-- Share Dialog -->
    <ShareDialog
        :is-open="shareDialogOpen"
        share-type="tag"
        :item="{ tag: selectedTagForShare }"
        @close="shareDialogOpen = false"
    />

    <!-- Delete Modal -->
    <TagDeleteModal
        :is-open="deleteModalOpen"
        :tag="selectedTagForDelete"
        :feature-count="getFeatureCountForTag(selectedTagForDelete)"
        :is-system-tag="isSystemTag(selectedTagForDelete)"
        @close="closeDeleteModal"
        @delete-all-features="handleDeleteAllFeatures"
        @remove-tag-only="handleRemoveTagOnly"
    />

    <!-- Bulk Operations Modal -->
    <BulkStylingModal
        :is-open="bulkOperationsModalOpen"
        :current-bulk-ops="currentBulkOperationsForSelectedTag"
        :saving="bulkOperationsSaving"
        :auto-close-on-apply="false"
        @close="closeBulkOperationsModal"
        @apply="handleApplyBulkOperations"
    />
  </div>
</template>

<script setup lang="ts">
import ShareDialog from '@/components/parts/ShareDialog.vue';
import TagDeleteModal from './TagDeleteModal.vue';
import Loader from '../parts/Loader.vue';
import BaseButton from '../parts/BaseButton.vue';
import BulkStylingModal from '@/components/import/parts/BulkStylingModal.vue';
import {
    ArrowDownTrayIcon,
    ArrowLeftIcon,
    ArrowRightIcon,
    CheckIcon,
    ExclamationCircleIcon,
    MagnifyingGlassIcon,
    MapIcon,
    PencilIcon,
    RectangleStackIcon,
    ShareIcon,
    TagIcon,
    TrashIcon,
    XMarkIcon,
} from '@heroicons/vue/24/outline';
import { useTagsData } from '@/composables/useTagsData';
import { useTagFeaturePagination } from '@/composables/useTagFeaturePagination';

defineOptions({ name: 'TagsPage' });

const {
    rootEl,
    tagsData,
    loading,
    refreshing,
    error,
    searchQuery,
    editingTag,
    editingTagValue,
    shareDialogOpen,
    selectedTagForShare,
    deleteModalOpen,
    selectedTagForDelete,
    pageSize,
    currentPage,
    gotoPageInput,
    bulkOperationsModalOpen,
    bulkOperationsSaving,
    totalTags,
    totalPages,
    hasNextPage,
    hasPreviousPage,
    isValidPageNumber,
    currentBulkOperationsForSelectedTag,
    isSystemTag,
    bindTagEditInput,
    startTagEdit,
    cancelTagEdit,
    saveTagEdit,
    getFeatureCountForTag,
    openDeleteModal,
    closeDeleteModal,
    handleDeleteAllFeatures,
    handleRemoveTagOnly,
    removeTagFromFeature,
    openBulkOperationsModal,
    closeBulkOperationsModal,
    handleApplyBulkOperations,
    openShareDialog,
    downloadTagKmz,
    viewTagOnMap,
    nextPage,
    previousPage,
    jumpToPage,
} = useTagsData();

const {
    tagFeaturePageSize,
    tagFeatureViews,
    getTagSearchQuery,
    updateTagSearchQuery,
    tagNextPage,
    tagPreviousPage,
} = useTagFeaturePagination(tagsData);
</script>

<style scoped>
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.feature-row {
  min-height: 96px; /* Ensures consistent height for all feature rows */
}

.feature-row-placeholder {
  height: 97px; /* 96px + 1px for the border to match feature rows with dividers */
}
</style>
