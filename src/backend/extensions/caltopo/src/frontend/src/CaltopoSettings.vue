<template>
  <div class="space-y-6">
    <!-- CalTopo Integration Section -->
    <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between mb-6 gap-4">
        <h2 class="text-xl font-semibold text-gray-900">CalTopo Integration</h2>
        <BaseButton
            @click="showSetupModal = true"
            class="w-full sm:w-auto"
            variant="primary"
            color="blue"
            size="sm"
            title="How to set up CalTopo integration"
        >
          <InformationCircleIcon class="w-4 h-4 mr-1.5"/>
          Setup Instructions
        </BaseButton>
      </div>

      <div class="space-y-6">
        <!-- Loading Spinner -->
        <div v-if="connectionStatus.checking" class="p-4">
          <div class="flex items-center justify-between">
            <Loader size="sm" layout="inline" message="Checking CalTopo connection status..." :showMessage="true"/>
            <div class="h-[2.25rem]"></div>
          </div>
        </div>

        <!-- Connection Status: Connected -->
        <div v-else-if="connectionStatus.status === 'connected'"
             class="p-4 bg-green-50 border border-green-200 rounded-md">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <CheckCircleIcon class="w-5 h-5 text-green-600"/>
              <span class="text-sm font-medium text-green-800">Connected to CalTopo</span>
            </div>
            <BaseButton
                @click="disconnectCaltopo"
                variant="white"
                size="sm"
                class="border-red-300 text-red-700 hover:bg-red-50 focus:ring-red-500"
            >
              Disconnect
            </BaseButton>
          </div>
        </div>

        <!-- Connection Status: Invalid Credentials -->
        <div v-else-if="connectionStatus.status === 'invalid'"
             class="p-4 bg-yellow-50 border border-yellow-200 rounded-md">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <ExclamationTriangleIcon class="w-5 h-5 text-yellow-600"/>
              <span class="text-sm font-medium text-yellow-800">Invalid CalTopo credentials</span>
            </div>
            <BaseButton
                @click="disconnectCaltopo"
                variant="white"
                size="sm"
                class="border-red-300 text-red-700 hover:bg-red-50 focus:ring-red-500"
            >
              Disconnect
            </BaseButton>
          </div>
          <p class="text-xs text-yellow-700 mt-2">Your stored credentials are no longer valid. Please reconnect with new
            credentials.</p>
        </div>

        <!-- Connection Status: Timeout -->
        <div v-else-if="connectionStatus.status === 'timeout'"
             class="p-4 bg-yellow-50 border border-yellow-200 rounded-md">
          <div class="flex items-center justify-between">
            <div class="flex items-center gap-2">
              <ClockIcon class="w-5 h-5 text-yellow-600"/>
              <span class="text-sm font-medium text-yellow-800">CalTopo request timed out</span>
            </div>
            <BaseButton
                @click="checkConnectionStatus"
                :disabled="connectionStatus.checking"
                variant="white"
                size="sm"
                class="border-yellow-300 text-yellow-700 hover:bg-yellow-50 focus:ring-yellow-500"
            >
              <span v-if="connectionStatus.checking">Retrying...</span>
              <span v-else>Retry</span>
            </BaseButton>
          </div>
          <p class="text-xs text-yellow-700 mt-2">Unable to verify credentials. Please try again.</p>
        </div>

        <!-- Connection Form: Not Connected or Invalid/Timeout -->
        <form
            v-else-if="connectionStatus.status === 'not_connected' || connectionStatus.status === 'invalid' || connectionStatus.status === 'timeout'"
            @submit.prevent="handleConnect" class="space-y-4" autocomplete="off">
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
          <BaseButton
              type="submit"
              :disabled="connecting"
              variant="primary"
              color="blue"
              size="sm"
          >
            <span v-if="connecting">Connecting...</span>
            <span v-else>Connect to CalTopo</span>
          </BaseButton>
        </form>

        <!-- Maps and Features Section -->
        <div v-if="connectionStatus.status === 'connected' || connectionStatus.checking"
             class="border-t border-gray-200 pt-6">
          <h3 class="text-lg font-semibold text-gray-900 mb-4">Import from CalTopo</h3>

          <!-- Maps List -->
          <div class="mb-6">
            <label class="block text-sm font-medium text-gray-700 mb-2">Select a Map</label>
            <div v-if="loadingFeatures && selectedMapId" class="py-8 border border-gray-200 rounded-md">
              <Loader size="md" layout="centered" message="Loading features..."/>
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
            <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
              <h3 class="text-md font-medium text-gray-900">
                Features in {{ selectedMapTitle }} ({{ features.length }})
              </h3>
              <div class="relative w-full sm:w-auto flex flex-col sm:flex-row gap-2">
                <BaseButton
                    v-if="!mapInQueue"
                    @click="handleImportMap"
                    :disabled="importingMap"
                    class="w-full sm:w-auto"
                    variant="primary"
                    color="blue"
                    size="md"
                >
                  <span v-if="importingMap">Importing...</span>
                  <span v-else>Import Entire Map</span>
                </BaseButton>
                <BaseButton
                    v-if="mapInQueue && importQueueId && queueStatus !== 'done'"
                    tag="router-link"
                    :to="`/import/process/${importQueueId}`"
                    class="w-full sm:w-auto"
                    variant="primary"
                    color="blue"
                    size="md"
                >
                  View in Queue
                </BaseButton>
                <BaseButton
                    v-if="queueStatus === 'done' && selectedMapId"
                    tag="router-link"
                    :to="getMapViewUrl(selectedMapId)"
                    class="w-full sm:w-auto"
                    variant="primary"
                    color="blue"
                    size="md"
                >
                  View on Map
                </BaseButton>
              </div>
            </div>

            <div
                class="border border-gray-200 rounded-md divide-y divide-gray-200 h-96 overflow-y-auto overflow-x-hidden">
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
                <BaseButton
                    v-if="!feature.is_imported"
                    @click="handleImportFeature(feature)"
                    :disabled="importingFeatures[feature.id] || mapInQueue || importingMap || !feature.is_valid"
                    :title="getFeatureButtonTooltip(feature)"
                    class="ml-4"
                    variant="primary"
                    color="blue"
                    size="xs"
                >
                  <span v-if="importingFeatures[feature.id]">Importing...</span>
                  <span v-else-if="!feature.is_valid">Unsupported</span>
                  <span v-else>Import</span>
                </BaseButton>
                <BaseButton
                    v-else
                    @click="handleViewInMap(feature)"
                    class="ml-4"
                    variant="primary"
                    color="blue"
                    size="xs"
                >
                  View on Map
                </BaseButton>
              </div>
            </div>
          </div>

          <!-- Error Message -->
          <div v-else-if="selectedMapId && featureLoadError" class="p-4 bg-red-50 border border-red-200 rounded-md">
            <div class="flex items-center gap-2">
              <XMarkIcon class="h-5 w-5 text-red-600"/>
              <span class="text-sm text-red-800">{{ featureLoadError }}</span>
            </div>
          </div>

          <!-- No Features Message -->
          <div v-else-if="selectedMapId && !loadingFeatures && features.length === 0 && !featureLoadError"
               class="text-sm text-gray-500">
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
    <CaltopoSetupModal :show="showSetupModal" @close="showSetupModal = false"/>
  </div>
