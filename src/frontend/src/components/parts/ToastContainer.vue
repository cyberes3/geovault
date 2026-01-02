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
        'pointer-events-auto rounded-lg shadow-lg px-4 py-3 flex items-center space-x-3 border max-w-md',
        getToastClasses(toast.type)
      ]"
    >
      <!-- Icon -->
      <div class="flex-shrink-0">
        <CheckCircleIcon v-if="toast.type === 'success'" class="h-5 w-5 text-green-600" />
        <XCircleIcon v-else-if="toast.type === 'error'" class="h-5 w-5 text-red-600" />
        <ExclamationTriangleIcon v-else-if="toast.type === 'warning'" class="h-5 w-5 text-yellow-600" />
        <InformationCircleIcon v-else class="h-5 w-5 text-blue-600" />
      </div>
      
      <!-- Message -->
      <div class="flex-1 min-w-0">
        <p v-if="!toast.html" class="text-sm font-medium" :class="getTextColor(toast.type)">
          {{ toast.message }}
        </p>
        <div v-else class="text-sm font-medium" :class="getTextColor(toast.type)" v-html="toast.html"></div>
      </div>
      
      <!-- Close Button -->
      <button
        v-if="toast.dismissible"
        @click="removeToast(toast.id)"
        class="flex-shrink-0 inline-flex text-gray-400 hover:text-gray-600 focus:outline-none transition-colors"
        title="Dismiss"
      >
        <XMarkIcon class="h-5 w-5" />
      </button>
    </div>
  </TransitionGroup>
</template>

<script>
import { 
  CheckCircleIcon, 
  XCircleIcon, 
  ExclamationTriangleIcon,
  InformationCircleIcon,
  XMarkIcon 
} from '@heroicons/vue/24/outline'
import { toast } from '@/utils/toast'

export default {
  name: 'ToastContainer',
  components: {
    CheckCircleIcon,
    XCircleIcon,
    ExclamationTriangleIcon,
    InformationCircleIcon,
    XMarkIcon
  },
  data() {
    return {
      toasts: []
    }
  },
  mounted() {
    // Subscribe to toast events
    this.unsubscribe = toast.subscribe((toastData) => {
      this.addToast(toastData)
    })
  },
  beforeUnmount() {
    // Clean up subscription
    if (this.unsubscribe) {
      this.unsubscribe()
    }
  },
  methods: {
    addToast(toastData) {
      this.toasts.push(toastData)
      
      // Auto-dismiss if duration is set
      if (toastData.duration) {
        setTimeout(() => {
          this.removeToast(toastData.id)
        }, toastData.duration)
      }
    },
    removeToast(id) {
      const index = this.toasts.findIndex(t => t.id === id)
      if (index > -1) {
        this.toasts.splice(index, 1)
      }
    },
    getToastClasses(type) {
      const baseClasses = 'bg-white border-gray-200'
      const typeClasses = {
        success: 'bg-green-50 border-green-200',
        error: 'bg-red-50 border-red-200',
        warning: 'bg-yellow-50 border-yellow-200',
        info: 'bg-blue-50 border-blue-200'
      }
      return typeClasses[type] || baseClasses
    },
    getTextColor(type) {
      const colors = {
        success: 'text-green-800',
        error: 'text-red-800',
        warning: 'text-yellow-800',
        info: 'text-blue-800'
      }
      return colors[type] || 'text-gray-800'
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

