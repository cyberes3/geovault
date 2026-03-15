<template>
  <div class="flex-1 min-h-0 overflow-hidden flex flex-col px-2 pt-2 pb-2">
    <div v-if="loading" class="flex-1 flex flex-col items-center justify-center p-4">
      <Loader size="md" :show-message="false" layout="inline" />
      <p class="text-sm text-black mt-4">Loading...</p>
    </div>

    <template v-else>
      <div class="flex border-b border-gray-200 mb-2 flex-shrink-0">
        <button
          v-for="tab in listTabs"
          :key="tab.id"
          type="button"
          :class="[
            'flex-1 py-2.5 text-sm font-medium border-b-2 transition-colors',
            listTab === tab.id
              ? 'border-blue-500 text-blue-600'
              : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
          ]"
          @click="$emit('update:listTab', tab.id)"
        >
          {{ tab.label }}
        </button>
      </div>

      <div
        v-if="listEmptyForTab"
        class="flex-1 flex flex-col items-center justify-center py-12 px-6 text-center"
      >
        <h3 class="text-base font-semibold text-gray-900 mb-1">{{ emptyTitle }}</h3>
        <p class="text-sm text-gray-500 max-w-xs">{{ emptyMessage }}</p>
      </div>

      <div
        v-else
        ref="scrollContainerRef"
        :class="scrollContainerClass"
        @click.self="$emit('clearHighlight')"
      >
        <template v-if="listTab === 'groups'">
          <div
            v-for="group in visibleGroupsTab"
            :key="'group-' + group.id"
            :class="[
              'group flex items-center gap-3 p-4 rounded-2xl cursor-pointer border transition-colors',
              activeGroupId != null && String(activeGroupId) === String(group.id)
                ? 'border-blue-500 bg-blue-100'
                : 'border-gray-200 bg-white hover:bg-gray-50'
            ]"
            @click="$emit('groupClick', group)"
          >
            <div
              class="flex-shrink-0 w-12 h-12 flex items-center justify-center rounded-xl bg-gray-100 border border-gray-200"
            >
              <Square3Stack3DIcon class="h-6 w-6 text-gray-500" />
            </div>
            <div class="flex-1 min-w-0">
              <div class="font-bold text-gray-900 truncate">{{ group.name }}</div>
              <div class="text-xs text-gray-500">{{ (group.track_ids || []).length }} {{ (group.track_ids || []).length === 1 ? 'tracker' : 'trackers' }}</div>
            </div>
            <div
              class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity"
              :class="actionOpacityClass"
            >
              <button
                type="button"
                title="View Group"
                class="p-2 rounded-xl text-gray-400 hover:text-blue-600 hover:bg-white"
                @click.stop="$emit('viewGroup', group)"
              >
                <ListBulletIcon class="h-5 w-5" />
              </button>
              <template v-if="group.is_owner">
                <button
                  type="button"
                  title="Edit Group"
                  class="p-2 rounded-xl text-gray-400 hover:text-blue-600 hover:bg-white"
                  @click.stop="$emit('editGroup', group)"
                >
                  <PencilIcon class="h-5 w-5" />
                </button>
              </template>
              <template v-else>
                <button
                  type="button"
                  title="Leave shared group"
                  class="p-2 rounded-xl text-gray-400 hover:text-red-600 hover:bg-white text-sm"
                  @click.stop="$emit('leaveGroup', group)"
                >
                  Leave shared group
                </button>
              </template>
            </div>
          </div>
        </template>

        <template v-else-if="listTab === 'shared'">
          <div
            v-for="group in visibleSharedGroupsTab"
            :key="'shared-group-' + group.id"
            :class="[
              'group flex items-center gap-3 p-4 rounded-2xl cursor-pointer border transition-colors',
              activeGroupId != null && String(activeGroupId) === String(group.id)
                ? 'border-blue-500 bg-blue-100'
                : 'border-gray-200 bg-white hover:bg-gray-50'
            ]"
            @click="$emit('groupClick', group)"
          >
            <div
              class="flex-shrink-0 w-12 h-12 flex items-center justify-center rounded-xl bg-gray-100 border border-gray-200"
            >
              <Square3Stack3DIcon class="h-6 w-6 text-gray-500" />
            </div>
            <div class="flex-1 min-w-0">
              <div
                class="font-bold text-gray-900 tracking-tight break-all flex items-center gap-1.5 min-w-0"
                :title="group.name"
              >
                <span class="truncate">{{ group.name }}</span>
                <CloudIcon class="h-4 w-4 text-gray-500 flex-shrink-0" />
              </div>
              <div
                v-if="group.owner_email"
                class="text-xs text-gray-500 truncate mt-0.5"
                :title="'Shared by ' + group.owner_email"
              >
                Shared by {{ group.owner_email }}
              </div>
              <div class="text-xs text-gray-500">{{ (group.track_ids || []).length }} {{ (group.track_ids || []).length === 1 ? 'tracker' : 'trackers' }}</div>
            </div>
            <div
              class="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity"
              :class="actionOpacityClass"
            >
              <button
                type="button"
                :title="isGroupHidden(group) ? 'Show on map' : 'Hide on map'"
                class="p-2 rounded-xl text-gray-400 hover:text-gray-700 hover:bg-white"
                @click.stop="$emit('toggleGroupVisibility', group)"
              >
                <EyeIcon v-if="isGroupHidden(group)" class="h-5 w-5" />
                <EyeSlashIcon v-else class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="View Group"
                class="p-2 rounded-xl text-gray-400 hover:text-blue-600 hover:bg-white"
                @click.stop="$emit('viewGroup', group)"
              >
                <ListBulletIcon class="h-5 w-5" />
              </button>
            </div>
          </div>
          <div
            v-for="track in visibleSharedTab"
            :key="track.id"
            :data-track-id="track.id"
            :class="trackRowClass(track)"
            @click="$emit('trackClick', track)"
          >
            <div
              class="flex-shrink-0 w-12 h-12 flex items-center justify-center rounded-xl bg-white border border-gray-100 transition-colors"
              :style="{ borderLeftColor: track.color || '#6C93DE', borderLeftWidth: '4px' }"
            >
              <TrackDirectionIcon
                :color="track.color || '#6C93DE'"
                :angle="getTrackDirectionAngle(track)"
                :size="26"
                :selected="selectedId === track.id"
                reserve-circle
              />
            </div>
            <div class="flex-1 min-w-0">
              <div
                class="font-bold text-gray-900 tracking-tight break-all flex items-center gap-1.5 min-w-0"
                :title="track.name"
              >
                <span class="truncate">{{ track.name }}</span>
                <CloudIcon
                  v-if="!track.is_owner"
                  class="h-4 w-4 text-gray-500 flex-shrink-0"
                />
              </div>
              <div
                v-if="track.owner_email"
                class="text-xs text-gray-500 truncate mt-0.5"
                :title="'Shared by ' + track.owner_email"
              >
                Shared by {{ track.owner_email }}
              </div>
              <div class="flex items-center gap-1.5 mt-0.5">
                <div class="text-xs font-medium text-gray-500 truncate">
                  {{ track.last_timestamp_ms ? formatTime(track.last_timestamp_ms) : 'Waiting for data...' }}
                </div>
              </div>
            </div>
            <div
              class="flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity"
              :class="actionOpacityClass"
            >
              <button
                type="button"
                :title="isHidden(track.id) ? 'Show on map' : 'Hide on map'"
                class="p-2 rounded-xl text-gray-400 hover:text-gray-700 hover:bg-white active:bg-gray-100 transition-all border border-transparent hover:border-gray-200"
                @click.stop="$emit('toggleVisibility', track.id)"
              >
                <EyeIcon v-if="isHidden(track.id)" class="h-5 w-5" />
                <EyeSlashIcon v-else class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="Latest Params"
                class="p-2 rounded-xl text-gray-400 hover:text-gray-600 hover:bg-white active:bg-gray-100 transition-all border border-transparent hover:border-gray-200"
                @click.stop="$emit('openParams', track.id)"
              >
                <TableCellsIcon class="h-5 w-5" />
              </button>
              <template v-if="track.is_owner">
                <button
                  type="button"
                  title="Edit"
                  class="p-2 rounded-xl text-gray-400 hover:text-blue-600 hover:bg-white active:bg-gray-100 transition-all border border-transparent hover:border-gray-200"
                  @click.stop="$emit('editTrack', track)"
                >
                  <PencilIcon class="h-5 w-5" />
                </button>
              </template>
            </div>
          </div>
        </template>

        <template v-else>
          <div
            v-for="track in visibleTrackersTab"
            :key="track.id"
            :data-track-id="track.id"
            :class="trackRowClass(track)"
            @click="$emit('trackClick', track)"
          >
            <div
              class="flex-shrink-0 w-12 h-12 flex items-center justify-center rounded-xl bg-white border border-gray-100 transition-colors"
              :style="{ borderLeftColor: track.color || '#6C93DE', borderLeftWidth: '4px' }"
            >
              <TrackDirectionIcon
                :color="track.color || '#6C93DE'"
                :angle="getTrackDirectionAngle(track)"
                :size="26"
                :selected="selectedId === track.id"
                reserve-circle
              />
            </div>
            <div class="flex-1 min-w-0">
              <div
                class="font-bold text-gray-900 tracking-tight break-all flex items-center gap-1.5 min-w-0"
                :title="track.name"
              >
                <span class="truncate">{{ track.name }}</span>
                <CloudIcon
                  v-if="!track.is_owner"
                  class="h-4 w-4 text-gray-500 flex-shrink-0"
                />
              </div>
              <div class="flex items-center gap-1.5 mt-0.5">
                <div class="text-xs font-medium text-gray-500 truncate">
                  {{ track.last_timestamp_ms ? formatTime(track.last_timestamp_ms) : 'Waiting for data...' }}
                </div>
              </div>
            </div>
            <div
              class="flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity"
              :class="actionOpacityClass"
            >
              <button
                type="button"
                title="Latest Params"
                class="p-2 rounded-xl text-gray-400 hover:text-gray-600 hover:bg-white active:bg-gray-100 transition-all border border-transparent hover:border-gray-200"
                @click.stop="$emit('openParams', track.id)"
              >
                <TableCellsIcon class="h-5 w-5" />
              </button>
              <template v-if="track.is_owner">
                <button
                  type="button"
                  title="Edit"
                  class="p-2 rounded-xl text-gray-400 hover:text-blue-600 hover:bg-white active:bg-gray-100 transition-all border border-transparent hover:border-gray-200"
                  @click.stop="$emit('editTrack', track)"
                >
                  <PencilIcon class="h-5 w-5" />
                </button>
              </template>
            </div>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>

