<template>
  <BaseModal
    :is-open="true"
    title="Public Trackers"
    @close="$emit('close')"
  >
    <div class="h-full flex flex-col gap-4 p-4 min-h-0">
      <p class="text-sm text-gray-600 flex-shrink-0">Add public trackers and groups. Incoming shares appear in Shared With Me.</p>
      <div class="relative flex-shrink-0">
        <input
          v-model="searchQuery"
          type="text"
          placeholder="Search by name or owner..."
          class="w-full border border-gray-300 px-3 py-2 rounded-lg pl-9"
        />
        <MagnifyingGlassIcon class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
      </div>
      <div v-if="loading" class="flex flex-1 min-h-0 justify-center items-center py-8">
        <Loader size="md" message="Loading..." />
      </div>
      <div v-else class="flex-1 min-h-0 overflow-y-auto border border-gray-200 rounded-lg divide-y divide-gray-100">
        <div
          v-for="item in filteredList"
          :key="itemKey(item)"
          class="flex items-center gap-3 px-3 py-2 hover:bg-gray-50"
        >
          <div class="flex-1 min-w-0">
            <div class="text-sm font-medium text-gray-900 truncate" :title="item.name">{{ item.name }}</div>
            <div class="text-xs text-gray-500 truncate" :title="item.owner_email">
              {{ item.owner_email }}
              <span v-if="item.kind === 'group'"> - {{ item.track_ids.length }} {{ item.track_ids.length === 1 ? 'tracker' : 'trackers' }}</span>
            </div>
          </div>
          <button
            v-if="subscribingIds.has(itemKey(item))"
            type="button"
            disabled
            class="p-2 rounded-lg text-gray-400 flex items-center justify-center size-9 flex-shrink-0 cursor-wait"
            title="Adding..."
          >
            <Loader size="xs" layout="inline" :show-message="false" />
          </button>
          <button
            v-else-if="addedPhase[itemKey(item)] === 'checkmark'"
            type="button"
            disabled
            class="p-2 rounded-lg flex items-center justify-center size-9 flex-shrink-0 cursor-default"
            title="Added"
          >
            <CheckCircleIcon class="h-5 w-5 text-green-600" />
          </button>
          <button
            v-else-if="addedPhase[itemKey(item)] === 'remove'"
            type="button"
            :disabled="unsubscribingIds.has(itemKey(item))"
            class="p-2 rounded-lg text-red-600 hover:bg-red-50 disabled:opacity-50 flex items-center justify-center size-9 flex-shrink-0"
            title="Remove from my trackers"
            @click="removeOne(item)"
          >
            <Loader v-if="unsubscribingIds.has(itemKey(item))" size="xs" layout="inline" :show-message="false" />
            <XMarkIcon v-else class="h-5 w-5" />
          </button>
          <button
            v-else
            type="button"
            class="p-2 rounded-lg text-blue-600 hover:bg-blue-50 flex items-center justify-center size-9 flex-shrink-0"
            :title="item.kind === 'group' ? 'Add Group' : 'Add Tracker'"
            @click="subscribeOne(item)"
          >
            <PlusIcon class="h-5 w-5" />
          </button>
        </div>
        <p v-if="!loading && filteredList.length === 0" class="px-3 py-4 text-sm text-gray-500">No public trackers or groups available</p>
      </div>
    </div>
    <template #actions>
      <button
        type="button"
        title="Refresh"
        class="p-2 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100 disabled:opacity-50 flex-shrink-0 flex items-center justify-center size-9"
        :disabled="loading"
        @click="onRefresh"
      >
        <ArrowPathIcon :class="['h-5 w-5', loading ? 'animate-spin' : '']" />
      </button>
      <BaseButton variant="white" size="sm" @click="$emit('close')">Close</BaseButton>
    </template>
  </BaseModal>
</template>

<script>
import { ref, computed, onMounted } from 'vue';
import { ArrowPathIcon, PlusIcon, MagnifyingGlassIcon, XMarkIcon } from '@heroicons/vue/24/outline';
import { CheckCircleIcon } from '@heroicons/vue/24/solid';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import Loader from 'platform/components/parts/Loader.vue';

