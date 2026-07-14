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
            selectedGroup && String(g.id) === String(selectedGroup.id)
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
        @group-hidden-changed="$emit('group-hidden-changed', $event)"
      />
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, ref, computed, watch, type PropType } from 'vue';
import { ArrowPathIcon, PencilIcon } from '@heroicons/vue/24/outline';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import GroupModal from './GroupModal.vue';
import type { LiveTrack, LiveTrackGroup } from './types/track';
import type { ExtensionApi } from './types/extension-api';

type SidebarView = 'list' | 'create' | 'edit';

export default defineComponent({
  name: 'GroupsSidebarContent',
  components: { BaseButton, GroupModal, ArrowPathIcon, PencilIcon },
  props: {
    /** When true, show loading state on refresh button */
    refreshing: { type: Boolean, default: false },
    groups: { type: Array as PropType<LiveTrackGroup[]>, default: () => [] },
    trackers: { type: Array as PropType<LiveTrack[]>, default: () => [] },
    api: { type: Object as PropType<ExtensionApi>, required: true },
    /** When set, open in edit view for this group id. */
    initialGroupId: { type: [String, Number] as PropType<string | number | null>, default: null },
  },
  emits: ['saved', 'refreshed', 'leave', 'group-hidden-changed'],
  setup(props, { emit }) {
    const view = ref<SidebarView>('list');
    const selectedGroup = ref<LiveTrackGroup | null>(null);

    const sortedGroups = computed((): LiveTrackGroup[] =>
      [...props.groups].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
    );

    watch(
      () => props.initialGroupId,
      (id) => {
        if (id != null && id !== '') {
          const g = props.groups.find((gr) => String(gr.id) === String(id));
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
        if (view.value === 'edit' && selectedGroup.value?.id != null) {
          const updated = groups.find((g) => String(g.id) === String(selectedGroup.value?.id));
          if (updated) selectedGroup.value = updated;
        }
      },
      { deep: true }
    );

    function openEdit(group: LiveTrackGroup): void {
      selectedGroup.value = group;
      view.value = 'edit';
    }

    function onCreateSaved(payload: unknown): void {
      emit('saved', payload);
      view.value = 'list';
    }

    function onEditSaved(payload: unknown): void {
      emit('saved', payload);
      view.value = 'list';
      selectedGroup.value = null;
    }

    function onEditRefreshed(): void {
      emit('refreshed');
    }

    function onLeaveGroup(): void {
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
});
</script>
