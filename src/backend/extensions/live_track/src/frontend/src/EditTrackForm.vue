<template>
  <div class="p-4 space-y-4">
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Name <span class="text-red-500">*</span></label>
      <input
        :value="name"
        type="text"
        placeholder="Tracker name"
        :readonly="!isOwner"
        class="w-full border border-gray-300 px-3 py-2 rounded-lg"
        :class="{ 'bg-gray-100': !isOwner }"
        @input="$emit('update:name', ($event.target && $event.target.value) || '')"
      />
      <p v-if="error" class="text-sm text-red-600">{{ error }}</p>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Color</label>
      <div class="flex items-center gap-2">
        <ColorPickerElement :model-value="color" :disabled="!isOwner" @update:model-value="$emit('update:color', $event)" />
        <button
          v-if="isOwner"
          type="button"
          title="Reset to Default Color from Name"
          class="p-2 rounded-lg text-gray-500 hover:bg-gray-100 hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
          @click="$emit('reset-color')"
        >
          <ArrowPathIcon class="h-5 w-5" />
        </button>
      </div>
    </div>
    <template v-if="isOwner">
      <SharingSection
        variant="track"
        :visibility="visibility"
        :shared-with-select-items="availableUsersForSelect"
        :shared-with-select-values="sharedWithEmailsForSelect"
        :loading-users="loadingUsers"
        :world-share-enabled="worldShareEnabled"
        :full-world-share-url="fullWorldShareUrl"
        :share-params-with-recipients="shareParamsWithRecipients"
        :share-params-with-world="shareParamsWithWorld"
        :allow-group-reshare="allowGroupReshare"
        :disabled="!isOwner"
        @update:visibility="$emit('update:visibility', $event)"
        @update:shared-with-emails="$emit('update:sharedWithEmails', $event)"
        @update:world-share-enabled="$emit('update:worldShareEnabled', $event)"
        @update:share-params-with-recipients="$emit('update:shareParamsWithRecipients', $event)"
        @update:share-params-with-world="$emit('update:shareParamsWithWorld', $event)"
        @update:allow-group-reshare="$emit('update:allowGroupReshare', $event)"
      />
    </template>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Visibility Window</label>
      <select
        :value="recentDataWindow"
        :disabled="!isOwner"
        class="select-custom w-full border border-gray-300 px-3 py-2 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500"
        :class="{ 'bg-gray-100': !isOwner }"
        @change="$emit('update:recentDataWindow', ($event.target && $event.target.value) || '')"
      >
        <option value="">All History</option>
        <option value="1min">Last Minute</option>
        <option value="1h">Last Hour</option>
        <option value="1d">Last Day</option>
        <option value="1w">Last Week</option>
        <option value="1m">Last Month</option>
        <option value="session">Last Session</option>
      </select>
    </div>
    <div v-if="isOwner" class="space-y-2">
      <div class="flex items-center gap-3">
        <ToggleButton
          :model-value="hiddenInList"
          label="Hide in List"
          size="md"
          @update:model-value="$emit('update:hidden-in-list', $event)"
        />
        <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="$emit('update:hidden-in-list', !hiddenInList)">Hide in List</label>
      </div>
      <p class="text-xs text-gray-500">When on, this tracker is hidden from the sidebar list. You can unhide it in Settings.</p>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">API Password</label>
      <div class="flex gap-2">
        <input :value="trackerSecret" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <CopyTextButton :text="trackerSecret" size="sm" />
      </div>
    </div>
    <div class="grid grid-cols-2 gap-3 pb-2">
      <BaseButton v-if="isOwner" variant="white" size="sm" @click="$emit('open-instructions')">GPSLogger Setup</BaseButton>
      <BaseButton v-if="isOwner && haukDomain" variant="white" size="sm" @click="$emit('open-hauk-instructions')">Hauk Setup</BaseButton>
      <BaseButton variant="white" size="sm" @click="$emit('download-kml')">Download KML</BaseButton>
      <BaseButton v-if="isOwner" variant="white" size="sm" :disabled="regeneratingTokens" @click="$emit('regenerate-tokens')">
        <Loader v-if="regeneratingTokens" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Regenerate All Tokens
      </BaseButton>
      <BaseButton v-if="isOwner" variant="secondary" color="red" size="sm" class="col-span-2 w-full" :disabled="clearHistoryDisabled" @click="$emit('clear-history')">
        <Loader v-if="clearing" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Clear Tracker
      </BaseButton>
      <BaseButton v-if="!isOwner" variant="secondary" color="gray" size="sm" :disabled="unsubscribing" @click="$emit('unsubscribe')">
        <Loader v-if="unsubscribing" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Remove From My List
      </BaseButton>
      <BaseButton v-if="isOwner" variant="secondary" color="red" size="sm" class="col-span-2 w-full" :disabled="deleting" @click="$emit('delete')">
        <Loader v-if="deleting" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Delete
      </BaseButton>
    </div>
  </div>
