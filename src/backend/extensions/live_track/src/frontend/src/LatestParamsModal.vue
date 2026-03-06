<template>
  <BaseModal
    :is-open="true"
    title="Latest Parameters"
    @close="$emit('close')"
  >
    <div class="p-4 sm:p-5 space-y-3">
      <div v-if="track?.name" class="text-sm font-medium text-gray-900 tracking-wide uppercase truncate min-w-0" :title="track.name">
        {{ track.name }}
      </div>
      <div
        v-if="track?.last_timestamp_ms"
        class="rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm shadow-sm"
      >
        <span class="font-medium text-gray-900">Last Update</span>
        <span class="block text-gray-900 truncate" :title="formatTimeLocal(track.last_timestamp_ms)">{{ formatTimeLocal(track.last_timestamp_ms) }}</span>
      </div>
      <div v-else class="rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-500">
        No points yet. Waiting for data…
      </div>
      <div
        v-if="track?.last_position"
        class="rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm shadow-sm"
      >
        <span class="font-medium text-gray-900">Position</span>
        <span class="block text-gray-900 mt-0.5">{{ formatLatLon(track.last_position) }}</span>
      </div>
      <div v-if="hasStoredParams && sortedParamEntries.length" class="grid grid-cols-2 sm:grid-cols-3 gap-2">
        <div
          v-for="[key, value] in sortedParamEntries"
          :key="key"
          class="rounded-lg border border-gray-200 bg-white px-3 py-2 shadow-sm min-w-0"
          :title="key === 'starttimestamp' ? formatDurationRunning(value) : undefined"
        >
          <div class="text-xs font-medium text-gray-900 truncate" :title="key === 'starttimestamp' ? undefined : ((paramLabels && paramLabels[key]) || key)">{{ (paramLabels && paramLabels[key]) || key }}</div>
          <div class="text-sm text-gray-900 break-all mt-0.5">{{ formatParamDisplay(key, value) }}</div>
        </div>
      </div>
      <div v-else-if="track?.last_timestamp_ms || track?.last_position" class="rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-500">
        No extended parameters for the latest point.
      </div>
    </div>
    <template #actions>
      <BaseButton variant="white" size="sm" @click="$emit('close')">Close</BaseButton>
    </template>
  </BaseModal>
</template>

<script>
import { computed } from 'vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';
import { formatParamDisplay } from './paramFormatters.js';

export default {
  name: 'LatestParamsModal',
  components: { BaseModal },
  props: {
    track: {
      type: Object,
      default: null
    },
    paramLabels: {
      type: Object,
      default: () => ({})
    }
  },
  emits: ['close'],
  setup(props) {
    /** True when we have real params from the backend (GPSLogger, etc.); false when only geometry (no point_params). */
    const hasStoredParams = computed(() => {
      const t = props.track;
      if (!t?.latestPointParams || typeof t.latestPointParams !== 'object') return false;
      return Object.keys(t.latestPointParams).length > 0;
    });

    /** Params to show: only stored latestPointParams when present; no fallback so we don't show lat/lon/timestamp as cards. */
    const displayParams = computed(() => {
      const t = props.track;
      if (!t || !hasStoredParams.value) return null;
      return t.latestPointParams;
    });

    const sortedParamEntries = computed(() => {
      const params = displayParams.value;
      if (!params || !Object.keys(params).length) return [];
      return Object.entries(params).sort(([a], [b]) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
    });

    function formatTimeLocal(ms) {
      if (!ms) return '';
      const d = new Date(ms);
      return d.toLocaleString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit',
        second: '2-digit'
      });
    }

    function formatLatLon(pos) {
      if (!pos || pos.lat == null || pos.lon == null) return '';
      return `${Number(pos.lat).toFixed(6)}, ${Number(pos.lon).toFixed(6)}`;
    }

    /** Tooltip for start timestamp: "Running for X days, Y hours, Z minutes". */
    function formatDurationRunning(timestamp) {
      if (timestamp == null) return '';
      const ms = typeof timestamp === 'number' && timestamp < 1e12 ? timestamp * 1000 : Number(timestamp);
      if (!Number.isFinite(ms)) return '';
      const elapsed = Date.now() - ms;
      if (elapsed < 0) return '';
      const totalMinutes = Math.floor(elapsed / 60000);
      const days = Math.floor(totalMinutes / 1440);
      const hours = Math.floor((totalMinutes % 1440) / 60);
      const minutes = totalMinutes % 60;
      const parts = [];
      if (days > 0) parts.push(`${days} day${days !== 1 ? 's' : ''}`);
      if (hours > 0) parts.push(`${hours} hour${hours !== 1 ? 's' : ''}`);
      parts.push(`${minutes} minute${minutes !== 1 ? 's' : ''}`);
      return `Running for ${parts.join(', ')}`;
    }

    return {
      hasStoredParams,
      displayParams,
      sortedParamEntries,
      formatTimeLocal,
      formatLatLon,
      formatDurationRunning,
      formatParamDisplay
    };
  }
};
</script>
