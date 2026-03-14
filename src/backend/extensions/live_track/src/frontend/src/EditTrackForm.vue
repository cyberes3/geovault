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
          title="Reset to default color from name"
          class="p-2 rounded-lg text-gray-500 hover:bg-gray-100 hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
          @click="$emit('reset-color')"
        >
          <ArrowPathIcon class="h-5 w-5" />
        </button>
      </div>
    </div>
    <template v-if="isOwner">
      <div class="border border-gray-200 rounded-lg p-3 space-y-3 bg-gray-50/50">
        <h3 class="text-sm font-semibold text-gray-800">Sharing with users</h3>
        <div class="space-y-2">
          <label class="text-sm font-medium text-gray-700">Who can see and add this tracker</label>
          <select
            :value="visibility"
            class="select-custom w-full border border-gray-300 px-3 py-2 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500"
            @change="$emit('update:visibility', ($event.target && $event.target.value) || 'private')"
          >
            <option value="private">Private (only me)</option>
            <option value="shared">Shared with specific users</option>
            <option value="public">Public (all authenticated users)</option>
          </select>
        </div>
        <div v-if="visibility === 'shared'" class="space-y-2">
          <ScrollingSelect
            label="Shared with (click to add or remove)"
            :items="availableUsersForSelect"
            :selected-values="sharedWithEmails"
            :loading="loadingUsers"
            max-height="12rem"
            empty-message="No other users found"
            @select="toggleUserEmail"
          />
        </div>
        <div class="space-y-2">
          <div class="flex items-center gap-3">
            <ToggleButton
              :model-value="shareParamsWithRecipients"
              label="Allow viewing parameters (shared users)"
              size="md"
              @update:model-value="$emit('update:shareParamsWithRecipients', $event)"
            />
            <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="$emit('update:shareParamsWithRecipients', !shareParamsWithRecipients)">Allow viewing parameters</label>
          </div>
          <p class="text-xs text-gray-500">When on, people you share this tracker with can see extended parameters (e.g. in Latest params). Serial is never shared.</p>
        </div>
      </div>
      <div class="border border-gray-200 rounded-lg p-3 space-y-3 bg-gray-50/50">
        <h3 class="text-sm font-semibold text-gray-800">World share link</h3>
        <div class="space-y-2">
          <div class="flex items-center gap-3">
            <ToggleButton
              :model-value="worldShareEnabled"
              label="World share link"
              size="md"
              @update:model-value="$emit('update:worldShareEnabled', $event)"
            />
            <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="$emit('update:worldShareEnabled', !worldShareEnabled)">World share link</label>
          </div>
          <p class="text-xs text-gray-500">When on, anyone with the link can view this track on a read-only map (no login required).</p>
        </div>
        <div class="space-y-2">
          <div class="flex items-center gap-3">
            <ToggleButton
              :model-value="shareParamsWithWorld"
              label="Allow viewing parameters (world link)"
              size="md"
              @update:model-value="$emit('update:shareParamsWithWorld', $event)"
            />
            <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="$emit('update:shareParamsWithWorld', !shareParamsWithWorld)">Allow viewing parameters</label>
          </div>
          <p class="text-xs text-gray-500">When on, anyone with the world share link can see extended parameters. Serial is never shared.</p>
        </div>
        <div v-if="worldShareEnabled && worldShareUrl" class="flex gap-2 items-center">
          <input
            :value="fullWorldShareUrl"
            readonly
            class="flex-1 px-3 py-2 text-sm border border-gray-300 rounded-md bg-gray-50 font-mono"
          />
          <button type="button" class="px-3 py-2 bg-gray-200 rounded text-sm whitespace-nowrap" @click="copy(fullWorldShareUrl)">Copy</button>
        </div>
      </div>
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
      </select>
    </div>
    <div v-if="isOwner" class="space-y-2">
      <div class="flex items-center gap-3">
        <ToggleButton
          :model-value="hiddenInList"
          label="Hide in list"
          size="md"
          @update:model-value="$emit('update:hidden-in-list', $event)"
        />
        <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="$emit('update:hidden-in-list', !hiddenInList)">Hide in list</label>
      </div>
      <p class="text-xs text-gray-500">When on, this tracker is hidden from the sidebar list. You can unhide it in Settings.</p>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">API Password</label>
      <div class="flex gap-2">
        <input :value="track?.tracker_secret" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <button type="button" class="px-2 py-1 bg-gray-200 hover:bg-gray-300 rounded text-sm" @click="copy(track?.tracker_secret || '')">Copy</button>
      </div>
    </div>
    <div class="grid grid-cols-2 gap-3 pb-2">
      <BaseButton v-if="isOwner" variant="white" size="sm" @click="$emit('open-instructions')">GPSLogger Setup</BaseButton>
      <BaseButton variant="white" size="sm" @click="$emit('download-kml')">Download KML</BaseButton>
      <BaseButton v-if="isOwner" variant="white" size="sm" :disabled="clearHistoryDisabled" @click="$emit('clear-history')">
        <Loader v-if="clearing" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Clear tracker
      </BaseButton>
      <BaseButton v-if="!isOwner" variant="secondary" color="gray" size="sm" :disabled="unsubscribing" @click="$emit('unsubscribe')">
        <Loader v-if="unsubscribing" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Remove from my list
      </BaseButton>
      <BaseButton v-if="isOwner" variant="secondary" color="red" size="sm" :disabled="deleting" @click="$emit('delete')">
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
import ScrollingSelect from 'platform/components/parts/ScrollingSelect.vue';
import ToggleButton from 'platform/components/parts/ToggleButton.vue';

export default {
  name: 'EditTrackForm',
  components: { ArrowPathIcon, BaseButton, ScrollingSelect, ToggleButton },
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
    copy: { type: Function, required: true },
    worldShareEnabled: { type: Boolean, default: false },
    worldShareUrl: { type: String, default: '' },
    shareParamsWithWorld: { type: Boolean, default: false },
    hiddenInList: { type: Boolean, default: false }
  },
  emits: ['update:name', 'update:color', 'update:recentDataWindow', 'update:visibility', 'update:shareParamsWithRecipients', 'update:shareParamsWithWorld', 'update:sharedWithEmails', 'update:worldShareEnabled', 'update:hidden-in-list', 'reset-color', 'open-instructions', 'download-kml', 'clear-history', 'delete', 'unsubscribe'],
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

    watch(
      () => props.isOwner && props.visibility === 'shared',
      (show) => { if (show) fetchUsers(); },
      { immediate: true }
    );

    function toggleUserEmail(item) {
      const email = (item && (item.label ?? item.value)) ? String(item.label || item.value).trim().toLowerCase() : '';
      if (!email) return;
      const current = props.sharedWithEmails || [];
      const has = current.some((e) => (e || '').toLowerCase() === email);
      if (has) emit('update:sharedWithEmails', current.filter((e) => (e || '').toLowerCase() !== email));
      else emit('update:sharedWithEmails', [...current, email]);
    }
    return { availableUsersForSelect, loadingUsers, toggleUserEmail, fullWorldShareUrl };
  }
};
</script>
