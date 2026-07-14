<template>
  <div v-if="show" :class="containerClasses">
    <div class="flex items-start">
      <div class="flex-shrink-0">
        <ExclamationTriangleIcon :class="iconClasses" />
      </div>
      <div class="ml-3 flex-1">
        <h3 :class="titleClasses">{{ title }}</h3>
        <div :class="contentClasses">
          <p>{{ message }}</p>
          <div v-if="queueDuplicateInfo" class="mt-2">
            <router-link
              :to="{ path: `/import/process/${queueDuplicateInfo.queue_item_id}`, query: { scrollToIndex: queueDuplicateInfo.global_index } }"
              class="inline-flex items-center px-3 py-1.5 border border-gray-300 rounded-md text-xs font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            >
              <MapIcon class="w-3 h-3 mr-1" />
              View in "{{ queueDuplicateInfo.queue_item_filename }}"
            </router-link>
          </div>
          <div v-if="featureStoreInfo" class="mt-2">
            <router-link
              :to="{ path: '/map', query: { featureId: featureStoreInfo.feature_store_id } }"
              class="inline-flex items-center px-3 py-1.5 border border-gray-300 rounded-md text-xs font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            >
              <MapIcon class="w-3 h-3 mr-1" />
              View on Map
            </router-link>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import { ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import { MapIcon } from '@heroicons/vue/24/outline'
import type { ImportFeatureItem } from '@/assets/js/types/import-types'

type DuplicateWarningType = 'feature_store_hash' | 'feature_store_geometry' | 'cross_queue_hash' | 'cross_queue_geometry'

interface QueueDuplicateInfo {
  hash?: string
  global_index?: number
  queue_item_id: number
  queue_item_filename?: string
}

interface FeatureStoreDuplicateInfo {
  feature_store_id: number
}

export default defineComponent({
  name: 'DuplicateWarning',
  components: {
    ExclamationTriangleIcon,
    MapIcon
  },
  props: {
    type: {
      type: String as PropType<DuplicateWarningType>,
      required: true,
      validator: (value: string) => [
        'feature_store_hash',
        'feature_store_geometry',
        'cross_queue_hash',
        'cross_queue_geometry'
      ].includes(value)
    },
    item: {
      type: Object as PropType<ImportFeatureItem>,
      required: true
    }
  },
  computed: {
    show(): boolean {
      switch (this.type) {
        case 'feature_store_hash':
          return this.item.isFeatureStoreHashDup ?? false
        case 'feature_store_geometry':
          return this.item.isFeatureStoreGeometryDup ?? false
        case 'cross_queue_hash':
          return this.item.isCrossQueueHashDup ?? false
        case 'cross_queue_geometry':
          return this.item.isCrossQueueGeometryDup ?? false
        default:
          return false
      }
    },
    title(): string {
      switch (this.type) {
        case 'feature_store_hash':
          return 'Exact Duplicate in Feature Library (Blocked)'
        case 'feature_store_geometry':
          return 'Same Location as Feature in Library'
        case 'cross_queue_hash':
          return 'Exact Duplicate in Import Table (Blocked)'
        case 'cross_queue_geometry':
          return 'Same Location as Feature in Import Table'
        default:
          return ''
      }
    },
    message(): string {
      switch (this.type) {
        case 'feature_store_hash':
          return 'This feature is identical to an existing feature in your feature library. Hash duplicates cannot be imported and are automatically blocked.'
        case 'feature_store_geometry':
          return 'This feature has the same location as an existing feature in your feature library. It is skipped by default, but you can restore it if needed.'
        case 'cross_queue_hash':
          return 'This feature is identical to one in another item in your queue and will be automatically blocked during import.'
        case 'cross_queue_geometry':
          return 'This feature has the same location as a feature in another item in your import table. It is skipped by default, but you can restore it if needed.'
        default:
          return ''
      }
    },
    queueDuplicateInfo(): QueueDuplicateInfo | null {
      // For cross-queue types, return queue item info
      if (this.type === 'cross_queue_hash' || this.type === 'cross_queue_geometry') {
        const info = this.item.duplicateInfo
        if (info?.queue_item_id != null) {
          return {
            hash: info.hash,
            global_index: info.global_index,
            queue_item_id: info.queue_item_id,
            queue_item_filename: info.queue_item_filename
          };
        }
      }
      return null
    },
    featureStoreInfo(): FeatureStoreDuplicateInfo | null {
      // For feature store types, return feature store ID for map link
      if (this.type === 'feature_store_hash' || this.type === 'feature_store_geometry') {
        const info = this.item.duplicateInfo
        if (info?.feature_store_id != null) {
          return {
            feature_store_id: info.feature_store_id
          };
        }
      }
      return null;
    },
    containerClasses(): string {
      return 'mb-4 p-4 rounded-md bg-yellow-100 border border-yellow-300'
    },
    iconClasses(): string {
      return 'h-5 w-5 text-yellow-600'
    },
    titleClasses(): string {
      return 'text-sm font-medium text-yellow-800'
    },
    contentClasses(): string {
      return 'mt-2 text-sm text-yellow-700'
    }
  }
})
</script>

