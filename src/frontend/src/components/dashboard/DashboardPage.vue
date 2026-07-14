<template>
  <div class="space-y-8" v-if="userInfo">
    <!-- Hero Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-8">
      <div class="text-center">
        <h1 class="text-4xl font-bold text-gray-900 mb-4">Welcome to the GeoVault</h1>

        <div class="geo-anim-wrapper my-8 mx-auto">
          <div class="geo-anim-container" aria-hidden="true">
            <div class="geo-grid"></div>
            <div class="geo-rings">
              <div class="geo-ring ring-1"></div>
              <div class="geo-ring ring-2"></div>
              <div class="geo-ring ring-3"></div>
            </div>
            <div class="geo-pulse"></div>
          </div>
          <div class="geo-border-blur geo-border-left"></div>
          <div class="geo-border-blur geo-border-right"></div>
        </div>

        <p class="text-xl text-gray-600 mb-8 max-w-2xl mx-auto">
          Store and view your geospatial data with ease. Upload KML/KMZ/GPX files, process them, and organize your
          geographic features.
        </p>
        <div class="flex flex-col sm:flex-row gap-4 justify-center">
          <BaseButton
              tag="router-link"
              to="/import"
              variant="primary"
              color="blue"
              size="lg"
          >
            <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
            </svg>
            Start Importing
          </BaseButton>
          <BaseButton
              tag="router-link"
              to="/map"
              variant="white"
              size="lg"
          >
            <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                    d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
            </svg>
            View Map
          </BaseButton>
        </div>
      </div>
    </div>

    <!-- Feature Count and Storage Display -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4">
      <div class="text-center">
        <p class="text-sm text-gray-600">
          You have <span class="font-semibold text-gray-900">{{ userInfo.featureCount }}</span> features in your
          vault<span v-if="storageLoading"> and calculating storage usage...</span><span
            v-else-if="storageBytes !== null"> and using <span
            class="font-semibold text-gray-900">{{ formatStorage(storageBytes) }}</span> of storage.</span><span
            v-else-if="storageError">.</span>
        </p>
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
            <div
                class="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center group-hover:bg-blue-200 transition-colors duration-200">
              <svg class="w-6 h-6 text-blue-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
              </svg>
            </div>
          </div>
          <div class="ml-4">
            <h3 class="text-sm font-medium text-gray-900 group-hover:text-blue-500">Import Data</h3>
            <p class="text-sm text-gray-500">Upload and process KML/KMZ files</p>
          </div>
        </router-link>

        <router-link
            to="/import/upload"
            class="flex items-center p-4 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors duration-200 group"
        >
          <div class="flex-shrink-0">
            <div
                class="w-10 h-10 bg-green-100 rounded-lg flex items-center justify-center group-hover:bg-green-200 transition-colors duration-200">
              <svg class="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M12 6v6m0 0v6m0-6h6m-6 0H6"></path>
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
            <div
                class="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center group-hover:bg-purple-200 transition-colors duration-200">
              <svg class="w-6 h-6 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                      d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
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

    <!-- Android Apps -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <h2 class="text-lg font-semibold text-gray-900 mb-4">Android Apps</h2>
      <p class="text-sm text-gray-500 mb-4">
        Install the companion apps (Android only) to upload files, manage your places, and track your location on the go.
      </p>
      <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
        <a
            :href="uploaderApkUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="flex items-center p-4 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors duration-200 group"
        >
          <div class="flex-shrink-0">
            <div
                class="w-10 h-10 bg-purple-100 rounded-lg flex items-center justify-center group-hover:bg-purple-200 transition-colors duration-200">
              <DevicePhoneMobileIcon class="w-6 h-6 text-purple-600"/>
            </div>
          </div>
          <div class="ml-4 flex-1 min-w-0">
            <h3 class="text-sm font-medium text-gray-900 group-hover:text-purple-600">Uploader</h3>
            <p class="text-sm text-gray-500">Upload KML/KMZ/GPX files</p>
          </div>
          <ArrowDownTrayIcon class="w-5 h-5 text-gray-400 flex-shrink-0"/>
        </a>
        <a
            :href="placesApkUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="flex items-center p-4 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors duration-200 group"
        >
          <div class="flex-shrink-0">
            <div
                class="w-10 h-10 bg-yellow-100 rounded-lg flex items-center justify-center group-hover:bg-yellow-200 transition-colors duration-200">
              <MapPinIcon class="w-6 h-6 text-yellow-600"/>
            </div>
          </div>
          <div class="ml-4 flex-1 min-w-0">
            <h3 class="text-sm font-medium text-gray-900 group-hover:text-yellow-600">Places</h3>
            <p class="text-sm text-gray-500">Manage and view your places</p>
          </div>
          <ArrowDownTrayIcon class="w-5 h-5 text-gray-400 flex-shrink-0"/>
        </a>
        <a
            :href="trackerApkUrl"
            target="_blank"
            rel="noopener noreferrer"
            class="flex items-center p-4 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors duration-200 group"
        >
          <div class="flex-shrink-0">
            <div
                class="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center group-hover:bg-blue-200 transition-colors duration-200">
              <SignalIcon class="w-6 h-6 text-blue-600"/>
            </div>
          </div>
          <div class="ml-4 flex-1 min-w-0">
            <h3 class="text-sm font-medium text-gray-900 group-hover:text-blue-600">Tracker</h3>
            <p class="text-sm text-gray-500">Live location sharing</p>
          </div>
          <ArrowDownTrayIcon class="w-5 h-5 text-gray-400 flex-shrink-0"/>
        </a>

        <!-- PWA Install Button -->
        <button
            v-if="canInstallPWA"
            @click="installPwa"
            class="flex items-center p-4 border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors duration-200 group text-left w-full"
        >
          <div class="flex-shrink-0">
            <div
                class="w-10 h-10 bg-blue-100 rounded-lg flex items-center justify-center group-hover:bg-blue-200 transition-colors duration-200">
              <ArrowDownOnSquareIcon class="w-6 h-6 text-blue-600"/>
            </div>
          </div>
          <div class="ml-4 flex-1 min-w-0">
            <h3 class="text-sm font-medium text-gray-900 group-hover:text-blue-600">Install Web App</h3>
            <p class="text-sm text-gray-500">Run GeoVault as a desktop/mobile app</p>
          </div>
          <ArrowDownTrayIcon class="w-5 h-5 text-gray-400 flex-shrink-0"/>
        </button>

        <!-- Compiled PWA APK Button (Extension) – only on mobile/tablet, not desktop -->
        <a
            v-if="pwaMintEnabled && !isDesktop"
            href="/api/extensions/pwa-mint/download/"
            target="_blank"
            rel="noopener noreferrer"
            class="flex items-center px-4 py-2 border border-gray-200 rounded hover:bg-gray-100 transition-colors duration-200"
        >
          <div class="flex-1 min-w-0">
            <h3 class="text-xs font-medium text-gray-500">Compiled Webview APK</h3>
            <p class="text-[10px] text-gray-400">Self-hosted TWA build</p>
          </div>
        </a>
      </div>
      <a
          :href="releasesPageUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-flex items-center mt-4 text-sm text-gray-500 hover:text-gray-700"
      >
        View all releases
        <ArrowTopRightOnSquareIcon class="w-4 h-4 ml-1"/>
      </a>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue'
