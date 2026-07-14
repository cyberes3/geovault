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
        v-if="!listEmptyForTab"
        class="relative flex-shrink-0 mb-2"
      >
        <input
          v-model="searchQuery"
          type="text"
          :placeholder="searchPlaceholder"
          class="w-full border border-gray-300 px-3 py-2 rounded-lg pl-9 text-sm"
        />
        <MagnifyingGlassIcon class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
      </div>

      <div
        v-if="listEmptyForTab"
        class="flex-1 flex flex-col items-center justify-center py-12 px-6 text-center"
      >
        <h3 class="text-base font-semibold text-gray-900 mb-1">{{ emptyTitle }}</h3>
        <p class="text-sm text-gray-500 max-w-xs">{{ emptyMessage }}</p>
      </div>

      <div
        v-else-if="filteredListEmptyForTab"
        class="flex-1 flex flex-col items-center justify-center py-12 px-6 text-center"
      >
        <h3 class="text-base font-semibold text-gray-900 mb-1">No matches</h3>
        <p class="text-sm text-gray-500 max-w-xs">Try a different search.</p>
      </div>

      <div
        v-else
        ref="scrollContainerRef"
        :class="scrollContainerClass"
        @click.self="$emit('clearHighlight')"
      >
        <template v-if="listTab === 'groups'">
          <div
            v-for="group in filteredGroupsTab"
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
              <div class="text-xs text-gray-500">{{ (group.track_ids ?? []).length }} {{ (group.track_ids ?? []).length === 1 ? 'tracker' : 'trackers' }}</div>
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
                  title="Leave Shared Group"
                  class="p-2 rounded-xl text-gray-400 hover:text-red-600 hover:bg-white text-sm"
                  @click.stop="$emit('leaveGroup', group)"
                >
                  Leave Shared Group
                </button>
              </template>
            </div>
          </div>
        </template>

        <template v-else-if="listTab === 'shared'">
          <div
            v-for="group in filteredSharedGroupsTab"
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
              <div class="text-xs text-gray-500">{{ (group.track_ids ?? []).length }} {{ (group.track_ids ?? []).length === 1 ? 'tracker' : 'trackers' }}</div>
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
            </div>
          </div>
          <div
            v-for="track in filteredSharedTab"
            :key="track.id"
            :data-track-id="track.id"
            :class="trackRowClass(track)"
            @click="$emit('trackClick', track)"
          >
            <div
              class="flex-shrink-0 w-12 h-12 flex items-center justify-center rounded-xl bg-white border border-gray-100 transition-colors"
              :style="{ borderLeftColor: track.color ?? '#6C93DE', borderLeftWidth: '4px' }"
            >
              <TrackDirectionIcon
                :color="track.color ?? '#6C93DE'"
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
                <div
                  :class="['text-xs font-medium truncate', listTimeClass(track)]"
                >
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

        <template v-else>
          <div
            v-for="track in filteredTrackersTab"
            :key="track.id"
            :data-track-id="track.id"
            :class="trackRowClass(track)"
            @click="$emit('trackClick', track)"
          >
            <div
              class="flex-shrink-0 w-12 h-12 flex items-center justify-center rounded-xl bg-white border border-gray-100 transition-colors"
              :style="{ borderLeftColor: track.color ?? '#6C93DE', borderLeftWidth: '4px' }"
            >
              <TrackDirectionIcon
                :color="track.color ?? '#6C93DE'"
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
                <div
                  :class="['text-xs font-medium truncate', listTimeClass(track)]"
                >
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

<script lang="ts">
import { defineComponent, ref, computed, watch, type PropType } from 'vue';
import { Square3Stack3DIcon, PencilIcon, TableCellsIcon, CloudIcon, ListBulletIcon, MagnifyingGlassIcon } from '@heroicons/vue/24/outline';
import Loader from 'platform/components/parts/Loader.vue';
import TrackDirectionIcon from './TrackDirectionIcon.vue';
import { getTrackDirectionAngle as getTrackDirectionAngleUtil } from './trackGeometry';
import { formatTimestampLocal } from './paramFormatters';
import { filterByQuery } from './sharingSelectors';
import { isActiveButDeadTrack } from './activeButDeadTrack';
import type { LiveTrack, LiveTrackGroup } from './types/track';

interface ListTab {
  id: 'trackers' | 'groups' | 'shared';
  label: string;
}

const LIST_TABS: ListTab[] = [
  { id: 'trackers', label: 'Trackers' },
  { id: 'groups', label: 'Groups' },
  { id: 'shared', label: 'Shared' }
];

