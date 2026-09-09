<template>
  <BaseModal
    :is-open="isOpen"
    title="Map Preview"
    max-width="6xl"
    @close="closeDialog"
  >
    <div class="flex-1 bg-white min-h-0 flex flex-col overflow-hidden relative h-full">
      <div ref="mapContainer" class="flex-1 w-full border-0"></div>

      <div v-show="isLoading" class="absolute top-4 right-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10">
        <Loader size="sm" layout="inline" message="Loading preview..." />
      </div>

      <div class="absolute bottom-4 left-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10 text-xs">
        <div class="space-y-1">
          <div>Features: <span class="font-medium">{{ featureCount }}</span></div>
          <div v-if="filename">File: <span class="font-medium">{{ filename }}</span></div>
        </div>
      </div>
    </div>
  </BaseModal>
</template>

<script setup lang="ts">
import { ref, watch, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { MapLibrePreviewMap } from '@/utils/map/common/MapLibrePreviewMap'
import BaseModal from '@/components/parts/BaseModal.vue'
import Loader from '@/components/parts/Loader.vue'
import type { GeoJsonFeature } from '@/types/geospatial'

const props = withDefaults(defineProps<{
  isOpen?: boolean
  features?: GeoJsonFeature[]
  filename?: string
}>(), {
  isOpen: false,
  features: () => [],
  filename: '',
})

const emit = defineEmits<{
  (e: 'close'): void
}>()

const mapContainer = ref<HTMLDivElement | null>(null)
const isLoading = ref(false)
const featureCount = ref(0)
const preview = new MapLibrePreviewMap()

function loadFeatures(): void {
  if (!preview.map || props.features.length === 0) {
    featureCount.value = 0
    isLoading.value = false
    return
  }

  isLoading.value = true
  try {
    preview.loadFeatures(props.features)
    featureCount.value = props.features.length
    preview.fitToFeatures(props.features, { padding: 50, maxZoom: 15 })
  } catch (error) {
    console.error('Error loading features for preview:', error)
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
          loadFeatures()
        }
      } catch (error) {
        console.error('Error initializing map preview:', error)
        isLoading.value = false
      }
    })
    return
  }
  preview.destroy()
})

watch(() => props.features, () => {
  if (props.isOpen && preview.map) {
    loadFeatures()
  }
}, { deep: true })

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