import { getStorageUsage } from "@/api/services/userApi";
import { getAppReleases, listExtensions, type ExtensionMetadata } from "@/api/services/extensionsApi";
import BaseButton from "../parts/BaseButton.vue";
import type { UserInfo } from "@/assets/js/types/store-types";
import {
  ArrowDownTrayIcon,
  ArrowTopRightOnSquareIcon,
  DevicePhoneMobileIcon,
  MapPinIcon,
  SignalIcon,
  ArrowDownOnSquareIcon,
} from "@heroicons/vue/24/outline";

interface StorageUsageResponse {
  by_type: Record<string, number>;
  total_storage_bytes: number;
}

interface AppReleasesResponse {
  uploader_url: string | null;
  places_url: string | null;
  tracker_url: string | null;
  releases_page_url: string;
}

/** Chrome's `beforeinstallprompt` event, captured by `extensionsRuntime` and replayed on demand. */
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
}

/** Narrow view of root getters this component reads by namespaced key. */
interface RootGetters {
  'auth/userInfo': UserInfo | null;
  'extensionsRuntime/deferredPrompt': BeforeInstallPromptEvent | null;
}

export default defineComponent({
  name: 'DashboardPage',
  components: {
    BaseButton,
    DevicePhoneMobileIcon,
    ArrowDownTrayIcon,
    ArrowTopRightOnSquareIcon,
    MapPinIcon,
    SignalIcon,
    ArrowDownOnSquareIcon,
  },
  data() {
    return {
      storageBytes: null as number | null,
      storageLoading: false,
      storageError: false,
      appReleases: null as AppReleasesResponse | null,
      extensions: [] as ExtensionMetadata[],
    };
  },
  computed: {
    userInfo(): UserInfo | null {
      return (this.$store.getters as RootGetters)['auth/userInfo'];
    },
    deferredPrompt(): BeforeInstallPromptEvent | null {
      return (this.$store.getters as RootGetters)['extensionsRuntime/deferredPrompt'];
    },
    uploaderApkUrl(): string | undefined {
      return this.appReleases?.uploader_url ? '/api/apps/download/uploader/' : this.releasesPageUrl;
    },
    placesApkUrl(): string | undefined {
      return this.appReleases?.places_url ? '/api/apps/download/places/' : this.releasesPageUrl;
    },
    trackerApkUrl(): string | undefined {
      return this.appReleases?.tracker_url ? '/api/apps/download/tracker/' : this.releasesPageUrl;
    },
    pwaMintEnabled(): boolean {
      return this.extensions.some(ext => ext.name === 'pwa_mint');
    },
    isDesktop(): boolean {
      return !/Mobile|Android/i.test(navigator.userAgent);
    },
    canInstallPWA(): boolean {
      return !!this.deferredPrompt;
    },
    releasesPageUrl(): string | undefined {
      return this.appReleases?.releases_page_url;
    },
  },
  methods: {
    formatStorage(bytes: number | null): string {
      if (bytes === null) {
        return '0 B'
      }

      const kb = 1024
      const mb = kb * 1024
      const gb = mb * 1024

      if (bytes >= gb) {
        return (bytes / gb).toFixed(2) + ' GB'
      } else if (bytes >= mb) {
        return (bytes / mb).toFixed(2) + ' MB'
      } else if (bytes >= kb) {
        return (bytes / kb).toFixed(2) + ' KB'
      } else {
        return bytes + ' B'
      }
    },
    async fetchStorageUsage(): Promise<void> {
      this.storageLoading = true
      this.storageError = false

      // Create AbortController for timeout handling
      const controller = new AbortController()
      const timeoutId = setTimeout(() => { controller.abort() }, 10000) // 10 second timeout

      try {
        const data = (await getStorageUsage(controller.signal)) as StorageUsageResponse

        clearTimeout(timeoutId)

        this.storageBytes = data.total_storage_bytes
        this.storageError = false
      } catch (error) {
        clearTimeout(timeoutId)

        // Covers both the abort-on-timeout case and any other request failure;
        // the UI treats them identically, so only the log message differs in detail.
        this.storageError = true
        console.error('Failed to fetch storage usage:', error)
        this.storageBytes = null
      } finally {
        this.storageLoading = false
      }
    },
    async fetchAppReleases(): Promise<void> {
      try {
        this.appReleases = (await getAppReleases()) as AppReleasesResponse;
      } catch {
        // Keep appReleases null; computed URLs fall back to releases page
      }
    },
    async fetchExtensions(): Promise<void> {
      try {
        this.extensions = await listExtensions();
      } catch {
        this.extensions = [];
      }
    },
    async installPwa(): Promise<void> {
      const promptEvent = this.deferredPrompt;
      if (!promptEvent) return;

      // Show the install prompt
      void promptEvent.prompt();

      // Wait for the user to respond to the prompt
      const { outcome } = await promptEvent.userChoice;
      console.log(`PWA: User response to install prompt: ${outcome}`);

      // We've used the prompt, and can't use it again, clear it from state
      void this.$store.dispatch("extensionsRuntime/setDeferredPrompt", null);
    },
  },
  async mounted() {
    await this.fetchStorageUsage();
    await this.fetchAppReleases();
    await this.fetchExtensions();
  },
})
</script>

