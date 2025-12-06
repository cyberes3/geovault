<template>
  <div class="space-y-6 min-w-0 max-w-full overflow-x-hidden">
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
          :showMessage="false"
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
            title="Clear search"
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
    <div v-else-if="!loading && Object.keys(tagsData).length === 0" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="text-center py-12">
        <TagIcon class="mx-auto h-12 w-12 text-gray-400" />
        <h3 class="mt-2 text-sm font-medium text-gray-900">No tags found</h3>
        <p class="mt-1 text-sm text-gray-500">Tags will appear here once you import features with tags.</p>
      </div>
    </div>

    <!-- No Search Results -->
    <div v-else-if="!loading && Object.keys(filteredTagsData).length === 0 && searchQuery" class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="text-center py-12">
        <MagnifyingGlassIcon class="mx-auto h-12 w-12 text-gray-400" />
        <h3 class="mt-2 text-sm font-medium text-gray-900">No tags match your search</h3>
        <p class="mt-1 text-sm text-gray-500">Try adjusting your search query.</p>
      </div>
    </div>

    <!-- Tags List -->
    <div v-else-if="!loading && Object.keys(filteredTagsData).length > 0" class="space-y-4">
      <div
          v-for="(features, tag) in paginatedTagsData"
          :key="tag"
          :data-tag="tag"
          class="bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden"
      >
        <!-- Tag Header -->
        <div class="bg-gray-50 px-6 py-4 border-b border-gray-200">
          <div class="flex items-center justify-between gap-4">
            <div class="flex items-center space-x-3 flex-1 min-w-0">
              <span v-if="editingTag !== tag" :class="[
                'inline-flex items-center px-3 py-1 rounded-full text-sm font-medium border truncate max-w-full',
                isSystemTag(tag) 
                  ? 'bg-purple-100 text-purple-800 border-purple-200' 
                  : 'bg-blue-100 text-blue-700 border-blue-200'
              ]">
                <span class="truncate">{{ tag }}</span>
                <span v-if="isSystemTag(tag)" class="ml-1.5 text-xs opacity-75 flex-shrink-0" title="System tag">🔒</span>
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
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                  title="View on Map"
                  type="button"
                  @click.stop.prevent="viewTagOnMap(tag)"
                  @mousedown.stop.prevent
              >
                <MapIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                  title="Share tag"
                  type="button"
                  @click.stop.prevent="openShareDialog(tag)"
                  @mousedown.stop.prevent
              >
                <ShareIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                  title="Download Tag KMZ"
                  type="button"
                  @click.stop.prevent="downloadTagKmz(tag)"
                  @mousedown.stop.prevent
              >
                <ArrowDownTrayIcon class="w-4 h-4" />
              </button>
              <button
                  v-if="!isSystemTag(tag)"
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-gray-600 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                  title="Edit tag name"
                  type="button"
                  @click.stop.prevent="startTagEdit(tag)"
                  @mousedown.stop.prevent
              >
                <PencilIcon class="w-4 h-4" />
              </button>
              <button
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                  title="Bulk style features in this tag"
                  type="button"
                  @click.stop.prevent="openBulkOperationsModal(tag)"
                  @mousedown.stop.prevent
              >
                <RectangleStackIcon class="w-4 h-4" />
              </button>
              <button
                  v-if="!isSystemTag(tag)"
                  class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-red-600 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-1 rounded"
                  title="Delete tag"
                  type="button"
                  @click.stop.prevent="deleteTag(tag)"
                  @mousedown.stop.prevent
              >
                <TrashIcon class="w-4 h-4" />
              </button>
            </div>
            <button
                v-else
                class="ml-2 p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 bg-blue-500 text-white hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-1 rounded"
                title="Save tag name"
                @click.stop="saveTagEdit(tag)"
            >
              <CheckIcon class="w-4 h-4" />
            </button>
          </div>
        </div>

        <!-- Tag Search Box (shown when tag has more than 10 features) -->
        <div v-if="getTagFeatureCount(tag) > 10" class="px-6 py-3 border-b border-gray-200 bg-gray-50">
          <div class="relative">
            <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
              <MagnifyingGlassIcon class="h-4 w-4 text-gray-400" />
            </div>
            <input
                :value="getTagSearchQuery(tag)"
                class="block w-full pl-9 pr-8 py-2 border border-gray-300 rounded-md leading-5 bg-white placeholder-gray-500 focus:outline-none focus:placeholder-gray-400 focus:ring-1 focus:ring-blue-500 focus:border-blue-500 text-sm"
                placeholder="Search features in this tag..."
                type="text"
                @input="updateTagSearchQuery(tag, $event.target.value)"
            />
            <button
                v-if="getTagSearchQuery(tag)"
                class="absolute inset-y-0 right-0 pr-3 flex items-center min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 justify-center"
                @click="updateTagSearchQuery(tag, '')"
                title="Clear search"
            >
              <XMarkIcon class="h-4 w-4 text-gray-400 hover:text-gray-600" />
            </button>
          </div>
        </div>

        <!-- Features List -->
        <div class="divide-y divide-gray-200">
          <div
              v-for="(feature, index) in getPaginatedFeaturesForTag(tag)"
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
                    {{ feature.geometry?.type || 'Unknown' }}
                  </span>
                </div>
              </div>
              <div class="flex-shrink-0 relative z-10 flex items-center space-x-2">
                <button
                    v-if="!isSystemTag(tag)"
                    class="p-2 sm:p-1.5 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-red-600 focus:outline-none focus:ring-2 focus:ring-red-500 focus:ring-offset-1 rounded"
                    title="Remove this feature from tag"
                    type="button"
                    @click.stop.prevent="removeTagFromFeature(tag, feature)"
                >
                  <XMarkIcon class="w-4 h-4" />
                </button>
                <router-link
                    v-if="feature.properties.database_id"
                    :to="{ path: '/map', query: { featureId: feature.properties.database_id } }"
                    class="inline-flex items-center px-3 py-1.5 border border-gray-300 rounded-md text-xs font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 cursor-pointer"
                    @click.stop
                >
                  View on Map
                  <MapIcon class="w-3 h-3 ml-1" />
                </router-link>
              </div>
            </div>
          </div>
          <!-- Placeholder rows to keep list height consistent across pages -->
          <div
              v-for="n in getTagPlaceholderCount(tag)"
              :key="`placeholder-${tag}-${n}`"
              class="feature-row-placeholder border-t border-gray-200"
              aria-hidden="true"
          >
          </div>
        </div>

        <!-- Tag Feature Pagination Controls -->
        <div v-if="getTagFeatureCount(tag) > 10 && getTagTotalPages(tag) > 1" class="px-6 py-3 border-t border-gray-200 bg-gray-50">
          <div class="flex items-center justify-between flex-wrap gap-2">
            <div class="text-xs text-gray-600">
              Showing features {{ (getTagCurrentPage(tag) - 1) * tagFeaturePageSize + 1 }} - {{ Math.min(getTagCurrentPage(tag) * tagFeaturePageSize, getTagFilteredFeatureCount(tag)) }} of {{ getTagFilteredFeatureCount(tag) }}
            </div>
            <div class="flex items-center space-x-2">
              <button
                  :disabled="!getTagHasPreviousPage(tag)"
                  class="inline-flex items-center px-3 py-2 sm:px-2 sm:py-1 min-h-[44px] sm:min-h-0 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                  @click="tagPreviousPage(tag)"
                  title="Previous page"
              >
                <ArrowLeftIcon class="w-3 h-3 mr-1" />
                Prev
              </button>
              <span class="text-xs text-gray-700">Page {{ getTagCurrentPage(tag) }} of {{ getTagTotalPages(tag) }}</span>
              <button
                  :disabled="!getTagHasNextPage(tag)"
                  class="inline-flex items-center px-3 py-2 sm:px-2 sm:py-1 min-h-[44px] sm:min-h-0 border border-gray-300 shadow-sm text-xs font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-1 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                  @click="tagNextPage(tag)"
                  title="Next page"
              >
                Next
                <ArrowRightIcon class="w-3 h-3 ml-1" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Pagination Controls -->
    <div v-if="!loading && Object.keys(filteredTagsData).length > 0 && totalPages > 1" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="flex items-center justify-between flex-wrap gap-4">
        <div class="text-sm text-gray-700">
          Showing tags {{ (currentPage - 1) * pageSize + 1 }} - {{ Math.min(currentPage * pageSize, totalTags) }} of {{ totalTags }}
        </div>
        <div class="flex items-center space-x-2">
          <button
              :disabled="!hasPreviousPage || totalPages <= 1"
              class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              @click="previousPage"
              title="Go to previous page"
          >
            <ArrowLeftIcon class="w-4 h-4 mr-1" />
            Previous
          </button>
          <span class="text-sm text-gray-700">Page {{ currentPage }} of {{ totalPages }}</span>
          <button
              :disabled="!hasNextPage || totalPages <= 1"
              class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
              @click="nextPage"
              title="Go to next page"
          >
            Next
            <ArrowRightIcon class="w-4 h-4 ml-1" />
          </button>
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
            <button
                :disabled="!isValidPageNumber || totalPages <= 1"
                class="inline-flex items-center px-3 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
                @click="jumpToPage"
                title="Jump to page"
            >
              Go
            </button>
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

    <!-- Bulk Operations Modal -->
    <BulkStylingModal
        :isOpen="bulkOperationsModalOpen"
        :currentBulkOps="currentBulkOperationsForSelectedTag"
        :saving="bulkOperationsSaving"
        :autoCloseOnApply="false"
        @close="closeBulkOperationsModal"
        @apply="handleApplyBulkOperations"
    />
  </div>
