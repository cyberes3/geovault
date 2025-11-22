<template>
  <div class="space-y-8" v-if="userInfo">
    <!-- Hero Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-8">
      <div class="text-center">
        <h1 class="text-4xl font-bold text-gray-900 mb-4">Welcome to the GeoVault</h1>

        <div class="geo-anim-container my-8 mx-auto" aria-hidden="true">
          <div class="geo-grid"></div>
          <div class="geo-rings">
            <div class="geo-ring ring-1"></div>
            <div class="geo-ring ring-2"></div>
            <div class="geo-ring ring-3"></div>
          </div>
          <div class="geo-pulse"></div>
        </div>

        <p class="text-xl text-gray-600 mb-8 max-w-2xl mx-auto">
          Store and view your geospatial data with ease. Upload KML/KMZ/GPX files, process them, and organize your geographic features.
        </p>
        <div class="flex flex-col sm:flex-row gap-4 justify-center">
          <router-link
            to="/import"
            class="inline-flex items-center px-6 py-3 border border-transparent text-base font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors duration-200"
          >
            <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
            </svg>
            Start Importing
          </router-link>
          <router-link
            to="/map"
            class="inline-flex items-center px-6 py-3 border border-gray-300 text-base font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors duration-200"
          >
            <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 20l-5.447-2.724A1 1 0 013 16.382V5.618a1 1 0 011.447-.894L9 7m0 13l6-3m-6 3V7m6 10l4.553 2.276A1 1 0 0021 18.382V7.618a1 1 0 00-.553-.894L15 4m0 13V4m0 0L9 7"></path>
            </svg>
            View Map
          </router-link>
        </div>
      </div>
    </div>

    <!-- Feature Count Display -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 text-center">
      <p class="text-sm text-gray-600">
        You have <span class="font-semibold text-gray-900">{{ userInfo.featureCount }}</span> features in your vault.
      </p>
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
.geo-anim-container {
  position: relative;
  width: 100%;
  max-width: 600px;
  height: 200px;
  overflow: hidden;
  background: linear-gradient(to bottom, #ffffff, #f0f9ff);
  border-radius: 0.5rem;
  perspective: 1000px;
  /* Fade out the bottom slightly to blend */
  mask-image: linear-gradient(to bottom, black 85%, transparent 100%);
  -webkit-mask-image: linear-gradient(to bottom, black 85%, transparent 100%);
}

.geo-grid {
  position: absolute;
  top: 0;
  left: -50%;
  width: 200%;
  height: 200%;
  background-image:
    linear-gradient(rgba(20, 61, 141, 0.1) 1px, transparent 1px),
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
  0% { background-position: 0 0; }
  100% { background-position: 0 40px; }
}

@keyframes rotateRight {
  from { transform: translate(-50%, -50%) rotate(0deg); }
  to { transform: translate(-50%, -50%) rotate(360deg); }
}

@keyframes rotateLeft {
  from { transform: translate(-50%, -50%) rotate(0deg); }
  to { transform: translate(-50%, -50%) rotate(-360deg); }
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