</template>

<script>
import { ref, watch, computed } from 'vue';
import { ArrowPathIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import CopyTextButton from './CopyTextButton.vue';
import SharingSection from './SharingSection.vue';

export default {
  name: 'EditTrackForm',
  components: { ArrowPathIcon, BaseButton, CopyTextButton, SharingSection },
  props: {
    track: { type: Object, default: null },
    name: { type: String, default: '' },
    color: { type: String, default: '#6C93DE' },
    recentDataWindow: { type: String, default: '' },
    visibility: { type: String, default: 'private' },
    shareParamsWithRecipients: { type: Boolean, default: false },
    sharedWithEmails: { type: Array, default: () => [] },
    isOwner: { type: Boolean, default: true },
    error: { type: String, default: '' },
    deleting: { type: Boolean, default: false },
    clearing: { type: Boolean, default: false },
    unsubscribing: { type: Boolean, default: false },
    /** When true, Clear history button is disabled (e.g. after clear succeeded until sidebar is closed). */
    clearHistoryDisabled: { type: Boolean, default: false },
    regeneratingTokens: { type: Boolean, default: false },
    trackerSecret: { type: String, default: '' },
    worldShareEnabled: { type: Boolean, default: false },
    worldShareUrl: { type: String, default: '' },
    shareParamsWithWorld: { type: Boolean, default: false },
    hiddenInList: { type: Boolean, default: false },
    allowGroupReshare: { type: Boolean, default: false },
    /** When set, the Hauk Setup button is shown (admin configured hauk_domain). */
    haukDomain: { type: String, default: '' }
  },
  emits: ['update:name', 'update:color', 'update:recentDataWindow', 'update:visibility', 'update:shareParamsWithRecipients', 'update:shareParamsWithWorld', 'update:sharedWithEmails', 'update:worldShareEnabled', 'update:allowGroupReshare', 'update:hidden-in-list', 'reset-color', 'open-instructions', 'open-hauk-instructions', 'download-kml', 'clear-history', 'regenerate-tokens', 'delete', 'unsubscribe'],
  setup(props, { emit }) {
    const fullWorldShareUrl = computed(() => {
      const path = props.worldShareUrl || '';
      if (!path) return '';
      if (typeof window !== 'undefined' && window.location && window.location.origin) {
        return `${window.location.origin}${path}`;
      }
      return path;
    });
    const availableUsers = ref([]);
    const loadingUsers = ref(false);

    async function fetchUsers() {
      loadingUsers.value = true;
      try {
        const res = await fetch('/api/users/', { credentials: 'include' });
        const data = await res.json();
        availableUsers.value = Array.isArray(data?.users) ? data.users : [];
      } catch {
        availableUsers.value = [];
      } finally {
        loadingUsers.value = false;
      }
    }

    const availableUsersForSelect = computed(() =>
      (availableUsers.value || []).map((u) => ({ value: (u.email || '').toLowerCase(), label: u.email || '' })).filter((u) => u.value)
    );
    const sharedWithEmailsForSelect = computed(() =>
      (props.sharedWithEmails || []).map((e) => String(e || '').toLowerCase()).filter(Boolean)
    );

    watch(
      () => props.isOwner && props.visibility === 'shared',
      (show) => { if (show) fetchUsers(); },
      { immediate: true }
    );

    return { availableUsersForSelect, sharedWithEmailsForSelect, loadingUsers, fullWorldShareUrl };
  }
};
</script>
