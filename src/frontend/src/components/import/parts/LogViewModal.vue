<template>
  <div
      v-if="isOpen"
      class="fixed inset-0 z-50"
      @click="handleBackdropClick"
  >
    <!-- Backdrop -->
    <div class="absolute bg-black bg-opacity-50 transition-opacity duration-300"
         style="position: absolute !important; top: 0 !important; left: 0 !important; right: 0 !important; bottom: 0 !important; width: 100% !important; height: 100% !important; margin: 0 !important; padding: 0 !important;"></div>

    <!-- Modal Container -->
    <div class="absolute inset-0 flex items-stretch justify-stretch sm:items-center sm:justify-center">
      <div
          ref="modalContainer"
          class="bg-white flex flex-col w-full h-full sm:h-[80vh] sm:w-[75%] sm:rounded-lg shadow-xl transform transition-all duration-300 ease-out overflow-hidden"
          @click.stop
      >
      <!-- Header -->
      <div class="flex items-center justify-between p-4 border-b border-gray-200 bg-gray-50 sm:rounded-t-lg">
        <h3 class="text-lg font-semibold text-gray-900">Processing Logs</h3>
        <button
            class="inline-flex items-center p-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
            @click="$emit('close')"
            title="Close log view"
        >
          <XMarkIcon class="w-4 h-4" />
        </button>
      </div>

      <!-- Log Content -->
      <div class="flex-1 overflow-hidden">
        <div class="h-full overflow-auto p-4">
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
      </div>

      </div>
    </div>
  </div>
</template>

<script>
import moment from "moment";
import { XMarkIcon, DocumentIcon } from '@heroicons/vue/24/outline';

export default {
  name: 'LogViewModal',
  components: {
    XMarkIcon,
    DocumentIcon
  },
  props: {
    isOpen: {
      type: Boolean,
      default: false
    },
    logs: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {
      modalStyle: {
        width: '100vw',
        height: '100vh',
        left: '0',
        top: '0'
      }
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        // Prevent background scrolling when modal opens
        document.body.style.overflow = 'hidden';
        // Move modal to body to avoid parent container constraints
        this.$nextTick(() => {
          if (this.$el && this.$el.parentNode !== document.body) {
            document.body.appendChild(this.$el);
          }
        });
      } else {
        // Restore background scrolling when modal closes
        document.body.style.overflow = '';
      }
    },
  },
  mounted() {
    document.addEventListener('keydown', this.handleKeydown);
    // Set overflow style if modal is already open when component mounts
    if (this.isOpen) {
      document.body.style.overflow = 'hidden';
    }
    // Move modal to body to avoid parent container constraints
    if (this.isOpen) {
      document.body.appendChild(this.$el);
    }
  },
  beforeUnmount() {
    document.removeEventListener('keydown', this.handleKeydown);
    // Restore background scrolling when modal is closed
    document.body.style.overflow = '';
    // Remove modal from body if it was moved there
    if (this.$el && this.$el.parentNode === document.body) {
      document.body.removeChild(this.$el);
    }
  },
  methods: {
    getLevelName(level) {
      const levelMap = {
        10: 'DEBUG',
        20: 'INFO',
        30: 'WARNING',
        40: 'ERROR',
        50: 'CRITICAL'
      };
      return levelMap[level] || 'UNKNOWN';
    },
    getLevelClass(level) {
      if (level >= 40) { // ERROR or CRITICAL
        return 'bg-red-100 text-red-800';
      } else if (level >= 30) { // WARNING
        return 'bg-yellow-100 text-yellow-800';
        } else if (level >= 20) { // INFO
          return 'bg-blue-100 text-blue-700';
        } else { // DEBUG
        return 'bg-gray-100 text-gray-800';
      }
    },
    formatTimestamp(timestamp) {
      if (!timestamp) return '';
      return moment(timestamp).format('YYYY-MM-DD HH:mm:ss');
    },
    handleBackdropClick() {
      this.$emit('close');
    },
    handleKeydown(e) {
      if (e.key === 'Escape') {
        this.$emit('close');
      }
    }
  }
}
</script>

