<template>
  <!-- Embedded create: no modal wrapper, for use inside sidebar -->
  <div v-if="embedded && !group" class="p-4 space-y-4">
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Name <span class="text-red-500">*</span></label>
      <input
        v-model="name"
        type="text"
        placeholder="Group name"
        class="w-full border border-gray-300 px-3 py-2 rounded-lg"
      />
      <p v-if="nameError" class="text-sm text-red-600">{{ nameError }}</p>
    </div>
    <div class="flex gap-2">
      <BaseButton variant="primary" color="blue" size="sm" :disabled="saving || !name.trim()" @click="create">
        <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Create
      </BaseButton>
      <BaseButton variant="white" size="sm" @click="$emit('close')">Cancel</BaseButton>
    </div>
  </div>

  <!-- Embedded edit: no modal wrapper, for use inside sidebar -->
  <div v-else-if="embedded && group" class="p-4 space-y-4">
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Name <span class="text-red-500">*</span></label>
      <input
        v-model="name"
        type="text"
        placeholder="Group name"
        class="w-full border border-gray-300 px-3 py-2 rounded-lg"
      />
      <p v-if="nameError" class="text-sm text-red-600">{{ nameError }}</p>
    </div>
    <div class="space-y-2">
      <SearchableCheckboxList
        label="Trackers in Group"
        description="Check the trackers that belong to this group"
        :items="allTrackers"
        v-model="groupTrackIdsSafe"
        :get-item-id="(t) => t.id"
        :get-item-label="(t) => t.name"
        search-placeholder="Search trackers..."
        empty-message="No trackers available"
        selected-count-label="Selected"
      />
    </div>
    <div class="space-y-2">
      <ScrollingSelect
        label="Members"
        :items="membersSelectItems"
        :selected-values="groupMemberIds"
        :loading="loadingUsers"
        max-height="12rem"
        empty-message="No other users found"
        @select="onMemberToggle"
      />
      <p v-if="memberError" class="text-sm text-red-600">{{ memberError }}</p>
    </div>
    <div v-if="group.is_owner" class="space-y-2">
      <div class="flex items-center gap-3">
        <ToggleButton
          :model-value="hiddenInList"
          label="Hide in List"
          size="md"
          @update:model-value="onHiddenInListChange($event)"
        />
        <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="onHiddenInListChange(!hiddenInList)">Hide in List</label>
      </div>
      <p class="text-xs text-gray-500">When on, this group is hidden from the sidebar list. You can unhide it in Settings.</p>
    </div>
    <div class="flex flex-wrap gap-2 pt-2">
      <BaseButton variant="primary" color="blue" size="sm" :disabled="saving || !name.trim()" @click="save">
        <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Save
      </BaseButton>
      <BaseButton variant="white" size="sm" @click="$emit('close')">Close</BaseButton>
      <BaseButton
        v-if="!group.is_owner"
        variant="white"
        size="sm"
        class="text-red-600 hover:bg-red-50"
        @click="$emit('leave')"
      >
        Leave group
      </BaseButton>
    </div>
  </div>

  <BaseModal
    v-else
    :is-open="true"
    :title="group ? 'Edit Group' : 'Create Group'"
    @close="$emit('close')"
  >
    <div class="p-4 space-y-4">
      <div class="space-y-2">
        <label class="text-sm font-medium text-gray-700">Name <span class="text-red-500">*</span></label>
        <input
          v-model="name"
          type="text"
          placeholder="Group name"
          class="w-full border border-gray-300 px-3 py-2 rounded-lg"
        />
        <p v-if="nameError" class="text-sm text-red-600">{{ nameError }}</p>
      </div>
      <template v-if="group">
        <div class="space-y-2">
          <SearchableCheckboxList
            label="Trackers in Group"
            description="Check the trackers that belong to this group"
            :items="allTrackers"
            v-model="groupTrackIdsSafe"
            :get-item-id="(t) => t.id"
            :get-item-label="(t) => t.name"
            search-placeholder="Search trackers..."
            empty-message="No trackers available"
            selected-count-label="Selected"
          />
        </div>
        <div class="space-y-2">
          <ScrollingSelect
            label="Members"
            :items="membersSelectItems"
            :selected-values="groupMemberIds"
            :loading="loadingUsers"
            max-height="12rem"
            empty-message="No other users found"
            @select="onMemberToggle"
          />
          <p v-if="memberError" class="text-sm text-red-600">{{ memberError }}</p>
        </div>
        <div v-if="group.is_owner" class="space-y-2">
          <div class="flex items-center gap-3">
            <ToggleButton
              :model-value="hiddenInList"
              label="Hide in List"
              size="md"
              @update:model-value="onHiddenInListChange($event)"
            />
            <label class="text-sm font-medium text-gray-700 cursor-pointer" @click="onHiddenInListChange(!hiddenInList)">Hide in List</label>
          </div>
          <p class="text-xs text-gray-500">When on, this group is hidden from the sidebar list. You can unhide it in Settings.</p>
        </div>
      </template>
    </div>
    <template #actions>
      <BaseButton variant="white" size="sm" @click="$emit('close')">Close</BaseButton>
      <BaseButton
        v-if="!group"
        variant="primary"
        color="blue"
        size="sm"
        :disabled="saving || !name.trim()"
        @click="create"
      >
        <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Create
      </BaseButton>
      <BaseButton
        v-else
        variant="primary"
        color="blue"
        size="sm"
        :disabled="saving || !name.trim()"
        @click="save"
      >
        <Loader v-if="saving" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Save
      </BaseButton>
    </template>
  </BaseModal>
