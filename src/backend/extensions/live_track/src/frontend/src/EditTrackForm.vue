<template>
  <div class="p-4 space-y-4">
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Name <span class="text-red-500">*</span></label>
      <input
        :value="name"
        type="text"
        placeholder="Track name"
        class="w-full border border-gray-300 px-3 py-2 rounded-lg"
        @input="$emit('update:name', ($event.target && $event.target.value) || '')"
      />
      <p v-if="error" class="text-sm text-red-600">{{ error }}</p>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Password (read-only)</label>
      <div class="flex gap-2">
        <input :value="track?.tracker_secret" readonly class="flex-1 px-2 py-1 text-sm border rounded bg-gray-50" />
        <button type="button" class="px-2 py-1 bg-gray-200 rounded text-sm" @click="copy(track?.tracker_secret || '')">Copy</button>
      </div>
    </div>
    <div class="space-y-2">
      <label class="text-sm font-medium text-gray-700">Color</label>
      <div class="flex items-center gap-2">
        <ColorPickerElement :model-value="color" @update:model-value="$emit('update:color', $event)" />
        <button
          type="button"
          title="Reset to default color from name"
          class="p-2 rounded-lg text-gray-500 hover:bg-gray-100 hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
          @click="$emit('reset-color')"
        >
          <ArrowPathIcon class="h-5 w-5" />
        </button>
      </div>
    </div>
    <p class="text-sm text-amber-800 bg-amber-50 p-2 rounded">
      Anyone with the tracker password can send location data to this track.
    </p>
    <div class="flex flex-wrap gap-2">
      <BaseButton variant="white" size="sm" @click="$emit('open-instructions')">GPSLogger Instructions</BaseButton>
      <BaseButton variant="white" size="sm" @click="$emit('download-kml')">Download KML</BaseButton>
      <BaseButton variant="white" size="sm" :disabled="clearHistoryDisabled" @click="$emit('clear-history')">
        <Loader v-if="clearing" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Clear history
      </BaseButton>
      <BaseButton variant="secondary" color="red" size="sm" :disabled="deleting" @click="$emit('delete')">
        <Loader v-if="deleting" size="sm" layout="inline" :show-message="false" class="mr-1" />
        Delete
      </BaseButton>
    </div>
  </div>
</template>

<script>
import { ArrowPathIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'EditTrackForm',
  components: { ArrowPathIcon },
  props: {
    track: { type: Object, default: null },
    name: { type: String, default: '' },
    color: { type: String, default: '#3388ff' },
    error: { type: String, default: '' },
    deleting: { type: Boolean, default: false },
    clearing: { type: Boolean, default: false },
    /** When true, Clear history button is disabled (e.g. after clear succeeded until modal is closed). */
    clearHistoryDisabled: { type: Boolean, default: false },
    copy: { type: Function, required: true }
  },
  emits: ['update:name', 'update:color', 'reset-color', 'open-instructions', 'download-kml', 'clear-history', 'delete']
};
</script>
