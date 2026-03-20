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
      <SharingSection
        variant="group"
        :visibility="visibility"
        :shared-with-select-items="sharedWithSelectItems"
        :shared-with-select-values="sharedWithEmailsForSelect"
        :loading-users="loadingUsers"
        :world-share-enabled="worldShareEnabled"
        :full-world-share-url="fullWorldShareUrl"
        :disabled="!group.is_owner"
        @update:visibility="visibility = $event"
        @update:shared-with-emails="sharedWithEmails = $event"
        @update:world-share-enabled="worldShareEnabled = $event"
        @copy="copyWorldShareUrl"
      />
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
        Leave Shared Group
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
          <SharingSection
            variant="group"
            :visibility="visibility"
            :shared-with-select-items="sharedWithSelectItems"
            :shared-with-select-values="sharedWithEmailsForSelect"
            :loading-users="loadingUsers"
            :world-share-enabled="worldShareEnabled"
            :full-world-share-url="fullWorldShareUrl"
            :disabled="!group.is_owner"
            @update:visibility="visibility = $event"
            @update:shared-with-emails="sharedWithEmails = $event"
            @update:world-share-enabled="worldShareEnabled = $event"
            @copy="copyWorldShareUrl"
          />
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
import SharingSection from './SharingSection.vue';

export default {
  name: 'GroupModal',
  components: { BaseModal, BaseButton, Loader, SearchableCheckboxList, ScrollingSelect, ToggleButton, SharingSection },
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
    const allUsers = ref([]);
    const loadingUsers = ref(false);

    const sharedWithSelectItems = computed(() =>
      (allUsers.value || [])
        .map((u) => ({ value: (u.email || '').toLowerCase(), label: u.email || '' }))
        .filter((u) => u.value)
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

    const visibility = ref(props.group?.visibility || 'private');
    const sharedWithEmails = ref([]);
    const worldShareEnabled = ref(!!(props.group?.world_share_id));
    const worldShareUrl = ref(props.group?.world_share_url || '');
    const fullWorldShareUrl = computed(() => {
      if (!worldShareUrl.value) return '';
      const origin = typeof window !== 'undefined' ? window.location.origin : '';
      return origin ? `${origin}${worldShareUrl.value}` : worldShareUrl.value;
    });
    async function copyWorldShareUrl() {
      if (!fullWorldShareUrl.value) return;
      const text = fullWorldShareUrl.value;
      const showSuccess = () => {
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Link copied');
      };
      if (navigator.clipboard?.writeText) {
        try {
          await navigator.clipboard.writeText(text);
          showSuccess();
        } catch {
          fallbackCopy(text, showSuccess);
        }
      } else {
        fallbackCopy(text, showSuccess);
      }
    }
    function fallbackCopy(text, onSuccess) {
      const textArea = document.createElement('textarea');
      textArea.value = text;
      textArea.style.position = 'fixed';
      textArea.style.opacity = '0';
      document.body.appendChild(textArea);
      textArea.select();
      try {
        const ok = document.execCommand('copy');
        if (ok) onSuccess();
      } catch {
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.error('Could not copy; please copy the link manually.');
      }
      document.body.removeChild(textArea);
    }
    const sharedWithEmailsForSelect = computed(() =>
      (sharedWithEmails.value || []).map((e) => String(e || '').toLowerCase()).filter(Boolean)
    );

    watch(() => props.group, (g) => {
      name.value = g?.name || '';
      nameError.value = '';
      hiddenInList.value = g?.hidden_in_list === true;
      visibility.value = g?.visibility || 'private';
      sharedWithEmails.value = Array.isArray(g?.shared_with_emails) ? [...g.shared_with_emails] : [];
      worldShareEnabled.value = !!(g?.world_share_id);
      worldShareUrl.value = g?.world_share_url || '';
      groupTrackIds.value = [...(g?.track_ids || [])];
      if (g?.id) fetchUsers();
    }, { immediate: true });

    const allTrackers = computed(() => props.trackers ?? []);

    async function create() {
      nameError.value = '';
      if (!name.value.trim()) return;
      saving.value = true;
      try {
        const res = await props.api.post('/groups/', { name: name.value.trim() });
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Group created');
        emit('saved', { action: 'created', group: res?.data || null });
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
        const payload = {
          name: name.value.trim(),
          hidden_in_list: hiddenInList.value,
          visibility: visibility.value,
          world_share_enabled: worldShareEnabled.value,
        };
        if (visibility.value === 'shared') {
          payload.shared_with_emails = [...sharedWithEmails.value];
        }
        const patchRes = await props.api.patch(`/groups/${props.group.id}/`, payload);
        const patchData = patchRes?.data;
        if (patchData) {
          worldShareEnabled.value = !!(patchData.world_share_id);
          worldShareUrl.value = patchData.world_share_url || '';
        }
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
        if (window.gv_core?.GeoVault?.toast) window.gv_core.GeoVault.toast.success('Group updated');
        const groupData = {
          ...(props.group || {}),
          ...(patchData || {}),
          track_ids: [...(groupTrackIds.value || [])],
        };
        emit('saved', { action: 'updated', group: groupData });
      } catch (e) {
        const err = props.api.handleError?.(e);
        nameError.value = err?.message || 'Failed to save';
      } finally {
        saving.value = false;
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
      visibility,
      sharedWithEmails,
      sharedWithSelectItems,
      sharedWithEmailsForSelect,
      worldShareEnabled,
      worldShareUrl,
      fullWorldShareUrl,
      copyWorldShareUrl,
      groupTrackIdsSafe,
      allTrackers,
      create,
      save,
      allUsers,
      loadingUsers,
      onHiddenInListChange,
    };
  },
};
</script>
