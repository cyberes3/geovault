<template>
  <div :class="isMapRoute ? 'h-screen bg-gray-50 overflow-hidden' : 'min-h-screen bg-gray-50'">
    <!-- Navigation Header -->
    <nav class="bg-white shadow-sm border-b border-gray-200">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="flex justify-between h-16">
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <h1 class="text-xl font-bold text-gray-900">GeoServer</h1>
            </div>
            <div class="hidden sm:ml-6 sm:flex sm:space-x-8">
              <router-link
                  :class="{ 'text-gray-900 border-gray-500': $route.path === '/' }"
                  class="inline-flex items-center px-1 pt-1 text-sm font-medium text-gray-500 hover:text-gray-700 hover:border-gray-300 border-b-2 border-transparent transition-colors duration-200"
                  to="/"
              >
                Home
              </router-link>
              <router-link
                  :class="{ 'text-gray-900 border-gray-500': $route.path === '/dashboard' }"
                  class="inline-flex items-center px-1 pt-1 text-sm font-medium text-gray-500 hover:text-gray-700 hover:border-gray-300 border-b-2 border-transparent transition-colors duration-200"
                  to="/dashboard"
              >
                Dashboard
              </router-link>
              <router-link
                  :class="{ 'text-gray-900 border-gray-500': $route.path.startsWith('/import') }"
                  class="inline-flex items-center px-1 pt-1 text-sm font-medium text-gray-500 hover:text-gray-700 hover:border-gray-300 border-b-2 border-transparent transition-colors duration-200"
                  to="/import"
              >
                Import
              </router-link>
              <router-link
                  :class="{ 'text-gray-900 border-gray-500': $route.path === '/map' }"
                  class="inline-flex items-center px-1 pt-1 text-sm font-medium text-gray-500 hover:text-gray-700 hover:border-gray-300 border-b-2 border-transparent transition-colors duration-200"
                  to="/map"
              >
                Map
              </router-link>
            </div>
          </div>
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <span class="text-sm text-gray-500">Welcome, {{ userInfo?.username || 'Guest' }}</span>
            </div>
          </div>
        </div>
      </div>
    </nav>

    <!-- Main Content -->
    <main :class="isMapRoute ? 'w-full h-[calc(100vh-4rem)] overflow-hidden' : 'max-w-7xl mx-auto py-6 px-4 sm:px-6 lg:px-8'">
      <router-view v-slot="{ Component }">
        <keep-alive>
          <component :is="Component"/>
        </keep-alive>
      </router-view>
    </main>
  </div>
</template>

<script>
import {mapState} from "vuex";
import {realtimeSocket} from "@/assets/js/websocket/realtimeSocket.js";

export default {
  name: 'App',
  data() {
    return {
      realtimeListenersAdded: false
    }
  },
  computed: {
    ...mapState(["userInfo"]),
    isMapRoute() {
      return this.$route.path === '/map'
    }
  },
  watch: {
    userInfo: {
      handler(newUserInfo, oldUserInfo) {
        // If user becomes unauthorized, disconnect WebSocket
        if (oldUserInfo && oldUserInfo.authorized && newUserInfo && !newUserInfo.authorized) {
          this.handleLogout();
        }
      },
      deep: true
    }
  },
  methods: {
    setupRealtimeConnection() {
      // Only connect if not already connected
      if (!realtimeSocket.isConnected) {
        realtimeSocket.connect();
      }

      // Handle connection status (only add listeners once)
      if (!this.realtimeListenersAdded) {
        this.addRealtimeListeners();
        this.realtimeListenersAdded = true;
      }
    },
    addRealtimeListeners() {
      // Handle connection status
      realtimeSocket.on('connected', () => {
        console.log('Realtime WebSocket connected');
        this.$store.dispatch('setWebSocketConnected', true);
        this.$store.dispatch('setWebSocketReconnectAttempts', 0);
      });

      realtimeSocket.on('disconnected', () => {
        console.log('Realtime WebSocket disconnected');
        this.$store.dispatch('setWebSocketConnected', false);
      });

      realtimeSocket.on('max_reconnect_attempts_reached', () => {
        console.error('Realtime WebSocket max reconnection attempts reached');
        this.$store.dispatch('setWebSocketReconnectAttempts', realtimeSocket.maxReconnectAttempts);
      });

      // Subscribe to import queue module
      realtimeSocket.subscribe('import_queue', 'initial_state', (data) => {
        console.log('Received import queue initial state:', data);
        this.$store.dispatch('setRealtimeModuleData', {module: 'importQueue', data});
        // Also update the legacy importQueue state for backward compatibility
        this.$store.commit('setImportQueue', data);
      });

      realtimeSocket.subscribe('import_queue', 'item_added', (data) => {
        console.log('Import queue item added:', data);
        // Request refresh to get updated data
        realtimeSocket.requestRefresh('import_queue');
      });

      realtimeSocket.subscribe('import_queue', 'item_deleted', (data) => {
        console.log('Import queue item deleted:', data);
        this.$store.dispatch('removeImportQueueItem', data.id.toString());
      });

      realtimeSocket.subscribe('import_queue', 'items_deleted', (data) => {
        console.log('Import queue items deleted:', data);
        this.$store.dispatch('removeImportQueueItems', data.ids.map(id => id.toString()));
      });

      realtimeSocket.subscribe('import_queue', 'status_updated', (data) => {
        console.log('Import queue status updated:', data);

        let updates = {
          processing: data.status === 'processing',
          processing_failed: data.status === 'failed'
        }

        // Handle completed status - need to get the actual feature count from the server
        if (data.status === 'completed') {
          updates.processing = false;
          updates.processing_failed = false;
          // Request a refresh to get the updated item with correct feature count
          realtimeSocket.requestRefresh('import_queue');
          return;
        }

        // For processing status, set feature_count to -1 to indicate processing
        if (data.status === 'processing') {
          updates.feature_count = -1;
        }

        // Update the specific item in the store
        this.$store.dispatch('updateImportQueueItem', {
          id: data.id.toString(),
          updates: updates
        });
      });

      realtimeSocket.subscribe('import_queue', 'item_imported', (data) => {
        console.log('Import queue item imported:', data);
        this.$store.dispatch('updateImportQueueItem', {
          id: data.id.toString(),
          updates: {imported: true}
        });
      });

    }
  },
  async created() {
    // Setup realtime connection after auth check
    this.setupRealtimeConnection();
  },
  mounted() {
    // WebSocket connection is managed globally and persists across page navigation
  },
  beforeDestroy() {
    // Don't disconnect WebSocket here - let it stay connected across the app lifecycle
  }
};
</script>
