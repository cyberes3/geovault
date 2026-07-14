<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div class="flex items-center">
            <h1 class="text-xl sm:text-2xl font-bold text-gray-900 mb-2">Import Data</h1>
            <div v-if="isRefreshing" class="ml-3 flex items-center text-sm text-gray-500">
              <Loader size="sm" layout="inline" :show-message="false" />
              <span class="ml-2">Updating...</span>
            </div>
          </div>
          <p class="text-gray-600">Manage your geospatial data imports and view processing history.</p>
        </div>
        <div class="flex flex-col sm:flex-row space-y-2 sm:space-y-0 sm:space-x-3 mt-4 sm:mt-0">
          <BaseButton
            tag="router-link"
            to="/import/upload"
            class="w-full sm:w-auto"
            variant="primary"
            color="blue"
            size="md"
          >
            <CloudArrowUpIcon class="w-4 h-4 mr-2" />
            Upload Files
          </BaseButton>
        </div>
      </div>
    </div>

    <!-- Ready to Import Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <h2 class="text-base sm:text-lg font-semibold text-gray-900 mb-4">Ready to Import</h2>

      <!-- Import table component -->
      <ImportTable :is-loading="importTableIsLoading"/>
    </div>

    <!-- Import History Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
      <h2 class="text-base sm:text-lg font-semibold text-gray-900 mb-4">Import History</h2>
      <p class="text-sm text-gray-600 mb-4">Click to download your previously imported files.</p>

      <div class="flex flex-col">
        <!-- Header Row (Desktop only) -->
        <div class="hidden md:flex bg-gray-50 px-3 py-3 sm:px-6 sm:py-3 border-b border-gray-200">
          <div class="flex-1 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">File Name</div>
          <div class="flex-1 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Date Imported</div>
        </div>

        <!-- Items -->
        <div class="flex flex-col space-y-3 md:space-y-0 md:divide-y md:divide-gray-200">
          <div v-for="(item, index) in importHistory" :key="`history-${index}`" class="flex flex-col md:flex-row md:items-center p-3 md:p-0 md:px-3 md:py-3 lg:px-6 lg:py-4 border border-gray-200 md:border-0 rounded-lg md:rounded-none hover:bg-gray-50 transition-colors">
            <div class="flex-1 mb-2 md:mb-0">
              <a :href="`${IMPORT_HISTORY_URL()}/${item.id}`" class="text-xs sm:text-sm font-medium text-blue-500 hover:text-blue-700 break-words">
                {{ item.original_filename }}
              </a>
            </div>
            <div class="flex-1 text-xs sm:text-sm text-gray-900 md:text-center">
              {{ formatDate(item.timestamp) }}
            </div>
          </div>

          <!-- Loading States -->
          <template v-if="combinedHistoryLoading">
            <div v-for="n in 3" :key="`history-loading-${n}`" class="flex flex-col md:flex-row md:items-center p-3 md:p-0 md:px-3 md:py-3 lg:px-6 lg:py-4 border border-gray-200 md:border-0 rounded-lg md:rounded-none animate-pulse">
              <div class="flex-1 mb-2 md:mb-0">
                <div class="w-3/4 md:w-32 h-4 bg-gray-200 rounded"></div>
              </div>
              <div class="flex-1 md:text-center">
                <div class="w-1/2 md:w-24 h-4 bg-gray-200 rounded md:mx-auto"></div>
              </div>
            </div>
          </template>

          <!-- Empty State -->
          <div v-if="!combinedHistoryLoading && importHistory.length === 0" class="py-12 text-center">
            <div class="flex flex-col items-center">
              <h3 class="text-lg font-medium text-gray-900 mb-2">No import history yet</h3>
              <p class="text-gray-500 mb-6 max-w-sm">Files you've successfully imported will appear here.</p>
            </div>
          </div>
        </div>
      </div>

      <!-- Pagination Controls -->
      <div v-if="!combinedHistoryLoading && importHistoryPagination.totalPages > 1" class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 mt-4">
        <div class="flex items-center justify-between flex-wrap gap-4">
          <div class="text-sm text-gray-700">
            Showing {{ (importHistoryPagination.page - 1) * importHistoryPagination.pageSize + 1 }} - 
            {{ Math.min(importHistoryPagination.page * importHistoryPagination.pageSize, importHistoryPagination.totalItems) }} 
            of {{ importHistoryPagination.totalItems }}
          </div>
          <div class="flex items-center space-x-2">
            <BaseButton
              :disabled="!importHistoryPagination.hasPrevious || isLoadingHistoryPage || importHistoryPagination.totalPages <= 1"
              variant="white"
              size="sm"
              @click="previousPage"
              title="Go to Previous Page"
            >
              <ArrowLeftIcon class="w-4 h-4 mr-1" />
              Previous
            </BaseButton>
            <span class="text-sm text-gray-700">Page {{ importHistoryPagination.page }} of {{ importHistoryPagination.totalPages }}</span>
            <BaseButton
              :disabled="!importHistoryPagination.hasNext || isLoadingHistoryPage || importHistoryPagination.totalPages <= 1"
              variant="white"
              size="sm"
              @click="nextPage"
              title="Go to Next Page"
            >
              Next
              <ArrowRightIcon class="w-4 h-4 ml-1" />
            </BaseButton>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue'
