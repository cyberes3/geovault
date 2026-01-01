<template>
  <div class="space-y-6">
    <!-- CalTopo Integration Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-xl font-semibold text-gray-900">CalTopo Integration</h2>
        <button
          @click="showSetupModal = true"
          class="inline-flex items-center px-3 py-1.5 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
          title="How to set up CalTopo integration"
        >
          <svg class="w-4 h-4 mr-1.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          Setup Instructions
        </button>
      </div>

      <div class="space-y-6">
        <!-- Loading Spinner -->
        <div v-if="connectionStatus.checking" class="p-4">
          <Loader size="sm" layout="inline" message="Checking CalTopo connection status..." :showMessage="true" />
        </div>

        <!-- Connection Status -->
        <div v-else-if="connectionStatus.connected" class="p-4 bg-green-50 border border-green-200 rounded-md">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <svg class="w-5 h-5 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span class="text-sm font-medium text-green-800">Connected to CalTopo</span>
            </div>
            <button
              @click="disconnectCaltopo"
              class="text-sm text-red-600 hover:text-red-800"
            >
              Disconnect
            </button>
          </div>
        </div>

        <!-- Connection Form -->
        <form v-else-if="!connectionStatus.connected" @submit.prevent="handleConnect" class="space-y-4" autocomplete="off">
        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Account ID (6 characters)</label>
            <input
              v-model="connectForm.account_id"
              type="text"
              maxlength="6"
              required
              autocomplete="off"
              name="caltopo-account-id"
              :disabled="connecting"
              class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700 mb-1">Credential Code (12 characters)</label>
            <input
              v-model="connectForm.credential_id"
              type="text"
              maxlength="12"
              required
              autocomplete="off"
              name="caltopo-credential-id"
              :disabled="connecting"
              class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100"
            />
          </div>
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700 mb-1">Credential Key</label>
          <input
            v-model="connectForm.credential_key"
            type="text"
            required
            autocomplete="off"
            name="caltopo-credential-key"
            :disabled="connecting"
            class="w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500 disabled:bg-gray-100"
          />
        </div>
        <div v-if="connectMessage" :class="[
          'p-3 rounded-md text-sm',
          connectMessageType === 'success' ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'
        ]">
          {{ connectMessage }}
        </div>
        <button
          type="submit"
          :disabled="connecting"
          class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50"
        >
          <span v-if="connecting">Connecting...</span>
          <span v-else>Connect to CalTopo</span>
        </button>
        </form>

        <!-- API Status Box -->
        <div v-if="connectionStatus.connected || connectionStatus.checking" class="border-t border-gray-200 pt-6">
          <h3 class="text-lg font-semibold text-gray-900 mb-4">API Status</h3>
          <div class="space-y-3">
            <div
              v-for="(status, endpoint) in apiStatus"
              :key="endpoint"
              class="flex items-center justify-between p-3 border border-gray-200 rounded-md"
            >
              <div class="flex items-center gap-3">
                <!-- Spinner -->
                <Loader
                  v-if="status === 'loading'"
                  size="sm"
                  layout="inline"
                  :showMessage="false"
                />
                <!-- Success -->
                <svg
                  v-else-if="status === 'success'"
                  class="h-5 w-5 text-green-500"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
                </svg>
                <!-- Error -->
                <svg
                  v-else-if="status === 'error'"
                  class="h-5 w-5 text-red-500"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
                </svg>
                <!-- Not checked -->
                <div v-else class="h-5 w-5 border-2 border-gray-300 rounded"></div>
                <span class="text-sm font-medium text-gray-700">{{ endpoint }}</span>
              </div>
              <span
                v-if="status === 'error' && apiErrors[endpoint]"
                class="text-xs text-red-600"
              >
                {{ apiErrors[endpoint] }}
              </span>
            </div>
          </div>
        </div>

        <!-- Maps and Features Section -->
        <div v-if="connectionStatus.connected || connectionStatus.checking" class="border-t border-gray-200 pt-6">
          <h3 class="text-lg font-semibold text-gray-900 mb-4">Import from CalTopo</h3>
      
      <!-- Maps List -->
      <div class="mb-6">
        <label class="block text-sm font-medium text-gray-700 mb-2">Select a Map</label>
        <div v-if="loadingMaps && !connectionStatus.checking" class="py-8 border border-gray-200 rounded-md bg-gray-50">
          <Loader size="md" layout="centered" message="Loading maps..." />
        </div>
        <div v-else-if="loadingFeatures && selectedMapId" class="py-8 border border-gray-200 rounded-md">
          <Loader size="md" layout="centered" message="Loading features..." />
        </div>
        <select
          v-else
          v-model="selectedMapId"
          @change="handleMapSelect"
          :disabled="connectionStatus.checking"
          :class="[
            'w-full px-3 py-2 border rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500',
            connectionStatus.checking 
              ? 'border-gray-200 bg-gray-100 text-gray-400 cursor-not-allowed' 
              : 'border-gray-300'
          ]"
        >
          <option value="">{{ connectionStatus.checking ? 'Loading...' : '-- Select a map --' }}</option>
          <option v-for="map in maps" :key="map.id" :value="map.id">
            {{ map.title || map.id }}
          </option>
        </select>
      </div>

      <!-- Features List -->
      <div v-if="selectedMapId && features.length > 0" class="space-y-4">
        <div class="flex items-center justify-between">
          <h3 class="text-md font-medium text-gray-900">
            Features in {{ selectedMapTitle }} ({{ features.length }})
          </h3>
          <div class="relative">
            <button
              @click="handleImportMap"
              :disabled="importingMap || mapInQueue"
              :title="mapInQueue ? 'This map is already in the import queue. Please complete the import.' : ''"
              class="inline-flex items-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <span v-if="importingMap">Importing...</span>
              <span v-else-if="mapInQueue">Already in Queue</span>
              <span v-else>Import Entire Map</span>
            </button>
          </div>
        </div>

        <div class="border border-gray-200 rounded-md divide-y divide-gray-200 h-96 overflow-y-auto overflow-x-hidden">
          <div
            v-for="feature in features"
            :key="feature.id"
            class="p-4 hover:bg-gray-50 flex items-center justify-between min-w-0"
          >
            <div class="flex-1">
              <div class="text-sm font-medium text-gray-900">
                {{ feature.properties?.title || feature.properties?.name || `Feature ${feature.id}` }}
              </div>
              <div class="text-xs text-gray-500 mt-1">
                {{ feature.properties?.class || 'Unknown' }} • ID: {{ feature.id }}
              </div>
            </div>
            <button
              @click="handleImportFeature(feature)"
              :disabled="importingFeatures[feature.id] || mapInQueue"
              :title="mapInQueue ? 'This map is already in the import queue. Please complete the import.' : ''"
              class="ml-4 inline-flex items-center px-3 py-1.5 border border-transparent text-xs font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <span v-if="importingFeatures[feature.id]">Importing...</span>
              <span v-else-if="mapInQueue">Disabled</span>
              <span v-else-if="feature.is_imported">Re-import</span>
              <span v-else>Import</span>
            </button>
          </div>
        </div>
      </div>

      <!-- Error Message -->
      <div v-else-if="selectedMapId && featureLoadError" class="p-4 bg-red-50 border border-red-200 rounded-md">
        <div class="flex items-center gap-2">
          <svg class="h-5 w-5 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
          <span class="text-sm text-red-800">{{ featureLoadError }}</span>
        </div>
      </div>
      
      <!-- No Features Message -->
      <div v-else-if="selectedMapId && !loadingFeatures && features.length === 0 && !featureLoadError" class="text-sm text-gray-500">
        No features found in this map.
      </div>
        </div>
      </div>
    </div>

    <!-- Import Warnings/Status -->
    <div v-if="importWarnings.length > 0" class="bg-yellow-50 border border-yellow-200 rounded-md p-4">
      <h3 class="text-sm font-medium text-yellow-800 mb-2">Import Warnings</h3>
      <ul class="list-disc list-inside text-sm text-yellow-700 space-y-1">
        <li v-for="(warning, idx) in importWarnings" :key="idx">
          {{ warning }}
        </li>
      </ul>
    </div>

    <!-- Setup Instructions Modal -->
    <CaltopoSetupModal :show="showSetupModal" @close="showSetupModal = false" />
  </div>
