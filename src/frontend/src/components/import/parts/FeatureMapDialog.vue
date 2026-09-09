<template>
  <BaseModal
    :is-open="isOpen"
    :title="`Feature Map View${selectedFeatureName ? ' - ' + selectedFeatureName : ''}`"
    max-width="6xl"
    @close="closeDialog"
  >
    <div class="flex-1 bg-white min-h-0 flex flex-col overflow-hidden relative h-full">
      <div ref="mapContainer" class="flex-1 w-full border-0"></div>

      <div v-show="isLoading" class="absolute top-4 right-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10">
        <Loader size="sm" layout="inline" message="Loading map..." />
      </div>

      <div class="absolute bottom-4 left-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10 text-xs">
        <div class="space-y-1">
          <div>Total Features: <span class="font-medium">{{ featureCount }}</span></div>
          <div>Selected: <span class="font-medium">{{ selectedFeatureName }}</span></div>
        </div>
      </div>
    </div>

    <template #footer>
      <button
        @click="closeDialog"
        class="inline-flex items-center px-4 py-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
        title="Close Dialog"
      >
        Close
      </button>
    </template>
  </BaseModal>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { MapLibrePreviewMap } from '@/utils/map/common/MapLibrePreviewMap'
import BaseModal from '@/components/parts/BaseModal.vue'
import Loader from '@/components/parts/Loader.vue'
import type { GeoJsonFeature } from '@/types/geospatial'

const props = withDefaults(defineProps<{
  isOpen?: boolean
  features?: GeoJsonFeature[]
  selectedFeatureIndex?: number
  filename?: string
}>(), {
  isOpen: false,
  features: () => [],
  selectedFeatureIndex: 0,
  filename: '',
})

const emit = defineEmits<{
  (e: 'close'): void
}>()

const mapContainer = ref<HTMLDivElement | null>(null)
const isLoading = ref(false)
const featureCount = ref(0)
const preview = new MapLibrePreviewMap()

const selectedFeature = computed(() => {
  if (props.selectedFeatureIndex < 0 || props.selectedFeatureIndex >= props.features.length) {
    return null
  }
  return props.features[props.selectedFeatureIndex]
})

const selectedFeatureName = computed(() => {
  const name = selectedFeature.value?.properties?.name
  return typeof name === 'string' ? name : ''
})

function showSelectedOrAll(): void {
  if (!preview.map || props.features.length === 0) {
    featureCount.value = 0
    isLoading.value = false
    return
  }

  isLoading.value = true
  try {
    preview.loadFeatures(props.features)
    featureCount.value = props.features.length
    const focus = selectedFeature.value
    if (focus) {
      preview.fitToFeatures([focus], { padding: 50, maxZoom: 15, duration: 500 })
    } else {
      preview.fitToFeatures(props.features, { padding: 50, maxZoom: 15 })
    }
  } catch (error) {
    console.error('Error loading features for feature map view:', error)
  } finally {
    isLoading.value = false
  }
}

function closeDialog(): void {
  emit('close')
}

watch(() => props.isOpen, (open) => {
  if (open) {
    void nextTick(async () => {
      isLoading.value = true
      try {
        if (mapContainer.value) {
          await preview.create(mapContainer.value)
          showSelectedOrAll()
        }
      } catch (error) {
        console.error('Error initializing feature map:', error)
        isLoading.value = false
      }
    })
    return
  }
  preview.destroy()
})

watch(() => props.features, () => {
  if (props.isOpen && preview.map) {
    showSelectedOrAll()
  }
}, { deep: true })

watch(() => props.selectedFeatureIndex, () => {
  if (props.isOpen && preview.map) {
    showSelectedOrAll()
  }
})

const route = useRoute()
watch(() => route.fullPath, () => {
  if (props.isOpen) {
    closeDialog()
  }
})

onBeforeUnmount(() => {
  preview.destroy()
})
</script>
