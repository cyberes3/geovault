<template>
  <BaseModal
    :is-open="isOpen"
    title="Update Spatial Data"
    max-width="4xl"
    @close="handleCancel"
  >
    <div class="px-6 py-4">
      <!-- File Selection Section (shown before upload starts) -->
          <div v-if="!importQueueId && !processing" class="space-y-4">
            <!-- Help Text -->
            <div class="p-4 bg-blue-50 border border-blue-200 rounded-lg">
              <div class="flex items-start">
                <InformationCircleIcon class="h-5 w-5 text-blue-500 mr-3 flex-shrink-0 mt-0.5" />
                <div class="flex-1 space-y-2 text-sm text-blue-800">
                  <p>Upload a <strong>KMZ, KML, or GPX</strong> file to replace this feature's spatial geometry.</p>
                  <p>Only features with <strong>matching geometry types</strong> will be available for selection.</p>
                  <p>The feature's <strong>name, description, and other properties will remain unchanged</strong>.</p>
                </div>
              </div>
            </div>

            <!-- File Drop Zone (only shown when no file is selected) -->
            <div v-if="!selectedFile">
              <label class="block text-sm font-medium text-gray-700 mb-2">
                Select KMZ/KML/GPX File
              </label>
              <div class="mt-1 flex items-center space-x-4">
                <label class="flex-1 cursor-pointer">
                  <input
                    ref="fileInput"
                    type="file"
                    accept=".kmz,.kml,.gpx"
                    @change="handleFileSelect"
                    class="hidden"
                  />
                  <div
                    :class="dropzoneClasses"
                    class="flex items-center justify-center px-6 py-3 border-2 border-dashed rounded-lg transition-colors"
                    @drop="onDrop"
                    @dragover.prevent
                    @dragenter.prevent="dragEnter"
                    @dragleave="dragLeave"
                  >
                    <div class="text-center">
                      <DocumentIcon class="mx-auto h-12 w-12 text-gray-400" />
                      <p class="mt-2 text-sm text-gray-600">
                        <span class="font-medium text-blue-500 hover:text-blue-700">Click to browse</span> or drag and drop
                      </p>
                      <p class="mt-1 text-xs text-gray-500">KMZ, KML, or GPX files only (max 5MB)</p>
                    </div>
                  </div>
                </label>
              </div>
            </div>

            <!-- Selected File Display (shown when file is selected) -->
            <div v-if="selectedFile" class="p-4 bg-blue-50 border border-blue-200 rounded-lg">
              <div class="flex items-center justify-between">
                <div class="flex items-center space-x-3 flex-1 min-w-0">
                  <DocumentIcon class="h-5 w-5 text-blue-500 flex-shrink-0" />
                  <div class="flex-1 min-w-0">
                    <p class="text-sm font-medium text-gray-900 truncate">{{ selectedFile.name }}</p>
                    <p class="text-xs text-gray-500">{{ formatFileSize(selectedFile.size) }}</p>
                  </div>
                </div>
                <button
                  @click="clearFileSelection"
                  class="ml-3 text-gray-400 hover:text-gray-600 focus:outline-none"
                  title="Remove File"
                >
                  <XMarkIcon class="h-5 w-5" />
                </button>
              </div>
            </div>

            <!-- Error Message -->
            <div v-if="errorMessage" class="p-4 bg-red-50 border-2 border-red-300 rounded-md">
              <div class="flex items-start">
                <ExclamationCircleIcon class="h-6 w-6 text-red-500 mr-3 flex-shrink-0 mt-0.5" />
                <p class="text-base font-medium text-red-900 leading-relaxed">{{ errorMessage }}</p>
              </div>
            </div>
          </div>

          <!-- Processing Section -->
          <div v-else-if="processing" class="space-y-4">
            <div class="text-center py-6">
              <Loader size="lg" layout="centered" :message="processingMessage" />
              <div v-if="processingProgress !== null" class="mt-6 max-w-md mx-auto">
                <div class="w-full bg-gray-200 rounded-full h-3 overflow-hidden">
                  <div
                    :style="{ width: processingProgress + '%' }"
                    class="bg-blue-500 h-3 rounded-full transition-all duration-300"
                  >
                  </div>
                </div>
                <p class="text-xs text-gray-500 mt-2">{{ Math.round(processingProgress) }}% complete</p>
              </div>
              <p v-if="selectedFile" class="mt-4 text-xs text-gray-500">Processing: {{ selectedFile.name }}</p>
            </div>
          </div>

          <!-- Feature Selection Section -->
          <div v-else-if="features.length > 0" class="space-y-4">
            <!-- No matching features message -->
            <div v-if="sortedFeatures.length === 0" class="p-4 bg-yellow-50 border border-yellow-200 rounded-lg">
              <div class="flex">
                <ExclamationTriangleIcon class="h-5 w-5 text-yellow-400 mr-2 flex-shrink-0" />
                <div>
                  <p class="text-sm font-medium text-yellow-800">No matching geometry types found</p>
                  <p class="text-xs text-yellow-700 mt-1">
                    The uploaded file contains {{ features.length }} feature{{ features.length !== 1 ? 's' : '' }},
                    but none match the geometry type of the existing feature ({{ existingFeatureGeometryType }}).
                    Only features with the same geometry type can be used for replacement.
                  </p>
                </div>
              </div>
            </div>

            <!-- Features list -->
            <div v-else>
              <div class="mb-3">
                <div class="flex items-center justify-between mb-2">
                  <h4 class="text-sm font-medium text-gray-900">
                    Select a feature to apply its spatial data:
                    <span v-if="features.length !== sortedFeatures.length" class="text-xs font-normal text-gray-500 ml-2">
                      ({{ sortedFeatures.length }} of {{ features.length }} matching geometry type)
                    </span>
                  </h4>
                </div>
                <div class="flex items-center gap-3 p-3 bg-gray-50 border border-gray-200 rounded-md">
                  <div class="flex-1">
                    <label class="block text-sm font-medium text-gray-700 mb-1">
                      Regenerate automatic tags
                    </label>
                    <p class="text-xs text-gray-600">
                      When enabled, location and geometry-based tags will be automatically updated based on the new spatial data. Your custom tags will be preserved.
                    </p>
                  </div>
                  <div class="flex-shrink-0">
                    <ToggleButton
                      v-model="regenerateTags"
                      size="md"
                    />
                  </div>
                </div>
              </div>
              <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div class="space-y-2 max-h-96 overflow-y-auto">
                  <div
                    v-for="(feature, index) in sortedFeatures"
                    :key="index"
                    @click="selectedFeatureIndex = index"
                    :class="[
                      'p-4 border-2 rounded-lg cursor-pointer transition-colors',
                      selectedFeatureIndex === index
                        ? 'border-blue-500 bg-blue-50'
                        : 'border-gray-200 hover:border-gray-300'
                    ]"
                  >
                    <div class="flex items-start justify-between">
                      <div class="flex-1 min-w-0">
                        <h5 class="text-sm font-medium text-gray-900">
                          {{ feature.properties?.name || `Feature ${index + 1}` }}
                        </h5>
                        <p class="text-xs text-gray-500 mt-1">{{ feature.geometry.type }}</p>
                        <p v-if="feature.properties?.description" class="text-xs text-gray-600 mt-1 line-clamp-2">
                          {{ feature.properties.description }}
                        </p>
                      </div>
                      <div v-if="selectedFeatureIndex === index" class="ml-4 flex-shrink-0">
                        <CheckIcon class="h-5 w-5 text-blue-500" />
                      </div>
                    </div>
                  </div>
                </div>
                <div class="relative min-h-64 border border-gray-300 rounded-md overflow-hidden">
                  <div ref="previewContainer" class="absolute inset-0"></div>
                  <button
                    v-if="selectedFeatureIndex !== null"
                    @click="expandMap(selectedFeatureIndex)"
                    class="absolute top-2 right-2 bg-white bg-opacity-90 hover:bg-opacity-100 rounded p-1 shadow-sm border border-gray-300"
                    title="Expand Map Preview"
                  >
                    <ArrowsPointingOutIcon class="w-4 h-4 text-gray-700" />
                  </button>
                </div>
              </div>
            </div>

            <!-- Error Message -->
            <div v-if="errorMessage" class="p-4 bg-red-50 border-2 border-red-300 rounded-md">
              <div class="flex items-start">
                <ExclamationCircleIcon class="h-6 w-6 text-red-500 mr-3 flex-shrink-0 mt-0.5" />
                <p class="text-base font-medium text-red-900 leading-relaxed">{{ errorMessage }}</p>
              </div>
            </div>

            <!-- Success Message -->
            <div v-if="successMessage" class="p-3 bg-green-50 border border-green-200 rounded-md">
              <div class="flex">
                <CheckIcon class="h-5 w-5 text-green-400 mr-2" />
                <p class="text-sm text-green-800">{{ successMessage }}</p>
              </div>
            </div>
          </div>
        </div>

    <template #footer>
      <!-- Cancel Button (shown when not processing or when no features available) -->
      <BaseButton
        v-if="!importQueueId || (!processing && features.length === 0)"
        @click="handleCancel"
        variant="white"
        size="sm"
        title="Cancel"
      >
        Cancel
      </BaseButton>

      <!-- Close Button (shown after applied) -->
      <BaseButton
        v-if="applied"
        @click="handleClose"
        variant="primary"
        color="blue"
        size="sm"
        title="Close Dialog"
      >
        Close
      </BaseButton>

      <!-- Upload Button (shown when file is selected but not yet uploaded) -->
      <BaseButton
        v-if="selectedFile && !importQueueId && !processing"
        @click="handleUpload"
        variant="primary"
        color="blue"
        size="sm"
        title="Upload and Process File"
      >
        <ArrowUpTrayIcon class="h-4 w-4 mr-2" />
        Upload & Process
      </BaseButton>

      <!-- Apply Button (always shown when features are available, disabled when not ready) -->
      <BaseButton
        v-if="sortedFeatures.length > 0 && !applied"
        @click="handleApply"
        :disabled="applying || selectedFeatureIndex === null"
        variant="primary"
        color="blue"
        size="sm"
        title="Apply Selected Feature's Spatial Data"
      >
        <CheckIcon v-if="!applying" class="h-4 w-4 mr-2" />
        <Loader v-if="applying" size="sm" layout="inline" :show-message="false" color="white" />
        {{ applying ? 'Applying...' : 'Apply Spatial Data' }}
      </BaseButton>
    </template>
  </BaseModal>

  <!-- Expanded Map Modal -->
  <BaseModal
    :is-open="expandedMapIndex !== null"
    :title="expandedMapIndex !== null ? (sortedFeatures[expandedMapIndex]?.properties?.name || `Feature ${expandedMapIndex + 1}`) + ' - Map Preview' : ''"
    max-width="6xl"
    :on-top="true"
    :full-screen-mobile="true"
    @close="closeExpandedMap"
  >
    <div class="flex-1 min-h-0 flex flex-col p-6 h-full">
      <div
        ref="expandedMapContainer"
        class="flex-1 min-h-0 w-full border border-gray-300 rounded-md overflow-hidden"
      ></div>
    </div>
  </BaseModal>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { MapLibrePreviewMap } from '@/utils/map/common/MapLibrePreviewMap'
