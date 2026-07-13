<template>
  <BaseModal
    :is-open="isOpen"
    title="Map Preview"
    max-width="6xl"
    @close="closeDialog"
  >
    <!-- Map Container -->
    <div class="flex-1 bg-white min-h-0 flex flex-col overflow-hidden relative h-full">
      <!-- Map -->
      <div ref="mapContainer" class="flex-1 w-full border-0"></div>

      <!-- Loading Indicator -->
      <div v-show="isLoading" class="absolute top-4 right-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10">
        <Loader size="sm" layout="inline" message="Loading preview..." />
      </div>

      <!-- Feature Info -->
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
import { Style, Fill, Stroke, Circle, Text } from 'ol/style'
import type { FeatureLike } from 'ol/Feature'
import {
  useOpenLayersPreviewMap,
  getFeatureProperties,
  getStringProperty,
  getNumberProperty,
} from '@/composables/useOpenLayersPreviewMap'
import BaseModal from '@/components/parts/BaseModal.vue'
import Loader from '@/components/parts/Loader.vue'
import { getDefaultBasemapFromStore } from '@/utils/map/mapConfigUtils'
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

function getFeatureStyle(feature: FeatureLike): Style {
  const properties = getFeatureProperties(feature)
  const geometryType = feature.getGeometry()?.getType()

  if (geometryType === 'Point') {
    const fillColor = getStringProperty(properties, 'marker-color', '#ff0000')
    return new Style({
      image: new Circle({
        radius: 5,
        fill: new Fill({ color: fillColor })
      })
    })
  } else if (geometryType === 'LineString') {
    const strokeColor = getStringProperty(properties, 'stroke', '#ff0000')
    return new Style({
      stroke: new Stroke({
        color: strokeColor,
        width: getNumberProperty(properties, 'stroke-width', 3)
      })
    })
  } else if (geometryType === 'Polygon') {
    const strokeColor = getStringProperty(properties, 'stroke', '#ff0000')
    let fillColor = getStringProperty(properties, 'fill', '#ff0000')

    const fillOpacity = getNumberProperty(properties, 'fill-opacity')
    if (fillOpacity !== undefined) {
      const hex = fillColor.replace('#', '')
      const r = parseInt(hex.substring(0, 2), 16)
      const g = parseInt(hex.substring(2, 4), 16)
      const b = parseInt(hex.substring(4, 6), 16)
      fillColor = `rgba(${r}, ${g}, ${b}, ${fillOpacity})`
    }

    return new Style({
      stroke: new Stroke({
        color: strokeColor,
        width: getNumberProperty(properties, 'stroke-width', 2)
      }),
      fill: new Fill({ color: fillColor })
    })
  }

  return new Style({
    stroke: new Stroke({ color: '#ff0000', width: 2 }),
    fill: new Fill({ color: 'rgba(255, 0, 0, 0.3)' })
  })
}

function getLabelStyle(feature: FeatureLike): Style | undefined {
  const properties = getFeatureProperties(feature)
  const geometryType = feature.getGeometry()?.getType()
  const name = getStringProperty(properties, 'name')

  if (!name) {
    return undefined
  }

  const offsetY = geometryType === 'Point' ? -15 : -10
  return new Style({
    text: new Text({
      text: name,
      font: '12px Arial',
      fill: new Fill({ color: '#000000' }),
      stroke: new Stroke({ color: '#ffffff', width: 3 }),
      offsetY
    })
  })
}

const { map, loadFeatures: loadFeaturesIntoMap, fitToAllFeatures, initMap, cleanup } = useOpenLayersPreviewMap({
  getFeatureStyle,
  getLabelStyle,
  tileSourceId: getDefaultBasemapFromStore()
})

async function initializeMap(): Promise<void> {
  await initMap(mapContainer.value)
}

function loadFeatures(): void {
  if (!map.value || props.features.length === 0) {
    featureCount.value = 0
    isLoading.value = false
    return
  }

  isLoading.value = true

  try {
    const olFeatures = loadFeaturesIntoMap(props.features)
    featureCount.value = olFeatures.length
    if (olFeatures.length > 0) {
      fitToAllFeatures({ padding: [50, 50, 50, 50], maxZoom: 15 })
    }
  } catch (error) {
    console.error('Error loading features for preview:', error)
  } finally {
    isLoading.value = false
  }
}

function closeDialog(): void {
  emit('close')
}

watch(() => props.isOpen, (newVal) => {
  if (newVal) {
    void nextTick(async () => {
      isLoading.value = true
      try {
        await initializeMap()
        loadFeatures()
      } catch (error) {
        console.error('Error initializing map preview:', error)
        isLoading.value = false
      }
    })
  } else {
    cleanup()
  }
})

watch(() => props.features, () => {
  if (props.isOpen && map.value) {
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
  cleanup()
})
</script>

<style scoped>
/* Hide OpenLayers attribution */
:deep(.ol-attribution) {
  display: none;
}

</style>