</template>

<script>
import { APIHOST } from '@/config.js'
import { getCookie } from '@/assets/js/auth.js'
import { ChevronDownIcon, Bars3Icon, XMarkIcon } from '@heroicons/vue/24/outline'
import CaltopoSetupModal from './CaltopoSetupModal.vue'
import Loader from '@/components/parts/Loader.vue'

export default {
  name: 'ImportSettingsTab',
  components: {
    ChevronDownIcon,
    Bars3Icon,
    XMarkIcon,
    CaltopoSetupModal,
    Loader
  },
  data() {
    return {
      connectionStatus: {
        connected: false,
        checking: false
      },
      connectForm: {
        account_id: '',
        credential_id: '',
        credential_key: ''
      },
      connecting: false,
      connectMessage: '',
      connectMessageType: '',
      maps: [],
      loadingMaps: false,
      selectedMapId: '',
      selectedMapTitle: '',
      features: [],
      loadingFeatures: false,
      importingFeatures: {},
      importingMap: false,
      importWarnings: [],
      showSetupModal: false,
      mapInQueue: false,
      apiStatus: {
        'Status Check': 'idle',
        'List Maps': 'idle'
      },
      apiErrors: {},
      featureLoadError: null
    }
  },
  async created() {
    await this.checkConnectionStatus()
  },
          methods: {
            async checkConnectionStatus() {
              this.connectionStatus.checking = true
              this.apiStatus['Status Check'] = 'loading'
              this.apiErrors['Status Check'] = null
              try {
                const response = await fetch(`${APIHOST}/api/caltopo/status/`, {
                  method: 'GET',
                  headers: {
                    'X-CSRFToken': getCookie('csrftoken') || ''
                  },
                  credentials: 'include'
                })
        
        if (response.ok) {
          const data = await response.json()
          this.connectionStatus.connected = data.connected || false
          this.apiStatus['Status Check'] = 'success'
          // Set checking to false immediately so "Connected to CalTopo" box appears
          this.connectionStatus.checking = false
          // Load maps asynchronously without blocking
          if (this.connectionStatus.connected) {
            this.checkApiStatus()
          }
        } else {
          this.apiStatus['Status Check'] = 'error'
          this.apiErrors['Status Check'] = `HTTP ${response.status}`
          this.connectionStatus.checking = false
        }
      } catch (error) {
        console.error('Error checking CalTopo status:', error)
        this.apiStatus['Status Check'] = 'error'
        this.apiErrors['Status Check'] = error.message
        this.connectionStatus.checking = false
      }
    },
    async checkApiStatus() {
      // Check maps endpoint
      await this.loadMaps()
    },
            async handleConnect() {
              this.connecting = true
              this.connectMessage = ''
              
              try {
                const response = await fetch(`${APIHOST}/api/caltopo/connect/`, {
                  method: 'POST',
                  headers: {
                    'Content-Type': 'application/json',
                    'X-CSRFToken': getCookie('csrftoken') || ''
                  },
                  credentials: 'include',
                  body: JSON.stringify(this.connectForm)
                })
        
        const data = await response.json()
        
        if (response.ok) {
          this.connectMessage = 'Successfully connected to CalTopo!'
          this.connectMessageType = 'success'
          this.connectionStatus.connected = true
          await this.checkApiStatus()
          // Clear form
          this.connectForm = {
            account_id: '',
            credential_id: '',
            credential_key: ''
          }
        } else {
          // Check for CalTopo timeout error
          if (data.details && data.details.error_code === 'CALTOPO_TIMEOUT') {
            this.connectMessage = 'CalTopo request timed out. Please reload the page and try again.'
          } else {
            this.connectMessage = data.error || 'Failed to connect to CalTopo'
          }
          this.connectMessageType = 'error'
        }
      } catch (error) {
        console.error('Error connecting to CalTopo:', error)
        this.connectMessage = `Error: ${error.message}`
        this.connectMessageType = 'error'
      } finally {
        this.connecting = false
      }
    },
    async disconnectCaltopo() {
      if (!confirm('Are you sure you want to disconnect from CalTopo? This will remove your saved credentials.')) {
        return
      }
      
      try {
        const response = await fetch(`${APIHOST}/api/caltopo/disconnect/`, {
          method: 'POST',
          headers: {
            'X-CSRFToken': getCookie('csrftoken') || ''
          },
          credentials: 'include'
        })
        
        const data = await response.json()
        
        if (response.ok) {
          this.connectionStatus.connected = false
          this.selectedMapId = ''
          this.features = []
          this.maps = []
          this.connectMessage = 'Disconnected from CalTopo'
          this.connectMessageType = 'success'
        } else {
          this.connectMessage = data.error || 'Failed to disconnect from CalTopo'
          this.connectMessageType = 'error'
        }
      } catch (error) {
        console.error('Error disconnecting from CalTopo:', error)
        this.connectMessage = `Error: ${error.message}`
        this.connectMessageType = 'error'
      }
    },
    async loadMaps() {
      this.loadingMaps = true
      this.apiStatus['List Maps'] = 'loading'
      this.apiErrors['List Maps'] = null
      try {
        const response = await fetch(`${APIHOST}/api/caltopo/maps/`, {
          method: 'GET',
          headers: {
            'X-CSRFToken': getCookie('csrftoken') || ''
          },
          credentials: 'include'
        })
        
        if (response.ok) {
          const data = await response.json()
                  this.maps = data.maps || []
          this.apiStatus['List Maps'] = 'success'
        } else {
          const errorData = await response.json().catch(() => ({}))
          this.apiStatus['List Maps'] = 'error'
          // Check for CalTopo timeout error
          if (errorData.details && errorData.details.error_code === 'CALTOPO_TIMEOUT') {
            this.apiErrors['List Maps'] = 'CalTopo request timed out. Please reload the page and try again.'
          } else {
            this.apiErrors['List Maps'] = errorData.error || `HTTP ${response.status}`
          }
          console.error('Failed to load maps:', errorData)
        }
      } catch (error) {
        this.apiStatus['List Maps'] = 'error'
        this.apiErrors['List Maps'] = error.message
        console.error('Error loading maps:', error)
      } finally {
        this.loadingMaps = false
      }
    },
    async handleMapSelect() {
      if (!this.selectedMapId) {
        this.features = []
        this.mapInQueue = false
        this.featureLoadError = null
        return
      }
      
      // Find map title
      const map = this.maps.find(m => m.id === this.selectedMapId)
      this.selectedMapTitle = map?.title || this.selectedMapId
      
      await this.loadFeatures()
    },
    async loadFeatures() {
      this.loadingFeatures = true
      this.features = []
      this.mapInQueue = false
      this.featureLoadError = null
      
      try {
        const response = await fetch(`${APIHOST}/api/caltopo/maps/${this.selectedMapId}/features/`, {
          method: 'GET',
          headers: {
            'X-CSRFToken': getCookie('csrftoken') || ''
          },
          credentials: 'include'
        })
        
        if (response.ok) {
          const data = await response.json()
          this.features = data.features || []
          this.mapInQueue = data.is_in_queue || false
        } else {
          const errorData = await response.json().catch(() => ({}))
          // Check for CalTopo timeout error
          if (errorData.details && errorData.details.error_code === 'CALTOPO_TIMEOUT') {
            this.featureLoadError = 'CalTopo request timed out. Please reload the page and try again.'
          } else {
            this.featureLoadError = errorData.error || `HTTP ${response.status}`
          }
          this.mapInQueue = false
          console.error('Failed to load features:', errorData)
        }
      } catch (error) {
        this.featureLoadError = error.message
        this.mapInQueue = false
        console.error('Error loading features:', error)
      } finally {
        this.loadingFeatures = false
      }
    },
    async handleImportFeature(feature) {
      this.importingFeatures[feature.id] = true
      this.importWarnings = []
      
      try {
        const response = await fetch(`${APIHOST}/api/caltopo/import/feature/`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': getCookie('csrftoken') || ''
          },
          credentials: 'include',
          body: JSON.stringify({
            map_id: this.selectedMapId,
            feature_id: feature.id,
            feature_class: feature.properties?.class || 'Marker'
          })
        })
        
        const data = await response.json()
        
        if (response.ok) {
          // Update feature import status
          const featureIndex = this.features.findIndex(f => f.id === feature.id)
          if (featureIndex !== -1) {
            this.features[featureIndex].is_imported = true
          }
          
          if (data.warnings && data.warnings.length > 0) {
            this.importWarnings = data.warnings.map(w => {
              if (w.type === 'hash') {
                return `Hash duplicate: Feature with identical hash already exists`
              } else if (w.type === 'geometry') {
                return `Geometry duplicate: Feature with similar geometry already exists`
              }
              return w.message || 'Unknown warning'
            })
          }
          
          if (this.toastRef) {
            this.toastRef.show('Feature imported successfully', 'success')
          }
        } else {
          // Check for CalTopo timeout error
          let errorMsg = data.error || 'Failed to import feature'
          if (data.details && data.details.error_code === 'CALTOPO_TIMEOUT') {
            errorMsg = 'CalTopo request timed out. Please reload the page and try again.'
          }
          if (this.toastRef) {
            this.toastRef.show(errorMsg, 'error')
          }
        }
      } catch (error) {
        console.error('Error importing feature:', error)
        if (this.toastRef) {
          this.toastRef.show(`Error: ${error.message}`, 'error')
        }
      } finally {
        this.importingFeatures[feature.id] = false
      }
    },
    async handleImportMap() {
      if (this.mapInQueue) {
        return
      }
      
      if (!confirm(`Are you sure you want to import all ${this.features.length} features from map "${this.selectedMapTitle}"? This will delete any previously imported features from this map.`)) {
        return
      }
      
      this.importingMap = true
      this.importWarnings = []
      
      try {
        const response = await fetch(`${APIHOST}/api/caltopo/import/map/`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'X-CSRFToken': getCookie('csrftoken') || ''
          },
          credentials: 'include',
          body: JSON.stringify({
            map_id: this.selectedMapId
          })
        })
        
        const data = await response.json()
        
        if (response.ok) {
          // Update queue status after successful import
          this.mapInQueue = true
          if (this.toastRef) {
            this.toastRef.show(`Map import queued. Processing ${data.feature_count || 0} features.`, 'success')
          }
        } else {
          // Check for CalTopo timeout error
          let errorMsg = data.error || 'Failed to import map'
          if (data.details && data.details.error_code === 'CALTOPO_TIMEOUT') {
            errorMsg = 'CalTopo request timed out. Please reload the page and try again.'
          }
          if (this.toastRef) {
            this.toastRef.show(errorMsg, 'error')
          }
        }
      } catch (error) {
        console.error('Error importing map:', error)
        if (this.toastRef) {
          this.toastRef.show(`Error: ${error.message}`, 'error')
        }
      } finally {
        this.importingMap = false
      }
    }
  },
  props: {
    toastRef: {
      type: Object,
      default: null
    }
  }
}
</script>