export default {
  name: 'DiscoverTrackersModal',
  components: { BaseModal, BaseButton, Loader, ArrowPathIcon, PlusIcon, MagnifyingGlassIcon, XMarkIcon, CheckCircleIcon },
  props: {
    api: { type: Object, required: true },
  },
  emits: ['close', 'saved'],
  setup(props, { emit }) {
    const available = ref({ public: [], public_groups: [], shared_with_me: [] });
    const loading = ref(true);
    const searchQuery = ref('');
    const subscribingIds = ref(new Set());
    /** After add success: 'checkmark' for 1s, then 'remove' (show X to unsubscribe). */
    const addedPhase = ref({});
    const unsubscribingIds = ref(new Set());

    const combinedList = computed(() => {
      const publicTracks = (available.value.public || []).map((t) => ({
        kind: 'tracker',
        ...t,
      }));
      const publicGroups = (available.value.public_groups || []).map((g) => ({
        kind: 'group',
        ...g,
        track_ids: Array.isArray(g.track_ids) ? g.track_ids : [],
      }));
      return [...publicTracks, ...publicGroups];
    });

    const filteredList = computed(() => {
      const q = (searchQuery.value || '').trim().toLowerCase();
      if (!q) return combinedList.value;
      return combinedList.value.filter(
        (t) =>
          (t.name || '').toLowerCase().includes(q) ||
          (t.owner_email || '').toLowerCase().includes(q)
      );
    });

    async function fetchAvailable() {
      loading.value = true;
      try {
        const res = await props.api.get('/trackers/available-to-add/');
        available.value = res.data || { public: [], public_groups: [], shared_with_me: [] };
      } catch (e) {
        const err = props.api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load');
        available.value = { public: [], public_groups: [], shared_with_me: [] };
      } finally {
        loading.value = false;
      }
    }

    function onRefresh() {
      fetchAvailable();
    }

    onMounted(() => {
      fetchAvailable();
    });

    function itemKey(item) {
      return `${item?.kind || 'tracker'}:${item?.id || ''}`;
    }

    async function removeOne(item) {
      if (!item?.id) return;
      const key = itemKey(item);
      unsubscribingIds.value = new Set([...unsubscribingIds.value, key]);
      try {
        if (item.kind === 'group') {
          for (const trackId of item.track_ids || []) {
            await props.api.delete(`/trackers/${trackId}/subscribe/`);
          }
          if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Group removed');
          available.value = {
            ...available.value,
            public_groups: (available.value.public_groups || []).filter((x) => x.id !== item.id),
          };
        } else {
          await props.api.delete(`/trackers/${item.id}/subscribe/`);
          if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Tracker removed');
          available.value = {
            ...available.value,
            public: (available.value.public || []).filter((x) => x.id !== item.id),
          };
        }
        emit('saved');
      } catch (e) {
        const err = props.api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to remove');
      } finally {
        const next = new Set(unsubscribingIds.value);
        next.delete(key);
        unsubscribingIds.value = next;
        addedPhase.value = Object.fromEntries(Object.entries(addedPhase.value).filter(([k]) => k !== key));
      }
    }

    async function subscribeOne(item) {
      if (!item?.id) return;
      const key = itemKey(item);
      subscribingIds.value = new Set([...subscribingIds.value, key]);
      try {
        if (item.kind === 'group') {
          for (const trackId of item.track_ids || []) {
            await props.api.post(`/trackers/${trackId}/subscribe/`);
          }
          if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Group added');
        } else {
          await props.api.post(`/trackers/${item.id}/subscribe/`);
          if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Tracker added');
        }
        emit('saved');
        addedPhase.value = { ...addedPhase.value, [key]: 'checkmark' };
        setTimeout(() => {
          addedPhase.value = { ...addedPhase.value, [key]: 'remove' };
        }, 1000);
      } catch (e) {
        const err = props.api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to add');
      } finally {
        const next = new Set(subscribingIds.value);
        next.delete(key);
        subscribingIds.value = next;
      }
    }

    return { searchQuery, loading, filteredList, subscribingIds, unsubscribingIds, addedPhase, itemKey, subscribeOne, removeOne, onRefresh };
  },
};
</script>
