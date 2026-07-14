<template>
  <BaseModal
    :is-open="isOpen"
    title="Processing Logs"
    max-width="6xl"
    @close="$emit('close')"
  >
    <div ref="logScrollContainer" class="h-full overflow-auto p-4">
          <div class="space-y-1 sm:space-y-0">
            <div
                v-for="(item, index) in logs"
                :key="`logitem-${index}`"
                class="border-l-4 pl-2 pb-2 sm:py-1"
            >
              <div class="flex flex-col gap-1 sm:grid sm:grid-cols-[190px_140px_80px_minmax(0,1fr)] sm:gap-x-4 sm:gap-y-1 sm:items-start">
                <!-- Level + Source (first row on mobile, cols 2–3 on desktop) -->
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

                <!-- Timestamp (second row on mobile, col 1 on desktop) -->
                <span
                    class="text-[11px] sm:text-xs text-gray-500 font-mono bg-gray-100 px-2 py-0.5 rounded sm:bg-transparent sm:px-0 sm:py-0 sm:w-fit sm:whitespace-nowrap sm:col-start-1 sm:row-start-1"
                >{{ formatTimestamp(item.timestamp) }}</span>

                <!-- Message (third row on mobile, last column on desktop) -->
                <p
                    class="text-xs sm:text-sm text-gray-700 leading-relaxed break-words sm:col-start-4 sm:row-start-1 sm:row-span-2"
                >
                  {{ item.msg }}
                </p>
              </div>

              <!-- Divider for mobile -->
              <hr v-if="index < logs.length - 1" class="sm:hidden mt-2 border-gray-200" />
            </div>
            <div v-if="logs.length === 0" class="text-center py-8">
              <DocumentIcon class="mx-auto h-12 w-12 text-gray-400" />
              <h3 class="mt-2 text-sm font-medium text-gray-900">No logs available</h3>
              <p class="mt-1 text-sm text-gray-500">Processing logs will appear here when available.</p>
            </div>
          </div>
    </div>
  </BaseModal>
</template>

<script lang="ts">
import { defineComponent, type PropType } from 'vue'
import moment from "moment";
import BaseModal from '@/components/parts/BaseModal.vue'
import { DocumentIcon } from '@heroicons/vue/24/outline';
import { getLevelName, getLevelClass } from '@/utils/import/featureProcessing';
import type { ImportLogEntry } from '@/assets/js/types/import-types';

export default defineComponent({
  name: 'LogViewModal',
  components: {
    BaseModal,
    DocumentIcon
  },
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    logs: {
      type: Array as PropType<ImportLogEntry[]>,
      default: () => []
    }
  },
  emits: ['close'],
  watch: {
    logs() {
      void this.$nextTick(() => { this.scrollToBottom() });
    },
    isOpen(open: boolean) {
      if (open) {
        void this.$nextTick(() => { this.scrollToBottom() });
      }
    }
  },
  methods: {
    getLevelName,
    getLevelClass,
    scrollToBottom() {
      const el = this.$refs.logScrollContainer as HTMLElement | undefined;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    },
    formatTimestamp(timestamp: string | undefined): string {
      if (!timestamp) return '';
      return moment(timestamp).format('YYYY-MM-DD HH:mm:ss');
    }
  }
})
</script>

