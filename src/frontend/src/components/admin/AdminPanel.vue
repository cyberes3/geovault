<template>
  <div v-if="!isAuthorized" class="max-w-7xl mx-auto">
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6 mb-6">
      <div class="flex items-center justify-center py-12">
        <div class="text-center">
          <svg class="mx-auto h-12 w-12 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"></path>
          </svg>
          <h2 class="mt-4 text-lg font-medium text-gray-900">Access Denied</h2>
          <p class="mt-2 text-sm text-gray-500">
            You do not have permission to access this page.
          </p>
          <router-link
            to="/dashboard"
            class="mt-4 inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700"
          >
            Go to Dashboard
          </router-link>
        </div>
      </div>
    </div>
  </div>
  <div v-else class="max-w-7xl mx-auto">
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6 mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Admin Panel</h1>
      <p class="text-gray-600 mt-1">System administration and management.</p>
    </div>

    <div class="flex flex-col lg:flex-row gap-6">
      <!-- Sidebar Navigation -->
      <div class="lg:w-64 flex-shrink-0">
        <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
          <nav class="space-y-1">
            <button
              @click="activeTab = 'overview'"
              :class="[
                'w-full text-left px-4 py-3 rounded-md text-sm font-medium transition-colors duration-200',
                activeTab === 'overview'
                  ? 'bg-blue-50 text-blue-700 border-l-4 border-blue-500'
                  : 'text-gray-700 hover:bg-gray-50'
              ]"
              title="Overview"
            >
              <div class="flex items-center">
                <svg class="w-5 h-5 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"></path>
                </svg>
                Overview
              </div>
            </button>
            <button
              @click="activeTab = 'users'"
              :class="[
                'w-full text-left px-4 py-3 rounded-md text-sm font-medium transition-colors duration-200',
                activeTab === 'users'
                  ? 'bg-blue-50 text-blue-700 border-l-4 border-blue-500'
                  : 'text-gray-700 hover:bg-gray-50'
              ]"
              title="Users list"
            >
              <div class="flex items-center">
                <svg class="w-5 h-5 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z"></path>
                </svg>
                Users
              </div>
            </button>
          </nav>
        </div>
      </div>

      <!-- Tab Content -->
      <div class="flex-1">
        <keep-alive>
          <component :is="currentTabComponent" />
        </keep-alive>
      </div>
    </div>
  </div>
</template>

<script>
import {mapState} from "vuex";
import OverviewTab from "./OverviewTab.vue";
import UsersListTab from "./UsersListTab.vue";

export default {
  name: 'AdminPanel',
  components: {
    OverviewTab,
    UsersListTab
  },
  computed: {
    ...mapState(["userInfo"]),
    isAuthorized() {
      return this.userInfo && this.userInfo.isSuperuser === true;
    },
    currentTabComponent() {
      const components = {
        'overview': 'OverviewTab',
        'users': 'UsersListTab'
      };
      return components[this.activeTab] || 'OverviewTab';
    }
  },
  data() {
    return {
      activeTab: 'overview',
      isInitializing: true
    }
  },
  watch: {
    activeTab(newTab) {
      // Update URL query parameter when tab changes (but not during initialization)
      // Only update if we're on the /admin route
      if (!this.isInitializing && this.$route.path === '/admin' && this.$route.query.tab !== newTab) {
        // Use push instead of replace so tab changes create history entries
        // This allows back button to navigate through tabs
        // Only include the tab param, don't spread other query params
        this.$router.push({
          path: '/admin',
          query: { tab: newTab }
        });
      }
    },
    '$route.query.tab'(newTab) {
      // Only process tab changes when on /admin route
      if (this.$route.path !== '/admin') {
        return;
      }
      // Update activeTab when route query parameter changes
      if (newTab && ['overview', 'users'].includes(newTab)) {
        if (this.activeTab !== newTab) {
          this.activeTab = newTab;
        }
      } else if (!newTab && this.activeTab !== 'overview') {
        // Default to 'overview' if no tab query parameter
        this.activeTab = 'overview';
      }
    }
  },
  mounted() {
    document.title = 'Admin Panel - GeoVault';

    // Redirect if not authorized (defense in depth)
    if (!this.isAuthorized) {
      // Small delay to show the error message, then redirect
      setTimeout(() => {
        this.$router.push('/dashboard');
      }, 2000);
      return;
    }

    // Initialize activeTab from query parameter
    const tabFromQuery = this.$route.query.tab;
    if (tabFromQuery && ['overview', 'users'].includes(tabFromQuery)) {
      this.activeTab = tabFromQuery;
      // Clean up any other query params that shouldn't be here
      const otherParams = Object.keys(this.$route.query).filter(key => key !== 'tab');
      if (otherParams.length > 0) {
        this.$router.replace({
          path: '/admin',
          query: { tab: tabFromQuery }
        });
      }
    } else {
      // If no valid tab in query, set default tab
      // Update URL immediately using replace to avoid creating history entry
      // This replaces the current /admin entry with /admin?tab=overview
      const targetTab = 'overview';
      this.activeTab = targetTab;
      // Use replace synchronously during initialization before watchers can fire
      // Clean up any unrelated query params
      this.$router.replace({
        path: '/admin',
        query: { tab: targetTab }
      });
    }

    // Mark initialization as complete after a tick to ensure watchers are set up
    this.$nextTick(() => {
      this.isInitializing = false;
    });
  }
}
</script>

