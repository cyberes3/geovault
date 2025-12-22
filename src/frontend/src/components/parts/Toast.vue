<template>
  <TransitionGroup
    name="toast"
    tag="div"
    class="fixed bottom-0 left-0 right-0 z-50 pointer-events-none flex flex-col items-center space-y-2 p-4"
  >
    <div
      v-for="toast in toasts"
      :key="toast.id"
      :class="[
        'pointer-events-auto bg-white rounded-lg shadow-lg px-4 py-2 flex items-center space-x-2 border border-gray-200',
        'max-w-xs'
      ]"
    >
      <!-- Icon -->
      <div :class="[
        'flex-shrink-0',
        toast.type === 'error' ? 'text-red-600' : 'text-gray-600'
      ]">
        <XCircleIcon v-if="toast.type === 'error'" class="h-4 w-4" />
        <MapPinIcon v-else class="h-4 w-4" />
      </div>
      
      <!-- Message -->
      <div class="flex-1 min-w-0">
        <p v-if="!toast.html" class="text-sm text-gray-700">
          {{ toast.message }}
        </p>
        <div v-else class="text-sm text-gray-700" v-html="toast.html"></div>
      </div>
      
      <!-- Close Button -->
      <button
        v-if="toast.sticky"
        @click="removeToast(toast.id)"
        class="flex-shrink-0 inline-flex text-gray-400 hover:text-gray-600 focus:outline-none"
        title="Dismiss"
      >
        <XMarkIcon class="h-4 w-4" />
      </button>
    </div>
  </TransitionGroup>
</template>

<script>
import { XCircleIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import { MapPinIcon } from '@heroicons/vue/24/solid'


export default {
  name: 'Toast',
  components: {
    MapPinIcon,
    XCircleIcon,
    XMarkIcon
  },
  data() {
    return {
      toasts: [],
      nextId: 0
    }
  },
  methods: {
    show(message, type = 'error', options = {}) {
      // Clear existing toasts to only show one at a time
      this.toasts = []
      
      const toast = {
        id: this.nextId++,
        message,
        html: options.html || null,
        type,
        sticky: options.sticky !== false, // Default to sticky for errors
        timeout: options.timeout
      }
      
      this.toasts.push(toast)
      
      // Auto-dismiss if timeout is set
      if (toast.timeout && !toast.sticky) {
        setTimeout(() => {
          this.removeToast(toast.id)
        }, toast.timeout)
      }
      
      return toast.id
    },
    error(message, options = {}) {
      return this.show(message, 'error', { sticky: true, ...options })
    },
    success(message, options = {}) {
      return this.show(message, 'success', { sticky: false, timeout: 3000, ...options })
    },
    removeToast(id) {
      const index = this.toasts.findIndex(t => t.id === id)
      if (index > -1) {
        this.toasts.splice(index, 1)
      }
    },
    clear() {
      this.toasts = []
    }
  }
}
</script>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}

.toast-enter-from {
  opacity: 0;
  transform: translateY(20px);
}

.toast-leave-to {
  opacity: 0;
  transform: translateY(-20px);
}

.toast-move {
  transition: transform 0.3s ease;
}

/* Style links inside toast messages */
:deep(a) {
  color: #2563eb;
  text-decoration: underline;
  cursor: pointer;
}

:deep(a:hover) {
  color: #1d4ed8;
}
</style>