<script>
import { ref, computed } from 'vue';
import { Square3Stack3DIcon, PencilIcon, TableCellsIcon, CloudIcon, ListBulletIcon, EyeIcon, EyeSlashIcon } from '@heroicons/vue/24/outline';
import Loader from 'platform/components/parts/Loader.vue';
import TrackDirectionIcon from './TrackDirectionIcon.vue';
import { getTrackDirectionAngle as getTrackDirectionAngleUtil } from './trackGeometry.js';
import { formatTimestampLocal } from './paramFormatters.js';

const LIST_TABS = [
  { id: 'trackers', label: 'Trackers' },
  { id: 'groups', label: 'Groups' },
  { id: 'shared', label: 'Shared' }
];

export default {
  name: 'TrackerListContent',
  components: {
    Loader,
    Square3Stack3DIcon,
    PencilIcon,
    TableCellsIcon,
    CloudIcon,
    ListBulletIcon,
    EyeIcon,
    EyeSlashIcon,
    TrackDirectionIcon
  },
  props: {
    listTab: { type: String, required: true },
    listTabs: { type: Array, default: () => LIST_TABS },
    visibleTrackersTab: { type: Array, default: () => [] },
    visibleSharedTab: { type: Array, default: () => [] },
    visibleGroupsTab: { type: Array, default: () => [] },
    visibleSharedGroupsTab: { type: Array, default: () => [] },
    selectedId: { type: [Number, String], default: null },
    activeGroupId: { type: [Number, String], default: null },
    highlightedId: { type: [Number, String], default: null },
    /** Set or array of track IDs hidden on map (for Shared tab eye button). */
    hiddenTrackIds: { type: [Set, Array], default: () => new Set() },
    /** Set or array of group IDs hidden on map (for Shared tab eye button). */
    hiddenGroupIds: { type: [Set, Array], default: () => new Set() },
    loading: { type: Boolean, default: false },
    listEmptyForTab: { type: Boolean, default: true },
    scrollContainerClass: {
      type: String,
      default: 'flex-1 min-h-0 overflow-y-auto space-y-3 px-1 py-1 custom-scrollbar'
    },
    /** Desktop: opacity-0 group-hover:opacity-100. Mobile: opacity-60 for always-visible actions. */
    actionOpacityClass: { type: String, default: '' }
  },
  emits: [
    'update:listTab',
    'groupClick',
    'trackClick',
    'editTrack',
    'openParams',
    'leaveGroup',
    'editGroup',
    'viewGroup',
    'toggleVisibility',
    'toggleGroupVisibility',
    'clearHighlight'
  ],
  setup(props) {
    const scrollContainerRef = ref(null);
    const formatTime = (ms) => formatTimestampLocal(ms);
    const getTrackDirectionAngle = getTrackDirectionAngleUtil;

    function isHidden(trackId) {
      const hid = props.hiddenTrackIds;
      if (hid instanceof Set) return hid.has(String(trackId));
      return Array.isArray(hid) && hid.includes(String(trackId));
    }

    function isGroupHidden(group) {
      const groupIds = props.hiddenGroupIds;
      const hasGroup = group?.id != null && (
        groupIds instanceof Set ? groupIds.has(String(group.id)) : Array.isArray(groupIds) && groupIds.includes(String(group.id))
      );
      if (hasGroup) return true;
      const trackIds = group?.track_ids || [];
      if (trackIds.length === 0) return false;
      const hid = props.hiddenTrackIds;
      const has = (id) => (hid instanceof Set ? hid.has(String(id)) : Array.isArray(hid) && hid.includes(String(id)));
      return trackIds.every((id) => has(id));
    }

    const emptyTitle = computed(() => {
      if (props.listTab === 'trackers') return 'No trackers yet';
      if (props.listTab === 'groups') return 'No groups yet';
      return 'No shared trackers or groups';
    });

    const emptyMessage = computed(() => {
      if (props.listTab === 'trackers')
        return 'Start by creating your first tracker to begin recording data.';
      if (props.listTab === 'groups')
        return 'Create a group to organize trackers and share with others.';
      return 'Trackers and groups shared with you will appear here.';
    });

    function trackRowClass(track) {
      const selected = props.selectedId === track.id;
      const highlighted =
        props.highlightedId != null &&
        String(props.highlightedId) === String(track.id) &&
        !selected;
      return [
        'group flex items-center gap-3 p-4 rounded-2xl cursor-pointer border transition-all',
        selected
          ? 'border-blue-500 bg-blue-100'
          : 'border-blue-100 bg-white hover:bg-blue-50 hover:border-blue-300',
        highlighted ? 'ring-2 ring-blue-500' : ''
      ];
    }

    return {
      scrollContainerRef,
      formatTime,
      getTrackDirectionAngle,
      isHidden,
      isGroupHidden,
      emptyTitle,
      emptyMessage,
      trackRowClass
    };
  }
};
</script>