import { IMPORT_HISTORY_URL } from "@/assets/js/import/url.js";
import { getImportHistory } from "@/api/services/importApi";
import ImportTable from "@/components/import/parts/ImportTable.vue";
import Loader from "@/components/parts/Loader.vue";
import BaseButton from "@/components/parts/BaseButton.vue";
import { CloudArrowUpIcon, ArrowLeftIcon, ArrowRightIcon } from '@heroicons/vue/24/outline';
import { formatDate } from "@/utils/dateUtils.js";
import type { NavigationGuardNext, RouteLocationNormalized } from 'vue-router';
import type {
  ImportHistoryItem,
  ImportHistoryPagination,
  BackendImportHistoryPagination,
} from "@/assets/js/store/modules/importQueue";

/** Narrow view of root getters this component reads by namespaced key. */
interface RootGetters {
  'importQueue/importHistory': ImportHistoryItem[];
  'importQueue/importHistoryLoaded': boolean;
  'importQueue/importHistoryPagination': ImportHistoryPagination;
}

/** Fields `beforeRouteEnter`'s `next(vm => ...)` callback needs on this component's instance. */
interface ImportHomePageInstance {
  refreshTables: () => Promise<void>;
  startAutoRefresh: () => void;
}

export default defineComponent({
  name: 'ImportHomePage',
  components: {
    ImportTable: ImportTable,
    Loader,
    BaseButton,
    CloudArrowUpIcon,
    ArrowLeftIcon,
    ArrowRightIcon
  },
  data() {
    return {
      importTableIsLoading: true,
      hasImportTableLoaded: false,
      refreshInterval: null as ReturnType<typeof setInterval> | null,
      isRefreshing: false,
      isLoadingHistoryPage: false,
    }
  },
  computed: {
    importHistory(): ImportHistoryItem[] {
      return (this.$store.getters as RootGetters)['importQueue/importHistory'];
    },
    importHistoryLoaded(): boolean {
      return (this.$store.getters as RootGetters)['importQueue/importHistoryLoaded'];
    },
    importHistoryPagination(): ImportHistoryPagination {
      return (this.$store.getters as RootGetters)['importQueue/importHistoryPagination'];
    },
    combinedHistoryLoading(): boolean {
      // Show loading placeholders only when:
      // 1. We haven't received initial data from WebSocket yet
      // 2. AND we don't have any data yet
      return !this.importHistoryLoaded && this.importHistory.length === 0;
    },
  },
  methods: {
    IMPORT_HISTORY_URL(): string {
      return IMPORT_HISTORY_URL
    },
    async fetchImportTable(showLoading = true): Promise<void> {
      if (showLoading) {
        this.importTableIsLoading = true
      }
      // The import table itself is kept up to date by the WebSocket
      // (importQueue/setImportTable); this just controls the loading indicator.
      if (showLoading) {
        this.importTableIsLoading = false
      }
      this.hasImportTableLoaded = true
    },
    startAutoRefresh(): void {
      // Clear any existing interval
      this.stopAutoRefresh()

      // Start auto-refresh every 5 seconds for import table only
      // Import history is now handled by WebSocket
      this.refreshInterval = setInterval(() => {
        // Don't call fetchImportTable during auto-refresh to avoid duplicate API calls
        // The ImportTable component will handle its own auto-refresh
        // History is now handled by WebSocket
      }, 5000)
    },
    stopAutoRefresh(): void {
      if (this.refreshInterval) {
        clearInterval(this.refreshInterval)
        this.refreshInterval = null
      }
    },
    async refreshTables(): Promise<void> {
      // Force immediate refresh of import table with loading indicators
      // Import history is now handled by WebSocket
      this.isRefreshing = true
      try {
        await this.fetchImportTable(true)
      } finally {
        this.isRefreshing = false
      }
    },
    async loadPage(page: number): Promise<void> {
      // Load a specific page via REST API
      if (this.isLoadingHistoryPage) {
        return; // Prevent concurrent requests
      }
      
      this.isLoadingHistoryPage = true;
      try {
        const data = (await getImportHistory(page, 10)) as {
          items: ImportHistoryItem[];
          pagination: BackendImportHistoryPagination;
        };

        // Update store with paginated data
        void this.$store.dispatch('importQueue/setImportHistory', {
          items: data.items,
          pagination: data.pagination
        });
        void this.$store.dispatch('importQueue/setImportHistoryPage', page);
      } catch (error) {
        console.error('Error loading import history page:', error);
      } finally {
        this.isLoadingHistoryPage = false;
      }
    },
    async nextPage(): Promise<void> {
      if (this.importHistoryPagination.hasNext) {
        await this.loadPage(this.importHistoryPagination.page + 1);
      }
    },
    async previousPage(): Promise<void> {
      if (this.importHistoryPagination.hasPrevious) {
        await this.loadPage(this.importHistoryPagination.page - 1);
      }
    },
    formatDate,
  },
  created() {
    // If we already have data, mark as initially loaded
    // This prevents showing loading placeholders when navigating back with browser buttons
    if (this.importHistory.length > 0) {
      void this.$store.dispatch('importQueue/setImportHistoryLoaded', true);
    }

    // Don't fetch data here - let the route guards handle it
    // This prevents duplicate API calls during navigation
  },
  mounted() {
    // Start auto-refresh for both tables
    this.startAutoRefresh()
  },
  beforeUnmount() {
    // Stop auto-refresh when component is about to be destroyed
    this.stopAutoRefresh()
  },
  beforeRouteEnter(_to: RouteLocationNormalized, _from: RouteLocationNormalized, next: NavigationGuardNext) {
    next(async (vm) => {
      const instance = vm as unknown as ImportHomePageInstance
      // Always refresh data when entering the route
      // This handles both navigation from other routes and direct access
      await instance.refreshTables()
      // Start auto-refresh when entering the route
      instance.startAutoRefresh()
    })
  },
  async beforeRouteUpdate(_to: RouteLocationNormalized, from: RouteLocationNormalized, next: NavigationGuardNext) {
    // Refresh data immediately when updating from a different route
    if (from.name && from.name !== 'Import') {
      await this.refreshTables()
      this.startAutoRefresh()
      next()
    } else {
      // Start auto-refresh when updating to the same route
      this.startAutoRefresh()
      next()
    }
  },
  beforeRouteLeave(_to: RouteLocationNormalized, _from: RouteLocationNormalized, next: NavigationGuardNext) {
    // Stop auto-refresh when leaving the route
    this.stopAutoRefresh()
    next()
  },
})
</script>

<style scoped>
</style>
