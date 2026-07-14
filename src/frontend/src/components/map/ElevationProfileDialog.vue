<template>
  <div v-if="feature" class="fixed bottom-0 left-0 right-0 w-full bg-white z-30 rounded-t-xl shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.1)] border-t border-gray-200 flex flex-col max-h-[80vh] md:absolute md:bottom-16 md:left-0 md:right-0 md:h-1/3 md:max-h-none md:rounded-none md:shadow-none md:z-20 lg:bottom-0 lg:left-0 lg:right-0">
    <div class="flex flex-col h-full min-h-0">
      <!-- Header -->
      <div class="relative flex items-center justify-between px-3 py-2 md:px-4 md:py-3 border-b border-gray-200 bg-gray-50 md:bg-white rounded-t-xl md:rounded-none flex-none">
        <h3 class="text-sm md:text-lg font-semibold text-gray-900">
          <span class="md:hidden">{{ getFeatureName(feature) }}</span>
          <span class="hidden md:inline">Elevation Profile</span>
        </h3>
        <div class="hidden md:block absolute left-1/2 transform -translate-x-1/2">
          <span class="text-lg text-gray-900">{{ getFeatureName(feature) }}</span>
        </div>
        <button
          @click="$emit('close')"
          class="text-gray-400 hover:text-gray-600 transition-colors p-2 sm:p-1 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0"
          title="Close Elevation Profile"
        >
          <XMarkIcon class="w-5 h-5" />
        </button>
      </div>

      <!-- Stats -->
      <div class="stats-container px-3 py-1.5 md:px-4 md:py-2 border-b border-gray-200 bg-gray-50 flex-none min-h-[28px] relative">
        <template v-if="hasElevationData && stats">
          <!-- Mobile layout: flex row with button on right -->
          <div class="sm:hidden flex items-center justify-between w-full gap-x-3 text-[10px]">
            <div class="flex items-center gap-x-3 flex-1">
              <div v-for="stat in firstRowStats" :key="stat.label" class="flex items-center">
                <span class="text-gray-600 mr-1">{{ stat.label }}:</span>
                <span class="font-medium text-gray-900">{{ stat.value }}</span>
              </div>
            </div>
            <!-- More button (only on mobile when there are remaining stats) -->
            <button
              v-if="hasRemainingStats"
              @click.stop="toggleDropdown"
              class="flex items-center justify-center text-gray-600 hover:text-gray-900 transition-colors flex-shrink-0 p-2 -mr-2 -my-1 min-w-[44px] min-h-[44px]"
              :class="{ 'text-gray-900': showMoreStats }"
            >
              <EllipsisHorizontalIcon class="w-4 h-4" />
            </button>
          </div>
          <!-- Desktop layout: grid -->
          <div class="hidden sm:grid grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-x-3 gap-y-1 text-[10px] md:text-xs w-full">
            <div v-for="stat in allStatItems" :key="stat.label" class="flex items-center">
              <span class="text-gray-600 mr-1">{{ stat.label }}:</span>
              <span class="font-medium text-gray-900">{{ stat.value }}</span>
            </div>
          </div>
        </template>
        <template v-else-if="isUpdatingChart">
          <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-x-3 gap-y-1 text-[10px] md:text-xs w-full animate-pulse">
          <!-- Dist -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-10"></div>
          </div>
          <!-- Change -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-10 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Asc -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Des -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Min -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Max -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          <!-- Avg -->
          <div class="flex items-center">
            <div class="h-2.5 bg-gray-200 rounded w-6 mr-1"></div>
            <div class="h-2.5 bg-gray-300 rounded w-8"></div>
          </div>
          </div>
        </template>
        <template v-else>
          <div class="text-[10px] md:text-xs text-gray-400 italic">
            No stats available
          </div>
        </template>
        <!-- Mobile dropdown for remaining stats -->
        <div
          v-if="hasRemainingStats && hasElevationData && stats"
          class="stats-dropdown sm:hidden absolute top-full left-0 right-0 bg-white border-t border-gray-200 shadow-lg z-50 max-h-48 overflow-y-auto transition-all duration-200 ease-out"
          :class="showMoreStats ? 'opacity-100 translate-y-0' : 'opacity-0 -translate-y-2 pointer-events-none'"
          @click.stop
        >
          <div class="px-3 py-2 space-y-2">
            <div v-for="stat in remainingStats" :key="stat.label" class="flex items-center justify-between text-[10px]">
              <span class="text-gray-600 mr-2">{{ stat.label }}:</span>
              <span class="font-medium text-gray-900">{{ stat.value }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Chart Container, Loading Spinner, or Warning -->
      <div class="flex-1 min-h-0 overflow-y-auto overflow-x-hidden relative bg-white">
        <!-- Chart Container -->
        <div v-if="hasElevationData" ref="chartContainer" class="chart-container w-full relative">
          <canvas ref="chartCanvas"></canvas>
          <!-- Loading Spinner Overlay -->
          <div v-if="isUpdatingChart" class="absolute inset-0 flex items-center justify-center z-10 pointer-events-none">
            <Loader size="sm" layout="centered" :show-message="false" />
          </div>
        </div>
        <!-- Loading Spinner -->
        <div v-if="isUpdatingChart && feature" class="absolute inset-0 flex items-center justify-center bg-white z-20">
          <Loader size="sm" layout="centered" message="Loading..." />
        </div>
        <!-- No Data Warning -->
        <div v-else-if="!hasElevationData && feature" class="absolute inset-0 flex items-center justify-center bg-white z-10">
          <div class="text-center p-2">
            <ExclamationTriangleIcon class="w-8 h-8 md:w-12 md:h-12 text-yellow-500 mx-auto mb-2" />
            <p class="text-gray-700 font-medium text-xs md:text-base">No elevation data</p>
          </div>
        </div>
      </div>

      <!-- Label text for small screens - outside scrollable area -->
      <div v-if="hasElevationData" class="md:hidden text-center py-3 px-3 flex-none border-t border-gray-100">
        <p class="text-sm text-gray-500">Elevation Profile</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, toRef, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute } from 'vue-router'
