<template>
  <LiveTrackSidebar
    v-if="!embedded"
    title="Latest Parameters"
    :container-ref="containerRef"
    :disable-animations="disableAnimations"
    @close="$emit('close')"
  >
    <div class="p-4 sm:p-5 space-y-3">
      <div v-if="track?.name" class="text-sm font-medium text-gray-900 tracking-wide uppercase truncate min-w-0" :title="track.name">
        {{ track.name }}
      </div>
      <div
        v-if="track?.last_timestamp_ms"
        class="rounded-lg border border-blue-200 bg-white px-3 py-2 text-sm text-gray-900"
      >
        <span class="font-medium">Last Update</span>
        <span class="block truncate" :title="formatTimeLocal(track.last_timestamp_ms)">{{ formatTimeLocal(track.last_timestamp_ms) }}</span>
      </div>
      <div v-else class="rounded-lg border border-blue-200 bg-white px-3 py-2 text-sm text-gray-900">
        No points yet. Waiting for data…
      </div>
      <div
        v-if="track?.last_position"
        class="rounded-lg border border-blue-200 bg-white px-3 py-2 text-sm text-gray-900"
      >
        <span class="font-medium">Position</span>
        <span class="block mt-0.5">{{ formatLatLon(track.last_position) }}</span>
      </div>
      <div v-if="hasStoredParams && sortedParamEntries.length" class="grid grid-cols-2 sm:grid-cols-3 gap-2">
        <div
          v-for="[key, value] in sortedParamEntries"
          :key="key"
          class="rounded-lg border border-blue-200 bg-white px-3 py-2 min-w-0 text-gray-900"
          :title="key === 'starttimestamp' ? formatDurationRunning(value) : undefined"
        >
          <div class="text-xs font-medium truncate" :title="key === 'starttimestamp' ? undefined : getParamLabel(key)">{{ getParamLabel(key) }}</div>
          <div class="text-sm break-all mt-0.5">{{ formatParamDisplay(key, value) }}</div>
        </div>
      </div>
      <div v-else-if="track?.last_timestamp_ms || track?.last_position" class="rounded-lg border border-blue-200 bg-white px-3 py-2 text-sm text-gray-900">
        No extended parameters for the latest point.
      </div>
    </div>
  </LiveTrackSidebar>
  <!-- Embedded: content + footer only (shell is provided by parent) -->
  <div v-else class="flex-1 min-h-0 flex flex-col">
    <div class="flex-1 overflow-y-auto min-h-0">
      <div class="p-4 sm:p-5 space-y-3">
        <div v-if="track?.name" class="text-sm font-medium text-gray-900 tracking-wide uppercase truncate min-w-0" :title="track.name">
          {{ track.name }}
        </div>
        <div
          v-if="track?.last_timestamp_ms"
          class="rounded-lg border border-blue-200 bg-white px-3 py-2 text-sm text-gray-900"
        >
          <span class="font-medium">Last Update</span>
          <span class="block truncate" :title="formatTimeLocal(track.last_timestamp_ms)">{{ formatTimeLocal(track.last_timestamp_ms) }}</span>
        </div>
        <div v-else class="rounded-lg border border-blue-200 bg-white px-3 py-2 text-sm text-gray-900">
          No points yet. Waiting for data…
        </div>
        <div
          v-if="track?.last_position"
          class="rounded-lg border border-blue-200 bg-white px-3 py-2 text-sm text-gray-900"
        >
          <span class="font-medium">Position</span>
          <span class="block mt-0.5">{{ formatLatLon(track.last_position) }}</span>
        </div>
        <div v-if="hasStoredParams && sortedParamEntries.length" class="grid grid-cols-2 sm:grid-cols-3 gap-2">
          <div
            v-for="[key, value] in sortedParamEntries"
            :key="key"
            class="rounded-lg border border-blue-200 bg-white px-3 py-2 min-w-0 text-gray-900"
            :title="key === 'starttimestamp' ? formatDurationRunning(value) : undefined"
          >
            <div class="text-xs font-medium truncate" :title="key === 'starttimestamp' ? undefined : getParamLabel(key)">{{ getParamLabel(key) }}</div>
            <div class="text-sm break-all mt-0.5">{{ formatParamDisplay(key, value) }}</div>
          </div>
        </div>
        <div v-else-if="track?.last_timestamp_ms || track?.last_position" class="rounded-lg border border-blue-200 bg-white px-3 py-2 text-sm text-gray-900">
          No extended parameters for the latest point.
        </div>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, computed, type PropType } from 'vue';
