<template>
  <div :class="isMapRoute ? 'h-screen bg-gray-50 overflow-hidden' : 'min-h-screen bg-gray-50'">
    <!-- Navigation Header -->
    <nav class="bg-white shadow-sm border-b border-gray-200">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="flex justify-between h-16">
          <div class="flex items-center">
            <div class="flex-shrink-0">
              <router-link class="flex items-center space-x-2 hover:opacity-80 transition-opacity" to="/">
                <img alt="GeoVault Logo" class="h-8 w-auto" src="/images/logo.svg"/>
                <h1 class="text-xl font-bold text-gray-900">GeoVault</h1>
              </router-link>
            </div>
            <div v-if="!userInfoLoading && userInfo" class="hidden sm:ml-6 sm:flex sm:space-x-8">
              <router-link
                  :class="{ 'text-gray-900 border-gray-500': $route.path === '/dashboard' || $route.path === '/' }"
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
                  :class="{ 'text-gray-900 border-gray-500': $route.path === '/tags' }"
                  class="inline-flex items-center px-1 pt-1 text-sm font-medium text-gray-500 hover:text-gray-700 hover:border-gray-300 border-b-2 border-transparent transition-colors duration-200"
                  to="/tags"
              >
                Tags
              </router-link>
              <router-link
                  :class="{ 'text-gray-900 border-gray-500': $route.path === '/collections' }"
                  class="inline-flex items-center px-1 pt-1 text-sm font-medium text-gray-500 hover:text-gray-700 hover:border-gray-300 border-b-2 border-transparent transition-colors duration-200"
                  to="/collections"
              >
                Collections
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
            <div ref="userMenuRef" class="relative">
              <button
                  v-if="!userInfoLoading && userInfo?.email"
                  class="flex items-center text-sm font-medium text-gray-900 hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 rounded-md px-3 py-2"
                  @click="toggleUserMenu"
              >
                {{ userInfo?.email }}
                <svg class="ml-2 h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path d="M19 9l-7 7-7-7" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"></path>
                </svg>
              </button>

              <!-- Login Link (shown when user is not logged in and auth check is complete) -->
              <a
                  v-if="!userInfoLoading && !userInfo"
                  class="text-sm font-medium text-gray-500 hover:text-gray-700 px-3 py-2"
                  href="/accounts/login/"
              >
                Sign In
              </a>

              <!-- Dropdown Menu -->
              <div
                  v-if="userMenuOpen"
                  class="absolute right-0 mt-2 w-48 bg-white rounded-md shadow-lg py-1 z-50 border border-gray-200"
              >
                <router-link
                    class="block px-4 py-2 text-sm text-gray-700 hover:bg-gray-100"
                    to="/settings"
                    @click="closeUserMenu"
                >
                  Settings
                </router-link>
                <router-link
                    v-if="userInfo?.isSuperuser"
                    class="block px-4 py-2 text-sm text-gray-700 hover:bg-gray-100"
                    to="/admin"
                    @click="closeUserMenu"
                >
                  Admin Panel
                </router-link>
                <div class="border-t border-gray-200 my-1"></div>
                <button
                    class="block w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-100"
                    @click="performLogout"
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
    <main
        :class="isMapRoute ? 'w-full h-[calc(100vh-4rem)] overflow-hidden' : 'max-w-7xl mx-auto py-6 px-4 sm:px-6 lg:px-8'">
      <!-- Show loading state while checking authentication for protected routes -->
      <div v-if="userInfoLoading && !isPublicShareRoute" class="flex items-center justify-center min-h-[400px]">
        <Loader layout="centered" />
      </div>
      <!-- Render router-view only after auth check completes (or immediately for public routes) -->
      <router-view v-if="!userInfoLoading || isPublicShareRoute" v-slot="{ Component }">
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
import Loader from "@/components/parts/Loader.vue";

