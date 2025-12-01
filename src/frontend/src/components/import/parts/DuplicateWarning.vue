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
      validator: (value) => ['hash', 'coord', 'queue'].includes(value)
    },
    item: {
      type: Object,
      required: true
    }
  },
  computed: {
    show() {
      switch (this.type) {
        case 'hash':
          return this.item.isDuplicate && this.item.duplicateInfo && this.item.duplicateInfo.type === 'hash'
        case 'coord':
          return this.item.isCoordDuplicate
        case 'queue':
          return this.item.isQueueDuplicate
        default:
          return false
      }
    },
    title() {
      switch (this.type) {
        case 'hash':
          return 'Exact Duplicate Feature (Blocked)'
        case 'coord':
          return 'Coordinate Duplicate (Skipped by Default)'
        case 'queue':
          return 'Duplicate Feature in Import Queue'
        default:
          return ''
      }
    },
    message() {
      switch (this.type) {
        case 'hash':
          return 'This feature is identical to an existing feature in your feature store. Hash duplicates cannot be imported and are automatically skipped.'
        case 'coord':
          return 'This feature has the same coordinates as an existing feature in your feature store or another item in your import queue. It is skipped by default, but you can restore it if needed.'
        case 'queue':
          return 'This feature is identical to one in another item in your import queue and will be automatically skipped during import.'
        default:
          return ''
      }
    },
    queueDuplicateInfo() {
      return this.type === 'queue' ? this.item.queueDuplicateInfo : null
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

