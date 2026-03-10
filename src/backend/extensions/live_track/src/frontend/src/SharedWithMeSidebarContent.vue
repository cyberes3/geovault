<template>
  <div class="flex-1 min-h-0 flex flex-col p-4">
    <div class="relative flex-shrink-0 mb-2">
      <input
        v-model="searchQuery"
        type="text"
        placeholder="Search by name or owner..."
        class="w-full border border-gray-300 px-3 py-2 rounded-lg pl-9"
      />
      <MagnifyingGlassIcon class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
    </div>
    <div class="flex-shrink-0 mb-3">
      <BaseButton variant="white" size="sm" class="w-full" @click="$emit('openDiscover')">
        Add public trackers
      </BaseButton>
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
            <CloudIcon class="h-5 w-5 text-gray-500 flex-shrink-0" />
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-900 truncate" :title="track.name">{{ track.name }}</div>
              <div class="text-xs text-gray-500 truncate" :title="track.owner_email">{{ track.owner_email }}</div>
            </div>
            <div class="flex items-center gap-1 flex-shrink-0">
              <button
                type="button"
                title="Reject (remove me from share; you won't see this in Incoming again)"
                class="p-2 rounded-lg text-gray-500 hover:text-red-600 hover:bg-red-50 disabled:opacity-50 disabled:cursor-not-allowed"
                :disabled="isAdding(track.id) || isLeavingShare(track.id)"
                @click="$emit('leaveShare', track.id)"
              >
                <Loader v-if="isLeavingShare(track.id)" size="sm" layout="inline" :show-message="false" class="h-5 w-5" />
                <XMarkIcon v-else class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="Add to my trackers"
                class="p-2 rounded-lg text-blue-600 hover:bg-blue-50 flex-shrink-0 disabled:opacity-50 disabled:cursor-not-allowed"
                :disabled="isAdding(track.id) || isLeavingShare(track.id)"
                @click="$emit('addIncoming', track)"
              >
                <Loader v-if="isAdding(track.id)" size="sm" layout="inline" :show-message="false" class="h-5 w-5" />
                <PlusIcon v-else class="h-5 w-5" />
              </button>
            </div>
          </div>
          <p v-if="filteredIncoming.length === 0" class="text-sm text-gray-500 py-4 px-2 text-center">No incoming shares</p>
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
            class="flex items-center gap-2 p-3 rounded-lg border border-gray-200 bg-white hover:bg-blue-50/50 hover:border-blue-200/80 transition-colors"
          >
            <CloudIcon class="h-5 w-5 text-gray-500 flex-shrink-0" />
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium text-gray-900 truncate" :title="track.name">{{ track.name }}</div>
              <div class="text-xs text-gray-500 truncate" :title="track.owner_email">{{ track.owner_email }}</div>
            </div>
            <div class="flex items-center gap-1 flex-shrink-0">
              <button
                type="button"
                :title="isHidden(track.id) ? 'Show on map' : 'Hide on map'"
                class="p-2 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100"
                @click="$emit('toggleVisibility', track.id)"
              >
                <EyeIcon v-if="isHidden(track.id)" class="h-5 w-5" />
                <EyeSlashIcon v-else class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="Unsubscribe (remove from my list; you can add again from Incoming)"
                class="p-2 rounded-lg text-gray-500 hover:text-red-600 hover:bg-red-50"
                :disabled="isUnsubscribing(track.id) || isLeavingShare(track.id)"
                @click="$emit('unsubscribe', track.id)"
              >
                <TrashIcon v-if="!isUnsubscribing(track.id)" class="h-5 w-5" />
                <Loader v-else size="sm" layout="inline" :show-message="false" class="h-5 w-5" />
              </button>
              <button
                type="button"
                title="Remove me from share (owner will no longer have you as recipient; you won't see this in Incoming again)"
                class="p-2 rounded-lg text-gray-500 hover:text-amber-600 hover:bg-amber-50"
                :disabled="isUnsubscribing(track.id) || isLeavingShare(track.id)"
                @click="$emit('leaveShare', track.id)"
              >
                <Loader v-if="isLeavingShare(track.id)" size="sm" layout="inline" :show-message="false" class="h-5 w-5" />
                <UserMinusIcon v-else class="h-5 w-5" />
              </button>
            </div>
          </div>
          <p v-if="filteredShared.length === 0" class="text-sm text-gray-500 py-4 px-2 text-center">No trackers on your map yet</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed } from 'vue';
import { CloudIcon, EyeIcon, EyeSlashIcon, TrashIcon, MagnifyingGlassIcon, PlusIcon, UserMinusIcon, XMarkIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import Loader from 'platform/components/parts/Loader.vue';

export default {
  name: 'SharedWithMeSidebarContent',
  components: { BaseButton, Loader, CloudIcon, EyeIcon, EyeSlashIcon, TrashIcon, MagnifyingGlassIcon, PlusIcon, UserMinusIcon, XMarkIcon },
  props: {
    trackers: { type: Array, default: () => [] },
    /** Trackers shared with you that you haven't added yet (from available-to-add shared_with_me) */
    incomingTrackers: { type: Array, default: () => [] },
    /** Track ID currently being added (show spinner) */
    addingIncomingId: { type: [String, Number], default: null },
    /** Track ID currently being removed from share (leave share in progress) */
    leavingShareId: { type: [String, Number], default: null },
    /** Set or array of track IDs that are hidden from the map */
    hiddenTrackIds: { type: [Set, Array], default: () => new Set() },
    unsubscribingId: { type: [String, Number], default: null },
  },
  emits: ['toggleVisibility', 'unsubscribe', 'leaveShare', 'addIncoming', 'openDiscover'],
  setup(props) {
    const searchQuery = ref('');

    const sharedTrackers = computed(() =>
      props.trackers.filter((t) => t.is_owner === false && (t.visibility || '') === 'shared')
    );

    const filterByQuery = (list) => {
      const q = (searchQuery.value || '').trim().toLowerCase();
      if (!q) return list;
      return list.filter(
        (t) =>
          (t.name || '').toLowerCase().includes(q) ||
          (t.owner_email || '').toLowerCase().includes(q)
      );
    };

    const filteredIncoming = computed(() => filterByQuery(props.incomingTrackers || []));

    const filteredShared = computed(() => filterByQuery(sharedTrackers.value));

    function isHidden(trackId) {
      const hid = props.hiddenTrackIds;
      if (hid instanceof Set) return hid.has(trackId);
      return Array.isArray(hid) && hid.includes(trackId);
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

    return { searchQuery, filteredIncoming, filteredShared, isHidden, isAdding, isUnsubscribing, isLeavingShare };
  },
};
</script>
