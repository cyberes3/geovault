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
              :to="{ path: `/import/process/${queueDuplicateInfo.queue_item_id}`, query: { scrollToIndex: queueDuplicateInfo.spatial_index } }"
              class="inline-flex items-center px-3 py-1.5 border border-gray-300 rounded-md text-xs font-medium text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            >
              <MapIcon class="w-3 h-3 mr-1" />
              View matching queue feature
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
import { ExclamationTriangleIcon, MapIcon } from '@heroicons/vue/24/outline'
import type { DuplicateVerdictWire } from '@/contracts/duplicates'
import { emptyVerdict } from '@/contracts/duplicates'
import { isBlockedVerdict, isDuplicateVerdict, isRestorableVerdict } from '@/composables/import/duplicateSession'

interface QueueDuplicateInfo {
  queue_item_id: number
  spatial_index: number | null
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
    verdict: {
      type: Object as PropType<DuplicateVerdictWire>,
      default: () => emptyVerdict()
    }
  },
  computed: {
    show(): boolean {
      return isDuplicateVerdict(this.verdict)
    },
    title(): string {
      if (isBlockedVerdict(this.verdict) && this.verdict.scope === 'library') {
        return 'Exact Duplicate in Feature Library (Blocked)'
      }
      if (isBlockedVerdict(this.verdict) && this.verdict.scope === 'draft_queue') {
        return 'Exact Duplicate in Import Table (Blocked)'
      }
      if (isRestorableVerdict(this.verdict) && this.verdict.scope === 'library') {
        return 'Same Location as Feature in Library'
      }
      if (isRestorableVerdict(this.verdict) && this.verdict.scope === 'draft_queue') {
        return 'Same Location as Feature in Import Table'
      }
      return 'Duplicate Feature'
    },
    message(): string {
      if (isBlockedVerdict(this.verdict)) {
        return 'This feature is identical to an existing feature. Hash duplicates cannot be imported and are automatically blocked.'
      }
      return 'This feature has the same location as an existing feature. It is skipped by default, but you can restore it if needed.'
    },
    queueDuplicateInfo(): QueueDuplicateInfo | null {
      if (this.verdict.scope !== 'draft_queue' || this.verdict.match_queue_id == null) {
        return null
      }
      return {
        queue_item_id: this.verdict.match_queue_id,
        spatial_index: this.verdict.match_spatial_index
      }
    },
    featureStoreInfo(): FeatureStoreDuplicateInfo | null {
      if (this.verdict.scope !== 'library' || this.verdict.match_feature_store_id == null) {
        return null
      }
      return {
        feature_store_id: this.verdict.match_feature_store_id
      }
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
