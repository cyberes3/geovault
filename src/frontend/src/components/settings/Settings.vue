<template>
  <div class="max-w-7xl mx-auto">
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6 mb-6">
      <h1 class="text-2xl font-bold text-gray-900">Settings</h1>
      <p class="text-gray-600 mt-1">Manage your account settings and preferences.</p>
    </div>

    <div class="flex flex-col lg:flex-row gap-6">
      <!-- Sidebar Navigation -->
      <div class="lg:w-64 flex-shrink-0">
        <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
          <nav class="space-y-1">
            <button
              @click="activeTab = 'account'"
              :class="[
                'w-full text-left px-4 py-3 rounded-md text-sm font-medium transition-colors duration-200',
                activeTab === 'account'
                  ? 'bg-primary-50 text-primary-700 border-l-4 border-primary-500'
                  : 'text-gray-700 hover:bg-gray-50'
              ]"
              title="Account settings"
            >
              <div class="flex items-center">
                <svg class="w-5 h-5 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"></path>
                </svg>
                Account
              </div>
            </button>
            <button
              @click="activeTab = 'map'"
              :class="[
                'w-full text-left px-4 py-3 rounded-md text-sm font-medium transition-colors duration-200',
                activeTab === 'map'
                  ? 'bg-primary-50 text-primary-700 border-l-4 border-primary-500'
                  : 'text-gray-700 hover:bg-gray-50'
              ]"
              title="Map settings"
            >
              <div class="flex items-center">
                <svg class="w-5 h-5 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
                </svg>
                Map
              </div>
            </button>
            <button
              @click="activeTab = 'sharing'"
              :class="[
                'w-full text-left px-4 py-3 rounded-md text-sm font-medium transition-colors duration-200',
                activeTab === 'sharing'
                  ? 'bg-primary-50 text-primary-700 border-l-4 border-primary-500'
                  : 'text-gray-700 hover:bg-gray-50'
              ]"
              title="Sharing settings"
            >
              <div class="flex items-center">
                <svg class="w-5 h-5 mr-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8.684 13.342C8.885 12.938 9 12.482 9 12c0-.482-.115-.938-.316-1.342m0 2.684a3 3 0 110-2.684m0 2.684l6.632 3.316m-6.632-6l6.632-3.316m0 0a3 3 0 105.367-2.684 3 3 0 00-5.367 2.684zm0 9.316a3 3 0 105.368 2.684 3 3 0 00-5.368-2.684z"></path>
                </svg>
                Sharing
              </div>
            </button>
          </nav>
        </div>
      </div>

      <!-- Tab Content -->
      <div class="flex-1">
        <keep-alive>
          <component :is="currentTabComponent" :toast-ref="toastRef" />
        </keep-alive>
      </div>
    </div>

    <!-- Toast Notifications -->
    <Toast ref="toast" />
  </div>
</template>

<script>
import Toast from "@/components/parts/Toast.vue";
import AccountSettingsTab from "./AccountSettingsTab.vue";
import MapSettingsTab from "./MapSettingsTab.vue";
import SharingSettingsTab from "./SharingSettingsTab.vue";

export default {
  name: 'Settings',
  components: {
    Toast,
    AccountSettingsTab,
    MapSettingsTab,
    SharingSettingsTab
  },
  data() {
    return {
      activeTab: 'account',
      isInitializing: true
    }
  },
  computed: {
    toastRef() {
      return this.$refs.toast;
    },
    currentTabComponent() {
      const components = {
        'account': 'AccountSettingsTab',
        'map': 'MapSettingsTab',
        'sharing': 'SharingSettingsTab'
      };
      return components[this.activeTab] || 'AccountSettingsTab';
    }
  },
  watch: {
    activeTab(newTab) {
      // Update URL query parameter when tab changes (but not during initialization)
      // Only update if we're on the /settings route
      if (!this.isInitializing && this.$route.path === '/settings' && this.$route.query.tab !== newTab) {
        // Use push instead of replace so tab changes create history entries
        // This allows back button to navigate through tabs
        // Only include the tab param, don't spread other query params
        this.$router.push({
          path: '/settings',
          query: { tab: newTab }
        });
      }
    },
    '$route.query.tab'(newTab) {
      // Only process tab changes when on /settings route
      if (this.$route.path !== '/settings') {
        return;
      }
      // Update activeTab when route query parameter changes
      if (newTab && ['account', 'map', 'sharing'].includes(newTab)) {
        if (this.activeTab !== newTab) {
          this.activeTab = newTab;
        }
      } else if (!newTab && this.activeTab !== 'account') {
        // Default to 'account' if no tab query parameter
        this.activeTab = 'account';
      }
    }
  },
  beforeRouteLeave(to, from, next) {
    // Let navigation proceed - the global router guard will clean up the tab query param
    // This guard is kept for potential future use or component-specific cleanup
    next();
  },
  created() {
    // Only initialize if we're on the /settings route
    if (this.$route.path !== '/settings') {
      this.isInitializing = false;
      return;
    }

    // Initialize activeTab from query parameter
    const tabFromQuery = this.$route.query.tab;
    if (tabFromQuery && ['account', 'map', 'sharing'].includes(tabFromQuery)) {
      this.activeTab = tabFromQuery;
      // Clean up any other query params that shouldn't be here
      const otherParams = Object.keys(this.$route.query).filter(key => key !== 'tab');
      if (otherParams.length > 0) {
        this.$router.replace({
          path: '/settings',
          query: { tab: tabFromQuery }
        });
      }
    } else {
      // If no valid tab in query, set default tab
      // Update URL immediately using replace to avoid creating history entry
      // This replaces the current /settings entry with /settings?tab=account
      const targetTab = 'account';
      this.activeTab = targetTab;
      // Use replace synchronously during initialization before watchers can fire
      // Clean up any unrelated query params
      this.$router.replace({
        path: '/settings',
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



