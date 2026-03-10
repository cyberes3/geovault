<template>
  <div class="flex-1 min-h-0 flex flex-col p-4">
    <!-- List view -->
    <template v-if="view === 'list'">
      <div class="flex-shrink-0 mb-3">
        <BaseButton variant="primary" color="blue" size="sm" class="w-full" @click="view = 'create'">
          Create group
        </BaseButton>
      </div>
      <div class="flex-1 min-h-0 overflow-y-auto space-y-2">
        <div
          v-for="g in sortedGroups"
          :key="g.id"
          :class="[
            'flex items-center gap-2 p-3 rounded-lg border transition-colors',
            view === 'edit' && selectedGroup && String(g.id) === String(selectedGroup.id)
              ? 'border-blue-500 bg-blue-50'
              : 'border-gray-200 bg-white hover:bg-gray-50'
          ]"
        >
          <div class="flex-1 min-w-0">
            <div class="text-sm font-medium text-gray-900 truncate" :title="g.name">{{ g.name }}</div>
            <div class="text-xs text-gray-500">{{ (g.track_ids || []).length }} tracker(s)</div>
          </div>
          <div class="flex items-center gap-1 flex-shrink-0">
            <template v-if="g.is_owner">
              <button
                type="button"
                title="Edit group"
                class="p-2 rounded-lg text-gray-500 hover:text-blue-600 hover:bg-blue-50"
                @click="openEdit(g)"
              >
                <PencilIcon class="h-5 w-5" />
              </button>
            </template>
            <template v-else>
              <button
                type="button"
                title="Leave group"
                class="p-2 rounded-lg text-gray-500 hover:text-red-600 hover:bg-red-50 text-sm"
                @click="$emit('leave', g)"
              >
                Leave
              </button>
            </template>
          </div>
        </div>
        <p v-if="sortedGroups.length === 0" class="text-sm text-gray-500 py-2">No groups yet. Create one to get started.</p>
      </div>
    </template>

    <!-- Create view -->
    <div v-else-if="view === 'create'" class="flex-1 min-h-0 overflow-y-auto">
      <GroupModal
        embedded
        :group="null"
        :trackers="trackers"
        :api="api"
        @close="view = 'list'"
        @saved="onCreateSaved"
      />
    </div>

    <!-- Edit view -->
    <div v-else-if="view === 'edit' && selectedGroup" class="flex-1 min-h-0 overflow-y-auto">
      <GroupModal
        embedded
        :group="selectedGroup"
        :trackers="trackers"
        :api="api"
        @close="view = 'list'; selectedGroup = null"
        @saved="onEditSaved"
        @refreshed="onEditRefreshed"
        @leave="onLeaveGroup"
      />
    </div>
  </div>
</template>

<script>
import { ref, computed, watch } from 'vue';
import { PencilIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import GroupModal from './GroupModal.vue';

export default {
  name: 'GroupsSidebarContent',
  components: { BaseButton, GroupModal, PencilIcon },
  props: {
    groups: { type: Array, default: () => [] },
    trackers: { type: Array, default: () => [] },
    api: { type: Object, required: true },
    /** When set, open in edit view for this group id. */
    initialGroupId: { type: [String, Number], default: null },
  },
  emits: ['saved', 'refreshed', 'leave'],
  setup(props, { emit }) {
    const view = ref('list');
    const selectedGroup = ref(null);

    const sortedGroups = computed(() =>
      [...(props.groups || [])].sort((a, b) => (a.name || '').localeCompare(b.name || ''))
    );

    watch(
      () => props.initialGroupId,
      (id) => {
        if (id != null && id !== '') {
          const g = (props.groups || []).find((gr) => String(gr.id) === String(id));
          if (g) {
            selectedGroup.value = g;
            view.value = 'edit';
          }
        }
      },
      { immediate: true }
    );

    watch(
      () => props.groups,
      (groups) => {
        if (view.value === 'edit' && selectedGroup.value?.id) {
          const updated = (groups || []).find((g) => String(g.id) === String(selectedGroup.value.id));
          if (updated) selectedGroup.value = updated;
        }
      },
      { deep: true }
    );

    function openEdit(group) {
      selectedGroup.value = group;
      view.value = 'edit';
    }

    function onCreateSaved() {
      emit('saved');
      view.value = 'list';
    }

    function onEditSaved() {
      emit('saved');
      view.value = 'list';
      selectedGroup.value = null;
    }

    function onEditRefreshed() {
      emit('refreshed');
    }

    function onLeaveGroup() {
      emit('leave', selectedGroup.value);
      view.value = 'list';
      selectedGroup.value = null;
    }

    return {
      view,
      selectedGroup,
      sortedGroups,
      openEdit,
      onCreateSaved,
      onEditSaved,
      onEditRefreshed,
      onLeaveGroup,
    };
  },
};
</script>
