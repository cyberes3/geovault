<template>
  <div class="flex-1 min-h-0 flex flex-col p-4 overflow-y-auto">
    <div class="flex-shrink-0 mb-4 border-b border-gray-200 pb-4">
      <div class="flex items-start justify-between gap-3">
        <div class="min-w-0">
          <h3 class="text-sm font-semibold text-gray-900">Stale data highlight</h3>
          <p class="text-xs text-gray-500 mt-0.5">
            When enabled, last-updated times use red when the tracker was touched recently but the last point is older than 10 minutes.
          </p>
        </div>
        <input
          type="checkbox"
          class="mt-1 h-4 w-4 flex-shrink-0 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          :checked="highlightStaleData"
          @change="$emit('update:highlightStaleData', $event.target.checked)"
        />
      </div>
    </div>
    <div class="flex-shrink-0 mb-4">
      <div class="flex items-center justify-between mb-2">
        <h3 class="text-sm font-semibold text-gray-900">Hidden Trackers</h3>
        <span v-if="hiddenTrackers.length > 0" class="text-xs text-gray-900">{{ hiddenTrackers.length }}</span>
      </div>
      <div v-if="hiddenTrackers.length === 0" class="text-xs text-gray-900 italic">None</div>
      <ul v-else class="space-y-1">
        <li
          v-for="track in hiddenTrackers"
          :key="track.id"
          class="flex items-center justify-between py-1.5 px-2 rounded hover:bg-gray-50"
        >
          <span class="text-sm text-gray-900 truncate flex-1 min-w-0 mr-2 flex items-center gap-1.5">
            <span class="truncate">{{ track.name || 'Unnamed' }}</span>
            <CloudIcon v-if="!track.is_owner" class="h-4 w-4 text-gray-700 flex-shrink-0" />
          </span>
          <button
            type="button"
            class="text-xs text-blue-600 hover:text-blue-800 font-medium flex-shrink-0"
            @click="$emit('unhide-tracker', track.id)"
          >
            Show
          </button>
        </li>
      </ul>
      <button
        v-if="hiddenTrackers.length > 1"
        type="button"
        class="mt-2 text-xs text-blue-600 hover:text-blue-800 font-medium disabled:opacity-50 disabled:cursor-not-allowed"
        :disabled="isUnhideAllTrackersLoading || isUnhideAllGroupsLoading"
        @click="$emit('unhide-all-trackers')"
      >
        {{ isUnhideAllTrackersLoading ? 'Showing trackers...' : 'Show all trackers' }}
      </button>
    </div>
    <div class="flex-shrink-0 mb-4 border-t border-gray-200 pt-4">
      <div class="flex items-center justify-between mb-2">
        <h3 class="text-sm font-semibold text-gray-900">Hidden Groups</h3>
        <span v-if="hiddenGroups.length > 0" class="text-xs text-gray-900">{{ hiddenGroups.length }}</span>
      </div>
      <div v-if="hiddenGroups.length === 0" class="text-xs text-gray-900 italic">None</div>
      <ul v-else class="space-y-1">
        <li
          v-for="group in hiddenGroups"
          :key="group.id"
          class="flex items-center justify-between py-1.5 px-2 rounded hover:bg-gray-50"
        >
          <span class="text-sm text-gray-900 truncate flex-1 min-w-0 mr-2 flex items-center gap-1.5">
            <span class="truncate">{{ group.name || 'Unnamed' }}</span>
            <CloudIcon v-if="!group.is_owner" class="h-4 w-4 text-gray-700 flex-shrink-0" />
          </span>
          <button
            type="button"
            class="text-xs text-blue-600 hover:text-blue-800 font-medium flex-shrink-0"
            @click="$emit('unhide-group', group.id)"
          >
            Show
          </button>
        </li>
      </ul>
      <button
        v-if="hiddenGroups.length > 1"
        type="button"
        class="mt-2 text-xs text-blue-600 hover:text-blue-800 font-medium disabled:opacity-50 disabled:cursor-not-allowed"
        :disabled="isUnhideAllTrackersLoading || isUnhideAllGroupsLoading"
        @click="$emit('unhide-all-groups')"
      >
        {{ isUnhideAllGroupsLoading ? 'Showing groups...' : 'Show all groups' }}
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
    /** Each item: { id, name, is_owner } — trackers with owner Hidden on. */
    hiddenTrackers: { type: Array, default: () => [] },
    /** Each item: { id, name, is_owner } — owned groups with Hidden on. */
    hiddenGroups: { type: Array, default: () => [] },
    isUnhideAllTrackersLoading: { type: Boolean, default: false },
    isUnhideAllGroupsLoading: { type: Boolean, default: false },
    highlightStaleData: { type: Boolean, default: false }
  },
  emits: [
    'unhide-tracker',
    'unhide-all-trackers',
    'unhide-group',
    'unhide-all-groups',
    'update:highlightStaleData'
  ],
};
</script>
