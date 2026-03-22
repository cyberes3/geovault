<template>
  <div class="p-4 space-y-4">
    <p class="text-sm text-amber-800 bg-amber-50 p-3 rounded">
      Keep your tracker password secret. Anyone who has it can send location data to this tracker.
    </p>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">URL</label>
      <div class="flex gap-2">
        <input :value="ingressUrl" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <CopyTextButton :text="ingressUrl" size="sm" />
      </div>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Method</label>
      <div class="flex gap-2">
        <input value="POST" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <CopyTextButton text="POST" size="sm" />
      </div>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Body</label>
      <div class="flex gap-2">
        <input :value="bodyTemplate" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <CopyTextButton :text="bodyTemplate" size="sm" />
      </div>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Username</label>
      <div class="flex gap-2">
        <input :value="userLogin" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <CopyTextButton :text="userLogin" size="sm" />
      </div>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Password (tracker secret)</label>
      <div class="flex gap-2">
        <input :value="trackerSecret" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <CopyTextButton :text="trackerSecret" size="sm" />
      </div>
    </div>
    <div class="grid gap-2" :class="haukDomain ? 'grid-cols-2' : 'grid-cols-1'">
      <BaseButton variant="primary" color="blue" size="sm" class="w-full min-w-0" @click="$emit('open-instructions')">
        GPSLogger Setup
      </BaseButton>
      <BaseButton
        v-if="haukDomain"
        variant="primary"
        color="blue"
        size="sm"
        class="w-full min-w-0"
        @click="$emit('open-hauk-instructions')"
      >
        Hauk Setup
      </BaseButton>
    </div>
  </div>
</template>

<script>
import BaseButton from 'platform/components/parts/BaseButton.vue';
import CopyTextButton from './CopyTextButton.vue';

export default {
  name: 'CreateSuccessView',
  components: { BaseButton, CopyTextButton },
  props: {
    ingressUrl: { type: String, default: '' },
    bodyTemplate: { type: String, default: '' },
    userLogin: { type: String, default: '' },
    trackerSecret: { type: String, default: '' },
    /** When set, the Hauk Setup button is shown. */
    haukDomain: { type: String, default: '' }
  },
  emits: ['open-instructions', 'open-hauk-instructions']
};
</script>
