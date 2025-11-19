<template>
  <div class="space-y-6">
    <!-- Page Header -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h1 class="text-2xl font-bold text-gray-900 mb-2">Dashboard</h1>
      <p class="text-gray-600">Welcome back, {{ userInfo.username }}! Here's an overview of your account and recent activity.</p>
    </div>

    <!-- User Info Card -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Account Information</h2>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div class="flex items-center space-x-3">
          <div class="flex-shrink-0">
            <div class="w-10 h-10 bg-blue-100 rounded-full flex items-center justify-center">
              <svg class="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"></path>
              </svg>
            </div>
          </div>
          <div>
            <p class="text-sm font-medium text-gray-500">Username</p>
            <p class="text-lg font-semibold text-gray-900">{{ userInfo.username }}</p>
          </div>
        </div>
        <div class="flex items-center space-x-3">
          <div class="flex-shrink-0">
            <div class="w-10 h-10 bg-green-100 rounded-full flex items-center justify-center">
              <svg class="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 7h.01M7 3h5c.512 0 1.024.195 1.414.586l7 7a2 2 0 010 2.828l-7 7a2 2 0 01-2.828 0l-7-7A1.994 1.994 0 013 12V7a4 4 0 014-4z"></path>
              </svg>
            </div>
          </div>
          <div>
            <p class="text-sm font-medium text-gray-500">User ID</p>
            <p class="text-lg font-semibold text-gray-900">{{ userInfo.id }}</p>
          </div>
        </div>
        <div class="flex items-center space-x-3">
          <div class="flex-shrink-0">
            <div class="w-10 h-10 bg-purple-100 rounded-full flex items-center justify-center">
              <svg class="w-6 h-6 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
              </svg>
            </div>
          </div>
          <div>
            <p class="text-sm font-medium text-gray-500">Features</p>
            <p class="text-lg font-semibold text-gray-900">{{ userInfo.featureCount }}</p>
          </div>
        </div>
      </div>
    </div>

    <!-- Tags Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold text-gray-900">Your Tags</h2>
        <router-link
          v-if="filteredTags && filteredTags.length > 0"
          to="/tags"
          class="text-sm font-medium text-blue-600 hover:text-blue-800"
        >
          View All Tags →
        </router-link>
      </div>
      <div v-if="filteredTags && filteredTags.length > 0" class="flex flex-wrap gap-2">
        <router-link
          v-for="tagObj in filteredTags"
          :key="tagObj.tag"
          to="/tags"
          class="inline-flex items-center px-3 py-1 rounded-full text-sm font-medium bg-blue-100 text-blue-800 border border-blue-200 hover:bg-blue-200 transition-colors cursor-pointer"
        >
          <span>{{ tagObj.tag }}</span>
          <span class="ml-1.5 px-1.5 py-0.5 rounded-full text-xs font-semibold bg-blue-200 text-blue-900">
            {{ tagObj.count }}
          </span>
        </router-link>
      </div>
      <div v-else class="text-gray-500 text-sm">
        No tags found. Tags will appear here once you import features with tags.
      </div>
    </div>

    <!-- Quick Actions -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Quick Actions</h2>
      <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        <router-link 
          to="/import" 
          class="flex items-center p-4 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors duration-200 group"
        >
          <div class="flex-shrink-0">
            <div class="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center group-hover:bg-blue-200 transition-colors duration-200">
              <svg class="w-6 h-6 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
              </svg>
            </div>
          </div>
          <div class="ml-4">
            <h3 class="text-sm font-medium text-gray-900 group-hover:text-blue-600">Import Data</h3>
            <p class="text-sm text-gray-500">Upload and process KML/KMZ files</p>
          </div>
        </router-link>

        <router-link 
          to="/import/upload" 
          class="flex items-center p-4 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors duration-200 group"
        >
          <div class="flex-shrink-0">
            <div class="w-10 h-10 bg-green-100 rounded-lg flex items-center justify-center group-hover:bg-green-200 transition-colors duration-200">
              <svg class="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 6v6m0 0v6m0-6h6m-6 0H6"></path>
              </svg>
            </div>
          </div>
          <div class="ml-4">
            <h3 class="text-sm font-medium text-gray-900 group-hover:text-green-600">Upload Files</h3>
            <p class="text-sm text-gray-500">Quick file upload interface</p>
          </div>
        </router-link>

        <router-link 
          to="/map" 
          class="flex items-center p-4 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors duration-200 group"
        >
          <div class="flex-shrink-0">
            <div class="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center group-hover:bg-purple-200 transition-colors duration-200">
              <svg class="w-6 h-6 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
              </svg>
            </div>
          </div>
          <div class="ml-4">
            <h3 class="text-sm font-medium text-gray-900 group-hover:text-purple-600">View Map</h3>
            <p class="text-sm text-gray-500">Interactive geospatial data visualization</p>
          </div>
        </router-link>
      </div>
    </div>
  </div>
</template>

<script>
import {mapState} from "vuex";
import {authMixin} from "@/assets/js/authMixin.js";

export default {
  computed: {
    ...mapState(["userInfo"]),
    filteredTags() {
      if (!this.userInfo || !this.userInfo.tags) {
        return [];
      }
      // Tags are now objects with tag and count properties, already filtered by backend
      // Just return them as-is (they're already top 5 and filtered)
      return this.userInfo.tags;
    }
  },
  components: {},
  mixins: [authMixin],
  data() {
    return {
    }
  },
  methods: {},
  async created() {
  },
  async mounted() {
  },
  watch: {},
}
</script>

<style scoped>

</style>