<style scoped>
.geo-anim-wrapper {
  position: relative;
  width: 100%;
  max-width: 600px;
  height: 200px;
}

.geo-anim-container {
  position: relative;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background: linear-gradient(to bottom, #ffffff, #f0f9ff);
  border-radius: 0.5rem;
  perspective: 1000px;
}

.geo-anim-wrapper::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 30px;
  pointer-events: none;
  z-index: 10;
  background: linear-gradient(to bottom, rgba(255, 255, 255, 1), rgba(255, 255, 255, 0));
  filter: blur(10px);
  -webkit-filter: blur(10px);
}

.geo-anim-wrapper::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 30px;
  pointer-events: none;
  z-index: 10;
  background: linear-gradient(to top, rgba(240, 249, 255, 1), rgba(240, 249, 255, 0));
  filter: blur(10px);
  -webkit-filter: blur(10px);
}

.geo-border-blur {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 30px;
  pointer-events: none;
  z-index: 10;
  filter: blur(10px);
  -webkit-filter: blur(10px);
}

.geo-border-left {
  left: 0;
  background: linear-gradient(to right, rgba(255, 255, 255, 1), rgba(255, 255, 255, 0));
}

.geo-border-right {
  right: 0;
  background: linear-gradient(to left, rgba(240, 249, 255, 1), rgba(240, 249, 255, 0));
}