import { getFeature, applyFeatureReplacement } from '@/api/services/featuresApi'
import { uploadImportFile, getImportJobStatus, getImportQueueFeatures, deleteImportItem } from '@/api/services/importApi'
import { getApiErrorMessage } from '@/utils/apiError'
import { PROCESSING_MESSAGES } from '@/assets/js/constants/processing-messages.js'
import BaseModal from '@/components/parts/BaseModal.vue'
import BaseButton from '@/components/parts/BaseButton.vue'
import Loader from '@/components/parts/Loader.vue'
import ToggleButton from '@/components/parts/ToggleButton.vue'
import { InformationCircleIcon, DocumentIcon, ExclamationCircleIcon, ExclamationTriangleIcon, CheckIcon, ArrowUpTrayIcon, ArrowsPointingOutIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import type { GeoJsonFeature } from '@/types/geospatial'

interface ImportJobStatus {
  status?: string
  progress?: number
  message?: string
  import_queue_id?: number | string
  error_message?: string
}

interface ImportJobStatusResponse {
  job_status?: ImportJobStatus
}

interface ImportQueueFeaturesResponse {
  items?: GeoJsonFeature[]
  error?: string
}

interface FeatureDetailResponse {
  feature?: {
    geojson?: {
      geometry?: {
        type?: string
      }
    }
  }
}

const props = withDefaults(defineProps<{
  isOpen?: boolean
  featureId: number
}>(), {
  isOpen: false,
})

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'applied'): void
}>()

