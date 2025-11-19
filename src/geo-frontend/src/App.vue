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
            <div class="relative" ref="userMenuRef">
              <button
                @click="toggleUserMenu"
                class="flex items-center text-sm font-medium text-gray-900 hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 rounded-md px-3 py-2"
              >
                {{ userInfo?.username || 'Guest' }}
                <svg class="ml-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"></path>
                </svg>
              </button>
              
              <!-- Dropdown Menu -->
              <div
                v-if="userMenuOpen"
                class="absolute right-0 mt-2 w-48 bg-white rounded-md shadow-lg py-1 z-50 border border-gray-200"
              >
                <router-link
                  to="/settings"
                  @click="closeUserMenu"
                  class="block px-4 py-2 text-sm text-gray-700 hover:bg-gray-100"
                >
                  Settings
                </router-link>
                <div class="border-t border-gray-200 my-1"></div>
                <button
                  @click="performLogout"
                  class="block w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100"
                >
                  Sign Out
                </button>
              </div>
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
import {getCookie} from "@/assets/js/auth.js";
import axios from "axios";

export default {
  name: 'App',
  data() {
    return {
      realtimeListenersAdded: false,
      userMenuOpen: false
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
    async setupRealtimeConnection() {
      // Load all modules from registry first
      await realtimeSocket.loadAllModules(this.$store);
      
      // Handle connection status (only add listeners once)
      if (!this.realtimeListenersAdded) {
        this.addRealtimeListeners();
        this.realtimeListenersAdded = true;
      }
      
      // Connect socket after modules are loaded and listeners are set up
      if (!realtimeSocket.isConnected) {
        realtimeSocket.connect();
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
    },
    handleLogout() {
      realtimeSocket.forceDisconnect();
    },
    async performLogout() {
      this.closeUserMenu();
      try {
        // Disconnect WebSocket first
        this.handleLogout();
        
        // Call allauth logout endpoint (requires POST with CSRF token)
        await axios.post('/accounts/logout/', {}, {
          headers: {
            'X-CSRFToken': getCookie('csrftoken')
          }
        });
        
        // Clear user info from store
        this.$store.commit('userInfo', null);
        
        // Redirect to login page
        window.location.href = '/accounts/login/';
      } catch (error) {
        console.error('Logout error:', error);
        // Even if logout fails, clear local state and redirect
        this.$store.commit('userInfo', null);
        window.location.href = '/accounts/login/';
      }
    },
    toggleUserMenu() {
      this.userMenuOpen = !this.userMenuOpen;
    },
    closeUserMenu() {
      this.userMenuOpen = false;
    },
    handleClickOutside(event) {
      if (this.$refs.userMenuRef && !this.$refs.userMenuRef.contains(event.target)) {
        this.userMenuOpen = false;
      }
    }
  },
  async created() {
    // Setup realtime connection after auth check
    this.setupRealtimeConnection();
  },
  mounted() {
    // WebSocket connection is managed globally and persists across page navigation
    // Add click outside listener for user menu
    document.addEventListener('click', this.handleClickOutside);
  },
  beforeDestroy() {
    // Don't disconnect WebSocket here - let it stay connected across the app lifecycle
    // Remove click outside listener
    document.removeEventListener('click', this.handleClickOutside);
  }
};
</script>