export default defineComponent({
  name: 'TrackerListContent',
  components: {
    Loader,
    Square3Stack3DIcon,
    PencilIcon,
    TableCellsIcon,
    CloudIcon,
    ListBulletIcon,
    MagnifyingGlassIcon,
    TrackDirectionIcon
  },
  props: {
    listTab: { type: String as PropType<ListTab['id']>, required: true },
    listTabs: { type: Array as PropType<ListTab[]>, default: () => LIST_TABS },
    visibleTrackersTab: { type: Array as PropType<LiveTrack[]>, default: () => [] },
    visibleSharedTab: { type: Array as PropType<LiveTrack[]>, default: () => [] },
    visibleGroupsTab: { type: Array as PropType<LiveTrackGroup[]>, default: () => [] },
    visibleSharedGroupsTab: { type: Array as PropType<LiveTrackGroup[]>, default: () => [] },
    selectedId: { type: [Number, String] as PropType<string | number | null>, default: null },
    activeGroupId: { type: [Number, String] as PropType<string | number | null>, default: null },
    highlightedId: { type: [Number, String] as PropType<string | number | null>, default: null },
    loading: { type: Boolean, default: false },
    listEmptyForTab: { type: Boolean, default: true },
    scrollContainerClass: {
      type: String,
      default: 'flex-1 min-h-0 overflow-y-auto space-y-3 px-1 py-1 custom-scrollbar'
    },
    /** Desktop: opacity-0 group-hover:opacity-100. Mobile: opacity-60 for always-visible actions. */
    actionOpacityClass: { type: String, default: '' },
    highlightStaleData: { type: Boolean, default: false }
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
    'clearHighlight'
  ],
  setup(props) {
    const scrollContainerRef = ref<HTMLElement | null>(null);
    const searchQuery = ref('');

    watch(() => props.listTab, () => {
      searchQuery.value = '';
    });

    const formatTime = (ms: number | string | null | undefined): string => formatTimestampLocal(ms);
    const getTrackDirectionAngle = getTrackDirectionAngleUtil;

    function listTimeClass(track: LiveTrack): string {
      if (!props.highlightStaleData) return 'text-gray-500';
      return isActiveButDeadTrack(track) ? 'text-red-600' : 'text-gray-500';
    }

    const filteredTrackersTab = computed((): LiveTrack[] => filterByQuery(props.visibleTrackersTab, searchQuery.value, 'name'));
    const filteredGroupsTab = computed((): LiveTrackGroup[] => filterByQuery(props.visibleGroupsTab, searchQuery.value, 'name'));
    const filteredSharedTab = computed((): LiveTrack[] => filterByQuery(props.visibleSharedTab, searchQuery.value, 'name', 'owner_email'));
    const filteredSharedGroupsTab = computed((): LiveTrackGroup[] => filterByQuery(props.visibleSharedGroupsTab, searchQuery.value, 'name', 'owner_email'));

    const filteredListEmptyForTab = computed((): boolean => {
      if (props.listTab === 'trackers') return filteredTrackersTab.value.length === 0;
      if (props.listTab === 'groups') return filteredGroupsTab.value.length === 0;
      return filteredSharedTab.value.length === 0 && filteredSharedGroupsTab.value.length === 0;
    });

    const searchPlaceholder = computed((): string => {
      if (props.listTab === 'trackers') return 'Search trackers...';
      if (props.listTab === 'groups') return 'Search groups...';
      return 'Search by name or owner...';
    });

    const emptyTitle = computed((): string => {
      if (props.listTab === 'trackers') return 'No Trackers Yet';
      if (props.listTab === 'groups') return 'No Groups Yet';
      return 'No Shared Trackers or Groups';
    });

    const emptyMessage = computed((): string => {
      if (props.listTab === 'trackers')
        return 'Start by creating your first tracker to begin recording data.';
      if (props.listTab === 'groups')
        return 'Create a group to organize trackers and share with others.';
      return 'Trackers and groups shared with you will appear here.';
    });

    function trackRowClass(track: LiveTrack): string[] {
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
      searchQuery,
      searchPlaceholder,
      filteredTrackersTab,
      filteredGroupsTab,
      filteredSharedTab,
      filteredSharedGroupsTab,
      filteredListEmptyForTab,
      formatTime,
      getTrackDirectionAngle,
      emptyTitle,
      emptyMessage,
      trackRowClass,
      listTimeClass
    };
  }
});
</script>
