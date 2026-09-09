<template>
  <BaseModal
    :is-open="isOpen"
    :title="dialogTitle"
    :max-width="shareType === 'feature' ? 'lg' : '2xl'"
    :full-screen-mobile="shareType !== 'feature'"
    @close="closeDialog"
  >
    <div class="p-6 flex flex-col h-full">
      <div class="mb-4">
        <h4 class="text-xl font-bold text-gray-900">{{ displayName }}</h4>
      </div>

      <div v-if="shareType === 'collection'" class="mb-4">
        <label class="block text-sm font-medium text-gray-700 mb-1">Collection Name</label>
        <input
          :value="displayName"
          readonly
          class="w-full px-3 py-2 border border-gray-300 rounded-md bg-gray-50 text-gray-500 cursor-not-allowed"
        />
      </div>

      <div v-if="showCreateForm" class="mb-6 space-y-4">
        <ShareEditor
          v-model:include-tags="includeTags"
          v-model:allow-downloads="allowDownloads"
        />
        <BaseButton :disabled="creating || !canCreate" @click="createShare">
          {{ creating ? 'Creating...' : 'Create Share Link' }}
        </BaseButton>
        <div v-if="management.error && !hasExistingShares" class="p-3 bg-red-50 border border-red-200 rounded-md">
          <p class="text-sm text-red-800">{{ management.error }}</p>
        </div>
        <div v-if="successMessage" class="p-3 bg-green-50 border border-green-200 rounded-md">
          <p class="text-sm text-green-800">{{ successMessage }}</p>
        </div>
      </div>

      <section class="bg-white rounded-lg flex-1 min-h-0 flex flex-col">
        <h4 v-if="shareType !== 'feature'" class="text-base font-semibold text-gray-900 mb-3">Existing Share Links</h4>
        <div class="overflow-y-auto flex-1 min-h-[100px]">
          <div v-if="management.loading" class="text-center py-4">
            <Loader size="sm" layout="centered" message="Loading shares..." />
          </div>
          <div v-else-if="!hasExistingShares" class="text-center py-8 text-gray-500">
            <p class="text-sm">No share link created yet for this {{ shareType }}.</p>
          </div>
          <div v-else class="space-y-3">
            <ShareLinkCard
              v-for="share in management.shares"
              :key="share.share_id"
              :share="share"
              :copied="management.copiedShareId === share.share_id"
              :revoking="management.deletingShareId === share.share_id"
              @copy="copyShare"
              @revoke="revokeShare"
            >
              <template v-if="share.share_type === 'feature'" #editor>
                <div class="mt-4">
                  <ShareEditor
                    :include-tags="share.include_tags"
                    :allow-downloads="share.allow_downloads"
                    :disabled="management.updatingShareId === share.share_id"
                    @update:include-tags="patchShare(share, 'include_tags', $event)"
                    @update:allow-downloads="patchShare(share, 'allow_downloads', $event)"
                  />
                </div>
              </template>
            </ShareLinkCard>
          </div>
        </div>
      </section>
    </div>
  </BaseModal>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import type { CreateSharePayload, ShareListItem, ShareType } from '@/contracts/share'
import { getApiErrorMessage } from '@/utils/apiError'
import { useShareManagement } from '@/composables/useShareManagement'
import BaseButton from './BaseButton.vue'
import BaseModal from './BaseModal.vue'
import Loader from './Loader.vue'
import ShareEditor from './ShareEditor.vue'
import ShareLinkCard from './ShareLinkCard.vue'

export interface ShareDialogItem {
  tag?: string;
  id?: string | number;
  collection_id?: string | number;
  name?: string;
  properties?: { name?: string; database_id?: string | number; id?: string | number };
}