const importQueueId = ref<number | string | null>(null)
const jobId = ref<string | null>(null)
const processing = ref(false)
const processingMessage = ref('Processing file...')
const processingProgress = ref<number | null>(null)
const features = ref<GeoJsonFeature[]>([])
const selectedFeatureIndex = ref<number | null>(null)
const errorMessage = ref('')
const successMessage = ref('')
const applying = ref(false)
const applied = ref(false)
const regenerateTags = ref(false)
const selectedFile = ref<File | null>(null)
const existingFeatureGeometryType = ref<string | null>(null)
const expandedMapIndex = ref<number | null>(null)
const isDragOver = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const previewContainer = ref<HTMLDivElement | null>(null)
const expandedMapContainer = ref<HTMLDivElement | null>(null)

let pollingInterval: ReturnType<typeof setInterval> | null = null
const preview = new MapLibrePreviewMap()
const expandedPreview = new MapLibrePreviewMap()

const sortedFeatures = computed(() => {
  let filtered = features.value

  if (existingFeatureGeometryType.value) {
    filtered = features.value.filter((feature) => geometryTypesMatch(existingFeatureGeometryType.value, feature.geometry.type))
  }

  return [...filtered].sort((a, b) => {
    const nameA = ((a.properties.name as string | undefined) ?? '').toLowerCase()
    const nameB = ((b.properties.name as string | undefined) ?? '').toLowerCase()
    return nameA.localeCompare(nameB)
  })
})

