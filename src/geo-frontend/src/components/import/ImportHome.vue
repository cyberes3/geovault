<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between">
        <div>
          <h1 class="text-2xl font-bold text-gray-900 mb-2">Import Data</h1>
          <p class="text-gray-600">Manage your geospatial data imports and view processing history.</p>
        </div>
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
            <tr v-for="(item, index) in history" :key="`history-${index}`" class="hover:bg-gray-50">
              <td class="px-6 py-4 whitespace-nowrap">
                <a :href="`${IMPORT_HISTORY_URL()}/${item.id}`" class="text-sm font-medium text-blue-600 hover:text-blue-900">
                  {{ item.original_filename }}
                </a>
              </td>
              <td class="px-6 py-4 whitespace-nowrap text-center text-sm text-gray-900">
                {{ item.timestamp }}
              </td>
            </tr>
            <tr v-for="n in 3" v-if="historyIsLoading" :key="`history-loading-${n}`" class="animate-pulse">
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
            <tr v-if="!historyIsLoading && history.length === 0">
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
import axios from "axios";
import {IMPORT_HISTORY_URL} from "@/assets/js/import/url.js";
import Importqueue from "@/components/import/parts/importqueue.vue";

export default {
  computed: {
    ...mapState(["userInfo", "importQueue"]),
  },
  components: {ImportQueue: Importqueue},
  mixins: [authMixin],
  data() {
    return {
      history: [],
      historyIsLoading: true,
      importQueueIsLoading: true,
      hasImportQueueLoaded: false,
    }
  },
  methods: {
    IMPORT_HISTORY_URL() {
      return IMPORT_HISTORY_URL
    },
    async fetchHistory() {
      const response = await axios.get(IMPORT_HISTORY_URL)
      this.history = response.data.data
      this.historyIsLoading = false
    },
    async fetchImportQueue() {
      this.importQueueIsLoading = true
      try {
        await this.$store.dispatch('refreshImportQueue')
      } finally {
        this.importQueueIsLoading = false
        this.hasImportQueueLoaded = true
      }
    },
  },
  async created() {
    await Promise.all([
      this.fetchHistory(),
      this.fetchImportQueue()
    ])
  },
  mounted() {
    // Auto-refresh disabled on import home page - only needed on upload page
    // this.startAutoRefresh()
  },
  // beforeRouteEnter(to, from, next) {
  //   next(async vm => {
  //   })
  // },
  watch: {},
}
</script>

<style scoped>
</style>
