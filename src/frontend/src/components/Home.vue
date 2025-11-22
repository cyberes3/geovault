<template>
  <div class="space-y-8">
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
          Manage and import your geospatial data with ease. Upload KML/KMZ files, process them, and organize your geographic features.
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
            to="/dashboard" 
            class="inline-flex items-center px-6 py-3 border border-gray-300 text-base font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 transition-colors duration-200"
          >
            <svg class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"></path>
            </svg>
            View Dashboard
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

    <!-- Features Section -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
      <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
        <div class="flex items-center mb-4">
          <div class="flex-shrink-0">
            <div class="w-8 h-8 bg-blue-100 rounded-lg flex items-center justify-center">
              <svg class="w-5 h-5 text-blue-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12"></path>
              </svg>
            </div>
          </div>
          <h3 class="ml-3 text-lg font-medium text-gray-900">Easy Import</h3>
        </div>
        <p class="text-gray-600">
          Upload KML and KMZ files with a simple drag-and-drop interface. Our system handles the processing automatically.
        </p>
      </div>

      <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
        <div class="flex items-center mb-4">
          <div class="flex-shrink-0">
            <div class="w-8 h-8 bg-green-100 rounded-lg flex items-center justify-center">
              <svg class="w-5 h-5 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path>
              </svg>
            </div>
          </div>
          <h3 class="ml-3 text-lg font-medium text-gray-900">Data Validation</h3>
        </div>
        <p class="text-gray-600">
          Built-in validation ensures your data is properly formatted and prevents duplicate imports automatically.
        </p>
      </div>

      <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
        <div class="flex items-center mb-4">
          <div class="flex-shrink-0">
            <div class="w-8 h-8 bg-purple-100 rounded-lg flex items-center justify-center">
              <svg class="w-5 h-5 text-purple-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z"></path>
              </svg>
            </div>
          </div>
          <h3 class="ml-3 text-lg font-medium text-gray-900">Fast Processing</h3>
        </div>
        <p class="text-gray-600">
          Quick processing and real-time updates keep you informed about the status of your imports.
        </p>
      </div>
    </div>
  </div>
</template>


<script>
import {authMixin} from "@/assets/js/authMixin.js";

export default {
  components: {},
  mixins: [authMixin],
  data() {
    return {}
  },
  methods: {},
  async created() {
    // Component initialization
  },
  // async mounted() {
  // },
  // beforeRouteEnter(to, from, next) {
  //   next(async vm => {
  //   })
  // },
  watch: {},
};
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
