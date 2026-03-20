<template>
  <div class="flex-1 min-h-0 flex flex-col p-4 overflow-hidden">
    <!-- Hidden trackers -->
    <div class="flex-shrink-0 mb-4">
      <div class="flex items-center justify-between mb-2">
        <h3 class="text-sm font-semibold text-gray-900">Hidden Trackers</h3>
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
        <h3 class="text-sm font-semibold text-gray-900">Hidden Groups</h3>
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
import { CloudIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'LiveTrackSettingsSidebarContent',
  components: { CloudIcon },
  props: {
    /** Each item: { id, name, is_owner, source: 'list'|'map' } */
    hiddenTrackers: { type: Array, default: () => [] },
    /** Each item: { id, name, is_owner, source: 'list'|'map' } */
    hiddenGroups: { type: Array, default: () => [] },
  },
  emits: [
    'unhide-tracker',
    'unhide-all-trackers',
    'unhide-tracker-from-map',
    'unhide-group',
    'unhide-all-groups',
    'unhide-group-from-map',
  ],
};
</script>
