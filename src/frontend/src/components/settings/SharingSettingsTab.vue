<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-6">
    <h2 class="text-lg font-semibold text-gray-900 mb-4">Shared Links</h2>

    <div v-if="management.loading" class="text-center py-8">
      <Loader size="md" layout="centered" message="Loading shares..." />
    </div>

    <div v-else-if="management.error" class="p-4 bg-red-50 border border-red-200 rounded-md">
      <p class="text-sm text-red-800">{{ management.error }}</p>
      <button
        class="mt-2 text-sm text-red-600 hover:text-red-800 underline"
        title="Retry Loading Shares"
        @click="loadShares"
      >
        Try again
      </button>
    </div>

    <div v-else-if="mapShares.length === 0" class="text-center py-8 text-gray-500">
      <ShareIcon class="mx-auto h-12 w-12 text-gray-400 mb-4" />
      <p class="text-sm">No share links created yet.</p>
      <p class="text-xs mt-1">Create share links from a feature, tag, or collection.</p>
    </div>

    <div v-else class="space-y-3">
      <ShareLinkCard
        v-for="share in mapShares"
        :key="share.share_id"
        :share="share"
        :copied="management.copiedShareId === share.share_id"
        :revoking="management.deletingShareId === share.share_id"
        @copy="copyShare"
        @revoke="revokeShare"
      >
        <template #badge>
          <span
            v-if="share.share_type === 'tag'"
            class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-purple-100 text-purple-800"
            title="Tag Share"
          >
            <TagIcon class="w-3 h-3 mr-1" />
            Tag
          </span>
          <span
            v-else-if="share.share_type === 'collection'"
            class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-green-100 text-green-800"
            title="Collection Share"
          >
            <FolderIcon class="w-3 h-3 mr-1" />
            Collection
          </span>
          <span
            v-else-if="share.share_type === 'feature'"
            class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-blue-100 text-blue-800"
            title="Feature Share"
          >
            <MapPinIcon class="w-3 h-3 mr-1" />
            Feature
          </span>
          <span class="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium bg-blue-100 text-blue-700">
            {{ shareDisplayName(share) }}
          </span>
        </template>
      </ShareLinkCard>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue'
import { FolderIcon, MapPinIcon, ShareIcon, TagIcon } from '@heroicons/vue/24/outline'
import type { ShareListItem } from '@/contracts/share'
import { shareDisplayName, useShareManagement } from '@/composables/useShareManagement'
import { toastApiError } from '@/utils/apiError'
import Loader from '@/components/parts/Loader.vue'
import ShareLinkCard from '@/components/parts/ShareLinkCard.vue'

export default defineComponent({
  name: 'SharingSettingsTab',
  components: {
    FolderIcon,
    Loader,
    MapPinIcon,
    ShareIcon,
    ShareLinkCard,
    TagIcon,
  },
  data() {
    return {
      management: useShareManagement(),
    }
  },
  computed: {
    mapShares(): ShareListItem[] {
      return this.management.shares.filter((share) => (
        share.share_type === 'tag' || share.share_type === 'collection' || share.share_type === 'feature'
      ))
    },
  },
  activated() {
    void this.loadShares()
  },
  methods: {
    shareDisplayName,
    async loadShares(): Promise<void> {
      try {
        await this.management.load()
      } catch {
        // error is stored on the management helper
      }
    },
    async copyShare(share: ShareListItem): Promise<void> {
      await this.management.copy(share.url, share.share_id)
    },
    async revokeShare(share: ShareListItem): Promise<void> {
      try {
        await this.management.remove(share.share_id)
      } catch (error) {
        toastApiError(error, 'Failed to delete share. Please try again.')
      }
    },
  },
})
</script>
