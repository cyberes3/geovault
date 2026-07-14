<template>
  <BaseModal :is-open="true" title="Configure Hauk" :on-top="true" @close="$emit('close')">
    <div class="p-4 space-y-6 text-sm">
      <p class="text-gray-900">
        In the Hauk app, follow these steps to send your location to this tracker. Hauk uses the same protocol as GeoVault Tracker’s Hauk-compatible API.
      </p>
      <div class="flex flex-wrap justify-center items-center gap-4">
        <a
          href="https://play.google.com/store/apps/details?id=info.varden.hauk"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-block"
          aria-label="Get Hauk on Google Play"
        >
          <img
            :src="googlePlayBadgeUrl"
            alt="Get it on Google Play"
            class="h-10 w-auto max-w-[180px] object-contain"
          />
        </a>
        <a
          href="https://apps.apple.com/app/hauk/id6738009304"
          target="_blank"
          rel="noopener noreferrer"
          class="inline-block"
          aria-label="Download Hauk on the App Store"
        >
          <img
            :src="appStoreBadgeUrl"
            alt="Download on the App Store"
            class="h-10 w-auto max-w-[180px] object-contain"
          />
        </a>
      </div>
      <div class="space-y-4">
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">1</span>
          <div class="flex-1 min-w-0">
            <p class="font-medium text-gray-900">Server URL</p>
            <p class="text-gray-900 mb-1">In Hauk, open settings and set <strong>Server</strong> to:</p>
            <div class="flex gap-2">
              <input :value="serverUrl" readonly class="flex-1 px-2 py-1.5 text-sm border rounded bg-gray-50 min-w-0" />
              <CopyTextButton :text="serverUrl" size="md" />
            </div>
            <p class="text-gray-700 text-xs mt-1">Use exactly this URL (with https). Do not add a path or trailing slash.</p>
          </div>
        </div>
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">2</span>
          <div class="flex-1 min-w-0">
            <p class="font-medium text-gray-900">Username</p>
            <p class="text-gray-900 mb-1">Set <strong>Username</strong> to your GeoVault account email:</p>
            <div class="flex gap-2">
              <input :value="userEmail" readonly class="flex-1 px-2 py-1.5 text-sm border rounded bg-gray-50 min-w-0" />
              <CopyTextButton :text="userEmail" size="md" />
            </div>
          </div>
        </div>
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">3</span>
          <div class="flex-1 min-w-0">
            <p class="font-medium text-gray-900">Password</p>
            <p class="text-gray-900 mb-1">Set <strong>Password</strong> to this tracker’s Hauk password:</p>
            <div class="flex gap-2">
              <input :value="haukPassword" readonly class="flex-1 px-2 py-1.5 text-sm border rounded bg-gray-50 min-w-0" />
              <CopyTextButton :text="haukPassword" size="md" />
            </div>
            <p class="text-gray-700 text-xs mt-1">This is the Hauk-only password for this tracker, not your GeoVault login. You can regenerate it from the tracker settings if needed.</p>
          </div>
        </div>
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">4</span>
          <div>
            <p class="font-medium text-gray-900">Start Sharing</p>
            <p class="text-gray-900">In Hauk, start a share session. Your live position will appear on this tracker’s map in GeoVault. Stop sharing in Hauk when you are done.</p>
          </div>
        </div>
      </div>
    </div>
  </BaseModal>
</template>

<script lang="ts">
import { defineComponent } from 'vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import CopyTextButton from './CopyTextButton.vue';
import appStoreBadgeUrl from '@/assets/download-on-app-store.svg';
import googlePlayBadgeUrl from '@/assets/get-it-on-google-play.svg';

export default defineComponent({
  name: 'HaukInstructionsModal',
  components: { BaseModal, CopyTextButton },
  props: {
    serverUrl: { type: String, default: '' },
    userEmail: { type: String, default: '' },
    haukPassword: { type: String, default: '' }
  },
  emits: ['close'],
  setup() {
    return { appStoreBadgeUrl, googlePlayBadgeUrl };
  }
});
</script>