</template>

<script>
import { ref, computed, watch } from 'vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import Loader from 'platform/components/parts/Loader.vue';
import SearchableCheckboxList from 'platform/components/parts/SearchableCheckboxList.vue';
import ScrollingSelect from 'platform/components/parts/ScrollingSelect.vue';
import ToggleButton from 'platform/components/parts/ToggleButton.vue';

export default {
  name: 'GroupModal',
  components: { BaseModal, BaseButton, Loader, SearchableCheckboxList, ScrollingSelect, ToggleButton },
  props: {
    group: { type: Object, default: null },
    trackers: { type: Array, default: () => [] },
    api: { type: Object, required: true },
    /** When true and group is null, render only the create form (no modal wrapper) for use inside a sidebar. */
    embedded: { type: Boolean, default: false },
  },
  emits: ['close', 'saved', 'refreshed', 'leave', 'hidden-in-list-changed'],
  setup(props, { emit }) {
    const name = ref(props.group?.name || '');
    const nameError = ref('');
    const saving = ref(false);
    const hiddenInList = ref(props.group?.hidden_in_list === true);
    const groupTrackIds = ref([]);
    const groupTrackIdsSafe = computed({
      get: () => groupTrackIds.value ?? [],
      set: (v) => { groupTrackIds.value = Array.isArray(v) ? v : []; },
    });
    const groupMemberIds = ref([]);
    const allUsers = ref([]);
    const loadingUsers = ref(false);
    const memberError = ref('');

    const membersSelectItems = computed(() =>
      (allUsers.value || []).map((u) => ({ id: u.id, label: u.email || '' })).filter((u) => u.label)
    );

    async function fetchUsers() {
      loadingUsers.value = true;
      try {
        const res = await fetch('/api/users/', { credentials: 'include' });
        const data = await res.json();
        allUsers.value = Array.isArray(data?.users) ? data.users : [];
      } catch {
        allUsers.value = [];
      } finally {
        loadingUsers.value = false;
      }
    }

    watch(() => props.group, (g) => {
      name.value = g?.name || '';
      nameError.value = '';
      memberError.value = '';
      hiddenInList.value = g?.hidden_in_list === true;
      groupTrackIds.value = [...(g?.track_ids || [])];
      groupMemberIds.value = (g?.member_ids || []).map((id) => String(id));
      if (g?.id) fetchUsers();
    }, { immediate: true });

    const allTrackers = computed(() => props.trackers ?? []);

    async function create() {
      nameError.value = '';
      if (!name.value.trim()) return;
      saving.value = true;
      try {
        await props.api.post('/groups/', { name: name.value.trim() });
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Group created');
        emit('saved');
      } catch (e) {
        const err = props.api.handleError?.(e);
        nameError.value = err?.message || 'Failed to create group';
      } finally {
        saving.value = false;
      }
    }

    async function save() {
      nameError.value = '';
      if (!props.group?.id || !name.value.trim()) return;
      saving.value = true;
      try {
        await props.api.patch(`/groups/${props.group.id}/`, { name: name.value.trim(), hidden_in_list: hiddenInList.value });
        const currentIds = new Set((groupTrackIds.value ?? []).map((id) => String(id)));
        const previousIds = new Set((props.group?.track_ids || []).map((id) => String(id)));
        const toRemove = (props.group?.track_ids || []).filter((id) => !currentIds.has(String(id)));
        const toAdd = groupTrackIds.value.filter((id) => !previousIds.has(String(id)));
        for (const trackId of toRemove) {
          await props.api.delete(`/groups/${props.group.id}/tracks/${trackId}/`);
        }
        for (const trackId of toAdd) {
          await props.api.post(`/groups/${props.group.id}/tracks/`, { track_id: trackId });
        }
        const prevMemberIds = new Set((props.group?.member_ids || []).map((id) => String(id)));
        const currMemberIds = new Set(groupMemberIds.value);
        const membersToRemove = [...prevMemberIds].filter((id) => !currMemberIds.has(id));
        const membersToAdd = [...currMemberIds].filter((id) => !prevMemberIds.has(id));
        for (const userId of membersToRemove) {
          await props.api.delete(`/groups/${props.group.id}/members/${userId}/`);
        }
        for (const userId of membersToAdd) {
          const user = allUsers.value.find((u) => String(u.id) === userId);
          if (user?.email) await props.api.post(`/groups/${props.group.id}/members/`, { email: user.email });
        }
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Group updated');
        emit('saved');
      } catch (e) {
        const err = props.api.handleError?.(e);
        nameError.value = err?.message || 'Failed to save';
      } finally {
        saving.value = false;
      }
    }

    function onMemberToggle(item) {
      if (!item) return;
      const userId = String(item.value != null ? item.value : item.id);
      memberError.value = '';
      const idx = groupMemberIds.value.indexOf(userId);
      if (idx >= 0) {
        groupMemberIds.value = groupMemberIds.value.filter((id) => id !== userId);
      } else {
        groupMemberIds.value = [...groupMemberIds.value, userId];
      }
    }

    function onHiddenInListChange(value) {
      hiddenInList.value = value;
      if (props.group?.id) {
        emit('hidden-in-list-changed', { groupId: props.group.id, hiddenInList: value });
      }
    }

    return {
      name,
      nameError,
      saving,
      hiddenInList,
      groupTrackIdsSafe,
      allTrackers,
      groupMemberIds,
      create,
      save,
      allUsers,
      loadingUsers,
      membersSelectItems,
      memberError,
      onMemberToggle,
      onHiddenInListChange,
    };
  },
};
</script>
