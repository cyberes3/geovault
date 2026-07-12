<template>
  <BaseModal
    :is-open="isOpen"
    :title="`Feature Map View${selectedFeatureName ? ' - ' + selectedFeatureName : ''}`"
    max-width="6xl"
    @close="closeDialog"
  >
    <!-- Map Container -->
    <div class="flex-1 bg-white min-h-0 flex flex-col overflow-hidden relative h-full">
      <!-- Map -->
      <div ref="mapContainer" class="flex-1 w-full border-0"></div>

      <!-- Loading Indicator -->
      <div v-show="isLoading" class="absolute top-4 right-4 bg-white bg-opacity-90 px-4 py-2 rounded-lg shadow-md z-10">
        <Loader size="sm" layout="inline" message="Loading map..." />
      </div>

      <!-- Feature Info -->
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
import { ref, shallowRef, computed, watch, onBeforeUnmount, nextTick } from 'vue'
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
const selectedFeature = shallowRef<FeatureLike | null>(null)

const selectedFeatureName = computed(() => {
  const feature = selectedFeature.value
  if (!feature) {
    return ''
  }
  return getStringProperty(getFeatureProperties(feature), 'name')
})

const HIGHLIGHT_COLOR = '#ffff00'
const HIGHLIGHT_STROKE_COLOR = '#000000'

function getFeatureStyle(feature: FeatureLike): Style | Style[] {
  const properties = getFeatureProperties(feature)
  const geometryType = feature.getGeometry()?.getType()
  const isSelected = feature === selectedFeature.value

  if (geometryType === 'Point') {
    return new Style({
      image: new Circle({
        radius: isSelected ? 8 : 5,
        fill: new Fill({
          color: isSelected ? HIGHLIGHT_COLOR : getStringProperty(properties, 'marker-color', '#ff0000')
        }),
        stroke: new Stroke({
          color: isSelected ? HIGHLIGHT_STROKE_COLOR : 'transparent',
          width: isSelected ? 2 : 0
        })
      })
    })
  } else if (geometryType === 'LineString') {
    if (isSelected) {
      return [
        new Style({ stroke: new Stroke({ color: HIGHLIGHT_STROKE_COLOR, width: 10 }) }),
        new Style({ stroke: new Stroke({ color: HIGHLIGHT_COLOR, width: 6 }) })
      ]
    }
    const strokeColor = getStringProperty(properties, 'stroke', '#ff0000')
    const strokeWidth = getNumberProperty(properties, 'stroke-width', 3)
    return new Style({ stroke: new Stroke({ color: strokeColor, width: strokeWidth }) })
  } else if (geometryType === 'MultiPoint') {
    const fillColor = isSelected ? HIGHLIGHT_COLOR : getStringProperty(properties, 'marker-color', '#ff0000')
    const strokeColor = isSelected ? HIGHLIGHT_STROKE_COLOR : '#000000'
    const strokeWidth = isSelected ? 3 : 2
    return new Style({
      image: new Circle({
        radius: isSelected ? 12 : 8,
        fill: new Fill({ color: fillColor }),
        stroke: new Stroke({ color: strokeColor, width: strokeWidth })
      })
    })
  } else if (geometryType === 'MultiLineString') {
    if (isSelected) {
      return [
        new Style({ stroke: new Stroke({ color: HIGHLIGHT_STROKE_COLOR, width: 10 }) }),
        new Style({ stroke: new Stroke({ color: HIGHLIGHT_COLOR, width: 6 }) })
      ]
    }
    const strokeColor = getStringProperty(properties, 'stroke', '#ff0000')
    const strokeWidth = getNumberProperty(properties, 'stroke-width', 3)
    return [
      new Style({ stroke: new Stroke({ color: '#000000', width: strokeWidth + 2 }) }),
      new Style({ stroke: new Stroke({ color: strokeColor, width: strokeWidth }) })
    ]
  } else if (geometryType === 'MultiPolygon' || geometryType === 'Polygon') {
    const strokeColor = isSelected ? HIGHLIGHT_STROKE_COLOR : getStringProperty(properties, 'stroke', '#ff0000')
    let fillColor = isSelected ? HIGHLIGHT_COLOR : getStringProperty(properties, 'fill', '#ff0000')
    const strokeWidth = isSelected ? 4 : getNumberProperty(properties, 'stroke-width', 2)

    const fillOpacity = getNumberProperty(properties, 'fill-opacity')
    if (!isSelected && fillOpacity !== undefined) {
      const hex = fillColor.replace('#', '')
      const r = parseInt(hex.substr(0, 2), 16)
      const g = parseInt(hex.substr(2, 2), 16)
      const b = parseInt(hex.substr(4, 2), 16)
      fillColor = `rgba(${r}, ${g}, ${b}, ${fillOpacity})`
    }

    return new Style({
      stroke: new Stroke({ color: strokeColor, width: strokeWidth }),
      fill: new Fill({ color: fillColor })
    })
  }

  return new Style({
    stroke: new Stroke({ color: isSelected ? HIGHLIGHT_COLOR : '#ff0000', width: isSelected ? 4 : 2 }),
    fill: new Fill({ color: isSelected ? HIGHLIGHT_COLOR : 'rgba(255, 0, 0, 0.3)' })
  })
}

