<template>
  <div class="flex-1 min-h-0 flex flex-col overflow-hidden p-4">
    <div class="relative flex-shrink-0 mb-2">
      <input
        v-model="searchQuery"
        type="text"
        placeholder="Search by name or owner..."
        class="w-full border border-gray-300 px-3 py-2 rounded-lg pl-9"
      />
      <MagnifyingGlassIcon class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
    </div>
    <div class="flex-shrink-0 mb-3 flex items-center gap-2">
      <BaseButton variant="primary" color="blue" size="sm" class="flex-1" @click="$emit('openDiscover')">
        Add Public Trackers
      </BaseButton>
      <BaseButton variant="primary" color="blue" size="sm" class="flex-1" @click="$emit('open-shared-list')">
        Manage Shared
      </BaseButton>
      <button
        type="button"
        title="Refresh"
        class="p-2 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100 flex-shrink-0 disabled:opacity-50 flex items-center justify-center size-9"
        :disabled="refreshing"
        @click="$emit('refresh')"
      >
        <ArrowPathIcon :class="['h-5 w-5', refreshing ? 'animate-spin' : '']" />
      </button>
    </div>
    <div class="flex-1 min-h-0 flex flex-col gap-3 overflow-hidden">
      <div class="flex-1 min-h-0 flex flex-col overflow-hidden rounded-xl border border-gray-200 bg-gray-50/60 shadow-sm">
        <div class="flex-shrink-0 px-3 py-2 border-b border-gray-200/80 bg-white/50 rounded-t-xl">
          <p class="text-xs font-semibold text-gray-600 uppercase tracking-wider">Incoming</p>
        </div>
        <div class="flex-1 min-h-0 overflow-y-auto p-2 space-y-2 custom-scrollbar">
          <div
            v-for="track in filteredIncoming"
            :key="'incoming-' + track.id"
            class="flex items-center gap-2 p-3 rounded-lg border border-gray-200/80 bg-white hover:bg-gray-50 hover:border-gray-300 transition-colors"
          >
            <TrackListChevronIcon class="flex-shrink-0 text-gray-500" />
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-900 truncate" :title="track.name">{{ track.name }}</div>
              <div class="text-xs text-gray-500 truncate" :title="track.owner_email">{{ track.owner_email }}</div>
            </div>
            <div class="flex items-center gap-1 flex-shrink-0">
              <button
                type="button"
                title="Reject (remove me from share; you won't see this in Incoming again)"
                class="p-2 rounded-lg text-gray-500 hover:text-red-600 hover:bg-red-50 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center size-9 flex-shrink-0"
                :disabled="isAdding(track.id) || isLeavingShare(track.id)"
                @click="$emit('leaveShare', track.id)"
              >
                <Loader v-if="isLeavingShare(track.id)" size="xs" layout="inline" :show-message="false" />
                <XMarkIcon v-else class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="Add to My Trackers"
                class="p-2 rounded-lg text-blue-600 hover:bg-blue-50 flex-shrink-0 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center size-9"
                :disabled="isAdding(track.id) || isLeavingShare(track.id)"
                @click="$emit('addIncoming', track)"
              >
                <Loader v-if="isAdding(track.id)" size="xs" layout="inline" :show-message="false" />
                <PlusIcon v-else class="h-5 w-5" />
              </button>
            </div>
          </div>
          <div
            v-for="group in filteredIncomingGroups"
            :key="'incoming-group-' + group.id"
            class="flex items-center gap-2 p-3 rounded-lg border border-gray-200/80 bg-white hover:bg-gray-50 hover:border-gray-300 transition-colors"
          >
            <UserGroupIcon class="h-5 w-5 text-gray-500 flex-shrink-0" />
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-900 truncate" :title="group.name">{{ group.name }}</div>
              <div class="text-xs text-gray-500 truncate" :title="group.owner_email">{{ group.owner_email }}</div>
            </div>
            <div class="flex items-center gap-1 flex-shrink-0">
              <button
                type="button"
                title="Leave shared group"
                class="p-2 rounded-lg text-gray-500 hover:text-red-600 hover:bg-red-50 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center size-9 flex-shrink-0"
                :disabled="isAddingGroup(group.id)"
                @click="onLeaveGroup(group)"
              >
                <XMarkIcon class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="Accept shared group"
                class="p-2 rounded-lg text-blue-600 hover:bg-blue-50 flex-shrink-0 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center size-9"
                :disabled="isAddingGroup(group.id)"
                @click="$emit('addIncomingGroup', group)"
              >
                <Loader v-if="isAddingGroup(group.id)" size="xs" layout="inline" :show-message="false" />
                <PlusIcon v-else class="h-5 w-5" />
              </button>
            </div>
          </div>
          <div
            v-for="group in filteredPublicGroups"
            :key="'public-group-' + group.id"
            class="flex items-center gap-2 p-3 rounded-lg border border-gray-200/80 bg-white hover:bg-gray-50 hover:border-gray-300 transition-colors"
          >
            <UserGroupIcon class="h-5 w-5 text-gray-500 flex-shrink-0" />
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-900 truncate" :title="group.name">{{ group.name }}</div>
              <div class="text-xs text-gray-500 truncate" :title="group.owner_email">{{ group.owner_email }} (public)</div>
            </div>
            <div class="flex items-center gap-1 flex-shrink-0">
              <button
                type="button"
                title="Add group to my trackers"
                class="p-2 rounded-lg text-blue-600 hover:bg-blue-50 flex-shrink-0 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center size-9"
                :disabled="isAddingGroup(group.id)"
                @click="$emit('addIncomingGroup', group)"
              >
                <Loader v-if="isAddingGroup(group.id)" size="xs" layout="inline" :show-message="false" />
                <PlusIcon v-else class="h-5 w-5" />
              </button>
            </div>
          </div>
          <p v-if="filteredIncoming.length === 0 && filteredIncomingGroups.length === 0 && filteredPublicGroups.length === 0" class="text-sm text-gray-500 py-4 px-2 text-center">No incoming shares</p>
        </div>
      </div>
      <div class="flex-1 min-h-0 flex flex-col overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm">
        <div class="flex-shrink-0 px-3 py-2 border-b border-gray-200 bg-gray-50/80 rounded-t-xl">
          <p class="text-xs font-semibold text-gray-600 uppercase tracking-wider">On your map</p>
        </div>
        <div class="flex-1 min-h-0 overflow-y-auto p-2 space-y-2 custom-scrollbar">
          <div
            v-for="track in filteredShared"
            :key="track.id"
            class="flex items-center gap-2 p-3 rounded-lg border border-gray-200 bg-white transition-colors cursor-pointer hover:bg-blue-50 hover:border-blue-200"
            @click="$emit('selectTrack', track)"
          >
            <TrackListChevronIcon class="flex-shrink-0 text-gray-500" />
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-900 truncate" :title="track.name">{{ track.name }}</div>
              <div class="text-xs text-gray-500 truncate" :title="track.owner_email">{{ track.owner_email }}</div>
            </div>
            <div class="flex items-center gap-1 flex-shrink-0" @click.stop>
              <button
                type="button"
                :title="isHidden(track.id) ? 'Show on Map' : 'Hide on Map'"
                class="p-2 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100 flex items-center justify-center size-9 flex-shrink-0"
                @click="$emit('toggleVisibility', track.id)"
              >
                <EyeIcon v-if="isHidden(track.id)" class="h-5 w-5" />
                <EyeSlashIcon v-else class="h-5 w-5" />
              </button>
              <template v-if="(track.visibility || '') === 'public'">
                <button
                  type="button"
                  title="Remove from my trackers (you can add again from Public Trackers)"
                  class="p-2 rounded-lg text-gray-500 hover:text-red-600 hover:bg-red-50 flex items-center justify-center size-9 flex-shrink-0"
                  :disabled="isUnsubscribing(track.id)"
                  @click="$emit('unsubscribe', track.id)"
                >
                  <Loader v-if="isUnsubscribing(track.id)" size="xs" layout="inline" :show-message="false" />
                  <XMarkIcon v-else class="h-5 w-5" />
                </button>
              </template>
              <template v-else>
                <button
                  type="button"
                  title="Unsubscribe (remove from my list; you can add again from Incoming)"
                  class="p-2 rounded-lg text-gray-500 hover:text-amber-600 hover:bg-amber-50 flex items-center justify-center size-9 flex-shrink-0"
                  :disabled="isUnsubscribing(track.id) || isLeavingShare(track.id)"
                  @click="$emit('unsubscribe', track.id)"
                >
                  <Loader v-if="isUnsubscribing(track.id)" size="xs" layout="inline" :show-message="false" />
                  <UserMinusIcon v-else class="h-5 w-5" />
                </button>
                <button
                  type="button"
                  title="Remove me from share (owner will no longer have you as recipient; you won't see this in Incoming again)"
                  class="p-2 rounded-lg text-gray-500 hover:text-red-600 hover:bg-red-50 flex items-center justify-center size-9 flex-shrink-0"
                  :disabled="isUnsubscribing(track.id) || isLeavingShare(track.id)"
                  @click="$emit('leaveShare', track.id)"
                >
                  <Loader v-if="isLeavingShare(track.id)" size="xs" layout="inline" :show-message="false" />
                  <XMarkIcon v-else class="h-5 w-5" />
                </button>
              </template>
            </div>
          </div>
          <div
            v-for="group in filteredSharedGroupsOnMap"
            :key="'on-map-group-' + group.id"
            class="flex items-center gap-2 p-3 rounded-lg border border-gray-200 bg-white transition-colors cursor-pointer hover:bg-blue-50 hover:border-blue-200"
            @click="$emit('selectGroup', group)"
          >
            <UserGroupIcon class="h-5 w-5 text-gray-500 flex-shrink-0" />
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-900 truncate" :title="group.name">{{ group.name }}</div>
              <div class="text-xs text-gray-500 truncate" :title="group.owner_email">{{ group.owner_email }}</div>
            </div>
            <div class="flex items-center gap-1 flex-shrink-0" @click.stop>
              <button
                type="button"
                :title="isGroupHidden(group) ? 'Show Group on Map' : 'Hide Group on Map'"
                class="p-2 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100 flex items-center justify-center size-9 flex-shrink-0"
                :disabled="isUnsubscribingGroup(group.id)"
                @click="$emit('toggleGroupVisibility', group)"
              >
                <EyeIcon v-if="isGroupHidden(group)" class="h-5 w-5" />
                <EyeSlashIcon v-else class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="Unsubscribe from all trackers in this group (you can add again from Incoming)"
                class="p-2 rounded-lg text-gray-500 hover:text-amber-600 hover:bg-amber-50 flex items-center justify-center size-9 flex-shrink-0"
                :disabled="isUnsubscribingGroup(group.id)"
                @click="$emit('unsubscribeGroup', group)"
              >
                <Loader v-if="isUnsubscribingGroup(group.id)" size="xs" layout="inline" :show-message="false" />
                <UserMinusIcon v-else class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="Leave shared group"
                class="p-2 rounded-lg text-gray-500 hover:text-red-600 hover:bg-red-50 flex items-center justify-center size-9 flex-shrink-0"
                :disabled="isUnsubscribingGroup(group.id)"
                @click="onLeaveGroup(group)"
              >
                <XMarkIcon class="h-5 w-5" />
              </button>
            </div>
          </div>
          <p v-if="filteredShared.length === 0 && filteredSharedGroupsOnMap.length === 0" class="text-sm text-gray-500 py-4 px-2 text-center">No trackers or groups on your map yet</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed } from 'vue';
