<template>
  <div class="flex-1 min-h-0 flex flex-col p-4">
    <!-- List view -->
    <template v-if="view === 'list'">
      <div class="flex-shrink-0 mb-3 flex items-center gap-2">
        <BaseButton variant="primary" color="blue" size="sm" class="flex-1" @click="view = 'create'">
          Create Group
        </BaseButton>
        <button
          type="button"
          title="Refresh"
          class="p-2 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100 flex-shrink-0 disabled:opacity-50 flex items-center justify-center size-9"
          :disabled="refreshing"
          @click="$emit('refreshed')"
        >
          <ArrowPathIcon :class="['h-5 w-5', refreshing ? 'animate-spin' : '']" />
        </button>
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
            <div
              v-if="g.owner_email"
              class="text-xs text-gray-500 truncate"
              :title="'Shared by ' + g.owner_email"
            >
              Shared by {{ g.owner_email }}
            </div>
            <div class="text-xs text-gray-500">{{ (g.track_ids || []).length }} {{ (g.track_ids || []).length === 1 ? 'tracker' : 'trackers' }}</div>
          </div>
          <div class="flex items-center gap-1 flex-shrink-0">
            <template v-if="g.is_owner">
              <button
                type="button"
                title="Edit Group"
                class="p-2 rounded-lg text-gray-500 hover:text-blue-600 hover:bg-blue-50"
                @click="openEdit(g)"
              >
                <PencilIcon class="h-5 w-5" />
              </button>
            </template>
            <template v-else>
              <button
                type="button"
                :title="isGroupHidden(g) ? 'Show on Map' : 'Hide on Map'"
                class="p-2 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100"
                @click="$emit('toggleGroupVisibility', g)"
              >
                <EyeIcon v-if="isGroupHidden(g)" class="h-5 w-5" />
                <EyeSlashIcon v-else class="h-5 w-5" />
              </button>
            </template>
          </div>
        </div>
        <p v-if="sortedGroups.length === 0" class="text-sm text-gray-500 py-2">No Groups Yet. Create One to Get Started.</p>
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
        @hidden-in-list-changed="$emit('hidden-in-list-changed', $event)"
      />
    </div>
  </div>
</template>

<script>
import { ref, computed, watch } from 'vue';
import { ArrowPathIcon, EyeIcon, EyeSlashIcon, PencilIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import GroupModal from './GroupModal.vue';

export default {
  name: 'GroupsSidebarContent',
  components: { BaseButton, GroupModal, ArrowPathIcon, EyeIcon, EyeSlashIcon, PencilIcon },
  props: {
    /** When true, show loading state on refresh button */
    refreshing: { type: Boolean, default: false },
    groups: { type: Array, default: () => [] },
    trackers: { type: Array, default: () => [] },
    api: { type: Object, required: true },
    /** When set, open in edit view for this group id. */
    initialGroupId: { type: [String, Number], default: null },
    /** Set or array of group ids that are hidden on the map (eye = show, eye-slash = hide). */
    hiddenGroupIds: { type: [Set, Array], default: () => new Set() },
  },
  emits: ['saved', 'refreshed', 'leave', 'hidden-in-list-changed', 'toggleGroupVisibility'],
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

    function onCreateSaved(payload) {
      emit('saved', payload);
      view.value = 'list';
    }

    function onEditSaved(payload) {
      emit('saved', payload);
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

    function isGroupHidden(group) {
      const groupIds = props.hiddenGroupIds;
      if (!group?.id) return false;
      const id = String(group.id);
      if (groupIds instanceof Set) return groupIds.has(id);
      return Array.isArray(groupIds) && groupIds.includes(id);
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
      isGroupHidden,
    };
  },
};
</script>
