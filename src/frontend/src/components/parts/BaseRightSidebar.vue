<template>
  <div
    v-if="isOpen"
    ref="sidebarBackdrop"
    data-base-right-sidebar
    class="fixed inset-0 z-50"
    role="dialog"
    aria-modal="true"
    tabindex="-1"
    @mousedown="handleBackdropMouseDown"
    @keydown="handleEscapeKey"
  >
    <!-- Backdrop -->
    <div
      ref="backdrop"
      class="absolute inset-0 bg-black/50 transition-opacity duration-200"
      aria-hidden="true"
    />

    <!-- Panel: full page on mobile, right sidebar on sm+ -->
    <Transition name="sidebar-slide">
      <div
        v-if="isOpen"
        key="panel"
        class="fixed right-0 top-0 bottom-0 w-full h-full sm:w-[28rem] sm:h-full flex flex-col bg-white shadow-xl overflow-hidden"
        @click.stop
        @mousedown.stop
      >
        <!-- Header -->
        <div class="flex items-center justify-between px-4 sm:px-6 py-4 border-b border-gray-200 bg-gray-50 flex-shrink-0">
          <h3 v-if="title" class="text-lg font-medium text-gray-900">
            {{ title }}
          </h3>
          <div v-else></div>
          <button
            type="button"
            @click="handleClose"
            class="p-2 sm:p-1 min-w-[44px] min-h-[44px] sm:min-w-0 sm:min-h-0 text-gray-400 hover:text-gray-600 focus:outline-none focus:text-gray-600 transition-colors"
            title="Close (ESC)"
          >
            <XMarkIcon class="h-6 w-6" />
          </button>
        </div>

        <!-- Content -->
        <div ref="contentScroll" class="flex-1 overflow-y-auto bg-white min-h-0">
          <slot />
        </div>

        <!-- Footer -->
        <div
          v-if="$slots.actions"
          class="bg-gray-50 px-4 sm:px-6 py-4 border-t border-gray-200 flex items-center justify-end flex-shrink-0"
        >
          <div class="flex items-center space-x-3">
            <slot name="actions" />
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';
import { XMarkIcon } from '@heroicons/vue/24/outline';

export default defineComponent({
  name: 'BaseRightSidebar',
  components: { XMarkIcon },
  props: {
    isOpen: {
      type: Boolean,
      required: true,
    },
    title: {
      type: String,
      default: null,
    },
    closeOnBackdrop: {
      type: Boolean,
      default: true,
    },
    closeOnEscape: {
      type: Boolean,
      default: true,
    },
  },
  emits: ['close'],
  data() {
    return {
      boundEscapeHandler: null as ((e: KeyboardEvent) => void) | null
    };
  },
  watch: {
    isOpen(newVal: boolean) {
      if (newVal) {
        document.body.classList.add('overflow-hidden');
        this.boundEscapeHandler = (e: KeyboardEvent) => {
          if (e.key === 'Escape') this.handleEscapeKey(e);
        };
        document.addEventListener('keydown', this.boundEscapeHandler);
        void this.$nextTick(() => {
          if (this.$el && (this.$el as Node).parentNode !== document.body) {
            document.body.appendChild(this.$el as Node);
          }
          const sidebarBackdrop = this.$refs.sidebarBackdrop as HTMLElement | undefined;
          sidebarBackdrop?.focus();
          const contentScroll = this.$refs.contentScroll as HTMLElement | undefined;
          if (contentScroll) {
            contentScroll.scrollTop = 0;
          }
        });
      } else {
        if (this.boundEscapeHandler) {
          document.removeEventListener('keydown', this.boundEscapeHandler);
          this.boundEscapeHandler = null;
        }
        document.body.classList.remove('overflow-hidden');
      }
    },
    $route() {
      if (this.isOpen) this.handleClose();
    },
  },
  mounted() {
    if (this.isOpen) {
      this.boundEscapeHandler = (e: KeyboardEvent) => {
        if (e.key === 'Escape') this.handleEscapeKey(e);
      };
      document.addEventListener('keydown', this.boundEscapeHandler);
      void this.$nextTick(() => {
        if (this.$el && (this.$el as Node).parentNode !== document.body) {
          document.body.appendChild(this.$el as Node);
        }
        const contentScroll = this.$refs.contentScroll as HTMLElement | undefined;
        if (contentScroll) {
          contentScroll.scrollTop = 0;
        }
      });
    }
  },
  beforeUnmount() {
    if (this.boundEscapeHandler) {
      document.removeEventListener('keydown', this.boundEscapeHandler);
      this.boundEscapeHandler = null;
    }
    document.body.classList.remove('overflow-hidden');
  },
  methods: {
    handleClose() {
      this.$emit('close');
    },
    handleBackdropMouseDown(event: MouseEvent) {
      if (!this.closeOnBackdrop) return;
      if (event.target === this.$refs.sidebarBackdrop || event.target === this.$refs.backdrop) {
        this.handleClose();
      }
    },
    handleEscapeKey(event: KeyboardEvent) {
      if (!this.closeOnEscape || event.key !== 'Escape') return;
      const dialogs = document.querySelectorAll('[role="dialog"]');
      const topmost = dialogs.length ? dialogs[dialogs.length - 1] : null;
      if (this.$refs.sidebarBackdrop && this.$refs.sidebarBackdrop !== topmost) return;
      this.handleClose();
    },
  },
});
</script>

<style scoped>
.sidebar-slide-enter-active,
.sidebar-slide-leave-active {
  transition: transform 0.2s ease-out;
}
.sidebar-slide-enter-from,
.sidebar-slide-leave-to {
  transform: translateX(100%);
}
.sidebar-slide-enter-to,
.sidebar-slide-leave-from {
  transform: translateX(0);
}
</style>
