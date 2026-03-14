<template>
  <div class="flex-1 min-h-0 overflow-y-auto space-y-3 px-1 py-1 custom-scrollbar">
    <div
      v-for="track in tracks"
      :key="track.id"
      :data-track-id="track.id"
      :class="[
        'group flex items-center gap-3 p-4 rounded-2xl cursor-pointer border transition-colors',
        selectedId != null && String(selectedId) === String(track.id)
          ? 'border-blue-500 bg-blue-100'
          : 'border-gray-200 bg-white hover:bg-gray-50'
      ]"
      @click="$emit('track-click', track)"
    >
      <div
        class="flex-shrink-0 w-12 h-12 flex items-center justify-center rounded-xl bg-white border border-gray-100 transition-colors"
        :style="{ borderLeftColor: track.color || '#6C93DE', borderLeftWidth: '4px' }"
      >
        <TrackDirectionIcon
          :color="track.color || '#6C93DE'"
          :angle="getTrackDirectionAngle(track)"
          :size="26"
          :selected="selectedId != null && String(selectedId) === String(track.id)"
          reserve-circle
        />
      </div>
      <div class="flex-1 min-w-0">
        <div class="font-bold text-gray-900 tracking-tight break-all truncate" :title="track.name">
          {{ track.name }}
        </div>
        <div class="text-xs font-medium text-gray-500 truncate mt-0.5">
          {{ track.last_timestamp_ms ? formatTime(track.last_timestamp_ms) : 'Waiting for data...' }}
        </div>
      </div>
      <div
        v-if="getParamsAllowed(track)"
        class="flex items-center gap-1 flex-shrink-0 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity"
      >
        <button
          type="button"
          title="Latest Params"
          class="p-2 rounded-xl text-gray-400 hover:text-gray-600 hover:bg-white active:bg-gray-100 transition-all border border-transparent hover:border-gray-200"
          @click.stop="$emit('open-params', track)"
        >
          <TableCellsIcon class="h-5 w-5" />
        </button>
      </div>
    </div>
  </div>
</template>

<script>
import { TableCellsIcon } from '@heroicons/vue/24/outline';
import TrackDirectionIcon from './TrackDirectionIcon.vue';
import { getTrackDirectionAngle } from './trackGeometry.js';
import { formatTimestampLocal } from './paramFormatters.js';

function formatTime(ms) {
  return formatTimestampLocal(ms);
}

export default {
  name: 'MapTrackList',
  components: { TableCellsIcon, TrackDirectionIcon },
  props: {
    tracks: {
      type: Array,
      default: () => []
    },
    selectedId: {
      type: [String, Number],
      default: null
    },
    /** Optional: (track) => boolean to show params button per track. Default: true when track has point_params or geometry coords. */
    getParamsAllowed: {
      type: Function,
      default: (track) => {
        const allow = track?.share_params_with_world === true ||
          (track?.share_params_with_world === undefined && track?.share_params_with_recipients === true);
        if (!allow) return false;
        const hasPoints = (track?.point_params?.length || track?.geometry?.coordinates?.length || 0) > 0;
        return hasPoints;
      }
    }
  },
  emits: ['track-click', 'open-params'],
  setup() {
    return {
      getTrackDirectionAngle,
      formatTime
    };
  }
};
</script>
