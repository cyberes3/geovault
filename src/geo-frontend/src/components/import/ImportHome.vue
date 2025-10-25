<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between">
        <div>
          <div class="flex items-center">
            <h1 class="text-2xl font-bold text-gray-900 mb-2">Import Data</h1>
            <div v-if="isRefreshing" class="ml-3 flex items-center text-sm text-gray-500">
              <svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-gray-400" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              Updating...
            </div>
          </div>
          <p class="text-gray-600">Manage your geospatial data imports and view processing history.</p>
        </div>
        <div class="flex items-center space-x-3">
          <button
            @click="refreshTables"
            :disabled="isRefreshing"
            class="inline-flex items-center px-3 py-2 border border-gray-300 text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <svg class="w-4 h-4 mr-2" :class="{ 'animate-spin': isRefreshing }" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"></path>
            </svg>
            {{ isRefreshing ? 'Refreshing...' : 'Refresh' }}
          </button>
          <router-link
            to="/import/upload"
            class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors duration-200"
          >
            <svg class="w-4 h-4 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6"></path>
            </svg>
            Upload Files
          </router-link>
        </div>
      </div>
    </div>

    <!-- Ready to Import Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Ready to Import</h2>
      <p class="text-sm text-gray-600 mb-4">Files that have been uploaded and are ready for processing.</p>

      <!-- Import queue component -->
      <ImportQueue :is-loading="importQueueIsLoading"/>
    </div>

    <!-- Import History Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Import History</h2>
      <p class="text-sm text-gray-600 mb-4">Download your previously imported files.</p>

      <div class="overflow-hidden">
        <table class="min-w-full divide-y divide-gray-200">
          <thead class="bg-gray-50">
            <tr>
              <th class="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">File Name</th>
              <th class="px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider">Date/Time Imported</th>
            </tr>
          </thead>
          <tbody class="bg-white divide-y divide-gray-200">
            <tr v-for="(item, index) in importHistory" :key="`history-${index}`" class="hover:bg-gray-50">
              <td class="px-6 py-4 whitespace-nowrap">
                <a :href="`${IMPORT_HISTORY_URL()}/${item.id}`" class="text-sm font-medium text-blue-600 hover:text-blue-900">
                  {{ item.original_filename }}
                </a>
              </td>
              <td class="px-6 py-4 whitespace-nowrap text-center text-sm text-gray-900">
                {{ item.timestamp }}
              </td>
            </tr>
            <tr v-for="n in 3" v-if="combinedHistoryLoading" :key="`history-loading-${n}`" class="animate-pulse">
              <td class="px-6 py-4 whitespace-nowrap">
                <div class="flex items-center">
                  <div class="w-8 h-8 bg-gray-200 rounded-lg"></div>
                  <div class="ml-4 w-32 h-4 bg-gray-200 rounded"></div>
                </div>
              </td>
              <td class="px-6 py-4 whitespace-nowrap text-center">
                <div class="w-24 h-4 bg-gray-200 rounded mx-auto"></div>
              </td>
            </tr>
            <tr v-if="!combinedHistoryLoading && importHistory.length === 0">
              <td colspan="3" class="px-6 py-12 text-center">
                <div class="flex flex-col items-center">
                  <div class="w-12 h-12 bg-gray-100 rounded-full flex items-center justify-center mb-3">
                    <svg class="w-6 h-6 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
                    </svg>
                  </div>
                  <h3 class="text-sm font-medium text-gray-900 mb-1">No import history yet</h3>
                  <p class="text-sm text-gray-500">Files you've successfully imported will appear here.</p>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script>
import {mapState} from "vuex"
import {authMixin} from "@/assets/js/authMixin.js";
import {IMPORT_HISTORY_URL} from "@/assets/js/import/url.js";
import ImportQueue from "@/components/import/parts/ImportQueue.vue";

export default {
  computed: {
    ...mapState(["userInfo", "importQueue", "importHistory", "importHistoryLoaded"]),
    combinedHistoryLoading() {
      // Show loading placeholders only when:
      // 1. We haven't received initial data from WebSocket yet
      // 2. AND we don't have any data yet
      return !this.importHistoryLoaded && this.importHistory.length === 0;
    }
  },
  components: {ImportQueue: ImportQueue},
  mixins: [authMixin],
  data() {
    return {
      importQueueIsLoading: true,
      hasImportQueueLoaded: false,
      refreshInterval: null,
      isRefreshing: false,
    }
  },
  methods: {
    IMPORT_HISTORY_URL() {
      return IMPORT_HISTORY_URL
    },
    async fetchImportQueue(showLoading = true) {
      if (showLoading) {
        this.importQueueIsLoading = true
      }
      try {
        await this.$store.dispatch('refreshImportQueue')
      } catch (error) {
        console.error('Error fetching import queue:', error)
      } finally {
        if (showLoading) {
          this.importQueueIsLoading = false
        }
        this.hasImportQueueLoaded = true
      }
    },
    startAutoRefresh() {
      // Clear any existing interval
      this.stopAutoRefresh()

      // Start auto-refresh every 5 seconds for import queue only
      // Import history is now handled by WebSocket
      this.refreshInterval = setInterval(() => {
        // Don't call fetchImportQueue during auto-refresh to avoid duplicate API calls
        // The ImportQueue component will handle its own auto-refresh
        // History is now handled by WebSocket
      }, 5000)
    },
    stopAutoRefresh() {
      if (this.refreshInterval) {
        clearInterval(this.refreshInterval)
        this.refreshInterval = null
      }
    },
    async refreshTables() {
      // Force immediate refresh of import queue with loading indicators
      // Import history is now handled by WebSocket
      this.isRefreshing = true
      try {
        await this.fetchImportQueue(true)
      } finally {
        this.isRefreshing = false
      }
    },
  },
  async created() {
    // If we already have data, mark as initially loaded
    // This prevents showing loading placeholders when navigating back with browser buttons
    if (this.importHistory && this.importHistory.length > 0) {
      this.$store.dispatch('setImportHistoryLoaded', true);
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
  beforeRouteEnter(to, from, next) {
    next(async vm => {
      // Always refresh data when entering the route
      // This handles both navigation from other routes and direct access
      await vm.refreshTables()
      // Start auto-refresh when entering the route
      vm.startAutoRefresh()
    })
  },
  beforeRouteUpdate(to, from, next) {
    // Refresh data immediately when updating from a different route
    if (from.name && from.name !== 'Import') {
      this.refreshTables().then(() => {
        this.startAutoRefresh()
        next()
      })
    } else {
      // Start auto-refresh when updating to the same route
      this.startAutoRefresh()
      next()
    }
  },
  beforeRouteLeave(to, from, next) {
    // Stop auto-refresh when leaving the route
    this.stopAutoRefresh()
    next()
  },
  watch: {},
}
</script>

<style scoped>
</style>
