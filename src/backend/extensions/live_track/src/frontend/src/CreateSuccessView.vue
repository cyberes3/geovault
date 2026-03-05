<template>
  <div class="p-4 space-y-4">
    <p class="text-sm text-amber-800 bg-amber-50 p-3 rounded">
      Keep your tracker password secret. Anyone who has it can send location data to this track.
    </p>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">URL</label>
      <div class="flex gap-2">
        <input :value="ingressUrl" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy(ingressUrl)">Copy</button>
      </div>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Method</label>
      <div class="flex gap-2">
        <input value="POST" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy('POST')">Copy</button>
      </div>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Body</label>
      <div class="flex gap-2">
        <input :value="bodyTemplate" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy(bodyTemplate)">Copy</button>
      </div>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Username</label>
      <div class="flex gap-2">
        <input :value="userLogin" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy(userLogin)">Copy</button>
      </div>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Password (tracker secret)</label>
      <div class="flex gap-2">
        <input :value="trackerSecret" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy(trackerSecret)">Copy</button>
      </div>
    </div>
    <BaseButton variant="primary" color="blue" size="sm" @click="$emit('open-instructions')">
      Instructions (GPSLogger)
    </BaseButton>
  </div>
</template>

<script>
export default {
  name: 'CreateSuccessView',
  props: {
    ingressUrl: { type: String, default: '' },
    bodyTemplate: { type: String, default: '' },
    userLogin: { type: String, default: '' },
    trackerSecret: { type: String, default: '' },
    copy: { type: Function, required: true }
  },
  emits: ['open-instructions']
};
</script>
