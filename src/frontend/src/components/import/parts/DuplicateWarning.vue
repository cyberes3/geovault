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
              :to="{ path: `/import/process/${queueDuplicateInfo.queue_item_id}`, query: { featureHash: queueDuplicateInfo.hash } }"
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

<script>
import { ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import { MapIcon } from '@heroicons/vue/24/outline'

export default {
  name: 'DuplicateWarning',
  components: {
    ExclamationTriangleIcon,
    MapIcon
  },
  props: {
    type: {
      type: String,
      required: true,
      validator: (value) => [
        'feature_store_hash',
        'feature_store_geometry',
        'cross_queue_hash',
        'cross_queue_geometry'
      ].includes(value)
    },
    item: {
      type: Object,
      required: true
    }
  },
  computed: {
    show() {
      switch (this.type) {
        case 'feature_store_hash':
          return this.item.isFeatureStoreHashDup
        case 'feature_store_geometry':
          return this.item.isFeatureStoreGeometryDup
        case 'cross_queue_hash':
          return this.item.isCrossQueueHashDup
        case 'cross_queue_geometry':
          return this.item.isCrossQueueGeometryDup
        default:
          return false
      }
    },
    title() {
      switch (this.type) {
        case 'feature_store_hash':
          return 'Exact Duplicate in Feature Library (Blocked)'
        case 'feature_store_geometry':
          return 'Same Location as Feature in Library'
        case 'cross_queue_hash':
          return 'Exact Duplicate in Import Queue (Blocked)'
        case 'cross_queue_geometry':
          return 'Same Location as Feature in Import Queue'
        default:
          return ''
      }
    },
    message() {
      switch (this.type) {
        case 'feature_store_hash':
          return 'This feature is identical to an existing feature in your feature library. Hash duplicates cannot be imported and are automatically blocked.'
        case 'feature_store_geometry':
          return 'This feature has the same location as an existing feature in your feature library. It is skipped by default, but you can restore it if needed.'
        case 'cross_queue_hash':
          return 'This feature is identical to one in another item in your import queue and will be automatically blocked during import.'
        case 'cross_queue_geometry':
          return 'This feature has the same location as a feature in another item in your import queue. It is skipped by default, but you can restore it if needed.'
        default:
          return ''
      }
    },
    queueDuplicateInfo() {
      // For cross-queue types, return queue item info
      if (this.type === 'cross_queue_hash' || this.type === 'cross_queue_geometry') {
        if (this.item.duplicateInfo && this.item.duplicateInfo.queue_item_id) {
          return {
            queue_item_id: this.item.duplicateInfo.queue_item_id,
            queue_item_filename: this.item.duplicateInfo.queue_item_filename
          };
        }
      }
      return null
    },
    featureStoreInfo() {
      // For feature store types, return feature store ID for map link
      if (this.type === 'feature_store_hash' || this.type === 'feature_store_geometry') {
        if (this.item.duplicateInfo && this.item.duplicateInfo.feature_store_id) {
          return {
            feature_store_id: this.item.duplicateInfo.feature_store_id
          };
        }
      }
      return null;
    },
    containerClasses() {
      return 'mb-4 p-4 rounded-md bg-yellow-100 border border-yellow-300'
    },
    iconClasses() {
      return 'h-5 w-5 text-yellow-600'
    },
    titleClasses() {
      return 'text-sm font-medium text-yellow-800'
    },
    contentClasses() {
      return 'mt-2 text-sm text-yellow-700'
    }
  }
}
</script>