.geo-grid {
  position: absolute;
  top: 0;
  left: -50%;
  width: 200%;
  height: 200%;
  background-image: linear-gradient(rgba(20, 61, 141, 0.1) 1px, transparent 1px),
  linear-gradient(90deg, rgba(20, 61, 141, 0.1) 1px, transparent 1px);
  background-size: 40px 40px;
  transform: rotateX(60deg);
  transform-origin: center top;
  animation: gridMove 2s linear infinite;
}

.geo-rings {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 160px;
  height: 160px;
  /* Add a slight tilt to the rings for 3D effect */
  transform-style: preserve-3d;
}

.geo-ring {
  position: absolute;
  top: 50%;
  left: 50%;
  border-radius: 50%;
  border: 2px dashed #143d8d;
  transform: translate(-50%, -50%);
  opacity: 0.6;
  box-shadow: 0 0 10px rgba(20, 61, 141, 0.1);
}

.ring-1 {
  width: 100%;
  height: 100%;
  animation: rotateRight 10s linear infinite;
}

.ring-2 {
  width: 70%;
  height: 70%;
  border-style: solid;
  border-width: 1px;
  border-top-color: transparent;
  border-bottom-color: transparent;
  animation: rotateLeft 6s linear infinite;
}

.ring-3 {
  width: 40%;
  height: 40%;
  border-style: dotted;
  animation: rotateRight 4s linear infinite;
}

.geo-pulse {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  width: 12px;
  height: 12px;
  background: #143d8d;
  border-radius: 50%;
  box-shadow: 0 0 0 0 rgba(20, 61, 141, 0.7);
  animation: pulse 2s infinite;
}

@keyframes gridMove {
  0% {
    background-position: 0 0;
  }
  100% {
    background-position: 0 40px;
  }
}

@keyframes rotateRight {
  from {
    transform: translate(-50%, -50%) rotate(0deg);
  }
  to {
    transform: translate(-50%, -50%) rotate(360deg);
  }
}

@keyframes rotateLeft {
  from {
    transform: translate(-50%, -50%) rotate(0deg);
  }
  to {
    transform: translate(-50%, -50%) rotate(-360deg);
  }
}

@keyframes pulse {
  0% {
    transform: translate(-50%, -50%) scale(0.95);
    box-shadow: 0 0 0 0 rgba(20, 61, 141, 0.7);
  }
  70% {
    transform: translate(-50%, -50%) scale(1);
    box-shadow: 0 0 0 20px rgba(20, 61, 141, 0);
  }
  100% {
    transform: translate(-50%, -50%) scale(0.95);
    box-shadow: 0 0 0 0 rgba(20, 61, 141, 0);
  }
}
</style>