import { ArrowPathIcon, EyeIcon, EyeSlashIcon, MagnifyingGlassIcon, PlusIcon, UserGroupIcon, UserMinusIcon, XMarkIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import Loader from 'platform/components/parts/Loader.vue';
import TrackListChevronIcon from './TrackListChevronIcon.vue';

export default {
  name: 'SharedWithMeSidebarContent',
  components: { BaseButton, Loader, TrackListChevronIcon, ArrowPathIcon, EyeIcon, EyeSlashIcon, MagnifyingGlassIcon, PlusIcon, UserGroupIcon, UserMinusIcon, XMarkIcon },
  props: {
    /** When true, show loading state on refresh button */
    refreshing: { type: Boolean, default: false },
    trackers: { type: Array, default: () => [] },
    /** Trackers shared with you that you haven't added yet (from available-to-add shared_with_me) */
    incomingTrackers: { type: Array, default: () => [] },
    /** Groups shared with you that have at least one addable track (from available-to-add shared_with_me_groups) */
    incomingGroups: { type: Array, default: () => [] },
    /** Public groups with at least one addable track (from available-to-add public_groups) */
    incomingPublicGroups: { type: Array, default: () => [] },
    /** Groups shared with you that are on your map (accepted; at least one track subscribed) */
    sharedGroupsOnMap: { type: Array, default: () => [] },
    /** Track ID currently being added (show spinner) */
    addingIncomingId: { type: [String, Number], default: null },
    /** Group ID currently being added (show spinner) */
    addingIncomingGroupId: { type: [String, Number], default: null },
    /** Track ID currently being removed from share (leave share in progress) */
    leavingShareId: { type: [String, Number], default: null },
    /** Set or array of track IDs that are hidden from the map */
    hiddenTrackIds: { type: [Set, Array], default: () => new Set() },
    unsubscribingId: { type: [String, Number], default: null },
    /** Group ID currently being unsubscribed (show spinner on group row) */
    unsubscribingGroupId: { type: [String, Number], default: null },
    /** API client for leave-group call */
    api: { type: Object, default: null },
  },
  emits: ['toggleVisibility', 'unsubscribe', 'leaveShare', 'addIncoming', 'addIncomingGroup', 'leaveGroup', 'toggleGroupVisibility', 'unsubscribeGroup', 'selectTrack', 'selectGroup', 'openDiscover', 'open-shared-list', 'refresh'],
  setup(props, { emit }) {
    const searchQuery = ref('');

    const sharedTrackers = computed(() =>
      props.trackers.filter(
        (t) =>
          t.is_owner === false &&
          ((t.visibility || '') === 'shared' || (t.visibility || '') === 'public')
      )
    );

    const filterByQuery = (list, nameKey = 'name', ownerKey = 'owner_email') => {
      const q = (searchQuery.value || '').trim().toLowerCase();
      if (!q) return list;
      return list.filter(
        (item) =>
          (item[nameKey] || '').toLowerCase().includes(q) ||
          (item[ownerKey] || '').toLowerCase().includes(q)
      );
    };

    const filteredIncoming = computed(() => filterByQuery(props.incomingTrackers || []));
    const filteredIncomingGroups = computed(() => filterByQuery(props.incomingGroups || [], 'name', 'owner_email'));
    const filteredPublicGroups = computed(() => filterByQuery(props.incomingPublicGroups || [], 'name', 'owner_email'));

    const filteredShared = computed(() => filterByQuery(sharedTrackers.value));
    const filteredSharedGroupsOnMap = computed(() =>
      filterByQuery(props.sharedGroupsOnMap || [], 'name', 'owner_email')
    );

    function isAddingGroup(groupId) {
      const id = props.addingIncomingGroupId;
      return id != null && String(id) === String(groupId);
    }

    async function onLeaveGroup(group) {
      if (!props.api?.delete || !group?.id) return;
      try {
        await props.api.delete(`/groups/${group.id}/leave/`);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Left shared group');
        emit('leaveGroup', group);
      } catch (e) {
        const err = props.api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to leave shared group');
      }
    }

    function isHidden(trackId) {
      const hid = props.hiddenTrackIds;
      if (hid instanceof Set) return hid.has(trackId);
      return Array.isArray(hid) && hid.includes(trackId);
    }

    function isGroupHidden(group) {
      const trackIds = group?.track_ids || [];
      if (trackIds.length === 0) return false;
      const hid = props.hiddenTrackIds;
      const has = (id) => (hid instanceof Set ? hid.has(id) : Array.isArray(hid) && hid.includes(id));
      return trackIds.every((id) => has(String(id)));
    }

    function isUnsubscribingGroup(groupId) {
      const id = props.unsubscribingGroupId;
      return id != null && String(id) === String(groupId);
    }

    function isAdding(trackId) {
      const id = props.addingIncomingId;
      return id != null && String(id) === String(trackId);
    }

    function isUnsubscribing(trackId) {
      const id = props.unsubscribingId;
      return id != null && String(id) === String(trackId);
    }

    function isLeavingShare(trackId) {
      const id = props.leavingShareId;
      return id != null && String(id) === String(trackId);
    }

    return {
      searchQuery,
      filteredIncoming,
      filteredIncomingGroups,
      filteredPublicGroups,
      filteredShared,
      filteredSharedGroupsOnMap,
      isHidden,
      isGroupHidden,
      isAdding,
      isAddingGroup,
      isUnsubscribing,
      isUnsubscribingGroup,
      isLeavingShare,
      onLeaveGroup,
    };
  },
};
</script>