export default defineComponent({
  name: 'ShareDialog',
  components: {
    BaseButton,
    BaseModal,
    Loader,
    ShareEditor,
    ShareLinkCard,
  },
  props: {
    isOpen: { type: Boolean, required: true },
    shareType: {
      type: String as PropType<Extract<ShareType, 'tag' | 'collection' | 'feature'>>,
      required: true,
    },
    item: { type: Object as PropType<ShareDialogItem>, required: true },
  },
  emits: ['close'],
  data() {
    return {
      management: useShareManagement(),
      includeTags: false,
      allowDownloads: false,
      creating: false,
      successMessage: null as string | null,
    }
  },
  computed: {
    dialogTitle(): string {
      return {
        tag: 'Share Tag',
        collection: 'Share Collection',
        feature: 'Share Feature',
      }[this.shareType]
    },
    displayName(): string {
      if (this.shareType === 'tag') {
        return this.item.tag || 'Unknown Tag'
      }
      if (this.shareType === 'collection') {
        return this.item.name || 'Unknown Collection'
      }
      return this.item.properties?.name || 'Unnamed Feature'
    },
    itemId(): string | number | null {
      if (this.shareType === 'collection') {
        return this.item.id ?? this.item.collection_id ?? null
      }
      if (this.shareType === 'feature') {
        return this.item.properties?.database_id ?? this.item.id ?? this.item.properties?.id ?? null
      }
      return null
    },
    canCreate(): boolean {
      if (this.shareType === 'tag') {
        return Boolean(this.item.tag)
      }
      return this.itemId != null
    },
    hasExistingShares(): boolean {
      return this.management.shares.length > 0
    },
    showCreateForm(): boolean {
      return this.shareType !== 'feature' || !this.hasExistingShares
    },
  },
  watch: {
    isOpen: {
      immediate: true,
      async handler(open: boolean) {
        if (!open) {
          return
        }
        this.includeTags = false
        this.allowDownloads = false
        this.successMessage = null
        await this.loadShares()
      },
    },
  },
  methods: {
    closeDialog() {
      this.$emit('close')
    },
    async loadShares() {
      if (this.shareType === 'tag') {
        const tag = (this.item.tag != null ? String(this.item.tag) : '').trim()
        await this.management.load({ type: 'tag', tag })
        return
      }
      if (this.shareType === 'collection') {
        const collectionId = this.itemId != null ? String(this.itemId) : ''
        if (!collectionId) {
          this.management.shares = []
          return
        }
        await this.management.load({ type: 'collection', collection_id: collectionId })
        return
      }
      if (this.itemId == null) {
        this.management.error = 'Invalid feature: missing feature ID'
        this.management.shares = []
        return
      }
      await this.management.load({ type: 'feature', feature_id: this.itemId })
    },
    async createShare() {
      this.creating = true
      this.successMessage = null
      try {
        const payload = {
          tag: { share_type: 'tag' as const, tag: String(this.item.tag ?? ''), include_tags: this.includeTags, allow_downloads: this.allowDownloads },
          collection: { share_type: 'collection' as const, collection_id: String(this.itemId ?? ''), include_tags: this.includeTags, allow_downloads: this.allowDownloads },
          feature: { share_type: 'feature' as const, feature_id: this.itemId ?? '', include_tags: this.includeTags, allow_downloads: this.allowDownloads },
        }[this.shareType] as CreateSharePayload
        await this.management.create(payload)
        this.successMessage = 'Share link created successfully!'
        window.setTimeout(() => {
          this.successMessage = null
        }, 3000)
      } catch (error) {
        this.management.error = getApiErrorMessage(error, 'Failed to create share. Please try again.')
      } finally {
        this.creating = false
      }
    },
    async copyShare(share: ShareListItem) {
      await this.management.copy(share.url, share.share_id)
    },
    async revokeShare(share: ShareListItem) {
      try {
        await this.management.remove(share.share_id)
        this.successMessage = 'Share deleted successfully!'
        if (this.shareType === 'feature') {
          window.setTimeout(() => this.closeDialog(), 400)
        }
      } catch (error) {
        this.management.error = getApiErrorMessage(error, 'Failed to delete share. Please try again.')
      }
    },
    async patchShare(share: ShareListItem, field: 'allow_downloads' | 'include_tags', value: boolean) {
      try {
        await this.management.update(share.share_id, { [field]: value })
      } catch (error) {
        this.management.error = getApiErrorMessage(error, 'Failed to update share setting. Please try again.')
      }
    },
  },
})
</script>