</template>

<script>
import {
  CheckCircleIcon,
  ClockIcon,
  ExclamationTriangleIcon,
  InformationCircleIcon,
  XMarkIcon
} from '@heroicons/vue/24/outline'
import CaltopoSetupModal from './CaltopoSetupModal.vue'
import BaseButton from 'platform/components/parts/BaseButton.vue'
import Loader from 'platform/components/parts/Loader.vue'

export default {
  name: 'CaltopoSettings',
  components: {
    InformationCircleIcon,
    CheckCircleIcon,
    ExclamationTriangleIcon,
    ClockIcon,
    XMarkIcon,
    CaltopoSetupModal,
    BaseButton,
    Loader
  },
  inject: ['caltopoExtensionApi', 'caltopoExtensionRouter', 'caltopoExtensionMainRouter'],
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
      queueStatus: null,
      featureLoadError: null
    }
  },
  async created() {
    await this.checkConnectionStatus()
  },
  computed: {
    api() {
      return this.caltopoExtensionApi
    },
    toast() {
      return window.gv_core.GeoVault?.toast
    },
    router() {
      return this.caltopoExtensionRouter || this.$router
    },
    mainRouter() {
      return this.caltopoExtensionMainRouter || this.$router
    },
    isMapImported() {
      return this.queueStatus === 'done'
    }
  },
  methods: {
    async checkConnectionStatus() {
      this.connectionStatus.checking = true
      try {
        const response = await this.api.get('/status/')
        this.connectionStatus.connected = response.data.connected || false
        this.connectionStatus.status = response.data.status || (response.data.connected ? 'connected' : 'not_connected')
        this.connectionStatus.checking = false
        if (this.connectionStatus.status === 'connected') {
          await this.loadMaps()
        }
      } catch (error) {
        const errorInfo = this.api.handleError(error)
        this.connectionStatus.checking = false
        console.error('Error checking CalTopo status:', errorInfo)
      }
    },
    async handleConnect() {
      this.connecting = true
      this.connectMessage = ''

      try {
        const response = await this.api.post('/connect/', this.connectForm)
        this.connectMessage = 'Successfully connected to CalTopo!'
        this.connectMessageType = 'success'
        this.connectionStatus.connected = true
        this.connectionStatus.status = 'connected'
        await this.loadMaps()
        this.connectForm = {
          account_id: '',
          credential_id: '',
          credential_key: ''
        }
      } catch (error) {
        const errorInfo = this.api.handleError(error)
        if (errorInfo.details?.error_code === 'CALTOPO_TIMEOUT') {
          this.connectMessage = 'CalTopo request timed out.'
        } else {
          this.connectMessage = errorInfo.message || 'Failed to connect to CalTopo'
        }
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
        await this.api.post('/disconnect/')
        this.connectionStatus.connected = false
        this.connectionStatus.status = 'not_connected'
        this.selectedMapId = ''
        this.features = []
        this.maps = []
        this.mapInQueue = false
        this.importQueueId = null
        this.queueStatus = null
        this.connectMessage = 'Disconnected from CalTopo'
        this.connectMessageType = 'success'
        this.toast.success('Disconnected from CalTopo')
      } catch (error) {
        const errorInfo = this.api.handleError(error)
        this.connectMessage = errorInfo.message || 'Failed to disconnect from CalTopo'
        this.connectMessageType = 'error'
        this.toast.error(errorInfo.message || 'Failed to disconnect from CalTopo')
      }
    },
    async loadMaps() {
      this.loadingMaps = true
      try {
        const response = await this.api.get('/maps/')
        this.maps = response.data.maps || []
      } catch (error) {
        const errorInfo = this.api.handleError(error)
        console.error('Failed to load maps:', errorInfo)
      } finally {
        this.loadingMaps = false
      }
    },
    async handleMapSelect() {
      if (!this.selectedMapId) {
        this.features = []
        this.mapInQueue = false
        this.importQueueId = null
        this.queueStatus = null
        this.featureLoadError = null
        return
      }

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
        const response = await this.api.get(`/maps/${this.selectedMapId}/features/`)
        this.features = response.data.features || []
        this.features.forEach(feature => {
          if (feature.is_valid === undefined) {
            feature.is_valid = true
          }
        })
        this.mapInQueue = response.data.is_in_queue || false
        this.importQueueId = response.data.import_queue_id || null
        this.queueStatus = response.data.queue_status || null
      } catch (error) {
        const errorInfo = this.api.handleError(error)
        if (errorInfo.details?.error_code === 'CALTOPO_TIMEOUT') {
          this.featureLoadError = 'CalTopo request timed out.'
        } else {
          this.featureLoadError = errorInfo.message || 'Failed to load features'
        }
        this.mapInQueue = false
        this.importQueueId = null
        console.error('Failed to load features:', errorInfo)
      } finally {
        this.loadingFeatures = false
      }
    },
    async handleImportFeature(feature) {
      if (feature.is_valid === false) {
        const featureClass = feature.properties?.class || 'Unknown'
        this.toast.error(`Feature type '${featureClass}' is not supported for import`)
        return
      }

      this.importingFeatures[feature.id] = true
      this.importWarnings = []

      try {
        const response = await this.api.post('/import/feature/', {
          map_id: this.selectedMapId,
          feature_id: feature.id,
          feature_class: feature.properties?.class || 'Marker'
        })

        const featureIndex = this.features.findIndex(f => f.id === feature.id)
        if (featureIndex !== -1) {
          this.features[featureIndex].is_imported = true
          if (response.data.feature && response.data.feature.properties && response.data.feature.properties.database_id) {
            this.features[featureIndex].database_id = response.data.feature.properties.database_id
          }
        }

        if (response.data.warnings && response.data.warnings.length > 0) {
          this.importWarnings = response.data.warnings.map(w => {
            if (w.type === 'hash') {
              return `Hash duplicate: Feature with identical hash already exists`
            } else if (w.type === 'geometry') {
              return `Geometry duplicate: Feature with similar geometry already exists`
            }
            return w.message || 'Unknown warning'
          })
        }

        this.toast.success('Feature imported successfully')
      } catch (error) {
        const errorInfo = this.api.handleError(error)
        this.toast.error(errorInfo.message || 'Failed to import feature')
      } finally {
        this.importingFeatures[feature.id] = false
      }
    },
    getMapViewUrl(mapId) {
      // Generate URL to view map with tag filter for the imported CalTopo map
      const tag = `source-file:caltopo_map_${mapId}.geojson`
      return {
        path: '/map',
        query: {tag: tag}
      }
    },
    handleViewInMap(feature) {
      const featureId = feature.database_id
      if (featureId) {
        // Use main router to navigate to the platform map page
        if (this.mainRouter) {
          this.mainRouter.push({
            path: '/map',
            query: {featureId: featureId}
          })
        } else if (this.$router) {
          this.$router.push({
            path: '/map',
            query: {featureId: featureId}
          })
        }
      } else {
        console.error('Feature missing database_id:', feature)
        this.toast.error('Unable to view feature: missing ID')
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

      this.features.forEach(feature => {
        feature.is_imported = false
        delete feature.database_id
      })

      try {
        const response = await this.api.post('/import/map/', {
          map_id: this.selectedMapId
        })

        this.mapInQueue = true
        this.importQueueId = response.data.import_queue_id || null

        this.toast.success(`Map import queued. Processing ${response.data.feature_count || 0} features.`)
      } catch (error) {
        const errorInfo = this.api.handleError(error)
        this.toast.error(errorInfo.message || 'Failed to import map')
      } finally {
        this.importingMap = false
      }
    }
  },
}
</script>
