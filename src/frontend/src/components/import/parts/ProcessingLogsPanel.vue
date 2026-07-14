<template>
  <div class="bg-white rounded-lg shadow-sm border border-gray-200 p-4 sm:p-6">
    <div class="flex items-center justify-between mb-4">
      <h2 class="text-base sm:text-lg font-semibold text-gray-900">Processing Logs</h2>
      <button
        class="inline-flex items-center p-2 border border-gray-300 shadow-sm text-xs sm:text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
        title="Open Full Log View"
        @click="$emit('open-full-logs')"
      >
        <ArrowTopRightOnSquareIcon class="w-4 h-4" />
      </button>
    </div>
    <div class="bg-gray-50 rounded-lg p-3 sm:p-4">
      <div ref="logsContainer" class="h-32 overflow-auto">
        <ul class="space-y-1 sm:space-y-0">
          <li
            v-for="(item, index) in logs"
            :key="`logitem-${index}`"
            class="border-l-4 pl-2 pb-2 sm:py-1"
            :class="(item.level ?? 0) >= 40 ? 'bg-red-50 border-red-400' : 'border-transparent'"
          >
            <div class="flex flex-col gap-1 sm:grid sm:grid-cols-[190px_140px_80px_minmax(0,1fr)] sm:gap-x-4 sm:gap-y-1 sm:items-start">
              <!-- Level + Source -->
              <div class="flex flex-wrap sm:flex-nowrap items-center gap-2 sm:col-start-2 sm:row-start-1">
                <span
                  v-if="item.level !== undefined"
                  :class="getLevelClass(item.level)"
                  class="text-[11px] sm:text-xs px-2 py-0.5 rounded font-medium"
                >
                  {{ getLevelName(item.level) }}
                </span>
                <span
                  v-if="item.source"
                  class="text-xs text-gray-400 bg-gray-100 px-2 py-1 rounded whitespace-normal sm:whitespace-nowrap break-words"
                  :title="item.source"
                >{{ item.source }}</span>
              </div>

              <!-- Timestamp -->
              <span
                class="text-[11px] sm:text-sm text-gray-500 font-mono bg-gray-100 px-2 py-0.5 rounded sm:bg-transparent sm:px-0 sm:py-0 sm:w-fit sm:whitespace-nowrap sm:col-start-1 sm:row-start-1"
              >{{ formatTimestamp(item.timestamp) }}</span>

              <!-- Message -->
              <p
                :class="(item.level ?? 0) >= 40 ? 'text-red-800 font-medium' : 'text-gray-700'"
                class="text-xs sm:text-sm leading-relaxed break-words sm:col-start-4 sm:row-start-1 sm:row-span-2"
              >
                {{ item.msg }}
              </p>
            </div>

            <!-- Divider for mobile -->
            <hr v-if="index < logs.length - 1" class="sm:hidden mt-2 border-gray-200" />
          </li>
          <li v-if="logs.length === 0" class="text-sm text-gray-500 italic">
            {{ isLoading ? 'Fetching logs...' : 'No logs available yet...' }}
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import moment from 'moment';
import { ArrowTopRightOnSquareIcon } from '@heroicons/vue/24/outline';
import { getLevelName, getLevelClass } from '@/utils/import/featureProcessing';
import type { ImportLogEntry } from '@/assets/js/types/import-types';

export default defineComponent({
  name: 'ProcessingLogsPanel',
  components: {
    ArrowTopRightOnSquareIcon
  },
  props: {
    logs: {
      type: Array as PropType<ImportLogEntry[]>,
      default: () => []
    },
    isLoading: {
      type: Boolean,
      default: false
    }
  },
  emits: ['open-full-logs'],
  methods: {
    getLevelName,
    getLevelClass,
    formatTimestamp(timestamp: string | undefined): string {
      if (!timestamp) return '';
      return moment(timestamp).format('YYYY-MM-DD HH:mm:ss');
    }
  }
});
</script>

