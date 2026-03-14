<template>
  <BaseModal
    :is-open="!!track"
    :title="track ? `Sharing: ${track.name || 'Unnamed'}` : 'Sharing'"
    @close="$emit('close')"
  >
    <div v-if="track" class="p-4 space-y-4">
      <div class="space-y-2">
        <label class="text-sm font-medium text-gray-900">Who can see and add this tracker</label>
        <select
          v-model="visibility"
          class="select-custom w-full border border-gray-300 px-3 py-2 rounded-md focus:outline-none focus:ring-blue-500 focus:border-blue-500"
        >
          <option value="private">Private (only me)</option>
          <option value="shared">Shared with specific users</option>
          <option value="public">Public (all authenticated users)</option>
        </select>
      </div>
      <div v-if="visibility === 'shared'" class="space-y-2">
        <label class="block text-sm font-medium text-gray-900">Shared with (click to add or remove)</label>
        <div
          v-if="loadingUsers"
          class="border border-gray-300 rounded-md bg-white flex items-center justify-center"
          style="height: 12rem;"
        >
          <Loader size="sm" message="Loading..." />
        </div>
        <ScrollingSelect
          v-else
          label=""
          :items="availableUsersForSelect"
          :selected-values="sharedWithEmails"
          :loading="false"
          max-height="12rem"
          empty-message="No other users found"
          @select="toggleUserEmail"
        />
      </div>
      <p class="text-xs text-gray-900">Set to Private to remove sharing.</p>
      <p v-if="error" class="text-sm text-red-600">{{ error }}</p>
    </div>
    <template #actions>
      <BaseButton variant="white" size="sm" @click="$emit('close')">Cancel</BaseButton>
      <BaseButton variant="primary" size="sm" :disabled="saving" @click="onSave">
        <Loader v-if="saving" size="xs" layout="inline" :show-message="false" />
        <span v-else>Save</span>
      </BaseButton>
    </template>
  </BaseModal>
</template>

<script>
import { ref, computed, watch } from 'vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import Loader from 'platform/components/parts/Loader.vue';
import ScrollingSelect from 'platform/components/parts/ScrollingSelect.vue';

export default {
  name: 'ShareSettingsModal',
  components: { BaseModal, BaseButton, Loader, ScrollingSelect },
  props: {
    track: { type: Object, default: null },
    api: { type: Object, default: null },
  },
  emits: ['close', 'saved'],
  setup(props, { emit }) {
    const visibility = ref('private');
    const sharedWithEmails = ref([]);
    const error = ref('');
    const saving = ref(false);
    const availableUsers = ref([]);
    const loadingUsers = ref(false);

    async function fetchUsers() {
      loadingUsers.value = true;
      try {
        const res = await fetch('/api/users/', { credentials: 'include' });
        const data = await res.json();
        availableUsers.value = Array.isArray(data?.users) ? data.users : [];
      } catch {
        availableUsers.value = [];
      } finally {
        loadingUsers.value = false;
      }
    }

    const availableUsersForSelect = computed(() =>
      (availableUsers.value || [])
        .map((u) => ({ value: (u.email || '').toLowerCase(), label: u.email || '' }))
        .filter((u) => u.value)
    );

    watch(
      () => props.track,
      (t) => {
        if (!t) return;
        visibility.value = t.visibility || 'private';
        sharedWithEmails.value = Array.isArray(t.shared_with_emails) ? [...t.shared_with_emails] : [];
        error.value = '';
        if (visibility.value === 'shared') fetchUsers();
      },
      { immediate: true }
    );

    watch(visibility, (v) => {
      if (v === 'shared') fetchUsers();
    });

    function toggleUserEmail(item) {
      const email = (item && (item.label ?? item.value)) ? String(item.label || item.value).trim().toLowerCase() : '';
      if (!email) return;
      const current = sharedWithEmails.value || [];
      const has = current.some((e) => (e || '').toLowerCase() === email);
      if (has) sharedWithEmails.value = current.filter((e) => (e || '').toLowerCase() !== email);
      else sharedWithEmails.value = [...current, email];
    }

    async function onSave() {
      if (!props.track?.id || !props.api) return;
      error.value = '';
      saving.value = true;
      try {
        const payload = {
          visibility: visibility.value,
        };
        if (visibility.value === 'shared') {
          payload.shared_with_emails = [...(sharedWithEmails.value || [])].map((e) => String(e || '').toLowerCase()).filter(Boolean);
        }
        const res = await props.api.post(`/trackers/${props.track.id}/settings/`, payload);
        emit('saved', res?.data);
        emit('close');
      } catch (e) {
        const err = props.api?.handleError?.(e);
        error.value = err?.message || 'Failed to save';
      } finally {
        saving.value = false;
      }
    }

    return {
      visibility,
      sharedWithEmails,
      error,
      saving,
      availableUsersForSelect,
      loadingUsers,
      toggleUserEmail,
      onSave,
    };
  },
};
</script>