import LiveTrackSidebar from './LiveTrackSidebar.vue';
import { formatParamDisplay, formatTimestampLocal } from './paramFormatters';
import type { LiveTrack, PointParams, TrackPosition } from './types/track';

type ContainerRefLike = { value: HTMLElement | null } | HTMLElement | null;

export default defineComponent({
  name: 'LatestParamsModal',
  components: { LiveTrackSidebar },
  props: {
    track: {
      type: Object as PropType<LiveTrack | null>,
      default: null
    },
    paramLabels: {
      type: Object as PropType<Record<string, string>>,
      default: () => ({})
    },
    containerRef: {
      type: Object as PropType<ContainerRefLike>,
      default: null,
    },
    /** When true, render only content + footer (no sidebar shell); parent provides the shell. */
    embedded: {
      type: Boolean,
      default: false,
    },
    disableAnimations: {
      type: Boolean,
      default: false,
    },
  },
  emits: ['close'],
  setup(props) {
    /** True when we have real params from the backend (GPSLogger, etc.); false when only geometry (no point_params). */
    const hasStoredParams = computed((): boolean => {
      const t = props.track;
      if (!t?.latestPointParams || typeof t.latestPointParams !== 'object') return false;
      return Object.keys(t.latestPointParams).length > 0;
    });

    /** Params to show: only stored latestPointParams when present; no fallback so we don't show lat/lon/timestamp as cards. */
    const displayParams = computed((): PointParams | null => {
      const t = props.track;
      if (!t || !hasStoredParams.value) return null;
      return t.latestPointParams ?? null;
    });

    const sortedParamEntries = computed((): [string, unknown][] => {
      const params = displayParams.value;
      if (!params || !Object.keys(params).length) return [];
      return Object.entries(params).sort(([a], [b]) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
    });

    function formatLatLon(pos: TrackPosition | null | undefined): string {
      if (pos?.lat == null) return '';
      return `${Number(pos.lat).toFixed(6)}, ${Number(pos.lon).toFixed(6)}`;
    }

    function getParamLabel(key: string): string {
      return props.paramLabels[key] ?? key;
    }

    /** Tooltip for start timestamp: "Running for X days, Y hours, Z minutes". */
    function formatDurationRunning(timestamp: unknown): string {
      if (timestamp == null) return '';
      const ms = typeof timestamp === 'number' && timestamp < 1e12 ? timestamp * 1000 : Number(timestamp);
      if (!Number.isFinite(ms)) return '';
      const elapsed = Date.now() - ms;
      if (elapsed < 0) return '';
      const totalMinutes = Math.floor(elapsed / 60000);
      const days = Math.floor(totalMinutes / 1440);
      const hours = Math.floor((totalMinutes % 1440) / 60);
      const minutes = totalMinutes % 60;
      const parts: string[] = [];
      if (days > 0) parts.push(`${days} day${days !== 1 ? 's' : ''}`);
      if (hours > 0) parts.push(`${hours} hour${hours !== 1 ? 's' : ''}`);
      parts.push(`${minutes} minute${minutes !== 1 ? 's' : ''}`);
      return `Running for ${parts.join(', ')}`;
    }

    return {
      hasStoredParams,
      displayParams,
      sortedParamEntries,
      formatTimeLocal: formatTimestampLocal,
      formatLatLon,
      getParamLabel,
      formatDurationRunning,
      formatParamDisplay
    };
  }
});
</script>
