<template>
  <BaseModal
    :is-open="!!track"
    :title="track ? `Who has added: ${track.name || 'Unnamed'}` : 'Public share'"
    @close="$emit('close')"
  >
    <div v-if="track" class="p-4 space-y-4">
      <div v-if="loadingSubscribers" class="flex justify-center py-4">
        <Loader size="md" message="Loading..." />
      </div>
      <template v-else>
        <p class="text-sm text-gray-900">Users who have added this public tracker to their list:</p>
        <div v-if="subscribers.length === 0" class="text-sm text-gray-900 italic">No one has added this track yet.</div>
        <ul v-else class="max-h-40 overflow-y-auto border border-gray-200 rounded-lg divide-y divide-gray-100">
          <li
            v-for="s in subscribers"
            :key="s.id"
            class="px-3 py-2 text-sm text-gray-900"
          >
            {{ s.email || 'No email' }}
          </li>
        </ul>
      </template>
      <p v-if="error" class="text-sm text-red-600">{{ error }}</p>
    </div>
    <template #actions>
      <BaseButton variant="white" size="sm" @click="$emit('close')">Close</BaseButton>
      <BaseButton
        variant="primary"
        size="sm"
        :disabled="deleting"
        class="!bg-red-600 hover:!bg-red-700"
        @click="onDeletePublicShare"
      >
        <Loader v-if="deleting" size="xs" layout="inline" :show-message="false" />
        <span v-else>Delete public share</span>
      </BaseButton>
    </template>
  </BaseModal>
</template>

<script>
import { ref, watch } from 'vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import Loader from 'platform/components/parts/Loader.vue';

export default {
  name: 'PublicSharePopup',
  components: { BaseModal, BaseButton, Loader },
  props: {
    track: { type: Object, default: null },
    api: { type: Object, default: null },
  },
  emits: ['close', 'deleted'],
  setup(props, { emit }) {
    const subscribers = ref([]);
    const loadingSubscribers = ref(false);
    const error = ref('');
    const deleting = ref(false);

    async function fetchSubscribers() {
      if (!props.track?.id || !props.api) return;
      loadingSubscribers.value = true;
      error.value = '';
      try {
        const res = await props.api.get(`/trackers/${props.track.id}/subscribers/`);
        subscribers.value = Array.isArray(res?.data?.subscribers) ? res.data.subscribers : [];
      } catch (e) {
        const err = props.api?.handleError?.(e);
        error.value = err?.message || 'Failed to load subscribers';
        subscribers.value = [];
      } finally {
        loadingSubscribers.value = false;
      }
    }

    watch(
      () => props.track,
      (t) => {
        if (!t) {
          subscribers.value = [];
          return;
        }
        fetchSubscribers();
      },
      { immediate: true }
    );

    async function onDeletePublicShare() {
      if (!props.track?.id || !props.api) return;
      error.value = '';
      deleting.value = true;
      try {
        const res = await props.api.post(`/trackers/${props.track.id}/settings/`, { visibility: 'private' });
        emit('deleted', res?.data);
        emit('close');
      } catch (e) {
        const err = props.api?.handleError?.(e);
        error.value = err?.message || 'Failed to remove public share';
      } finally {
        deleting.value = false;
      }
    }

    return {
      subscribers,
      loadingSubscribers,
      error,
      deleting,
      onDeletePublicShare,
    };
  },
};
</script>
