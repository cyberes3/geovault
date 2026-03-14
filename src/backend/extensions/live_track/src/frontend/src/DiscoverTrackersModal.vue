<template>
  <BaseModal
    :is-open="true"
    title="Discover Trackers"
    @close="$emit('close')"
  >
    <div class="p-4 space-y-4">
      <p class="text-sm text-gray-600">Add public trackers. Incoming shares appear in Shared with me.</p>
      <div class="relative">
        <input
          v-model="searchQuery"
          type="text"
          placeholder="Search by name or owner..."
          class="w-full border border-gray-300 px-3 py-2 rounded-lg pl-9"
        />
        <MagnifyingGlassIcon class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-gray-400" />
      </div>
      <div v-if="loading" class="flex justify-center py-8">
        <Loader size="md" message="Loading..." />
      </div>
      <div v-else class="max-h-60 overflow-y-auto border border-gray-200 rounded-lg divide-y divide-gray-100">
        <div
          v-for="t in filteredList"
          :key="t.id"
          class="flex items-center gap-3 px-3 py-2 hover:bg-gray-50"
        >
          <div class="flex-1 min-w-0">
            <div class="text-sm font-medium text-gray-900 truncate" :title="t.name">{{ t.name }}</div>
            <div class="text-xs text-gray-500 truncate" :title="t.owner_email">{{ t.owner_email }}</div>
          </div>
          <button
            type="button"
            :disabled="subscribingIds.has(t.id)"
            class="p-2 rounded-lg text-blue-600 hover:bg-blue-50 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center size-9 flex-shrink-0"
            title="Add Tracker"
            @click="subscribeOne(t)"
          >
            <PlusIcon v-if="!subscribingIds.has(t.id)" class="h-5 w-5" />
            <Loader v-else size="xs" layout="inline" :show-message="false" />
          </button>
        </div>
        <p v-if="!loading && filteredList.length === 0" class="px-3 py-4 text-sm text-gray-500">No public trackers available</p>
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
        <Loader v-if="loading" size="xs" layout="inline" :show-message="false" />
        <ArrowPathIcon v-else class="h-5 w-5" />
      </button>
      <BaseButton variant="white" size="sm" @click="$emit('close')">Close</BaseButton>
    </template>
  </BaseModal>
</template>

<script>
import { ref, computed, onMounted } from 'vue';
import { ArrowPathIcon, PlusIcon, MagnifyingGlassIcon } from '@heroicons/vue/24/outline';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import Loader from 'platform/components/parts/Loader.vue';

export default {
  name: 'DiscoverTrackersModal',
  components: { BaseModal, BaseButton, Loader, ArrowPathIcon, PlusIcon, MagnifyingGlassIcon },
  props: {
    api: { type: Object, required: true },
  },
  emits: ['close', 'saved'],
  setup(props, { emit }) {
    const available = ref({ public: [], shared_with_me: [] });
    const loading = ref(true);
    const searchQuery = ref('');
    const subscribingIds = ref(new Set());

    const combinedList = computed(() => available.value.public || []);

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
        available.value = res.data || { public: [], shared_with_me: [] };
      } catch (e) {
        const err = props.api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to load');
        available.value = { public: [], shared_with_me: [] };
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

    async function subscribeOne(tracker) {
      if (!tracker?.id) return;
      subscribingIds.value = new Set([...subscribingIds.value, tracker.id]);
      try {
        await props.api.post(`/trackers/${tracker.id}/subscribe/`);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Tracker added');
        available.value = {
          ...available.value,
          public: (available.value.public || []).filter((x) => x.id !== tracker.id),
        };
        emit('saved');
      } catch (e) {
        const err = props.api.handleError?.(e);
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error(err?.message || 'Failed to add');
      } finally {
        const next = new Set(subscribingIds.value);
        next.delete(tracker.id);
        subscribingIds.value = next;
      }
    }

    return { searchQuery, loading, filteredList, subscribingIds, subscribeOne, onRefresh };
  },
};
</script>
