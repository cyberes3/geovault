<template>
  <Teleport :to="teleportTarget">
    <!-- When locked to container, wrap in a layer that fills the map column so panel height = map height. pointer-events-none so map can pan/zoom; panel has pointer-events-auto. -->
    <div
      v-if="isContainerMode"
      class="fixed inset-x-0 bottom-0 top-16 sm:absolute sm:inset-0 overflow-hidden flex justify-end pointer-events-none z-50"
    >
      <div
        ref="panel"
        data-live-track-sidebar
        :class="panelClasses"
        role="dialog"
        aria-modal="true"
        aria-labelledby="live-track-sidebar-title"
        tabindex="-1"
        @keydown="handleEscapeKey"
      >
        <div class="flex items-center justify-between px-4 sm:px-5 py-3 border-b border-gray-200 bg-white flex-shrink-0">
          <h2 id="live-track-sidebar-title" v-if="title" class="text-lg font-semibold text-gray-900 truncate min-w-0">
            {{ title }}
          </h2>
          <div v-else class="min-w-0" />
          <button
            type="button"
            @click="requestClose"
            class="p-2 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 focus:outline-none focus:text-gray-600 focus:bg-gray-100 transition-colors flex-shrink-0"
            title="Close (ESC)"
          >
            <XMarkIcon class="h-5 w-5" />
          </button>
        </div>
        <div ref="contentScroll" class="flex-1 overflow-y-auto min-h-0">
          <slot />
        </div>
        <div
          v-if="$slots.actions"
          class="px-4 sm:px-5 py-3 border-t border-gray-200 bg-gray-50 flex items-center justify-end flex-shrink-0"
        >
          <div class="flex items-center gap-3">
            <slot name="actions" />
          </div>
        </div>
      </div>
    </div>
    <div
      v-else
      ref="panel"
      data-live-track-sidebar
      :class="panelClasses"
      role="dialog"
      aria-modal="true"
      aria-labelledby="live-track-sidebar-title"
      tabindex="-1"
      @keydown="handleEscapeKey"
    >
      <div class="flex items-center justify-between px-4 sm:px-5 py-3 border-b border-gray-200 bg-white flex-shrink-0">
        <h2 id="live-track-sidebar-title" v-if="title" class="text-lg font-semibold text-gray-900 truncate min-w-0">
          {{ title }}
        </h2>
        <div v-else class="min-w-0" />
        <button
          type="button"
          @click="requestClose"
          class="p-2 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 focus:outline-none focus:text-gray-600 focus:bg-gray-100 transition-colors flex-shrink-0"
          title="Close (ESC)"
        >
          <XMarkIcon class="h-5 w-5" />
        </button>
      </div>
      <div ref="contentScroll" class="flex-1 overflow-y-auto min-h-0">
        <slot />
      </div>
      <div
        v-if="$slots.actions"
        class="px-4 sm:px-5 py-3 border-t border-gray-200 bg-gray-50 flex items-center justify-end flex-shrink-0"
      >
        <div class="flex items-center gap-3">
          <slot name="actions" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script lang="ts">
import { defineComponent, ref, computed, onMounted, onBeforeUnmount, nextTick, type PropType } from 'vue';
import { XMarkIcon } from '@heroicons/vue/24/outline';

type ContainerRefLike = { value: HTMLElement | null } | HTMLElement | null;

export default defineComponent({
  name: 'LiveTrackSidebar',
  components: { XMarkIcon },
  props: {
    title: {
      type: String,
      default: null,
    },
    /** When set, sidebar is teleported into this element and positioned absolute (locked to container height). Otherwise teleported to body and fixed (full viewport). */
    containerRef: {
      type: Object as PropType<ContainerRefLike>,
      default: null,
    },
    /** When true, disable all CSS transitions/animations inside sidebar subtree. */
    disableAnimations: {
      type: Boolean,
      default: false,
    },
  },
  emits: ['close'],
  setup(props, { emit }) {
    const contentScroll = ref<HTMLElement | null>(null);
    const panel = ref<HTMLElement | null>(null);

    // Accept either a Vue ref object ({ value: HTMLElement }) or a direct HTMLElement.
    const resolvedContainer = computed((): HTMLElement | null => {
      const c = props.containerRef;
      if (c == null) return null;
      return 'value' in c ? c.value : c;
    });
    const teleportTarget = computed((): HTMLElement | string => resolvedContainer.value ?? 'body');
    const isContainerMode = computed((): boolean => resolvedContainer.value != null && teleportTarget.value !== 'body');
    const panelClasses = computed((): string => {
      const base = 'live-track-sidebar-panel z-50 flex flex-col bg-white border-l border-gray-200 overflow-hidden pointer-events-auto';
      const animationClass = props.disableAnimations ? 'live-track-sidebar--no-animations' : '';
      if (isContainerMode.value) {
        return `${base} w-full sm:w-[28rem] h-full ${animationClass}`.trim();
      }
      return `${base} fixed inset-x-0 top-16 bottom-0 w-full sm:inset-y-0 sm:right-0 sm:left-auto sm:top-0 sm:w-[28rem] sm:h-full ${animationClass}`.trim();
    });

    function requestClose(): void {
      emit('close');
    }

    function handleEscapeKey(e: KeyboardEvent): void {
      if (e.key !== 'Escape') return;
      const dialogs = document.querySelectorAll('[data-live-track-sidebar]');
      const topmost = dialogs.length ? dialogs[dialogs.length - 1] : null;
      if (panel.value && panel.value !== topmost) return;
      requestClose();
    }

    let boundEscape: ((e: KeyboardEvent) => void) | null = null;

    function addEscapeListener(): void {
      boundEscape = (e: KeyboardEvent) => { handleEscapeKey(e); };
      document.addEventListener('keydown', boundEscape);
    }

    function removeEscapeListener(): void {
      if (boundEscape) {
        document.removeEventListener('keydown', boundEscape);
        boundEscape = null;
      }
    }

    onMounted(() => {
      if (!props.containerRef) document.body.classList.add('overflow-hidden');
      addEscapeListener();
      void nextTick(() => {
        if (contentScroll.value) contentScroll.value.scrollTop = 0;
        if (panel.value) panel.value.focus();
      });
    });

    onBeforeUnmount(() => {
      removeEscapeListener();
      if (!props.containerRef) document.body.classList.remove('overflow-hidden');
    });

    return {
      contentScroll,
      panel,
      teleportTarget,
      isContainerMode,
      panelClasses,
      requestClose,
      handleEscapeKey,
    };
  },
});
</script>

<style scoped>
.live-track-sidebar-panel {
  box-shadow: -4px 0 15px -3px rgba(0, 0, 0, 0.08), -2px 0 6px -2px rgba(0, 0, 0, 0.04);
}

.live-track-sidebar--no-animations,
.live-track-sidebar--no-animations * {
  transition: none !important;
  animation: none !important;
}
</style>