</template>

<script>
import TagShareDialog from "./TagShareDialog.vue";
import Loader from "./parts/Loader.vue";
import BulkStylingModal from "@/components/import/parts/BulkStylingModal.vue";
import { createEmptyBulkOperations, cloneBulkOperations } from "@/utils/bulkOperations.js";
import { sortTagsByPriority, sortUserTagsAlphabetically, isSystemTag } from "@/utils/tagUtils.js";
import { MagnifyingGlassIcon, ExclamationCircleIcon, TagIcon, ShareIcon, ArrowDownTrayIcon, PencilIcon, TrashIcon, CheckIcon, MapIcon, ArrowLeftIcon, ArrowRightIcon, XMarkIcon, RectangleStackIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'TagsPage',
  components: {
    TagShareDialog,
    Loader,
    MagnifyingGlassIcon,
    ExclamationCircleIcon,
    TagIcon,
    ShareIcon,
    ArrowDownTrayIcon,
    PencilIcon,
    TrashIcon,
    CheckIcon,
    MapIcon,
    ArrowLeftIcon,
    ArrowRightIcon,
    XMarkIcon,
    RectangleStackIcon,
    BulkStylingModal
  },
  data() {
    return {
      tagsData: {}, // Combined user and system tags for display
      userTagsData: {}, // User tags only
      systemTagsData: {}, // System tags only
      loading: true,
      refreshing: false, // Background refresh state (separate from loading)
      error: null,
      searchQuery: '', // Search query for filtering tags
      editingTag: null, // Tag currently being edited
      editingTagValue: '', // Value of tag being edited
      shareDialogOpen: false, // Whether share dialog is open
      selectedTagForShare: '', // Tag selected for sharing
      currentPage: 1, // Current page number
      pageSize: 10, // Number of tags per page
      gotoPageInput: null, // Input value for jumping to a page
      tagFeaturePageSize: 10, // Number of features per page within a tag
      tagCurrentPages: {}, // Current page number for each tag { tagName: pageNumber }
      tagSearchQueries: {}, // Search query for each tag { tagName: query }
      paginationInfo: null, // Server-side pagination info
      searchDebounceTimer: null, // Timer for debouncing search input

      // Bulk operations state for tags page
      bulkOperationsModalOpen: false,
      bulkOperationsSelectedTag: '',
      bulkOperationsByTag: {}, // { tagName: bulkOps }
      bulkOperationsSaving: false
    }
  },
  computed: {
    // Use server-side filtered data (no client-side filtering needed)
    filteredTagsData() {
      return this.tagsData;
    },
    sortedTagKeys() {
      // Get sorted array of tag keys from filteredTagsData
      const tagKeys = Object.keys(this.filteredTagsData);
      
      // Separate user tags and system tags
      const userTags = tagKeys.filter(tag => !this.isSystemTag(tag));
      const systemTags = tagKeys.filter(tag => this.isSystemTag(tag));
      
      // Sort user tags alphabetically, system tags by priority
      const sortedUserTags = sortUserTagsAlphabetically(userTags);
      const sortedSystemTags = sortTagsByPriority(systemTags);
      
      // Return user tags first, then system tags
      return [...sortedUserTags, ...sortedSystemTags];
    },
    totalTags() {
      // Use server-side pagination info if available, otherwise fall back to client-side count
      if (this.paginationInfo) {
        return this.paginationInfo.total_tags;
      }
      return this.sortedTagKeys.length;
    },
    totalPages() {
      // Use server-side pagination info if available
      if (this.paginationInfo) {
        return this.paginationInfo.total_pages;
      }
      return Math.ceil(this.totalTags / this.pageSize);
    },
    hasNextPage() {
      // Use server-side pagination info if available
      if (this.paginationInfo) {
        return this.paginationInfo.has_next;
      }
      return this.currentPage < this.totalPages;
    },
    hasPreviousPage() {
      // Use server-side pagination info if available
      if (this.paginationInfo) {
        return this.paginationInfo.has_previous;
      }
      return this.currentPage > 1;
    },
    paginatedTagsData() {
      // Server already returns paginated data, so just return tagsData
      return this.tagsData;
    },
    isValidPageNumber() {
      return this.gotoPageInput &&
          this.gotoPageInput >= 1 &&
          this.gotoPageInput <= this.totalPages &&
          this.gotoPageInput !== this.currentPage;
    }
  },
  methods: {
    // Tag feature pagination methods
    getTagSearchQuery(tag) {
      return this.tagSearchQueries[tag] || '';
    },
    updateTagSearchQuery(tag, query) {
      this.tagSearchQueries[tag] = query;
      // Reset to page 1 when search changes
      this.tagCurrentPages[tag] = 1;
    },
    getTagCurrentPage(tag) {
      return this.tagCurrentPages[tag] || 1;
    },
    getTagFeatureCount(tag) {
      const features = this.filteredTagsData[tag] || [];
      return features.length;
    },
    getTagFilteredFeatures(tag) {
      const features = this.filteredTagsData[tag] || [];
      const searchQuery = this.getTagSearchQuery(tag);
      
      if (!searchQuery.trim()) {
        return features;
      }
      
      const query = searchQuery.toLowerCase().trim();
      return features.filter(feature => {
        const name = (feature.properties?.name || '').toLowerCase();
        const description = (feature.properties?.description || '').toLowerCase();
        const geometryType = (feature.geometry?.type || '').toLowerCase();
        return name.includes(query) || description.includes(query) || geometryType.includes(query);
      });
    },
    getTagFilteredFeatureCount(tag) {
      return this.getTagFilteredFeatures(tag).length;
    },
    getTagTotalPages(tag) {
      return Math.ceil(this.getTagFilteredFeatureCount(tag) / this.tagFeaturePageSize);
    },
    getTagHasNextPage(tag) {
      return this.getTagCurrentPage(tag) < this.getTagTotalPages(tag);
    },
    getTagHasPreviousPage(tag) {
      return this.getTagCurrentPage(tag) > 1;
    },
    getPaginatedFeaturesForTag(tag) {
      const filteredFeatures = this.getTagFilteredFeatures(tag);
      let currentPage = this.getTagCurrentPage(tag);
      const totalPages = Math.ceil(filteredFeatures.length / this.tagFeaturePageSize);
      
      // Ensure page is valid (not beyond total pages)
      if (totalPages > 0 && currentPage > totalPages) {
        currentPage = totalPages;
        this.tagCurrentPages[tag] = currentPage;
      } else if (currentPage < 1) {
        currentPage = 1;
        this.tagCurrentPages[tag] = currentPage;
      }
      
      const startIndex = (currentPage - 1) * this.tagFeaturePageSize;
      const endIndex = startIndex + this.tagFeaturePageSize;
      return filteredFeatures.slice(startIndex, endIndex);
    },
    tagNextPage(tag) {
      if (this.getTagHasNextPage(tag)) {
        const currentPage = this.getTagCurrentPage(tag);
        this.tagCurrentPages[tag] = currentPage + 1;
      }
    },
    tagPreviousPage(tag) {
      if (this.getTagHasPreviousPage(tag)) {
        const currentPage = this.getTagCurrentPage(tag);
        this.tagCurrentPages[tag] = currentPage - 1;
      }
    },
    getTagPlaceholderCount(tag) {
      // Only lock height when this tag has multiple feature pages
      if (this.getTagTotalPages(tag) <= 1) {
        return 0;
      }
      const currentPageFeatures = this.getPaginatedFeaturesForTag(tag);
      return Math.max(0, this.tagFeaturePageSize - currentPageFeatures.length);
    },
    isSystemTag(tag) {
      // Check if tag exists in systemTagsData
      return tag in this.systemTagsData;
    },
    async fetchTagsData(showLoading = true, mergeMode = false) {
      if (showLoading) {
        this.loading = true;
      }
      this.error = null;

      try {
        // Build query parameters
        const params = new URLSearchParams({
          page: this.currentPage.toString()
        });
        
        // Add search query if provided
        if (this.searchQuery.trim()) {
          params.append('search', this.searchQuery.trim());
        }
        
        const response = await fetch(`/api/features/by-tag/?${params.toString()}`);

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        const data = await response.json();

        if (response.ok) {
          const newUserTags = data.user_tags || {};
          const newSystemTags = data.system_tags || {};
          
          if (mergeMode) {
            // Merge mode: update only tags that are in the fetched data (current page)
            // Preserve tags that aren't on the current page
            // Update tags that are on the current page with fresh data
            Object.keys(newUserTags).forEach(tag => {
              this.userTagsData[tag] = newUserTags[tag];
            });
            Object.keys(newSystemTags).forEach(tag => {
              this.systemTagsData[tag] = newSystemTags[tag];
            });
            
            // Rebuild combined tagsData from merged data
            this.tagsData = {
              ...this.userTagsData,
              ...this.systemTagsData
            };
          } else {
            // Replace mode: completely replace the data
            this.userTagsData = newUserTags;
            this.systemTagsData = newSystemTags;
            
            // Combine both for display
            this.tagsData = {
              ...this.userTagsData,
              ...this.systemTagsData
            };
          }
          
          // Store pagination info from server
          if (data.pagination) {
            this.paginationInfo = data.pagination;
          } else {
            this.paginationInfo = null;
          }
        } else {
          throw new Error(data.error || 'Failed to load tags');
        }
      } catch (error) {
        console.error('Error fetching tags data:', error);
        this.error = error.message || 'Failed to load tags. Please try again.';
      } finally {
        if (showLoading) {
          this.loading = false;
        }
      }
    },
    async refreshTagsData() {
      // Background refresh that preserves pagination and search state
      this.refreshing = true;
      try {
        // Fetch data without showing full-page loader
        await this.fetchTagsData(false);
      } catch (error) {
        // Errors are already handled in fetchTagsData, just log here
        console.error('Error refreshing tags data:', error);
      } finally {
        this.refreshing = false;
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
      // Prevent editing system tags
      if (this.isSystemTag(oldTag)) {
        alert('System tags cannot be edited');
        this.cancelTagEdit();
        return;
      }

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

        // Prepare bulk update payload
        const updates = [];
        for (const feature of features) {
          if (!feature.properties.database_id) {
            continue;
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

          updates.push({
            feature_id: feature.properties.database_id,
            tags: currentTags
          });
        }

        // Send bulk update request
        if (updates.length > 0) {
          const csrfToken = this.getCookie('csrftoken');
          const response = await fetch('/api/features/bulk-update-metadata/', {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'X-CSRFToken': csrfToken || ''
            },
            body: JSON.stringify({
              updates: updates
            })
          });

          if (!response.ok) {
            const errorData = await response.json().catch(() => ({}));
            throw new Error(errorData.error || `Failed to update features: ${response.status}`);
          }

          const result = await response.json();
          // Response is successful if we got here (response.ok is true)

          // Check for any errors in the response
          if (result.errors && result.errors.length > 0) {
            const errorMessages = result.errors.map(e => `Feature ${e.feature_id}: ${e.error}`).join('\n');
            console.warn('Some features failed to update:', errorMessages);
            // Still continue with the update, but log the errors
          }
        }

        // Update local state
        const newTagsData = {...this.tagsData};
        newTagsData[newTag] = newTagsData[oldTag];
        delete newTagsData[oldTag];
        this.tagsData = newTagsData;
        
        // Also update userTagsData or systemTagsData to keep them in sync
        if (this.userTagsData[oldTag]) {
          this.userTagsData[newTag] = this.userTagsData[oldTag];
          delete this.userTagsData[oldTag];
        }
        if (this.systemTagsData[oldTag]) {
          this.systemTagsData[newTag] = this.systemTagsData[oldTag];
          delete this.systemTagsData[oldTag];
        }

        // Cancel edit mode
        this.cancelTagEdit();

        // Refresh the data to ensure consistency (merge mode to update only changed tags)
        await this.fetchTagsData(true, true);

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
      // Prevent deleting system tags
      if (this.isSystemTag(tag)) {
        alert('System tags cannot be deleted');
        return;
      }

      // Get the number of features with this tag
      const features = this.tagsData[tag] || [];
      const featureCount = features.length;

      // Show confirmation dialog
      const confirmMessage = `Are you sure you want to delete the tag "${tag}"?\n\nThis will remove the tag from ${featureCount} ${featureCount === 1 ? 'feature' : 'features'}.`;
      if (!confirm(confirmMessage)) {
        return;
      }

      try {
        // Prepare bulk update payload
        const updates = [];
        for (const feature of features) {
          if (!feature.properties.database_id) {
            continue;
          }

          // Get current tags
          const currentTags = Array.isArray(feature.properties.tags)
              ? [...feature.properties.tags]
              : [];

          // Remove the tag from the array
          const filteredTags = currentTags.filter(t => t !== tag);

          updates.push({
            feature_id: feature.properties.database_id,
            tags: filteredTags
          });
        }

        // Send bulk update request
        if (updates.length > 0) {
          const csrfToken = this.getCookie('csrftoken');
          const response = await fetch('/api/features/bulk-update-metadata/', {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'X-CSRFToken': csrfToken || ''
            },
            body: JSON.stringify({
              updates: updates
            })
          });

          if (!response.ok) {
            const errorData = await response.json().catch(() => ({}));
            throw new Error(errorData.error || `Failed to update features: ${response.status}`);
          }

          const result = await response.json();
          // Response is successful if we got here (response.ok is true)

          // Check for any errors in the response
          if (result.errors && result.errors.length > 0) {
            const errorMessages = result.errors.map(e => `Feature ${e.feature_id}: ${e.error}`).join('\n');
            console.warn('Some features failed to update:', errorMessages);
            // Still continue with the update, but log the errors
          }
        }

        // Remove tag from local state
        const newTagsData = {...this.tagsData};
        delete newTagsData[tag];
        this.tagsData = newTagsData;
        
        // Also remove from userTagsData or systemTagsData
        if (this.userTagsData[tag]) {
          delete this.userTagsData[tag];
        }
        if (this.systemTagsData[tag]) {
          delete this.systemTagsData[tag];
        }

        // Refresh the data to ensure consistency (merge mode to update only changed tags)
        await this.fetchTagsData(true, true);
      } catch (error) {
        console.error('Error deleting tag:', error);
        alert(`Failed to delete tag: ${error.message}`);
      }
    },
    async removeTagFromFeature(tag, feature) {
      if (!feature.properties.database_id) {
        return;
      }

      // Prevent removing system tags
      if (this.isSystemTag(tag)) {
        alert('System tags cannot be removed from features');
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
        const response = await fetch(`/api/feature/${feature.properties.database_id}/update-metadata/`, {
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
          throw new Error(`Failed to update feature ${feature.properties.database_id}`);
        }

        // Update local state - remove feature from tag's list
        const newTagsData = {...this.tagsData};
        if (newTagsData[tag]) {
          newTagsData[tag] = newTagsData[tag].filter(f => f.properties.database_id !== feature.properties.database_id);
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
    openBulkOperationsModal(tag) {
      this.bulkOperationsSelectedTag = tag;
      this.bulkOperationsModalOpen = true;
    },
    closeBulkOperationsModal() {
      this.bulkOperationsModalOpen = false;
    },
    async handleApplyBulkOperations(bulkData) {
      if (!this.bulkOperationsSelectedTag) {
        this.bulkOperationsModalOpen = false;
        return;
      }
      const tag = this.bulkOperationsSelectedTag;
      // Persist last used bulk operations per tag in local state
      this.bulkOperationsByTag = {
        ...this.bulkOperationsByTag,
        [tag]: cloneBulkOperations(bulkData)
      };

      this.bulkOperationsSaving = true;
      try {
        await this.applyBulkOperationsToTag(tag, bulkData);
        this.bulkOperationsModalOpen = false;
      } finally {
        this.bulkOperationsSaving = false;
      }
    },
    async applyBulkOperationsToTag(tag, bulkData) {
      try {
        const csrfToken = this.getCookie('csrftoken');
        const response = await fetch(`/api/features/bulk-operations/by-tag/${encodeURIComponent(tag)}/`, {
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

        // Refresh tags data to reflect styling/tag changes (merge mode to update only changed tags)
        await this.fetchTagsData(true, true);
      } catch (error) {
        console.error('Error applying bulk operations to tag:', error);
        alert(`Failed to apply bulk operations: ${error.message}`);
      }
    },
    currentBulkOperationsForTag(tag) {
      return this.bulkOperationsByTag[tag] || createEmptyBulkOperations();
    },
    currentBulkOperationsForSelectedTag() {
      if (!this.bulkOperationsSelectedTag) {
        return createEmptyBulkOperations();
      }
      return this.currentBulkOperationsForTag(this.bulkOperationsSelectedTag);
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
    },
    downloadTagKmz(tag) {
      const url = `/api/export-kmz?tag=${encodeURIComponent(tag)}`;
      window.open(url, '_blank');
    },
    viewTagOnMap(tag) {
      // Navigate to map page with tag query parameter
      this.$router.push({
        path: '/map',
        query: { tag: tag }
      });
    },
    nextPage() {
      if (this.hasNextPage) {
        this.currentPage++;
        this.gotoPageInput = null;
        this.fetchTagsData();
        // Scroll to top of tags list
        this.$nextTick(() => {
          const tagsList = this.$el.querySelector('.space-y-4');
          if (tagsList) {
            tagsList.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }
        });
      }
    },
    previousPage() {
      if (this.hasPreviousPage) {
        this.currentPage--;
        this.gotoPageInput = null;
        this.fetchTagsData();
        // Scroll to top of tags list
        this.$nextTick(() => {
          const tagsList = this.$el.querySelector('.space-y-4');
          if (tagsList) {
            tagsList.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }
        });
      }
    },
    jumpToPage() {
      if (this.isValidPageNumber) {
        this.currentPage = this.gotoPageInput;
        this.gotoPageInput = null;
        this.fetchTagsData();
        // Scroll to top of tags list
        this.$nextTick(() => {
          const tagsList = this.$el.querySelector('.space-y-4');
          if (tagsList) {
            tagsList.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }
        });
      }
    }
  },
  watch: {
    searchQuery() {
      // Clear existing timer
      if (this.searchDebounceTimer) {
        clearTimeout(this.searchDebounceTimer);
      }
      
      // Reset to first page immediately (for UI responsiveness)
      this.currentPage = 1;
      this.gotoPageInput = null;
      
      // Debounce the API call - wait 400ms after user stops typing
      this.searchDebounceTimer = setTimeout(() => {
        this.fetchTagsData();
      }, 400);
    },
    $route(to, from) {
      // Refresh data when navigating back to tags page from another route
      // Skip on initial mount (handled by mounted hook) and if already refreshing
      // Only refresh if we have existing data (not initial load) and coming from different route
      if (to.path === '/tags' && 
          from.path !== '/tags' && 
          from.path !== '/' && 
          !this.refreshing &&
          Object.keys(this.tagsData).length > 0) {
        this.refreshTagsData();
      }
    }
  },
  async mounted() {
    await this.fetchTagsData();
  },
  beforeRouteEnter(to, from, next) {
    // Handle initial navigation to tags page
    // Skip refresh on initial mount - let mounted() handle it
    next();
  },
  beforeRouteUpdate(to, from, next) {
    // Refresh data when navigating to tags page from another route
    if (to.path === '/tags' && from.path !== '/tags') {
      // Use next() callback to access component instance
      next((vm) => {
        vm.refreshTagsData();
      });
    } else {
      next();
    }
  },
  activated() {
    // Handle navigation back to tags page when component is kept alive
    // Only refresh if we have data already (not initial mount) and not already refreshing
    if (Object.keys(this.tagsData).length > 0 && !this.refreshing) {
      this.refreshTagsData();
    }
  },
  beforeUnmount() {
    // Clear debounce timer when component is destroyed
    if (this.searchDebounceTimer) {
      clearTimeout(this.searchDebounceTimer);
    }
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

.feature-row {
  min-height: 96px; /* Ensures consistent height for all feature rows */
}

.feature-row-placeholder {
  height: 97px; /* 96px + 1px for the border to match feature rows with dividers */
}
</style>

