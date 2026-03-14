<template>
  <div class="flex-1 min-h-0 flex flex-col p-4 overflow-hidden">
    <p class="text-sm text-gray-900 mb-4">
      Hidden trackers and groups appear here (from list or map). Use the toggle on each tracker or group edit page to hide them from the list; use the eye in the Shared tab to hide on map.
    </p>

    <!-- Manage shared trackers -->
    <div class="flex-shrink-0 mb-4">
      <BaseButton
        variant="primary"
        color="blue"
        size="sm"
        class="w-full flex items-center justify-center gap-2"
        @click="$emit('open-shared-list')"
      >
        <ShareIcon class="h-4 w-4" />
        Manage shared trackers
      </BaseButton>
    </div>

    <!-- Hidden trackers -->
    <div class="flex-shrink-0 mb-4">
      <div class="flex items-center justify-between mb-2">
        <h3 class="text-sm font-semibold text-gray-900">Hidden trackers</h3>
        <span v-if="hiddenTrackers.length > 0" class="text-xs text-gray-900">{{ hiddenTrackers.length }}</span>
      </div>
      <div v-if="hiddenTrackers.length === 0" class="text-xs text-gray-900 italic">None</div>
      <ul v-else class="space-y-1">
        <li
          v-for="(track, index) in hiddenTrackers"
          :key="track.source === 'list' ? track.id : 'map-' + track.id + '-' + index"
          class="flex items-center justify-between py-1.5 px-2 rounded hover:bg-gray-50"
        >
          <span class="text-sm text-gray-900 truncate flex-1 min-w-0 mr-2 flex items-center gap-1.5">
            <span class="truncate">{{ track.name || 'Unnamed' }}</span>
            <CloudIcon v-if="!track.is_owner" class="h-4 w-4 text-gray-700 flex-shrink-0" />
          </span>
          <button
            type="button"
            class="text-xs text-blue-600 hover:text-blue-800 font-medium flex-shrink-0"
            @click="track.source === 'list' ? $emit('unhide-tracker', track.id) : $emit('unhide-tracker-from-map', track.id)"
          >
            Show
          </button>
        </li>
      </ul>
      <button
        v-if="hiddenTrackers.length > 1"
        type="button"
        class="mt-2 text-xs text-blue-600 hover:text-blue-800 font-medium"
        @click="$emit('unhide-all-trackers')"
      >
        Show all trackers
      </button>
    </div>

    <!-- Hidden groups -->
    <div class="flex-shrink-0">
      <div class="flex items-center justify-between mb-2">
        <h3 class="text-sm font-semibold text-gray-900">Hidden groups</h3>
        <span v-if="hiddenGroups.length > 0" class="text-xs text-gray-900">{{ hiddenGroups.length }}</span>
      </div>
      <div v-if="hiddenGroups.length === 0" class="text-xs text-gray-900 italic">None</div>
      <ul v-else class="space-y-1">
        <li
          v-for="(group, index) in hiddenGroups"
          :key="group.source === 'list' ? group.id : 'map-' + group.id + '-' + index"
          class="flex items-center justify-between py-1.5 px-2 rounded hover:bg-gray-50"
        >
          <span class="text-sm text-gray-900 truncate flex-1 min-w-0 mr-2 flex items-center gap-1.5">
            <span class="truncate">{{ group.name || 'Unnamed' }}</span>
            <CloudIcon v-if="!group.is_owner" class="h-4 w-4 text-gray-700 flex-shrink-0" />
          </span>
          <button
            type="button"
            class="text-xs text-blue-600 hover:text-blue-800 font-medium flex-shrink-0"
            @click="group.source === 'list' ? $emit('unhide-group', group.id) : $emit('unhide-group-from-map', group.id)"
          >
            Show
          </button>
        </li>
      </ul>
      <button
        v-if="hiddenGroups.length > 1"
        type="button"
        class="mt-2 text-xs text-blue-600 hover:text-blue-800 font-medium"
        @click="$emit('unhide-all-groups')"
      >
        Show all groups
      </button>
    </div>
  </div>
</template>

<script>
import { CloudIcon, ShareIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';

export default {
  name: 'LiveTrackSettingsSidebarContent',
  components: { CloudIcon, ShareIcon, BaseButton },
  props: {
    /** Each item: { id, name, is_owner, source: 'list'|'map' } */
    hiddenTrackers: { type: Array, default: () => [] },
    /** Each item: { id, name, is_owner, source: 'list'|'map' } */
    hiddenGroups: { type: Array, default: () => [] },
  },
  emits: [
    'open-shared-list',
    'unhide-tracker',
    'unhide-all-trackers',
    'unhide-tracker-from-map',
    'unhide-group',
    'unhide-all-groups',
    'unhide-group-from-map',
  ],
};
</script>
