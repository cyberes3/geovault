<template>
  <div :class="isMapRoute ? 'h-screen bg-gray-50 overflow-hidden' : 'min-h-screen bg-gray-50'">
    <!-- Navigation Header -->
    <nav class="bg-white shadow-sm border-b border-gray-200 relative z-50">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="flex justify-between h-16">
          
          <!-- Logo & Hamburger Container -->
          <div class="flex items-center justify-between w-full md:w-auto">
            <!-- Logo -->
            <div class="flex-shrink-0 flex items-center">
              <router-link class="flex items-center space-x-2 hover:opacity-80 transition-opacity" to="/">
                <img alt="GeoVault Logo" class="h-8 w-auto" src="/images/logo.svg"/>
                <h1 class="text-xl font-bold text-gray-900">GeoVault</h1>
              </router-link>
            </div>
            
            <!-- Hamburger (Mobile Only) -->
            <div class="flex items-center md:hidden">
              <button
                  v-if="!userInfoLoading && userInfo"
                  class="inline-flex items-center justify-center p-2 rounded-md text-gray-700 hover:text-gray-900 hover:bg-gray-100 focus:outline-none focus:ring-2 focus:ring-inset focus:ring-blue-500"
                  @click="toggleMobileMenu"
                  aria-label="Toggle menu"
              >
                <Bars3Icon v-if="!mobileMenuOpen" class="h-6 w-6" />
                <XMarkIcon v-else class="h-6 w-6" />
              </button>
              
              <!-- Mobile Login Link (Only if not logged in) -->
              <a
                  v-if="!userInfoLoading && !userInfo"
                  class="text-sm font-medium text-gray-500 hover:text-gray-700 px-3 py-2"
                  href="/accounts/login/"
              >
                Sign In
              </a>
            </div>
          </div>

          <!-- Unified Menu Container -->
          <div 
            v-if="!userInfoLoading"
            :class="[
              'md:flex md:items-center md:ml-6 md:flex-1',
              mobileMenuOpen && userInfo ? 'fixed inset-x-0 top-16 z-50 flex flex-col bg-white shadow-lg p-4 sm:p-6 space-y-4 rounded-b-lg overflow-y-auto border-b border-gray-200' : 'hidden'
            ]"
          >
            
            <!-- Navigation Links -->
            <div v-if="userInfo" class="flex flex-col md:flex-row md:space-x-8 space-y-2 md:space-y-0">
              <router-link
                  :class="[
                    $route.path === '/dashboard' || $route.path === '/' 
                      ? 'text-blue-600 border-blue-500 bg-blue-50 md:bg-transparent' 
                      : 'text-gray-500 border-transparent hover:text-gray-700 hover:border-gray-300 hover:bg-gray-50 md:hover:bg-transparent',
                    'block md:inline-flex items-center px-3 md:px-1 py-2 md:pt-1 text-base md:text-sm font-medium border-l-4 md:border-l-0 md:border-b-2 transition-colors duration-200 rounded-r-md md:rounded-none'
                  ]"
                  to="/dashboard"
                  @click="closeMobileMenu"
              >
                Dashboard
              </router-link>
              <router-link
                  :class="[
                    $route.path.startsWith('/import')
                      ? 'text-blue-600 border-blue-500 bg-blue-50 md:bg-transparent' 
                      : 'text-gray-500 border-transparent hover:text-gray-700 hover:border-gray-300 hover:bg-gray-50 md:hover:bg-transparent',
                    'block md:inline-flex items-center px-3 md:px-1 py-2 md:pt-1 text-base md:text-sm font-medium border-l-4 md:border-l-0 md:border-b-2 transition-colors duration-200 rounded-r-md md:rounded-none'
                  ]"
                  to="/import"
                  @click="closeMobileMenu"
              >
                Import
              </router-link>
              <router-link
                  :class="[
                    $route.path === '/tags'
                      ? 'text-blue-600 border-blue-500 bg-blue-50 md:bg-transparent' 
                      : 'text-gray-500 border-transparent hover:text-gray-700 hover:border-gray-300 hover:bg-gray-50 md:hover:bg-transparent',
                    'block md:inline-flex items-center px-3 md:px-1 py-2 md:pt-1 text-base md:text-sm font-medium border-l-4 md:border-l-0 md:border-b-2 transition-colors duration-200 rounded-r-md md:rounded-none'
                  ]"
                  to="/tags"
                  @click="closeMobileMenu"
              >
                Tags
              </router-link>
              <router-link
                  :class="[
                    $route.path === '/collections'
                      ? 'text-blue-600 border-blue-500 bg-blue-50 md:bg-transparent' 
                      : 'text-gray-500 border-transparent hover:text-gray-700 hover:border-gray-300 hover:bg-gray-50 md:hover:bg-transparent',
                    'block md:inline-flex items-center px-3 md:px-1 py-2 md:pt-1 text-base md:text-sm font-medium border-l-4 md:border-l-0 md:border-b-2 transition-colors duration-200 rounded-r-md md:rounded-none'
                  ]"
                  to="/collections"
                  @click="closeMobileMenu"
              >
                Collections
              </router-link>
              <router-link
                  :class="[
                    $route.path === '/map'
                      ? 'text-blue-600 border-blue-500 bg-blue-50 md:bg-transparent' 
                      : 'text-gray-500 border-transparent hover:text-gray-700 hover:border-gray-300 hover:bg-gray-50 md:hover:bg-transparent',
                    'block md:inline-flex items-center px-3 md:px-1 py-2 md:pt-1 text-base md:text-sm font-medium border-l-4 md:border-l-0 md:border-b-2 transition-colors duration-200 rounded-r-md md:rounded-none'
                  ]"
                  to="/map"
                  @click="closeMobileMenu"
              >
                Map
              </router-link>
            </div>

            <!-- Account Section -->
            <div class="flex items-center md:ml-auto">
              <!-- Logged In -->
              <div v-if="userInfo" class="w-full md:w-auto relative md:ml-3" ref="userMenuRef">
                
                <!-- Desktop Trigger -->
                <button
                    class="hidden md:flex items-center text-sm font-medium text-gray-900 hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 rounded-md px-3 py-2"
                    @click="toggleUserMenu"
                >
                  {{ userInfo.email }}
                  <ChevronDownIcon class="ml-2 h-4 w-4" />
                </button>

                <!-- Mobile Header -->
                <div class="md:hidden px-4 sm:px-6 py-2 border-t border-gray-200 mt-2 pt-4">
                  <div class="text-base font-medium text-gray-900">{{ userInfo.email }}</div>
                </div>

                <!-- Menu Items (Dropdown on Desktop, Static on Mobile) -->
                <div
                    :class="[
                      'md:absolute md:right-0 md:mt-2 md:w-48 md:bg-white md:rounded-md md:shadow-lg md:py-1 md:border md:border-gray-200 z-50',
                      (!userMenuOpen) ? 'md:hidden' : '',
                      'block'
                    ]"
                >
                  <router-link
                      :class="[
                        $route.path === '/settings'
                          ? 'text-blue-600 border-blue-500 bg-blue-50 md:bg-gray-100 md:border-transparent'
                          : 'text-gray-700 border-transparent hover:text-gray-900 md:hover:bg-gray-100 hover:bg-gray-50',
                        'block px-4 sm:px-6 md:px-4 py-2 text-base md:text-sm font-normal border-l-4 md:border-l-0 rounded-r-md md:rounded-none'
                      ]"
                      to="/settings"
                      @click="() => { closeUserMenu(); closeMobileMenu(); }"
                  >
                    Settings
                  </router-link>
                  <router-link
                      v-if="userInfo.isSuperuser"
                      :class="[
                        $route.path === '/admin'
                          ? 'text-blue-600 border-blue-500 bg-blue-50 md:bg-gray-100 md:border-transparent'
                          : 'text-gray-700 border-transparent hover:text-gray-900 md:hover:bg-gray-100 hover:bg-gray-50',
                        'block px-4 sm:px-6 md:px-4 py-2 text-base md:text-sm font-normal border-l-4 md:border-l-0 rounded-r-md md:rounded-none'
                      ]"
                      to="/admin"
                      @click="() => { closeUserMenu(); closeMobileMenu(); }"
                  >
                    Admin Panel
                  </router-link>
                  <div class="hidden md:block border-t border-gray-200 my-1"></div>
                  <button
                      class="block w-full text-left px-4 sm:px-6 md:px-4 py-2 text-base md:text-sm font-normal text-gray-700 hover:text-gray-900 md:hover:bg-gray-100 hover:bg-gray-50 rounded-md md:rounded-none"
                      @click="performLogout"
                  >
                    Sign Out
                  </button>
                </div>
              </div>

              <!-- Guest (Desktop Only - Mobile handled in hamburger section) -->
              <a
                  v-else
                  class="hidden md:block text-sm font-medium text-gray-500 hover:text-gray-700 px-3 py-2"
                  href="/accounts/login/"
              >
                Sign In
              </a>
            </div>

          </div>
        </div>
      </div>

      <!-- Mobile Menu Backdrop -->
      <div
          v-if="mobileMenuOpen && userInfo"
          class="md:hidden fixed inset-x-0 top-16 bottom-0 bg-gray-600 bg-opacity-75 z-40"
          @click="closeMobileMenu"
      ></div>
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
import { ChevronDownIcon, Bars3Icon, XMarkIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'App',
  components: {
    Loader,
    ChevronDownIcon,
    Bars3Icon,
    XMarkIcon
  },
  data() {
    return {
      realtimeListenersAdded: false,
      userMenuOpen: false,
      mobileMenuOpen: false,
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
        // Close mobile menu on route change
        this.closeMobileMenu();

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
    toggleMobileMenu() {
      this.mobileMenuOpen = !this.mobileMenuOpen;
      // Close user menu when opening mobile menu
      if (this.mobileMenuOpen) {
        this.userMenuOpen = false;
        // Prevent body scroll when menu is open
        document.body.style.overflow = 'hidden';
      } else {
        // Restore body scroll when menu is closed
        document.body.style.overflow = '';
      }
    },
    closeMobileMenu() {
      this.mobileMenuOpen = false;
      // Restore body scroll when menu is closed
      document.body.style.overflow = '';
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
    // Restore body scroll in case menu was open
    document.body.style.overflow = '';
  }
};
</script>