const dropzoneClasses = computed(() => {
  return isDragOver.value
    ? 'border-blue-600 bg-blue-50'
    : 'border-gray-300 hover:border-blue-600 hover:bg-blue-50'
})

function resetDialog(): void {
  cleanupMaps()

  importQueueId.value = null
  jobId.value = null
  processing.value = false
  processingMessage.value = 'Processing file...'
  processingProgress.value = null
  features.value = []
  selectedFeatureIndex.value = null
  errorMessage.value = ''
  successMessage.value = ''
  applying.value = false
  applied.value = false
  regenerateTags.value = false
  selectedFile.value = null
  existingFeatureGeometryType.value = null
  expandedMapIndex.value = null
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

function cleanupMaps(): void {
  preview.destroy()
  expandedPreview.destroy()
}

async function showSelectedPreview(): Promise<void> {
  if (selectedFeatureIndex.value === null || !previewContainer.value) return
  const feature = sortedFeatures.value[selectedFeatureIndex.value]
  if (!feature) return
  if (!preview.map) {
    await preview.create(previewContainer.value)
  }
  preview.loadFeatures([feature])
  preview.fitToFeatures([feature], { padding: 16, duration: 0, maxZoom: 12 })
}

function expandMap(index: number): void {
  expandedMapIndex.value = index
}

function closeExpandedMap(): void {
  expandedMapIndex.value = null
}

async function initializeExpandedMap(): Promise<void> {
  if (expandedMapIndex.value === null || !expandedMapContainer.value) return
  const feature = sortedFeatures.value[expandedMapIndex.value]
  if (!feature) return
  await expandedPreview.create(expandedMapContainer.value)
  expandedPreview.loadFeatures([feature])
  expandedPreview.fitToFeatures([feature], { padding: 24, duration: 0, maxZoom: 15 })
}

async function fetchExistingFeatureGeometryType(): Promise<void> {
  try {
    const data = await getFeature(props.featureId) as FeatureDetailResponse
    const geometryType = data.feature?.geojson?.geometry?.type
    if (geometryType) {
      existingFeatureGeometryType.value = geometryType
    }
  } catch (error) {
    console.error('Error fetching existing feature geometry type:', error)
    // Continue without filtering if we can't fetch the geometry type
  }
}

function geometryTypesMatch(existingType: string | null | undefined, replacementType: string | null | undefined): boolean {
  if (!existingType || !replacementType) return false
  return existingType.toLowerCase() === replacementType.toLowerCase()
}

function cleanup(): void {
  if (pollingInterval) {
    clearInterval(pollingInterval)
    pollingInterval = null
  }
  cleanupMaps()
}

function handleFileSelect(event: Event): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    selectedFile.value = null
    return
  }

  if (processing.value || importQueueId.value) {
    errorMessage.value = 'Please wait for the current upload to complete'
    if (fileInput.value) {
      fileInput.value.value = ''
    }
    return
  }

  const fileName = file.name.toLowerCase()
  if (!fileName.endsWith('.kmz') && !fileName.endsWith('.kml') && !fileName.endsWith('.gpx')) {
    errorMessage.value = 'Please select a KMZ, KML, or GPX file'
    selectedFile.value = null
    if (fileInput.value) {
      fileInput.value.value = ''
    }
    return
  }

  errorMessage.value = ''
  selectedFile.value = file
}

