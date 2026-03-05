<template>
  <div
    v-if="isOpen"
    ref="modalBackdrop"
    data-base-modal
    :class="[
      'fixed inset-0',
      onTop ? 'z-[60]' : 'z-50'
    ]"
    role="dialog"
    aria-modal="true"
    @mousedown="handleBackdropMouseDown"
    @keydown.esc="handleEscapeKey"
    tabindex="-1"
  >
    <!-- Backdrop -->
    <div class="absolute inset-0 bg-black/50 transition-opacity"></div>

    <!-- Modal panel -->
    <div class="absolute inset-0 flex items-stretch justify-stretch sm:items-center sm:justify-center">
      <div
        :class="[
          'bg-white flex flex-col w-full shadow-xl overflow-hidden',
          fullScreenMobile ? 'h-full' : 'h-[90vh] rounded-lg',
          'sm:h-[90vh] sm:rounded-lg',
          maxWidthClass
        ]"
        @click.stop
        @mousedown.stop
      >
        <!-- Header -->
        <div
          :class="[
            'flex items-center justify-between px-4 sm:px-6 py-4 border-b border-gray-200 bg-gray-50 flex-shrink-0',
            fullScreenMobile ? '' : 'rounded-t-lg',
            'sm:rounded-t-lg'
          ]"
        >
          <h3 v-if="title" class="text-lg font-medium text-gray-900">
            {{ title }}
          </h3>
          <div v-else></div>
          <button
            @click="handleClose"
            class="p-2 sm:p-1 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition-colors"
            title="Close dialog (ESC)"
          >
            <XMarkIcon class="h-6 w-6" />
          </button>
        </div>

        <!-- Content -->
        <div class="flex-1 overflow-y-auto bg-white min-h-0">
          <slot></slot>
        </div>

        <!-- Footer -->
        <div
          v-if="$slots.footer || $slots.actions || $slots['footer-left']"
          :class="[
            'bg-gray-50 px-4 sm:px-6 py-4 border-t border-gray-200 flex items-center flex-shrink-0',
            $slots['footer-left'] ? 'justify-between' : 'justify-end',
            fullScreenMobile ? '' : 'rounded-b-lg',
            'sm:rounded-b-lg'
          ]"
        >
          <div v-if="$slots['footer-left']" class="flex-1 min-w-0 mr-4">
            <slot name="footer-left"></slot>
          </div>
          <div class="flex items-center space-x-3">
            <slot name="footer"></slot>
            <slot name="actions"></slot>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { XMarkIcon } from '@heroicons/vue/24/outline'

export default {
  name: 'BaseModal',
  components: {
    XMarkIcon
  },
  props: {
    isOpen: {
      type: Boolean,
      required: true
    },
    title: {
      type: String,
      default: null
    },
    maxWidth: {
      type: String,
      default: '2xl',
      validator: (value) => ['sm', 'md', 'lg', 'xl', '2xl', '4xl', '6xl'].includes(value)
    },
    closeOnBackdrop: {
      type: Boolean,
      default: true
    },
    closeOnEscape: {
      type: Boolean,
      default: true
    },
    fullScreenMobile: {
      type: Boolean,
      default: true
    },
    onTop: {
      type: Boolean,
      default: false
    }
  },
  emits: ['close'],
  computed: {
    maxWidthClass() {
      const widthMap = {
        sm: 'sm:max-w-sm',
        md: 'sm:max-w-md',
        lg: 'sm:max-w-lg',
        xl: 'sm:max-w-xl',
        '2xl': 'sm:max-w-2xl',
        '4xl': 'sm:max-w-4xl',
        '6xl': 'sm:max-w-6xl'
      }
      return widthMap[this.maxWidth] || 'sm:max-w-2xl'
    }
  },
  watch: {
    isOpen(newVal) {
      if (newVal) {
        // Prevent background scroll
        document.body.classList.add('overflow-hidden')
        // Document-level ESC so it works when focus is inside modal content
        this._boundEscape = (e) => {
          if (e.key === 'Escape') this.handleEscapeKey(e)
        }
        document.addEventListener('keydown', this._boundEscape)
        // Move modal to body to avoid layout offsets
        this.$nextTick(() => {
          if (this.$el && this.$el.parentNode !== document.body) {
            document.body.appendChild(this.$el)
          }
          // Focus the modal backdrop for keyboard navigation
          if (this.$refs.modalBackdrop) {
            this.$refs.modalBackdrop.focus()
          }
        })
      } else {
        if (this._boundEscape) {
          document.removeEventListener('keydown', this._boundEscape)
          this._boundEscape = null
        }
        // Restore background scroll
        document.body.classList.remove('overflow-hidden')
      }
    },
    $route() {
      // Close modal when route changes
      if (this.isOpen) {
        this.handleClose()
      }
    }
  },
  mounted() {
    // If modal is already open when component mounts, add ESC listener and move to body
    if (this.isOpen) {
      this._boundEscape = (e) => {
        if (e.key === 'Escape') this.handleEscapeKey(e)
      }
      document.addEventListener('keydown', this._boundEscape)
      this.$nextTick(() => {
        if (this.$el && this.$el.parentNode !== document.body) {
          document.body.appendChild(this.$el)
        }
      })
    }
  },
  beforeUnmount() {
    if (this._boundEscape) {
      document.removeEventListener('keydown', this._boundEscape)
      this._boundEscape = null
    }
    // Clean up: restore background scroll
    document.body.classList.remove('overflow-hidden')
  },
  methods: {
    handleClose() {
      this.$emit('close')
    },
    handleBackdropMouseDown(event) {
      if (this.closeOnBackdrop && event.target === event.currentTarget) {
        this.handleClose()
      }
    },
    handleEscapeKey(event) {
      if (!this.closeOnEscape || event.key !== 'Escape') return
      // Only close if this modal is the topmost. Use all [role="dialog"] so when e.g. ColorPicker
      // is open on top of us, it is the last dialog and we don't close; first ESC closes the picker,
      // second ESC closes us.
      const dialogs = document.querySelectorAll('[role="dialog"]')
      const topmost = dialogs.length ? dialogs[dialogs.length - 1] : null
      if (this.$refs.modalBackdrop && this.$refs.modalBackdrop !== topmost) return
      this.handleClose()
    }
  }
}
</script>

