<template>
  <div class="absolute z-10 bottom-4 right-4 flex flex-col gap-2 bg-white border border-gray-200 rounded overflow-hidden">
    <button
      type="button"
      class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
      :title="followLocked ? 'Unlock map' : 'Lock map to tracker'"
      @click="$emit('toggle-follow')"
    >
      <LockClosedIcon v-if="followLocked" class="w-5 h-5" />
      <LockOpenIcon v-else class="w-5 h-5" />
    </button>
    <button
      v-if="showParamsButton"
      type="button"
      class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
      title="Latest parameters"
      @click="$emit('open-params')"
    >
      <TableCellsIcon class="w-5 h-5" />
    </button>
    <button
      type="button"
      class="p-2 bg-white text-gray-700 hover:bg-gray-50 transition-colors duration-200 focus:outline-none"
      title="Map Settings"
      @click="$emit('open-layer')"
    >
      <Square3Stack3DIcon class="w-5 h-5" />
    </button>
  </div>
</template>

<script>
import { LockClosedIcon, LockOpenIcon, Square3Stack3DIcon, TableCellsIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'SingleTrackMapControls',
  components: { LockClosedIcon, LockOpenIcon, Square3Stack3DIcon, TableCellsIcon },
  props: {
    followLocked: { type: Boolean, default: false },
    showParamsButton: { type: Boolean, default: false }
  },
  emits: ['toggle-follow', 'open-params', 'open-layer']
};
</script>