function clearFileSelection(): void {
  selectedFile.value = null
  errorMessage.value = ''
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

function isAllowedSpatialFile(fileName: string): boolean {
  return fileName.endsWith('.kmz') || fileName.endsWith('.kml') || fileName.endsWith('.gpx')
}

function onDrop(e: DragEvent): void {
  e.preventDefault()
  e.stopPropagation()
  isDragOver.value = false

  const file = e.dataTransfer?.files[0]
  if (!file) {
    selectedFile.value = null
    return
  }

  if (processing.value || importQueueId.value) {
    errorMessage.value = 'Please wait for the current upload to complete'
    return
  }

  if (!isAllowedSpatialFile(file.name.toLowerCase())) {
    errorMessage.value = 'Please select a KMZ, KML, or GPX file'
    selectedFile.value = null
    return
  }

  errorMessage.value = ''
  selectedFile.value = file
}

function dragEnter(e: DragEvent): void {
  e.preventDefault()
  e.stopPropagation()
  isDragOver.value = true
}

function dragLeave(e: DragEvent): void {
  e.preventDefault()
  e.stopPropagation()
  const dropzone = e.currentTarget as HTMLElement | null
  const relatedTarget = e.relatedTarget as Node | null
  if (!dropzone?.contains(relatedTarget)) {
    isDragOver.value = false
  }
}

function handleUpload(): void {
  if (!selectedFile.value) {
    errorMessage.value = 'Please select a file first'
    return
  }
  void uploadFile(selectedFile.value)
}

async function uploadFile(file: File): Promise<void> {
  errorMessage.value = ''
  processing.value = true
  processingMessage.value = 'Uploading file...'
  processingProgress.value = 0

  try {
    const data = await uploadImportFile(file, { replacementFeatureId: props.featureId })
    jobId.value = data.job_id ?? null
    processingMessage.value = 'Processing file...'
    processingProgress.value = 10
    startPolling()
  } catch (error) {
    console.error('Error uploading file:', error)
    errorMessage.value = getApiErrorMessage(error, 'Failed to upload file')
    processing.value = false
  }
}

function startPolling(): void {
  pollingInterval = setInterval(() => {
    void (async () => {
      if (!jobId.value) return

      try {
        const data = await getImportJobStatus(jobId.value) as ImportJobStatusResponse
        const jobStatus = data.job_status
        if (jobStatus) {
          processingProgress.value = jobStatus.progress ?? 0
          processingMessage.value = jobStatus.message || 'Processing...'

          if (jobStatus.status === 'completed') {
            importQueueId.value = jobStatus.import_queue_id ?? null
            if (pollingInterval) {
              clearInterval(pollingInterval)
              pollingInterval = null
            }
            await fetchFeatures()
          } else if (jobStatus.status === 'failed') {
            errorMessage.value = jobStatus.error_message ?? PROCESSING_MESSAGES.PROCESSING_FAILED_DEFAULT
            processing.value = false
            if (pollingInterval) {
              clearInterval(pollingInterval)
              pollingInterval = null
            }
          }
        }
      } catch (error) {
        console.error('Error polling status:', error)
      }
    })()
  }, 1000)
}

async function fetchFeatures(): Promise<void> {
  if (!importQueueId.value) return

  try {
    const data = await getImportQueueFeatures(importQueueId.value) as ImportQueueFeaturesResponse
    if (data.items) {
      features.value = data.items
      processing.value = false
    } else {
      errorMessage.value = data.error || 'Failed to load features'
      processing.value = false
    }
  } catch (error) {
    console.error('Error fetching features:', error)
    errorMessage.value = getApiErrorMessage(error, 'Failed to load features')
    processing.value = false
  }
}

async function handleApply(): Promise<void> {
  if (selectedFeatureIndex.value === null || !importQueueId.value) return

  const selectedFeature = sortedFeatures.value[selectedFeatureIndex.value]
  const originalIndex = features.value.findIndex((f) => f === selectedFeature)

  if (originalIndex === -1) {
    errorMessage.value = 'Selected feature not found'
    return
  }

  applying.value = true
  errorMessage.value = ''

  try {
    await applyFeatureReplacement(props.featureId, {
      import_queue_id: importQueueId.value,
      feature_index: originalIndex,
      regenerate_tags: regenerateTags.value
    })

    successMessage.value = 'Spatial data updated successfully!'
    applied.value = true
    applying.value = false
    emit('applied')
  } catch (error) {
    console.error('Error applying replacement:', error)
    errorMessage.value = getApiErrorMessage(error, 'Failed to apply replacement geometry')
    applying.value = false
  }
}

function handleCancel(): void {
  handleClose()

  if (importQueueId.value) {
    deleteImportItem(importQueueId.value).catch((error: unknown) => {
      console.error('Error deleting import table item:', error)
    })
  }
}

function handleClose(): void {
  cleanup()
  emit('close')
}

function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 Bytes'
  const k = 1024
  const sizes = ['Bytes', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return Math.round(bytes / Math.pow(k, i) * 100) / 100 + ' ' + sizes[i]
}

watch(() => props.isOpen, (newVal) => {
  if (newVal) {
    void nextTick(() => {
      resetDialog()
      void fetchExistingFeatureGeometryType()
    })
  } else {
    cleanup()
  }
})

watch(expandedMapIndex, (newVal) => {
  if (newVal !== null) {
    void nextTick(() => {
      void initializeExpandedMap()
    })
  } else {
    expandedPreview.destroy()
  }
})

watch(selectedFeatureIndex, () => {
  if (props.isOpen) {
    void nextTick(() => {
      void showSelectedPreview()
    })
  }
})

watch(sortedFeatures, () => {
  if (props.isOpen && selectedFeatureIndex.value === null && sortedFeatures.value.length > 0) {
    selectedFeatureIndex.value = 0
  }
  void nextTick(() => {
    void showSelectedPreview()
  })
})

const route = useRoute()
watch(() => route.fullPath, () => {
  if (props.isOpen) {
    handleClose()
  }
})

onBeforeUnmount(() => {
  cleanup()
})
</script>
