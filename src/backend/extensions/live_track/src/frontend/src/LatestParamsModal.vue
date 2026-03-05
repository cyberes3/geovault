<template>
  <BaseModal
    :is-open="true"
    :title="modalTitle"
    @close="$emit('close')"
  >
    <div class="p-4 space-y-4">
      <div v-if="track?.last_timestamp_ms" class="text-sm">
        <span class="font-medium text-gray-700">Time (local): </span>
        <span class="text-gray-900">{{ formatTimeWithTimezone(track.last_timestamp_ms) }}</span>
      </div>
      <div v-else class="text-sm text-gray-500">No points yet.</div>
      <div v-if="displayParams && Object.keys(displayParams).length" class="space-y-2">
        <div class="font-medium text-gray-700 text-sm">Params</div>
        <dl class="border border-gray-200 rounded-lg divide-y divide-gray-200 overflow-hidden">
          <div
            v-for="(value, key) in displayParams"
            :key="key"
            class="flex flex-wrap gap-x-2 gap-y-1 px-3 py-2 sm:flex-nowrap"
          >
            <dt class="text-sm font-medium text-gray-500">{{ key }}</dt>
            <dd class="text-sm text-gray-900 break-all">{{ formatParamValue(value) }}</dd>
          </div>
        </dl>
      </div>
      <div v-else class="text-sm text-gray-500">No point data yet.</div>
    </div>
    <template #actions>
      <BaseButton variant="white" size="sm" @click="$emit('close')">Close</BaseButton>
    </template>
  </BaseModal>
</template>

<script>
import { computed } from 'vue';
import BaseModal from 'platform/components/parts/BaseModal.vue';

export default {
  name: 'LatestParamsModal',
  components: { BaseModal },
  props: {
    track: {
      type: Object,
      default: null
    }
  },
  emits: ['close'],
  setup(props) {
    const modalTitle = computed(() =>
      props.track?.name ? `Latest params – ${props.track.name}` : 'Latest params'
    );

    /** Params to show: stored latestPointParams, or fallback lat/lon/timestamp from geometry when empty (initial load returns point_params but they can be {}). */
    const displayParams = computed(() => {
      const t = props.track;
      if (!t) return null;
      const stored = t.latestPointParams && typeof t.latestPointParams === 'object' ? t.latestPointParams : {};
      const hasStored = Object.keys(stored).length > 0;
      if (hasStored) return stored;
      if (t.last_timestamp_ms != null && t.last_position) {
        return {
          lat: t.last_position.lat,
          lon: t.last_position.lon,
          timestamp: t.last_timestamp_ms
        };
      }
      return null;
    });

    function formatTimeWithTimezone(ms) {
      if (!ms) return '';
      const d = new Date(ms);
      return d.toLocaleString(undefined, {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit',
        second: '2-digit',
        timeZoneName: 'short'
      });
    }

    function formatParamValue(value) {
      if (value === null) return 'null';
      if (value === undefined) return '';
      if (typeof value === 'object') return JSON.stringify(value);
      return String(value);
    }

    return {
      modalTitle,
      displayParams,
      formatTimeWithTimezone,
      formatParamValue
    };
  }
};
</script>
