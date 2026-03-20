<template>
  <BaseModal :is-open="true" title="Configure Hauk" :on-top="true" @close="$emit('close')">
    <div class="p-4 space-y-6 text-sm">
      <p class="text-gray-900">
        In the Hauk app, follow these steps to send your location to this tracker. Hauk uses the same protocol as GeoVault Tracker’s Hauk-compatible API.
      </p>
      <div class="flex justify-center">
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
      </div>
      <div class="space-y-4">
        <div class="flex gap-3">
          <span class="flex-shrink-0 w-6 h-6 rounded-full bg-blue-100 text-blue-800 flex items-center justify-center font-medium">1</span>
          <div class="flex-1 min-w-0">
            <p class="font-medium text-gray-900">Server URL</p>
            <p class="text-gray-900 mb-1">In Hauk, open settings and set <strong>Server</strong> to:</p>
            <div class="flex gap-2">
              <input :value="serverUrl" readonly class="flex-1 px-2 py-1.5 text-sm border rounded bg-gray-50 min-w-0" />
              <button type="button" class="px-2 py-1.5 bg-gray-200 rounded text-sm flex-shrink-0" @click="copy(serverUrl)">Copy</button>
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
              <button type="button" class="px-2 py-1.5 bg-gray-200 rounded text-sm flex-shrink-0" @click="copy(userEmail)">Copy</button>
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
              <button type="button" class="px-2 py-1.5 bg-gray-200 rounded text-sm flex-shrink-0" @click="copy(haukPassword)">Copy</button>
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

<script>
import BaseModal from 'platform/components/parts/BaseModal.vue';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import googlePlayBadgeUrl from '@/assets/get-it-on-google-play.svg';

export default {
  name: 'HaukInstructionsModal',
  components: { BaseModal, BaseButton },
  props: {
    serverUrl: { type: String, default: '' },
    userEmail: { type: String, default: '' },
    haukPassword: { type: String, default: '' }
  },
  emits: ['close'],
  setup(props) {
    function copy(text) {
      const toast = window.gv_core?.GeoVault?.toast;
      const showCopied = () => toast && toast.success('Copied');
      if (navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(text).then(showCopied).catch(() => copyFallback(text, showCopied));
      } else {
        copyFallback(text, showCopied);
      }
    }
    function copyFallback(text, onSuccess) {
      const el = document.createElement('textarea');
      el.value = text;
      el.setAttribute('readonly', '');
      el.style.position = 'fixed';
      el.style.left = '-9999px';
      el.style.top = '0';
      document.body.appendChild(el);
      el.select();
      el.setSelectionRange(0, text.length);
      try {
        if (document.execCommand('copy')) onSuccess?.();
      } catch (e) {
        // ignore
      }
      document.body.removeChild(el);
    }
    return { copy, googlePlayBadgeUrl };
  }
};
</script>
