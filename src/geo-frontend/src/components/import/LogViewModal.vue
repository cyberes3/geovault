<template>
  <div
    v-if="isOpen"
    class="fixed inset-0 z-50 overflow-hidden"
    @click="handleBackdropClick"
  >
    <!-- Backdrop -->
    <div class="absolute inset-0 bg-black bg-opacity-50 transition-opacity duration-300"></div>
    
    <!-- Modal Container -->
    <div
      ref="modalContainer"
      class="absolute inset-4 bg-white rounded-lg shadow-xl flex flex-col transform transition-all duration-300 ease-out"
      :style="modalStyle"
      @click.stop
    >
      <!-- Header -->
      <div class="flex items-center justify-between p-4 border-b border-gray-200 bg-gray-50 rounded-t-lg">
        <h3 class="text-lg font-semibold text-gray-900">Processing Logs - Full View</h3>
        <button
          @click="$emit('close')"
          class="inline-flex items-center p-2 border border-gray-300 shadow-sm text-sm font-medium rounded-md text-gray-700 bg-white hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-blue-500"
        >
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
          </svg>
        </button>
      </div>
      
      <!-- Log Content -->
      <div class="flex-1 overflow-hidden">
        <div class="h-full overflow-auto p-4">
          <div class="space-y-1">
            <div
              v-for="(item, index) in logs"
              :key="`logitem-${index}`"
              class="flex items-start space-x-3 p-3 rounded-lg hover:bg-gray-50 border-l-2 border-transparent hover:border-gray-200 transition-colors"
            >
              <span class="text-xs text-gray-500 font-mono whitespace-nowrap bg-gray-100 px-2 py-1 rounded">{{ item.timestamp }}</span>
              <span class="text-sm text-gray-700 flex-1 leading-relaxed">{{ item.msg }}</span>
            </div>
            <div v-if="logs.length === 0" class="text-center py-8">
              <svg class="mx-auto h-12 w-12 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
              </svg>
              <h3 class="mt-2 text-sm font-medium text-gray-900">No logs available</h3>
              <p class="mt-1 text-sm text-gray-500">Processing logs will appear here when available.</p>
            </div>
          </div>
        </div>
      </div>
      
    </div>
  </div>
</template>

<script>
export default {
  name: 'LogViewModal',
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
        width: '90vw',
        height: '90vh',
        left: '5vw',
        top: '5vh'
      }
    }
  },
  mounted() {
    document.addEventListener('keydown', this.handleKeydown);
  },
  beforeUnmount() {
    document.removeEventListener('keydown', this.handleKeydown);
  },
  methods: {
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