export default {
  name: 'App',
  components: {
    Loader
  },
  data() {
    return {
      realtimeListenersAdded: false,
      userMenuOpen: false,
      userInfoLoading: true
    }
  },
  computed: {
    ...mapState(["userInfo"]),
    isMapRoute() {
      return this.$route.path === '/map' || this.$route.path === '/mapshare'
    },
    isPublicShareRoute() {
      return this.$route.path === '/mapshare'
    }
  },
  watch: {
    userInfo: {
      handler(newUserInfo, oldUserInfo) {
        // If user becomes unauthorized (userInfo is cleared), disconnect WebSocket and redirect
        if (oldUserInfo && !newUserInfo) {
          this.handleLogout();
          // Clear user settings when user logs out
          this.$store.commit('userSettings', null);
          // Redirect to login if not on a public share route
          const hash = window.location.hash || '';
          const isPublicShare = hash.startsWith('#/mapshare');
          if (!isPublicShare) {
            window.location.href = '/accounts/login/';
          }
        }
        // If user becomes authorized (userInfo is set), ensure WebSocket is connected
        if (newUserInfo && !realtimeSocket.isConnected) {
          this.setupRealtimeConnection();
        }
      },
      deep: true
    },
    $route: {
      handler(to, from) {
        // Don't check auth during initial load - that's handled by checkAuth() in created()
        // Only check on route changes after initial auth check is complete
        if (this.userInfoLoading) {
          return;
        }
        // Redirect to login if userInfo is null and not on a public share route
        if (to.path !== '/mapshare' && !this.userInfo) {
          window.location.href = '/accounts/login/';
          return;
        }
        
        // Check admin access
        if (to.meta.requiresAdmin && this.userInfo && !this.userInfo.isSuperuser) {
          this.$router.push('/');
          return;
        }

        // When navigating to authenticated routes, ensure WebSocket is connected if user is authorized
        if (to.path !== '/mapshare' && this.userInfo && !realtimeSocket.isConnected) {
          this.setupRealtimeConnection();
        }
      },
      immediate: false
    }
  },
  methods: {
    async checkAuth() {
      // Check if we're on a public share route using window.location.hash
      // since $route might not be ready yet
      const hash = window.location.hash || '';
      const isPublicShare = hash.startsWith('#/mapshare');

      this.userInfoLoading = true;
      
      // Use centralized store action to fetch user info
      const userStatus = await this.$store.dispatch('fetchUserInfo');

      if (!userStatus || !userStatus.authorized) {
        // User is not authorized (guest)
        if (isPublicShare) {
          // On public share routes, allow access without redirecting
          this.userInfoLoading = false;
          return;
        }
        // On other routes, redirect to login
        window.location.href = '/accounts/login/';
        return;
      }

      this.userInfoLoading = false;
      const userInfo = this.$store.state.userInfo;

      // Check admin access for initial route
      if (this.$route.meta.requiresAdmin && !userInfo.isSuperuser) {
        this.$router.push('/');
      }

      // Load user settings after authentication
      try {
        await this.$store.dispatch('fetchUserSettings');
      } catch (error) {
        console.error('Error loading user settings:', error);
        // Continue even if settings fail to load
      }

      // Always setup WebSocket connection if user is authorized (not just for non-public routes)
      // The setupRealtimeConnection method will skip public share routes internally
      await this.setupRealtimeConnection();
    },
    async setupRealtimeConnection() {
      // Only connect if user is authorized (userInfo exists means user is authorized)
      if (!this.userInfo) {
        return;
      }

      // Skip WebSocket connection for public share routes
      if (this.isPublicShareRoute) {
        return;
      }

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
        this.$store.dispatch('setWebSocketConnected', true);
        this.$store.dispatch('setWebSocketReconnectAttempts', 0);
      });

      realtimeSocket.on('disconnected', () => {
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

        // Get CSRF token
        const csrfToken = getCookie('csrftoken');
        if (!csrfToken) {
          console.error('CSRF token not found');
          // Even if CSRF token is missing, clear local state and redirect
          this.$store.commit('userInfo', null);
          this.$store.commit('userSettings', null);
          window.location.href = '/accounts/login/';
          return;
        }

        // Call allauth logout endpoint (requires POST with CSRF token in form data)
        // Django allauth expects the CSRF token as 'csrfmiddlewaretoken' in the form body
        const formData = new URLSearchParams();
        formData.append('csrfmiddlewaretoken', csrfToken);

        await axios.post('/accounts/logout/', formData.toString(), {
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'X-CSRFToken': csrfToken
          }
        });

        // Clear user info and settings from store
        this.$store.commit('userInfo', null);
        this.$store.commit('userSettings', null);

        // Redirect to login page
        window.location.href = '/accounts/login/';
      } catch (error) {
        console.error('Logout error:', error);
        // Even if logout fails, clear local state and redirect
        this.$store.commit('userInfo', null);
        this.$store.commit('userSettings', null);
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
    },
  },
  async created() {
    // Always check authentication (even on public share routes) to set userInfo if logged in
    await this.checkAuth();
    // WebSocket connection is now handled in checkAuth() after userInfo is set
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
