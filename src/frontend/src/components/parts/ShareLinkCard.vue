<template>
  <div class="border border-gray-200 rounded-lg p-4 bg-stone-50">
    <div class="flex items-start justify-between">
      <div class="flex-1 min-w-0">
        <div v-if="$slots.badge" class="mb-2 flex items-center gap-2">
          <slot name="badge"></slot>
        </div>
        <div class="mb-2">
          <label class="block text-xs font-medium text-gray-700 mb-1">Share Link</label>
          <div class="flex items-center space-x-2">
            <div class="relative flex-1 min-w-0">
              <input
                :value="fullUrl"
                readonly
                class="w-full px-3 py-2 text-sm border border-gray-300 rounded-md bg-gray-50 text-gray-700 font-mono overflow-hidden cursor-pointer select-none focus:outline-none focus:ring-2 focus:ring-blue-500"
                :title="copied ? 'Copied!' : 'Click to Copy'"
                @click="copyLink"
              />
              <div
                v-if="copied"
                class="absolute -top-8 left-1/2 transform -translate-x-1/2 bg-gray-900 text-white text-xs rounded px-2 py-1 whitespace-nowrap pointer-events-none z-10"
              >
                Copied!
                <div class="absolute top-full left-1/2 transform -translate-x-1/2 border-4 border-transparent border-t-gray-900"></div>
              </div>
            </div>
            <BaseButton
              variant="white"
              size="sm"
              :title="copied ? 'Copied!' : 'Copy Link'"
              @click="copyLink"
            >
              <ClipboardDocumentIcon v-if="!copied" class="w-4 h-4" />
              <CheckIcon v-else class="w-4 h-4 text-green-600" />
            </BaseButton>
          </div>
        </div>

        <slot name="editor"></slot>

        <div class="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-xs text-gray-600">
          <div class="flex items-center gap-1">
            <span class="font-medium">Created:</span>
            <span>{{ formatDate(share.created_at) }}</span>
          </div>
          <div class="flex items-center gap-1">
            <span class="font-medium">Access Count:</span>
            <span>{{ share.access_count }}</span>
          </div>
          <div class="flex items-center gap-1">
            <span class="font-medium">Download:</span>
            <span>{{ share.allow_downloads ? 'Yes' : 'No' }}</span>
          </div>
          <div class="flex items-center gap-1">
            <span class="font-medium">Include Tags:</span>
            <span>{{ share.include_tags ? 'Yes' : 'No' }}</span>
          </div>
        </div>
      </div>

      <button
        :disabled="revoking"
        class="ml-3 flex-shrink-0 p-2 text-red-600 hover:text-red-800 hover:bg-red-50 rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
        :title="revoking ? 'Deleting...' : 'Delete Share'"
        @click="confirmOpen = true"
      >
        <TrashIcon class="w-5 h-5" />
      </button>
    </div>

    <BaseModal :is-open="confirmOpen" title="Delete Share Link" max-width="sm" @close="confirmOpen = false">
      <p class="text-sm text-gray-700 p-6">Are you sure you want to delete this share link?</p>
      <template #footer>
        <BaseButton variant="white" @click="confirmOpen = false">Cancel</BaseButton>
        <BaseButton color="red" :disabled="revoking" @click="confirmRevoke">Delete</BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import { CheckIcon, ClipboardDocumentIcon, TrashIcon } from '@heroicons/vue/24/outline'
import type { ShareListItem } from '@/contracts/share'
import { formatDate } from '@/utils/dateUtils'
import { absoluteShareUrl } from '@/composables/useShareManagement'
import BaseButton from './BaseButton.vue'
import BaseModal from './BaseModal.vue'

export default defineComponent({
  name: 'ShareLinkCard',
  components: {
    BaseButton,
    BaseModal,
    CheckIcon,
    ClipboardDocumentIcon,
    TrashIcon,
  },
  props: {
    share: { type: Object as PropType<ShareListItem>, required: true },
    copied: { type: Boolean, default: false },
    revoking: { type: Boolean, default: false },
  },
  emits: ['copy', 'revoke'],
  data() {
    return { confirmOpen: false }
  },
  computed: {
    fullUrl(): string {
      return absoluteShareUrl(this.share.url)
    },
  },
  methods: {
    formatDate,
    copyLink() {
      this.$emit('copy', this.share)
    },
    confirmRevoke() {
      this.confirmOpen = false
      this.$emit('revoke', this.share)
    },
  },
})
</script>
