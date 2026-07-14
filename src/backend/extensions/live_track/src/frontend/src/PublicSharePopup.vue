<template>
  <BaseModal
    :is-open="!!track"
    :title="track ? `Who has added: ${track.name || 'Unnamed'}` : 'Public Share'"
    @close="$emit('close')"
  >
    <div v-if="track" class="p-4 space-y-4">
      <div v-if="loadingSubscribers" class="flex justify-center py-4">
        <Loader size="md" message="Loading..." />
      </div>
      <template v-else>
        <p class="text-sm text-gray-900">Users who have added this public tracker to their list:</p>
        <div v-if="subscribers.length === 0" class="text-sm text-gray-900 italic">No one has added this tracker yet.</div>
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
        <span v-else>Delete Public Share</span>
      </BaseButton>
    </template>
  </BaseModal>
</template>

<script lang="ts">
import { defineComponent, ref, watch, type PropType } from 'vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import BaseButton from 'platform/components/parts/BaseButton.vue';
import Loader from 'platform/components/parts/Loader.vue';
import { buildTrackerSharingPayload } from './settingsPayloadBuilders';
import type { LiveTrack } from './types/track';
import type { ExtensionApi } from './types/extension-api';

interface Subscriber {
  id: string | number;
  email?: string;
}

export default defineComponent({
  name: 'PublicSharePopup',
  components: { BaseModal, BaseButton, Loader },
  props: {
    track: { type: Object as PropType<LiveTrack | null>, default: null },
    api: { type: Object as PropType<ExtensionApi | null>, default: null },
  },
  emits: ['close', 'deleted'],
  setup(props, { emit }) {
    const subscribers = ref<Subscriber[]>([]);
    const loadingSubscribers = ref(false);
    const error = ref('');
    const deleting = ref(false);

    async function fetchSubscribers(): Promise<void> {
      if (!props.track?.id || !props.api) return;
      loadingSubscribers.value = true;
      error.value = '';
      try {
        const res = await props.api.get(`/trackers/${props.track.id}/subscribers/`);
        const data = res.data as { subscribers?: Subscriber[] } | null;
        subscribers.value = Array.isArray(data?.subscribers) ? data.subscribers : [];
      } catch (e) {
        const err = props.api.handleError(e);
        error.value = err.message || 'Failed to load subscribers';
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
        void fetchSubscribers();
      },
      { immediate: true }
    );

    async function onDeletePublicShare(): Promise<void> {
      if (!props.track?.id || !props.api) return;
      error.value = '';
      deleting.value = true;
      try {
        const payload = buildTrackerSharingPayload(props.track, 'private', []);
        const res = await props.api.post(`/trackers/${props.track.id}/settings/`, payload);
        emit('deleted', res.data);
        emit('close');
      } catch (e) {
        const err = props.api.handleError(e);
        error.value = err.message || 'Failed to remove public share';
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
});
</script>