import Loader from '@/components/parts/Loader.vue'
import { XMarkIcon, ExclamationTriangleIcon, EllipsisHorizontalIcon } from '@heroicons/vue/24/outline'
import { useElevationChart, type ElevationChartEmits } from '@/composables/useElevationChart'
import type { GeoJsonFeature } from '@/types/geospatial'

const props = withDefaults(defineProps<{
  feature?: GeoJsonFeature | null
  shareId?: string | null
  isPublicShare?: boolean
}>(), {
  feature: null,
  shareId: null,
  isPublicShare: false,
})

const emit = defineEmits<ElevationChartEmits & {
  (e: 'close'): void
}>()

const route = useRoute()

const {
  chartCanvas,
  hasElevationData,
  isUpdatingChart,
  stats,
  allStatItems,
  firstRowStats,
  remainingStats,
  hasRemainingStats,
} = useElevationChart(toRef(props, 'feature'), toRef(props, 'shareId'), toRef(props, 'isPublicShare'), emit)

// Unused by script logic, but Chart.js needs a stable container element in the DOM.
const chartContainer = ref<HTMLDivElement | null>(null)

// Mobile "more stats" dropdown UI state - pure dialog chrome, not chart/data logic.
const showMoreStats = ref(false)
let toggleDebounceTimer: ReturnType<typeof setTimeout> | null = null

function getFeatureName(feature: GeoJsonFeature | null | undefined): string {
  if (!feature) return 'Unnamed Feature'
  const properties = feature.properties as Record<string, unknown>
  const name = properties.name
  return typeof name === 'string' && name ? name : 'Unnamed Feature'
}

function handleKeyDown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    if (showMoreStats.value) {
      showMoreStats.value = false
    } else {
      emit('close')
    }
  }
}

function handleClickOutside(event: MouseEvent): void {
  if (!showMoreStats.value) return
  const target = event.target as HTMLElement | null
  const statsContainer = target?.closest('.stats-container')
  const dropdown = target?.closest('.stats-dropdown')
  if (!statsContainer && !dropdown) {
    showMoreStats.value = false
  }
}

function toggleDropdown(): void {
  if (toggleDebounceTimer) {
    clearTimeout(toggleDebounceTimer)
  }
  toggleDebounceTimer = setTimeout(() => {
    showMoreStats.value = !showMoreStats.value
    toggleDebounceTimer = null
  }, 150)
}

watch(() => props.feature, () => {
  showMoreStats.value = false
  if (toggleDebounceTimer) {
    clearTimeout(toggleDebounceTimer)
    toggleDebounceTimer = null
  }
})

watch(() => route.fullPath, () => {
  if (props.feature) {
    emit('close')
  }
})

onMounted(() => {
  document.addEventListener('keydown', handleKeyDown)
  document.addEventListener('click', handleClickOutside)
})

onBeforeUnmount(() => {
  if (toggleDebounceTimer) {
    clearTimeout(toggleDebounceTimer)
    toggleDebounceTimer = null
  }
  document.removeEventListener('keydown', handleKeyDown)
  document.removeEventListener('click', handleClickOutside)
})
</script>

<style scoped>
/* Chart container styling - fit to available space */
.chart-container {
  height: 100%;
}

canvas {
  display: block;
}
</style>
