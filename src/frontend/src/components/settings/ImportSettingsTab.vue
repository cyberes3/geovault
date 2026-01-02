<template>
  <div class="space-y-6">
    <!-- CalTopo Integration Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between mb-6 gap-4">
        <h2 class="text-xl font-semibold text-gray-900">CalTopo Integration</h2>
        <button
          @click="showSetupModal = true"
          class="w-full sm:w-auto inline-flex items-center justify-center px-3 py-1.5 border border-transparent shadow-sm text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
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
          <div class="flex items-center justify-between">
            <Loader size="sm" layout="inline" message="Checking CalTopo connection status..." :showMessage="true" />
            <div class="h-[2.25rem]"></div>
          </div>
        </div>

        <!-- Connection Status: Connected -->
        <div v-else-if="connectionStatus.status === 'connected'" class="p-4 bg-green-50 border border-green-200 rounded-md">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <svg class="w-5 h-5 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span class="text-sm font-medium text-green-800">Connected to CalTopo</span>
            </div>
            <button
              @click="disconnectCaltopo"
              class="inline-flex items-center px-3 py-1.5 border border-red-300 text-sm font-medium rounded-md text-red-700 bg-white hover:bg-red-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500"
            >
              Disconnect
            </button>
          </div>
        </div>

        <!-- Connection Status: Invalid Credentials -->
        <div v-else-if="connectionStatus.status === 'invalid'" class="p-4 bg-yellow-50 border border-yellow-200 rounded-md">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <svg class="w-5 h-5 text-yellow-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
              </svg>
              <span class="text-sm font-medium text-yellow-800">Invalid CalTopo credentials</span>
            </div>
            <button
              @click="disconnectCaltopo"
              class="inline-flex items-center px-3 py-1.5 border border-red-300 text-sm font-medium rounded-md text-red-700 bg-white hover:bg-red-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-red-500"
            >
              Disconnect
            </button>
          </div>
          <p class="text-xs text-yellow-700 mt-2">Your stored credentials are no longer valid. Please reconnect with new credentials.</p>
        </div>

        <!-- Connection Status: Timeout -->
        <div v-else-if="connectionStatus.status === 'timeout'" class="p-4 bg-yellow-50 border border-yellow-200 rounded-md">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <svg class="w-5 h-5 text-yellow-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span class="text-sm font-medium text-yellow-800">CalTopo request timed out</span>
            </div>
            <button
              @click="checkConnectionStatus"
              :disabled="connectionStatus.checking"
              class="inline-flex items-center px-3 py-1.5 border border-yellow-300 text-sm font-medium rounded-md text-yellow-700 bg-white hover:bg-yellow-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-yellow-500 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <span v-if="connectionStatus.checking">Retrying...</span>
              <span v-else>Retry</span>
            </button>
          </div>
          <p class="text-xs text-yellow-700 mt-2">Unable to verify credentials. Please try again.</p>
        </div>

        <!-- Connection Form: Not Connected or Invalid/Timeout -->
        <form v-else-if="connectionStatus.status === 'not_connected' || connectionStatus.status === 'invalid' || connectionStatus.status === 'timeout'" @submit.prevent="handleConnect" class="space-y-4" autocomplete="off">
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
        <div v-if="connectionStatus.status === 'connected' || connectionStatus.checking" class="border-t border-gray-200 pt-6">
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
        <div v-if="connectionStatus.status === 'connected' || connectionStatus.checking" class="border-t border-gray-200 pt-6">
          <h3 class="text-lg font-semibold text-gray-900 mb-4">Import from CalTopo</h3>
      
      <!-- Maps List -->
      <div class="mb-6">
        <label class="block text-sm font-medium text-gray-700 mb-2">Select a Map</label>
        <div v-if="loadingFeatures && selectedMapId" class="py-8 border border-gray-200 rounded-md">
          <Loader size="md" layout="centered" message="Loading features..." />
        </div>
        <select
          v-else
          v-model="selectedMapId"
          @change="handleMapSelect"
          :disabled="connectionStatus.checking || apiStatus['List Maps'] === 'loading'"
          :class="[
            'w-full px-3 py-2 border rounded-md shadow-sm focus:outline-none focus:ring-blue-500 focus:border-blue-500',
            (connectionStatus.checking || apiStatus['List Maps'] === 'loading')
              ? 'border-gray-200 bg-gray-100 text-gray-400 cursor-not-allowed' 
              : 'border-gray-300'
          ]"
        >
          <option value="">{{ (connectionStatus.checking || apiStatus['List Maps'] === 'loading') ? 'Loading...' : '-- Select a map --' }}</option>
          <option v-for="map in maps" :key="map.id" :value="map.id">
            {{ map.title || map.id }}
          </option>
        </select>
      </div>

      <!-- Features List -->
      <div v-if="selectedMapId && features.length > 0" class="space-y-4">
        <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <h3 class="text-md font-medium text-gray-900">
            Features in {{ selectedMapTitle }} ({{ features.length }})
          </h3>
          <div class="relative w-full sm:w-auto flex flex-col sm:flex-row gap-2">
            <button
              v-if="!mapInQueue"
              @click="handleImportMap"
              :disabled="importingMap"
              class="w-full sm:w-auto inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <span v-if="importingMap">Importing...</span>
              <span v-else>Import Entire Map</span>
            </button>
            <router-link
              v-if="mapInQueue && importQueueId"
              :to="`/import/process/${importQueueId}`"
              class="w-full sm:w-auto inline-flex items-center justify-center px-4 py-2 border border-transparent text-sm font-medium rounded-md text-white bg-blue-600 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            >
              View in Queue
            </router-link>
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
              v-if="!feature.is_imported"
              @click="handleImportFeature(feature)"
              :disabled="importingFeatures[feature.id] || mapInQueue || importingMap || !feature.is_valid"
              :title="getFeatureButtonTooltip(feature)"
              class="ml-4 inline-flex items-center px-3 py-1.5 border border-transparent text-xs font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              <span v-if="importingFeatures[feature.id]">Importing...</span>
              <span v-else-if="!feature.is_valid">Unsupported</span>
              <span v-else>Import</span>
            </button>
            <button
              v-else
              @click="handleViewInMap(feature)"
              class="ml-4 inline-flex items-center px-3 py-1.5 border border-transparent text-xs font-medium rounded-md text-white bg-blue-500 hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            >
              View in Map
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
import { toast } from '@/utils/toast'

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
        checking: false,
        status: null // 'not_connected', 'invalid', 'timeout', or 'connected'
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
      importQueueId: null,
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
          this.connectionStatus.status = data.status || (data.connected ? 'connected' : 'not_connected')
          this.apiStatus['Status Check'] = 'success'
          // Set checking to false immediately so status box appears
          this.connectionStatus.checking = false
          // Load maps asynchronously without blocking if connected
          if (this.connectionStatus.status === 'connected') {
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
          this.connectionStatus.status = 'connected'
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
            this.connectMessage = 'CalTopo request timed out.'
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
          this.connectionStatus.status = 'not_connected'
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
            this.apiErrors['List Maps'] = 'CalTopo request timed out.'
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
        this.importQueueId = null
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
      this.importQueueId = null
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
          // Ensure is_valid defaults to true for backwards compatibility
          this.features.forEach(feature => {
            if (feature.is_valid === undefined) {
              feature.is_valid = true
            }
          })
          this.mapInQueue = data.is_in_queue || false
          this.importQueueId = data.import_queue_id || null
        } else {
          const errorData = await response.json().catch(() => ({}))
          // Check for CalTopo timeout error
          if (errorData.details && errorData.details.error_code === 'CALTOPO_TIMEOUT') {
            this.featureLoadError = 'CalTopo request timed out.'
          } else {
            this.featureLoadError = errorData.error || `HTTP ${response.status}`
          }
          this.mapInQueue = false
          this.importQueueId = null
          console.error('Failed to load features:', errorData)
        }
      } catch (error) {
        this.featureLoadError = error.message
        this.mapInQueue = false
        this.importQueueId = null
        console.error('Error loading features:', error)
      } finally {
        this.loadingFeatures = false
      }
    },
    async handleImportFeature(feature) {
      // Block invalid features from being imported
      if (feature.is_valid === false) {
        const featureClass = feature.properties?.class || 'Unknown'
        toast.error(`Feature type '${featureClass}' is not supported for import`)
        return
      }
      
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
          // Update feature import status and store database_id
          const featureIndex = this.features.findIndex(f => f.id === feature.id)
          if (featureIndex !== -1) {
            this.features[featureIndex].is_imported = true
            // Store database_id for navigation
            if (data.feature && data.feature.properties && data.feature.properties.database_id) {
              this.features[featureIndex].database_id = data.feature.properties.database_id
            }
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
          
          toast.success('Feature imported successfully')
        } else {
          // Handle error response
          let errorMsg = 'Failed to import feature'
          if (data && data.error) {
            errorMsg = data.error
          } else if (data && data.details && data.details.error_code === 'CALTOPO_TIMEOUT') {
            errorMsg = 'CalTopo request timed out.'
          } else if (response.statusText) {
            errorMsg = `Failed to import feature: ${response.statusText}`
          }
          
          toast.error(errorMsg)
        }
      } catch (error) {
        console.error('Error importing feature:', error)
        const errorMsg = error.message || 'An unexpected error occurred while importing the feature'
        toast.error(errorMsg)
      } finally {
        this.importingFeatures[feature.id] = false
      }
    },
    handleViewInMap(feature) {
      // Navigate to map with featureId query parameter to zoom to the feature
      const featureId = feature.database_id
      if (featureId) {
        this.$router.push({
          path: '/map',
          query: { featureId: featureId }
        })
      } else {
        console.error('Feature missing database_id:', feature)
        toast.error('Unable to view feature: missing ID')
      }
    },
    getFeatureButtonTooltip(feature) {
      if (feature.is_valid === false) {
        const featureClass = feature.properties?.class || 'Unknown'
        return `Feature type '${featureClass}' is not supported for import`
      }
      if (this.mapInQueue || this.importingMap) {
        return 'This map is being imported. Please wait for the import to complete.'
      }
      return ''
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
      
      // Reset all feature import statuses immediately (existing features will be deleted by backend)
      this.features.forEach(feature => {
        feature.is_imported = false
        delete feature.database_id
      })
      
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
          this.importQueueId = data.import_queue_id || null
          
          toast.success(`Map import queued. Processing ${data.feature_count || 0} features.`)
        } else {
          // Handle error response
          let errorMsg = 'Failed to import map'
          if (data && data.error) {
            errorMsg = data.error
          } else if (data && data.details && data.details.error_code === 'CALTOPO_TIMEOUT') {
            errorMsg = 'CalTopo request timed out.'
          } else if (response.statusText) {
            errorMsg = `Failed to import map: ${response.statusText}`
          }
          
          toast.error(errorMsg)
        }
      } catch (error) {
        console.error('Error importing map:', error)
        const errorMsg = error.message || 'An unexpected error occurred while importing the map'
        toast.error(errorMsg)
      } finally {
        this.importingMap = false
      }
    }
  },
}
</script>