function getLabelStyle(feature: FeatureLike): Style | undefined {
  const properties = getFeatureProperties(feature)
  const geometryType = feature.getGeometry()?.getType()
  const name = getStringProperty(properties, 'name', 'Unnamed Feature')
  const isSelected = feature === selectedFeature.value

  if (!name || name === 'Unnamed Feature') {
    return undefined
  }

  const offsetY = geometryType === 'Point' || geometryType === 'MultiPoint'
    ? (isSelected ? -20 : -15)
    : (isSelected ? -15 : -10)

  return new Style({
    text: new Text({
      text: name,
      font: isSelected ? 'bold 14px Arial' : '12px Arial',
      fill: new Fill({ color: '#000000' }),
      stroke: new Stroke({ color: '#ffffff', width: isSelected ? 4 : 3 }),
      offsetY
    })
  })
}

const {
  map,
  vectorSource,
  loadFeatures: loadFeaturesIntoMap,
  fitToAllFeatures,
  zoomToFeature,
  refreshStyles,
  initMap,
  cleanup
} = useOpenLayersPreviewMap({
  getFeatureStyle,
  getLabelStyle
})

async function initializeMap(): Promise<void> {
  await initMap(mapContainer.value)
}

function zoomToSelectedFeature(): void {
  zoomToFeature(selectedFeature.value, {
    padding: [50, 50, 50, 50],
    maxZoom: 15,
    duration: 500,
    pointBufferThresholdMeters: 100,
    pointBufferMeters: 1000
  })
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

    if (props.selectedFeatureIndex >= 0 && props.selectedFeatureIndex < olFeatures.length) {
      selectedFeature.value = olFeatures[props.selectedFeatureIndex]
      zoomToSelectedFeature()
    } else {
      fitToAllFeatures({ padding: [50, 50, 50, 50], maxZoom: 15 })
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

watch(() => props.isOpen, (newVal) => {
  if (newVal) {
    void nextTick(async () => {
      isLoading.value = true
      try {
        await initializeMap()
        loadFeatures()
      } catch (error) {
        console.error('Error initializing feature map:', error)
        isLoading.value = false
      }
    })
  } else {
    cleanup()
    selectedFeature.value = null
  }
})

watch(() => props.features, () => {
  if (props.isOpen && map.value) {
    loadFeatures()
  }
}, { deep: true })

watch(() => props.selectedFeatureIndex, () => {
  if (props.isOpen && map.value && props.features.length > 0) {
    const olFeatures = vectorSource.value ? vectorSource.value.getFeatures() : []
    if (props.selectedFeatureIndex >= 0 && props.selectedFeatureIndex < olFeatures.length) {
      selectedFeature.value = olFeatures[props.selectedFeatureIndex]
      refreshStyles()
      zoomToSelectedFeature()
    }
  }
})

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
