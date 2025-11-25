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
        'pointer-events-auto max-w-md w-full rounded-lg shadow-lg p-4 flex items-start space-x-3',
        toast.type === 'error' ? 'bg-red-50 border border-red-200' : 'bg-green-50 border border-green-200'
      ]"
    >
      <!-- Icon -->
      <div :class="[
        'flex-shrink-0',
        toast.type === 'error' ? 'text-red-600' : 'text-green-600'
      ]">
        <svg v-if="toast.type === 'error'" class="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
          <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" clip-rule="evenodd" />
        </svg>
        <svg v-else class="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
          <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" />
        </svg>
      </div>
      
      <!-- Message -->
      <div class="flex-1 min-w-0">
        <p :class="[
          'text-sm font-medium',
          toast.type === 'error' ? 'text-red-800' : 'text-green-800'
        ]">
          {{ toast.message }}
        </p>
      </div>
      
      <!-- Close Button -->
      <button
        v-if="toast.sticky"
        @click="removeToast(toast.id)"
        :class="[
          'flex-shrink-0 inline-flex text-gray-400 hover:text-gray-600 focus:outline-none',
          toast.type === 'error' ? 'hover:text-red-600' : 'hover:text-green-600'
        ]"
        title="Dismiss"
      >
        <svg class="h-5 w-5" fill="currentColor" viewBox="0 0 20 20">
          <path fill-rule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clip-rule="evenodd" />
        </svg>
      </button>
    </div>
  </TransitionGroup>
</template>

<script>
export default {
  name: 'Toast',
  data() {
    return {
      toasts: [],
      nextId: 0
    }
  },
  methods: {
    show(message, type = 'error', options = {}) {
      const toast = {
        id: this.nextId++,
        message,
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
</style>

